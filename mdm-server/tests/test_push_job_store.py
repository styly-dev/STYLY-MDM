import time
import uuid

import pytest

from styly_mdm.push_job_store import PushJobStore, StoreConflict, now_ms
from styly_mdm.push_jobs import (
    DeviceState,
    JobState,
    ProtocolMode,
    canonicalize_create_request,
)


def canonical(client_request_id=None, targets=None):
    return canonicalize_create_request(
        {
            "client_request_id": client_request_id or str(uuid.uuid4()),
            "target_devices": targets or ["D1"],
            "mode": "push",
            "dest_path": "/sdcard/STYLY/content",
            "source": {
                "display_name": "content",
                "declared_file_count": 1,
                "declared_total_bytes": 1,
            },
        }
    )


@pytest.fixture
def store(tmp_path):
    value = PushJobStore(tmp_path / "push_jobs.sqlite3")
    yield value
    value.close()


@pytest.mark.asyncio
async def test_create_is_durable_and_idempotent(store):
    req = canonical()
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}
    created, first = await store.create_job(req, protocols, 60_000)
    replay_created, replay = await store.create_job(req, protocols, 60_000)
    assert created is True
    assert replay_created is False
    assert replay["job_id"] == first["job_id"]
    assert replay["revision"] == first["revision"] == 1


@pytest.mark.asyncio
async def test_same_request_id_with_different_fingerprint_conflicts(store):
    request_id = str(uuid.uuid4())
    first = canonical(request_id)
    other = canonicalize_create_request(
        {
            "client_request_id": request_id,
            "target_devices": ["D1"],
            "mode": "sync",
            "dest_path": "/sdcard/STYLY/content",
            "source": {"display_name": "content", "declared_file_count": 1, "declared_total_bytes": 1},
        }
    )
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}
    await store.create_job(first, protocols, 60_000)
    with pytest.raises(StoreConflict):
        await store.create_job(other, protocols, 60_000)


@pytest.mark.asyncio
async def test_same_device_jobs_are_ordered_by_enqueue_sequence(store):
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}
    _, first = await store.create_job(canonical(), protocols, 60_000)
    _, second = await store.create_job(canonical(), protocols, 60_000)
    for snapshot in (first, second):
        # Publish a synthetic immutable artifact then enable dispatch.
        await store.start_upload(snapshot["job_id"])
        await store.mark_packaging(snapshot["job_id"], 1, 1)
        await store.publish_artifact(snapshot["job_id"], {
            "artifact_id": str(uuid.uuid4()), "storage_name": str(uuid.uuid4()) + ".zip",
            "display_filename": "x.zip", "byte_size": 1, "sha256": "a" * 64, "entry_count": 1,
        })
        await store.enable_dispatch(snapshot["job_id"])
    claimed = await store.claim_next(["D1"])
    assert claimed["job"]["job_id"] == first["job_id"]
    assert await store.claim_next(["D1"]) is None


@pytest.mark.asyncio
async def test_terminal_result_wakes_canonical_aggregate(store):
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}
    _, job = await store.create_job(canonical(), protocols, 60_000)
    job_id = job["job_id"]
    await store.start_upload(job_id)
    await store.mark_packaging(job_id, 1, 1)
    await store.publish_artifact(job_id, {
        "artifact_id": str(uuid.uuid4()), "storage_name": str(uuid.uuid4()) + ".zip",
        "display_filename": "x.zip", "byte_size": 1, "sha256": "a" * 64, "entry_count": 1,
    })
    await store.enable_dispatch(job_id)
    await store.claim_next(["D1"])
    await store.mark_dispatching(job_id, "D1", {"push_job_id_v1"}, now_ms() + 1000)
    await store.transition_device(job_id, "D1", expected={DeviceState.DISPATCHING}, target=DeviceState.DOWNLOADING)
    accepted, reason, snapshot = await store.settle_result(job_id, "D1", 1, "success", added=1)
    assert accepted and reason is None
    assert snapshot["state"] == JobState.SUCCEEDED.value
    assert snapshot["aggregate"]["succeeded"] == 1


@pytest.mark.asyncio
async def test_stale_attempt_does_not_change_terminal_state(store):
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}
    _, job = await store.create_job(canonical(), protocols, 60_000)
    with pytest.raises(StoreConflict, match="stale_attempt"):
        await store.settle_result(job["job_id"], "D1", 2, "success")
    current = await store.get_snapshot(job["job_id"])
    assert current["revision"] == 1


@pytest.mark.asyncio
async def test_unconfirmed_creates_persistent_fence_and_late_result_only_clears_it(store):
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}
    _, job = await store.create_job(canonical(), protocols, 60_000)
    job_id = job["job_id"]
    await store.start_upload(job_id)
    await store.mark_packaging(job_id, 1, 1)
    await store.publish_artifact(job_id, {
        "artifact_id": str(uuid.uuid4()), "storage_name": str(uuid.uuid4()) + ".zip",
        "display_filename": "x.zip", "byte_size": 1, "sha256": "a" * 64, "entry_count": 1,
    })
    await store.enable_dispatch(job_id)
    await store.claim_next(["D1"])
    await store.mark_dispatching(job_id, "D1", {"push_job_id_v1"}, now_ms() + 1000)
    await store.mark_reconciling(job_id, "D1", expected={DeviceState.DISPATCHING}, reason="lost", deadline=now_ms())
    terminal = await store.mark_unconfirmed(job_id, "D1", "process-a", "timeout")
    assert terminal["state"] == JobState.COMPLETED_WITH_ERRORS.value
    assert terminal["devices"]["D1"]["device_fence"] is not None
    accepted, after = await store.settle_late_fenced_result(job_id, "D1", 1)
    assert accepted
    assert after["state"] == JobState.COMPLETED_WITH_ERRORS.value
    assert after["devices"]["D1"]["state"] == DeviceState.UNCONFIRMED.value
    assert after["devices"]["D1"]["device_fence"] is None


@pytest.mark.asyncio
async def test_process_replacement_clears_job_v1_fence_but_same_process_does_not(store):
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}
    _, job = await store.create_job(canonical(), protocols, 60_000)
    job_id = job["job_id"]
    await store.start_upload(job_id)
    await store.mark_packaging(job_id, 1, 1)
    await store.publish_artifact(job_id, {
        "artifact_id": str(uuid.uuid4()), "storage_name": str(uuid.uuid4()) + ".zip",
        "display_filename": "x.zip", "byte_size": 1, "sha256": "a" * 64, "entry_count": 1,
    })
    await store.enable_dispatch(job_id)
    await store.claim_next(["D1"])
    await store.mark_dispatching(job_id, "D1", {"push_job_id_v1"}, now_ms() + 1000)
    await store.mark_reconciling(job_id, "D1", expected={DeviceState.DISPATCHING}, reason="lost", deadline=now_ms())
    await store.mark_unconfirmed(job_id, "D1", "process-a", "timeout")
    assert await store.clear_fence_on_process_replacement("D1", "process-a", True) == []
    snapshots = await store.clear_fence_on_process_replacement("D1", "process-b", True)
    assert len(snapshots) == 1
    assert snapshots[0]["devices"]["D1"]["device_fence"] is None


def test_restart_recovery_does_not_redispatch_waiting_transfer(tmp_path):
    path = tmp_path / "push_jobs.sqlite3"
    first = PushJobStore(path)
    req = canonical()
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}

    async def prepare():
        _, job = await first.create_job(req, protocols, 60_000)
        job_id = job["job_id"]
        await first.start_upload(job_id)
        await first.mark_packaging(job_id, 1, 1)
        await first.publish_artifact(job_id, {
            "artifact_id": str(uuid.uuid4()), "storage_name": str(uuid.uuid4()) + ".zip",
            "display_filename": "x.zip", "byte_size": 1, "sha256": "a" * 64, "entry_count": 1,
        })
        await first.enable_dispatch(job_id)
        await first.claim_next(["D1"])
        return job_id

    import asyncio
    job_id = asyncio.run(prepare())
    first.close()
    second = PushJobStore(path)
    snapshots, _cleanup = second.recover_startup_sync(
        accept_reconciliation_timeout_ms=60_000,
        reconciliation_timeout_ms=1_800_000,
    )
    current = second._call_sync(lambda conn: second._snapshot(conn, job_id))
    second.close()
    assert current["dispatch_enabled"] is False
    assert current["dispatch_paused_reason"] == "server_restart"
    assert current["devices"]["D1"]["state"] == DeviceState.QUEUED.value

@pytest.mark.asyncio
async def test_reconcile_absent_clears_matching_fence_and_returns_exact_snapshot(store):
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}
    _, job = await store.create_job(canonical(), protocols, 60_000)
    job_id = job["job_id"]
    await store.start_upload(job_id)
    await store.mark_packaging(job_id, 1, 1)
    await store.publish_artifact(job_id, {
        "artifact_id": str(uuid.uuid4()), "storage_name": str(uuid.uuid4()) + ".zip",
        "display_filename": "x.zip", "byte_size": 1, "sha256": "a" * 64, "entry_count": 1,
    })
    await store.enable_dispatch(job_id)
    await store.claim_next(["D1"])
    await store.mark_dispatching(job_id, "D1", {"push_job_id_v1"}, now_ms() + 1000)
    await store.mark_reconciling(
        job_id, "D1", expected={DeviceState.DISPATCHING}, reason="lost", deadline=now_ms()
    )
    await store.mark_unconfirmed(job_id, "D1", "process-a", "timeout")

    # An exact explicit absence report after the assignment is terminal clears only
    # the matching fence. The canonical unconfirmed result remains terminal.
    snapshots = await store.clear_matching_fence(job_id, "D1", 1)
    current = next(snapshot for snapshot in snapshots if snapshot["job_id"] == job_id)
    assert current["devices"]["D1"]["state"] == DeviceState.UNCONFIRMED.value
    assert current["devices"]["D1"]["device_fence"] is None


@pytest.mark.asyncio
async def test_clear_matching_fence_rejects_wrong_attempt_without_mutation(store):
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}
    _, job = await store.create_job(canonical(), protocols, 60_000)
    job_id = job["job_id"]
    await store.start_upload(job_id)
    await store.mark_packaging(job_id, 1, 1)
    await store.publish_artifact(job_id, {
        "artifact_id": str(uuid.uuid4()), "storage_name": str(uuid.uuid4()) + ".zip",
        "display_filename": "x.zip", "byte_size": 1, "sha256": "a" * 64, "entry_count": 1,
    })
    await store.enable_dispatch(job_id)
    await store.claim_next(["D1"])
    await store.mark_dispatching(job_id, "D1", {"push_job_id_v1"}, now_ms() + 1000)
    await store.mark_reconciling(
        job_id, "D1", expected={DeviceState.DISPATCHING}, reason="lost", deadline=now_ms()
    )
    terminal = await store.mark_unconfirmed(job_id, "D1", "process-a", "timeout")
    revision = terminal["revision"]

    assert await store.clear_matching_fence(job_id, "D1", 2) == []
    current = await store.get_snapshot(job_id)
    assert current["revision"] == revision
    assert current["devices"]["D1"]["device_fence"] is not None
