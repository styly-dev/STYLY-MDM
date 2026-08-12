import asyncio
import threading
import time
import uuid

import pytest

from styly_mdm.push_job_manager import PushJobManager
from styly_mdm.push_job_store import (
    PushJobStore,
    StoreConflict,
    _DbWorker,
    _DbWorkerStopped,
    now_ms,
)
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


@pytest.fixture
def manager(store):
    return PushJobManager(store)


@pytest.mark.asyncio
async def test_db_worker_survives_cancellation_during_sqlite_work(tmp_path):
    worker = _DbWorker(tmp_path / "push_jobs.sqlite3")
    started = threading.Event()
    release = threading.Event()

    def blocking_call(_conn):
        started.set()
        assert release.wait(timeout=1)
        return "finished"

    try:
        wrapped = asyncio.wrap_future(worker.submit(blocking_call))
        assert await asyncio.to_thread(started.wait, 1)
        wrapped.cancel()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await wrapped

        follow_up = asyncio.wrap_future(worker.submit(lambda _conn: "still alive"))
        assert await asyncio.wait_for(follow_up, timeout=1) == "still alive"
    finally:
        release.set()
        worker.close()


def test_db_worker_fails_pending_and_future_submissions_after_unexpected_exit(tmp_path):
    worker = _DbWorker(tmp_path / "push_jobs.sqlite3")
    started = threading.Event()
    release = threading.Event()

    def blocking_call(_conn):
        started.set()
        assert release.wait(timeout=1)
        return "worker result"

    try:
        active = worker.submit(blocking_call)
        assert started.wait(timeout=1)
        pending = worker.submit(lambda _conn: "never executed")
        callback_ran = threading.Event()
        callback_error = []

        def submit_from_callback(_future):
            callback_error.append(worker.submit(lambda _conn: "never queued").exception())
            callback_ran.set()

        pending.add_done_callback(submit_from_callback)

        # Simulate an internal completion invariant violation after execution
        # has started. The worker must fail closed instead of abandoning its queue.
        active.set_result("injected result")
        release.set()

        with pytest.raises(_DbWorkerStopped, match="stopped unexpectedly"):
            pending.result(timeout=1)
        assert callback_ran.wait(timeout=1)
        assert isinstance(callback_error[0], _DbWorkerStopped)
        with pytest.raises(_DbWorkerStopped, match="stopped unexpectedly"):
            worker.submit(lambda _conn: "never queued").result(timeout=1)
    finally:
        release.set()
        worker.close()


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
async def test_same_device_jobs_are_ordered_by_enqueue_sequence(store, manager):
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
    claimed = await manager.claim_next(["D1"])
    assert claimed["job"]["job_id"] == first["job_id"]
    assert await manager.claim_next(["D1"]) is None


@pytest.mark.asyncio
async def test_terminal_result_wakes_canonical_aggregate(store, manager):
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
    await manager.claim_next(["D1"])
    await store.mark_dispatching(job_id, "D1", {"push_job_id_v1"}, now_ms() + 1000)
    await store.transition_device(job_id, "D1", expected={DeviceState.DISPATCHING}, target=DeviceState.DOWNLOADING)
    await store.transition_device(job_id, "D1", expected={DeviceState.DOWNLOADING}, target=DeviceState.VALIDATING)
    await store.transition_device(job_id, "D1", expected={DeviceState.VALIDATING}, target=DeviceState.APPLYING)
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
@pytest.mark.parametrize(
    "changed",
    [
        {"failure_code": "other_failure"},
        {"failure_detail": "different detail"},
        {"added": 2},
        {"updated": 3},
        {"deleted": 4},
    ],
)
async def test_failed_terminal_replay_requires_all_result_fields_to_match(store, changed):
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}
    _, job = await store.create_job(canonical(), protocols, 60_000)
    job_id = job["job_id"]
    original = {
        "failure_code": "apply_failed",
        "failure_detail": "copy failed",
        "added": 1,
        "updated": 2,
        "deleted": 3,
    }
    await store.start_upload(job_id)
    await store.mark_packaging(job_id, 1, 1)
    await store.publish_artifact(job_id, {
        "artifact_id": str(uuid.uuid4()), "storage_name": str(uuid.uuid4()) + ".zip",
        "display_filename": "x.zip", "byte_size": 1, "sha256": "a" * 64, "entry_count": 1,
    })
    await store.enable_dispatch(job_id)
    await PushJobManager(store).claim_next(["D1"])
    await store.mark_dispatching(job_id, "D1", {"push_job_id_v1"}, now_ms() + 1000)

    accepted, reason, first = await store.settle_result(
        job_id, "D1", 1, "fail", **original
    )
    assert accepted is True
    assert reason is None

    accepted, reason, replay = await store.settle_result(
        job_id, "D1", 1, "fail", **original
    )
    assert accepted is True
    assert reason is None
    assert replay["revision"] == first["revision"]

    conflicting = {**original, **changed}
    accepted, reason, replay = await store.settle_result(
        job_id, "D1", 1, "fail", **conflicting
    )
    assert accepted is False
    assert reason == "conflicting_terminal_result"
    assert replay["revision"] == first["revision"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "state,status,accepted",
    [
        (DeviceState.DISPATCHING, "fail", True),
        (DeviceState.DOWNLOADING, "fail", True),
        (DeviceState.VALIDATING, "fail", True),
        (DeviceState.APPLYING, "fail", True),
        (DeviceState.RECONCILING, "fail", True),
        (DeviceState.WAITING_TRANSFER, "fail", False),
        (DeviceState.DISPATCHING, "success", False),
        (DeviceState.DOWNLOADING, "success", False),
        (DeviceState.VALIDATING, "success", False),
        (DeviceState.APPLYING, "success", True),
        (DeviceState.RECONCILING, "success", True),
    ],
)
async def test_result_settlement_state_table_preserves_rejected_revision(
    store, manager, state, status, accepted
):
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
    await manager.claim_next(["D1"])
    if state is not DeviceState.WAITING_TRANSFER:
        await store.mark_dispatching(job_id, "D1", {"push_job_id_v1"}, now_ms() + 1000)
    if state in {DeviceState.DOWNLOADING, DeviceState.VALIDATING, DeviceState.APPLYING}:
        await store.transition_device(
            job_id, "D1", expected={DeviceState.DISPATCHING}, target=DeviceState.DOWNLOADING
        )
    if state in {DeviceState.VALIDATING, DeviceState.APPLYING}:
        await store.transition_device(
            job_id, "D1", expected={DeviceState.DOWNLOADING}, target=DeviceState.VALIDATING
        )
    if state is DeviceState.APPLYING:
        await store.transition_device(
            job_id, "D1", expected={DeviceState.VALIDATING}, target=DeviceState.APPLYING
        )
    if state is DeviceState.RECONCILING:
        await store.mark_reconciling(
            job_id,
            "D1",
            expected={DeviceState.DISPATCHING},
            reason="test",
            deadline=now_ms() + 1000,
        )

    before = await store.get_snapshot(job_id)
    result_accepted, reason, after = await store.settle_result(
        job_id, "D1", 1, status, failure_code="apply_failed" if status == "fail" else None
    )
    assert result_accepted is accepted
    if accepted:
        assert reason is None
        assert after["revision"] == before["revision"] + 1
    else:
        assert reason == "unexpected_result_state"
        assert after == before

@pytest.mark.asyncio
async def test_unconfirmed_creates_persistent_fence_and_late_result_only_clears_it(
    store, manager
):
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
    await manager.claim_next(["D1"])
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
async def test_process_replacement_clears_job_v1_fence_but_same_process_does_not(
    store, manager
):
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
    await manager.claim_next(["D1"])
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
    manager = PushJobManager(first)
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
        await manager.claim_next(["D1"])
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
async def test_reconcile_absent_clears_matching_fence_and_returns_exact_snapshot(
    store, manager
):
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
    await manager.claim_next(["D1"])
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
async def test_clear_matching_fence_rejects_wrong_attempt_without_mutation(
    store, manager
):
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
    await manager.claim_next(["D1"])
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



def test_restart_recovery_uses_short_deadline_for_existing_preaccept_reconciliation(tmp_path):
    path = tmp_path / "push_jobs.sqlite3"
    first = PushJobStore(path)
    manager = PushJobManager(first)
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}

    async def prepare():
        _, job = await first.create_job(canonical(), protocols, 60_000)
        job_id = job["job_id"]
        await first.start_upload(job_id)
        await first.mark_packaging(job_id, 1, 1)
        await first.publish_artifact(job_id, {
            "artifact_id": str(uuid.uuid4()),
            "storage_name": str(uuid.uuid4()) + ".zip",
            "display_filename": "x.zip",
            "byte_size": 1,
            "sha256": "a" * 64,
            "entry_count": 1,
        })
        await first.enable_dispatch(job_id)
        await manager.claim_next(["D1"])
        await first.mark_dispatching(
            job_id, "D1", {"push_job_id_v1"}, now_ms() + 1000
        )
        await first.mark_reconciling(
            job_id,
            "D1",
            expected={DeviceState.DISPATCHING},
            reason="command_accept_timeout",
            deadline=now_ms() + 999_999,
        )
        return job_id

    job_id = asyncio.run(prepare())
    first.close()
    before = now_ms()
    second = PushJobStore(path)
    second.recover_startup_sync(
        accept_reconciliation_timeout_ms=1_234,
        reconciliation_timeout_ms=5_678,
    )
    current = second._call_sync(lambda conn: second._snapshot(conn, job_id))
    second.close()
    assignment = current["devices"]["D1"]
    assert assignment["state"] == DeviceState.RECONCILING.value
    assert assignment["reconciliation_reason"] == "server_restart_before_accept"
    assert before + 1_234 <= assignment["reconciliation_deadline"] <= now_ms() + 1_234
