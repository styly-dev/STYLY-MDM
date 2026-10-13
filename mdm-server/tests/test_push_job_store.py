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


async def prepare_job_v1_dispatch(manager, job_id):
    return await manager.prepare_dispatch(
        job_id,
        "D1",
        accept_deadline=now_ms() + 1000,
    )


async def settled_snapshot(manager, result, job_id):
    snapshot = next(
        (item for item in result.snapshots if item["job_id"] == job_id), None
    )
    return snapshot if snapshot is not None else await manager.get_snapshot(job_id)


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


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "field",
    ["dispatch_revision", "dispatch_capability_snapshot_json"],
)
async def test_transition_device_rejects_dispatch_identity_overwrites(store, field):
    with pytest.raises(ValueError, match="unsupported device fields"):
        await store.transition_device(
            "job",
            "D1",
            expected=None,
            target=DeviceState.QUEUED,
            fields={field: 1},
        )


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
    await prepare_job_v1_dispatch(manager, job_id)
    await store.transition_device(job_id, "D1", expected={DeviceState.DISPATCHING}, target=DeviceState.DOWNLOADING)
    await store.transition_device(job_id, "D1", expected={DeviceState.DOWNLOADING}, target=DeviceState.VALIDATING)
    await store.transition_device(job_id, "D1", expected={DeviceState.VALIDATING}, target=DeviceState.APPLYING)
    result = await manager.settle_result(job_id, "D1", 1, "success", added=1)
    snapshot = await settled_snapshot(manager, result, job_id)
    assert result.accepted and result.reason is None
    assert snapshot["state"] == JobState.SUCCEEDED.value
    assert snapshot["aggregate"]["succeeded"] == 1


@pytest.mark.asyncio
async def test_stale_attempt_does_not_change_terminal_state(store, manager):
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}
    _, job = await store.create_job(canonical(), protocols, 60_000)
    with pytest.raises(StoreConflict, match="stale_attempt"):
        await manager.settle_result(job["job_id"], "D1", 2, "success")
    current = await store.get_snapshot(job["job_id"])
    assert current["revision"] == 1


@pytest.mark.asyncio
async def test_unknown_result_is_rejected_without_revision_or_snapshot(store, manager):
    result = await manager.settle_result(str(uuid.uuid4()), "D1", 1, "success")
    assert result.accepted is False
    assert result.reason == "unknown_job"
    assert result.revision is None
    assert result.snapshots == []
    assert result.release_transfer is False


@pytest.mark.asyncio
async def test_exact_opaque_result_evidence_clears_fence_without_releasing_transfer(
    store, manager
):
    _, visible = await store.create_job(
        canonical(), {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}, 60_000
    )
    foreign_job = str(uuid.uuid4())
    await manager.add_opaque_fence(
        "D1",
        manager.opaque_identity_for_active({"job_id": foreign_job, "attempt": 1}),
        ProtocolMode.JOB_V1,
        "process-a",
        "unknown active job",
    )
    before = await store.get_snapshot(visible["job_id"])

    result = await manager.settle_result(foreign_job, "D1", 1, "success")

    assert result.accepted is True
    assert result.reason is None
    assert result.release_transfer is False
    assert result.revision is None
    assert len(result.snapshots) == 1
    assert result.snapshots[0]["job_id"] == visible["job_id"]
    assert result.snapshots[0]["revision"] == before["revision"] + 1
    current = await store.get_snapshot(visible["job_id"])
    assert current["devices"]["D1"]["device_fence"] is None


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
async def test_failed_terminal_replay_requires_all_result_fields_to_match(store, manager, changed):
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
    manager = PushJobManager(store)
    await manager.claim_next(["D1"])
    await prepare_job_v1_dispatch(manager, job_id)

    first = await manager.settle_result(
        job_id, "D1", 1, "fail", **original
    )
    assert first.accepted is True
    assert first.reason is None
    first_snapshot = await settled_snapshot(manager, first, job_id)

    replay = await manager.settle_result(
        job_id, "D1", 1, "fail", **original
    )
    assert replay.accepted is True
    assert replay.reason is None
    assert replay.revision == first_snapshot["revision"]
    assert replay.snapshots == []

    conflicting = {**original, **changed}
    replay = await manager.settle_result(
        job_id, "D1", 1, "fail", **conflicting
    )
    assert replay.accepted is False
    assert replay.reason == "conflicting_terminal_result"
    assert replay.revision == first.revision
    assert replay.snapshots == []


@pytest.mark.asyncio
async def test_success_replay_accepts_legacy_empty_failure_detail(store, manager):
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
    await prepare_job_v1_dispatch(manager, job_id)
    first = await manager.settle_result(
        job_id, "D1", 1, "success", added=1, updated=2, deleted=3,
    )
    assert first.accepted

    # Older success rows could persist an empty string instead of NULL here.
    store._call_sync(lambda conn: conn.execute(
        "UPDATE push_job_devices SET failure_detail='' WHERE job_id=? AND device_id='D1'",
        (job_id,),
    ))
    before = await store.get_snapshot(job_id)
    replay = await manager.settle_result(
        job_id, "D1", 1, "success", added=1, updated=2, deleted=3,
    )

    assert replay.accepted is True
    assert replay.reason is None
    assert replay.revision == before["revision"]
    assert replay.snapshots == []
    assert (await store.get_snapshot(job_id))["revision"] == before["revision"]


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
        (DeviceState.DISPATCHING, "success", True),
        (DeviceState.DOWNLOADING, "success", True),
        (DeviceState.VALIDATING, "success", True),
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
        await prepare_job_v1_dispatch(manager, job_id)
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
    result = await manager.settle_result(
        job_id,
        "D1",
        1,
        status,
        added=1 if status == "success" else 0,
        updated=2 if status == "success" else 0,
        deleted=3 if status == "success" else 0,
        failure_code="apply_failed" if status == "fail" else None,
    )
    assert result.accepted is accepted
    after = await settled_snapshot(manager, result, job_id)
    if accepted:
        assert result.reason is None
        assert after["revision"] == before["revision"] + 1
        terminal = "succeeded" if status == "success" else "failed"
        assert after["devices"]["D1"]["state"] == terminal
        assert after["aggregate"][terminal] == 1
        if status == "success":
            assert after["devices"]["D1"]["result"] == {
                "added": 1,
                "updated": 2,
                "deleted": 3,
            }
        else:
            assert after["devices"]["D1"]["failure"]["code"] == "apply_failed"

        duplicate = await manager.settle_result(
            job_id,
            "D1",
            1,
            status,
            added=1 if status == "success" else 0,
            updated=2 if status == "success" else 0,
            deleted=3 if status == "success" else 0,
            failure_code="apply_failed" if status == "fail" else None,
        )
        assert duplicate.accepted is True
        assert duplicate.reason is None
        assert duplicate.revision == after["revision"]
        assert duplicate.snapshots == []
    else:
        assert result.reason == "unexpected_result_state"
        assert after == before


async def cancelled_unconfirmed_job(store, manager):
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
    await prepare_job_v1_dispatch(manager, job_id)
    await manager.mark_reconciling(
        job_id, "D1", expected={DeviceState.DISPATCHING},
        reason="test", deadline=now_ms(),
    )
    await manager.cancel_interrupted(job_id, "D1")
    await manager.mark_unconfirmed(job_id, "D1", "process-a", "timeout")
    return job_id


@pytest.mark.asyncio
@pytest.mark.parametrize("status", ["success", "fail"])
@pytest.mark.parametrize("fence_state", ["matching", "mismatched", "missing"])
async def test_cancelled_late_result_requires_exact_fence(
    store, manager, status, fence_state
):
    job_id = await cancelled_unconfirmed_job(store, manager)
    before = await store.get_snapshot(job_id)
    if fence_state == "mismatched":
        store._call_sync(lambda conn: conn.execute(
            "UPDATE push_device_fences SET blocking_attempt=2 WHERE device_id='D1'"
        ))
    elif fence_state == "missing":
        store._call_sync(lambda conn: conn.execute(
            "DELETE FROM push_device_fences WHERE device_id='D1'"
        ))

    result = await manager.settle_result(
        job_id, "D1", 1, status,
        added=1, updated=2, deleted=3,
        failure_code="apply_failed" if status == "fail" else None,
    )

    if fence_state == "matching":
        assert result.accepted is True
        assert result.reason is None
        assert result.release_transfer is True
        assert result.revision == before["revision"] + 1
        assert len(result.snapshots) == 1
        after = await store.get_snapshot(job_id)
        assert after["devices"]["D1"]["state"] == (
            DeviceState.SUCCEEDED.value if status == "success" else DeviceState.FAILED.value
        )
        assert after["devices"]["D1"]["device_fence"] is None
    else:
        assert result.accepted is False
        assert result.reason == "stale_result"
        assert result.release_transfer is False
        assert result.revision is None
        assert result.snapshots == []
        after = await store.get_snapshot(job_id)
        assert after["revision"] == before["revision"]
        assert after["devices"]["D1"]["state"] == DeviceState.UNCONFIRMED.value

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
    await prepare_job_v1_dispatch(manager, job_id)
    await store.mark_reconciling(job_id, "D1", expected={DeviceState.DISPATCHING}, reason="lost", deadline=now_ms())
    terminal = next(
        snapshot
        for snapshot in await manager.mark_unconfirmed(
            job_id, "D1", "process-a", "timeout"
        )
        if snapshot["job_id"] == job_id
    )
    assert terminal["state"] == JobState.COMPLETED_WITH_ERRORS.value
    assert terminal["devices"]["D1"]["device_fence"] is not None
    result = await manager.settle_result(job_id, "D1", 1, "success")
    after = await settled_snapshot(manager, result, job_id)
    assert result.accepted
    assert result.release_transfer
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
    await prepare_job_v1_dispatch(manager, job_id)
    await store.mark_reconciling(job_id, "D1", expected={DeviceState.DISPATCHING}, reason="lost", deadline=now_ms())
    await manager.mark_unconfirmed(job_id, "D1", "process-a", "timeout")
    assert await manager.clear_fence_on_process_replacement("D1", "process-a", True) == []
    snapshots = await manager.clear_fence_on_process_replacement("D1", "process-b", True)
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
    await prepare_job_v1_dispatch(manager, job_id)
    await store.mark_reconciling(
        job_id, "D1", expected={DeviceState.DISPATCHING}, reason="lost", deadline=now_ms()
    )
    await manager.mark_unconfirmed(job_id, "D1", "process-a", "timeout")

    # An exact explicit absence report after the assignment is terminal clears only
    # the matching fence. The canonical unconfirmed result remains terminal.
    snapshots = await manager.clear_matching_fence(job_id, "D1", 1)
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
    await prepare_job_v1_dispatch(manager, job_id)
    await store.mark_reconciling(
        job_id, "D1", expected={DeviceState.DISPATCHING}, reason="lost", deadline=now_ms()
    )
    terminal = next(
        snapshot
        for snapshot in await manager.mark_unconfirmed(
            job_id, "D1", "process-a", "timeout"
        )
        if snapshot["job_id"] == job_id
    )
    revision = terminal["revision"]

    assert await manager.clear_matching_fence(job_id, "D1", 2) == []
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
        await prepare_job_v1_dispatch(manager, job_id)
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
