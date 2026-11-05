"""Retry dependencies retain revision and publication guarantees across commits."""
import asyncio
import threading
import uuid

import pytest
from aiohttp.test_utils import TestClient, TestServer

from server_test_helpers import reset_server_state
from styly_mdm import server
from styly_mdm.push_jobs import DeviceState, JobState, ProtocolMode, canonicalize_create_request
from test_push_retry_failed import completed, manager
from ws_test_helpers import receive_type


async def newer_job(store, original, *, destination=None, request_id=None):
    request = canonicalize_create_request({
        "client_request_id": request_id or str(uuid.uuid4()),
        "target_devices": ["failed"], "mode": "sync",
        "dest_path": destination or original["dest_path"],
        "source": {"display_name": "newer", "declared_file_count": 1,
                   "declared_total_bytes": 4},
    })
    return await store.create_job(request, {
        "failed": (ProtocolMode.JOB_V1, ["push_job_id_v1", "push_resume_v1"]),
    }, 600_000)


@pytest.mark.asyncio
@pytest.mark.parametrize("ending", ["upload_failure", "expiry", "restart", "cancel", "offline", "transition"])
async def test_retry_dependency_revised_and_published_when_newer_work_ends(manager, tmp_path, ending):
    store = manager.store
    original = await completed(manager, tmp_path)
    _, newer = await newer_job(store, original)
    changed, = await store.drain_retry_publications()
    assert changed["job_id"] == original["job_id"]
    assert changed["revision"] > original["revision"]
    assert changed["devices"]["failed"]["retryable"] is False
    assert changed["devices"]["interrupted"]["retryable"] is True
    job_id = newer["job_id"]
    if ending == "upload_failure":
        await store.fail_pre_dispatch(job_id, JobState.FAILED, "upload_failed", "Upload failed")
    elif ending == "expiry":
        await store._call(lambda conn: conn.execute(
            "UPDATE push_jobs SET create_expires_at=0 WHERE job_id=?", (job_id,)))
        await store.expire_created(job_id)
    elif ending == "restart":
        await store.start_upload(job_id)
        store.recover_startup_sync(accept_reconciliation_timeout_ms=1000, reconciliation_timeout_ms=1000)
    else:
        await store.start_upload(job_id)
        await store.mark_packaging(job_id, 1, 4)
        artifact_id = str(uuid.uuid4())
        await store.publish_artifact(job_id, {
            "artifact_id": artifact_id, "storage_name": artifact_id + ".zip",
            "display_filename": "newer.zip", "byte_size": 4,
            "sha256": "b" * 64, "entry_count": 1,
        })
        if ending == "cancel":
            await store.enable_dispatch(job_id)
            store.recover_startup_sync(accept_reconciliation_timeout_ms=1000, reconciliation_timeout_ms=1000)
            await manager.cancel_interrupted(job_id, "failed")
        elif ending == "offline":
            await store.enable_dispatch(job_id)
            await manager.settle_offline_before_dispatch("failed", job_id)
        else:
            await manager.transition_device(job_id, "failed", expected={DeviceState.QUEUED},
                                            target=DeviceState.FAILED)
    restored = await store.get_snapshot(original["job_id"])
    assert restored["revision"] > changed["revision"]
    assert restored["devices"]["failed"]["retryable"] is True
    updates = await store.drain_retry_publications()
    assert next(job for job in updates if job["job_id"] == original["job_id"]) == restored


@pytest.mark.asyncio
async def test_other_destination_and_idempotent_creation_do_not_revise_dependencies(manager, tmp_path):
    store = manager.store
    original = await completed(manager, tmp_path)
    await newer_job(store, original, destination="/sdcard/STYLY/other")
    assert await store.drain_retry_publications() == []
    assert (await store.get_snapshot(original["job_id"]))["revision"] == original["revision"]
    request_id = str(uuid.uuid4())
    await newer_job(store, original, request_id=request_id)
    changed, = await store.drain_retry_publications()
    created, _ = await newer_job(store, original, request_id=request_id)
    assert not created
    assert await store.drain_retry_publications() == []
    assert await store.get_snapshot(original["job_id"]) == changed


@pytest.mark.asyncio
@pytest.mark.parametrize("reason", ["cancelled", "artifact_expired", "retried", "superseded"])
async def test_unchanged_old_history_is_not_revised_or_republished(manager, tmp_path, reason):
    store = manager.store
    original = await completed(manager, tmp_path)
    if reason == "cancelled":
        await store._call(lambda conn: conn.execute(
            "UPDATE push_job_devices SET failure_code='cancelled' WHERE job_id=? AND device_id='failed'",
            (original["job_id"],)))
    elif reason == "artifact_expired":
        await store._call(lambda conn: conn.execute(
            "UPDATE push_artifacts SET retention_state='deleted' WHERE artifact_id=?",
            (original["artifact"]["artifact_id"],)))
    elif reason == "retried":
        await manager.retry_failed(original["job_id"], str(uuid.uuid4()),
                                   artifact_root=tmp_path, online_devices={"failed"})
    else:
        await newer_job(store, original)
    await store.drain_retry_publications()
    before = await store.get_snapshot(original["job_id"])
    _, newer = await newer_job(store, original)
    assert await store.drain_retry_publications() == []
    assert await store.get_snapshot(original["job_id"]) == before
    await store.fail_pre_dispatch(newer["job_id"], JobState.FAILED, "upload_failed", "Upload failed")
    assert await store.drain_retry_publications() == []
    assert await store.get_snapshot(original["job_id"]) == before


@pytest.mark.asyncio
async def test_only_last_pending_superseder_restores_old_retry(manager, tmp_path):
    store = manager.store
    original = await completed(manager, tmp_path)
    _, first = await newer_job(store, original)
    paused, = await store.drain_retry_publications()
    _, second = await newer_job(store, original)
    assert await store.drain_retry_publications() == []
    await store.fail_pre_dispatch(first["job_id"], JobState.FAILED, "upload_failed", "Upload failed")
    assert await store.drain_retry_publications() == []
    await store.fail_pre_dispatch(second["job_id"], JobState.FAILED, "upload_failed", "Upload failed")
    restored, = await store.drain_retry_publications()
    assert restored["revision"] > paused["revision"]
    assert restored["devices"]["failed"]["retryable"]


@pytest.mark.asyncio
async def test_retry_dependency_rollback_never_publishes(manager, tmp_path, monkeypatch):
    store = manager.store
    original = await completed(manager, tmp_path)
    snapshot = store._snapshot

    def fail_dependency_snapshot(conn, job_id, **kwargs):
        if job_id == original["job_id"]:
            raise RuntimeError("Injected dependency snapshot failure")
        return snapshot(conn, job_id, **kwargs)

    with monkeypatch.context() as patch:
        patch.setattr(store, "_snapshot", fail_dependency_snapshot)
        with pytest.raises(RuntimeError, match="Injected"):
            await newer_job(store, original)
    assert await store.get_snapshot(original["job_id"]) == original
    assert await store.drain_retry_publications() == []
    assert await store._call(lambda conn: conn.execute("SELECT COUNT(*) FROM push_jobs").fetchone()[0]) == 1


@pytest.mark.asyncio
async def test_expired_artifact_revises_retry_controls(manager, tmp_path):
    store = manager.store
    original = await completed(manager, tmp_path)
    await store._call(lambda conn: conn.execute(
        "UPDATE push_job_devices SET state='failed' WHERE device_id='active'"))
    assert manager.gc_artifacts_sync(tmp_path, retry_window_ms=0, timestamp=10) == [original["artifact"]["artifact_id"]]
    updated, = await store.drain_retry_publications()
    assert updated["revision"] > original["revision"]
    assert not any(device["retryable"] for device in updated["devices"].values())


@pytest.mark.asyncio
async def test_retry_creation_notifies_original_and_replay_is_quiet(manager, tmp_path):
    store = manager.store
    original = await completed(manager, tmp_path)
    request_id = str(uuid.uuid4())
    _, retry = await manager.retry_failed(original["job_id"], request_id,
                                         artifact_root=tmp_path, online_devices={"failed"})
    updated, = await store.drain_retry_publications()
    assert updated["revision"] > original["revision"]
    assert updated["devices"]["failed"]["retry_job_id"] == retry["job_id"]
    assert not updated["devices"]["failed"]["retryable"]
    created, _ = await manager.retry_failed(original["job_id"], request_id,
                                           artifact_root=tmp_path, online_devices={"failed"})
    assert not created
    assert await store.drain_retry_publications() == []


@pytest.mark.asyncio
async def test_cancelled_request_keeps_committed_retry_publication(manager, tmp_path, monkeypatch):
    store = manager.store
    original = await completed(manager, tmp_path)
    entered, release = threading.Event(), threading.Event()
    commit = store._commit

    def delayed_commit(conn):
        entered.set()
        assert release.wait(3)
        commit(conn)

    monkeypatch.setattr(store, "_commit", delayed_commit)
    request = asyncio.create_task(newer_job(store, original))
    try:
        assert await asyncio.to_thread(entered.wait, 2)
        request.cancel()
        with pytest.raises(asyncio.CancelledError):
            await request
    finally:
        release.set()
    changed, = await store.drain_retry_publications()
    assert changed["revision"] > original["revision"]
    assert changed["devices"]["failed"]["retryable"] is False


@pytest.mark.asyncio
async def test_retry_updates_reach_connected_admin_without_explicit_runtime_publish(tmp_path):
    app = server.create_app()
    runtime = app["push_runtime"]
    async with TestClient(TestServer(app)) as client:
        original = await completed(runtime.manager, tmp_path)
        admin = await client.ws_connect("/ws/admin")
        assert await receive_type(admin, "PUSH_JOBS_SNAPSHOT") is not None
        _, newer = await newer_job(runtime.store, original)

        async def update_after(revision, retryable):
            event = await receive_type(admin, "PUSH_JOB_UPDATED", predicate=lambda message: (
                message["job_id"] == original["job_id"] and message["revision"] > revision
                and message["job"]["devices"]["failed"]["retryable"] is retryable))
            assert event is not None
            return event["revision"]

        revision = await update_after(original["revision"], False)
        await runtime.store.fail_pre_dispatch(newer["job_id"], JobState.FAILED, "upload_failed", "Upload failed")
        await update_after(revision, True)
        await admin.close()
