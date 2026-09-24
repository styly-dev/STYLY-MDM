import asyncio
import hashlib
import uuid

import aiohttp
import pytest
from aiohttp import web
from aiohttp.test_utils import TestServer

from styly_mdm.push_artifacts import ArtifactStore
from styly_mdm.push_job_store import PushJobStore, now_ms
from styly_mdm.push_job_manager import PushJobManager
from styly_mdm.push_jobs import DeviceState, ProtocolMode, canonicalize_create_request
from styly_mdm.push_scheduler import PushScheduler
from styly_mdm.push_runtime import PushRuntime
from styly_mdm.push_transfer_leases import PushTransferLeases
from styly_mdm.transfer_registry import TransferKey


@pytest.fixture
def artifact_runtime(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    artifacts = ArtifactStore(tmp_path)
    artifact_id = str(uuid.uuid4())
    payload = b"0123456789"
    storage_name = f"{artifact_id}.zip"
    (artifacts.artifact_root / storage_name).write_bytes(payload)
    digest = hashlib.sha256(payload).hexdigest()
    store._call_sync(
        lambda conn: conn.execute(
            "INSERT INTO push_artifacts(artifact_id, storage_name, display_filename, "
            "byte_size, sha256, entry_count, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            (artifact_id, storage_name, "x.zip", len(payload), digest, 1, now_ms()),
        )
    )
    runtime = object.__new__(PushRuntime)
    runtime.store = store
    runtime.artifacts = artifacts
    runtime.leases = PushTransferLeases()
    yield runtime, artifact_id, digest, payload
    store.close()


@pytest.mark.asyncio
async def test_artifact_http_range_etag_and_head(artifact_runtime):
    runtime, artifact_id, digest, payload = artifact_runtime
    lease = runtime.leases.issue(TransferKey("push", "D1", "job", 1), artifact_id)
    app = web.Application()
    app.router.add_get("/artifacts/{artifact_id}", runtime.artifact_handler)
    server = TestServer(app)
    await server.start_server()
    try:
        async with aiohttp.ClientSession() as client:
            url = f"http://{server.host}:{server.port}/artifacts/{artifact_id}?lease={lease}"
            response = await client.get(url, headers={"Accept-Encoding": "identity"})
            assert response.status == 200
            assert "Content-Encoding" not in response.headers
            assert await response.read() == payload
            response = await client.get(url, headers={"Range": "bytes=2-5"})
            assert response.status == 206
            assert await response.read() == payload[2:6]
            assert response.headers["Content-Range"] == f"bytes 2-5/{len(payload)}"
            assert response.headers["Content-Length"] == "4"
            assert response.headers["Accept-Ranges"] == "bytes"
            assert response.headers["ETag"] == f'"{digest}"'
            assert "Content-Encoding" not in response.headers

            response = await client.get(url, headers={"If-Match": '"wrong"'})
            assert response.status == 412
            assert await response.read() == b""

            response = await client.get(
                url, headers={"Range": "bytes=99-", "If-Match": f'"{digest}"'}
            )
            assert response.status == 416
            assert response.headers["Content-Range"] == f"bytes */{len(payload)}"
            assert await response.read() == b""

            for invalid_range in ("bytes=abc", "bytes=0-1,4-5"):
                response = await client.get(
                    url,
                    headers={"Range": invalid_range, "If-Match": f'"{digest}"'},
                )
                assert response.status == 200
                assert await response.read() == payload
                assert "Content-Range" not in response.headers

            response = await client.head(
                url, headers={"Range": "bytes=4-", "If-Match": f'"{digest}"'}
            )
            assert response.status == 206
            assert response.headers["Content-Length"] == str(len(payload) - 4)
            assert await response.read() == b""
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_artifact_http_requires_a_scoped_lease(artifact_runtime):
    runtime, artifact_id, _digest, _payload = artifact_runtime
    app = web.Application()
    app.router.add_get("/artifacts/{artifact_id}", runtime.artifact_handler)
    server = TestServer(app)
    await server.start_server()
    try:
        async with aiohttp.ClientSession() as client:
            url = f"http://{server.host}:{server.port}/artifacts/{artifact_id}"
            response = await client.get(url)
            assert response.status == 409
            assert response.headers["X-Push-Lease-Status"] == "revoked"
            await response.read()

            response = await client.get(f"{url}?lease=unknown-token")
            assert response.status == 409
            assert response.headers["X-Push-Lease-Status"] == "revoked"
            await response.read()

            key = TransferKey("push", "D1", "job", 1)
            revoked_lease = runtime.leases.issue(key, artifact_id)
            runtime.leases.revoke_now(key)
            response = await client.get(f"{url}?lease={revoked_lease}")
            assert response.status == 409
            assert response.headers["X-Push-Lease-Status"] == "revoked"
            await response.read()

            other_artifact_lease = runtime.leases.issue(
                TransferKey("push", "D1", "other-job", 1), "some-other-artifact"
            )
            response = await client.get(f"{url}?lease={other_artifact_lease}")
            assert response.status == 409
            assert response.headers["X-Push-Lease-Status"] == "revoked"
            await response.read()
    finally:
        await server.close()


@pytest.mark.asyncio
async def test_artifact_http_range_reconnect_replaces_busy_lease(artifact_runtime):
    runtime, artifact_id, _digest, payload = artifact_runtime
    lease = runtime.leases.issue(TransferKey("push", "D1", "job", 1), artifact_id)
    started = asyncio.Event()
    stopped = asyncio.Event()
    calls = 0

    async def blocked_serve(request, _token):
        nonlocal calls
        if request.method == "HEAD":
            return web.Response(headers={"Content-Length": str(len(payload))})
        calls += 1
        if calls == 1:
            started.set()
            try:
                await asyncio.Future()
            finally:
                stopped.set()
        assert request.headers["Range"] == "bytes=5-"
        return web.Response(
            status=206,
            body=payload[5:],
            headers={"Content-Range": f"bytes 5-{len(payload) - 1}/{len(payload)}"},
        )

    runtime._serve_artifact = blocked_serve
    app = web.Application()
    app.router.add_get("/artifacts/{artifact_id}", runtime.artifact_handler)
    server = TestServer(app)
    await server.start_server()
    old_writer = None
    try:
        old_reader, old_writer = await asyncio.open_connection(server.host, server.port)
        old_writer.write(
            f"GET /artifacts/{artifact_id}?lease={lease} HTTP/1.1\r\n"
            f"Host: {server.host}:{server.port}\r\n\r\n".encode()
        )
        await old_writer.drain()
        await asyncio.wait_for(started.wait(), timeout=1)
        async with aiohttp.ClientSession() as client:
            url = f"http://{server.host}:{server.port}/artifacts/{artifact_id}?lease={lease}"
            head = await asyncio.wait_for(client.head(url), timeout=1)
            assert head.status == 200
            assert not stopped.is_set()
            response = await asyncio.wait_for(
                client.get(url, headers={"Range": "bytes=5-"}), timeout=1
            )
            assert stopped.is_set()
            assert response.status == 206
            assert await response.read() == payload[5:]
        assert await asyncio.wait_for(old_reader.read(), timeout=1) == b""
    finally:
        if old_writer is not None:
            old_writer.close()
            await old_writer.wait_closed()
        await server.close()


@pytest.mark.asyncio
async def test_offline_cancel_revokes_http_lease_before_next_request(tmp_path):
    from styly_mdm.transfer_registry import TransferRegistry
    from test_push_operator_controls import admin_action, operator_runtime
    from test_push_cancellation import CAPS
    from test_push_review_connection import downloading_job

    store = PushJobStore(tmp_path / "cancel.sqlite3")
    manager = PushJobManager(store)
    server = None
    old_writer = None
    try:
        active = await downloading_job(store, manager, CAPS)
        runtime, _session, admin = operator_runtime(manager, tmp_path)
        runtime.leases = PushTransferLeases()
        runtime.transfers = TransferRegistry(runtime.leases.revoke_now)
        key = TransferKey("push", "D1", active["job_id"], 1)
        token = runtime.leases.issue(key, active["artifact"]["artifact_id"])
        future = asyncio.get_running_loop().create_future()
        runtime.transfers.register(key, future)
        started = asyncio.Event()
        stopped = asyncio.Event()

        async def blocked_stream(_request, _token):
            started.set()
            try:
                await asyncio.Future()
            finally:
                stopped.set()

        runtime._serve_artifact = blocked_stream
        app = web.Application()
        app.router.add_get("/artifacts/{artifact_id}", runtime.artifact_handler)
        server = TestServer(app)
        await server.start_server()
        old_reader, old_writer = await asyncio.open_connection(server.host, server.port)
        old_writer.write(
            f"GET /artifacts/{active['artifact']['artifact_id']}?lease={token} HTTP/1.1\r\n"
            f"Host: {server.host}:{server.port}\r\n\r\n".encode()
        )
        await old_writer.drain()
        await asyncio.wait_for(started.wait(), timeout=1)
        await manager.mark_reconciling(active["job_id"], "D1", expected={DeviceState.DOWNLOADING},
                                       reason="device_disconnect", deadline=now_ms() + 60_000)
        runtime.sessions.clear()
        await admin_action(runtime, admin, "CANCEL_PUSH_JOB", active["job_id"], target_devices=["D1"])
        assert stopped.is_set()
        assert await asyncio.wait_for(old_reader.read(), timeout=1) == b""
        assert future.result() == "cancelled"
        assert runtime.leases.token(key) is None
        async with aiohttp.ClientSession() as client:
            url = (f"http://{server.host}:{server.port}/artifacts/"
                   f"{active['artifact']['artifact_id']}?lease={token}")
            response = await client.get(url)
            assert response.status == 409
            assert response.headers["X-Push-Lease-Status"] == "revoked"
            await response.read()
    finally:
        if old_writer is not None:
            old_writer.close()
            await old_writer.wait_closed()
        if server is not None:
            await server.close()
        store.close()


def test_artifact_gc_keeps_tombstone_for_deleted_identity(artifact_runtime):
    runtime, artifact_id, _digest, _payload = artifact_runtime
    store = runtime.store
    job_id = str(uuid.uuid4())
    timestamp = now_ms()

    def insert_job(conn):
        conn.execute(
            """
            INSERT INTO push_jobs(
                job_id, client_request_id, request_fingerprint, revision, state,
                mode, dest_path, source_label, declared_file_count, declared_total_bytes,
                artifact_id, created_at, create_expires_at, updated_at, terminal_at
            ) VALUES (?, ?, ?, 1, 'succeeded', 'push', '/sdcard/A', 'x', 1, 1,
                      ?, ?, ?, ?, ?)
            """,
            (job_id, str(uuid.uuid4()), "f" * 64, artifact_id, timestamp,
             timestamp, timestamp, timestamp),
        )
        conn.execute(
            """
            INSERT INTO push_job_devices(
                job_id, device_id, enqueue_seq, target_ordinal, protocol_mode,
                create_capability_snapshot_json, state, updated_at, terminal_at
            ) VALUES (?, 'D1', 1, 0, 'job_v1', '[]', 'succeeded', ?, ?)
            """,
            (job_id, timestamp, timestamp),
        )

    store._call_sync(insert_job)
    removed = store.gc_artifacts_sync(
        runtime.artifacts.artifact_root,
        retry_window_ms=0,
        timestamp=timestamp + 1,
    )
    assert removed == [artifact_id]
    record = store._call_sync(
        lambda conn: dict(
            conn.execute(
                "SELECT retention_state FROM push_artifacts WHERE artifact_id=?",
                (artifact_id,),
            ).fetchone()
        )
    )
    assert record["retention_state"] == "deleted"
    assert not (runtime.artifacts.artifact_root / f"{artifact_id}.zip").exists()


@pytest.mark.asyncio
async def test_resumable_replay_keeps_immutable_assignment_revision(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    request = canonicalize_create_request(
        {
            "client_request_id": str(uuid.uuid4()),
            "target_devices": ["D1"],
            "mode": "push",
            "dest_path": "/sdcard/A",
            "source": {"display_name": "x", "declared_file_count": 1, "declared_total_bytes": 1},
        }
    )
    try:
        _, created = await store.create_job(
            request, {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1", "push_resume_v1"})}, 1000
        )
        job_id = created["job_id"]
        await store.start_upload(job_id)
        await store.mark_packaging(job_id, 1, 1)
        artifact_id = str(uuid.uuid4())
        await store.publish_artifact(
            job_id,
            {
                "artifact_id": artifact_id,
                "storage_name": f"{artifact_id}.zip",
                "display_filename": "x.zip",
                "byte_size": 10,
                "sha256": "a" * 64,
                "entry_count": 1,
            },
        )
        await store.enable_dispatch(job_id)
        assignment = await manager.claim_next(["D1"])
        assert assignment is not None
        await manager.prepare_dispatch(
            job_id,
            "D1",
            protocol_mode=ProtocolMode.JOB_V1,
            live_capabilities={"push_job_id_v1", "push_resume_v1"},
            accept_deadline=now_ms() + 1000,
        )
        first = await manager.assignment(job_id, "D1")
        assert first is not None
        immutable_revision = first["dispatch_revision"]
        await manager.transition_device(
            job_id,
            "D1",
            expected={DeviceState.DISPATCHING},
            target=DeviceState.RECONCILING,
        )
        for rejected in (
            {"artifact_id": str(uuid.uuid4()), "dispatch_revision": immutable_revision, "validated_offset": 3},
            {"artifact_id": artifact_id, "dispatch_revision": immutable_revision + 1, "validated_offset": 3},
            {"artifact_id": artifact_id, "dispatch_revision": immutable_revision, "validated_offset": 11},
            {"artifact_id": artifact_id, "dispatch_revision": immutable_revision, "validated_offset": True},
        ):
            resumed, rejected_snapshot = await manager.resume_interrupted(
                job_id,
                "D1",
                attempt=1,
                **rejected,
            )
            assert resumed == "rejected"
            assert rejected_snapshot["devices"]["D1"]["state"] == "reconciling"
        store._call_sync(
            lambda conn: (
                conn.execute(
                    "UPDATE push_jobs SET dispatch_enabled=0, "
                    "dispatch_paused_reason='server_restart' WHERE job_id=?",
                    (job_id,),
                ),
                conn.commit(),
            )
        )
        resumed, snapshot = await manager.resume_interrupted(
            job_id,
            "D1",
            attempt=1,
            artifact_id=artifact_id,
            dispatch_revision=immutable_revision,
            validated_offset=3,
        )
        assert resumed == "requeued"
        assert snapshot["dispatch_enabled"] is False
        assert snapshot["dispatch_paused_reason"] == "server_restart"
        assert await manager.claim_next(["D1"]) is None
        await manager.enable_dispatch(job_id)
        assert await manager.claim_next(["D1"]) is not None
        await manager.prepare_dispatch(
            job_id,
            "D1",
            protocol_mode=ProtocolMode.JOB_V1,
            live_capabilities={"push_job_id_v1", "push_resume_v1"},
            accept_deadline=now_ms() + 1000,
        )
        replayed = await manager.get_snapshot(job_id)
        command = PushScheduler._command(replayed, "D1", ProtocolMode.JOB_V1, "http://server")
        assert command["revision"] == immutable_revision
        assert replayed["revision"] > immutable_revision
        assert replayed["devices"]["D1"]["validated_offset"] == 3
    finally:
        store.close()
