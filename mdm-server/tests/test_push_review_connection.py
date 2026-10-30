import asyncio
import json
import types
import uuid

import pytest

from styly_mdm import push_runtime
from styly_mdm.push_job_manager import PushJobManager
from styly_mdm.push_job_store import PushJobStore, StoreConflict, now_ms
from styly_mdm.push_jobs import (
    DeviceState,
    ProtocolMode,
    canonicalize_create_request,
)
from styly_mdm.push_runtime import PushRuntime
from styly_mdm.push_scheduler import LiveSession, PushScheduler
from styly_mdm.push_transfer_leases import PushTransferLeases
from styly_mdm.transfer_registry import TransferKey, TransferRegistry


def request():
    return canonicalize_create_request({
        "client_request_id": str(uuid.uuid4()),
        "target_devices": ["D1"],
        "mode": "push",
        "dest_path": "/sdcard/STYLY/content",
        "source": {
            "display_name": "content",
            "declared_file_count": 1,
            "declared_total_bytes": 1,
        },
    })


def protocols():
    return {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}


async def ready_job(store, manager):
    _, created = await manager.store.create_job(request(), protocols(), 60_000)
    job_id = created["job_id"]
    await store.start_upload(job_id)
    await store.mark_packaging(job_id, 1, 1)
    ready = await store.publish_artifact(job_id, {
        "artifact_id": str(uuid.uuid4()),
        "storage_name": str(uuid.uuid4()) + ".zip",
        "display_filename": "content.zip",
        "byte_size": 1,
        "sha256": "a" * 64,
        "entry_count": 1,
    })
    return ready


async def downloading_job(store, manager):
    ready = await ready_job(store, manager)
    job_id = ready["job_id"]
    await manager.store.enable_dispatch(job_id)
    claimed = await manager.claim_next(["D1"])
    assert claimed is not None
    await manager.prepare_dispatch(
        job_id,
        "D1",
        accept_deadline=now_ms() + 60_000,
    )
    return await manager.transition_device(
        job_id,
        "D1",
        expected={DeviceState.DISPATCHING},
        target=DeviceState.DOWNLOADING,
        fields={"accepted_at": now_ms(), "accept_deadline": None},
    )


class Ws:
    def __init__(self):
        self.messages = []

    async def send_str(self, value):
        self.messages.append(json.loads(value))


class SnapshotWs(Ws):
    def __init__(self):
        super().__init__()
        self.close_calls = []

    async def close(self, **kwargs):
        self.close_calls.append(kwargs)
        return True


class Scheduler:
    def __init__(self):
        self.wake_count = 0
        self.reconcile_calls = []
        self.active_transfer_slots = []

    def wake(self):
        self.wake_count += 1

    async def send_reconcile(self, session, snapshot, device_id):
        self.reconcile_calls.append((session, snapshot, device_id))

    async def reconcile_active_transfer(self, job_id, device_id, attempt):
        self.active_transfer_slots.append((job_id, device_id, attempt))


class RegistrationManager:
    async def pending_cancellations_for_device(self, device_id):
        return []

    def __init__(self):
        self.clear_calls = 0

    async def clear_fence_on_process_replacement(self, *_args):
        self.clear_calls += 1
        return []

    async def active_assignment_for_device(self, _device_id):
        return None

    async def reconcile_missing_runtime(self, *_args, **_kwargs):
        return []


def runtime_for(manager):
    runtime = object.__new__(PushRuntime)
    runtime.pending_disconnects = set()
    runtime.disconnect_recovery_tasks = {}
    runtime.artifact_gc_task = None
    runtime.manager = manager
    runtime.transfers = TransferRegistry()
    runtime.scheduler = None
    runtime.send_timeout = 1
    runtime.publish = lambda _snapshot: asyncio.sleep(0)
    return runtime


def live_session(capabilities, *, ws=None, device_id="D1", session_id="session"):
    return LiveSession(
        device_id, session_id, ws if ws is not None else Ws(),
        frozenset(capabilities), str(uuid.uuid4()), asyncio.Lock(), "http://server",
    )


@pytest.fixture
def manager(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    try:
        yield PushJobManager(store)
    finally:
        store.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("report_path", ["registration", "reconcile"])
@pytest.mark.parametrize(
    "reason",
    [
        None,
        "download_interrupted",
    ],
)
async def test_interrupted_reports_preserve_paused_work_and_replacement_slot(
    manager, report_path, reason
):
    store = manager.store
    capabilities = frozenset({"push_job_id_v1", "push_resume_v1"})
    snapshot = await downloading_job(store, manager)
    job_id = snapshot["job_id"]
    if reason is None:
        store.recover_startup_sync(
            accept_reconciliation_timeout_ms=60_000,
            reconciliation_timeout_ms=1_800_000,
        )
    runtime = runtime_for(manager)
    runtime.scheduler = Scheduler()
    published = []

    async def publish(value):
        published.append(value)

    runtime.publish = publish
    ws = Ws()
    session = live_session(capabilities, ws=ws)
    report = {
        "job_id": job_id, "attempt": 1,
        "artifact_id": snapshot["artifact"]["artifact_id"],
        "revision": snapshot["devices"]["D1"]["dispatch_revision"],
        "validated_offset": 1, "phase": "downloading", "status": "interrupted",
    }
    if reason is not None:
        report["reason"] = reason

    async def send_report():
        if report_path == "registration":
            published.extend(await runtime._registration_active_snapshots("D1", session, report))
        else:
            await runtime._handle_reconcile_report(session, "D1", report)

    key = TransferKey("push", "D1", job_id, 1)
    old_transfer = asyncio.get_running_loop().create_future()
    runtime.transfers.register(key, old_transfer)
    await send_report()
    assert old_transfer.result() == "resumable_replay"
    paused = await manager.get_snapshot(job_id)
    assert paused["devices"]["D1"]["state"] == "queued"
    assert paused["devices"]["D1"]["validated_offset"] == 1
    assert paused["dispatch_enabled"] is (reason is not None)
    assert paused["dispatch_paused_reason"] == (None if reason else "server_restart")
    # Without a live operator Resume, evidence never authorizes dispatch.
    expected_queue_reason = (
        "download_interrupted"
        if reason == "download_interrupted"
        else reason or "dispatch_paused"
    )
    assert paused["devices"]["D1"]["queue_reason"] == expected_queue_reason
    assert await manager.claim_next(["D1"]) is None
    # Disconnect does not rewrite queued state: repeated REGISTER/reconcile
    # must retain it without a rejection or a revision change.
    assert await manager.active_assignment_for_device("D1") is None
    await send_report()
    await send_report()
    assert await manager.get_snapshot(job_id) == paused
    assert runtime.scheduler.wake_count == int(reason is not None and report_path == "reconcile")

    await manager.store.enable_dispatch(job_id, ["D1"])
    await send_report()  # Do not re-pause after the operator authorizes Resume.
    assert (await manager.get_snapshot(job_id))["dispatch_enabled"] is True
    assert await manager.claim_next(["D1"]) is not None
    replacement = asyncio.get_running_loop().create_future()
    runtime.transfers.register(key, replacement)
    for state in ("waiting_transfer", "dispatching"):
        if state == "dispatching":
            await manager.prepare_dispatch(
                job_id, "D1",
                accept_deadline=now_ms() + 60_000,
            )
        before = await manager.get_snapshot(job_id)
        await send_report()
        assert await manager.get_snapshot(job_id) == before
        assert not replacement.done()
        assert runtime.transfers.get(key) is replacement
    command = PushScheduler._command(
        before, "D1", "http://server", lease_token="test-lease"
    )
    assert command["revision"] == report["revision"]
    assert command["artifact_id"] == report["artifact_id"]
    assert ws.messages == []
    assert len(published) == 1


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "mismatch",
    [
        "revision",
        "missing_revision",
        "artifact_id",
        "validated_offset",
        "boolean_offset",
    ],
)
async def test_interrupted_transfer_requires_exact_evidence_before_pausing(manager, mismatch):
    store = manager.store
    snapshot = await downloading_job(store, manager)
    evidence = {
        "dispatch_revision": snapshot["devices"]["D1"]["dispatch_revision"],
        "artifact_id": snapshot["artifact"]["artifact_id"],
        "validated_offset": 1,
    }
    if mismatch == "revision":
        evidence["dispatch_revision"] += 1
    elif mismatch == "missing_revision":
        evidence["dispatch_revision"] = None
    elif mismatch == "artifact_id":
        evidence["artifact_id"] = str(uuid.uuid4())
    elif mismatch == "boolean_offset":
        evidence["validated_offset"] = True
    else:
        evidence["validated_offset"] = 2
    outcome, (unchanged,) = await manager.resume_interrupted(
        snapshot["job_id"], "D1", attempt=1,
        reason="download_interrupted", **evidence,
    )
    assert outcome == "rejected"
    assert unchanged == snapshot


@pytest.mark.asyncio
async def test_paused_resume_can_expire_without_another_dispatch(manager):
    store = manager.store
    snapshot = await downloading_job(store, manager)
    job_id = snapshot["job_id"]
    await manager.resume_interrupted(
        job_id, "D1", attempt=1, reason="download_interrupted",
        artifact_id=snapshot["artifact"]["artifact_id"],
        dispatch_revision=snapshot["devices"]["D1"]["dispatch_revision"],
        validated_offset=1,
    )
    rejected = await manager.settle_result(job_id, "D1", 1, "success")
    assert not rejected.accepted  # A queued row still cannot report arbitrary success.
    settled = await manager.settle_result(
        job_id, "D1", 1, "fail", failure_code="resume_expired",
    )
    assert settled.accepted
    expired = await store.get_snapshot(job_id)
    assert expired["devices"]["D1"]["state"] == "failed"
    assert (await manager.assignment(job_id, "D1"))["failure_code"] == "resume_expired"
    assert await manager.active_assignment_for_device("D1") is None


@pytest.mark.asyncio
async def test_unversioned_interrupted_report_waits_for_absent_reconciliation(manager):
    store = manager.store
    snapshot = await downloading_job(store, manager)
    job_id = snapshot["job_id"]
    store._call_sync(lambda conn: conn.execute(
        "UPDATE push_job_devices SET dispatch_revision=NULL "
        "WHERE job_id=? AND device_id='D1'",
        (job_id,),
    ))
    await manager.mark_reconciling(
        job_id, "D1", expected={DeviceState.DOWNLOADING},
        reason="device_disconnect", deadline=now_ms() + 60_000,
    )
    runtime = runtime_for(manager)
    session = live_session({"push_job_id_v1"})
    await runtime._handle_reconcile_report(session, "D1", {
        "job_id": job_id, "attempt": 1, "status": "interrupted",
        "artifact_id": snapshot["artifact"]["artifact_id"], "phase": "downloading",
    })
    unversioned = await manager.assignment(job_id, "D1")
    assert unversioned["state"] == DeviceState.RECONCILING.value
    assert unversioned["dispatch_revision"] is None
    assert unversioned["reconciliation_deadline"] is not None
    assert session.ws.messages == []

    await runtime._handle_reconcile_report(session, "D1", {
        "job_id": job_id, "attempt": 1, "status": "absent",
    })
    reconciled = await manager.assignment(job_id, "D1")
    assert reconciled["state"] == DeviceState.INTERRUPTED.value
    assert reconciled["failure_code"] == "client_state_absent"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("field", "value"),
    [("attempt", True), ("attempt", 1.0), ("revision", True), ("revision", 1.0)],
)
async def test_interrupted_report_requires_integer_identity_fields(manager, field, value):
    store = manager.store
    snapshot = await downloading_job(store, manager)
    job_id = snapshot["job_id"]
    # Make bool/float values compare equal to the authorized integer revision.
    store._call_sync(lambda conn: conn.execute(
        "UPDATE push_job_devices SET dispatch_revision=1 "
        "WHERE job_id=? AND device_id='D1'",
        (job_id,),
    ))
    await manager.mark_reconciling(
        job_id, "D1", expected={DeviceState.DOWNLOADING},
        reason="device_disconnect", deadline=now_ms() + 60_000,
    )
    runtime = runtime_for(manager)
    session = live_session({"push_job_id_v1"})

    report = {
        "job_id": job_id,
        "attempt": 1,
        "status": "interrupted",
        "artifact_id": snapshot["artifact"]["artifact_id"],
        "revision": 1,
        "validated_offset": 0,
        "phase": "downloading",
    }
    report[field] = value
    await runtime._handle_reconcile_report(session, "D1", report)

    current = await manager.assignment(job_id, "D1")
    assert current["state"] == DeviceState.RECONCILING.value
    assert current["reconciliation_deadline"] is not None
    assert session.ws.messages == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "dispatch_capabilities_json", [None, "{", '["push_job_id_v1"]']
)
async def test_interrupted_reconcile_uses_job_v1_revision_not_capability_snapshot(
    manager, dispatch_capabilities_json,
):
    store = manager.store
    dispatched = await downloading_job(
        store, manager
    )
    job_id = dispatched["job_id"]
    store._call_sync(lambda conn: conn.execute(
        "UPDATE push_job_devices SET dispatch_capability_snapshot_json=? "
        "WHERE job_id=? AND device_id='D1'",
        (dispatch_capabilities_json, job_id),
    ))
    await manager.mark_reconciling(
        job_id,
        "D1",
        expected={DeviceState.DOWNLOADING},
        reason="device_disconnect",
        deadline=now_ms() + 60_000,
    )
    runtime = runtime_for(manager)
    session = live_session({"push_job_id_v1"})

    await runtime._handle_reconcile_report(session, "D1", {
        "job_id": job_id,
        "attempt": 1,
        "status": "interrupted",
        "artifact_id": dispatched["artifact"]["artifact_id"],
        "revision": dispatched["devices"]["D1"]["dispatch_revision"],
        "validated_offset": 0,
        "phase": "downloading",
    })

    assignment = await manager.assignment(job_id, "D1")
    assert assignment["state"] == DeviceState.QUEUED.value
    assert assignment["dispatch_revision"] == dispatched["devices"]["D1"]["dispatch_revision"]
    assert assignment["validated_offset"] == 0
    assert session.ws.messages == []


@pytest.mark.asyncio
async def test_retry_exhaustion_allows_another_device_waiting_for_a_slot(manager):
    store = manager.store
    capabilities = frozenset({"push_job_id_v1", "push_resume_v1"})
    snapshot = await downloading_job(store, manager)
    job_id = snapshot["job_id"]
    # Add a second target to the fixture before it is claimed, as a multi-target
    # job does at creation. D1 owns the only network slot.
    store._call_sync(lambda conn: conn.execute(
        "INSERT INTO push_job_devices(job_id, device_id, enqueue_seq, target_ordinal, "
        "protocol_mode, create_capability_snapshot_json, state, updated_at) "
        "VALUES (?, 'D2', 2, 1, 'job_v1', ?, 'queued', ?)",
        (job_id, json.dumps(sorted(capabilities)), now_ms()),
    ))
    waiting = await manager.claim_next(["D2"])
    assert waiting is not None
    session = live_session(capabilities, device_id="D2")
    slots = asyncio.Semaphore(0)
    registry = TransferRegistry()
    scheduler = PushScheduler(
        manager=manager, transfer_registry=registry,
        transfer_slots=lambda: slots, sessions=lambda: {"D2": session},
        publish=lambda _snapshot: asyncio.sleep(0), send_timeout=1,
        accept_timeout=1, accept_reconciliation_timeout=1,
        reconciliation_timeout=1, leases=PushTransferLeases(),
    )
    task = asyncio.create_task(scheduler._dispatch_assignment(waiting))
    try:
        await asyncio.sleep(0)
        assert not task.done()
        await manager.resume_interrupted(
            job_id, "D1", attempt=1, reason="download_interrupted",
            artifact_id=snapshot["artifact"]["artifact_id"],
            dispatch_revision=snapshot["devices"]["D1"]["dispatch_revision"],
            validated_offset=1,
        )
        slots.release()
        for _ in range(100):
            if session.ws.messages:
                break
            await asyncio.sleep(0.01)
        assert session.ws.messages[0]["type"] == "EXECUTE_PUSH_FILES"
        assert (await manager.assignment(job_id, "D2"))["state"] == "dispatching"
        assert await manager.claim_next(["D1"]) is None
        await manager.store.enable_dispatch(job_id, ["D1"])
        assert await manager.claim_next(["D1"]) is not None
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


@pytest.mark.asyncio
async def test_resume_rejection_carries_exact_cleanup_authority():
    runtime = object.__new__(PushRuntime)
    runtime.pending_disconnects = set()
    runtime.disconnect_recovery_tasks = {}
    runtime.artifact_gc_task = None
    runtime.send_timeout = 1
    ws = Ws()
    session = live_session({"push_job_id_v1", "push_resume_v1"}, ws=ws)
    artifact_id = str(uuid.uuid4())

    await runtime._send_resume_rejected(
        session,
        job_id="job-1",
        artifact_id=artifact_id,
        revision=7,
    )

    assert ws.messages == [{
        "type": "PUSH_RESUME_REJECTED",
        "job_id": "job-1",
        "attempt": 1,
        "artifact_id": artifact_id,
        "revision": 7,
        "reason": "resume_not_authorized",
        "retryable": False,
        "detail": "Server could not authorize the interrupted assignment",
    }]


@pytest.mark.asyncio
async def test_malformed_resume_identity_cannot_authorize_client_cleanup():
    runtime = object.__new__(PushRuntime)
    runtime.pending_disconnects = set()
    runtime.disconnect_recovery_tasks = {}
    runtime.artifact_gc_task = None
    runtime.send_timeout = 1
    ws = Ws()
    session = live_session({"push_job_id_v1", "push_resume_v1"}, ws=ws)

    await runtime._send_resume_rejected(
        session,
        job_id="job-1",
        artifact_id=str(uuid.uuid4()),
        revision=True,
    )

    assert ws.messages == []


@pytest.mark.asyncio
async def test_failed_interrupted_reconciliation_returns_permanent_disposition():
    artifact_id = str(uuid.uuid4())

    class Manager:
        async def clear_matching_opaque_fence(self, *_args):
            return False, []

        async def assignment(self, *_args):
            return {
                "state": DeviceState.RECONCILING.value,
                "artifact_id": artifact_id,
                "dispatch_capability_snapshot_json": json.dumps(
                    ["push_job_id_v1", "push_resume_v1"]
                ),
            }

        async def resume_interrupted(self, *_args, **_kwargs):
            return "rejected", [{"job_id": "job-1"}]

    runtime = runtime_for(Manager())
    ws = Ws()
    session = live_session({"push_job_id_v1", "push_resume_v1"}, ws=ws)

    await runtime._handle_reconcile_report(
        session,
        "D1",
        {
            "type": "PUSH_RECONCILE_REPORT",
            "job_id": "job-1",
            "attempt": 1,
            "artifact_id": artifact_id,
            "revision": 7,
            "validated_offset": 3,
            "status": "interrupted",
            "phase": "downloading",
        },
    )

    assert ws.messages[0]["type"] == "PUSH_RESUME_REJECTED"
    assert ws.messages[0]["artifact_id"] == artifact_id
    assert ws.messages[0]["revision"] == 7


@pytest.mark.asyncio
async def test_interrupted_reconciliation_without_artifact_never_resumes(manager):
    job = await downloading_job(manager.store, manager)
    runtime = runtime_for(manager)
    ws = Ws()
    session = live_session({"push_job_id_v1", "push_resume_v1"}, ws=ws)
    await runtime._handle_reconcile_report(session, "D1", {
        "job_id": job["job_id"], "attempt": 1,
        "revision": job["devices"]["D1"]["dispatch_revision"],
        "validated_offset": 1, "status": "interrupted", "phase": "downloading",
    })
    assert (await manager.assignment(job["job_id"], "D1"))["state"] == "failed"
    assert await manager.fenced_assignment_for_device("D1") is not None
    # Do not manufacture a discard identity from the canonical assignment.
    assert ws.messages == []


@pytest.mark.asyncio
async def test_operator_resume_reconciles_recovered_state_before_dispatch(manager):
    store = manager.store
    active = await downloading_job(store, manager)
    job_id = active["job_id"]
    await manager.mark_reconciling(
        job_id,
        "D1",
        expected={DeviceState.DOWNLOADING},
        reason="server_restart",
        deadline=now_ms() + 60_000,
    )
    await store._call(
        lambda conn: conn.execute(
            "UPDATE push_jobs SET dispatch_enabled=0, "
            "dispatch_paused_reason='server_restart' WHERE job_id=?",
            (job_id,),
        )
    )

    runtime = object.__new__(PushRuntime)
    runtime.pending_disconnects = set()
    runtime.disconnect_recovery_tasks = {}
    runtime.artifact_gc_task = None
    runtime.store = store
    runtime.manager = manager
    runtime.scheduler = Scheduler()
    runtime.admin_send_timeout = 1
    runtime.legacy = types.SimpleNamespace(
        MAX_CONCURRENT_TRANSFERS=5,
        devices={"D1": {"push_state_status": "available"}},
    )
    runtime.publish = lambda _snapshot: asyncio.sleep(0)
    runtime.sessions = {
        "D1": live_session(
            {"push_state_retry_v1", "push_job_id_v1", "push_resume_v1"}, session_id="one",
        )
    }
    admin = Ws()

    consumed = await runtime.handle_admin_message(
        admin, {"type": "PUSH_FILES", "job_id": job_id, "target_devices": ["D1"]}
    )

    assert consumed is True
    assert runtime.scheduler.wake_count == 1
    assert len(runtime.scheduler.reconcile_calls) == 1
    _, snapshot, device_id = runtime.scheduler.reconcile_calls[0]
    assert device_id == "D1"
    assert snapshot["devices"]["D1"]["state"] == DeviceState.RECONCILING.value

    runtime.scheduler.reconcile_calls.clear()
    runtime.legacy.devices["D1"]["push_state_status"] = "unavailable"
    await runtime.handle_admin_message(
        admin, {"type": "PUSH_FILES", "job_id": job_id, "target_devices": ["D1"]}
    )
    assert runtime.scheduler.reconcile_calls == []


async def _record_async(target, value):
    target.append(value)


@pytest.mark.asyncio
async def test_initial_snapshot_failure_closes_admin_for_reconnect():
    class Manager:
        async def list_snapshots(self, *_args):
            raise RuntimeError("snapshot unavailable")

    runtime = object.__new__(PushRuntime)
    runtime.pending_disconnects = set()
    runtime.disconnect_recovery_tasks = {}
    runtime.artifact_gc_task = None
    runtime.manager = Manager()
    runtime.recent_days = 30
    runtime.recent_limit = 100
    runtime.send_timeout = 1
    runtime.admin_send_timeout = 1
    ws = SnapshotWs()

    await runtime.send_initial_snapshot(ws)

    assert len(ws.close_calls) == 1
    assert ws.close_calls[0]["code"] == push_runtime.WSCloseCode.GOING_AWAY
    assert ws.close_calls[0]["message"] == b"initial Push snapshot failed"
    assert ws.close_calls[0]["drain"] is False


@pytest.mark.asyncio
async def test_initial_snapshot_send_timeout_closes_admin_for_reconnect():
    class Manager:
        async def list_snapshots(self, *_args):
            return []

    class BlockingSnapshotWs(SnapshotWs):
        async def send_str(self, _value):
            await asyncio.Future()

    runtime = object.__new__(PushRuntime)
    runtime.pending_disconnects = set()
    runtime.disconnect_recovery_tasks = {}
    runtime.artifact_gc_task = None
    runtime.manager = Manager()
    runtime.recent_days = 30
    runtime.recent_limit = 100
    runtime.admin_send_timeout = 0.01
    ws = BlockingSnapshotWs()

    await asyncio.wait_for(runtime.send_initial_snapshot(ws), timeout=1)

    assert len(ws.close_calls) == 1
    assert ws.close_calls[0]["code"] == push_runtime.WSCloseCode.GOING_AWAY


@pytest.mark.asyncio
async def test_initial_snapshot_cancellation_is_not_swallowed():
    class Manager:
        async def list_snapshots(self, *_args):
            raise asyncio.CancelledError()

    runtime = object.__new__(PushRuntime)
    runtime.pending_disconnects = set()
    runtime.disconnect_recovery_tasks = {}
    runtime.artifact_gc_task = None
    runtime.manager = Manager()
    runtime.recent_days = 30
    runtime.recent_limit = 100
    runtime.send_timeout = 1
    runtime.admin_send_timeout = 1
    ws = SnapshotWs()

    with pytest.raises(asyncio.CancelledError):
        await runtime.send_initial_snapshot(ws)

    assert ws.close_calls == []


@pytest.mark.asyncio
async def test_ready_job_can_be_dispatched_with_existing_job_id(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        ready = await ready_job(store, manager)
        runtime = object.__new__(PushRuntime)
        runtime.pending_disconnects = set()
        runtime.disconnect_recovery_tasks = {}
        runtime.artifact_gc_task = None
        runtime.store = store
        runtime.scheduler = Scheduler()
        runtime.admin_send_timeout = 1
        runtime.legacy = types.SimpleNamespace(MAX_CONCURRENT_TRANSFERS=5, devices={})
        runtime.manager = manager
        runtime.sessions = {device_id: object() for device_id in ready["devices"]}
        published = []

        async def publish(snapshot):
            published.append(snapshot)

        runtime.publish = publish
        ws = Ws()

        consumed = await runtime.handle_admin_message(ws, {
            "type": "PUSH_FILES",
            "job_id": ready["job_id"],
        })

        current = await store.get_snapshot(ready["job_id"])
        assert consumed is True
        assert current["dispatch_enabled"] is True
        assert current["dispatch_paused_reason"] is None
        assert published[-1]["revision"] == current["revision"]
        assert runtime.scheduler.wake_count == 1
        assert ws.messages[-1]["type"] == "PUSH_FILES_SENT"
        assert ws.messages[-1]["job_id"] == ready["job_id"]
    finally:
        store.close()


@pytest.mark.asyncio
async def test_dispatch_ack_timeout_still_wakes_scheduler_and_closes_admin(tmp_path):
    class BlockingWs(SnapshotWs):
        def __init__(self):
            super().__init__()
            self.send_started = asyncio.Event()

        async def send_str(self, _value):
            self.send_started.set()
            await asyncio.Future()

    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        ready = await ready_job(store, manager)
        runtime = object.__new__(PushRuntime)
        runtime.pending_disconnects = set()
        runtime.disconnect_recovery_tasks = {}
        runtime.artifact_gc_task = None
        runtime.store = store
        runtime.scheduler = Scheduler()
        runtime.admin_send_timeout = 0.01
        ws = BlockingWs()
        runtime.manager = manager
        runtime.sessions = {device_id: object() for device_id in ready["devices"]}
        runtime.legacy = types.SimpleNamespace(
            MAX_CONCURRENT_TRANSFERS=5,
            devices={},
            admin_connections={ws},
            _admin_send_locks={ws: asyncio.Lock()},
        )
        runtime.publish = lambda _snapshot: asyncio.sleep(0)

        handling = asyncio.create_task(runtime.handle_admin_message(ws, {
            "type": "PUSH_FILES",
            "job_id": ready["job_id"],
        }))
        await asyncio.wait_for(ws.send_started.wait(), timeout=1)
        assert runtime.scheduler.wake_count == 1
        consumed = await asyncio.wait_for(handling, timeout=1)

        current = await store.get_snapshot(ready["job_id"])
        assert consumed is True
        assert current["dispatch_enabled"] is True
        assert runtime.scheduler.wake_count == 1
        assert ws.close_calls
        assert ws not in runtime.legacy.admin_connections
        assert ws not in runtime.legacy._admin_send_locks
    finally:
        store.close()


@pytest.mark.asyncio
async def test_dispatch_error_timeout_closes_admin_without_waking_scheduler(tmp_path):
    class BlockingWs(SnapshotWs):
        async def send_str(self, _value):
            await asyncio.Future()

    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    try:
        runtime = object.__new__(PushRuntime)
        runtime.pending_disconnects = set()
        runtime.disconnect_recovery_tasks = {}
        runtime.artifact_gc_task = None
        runtime.store = store
        runtime.scheduler = Scheduler()
        runtime.admin_send_timeout = 0.01
        ws = BlockingWs()
        runtime.legacy = types.SimpleNamespace(
            MAX_CONCURRENT_TRANSFERS=5,
            devices={},
            admin_connections={ws},
            _admin_send_locks={ws: asyncio.Lock()},
        )

        consumed = await asyncio.wait_for(runtime.handle_admin_message(ws, {
            "type": "PUSH_FILES",
            "job_id": str(uuid.uuid4()),
        }), timeout=1)

        assert consumed is True
        assert runtime.scheduler.wake_count == 0
        assert ws.close_calls
        assert ws not in runtime.legacy.admin_connections
        assert ws not in runtime.legacy._admin_send_locks
    finally:
        store.close()


@pytest.mark.asyncio
async def test_superseded_register_cannot_become_runtime_owner():
    stale = Ws()
    current = Ws()
    manager = RegistrationManager()
    runtime = object.__new__(PushRuntime)
    runtime.pending_disconnects = set()
    runtime.disconnect_recovery_tasks = {}
    runtime.artifact_gc_task = None
    runtime.legacy = types.SimpleNamespace(devices={"D1": {"ws": current}})
    runtime.manager = manager
    runtime.sessions = {}
    runtime.device_locks = {}
    runtime.registration_candidates = {"D1": stale}
    runtime.send_timeout = 1
    runtime.scheduler = Scheduler()

    await runtime.register_device(
        stale,
        {
            "device_id": "D1",
            "process_instance_id": str(uuid.uuid4()),
            "capabilities": ["push_job_id_v1"],
            "push_runtime": {"active": None},
        },
        "http://server",
    )

    assert runtime.sessions == {}
    assert stale.messages == []
    assert manager.clear_calls == 0


@pytest.mark.asyncio
async def test_current_register_is_bound_to_legacy_owner():
    ws = Ws()
    manager = RegistrationManager()
    runtime = object.__new__(PushRuntime)
    runtime.pending_disconnects = set()
    runtime.disconnect_recovery_tasks = {}
    runtime.artifact_gc_task = None
    runtime.legacy = types.SimpleNamespace(devices={"D1": {"ws": ws}})
    runtime.manager = manager
    runtime.sessions = {}
    runtime.device_locks = {}
    runtime.registration_candidates = {"D1": ws}
    runtime.send_timeout = 1
    runtime.accept_reconciliation_timeout = 60
    runtime.reconciliation_timeout = 1800
    runtime.scheduler = Scheduler()
    published = []

    async def publish(snapshot):
        published.append(snapshot)

    runtime.publish = publish
    await runtime.register_device(
        ws,
        {
            "device_id": "D1",
            "process_instance_id": str(uuid.uuid4()),
            "capabilities": ["push_job_id_v1"],
            "push_runtime": {"active": None},
        },
        "http://server",
    )

    assert runtime.sessions["D1"].ws is ws
    assert ws.messages[0]["type"] == "REGISTERED"
    assert "D1" not in runtime.registration_candidates
    assert manager.clear_calls == 1


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("entry_is_current", "registration_ready"),
    [(True, True), (False, True), (True, False)],
)
async def test_refresh_registration_ack_requires_current_ready_owner(
    entry_is_current, registration_ready
):
    ws = Ws()
    entry_owner = ws if entry_is_current else Ws()
    process_instance_id = str(uuid.uuid4())
    runtime = object.__new__(PushRuntime)
    runtime.pending_disconnects = set()
    runtime.disconnect_recovery_tasks = {}
    runtime.artifact_gc_task = None
    runtime.sessions = {
        "D1": LiveSession(
            "D1", "session", ws, frozenset(), process_instance_id,
            asyncio.Lock(), "http://server", push_state_available=False,
        )
    }
    runtime.device_locks = {}
    runtime.legacy = types.SimpleNamespace(
        devices={"D1": {
            "ws": entry_owner,
            "registration_ready": registration_ready,
            "push_state_status": "unavailable",
        }},
        broadcast_device_list=lambda: asyncio.sleep(0),
    )
    runtime.send_timeout = 1
    runtime.scheduler = Scheduler()
    runtime._spawn_background = lambda coro, **_kwargs: coro.close()

    async def after_registration(*_args):
        return None

    runtime._after_registration = after_registration
    await runtime.refresh_registration(ws, "D1", {
        "process_instance_id": process_instance_id,
        "capabilities": [],
        "push_state": {"status": "unavailable"},
    })

    expected = ([{"type": "REGISTERED", "session_id": "session"}]
                if entry_is_current and registration_ready else [])
    assert ws.messages == expected


@pytest.mark.asyncio
async def test_unavailable_push_state_does_not_report_absence_or_request_reconcile():
    class ReconcilingManager(RegistrationManager):
        async def active_assignment_for_device(self, _device_id):
            return {"state": DeviceState.RECONCILING.value}

    ws = Ws()
    manager = ReconcilingManager()
    runtime = object.__new__(PushRuntime)
    runtime.pending_disconnects = set()
    runtime.disconnect_recovery_tasks = {}
    runtime.artifact_gc_task = None
    runtime.legacy = types.SimpleNamespace(devices={"D1": {"ws": ws}})
    runtime.manager = manager
    runtime.sessions = {}
    runtime.device_locks = {}
    runtime.registration_candidates = {"D1": ws}
    runtime.send_timeout = 1
    runtime.scheduler = Scheduler()
    runtime.publish = lambda _snapshot: asyncio.sleep(0)

    await runtime.register_device(
        ws,
        {
            "device_id": "D1",
            "process_instance_id": str(uuid.uuid4()),
            "capabilities": ["push_state_retry_v1"],
            "push_state": {"status": "unavailable"},
            "push_runtime": {"active": None},
        },
        "http://server",
    )

    assert manager.clear_calls == 0
    assert runtime.scheduler.reconcile_calls == []


@pytest.mark.asyncio
async def test_registration_active_report_preserves_offline_timeout_fence(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        active = await downloading_job(store, manager)
        job_id = active["job_id"]
        artifact_id = active["artifact"]["artifact_id"]
        await manager.mark_reconciling(
            job_id,
            "D1",
            expected={DeviceState.DOWNLOADING},
            reason="device_disconnect",
            deadline=now_ms(),
        )
        await manager.mark_unconfirmed(
            job_id,
            "D1",
            None,
            "device stayed offline past deadline",
        )
        next_job = await ready_job(store, manager)
        await manager.store.enable_dispatch(next_job["job_id"])

        ws = Ws()
        runtime = object.__new__(PushRuntime)
        runtime.pending_disconnects = set()
        runtime.disconnect_recovery_tasks = {}
        runtime.artifact_gc_task = None
        runtime.legacy = types.SimpleNamespace(devices={"D1": {"ws": ws}})
        runtime.manager = manager
        runtime.sessions = {}
        runtime.device_locks = {}
        runtime.registration_candidates = {"D1": ws}
        runtime.send_timeout = 1
        runtime.scheduler = Scheduler()
        runtime.publish = lambda _snapshot: asyncio.sleep(0)

        await runtime.register_device(
            ws,
            {
                "device_id": "D1",
                "process_instance_id": str(uuid.uuid4()),
                "capabilities": ["push_job_id_v1"],
                "push_runtime": {
                    "active": {
                        "job_id": job_id,
                        "attempt": 1,
                        "artifact_id": artifact_id,
                        "phase": "downloading",
                    }
                },
            },
            "http://server",
        )

        fenced = await manager.get_snapshot(job_id)
        assert fenced["devices"]["D1"]["device_fence"] is not None
        assert await manager.claim_next(["D1"]) is None
    finally:
        store.close()


@pytest.mark.asyncio
async def test_registration_candidate_reconciles_replaced_active_session(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        active = await downloading_job(store, manager)
        job_id = active["job_id"]
        artifact_id = active["artifact"]["artifact_id"]
        runtime = object.__new__(PushRuntime)
        runtime.pending_disconnects = set()
        runtime.disconnect_recovery_tasks = {}
        runtime.artifact_gc_task = None
        runtime.manager = manager
        runtime.sessions = {}
        runtime.device_locks = {}
        runtime.registration_candidates = {}
        runtime.transfers = TransferRegistry()
        runtime.leases = PushTransferLeases()
        runtime.leases.issue(TransferKey("push", "D1", job_id, 1), artifact_id)
        runtime.accept_reconciliation_timeout = 0.1
        runtime.reconciliation_timeout = 1
        runtime.scheduler = Scheduler()
        runtime.send_timeout = 1
        published = []

        async def publish(snapshot):
            published.append(snapshot)

        runtime.publish = publish
        lock = runtime._device_lock("D1")
        old = Ws()
        new = Ws()
        runtime.sessions["D1"] = LiveSession(
            device_id="D1",
            session_id="old",
            ws=old,
            capabilities=frozenset({"push_job_id_v1"}),
            process_instance_id="old-process",
            owner_lock=lock,
            http_base="http://server",
        )
        future = asyncio.get_running_loop().create_future()
        runtime.transfers.register(TransferKey("push", "D1", job_id, 1), future)

        runtime.note_registration_candidate(new, "D1")
        assert runtime.sessions["D1"].ws is old
        assert runtime.registration_candidates["D1"] is new
        assert not future.done()

        runtime.legacy = types.SimpleNamespace(devices={"D1": {"ws": new}})
        await runtime.register_device(
            new,
            {
                "device_id": "D1",
                "process_instance_id": str(uuid.uuid4()),
                "capabilities": ["push_job_id_v1"],
                "push_runtime": {
                    "active": {
                        "job_id": job_id,
                        "attempt": 1,
                        "artifact_id": artifact_id,
                        "phase": "downloading",
                    }
                },
            },
            "http://server",
        )

        assert runtime.sessions["D1"].ws is new
        assert not future.done()
        assert runtime.scheduler.active_transfer_slots == [(job_id, "D1", 1)]
        current = await manager.assignment(job_id, "D1")
        assert current["state"] == DeviceState.DOWNLOADING.value
        assert any(
            snapshot["devices"]["D1"]["state"] == DeviceState.RECONCILING.value
            for snapshot in published
        )
        assert published[-1]["devices"]["D1"]["state"] == "downloading"
    finally:
        store.close()


@pytest.mark.asyncio
async def test_unknown_exact_phase_messages_are_noops(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        runtime = object.__new__(PushRuntime)
        runtime.pending_disconnects = set()
        runtime.disconnect_recovery_tasks = {}
        runtime.artifact_gc_task = None
        runtime.store = store
        runtime.manager = manager
        runtime.transfers = TransferRegistry()
        published = []

        async def publish(snapshot):
            published.append(snapshot)

        runtime.publish = publish
        unknown = str(uuid.uuid4())
        await runtime._handle_transfer_complete("D1", {
            "job_id": unknown,
            "attempt": 1,
            "artifact_id": str(uuid.uuid4()),
            "received_size": 1,
        })
        await runtime._handle_validation_complete("D1", {
            "job_id": unknown,
            "attempt": 1,
            "artifact_id": str(uuid.uuid4()),
        })
        await runtime._handle_phase("D1", {
            "job_id": unknown,
            "attempt": 1,
            "phase": "applying",
        })
        assert published == []
        assert len(runtime.transfers) == 0
    finally:
        store.close()


@pytest.mark.asyncio
async def test_registration_active_artifact_conflict_fails_and_fences(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        active = await downloading_job(store, manager)
        job_id = active["job_id"]
        runtime = object.__new__(PushRuntime)
        runtime.pending_disconnects = set()
        runtime.disconnect_recovery_tasks = {}
        runtime.artifact_gc_task = None
        runtime.manager = manager
        session = LiveSession(
            device_id="D1",
            session_id="session",
            ws=Ws(),
            capabilities=frozenset({"push_job_id_v1"}),
            process_instance_id="process-a",
            owner_lock=asyncio.Lock(),
            http_base="http://server",
        )
        snapshots = await runtime._registration_active_snapshots(
            "D1",
            session,
            {
                "job_id": job_id,
                "attempt": 1,
                "artifact_id": str(uuid.uuid4()),
                "phase": "downloading",
            },
        )
        current = await manager.get_snapshot(job_id)
        assert current["devices"]["D1"]["state"] == DeviceState.FAILED.value
        assert current["devices"]["D1"]["device_fence"] is not None
        assert snapshots[-1]["revision"] == current["revision"]
    finally:
        store.close()


@pytest.mark.asyncio
async def test_server_restart_active_registration_does_not_reuse_revoked_http_lease(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    scheduler = None
    try:
        active = await downloading_job(store, manager)
        job_id = active["job_id"]
        artifact_id = active["artifact"]["artifact_id"]
        await manager.mark_reconciling(
            job_id,
            "D1",
            expected={DeviceState.DOWNLOADING},
            reason="server_restart_after_accept",
            deadline=now_ms() + 60_000,
        )
        registry = TransferRegistry()
        leases = PushTransferLeases()
        semaphore = asyncio.Semaphore(1)

        async def publish(_snapshot):
            return None

        scheduler = PushScheduler(
            manager=manager,
            transfer_registry=registry,
            transfer_slots=lambda: semaphore,
            sessions=lambda: {},
            publish=publish,
            send_timeout=1,
            accept_timeout=1,
            accept_reconciliation_timeout=1,
            reconciliation_timeout=1,
            leases=leases,
        )
        runtime = object.__new__(PushRuntime)
        runtime.pending_disconnects = set()
        runtime.disconnect_recovery_tasks = {}
        runtime.artifact_gc_task = None
        runtime.manager = manager
        runtime.scheduler = scheduler
        runtime.transfers = registry
        runtime.leases = leases
        session = live_session({"push_job_id_v1"}, session_id="new")

        snapshots = await runtime._registration_active_snapshots(
            "D1",
            session,
            {
                "job_id": job_id,
                "attempt": 1,
                "artifact_id": artifact_id,
                "phase": "downloading",
            },
        )

        # The old process's URL carried an in-memory lease that no longer exists.
        # Registration therefore cannot restore the old HTTP transfer or consume
        # a new slot; the client must report interruption and be manually resumed.
        assert snapshots == []
        key = TransferKey("push", "D1", job_id, 1)
        assert registry.get(key) is None
        assert leases.token(key) is None
        assert not semaphore.locked()
    finally:
        if scheduler is not None:
            await scheduler.stop()
        store.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("phase", ["validating", "applying"])
async def test_active_registration_after_offline_download_releases_transfer_slot(
    manager, phase
):
    store = manager.store
    active = await downloading_job(store, manager)
    job_id = active["job_id"]
    artifact_id = active["artifact"]["artifact_id"]
    await manager.mark_reconciling(
        job_id,
        "D1",
        expected={DeviceState.DOWNLOADING},
        reason="device_disconnect",
        deadline=now_ms() + 60_000,
    )
    leases = PushTransferLeases()
    registry = TransferRegistry(leases.revoke_now)
    key = TransferKey("push", "D1", job_id, 1)
    leases.issue(key, artifact_id)
    transfer = asyncio.get_running_loop().create_future()
    registry.register(key, transfer)
    runtime = object.__new__(PushRuntime)
    runtime.pending_disconnects = set()
    runtime.disconnect_recovery_tasks = {}
    runtime.artifact_gc_task = None
    runtime.manager = manager
    runtime.scheduler = Scheduler()
    runtime.transfers = registry
    runtime.leases = leases
    session = live_session({"push_job_id_v1"}, session_id="new")

    snapshots = await runtime._registration_active_snapshots(
        "D1",
        session,
        {
            "job_id": job_id,
            "attempt": 1,
            "artifact_id": artifact_id,
            "phase": phase,
        },
    )

    assert snapshots[-1]["devices"]["D1"]["state"] == phase
    # The download finished offline: the slot and HTTP lease must not wait for
    # the idle watchdog, which would push validation back to reconciliation.
    assert transfer.done()
    assert transfer.result() == "transfer_complete"
    assert leases.token(key) is None
    assert runtime.scheduler.active_transfer_slots == []


@pytest.mark.asyncio
async def test_interrupted_registration_uses_revision_without_session_resume_capability(
    manager,
):
    store = manager.store
    active = await downloading_job(store, manager)
    job_id = active["job_id"]
    artifact_id = active["artifact"]["artifact_id"]
    await manager.mark_reconciling(
        job_id,
        "D1",
        expected={DeviceState.DOWNLOADING},
        reason="device_disconnect",
        deadline=now_ms() + 60_000,
    )
    assignment = await manager.assignment(job_id, "D1")
    ws = Ws()
    runtime = object.__new__(PushRuntime)
    runtime.pending_disconnects = set()
    runtime.disconnect_recovery_tasks = {}
    runtime.artifact_gc_task = None
    runtime.manager = manager
    runtime.transfers = TransferRegistry()
    runtime.send_timeout = 1
    session = LiveSession(
        device_id="D1",
        session_id="session",
        ws=ws,
        capabilities=frozenset({"push_job_id_v1"}),
        process_instance_id="process-a",
        owner_lock=asyncio.Lock(),
        http_base="http://server",
    )

    snapshots = await runtime._registration_active_snapshots(
        "D1",
        session,
        {
            "job_id": job_id,
            "attempt": 1,
            "artifact_id": artifact_id,
            "phase": "downloading",
            "status": "interrupted",
            "revision": assignment["dispatch_revision"],
            "validated_offset": 0,
        },
    )

    current = await manager.assignment(job_id, "D1")
    assert len(snapshots) == 1
    assert snapshots[0]["devices"]["D1"]["state"] == DeviceState.QUEUED.value
    assert current["state"] == DeviceState.QUEUED.value
    assert current["validated_offset"] == 0
    assert ws.messages == []


class LegacyEvents:
    def __init__(self):
        self.events = []
        self.devices = {}

    async def forward_to_admins(self, event):
        self.events.append(event)


@pytest.mark.asyncio
async def test_legacy_busy_report_still_creates_opaque_fence(manager):
    store = manager.store
    ready = await ready_job(store, manager)
    job_id = ready["job_id"]
    await manager.store.enable_dispatch(job_id)
    claimed = await manager.claim_next(["D1"])
    assert claimed is not None
    snapshot = await manager.prepare_dispatch(
        job_id,
        "D1",
        accept_deadline=now_ms() + 60_000,
    )
    session = live_session(())
    published = []

    async def publish(value):
        published.append(value)

    scheduler = object.__new__(PushScheduler)
    scheduler.manager = manager
    scheduler.sessions = lambda: {"D1": session}
    scheduler.publish = publish
    scheduler.wake = lambda: None

    keep_transfer = await scheduler._handle_busy(
        snapshot,
        "D1",
        {"active_job": {"legacy": True, "dest_path": "/sdcard/STYLY/content"}},
    )

    fence = await manager.fenced_assignment_for_device("D1")
    current = await manager.assignment(job_id, "D1")
    assert keep_transfer is False
    assert fence["blocking_job_id"] is None
    assert fence["protocol_mode"] == ProtocolMode.LEGACY.value
    assert current["state"] == DeviceState.FAILED.value
    assert current["failure_code"] == "client_state_conflict"
    assert published


@pytest.mark.asyncio
async def test_first_terminal_result_queues_one_canonical_update_and_duplicate_is_noop(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        active = await downloading_job(store, manager)
        job_id = active["job_id"]
        await manager.transition_device(
            job_id,
            "D1",
            expected={DeviceState.DOWNLOADING},
            target=DeviceState.VALIDATING,
            fields={"transfer_completed_at": now_ms()},
        )
        await manager.transition_device(
            job_id,
            "D1",
            expected={DeviceState.VALIDATING},
            target=DeviceState.APPLYING,
            fields={"apply_started_at": now_ms()},
        )

        runtime = object.__new__(PushRuntime)
        runtime.pending_disconnects = set()
        runtime.disconnect_recovery_tasks = {}
        runtime.artifact_gc_task = None
        runtime.store = store
        runtime.manager = manager
        runtime.transfers = TransferRegistry()
        runtime.scheduler = Scheduler()
        runtime.send_timeout = 1
        runtime.legacy = LegacyEvents()
        runtime.sessions = {}
        runtime.pending_publications = {}
        runtime.publication_revisions = {}
        runtime.publication_wake = asyncio.Event()
        published = []
        original_publish = runtime.publish

        async def track_publish(snapshot):
            published.append(snapshot)
            await original_publish(snapshot)

        runtime.publish = track_publish
        session = LiveSession(
            device_id="D1",
            session_id="session",
            ws=Ws(),
            capabilities=frozenset({"push_job_id_v1"}),
            process_instance_id="process-a",
            owner_lock=asyncio.Lock(),
            http_base="http://server",
        )
        runtime.sessions["D1"] = session
        result = {
            "job_id": job_id,
            "attempt": 1,
            "status": "success",
            "dest_path": "/sdcard/STYLY/content",
            "added": 1,
            "updated": 2,
            "deleted": 3,
        }

        await runtime._handle_result("D1", result, owned_session=session)
        assert runtime.legacy.events == []
        assert runtime.pending_publications[job_id]["state"] == "succeeded"
        assert len(published) == 1

        queued_revision = runtime.pending_publications[job_id]["revision"]
        await runtime._handle_result("D1", result, owned_session=session)
        assert runtime.legacy.events == []
        assert len(published) == 1
        assert runtime.pending_publications[job_id]["revision"] == queued_revision
        assert session.ws.messages[-1]["accepted"] is True
        assert session.ws.messages[-1]["revision"] == queued_revision
    finally:
        store.close()


@pytest.mark.asyncio
async def test_exact_success_settles_even_when_applying_phase_was_lost(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        active = await downloading_job(store, manager)
        job_id = active["job_id"]
        runtime = object.__new__(PushRuntime)
        runtime.pending_disconnects = set()
        runtime.disconnect_recovery_tasks = {}
        runtime.artifact_gc_task = None
        runtime.store = store
        runtime.manager = manager
        runtime.transfers = TransferRegistry()
        runtime.scheduler = Scheduler()
        runtime.send_timeout = 1
        runtime.legacy = LegacyEvents()
        runtime.sessions = {}
        runtime.pending_publications = {}
        runtime.publication_revisions = {}
        runtime.publication_wake = asyncio.Event()
        ws = Ws()
        session = LiveSession(
            device_id="D1",
            session_id="session",
            ws=ws,
            capabilities=frozenset({"push_job_id_v1"}),
            process_instance_id="process-a",
            owner_lock=asyncio.Lock(),
            http_base="http://server",
        )
        runtime.sessions["D1"] = session
        waiter = asyncio.get_running_loop().create_future()
        runtime.transfers.register(TransferKey("push", "D1", job_id, 1), waiter)
        await runtime._handle_result(
            "D1",
            {
                "job_id": job_id,
                "attempt": 1,
                "status": "success",
                "added": 0,
                "updated": 0,
                "deleted": 0,
            },
            owned_session=session,
        )

        after = await store.get_snapshot(job_id)
        assert after["devices"]["D1"]["state"] == "succeeded"
        assert waiter.result() == "terminal_result"
        assert runtime.pending_publications[job_id]["revision"] == after["revision"]
        assert ws.messages[-1] == {
            "type": "PUSH_RESULT_ACK",
            "job_id": job_id,
            "attempt": 1,
            "accepted": True,
            "revision": after["revision"],
        }
    finally:
        store.close()


@pytest.mark.asyncio
async def test_result_settlement_failure_rolls_back_without_ack_or_transfer_release(
    manager, monkeypatch
):
    store = manager.store
    active = await downloading_job(store, manager)
    job_id = active["job_id"]
    before = await store.get_snapshot(job_id)
    runtime = object.__new__(PushRuntime)
    runtime.pending_disconnects = set()
    runtime.disconnect_recovery_tasks = {}
    runtime.artifact_gc_task = None
    runtime.store = store
    runtime.manager = manager
    runtime.transfers = TransferRegistry()
    runtime.scheduler = Scheduler()
    runtime.send_timeout = 1
    runtime.legacy = LegacyEvents()
    runtime.sessions = {}
    runtime.pending_publications = {}
    runtime.publication_revisions = {}
    runtime.publication_wake = asyncio.Event()
    ws = Ws()
    session = LiveSession(
        device_id="D1",
        session_id="session",
        ws=ws,
        capabilities=frozenset({"push_job_id_v1"}),
        process_instance_id="process-a",
        owner_lock=asyncio.Lock(),
        http_base="http://server",
    )
    runtime.sessions["D1"] = session
    waiter = asyncio.get_running_loop().create_future()
    runtime.transfers.register(TransferKey("push", "D1", job_id, 1), waiter)

    def fail_snapshot(_conn, _job_id):
        raise RuntimeError("injected settlement snapshot failure")

    monkeypatch.setattr(store, "_snapshot", fail_snapshot)
    with pytest.raises(RuntimeError, match="injected settlement snapshot failure"):
        await runtime._handle_result(
            "D1",
            {
                "job_id": job_id,
                "attempt": 1,
                "status": "success",
                "added": 1,
                "updated": 0,
                "deleted": 0,
            },
            owned_session=session,
        )

    monkeypatch.undo()
    after = await store.get_snapshot(job_id)
    assert after == before
    assert waiter.done() is False
    assert ws.messages == []
    assert runtime.pending_publications == {}


@pytest.mark.asyncio
@pytest.mark.parametrize("disconnect_first", [False, True])
async def test_replacement_register_preserves_healthy_download_in_either_order(
    tmp_path, disconnect_first
):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        active = await downloading_job(store, manager)
        job_id = active["job_id"]
        artifact_id = active["artifact"]["artifact_id"]
        runtime = object.__new__(PushRuntime)
        runtime.pending_disconnects = set()
        runtime.disconnect_recovery_tasks = {}
        runtime.artifact_gc_task = None
        runtime.manager = manager
        runtime.sessions = {}
        runtime.device_locks = {}
        runtime.registration_candidates = {}
        runtime.transfers = TransferRegistry()
        runtime.leases = PushTransferLeases()
        key = TransferKey("push", "D1", job_id, 1)
        lease = runtime.leases.issue(key, artifact_id)
        runtime.accept_reconciliation_timeout = 0.1
        runtime.reconciliation_timeout = 1
        runtime.scheduler = Scheduler()
        runtime.send_timeout = 1
        published = []

        async def publish(snapshot):
            published.append(snapshot)

        runtime.publish = publish
        lock = runtime._device_lock("D1")
        old = Ws()
        new = Ws()
        runtime.sessions["D1"] = LiveSession(
            device_id="D1",
            session_id="old",
            ws=old,
            capabilities=frozenset({"push_job_id_v1"}),
            process_instance_id="old-process",
            owner_lock=lock,
            http_base="http://server",
        )
        runtime.legacy = types.SimpleNamespace(devices={"D1": {"ws": new}})
        runtime.note_registration_candidate(new, "D1")

        entered = asyncio.Event()
        release = asyncio.Event()
        original_disconnect = manager.settle_disconnect
        first_query = True

        async def blocked_disconnect(device_id, **kwargs):
            nonlocal first_query
            if first_query:
                first_query = False
                entered.set()
                await release.wait()
            return await original_disconnect(device_id, **kwargs)

        manager.settle_disconnect = blocked_disconnect
        registration_call = runtime.register_device(
            new,
            {
                "device_id": "D1",
                "process_instance_id": str(uuid.uuid4()),
                "capabilities": ["push_job_id_v1"],
                "push_runtime": {"active": {
                    "job_id": job_id,
                    "attempt": 1,
                    "artifact_id": artifact_id,
                    "phase": "downloading",
                }},
            },
            "http://server",
        )
        if disconnect_first:
            disconnect = asyncio.create_task(runtime.disconnect_device("D1", old))
            await asyncio.wait_for(entered.wait(), timeout=1)
            registration = asyncio.create_task(registration_call)
        else:
            registration = asyncio.create_task(registration_call)
            await asyncio.wait_for(entered.wait(), timeout=1)
            disconnect = asyncio.create_task(runtime.disconnect_device("D1", old))
        await asyncio.sleep(0)
        assert not registration.done() and not disconnect.done()

        release.set()
        await asyncio.gather(disconnect, registration)

        current = await manager.assignment(job_id, "D1")
        assert runtime.sessions["D1"].ws is new
        assert current["state"] == DeviceState.DOWNLOADING.value
        assert published[-1]["devices"]["D1"]["state"] == DeviceState.DOWNLOADING.value
        assert runtime.leases.token(key) == lease.token
    finally:
        store.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("disconnect_first", [False, True])
@pytest.mark.parametrize("queued", [False, True])
async def test_replacement_register_withdraws_pending_resume(
    manager, disconnect_first, queued
):
    store = manager.store
    capabilities = frozenset({"push_job_id_v1", "push_resume_v1"})
    active = await downloading_job(store, manager)
    job_id = active["job_id"]
    await manager.mark_reconciling(
        job_id, "D1", expected={DeviceState.DOWNLOADING},
        reason="device_disconnect", deadline=now_ms() + 60_000,
    )
    if queued:
        outcome, _ = await manager.resume_interrupted(
            job_id, "D1", attempt=1,
            artifact_id=active["artifact"]["artifact_id"],
            dispatch_revision=active["devices"]["D1"]["dispatch_revision"],
            validated_offset=1,
        )
        assert outcome == "requeued"
    await manager.store.enable_dispatch(job_id, ["D1"])
    assert (await manager.assignment(job_id, "D1"))["queue_reason"] == "resumable_replay"
    runtime = runtime_for(manager)
    runtime.sessions = {}
    runtime.device_locks = {}
    runtime.registration_candidates = {}
    runtime.leases = PushTransferLeases()
    runtime.accept_reconciliation_timeout = 1
    runtime.reconciliation_timeout = 60
    runtime.scheduler = Scheduler()
    runtime.publish = lambda value: asyncio.sleep(0)
    old, new = Ws(), Ws()
    process_id = str(uuid.uuid4())
    runtime.sessions["D1"] = LiveSession(
        "D1", "old", old, capabilities, process_id,
        runtime._device_lock("D1"), "http://server",
    )
    runtime.legacy = types.SimpleNamespace(devices={"D1": {"ws": new}})
    runtime.note_registration_candidate(new, "D1")
    if disconnect_first:
        await runtime.disconnect_device("D1", old)
    await runtime.register_device(new, {
        "device_id": "D1", "process_instance_id": process_id,
        "capabilities": list(capabilities),
        "push_runtime": {"active": {
            "job_id": job_id, "attempt": 1,
            "artifact_id": active["artifact"]["artifact_id"],
            "revision": active["devices"]["D1"]["dispatch_revision"],
            "phase": "downloading", "status": "interrupted",
            "validated_offset": 1,
        }},
    }, "http://server")
    paused = await manager.get_snapshot(job_id)
    assert paused["devices"]["D1"]["state"] == "queued"
    assert paused["devices"]["D1"]["queue_reason"] == "device_offline"
    assert paused["devices"]["D1"]["resume_required"] is True
    assert await manager.claim_next(["D1"]) is None
    # A late close from the superseded socket cannot change the new owner.
    await runtime.disconnect_device("D1", old)
    assert await manager.get_snapshot(job_id) == paused
    assert runtime.sessions["D1"].ws is new
    await manager.store.enable_dispatch(job_id, ["D1"])
    assert await manager.claim_next(["D1"]) is not None


@pytest.mark.asyncio
@pytest.mark.parametrize("timeout_first", [False, True])
async def test_result_racing_unconfirmed_is_settled_atomically_and_acked(
    tmp_path, monkeypatch, timeout_first
):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        active = await downloading_job(store, manager)
        job_id = active["job_id"]
        await manager.mark_reconciling(
            job_id,
            "D1",
            expected={DeviceState.DOWNLOADING},
            reason="device_disconnect",
            deadline=now_ms(),
        )
        runtime = object.__new__(PushRuntime)
        runtime.pending_disconnects = set()
        runtime.disconnect_recovery_tasks = {}
        runtime.artifact_gc_task = None
        runtime.store = store
        runtime.manager = manager
        runtime.transfers = TransferRegistry()
        runtime.scheduler = Scheduler()
        runtime.send_timeout = 1
        runtime.legacy = LegacyEvents()
        runtime.sessions = {}
        runtime.pending_publications = {}
        runtime.publication_revisions = {}
        runtime.publication_wake = asyncio.Event()
        ws = Ws()
        session = LiveSession(
            device_id="D1",
            session_id="session",
            ws=ws,
            capabilities=frozenset({"push_job_id_v1"}),
            process_instance_id="process-a",
            owner_lock=asyncio.Lock(),
            http_base="http://server",
        )
        runtime.sessions["D1"] = session
        waiter = asyncio.get_running_loop().create_future()
        runtime.transfers.register(TransferKey("push", "D1", job_id, 1), waiter)
        if timeout_first:
            settle = manager.settle_result

            async def settle_after_timeout(*args, **kwargs):
                await manager.mark_unconfirmed(
                    job_id, "D1", "process-a", "reconciliation timed out"
                )
                return await settle(*args, **kwargs)

            monkeypatch.setattr(manager, "settle_result", settle_after_timeout)
        await runtime._handle_result(
            "D1",
            {
                "job_id": job_id,
                "attempt": 1,
                "status": "success",
                "added": 1,
                "updated": 0,
                "deleted": 0,
            },
            owned_session=session,
        )

        if not timeout_first:
            # The timer may have read this row before the terminal result committed.
            # Its fenced transition must fail closed after the result wins.
            with pytest.raises(StoreConflict):
                await manager.mark_unconfirmed(
                    job_id, "D1", "process-a", "stale reconciliation timeout"
                )

        after = await store.get_snapshot(job_id)
        expected_state = "unconfirmed" if timeout_first else "succeeded"
        device = after["devices"]["D1"]
        assert device["state"] == expected_state
        assert device["device_fence"] is None
        if timeout_first:
            assert device["failure"]["code"] == "reconciliation_timeout"
            assert device["result"] == {
                "added": None, "updated": None, "deleted": None
            }
        else:
            assert device["failure"] is None
            assert device["result"] == {"added": 1, "updated": 0, "deleted": 0}
        assert waiter.result() == "terminal_result"
        assert ws.messages[-1]["accepted"] is True
        assert ws.messages[-1]["revision"] == after["revision"]
    finally:
        store.close()


@pytest.mark.asyncio
async def test_unknown_result_receives_negative_nonretryable_ack(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        runtime = object.__new__(PushRuntime)
        runtime.pending_disconnects = set()
        runtime.disconnect_recovery_tasks = {}
        runtime.artifact_gc_task = None
        runtime.store = store
        runtime.manager = manager
        runtime.transfers = TransferRegistry()
        runtime.scheduler = Scheduler()
        runtime.send_timeout = 1
        runtime.legacy = LegacyEvents()
        runtime.sessions = {}
        ws = Ws()
        session = LiveSession(
            device_id="D1",
            session_id="session",
            ws=ws,
            capabilities=frozenset({"push_job_id_v1"}),
            process_instance_id="process-a",
            owner_lock=asyncio.Lock(),
            http_base="http://server",
        )
        runtime.sessions["D1"] = session
        job_id = str(uuid.uuid4())

        await runtime._handle_result(
            "D1",
            {
                "job_id": job_id,
                "attempt": 1,
                "status": "fail",
                "failure_code": "apply_failed",
            },
            owned_session=session,
        )

        assert ws.messages[-1] == {
            "type": "PUSH_RESULT_ACK",
            "job_id": job_id,
            "attempt": 1,
            "accepted": False,
            "reason": "unknown_job",
            "retryable": False,
        }
    finally:
        store.close()


@pytest.mark.asyncio
async def test_legacy_result_does_not_settle_preserved_assignment(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        legacy_request = request()
        _, created = await manager.store.create_job(
            legacy_request,
            {"D1": (ProtocolMode.LEGACY, set())},
            60_000,
        )
        job_id = created["job_id"]
        await store.start_upload(job_id)
        await store.mark_packaging(job_id, 1, 1)
        await store.publish_artifact(job_id, {
            "artifact_id": str(uuid.uuid4()),
            "storage_name": str(uuid.uuid4()) + ".zip",
            "display_filename": "content.zip",
            "byte_size": 1,
            "sha256": "a" * 64,
            "entry_count": 1,
        })
        await manager.store.enable_dispatch(job_id)
        assert await manager.claim_next(["D1"]) is not None
        await manager.prepare_dispatch(
            job_id,
            "D1",
            accept_deadline=None,
        )
        store._call_sync(
            lambda conn: (
                conn.execute(
                    "UPDATE push_job_devices SET protocol_mode='legacy' "
                    "WHERE job_id=? AND device_id=?",
                    (job_id, "D1"),
                ),
                conn.commit(),
            )
        )
        await manager.transition_device(
            job_id,
            "D1",
            expected={DeviceState.DISPATCHING},
            target=DeviceState.DOWNLOADING,
            fields={"accepted_at": now_ms()},
        )
        await manager.transition_device(
            job_id,
            "D1",
            expected={DeviceState.DOWNLOADING},
            target=DeviceState.VALIDATING,
        )
        await manager.transition_device(
            job_id,
            "D1",
            expected={DeviceState.VALIDATING},
            target=DeviceState.APPLYING,
        )

        runtime = object.__new__(PushRuntime)
        runtime.pending_disconnects = set()
        runtime.disconnect_recovery_tasks = {}
        runtime.artifact_gc_task = None
        runtime.store = store
        runtime.manager = manager
        runtime.transfers = TransferRegistry()
        runtime.scheduler = Scheduler()
        runtime.send_timeout = 1
        runtime.legacy = LegacyEvents()
        runtime.device_locks = {}
        runtime.registration_candidates = {}
        runtime.registration_previous = {}
        runtime.pending_publications = {}
        runtime.publication_revisions = {}
        runtime.publication_wake = asyncio.Event()
        ws = Ws()
        lock = runtime._device_lock("D1")
        session = LiveSession(
            device_id="D1",
            session_id="legacy",
            ws=ws,
            capabilities=frozenset(),
            process_instance_id=None,
            owner_lock=lock,
            http_base="http://server",
        )
        runtime.sessions = {"D1": session}
        consumed = await runtime.handle_device_message(
            ws,
            "D1",
            {
                "type": "PUSH_FILES_RESULT",
                "status": "success",
                "dest_path": "/sdcard/STYLY/content",
                "added": 1,
                "updated": 0,
                "deleted": 0,
            },
        )
        assert consumed is False
        assert runtime.legacy.events == []
        assert ws.messages == []
        current = await manager.assignment(job_id, "D1")
        assert current["state"] == DeviceState.APPLYING.value
        assert current["protocol_mode"] == ProtocolMode.LEGACY.value
    finally:
        store.close()
