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
    yield runtime, artifact_id, digest, payload
    store.close()


@pytest.mark.asyncio
async def test_artifact_http_range_etag_and_head(artifact_runtime):
    runtime, artifact_id, digest, payload = artifact_runtime
    app = web.Application()
    app.router.add_get("/artifacts/{artifact_id}", runtime.artifact_handler)
    server = TestServer(app)
    await server.start_server()
    try:
        async with aiohttp.ClientSession() as client:
            url = f"http://{server.host}:{server.port}/artifacts/{artifact_id}"
            response = await client.get(url, headers={"Range": "bytes=2-5"})
            assert response.status == 206
            assert await response.read() == payload[2:6]
            assert response.headers["Content-Range"] == f"bytes 2-5/{len(payload)}"
            assert response.headers["Content-Length"] == "4"
            assert response.headers["Accept-Ranges"] == "bytes"
            assert response.headers["ETag"] == f'"{digest}"'
            assert response.headers["Content-Encoding"] == "identity"

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
            assert resumed is False
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
        assert resumed is True
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
