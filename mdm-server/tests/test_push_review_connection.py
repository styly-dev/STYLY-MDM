import asyncio
import json
import types
import uuid

import pytest

from styly_mdm import push_runtime
from styly_mdm.push_job_manager import PushJobManager
from styly_mdm.push_job_store import PushJobStore, now_ms
from styly_mdm.push_jobs import DeviceState, ProtocolMode, canonicalize_create_request
from styly_mdm.push_runtime import PushRuntime
from styly_mdm.push_scheduler import LiveSession
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
    _, created = await manager.create_job(request(), protocols(), 60_000)
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


async def downloading_job(store, manager, capabilities=None):
    ready = await ready_job(store, manager)
    job_id = ready["job_id"]
    await manager.enable_dispatch(job_id)
    claimed = await manager.claim_next(["D1"])
    assert claimed is not None
    await manager.prepare_dispatch(
        job_id,
        "D1",
        protocol_mode=ProtocolMode.JOB_V1,
        live_capabilities=capabilities or {"push_job_id_v1"},
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

    def wake(self):
        self.wake_count += 1

    async def send_reconcile(self, session, snapshot, device_id):
        self.reconcile_calls.append((session, snapshot, device_id))


class RegistrationManager:
    def __init__(self):
        self.clear_calls = 0

    async def clear_fence_on_process_replacement(self, *_args):
        self.clear_calls += 1
        return []

    async def active_assignment_for_device(self, _device_id):
        return None


@pytest.mark.asyncio
async def test_resume_rejection_carries_exact_cleanup_authority():
    runtime = object.__new__(PushRuntime)
    runtime.send_timeout = 1
    ws = Ws()
    session = LiveSession(
        device_id="D1",
        session_id="session",
        ws=ws,
        capabilities=frozenset({"push_job_id_v1", "push_resume_v1"}),
        process_instance_id=str(uuid.uuid4()),
        owner_lock=asyncio.Lock(),
        http_base="http://server",
    )
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
    runtime.send_timeout = 1
    ws = Ws()
    session = LiveSession(
        device_id="D1",
        session_id="session",
        ws=ws,
        capabilities=frozenset({"push_job_id_v1", "push_resume_v1"}),
        process_instance_id=str(uuid.uuid4()),
        owner_lock=asyncio.Lock(),
        http_base="http://server",
    )

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
            return False, {"job_id": "job-1"}

    runtime = object.__new__(PushRuntime)
    runtime.manager = Manager()
    runtime.send_timeout = 1
    runtime.scheduler = None
    runtime.publish = lambda _snapshot: asyncio.sleep(0)
    ws = Ws()
    session = LiveSession(
        device_id="D1",
        session_id="session",
        ws=ws,
        capabilities=frozenset({"push_job_id_v1", "push_resume_v1"}),
        process_instance_id=str(uuid.uuid4()),
        owner_lock=asyncio.Lock(),
        http_base="http://server",
    )

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
async def test_interrupted_reconciliation_without_artifact_never_resumes():
    artifact_id = str(uuid.uuid4())

    class Manager:
        def __init__(self):
            self.resume_calls = 0

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
            self.resume_calls += 1
            return True, {"job_id": "job-1"}

    runtime = object.__new__(PushRuntime)
    runtime.manager = Manager()
    runtime.send_timeout = 1
    runtime.scheduler = None
    runtime.publish = lambda _snapshot: asyncio.sleep(0)
    ws = Ws()
    session = LiveSession(
        device_id="D1",
        session_id="session",
        ws=ws,
        capabilities=frozenset({"push_job_id_v1", "push_resume_v1"}),
        process_instance_id=str(uuid.uuid4()),
        owner_lock=asyncio.Lock(),
        http_base="http://server",
    )

    await runtime._handle_reconcile_report(
        session,
        "D1",
        {
            "type": "PUSH_RECONCILE_REPORT",
            "job_id": "job-1",
            "attempt": 1,
            "revision": 7,
            "validated_offset": 3,
            "status": "interrupted",
            "phase": "downloading",
        },
    )

    assert runtime.manager.resume_calls == 0
    assert ws.messages[0]["type"] == "PUSH_RESUME_REJECTED"
    assert ws.messages[0]["artifact_id"] == artifact_id
    assert ws.messages[0]["revision"] == 7


@pytest.mark.asyncio
async def test_push_state_retry_targets_only_affected_online_devices_without_scheduler_wake():
    runtime = object.__new__(PushRuntime)
    runtime.send_timeout = 1
    runtime.admin_send_timeout = 1
    runtime.scheduler = Scheduler()
    affected_ws = Ws()
    healthy_ws = Ws()
    runtime.sessions = {
        "D1": LiveSession(
            device_id="D1",
            session_id="one",
            ws=affected_ws,
            capabilities=frozenset({"push_state_retry_v1"}),
            process_instance_id=str(uuid.uuid4()),
            owner_lock=asyncio.Lock(),
            http_base="http://server",
        ),
        "D2": LiveSession(
            device_id="D2",
            session_id="two",
            ws=healthy_ws,
            capabilities=frozenset({"push_state_retry_v1", "push_job_id_v1"}),
            process_instance_id=str(uuid.uuid4()),
            owner_lock=asyncio.Lock(),
            http_base="http://server",
        ),
    }
    runtime.legacy = types.SimpleNamespace(devices={
        "D1": {"push_state_status": "unavailable"},
        "D2": {"push_state_status": "available"},
    })
    admin = Ws()

    consumed = await runtime.handle_admin_message(admin, {
        "type": "RETRY_PUSH_STATE",
        "target_devices": ["D1", "D2", "offline", "D1"],
    })

    assert consumed is True
    assert affected_ws.messages == [{"type": "RETRY_PUSH_STATE"}]
    assert healthy_ws.messages == []
    assert admin.messages == [{
        "type": "PUSH_STATE_RETRY_SENT",
        "sent_count": 1,
        "target_count": 3,
    }]
    assert runtime.scheduler.wake_count == 0


@pytest.mark.asyncio
async def test_push_state_retry_result_refreshes_capability_without_dispatching():
    runtime = object.__new__(PushRuntime)
    runtime.scheduler = Scheduler()
    runtime.device_locks = {}
    ws = Ws()
    session = LiveSession(
        device_id="D1",
        session_id="one",
        ws=ws,
        capabilities=frozenset({"push_state_retry_v1"}),
        process_instance_id=str(uuid.uuid4()),
        owner_lock=asyncio.Lock(),
        http_base="http://server",
    )
    runtime.sessions = {"D1": session}
    broadcasts = []
    admin_messages = []
    runtime.legacy = types.SimpleNamespace(
        devices={"D1": {"push_state_status": "unavailable"}},
        device_registry={"D1": {}},
        save_registry=lambda: None,
        broadcast_device_list=lambda: _record_async(broadcasts, "devices"),
        _broadcast_admin_message=lambda message: _record_async(
            admin_messages, json.loads(message)
        ),
    )

    consumed = await runtime.handle_device_message(ws, "D1", {
        "type": "PUSH_STATE_RETRY_RESULT",
        "status": "success",
        "capabilities": ["push_state_retry_v1", "push_job_id_v1", "push_resume_v1"],
        "push_state": {"status": "available"},
        "push_runtime": {"active": None},
    })

    assert consumed is True
    assert session.capabilities == frozenset({
        "push_state_retry_v1", "push_job_id_v1", "push_resume_v1",
    })
    assert session.reported_push_runtime == {"active": None}
    assert runtime.legacy.devices["D1"]["push_state_status"] == "available"
    assert broadcasts == ["devices"]
    assert admin_messages[0]["type"] == "PUSH_STATE_RETRY_RESULT"
    assert runtime.scheduler.wake_count == 0


@pytest.mark.asyncio
async def test_push_state_retry_reconciles_identity_but_keeps_restart_pause(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        active = await downloading_job(
            store, manager, {"push_job_id_v1", "push_resume_v1"}
        )
        job_id = active["job_id"]
        artifact_id = active["artifact"]["artifact_id"]
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
        assignment = await manager.assignment(job_id, "D1")
        ws = Ws()
        session = LiveSession(
            device_id="D1",
            session_id="one",
            ws=ws,
            capabilities=frozenset({"push_state_retry_v1"}),
            process_instance_id=str(uuid.uuid4()),
            owner_lock=asyncio.Lock(),
            http_base="http://server",
        )
        runtime = object.__new__(PushRuntime)
        runtime.manager = manager
        runtime.sessions = {"D1": session}
        runtime.device_locks = {"D1": session.owner_lock}
        runtime.transfers = TransferRegistry()
        runtime.send_timeout = 1
        runtime.scheduler = Scheduler()
        published = []
        runtime.publish = lambda snapshot: _record_async(published, snapshot)
        runtime.legacy = types.SimpleNamespace(
            devices={"D1": {"push_state_status": "unavailable"}},
            device_registry={"D1": {}},
            save_registry=lambda: None,
            broadcast_device_list=lambda: _record_async([], "devices"),
            _broadcast_admin_message=lambda _message: _record_async([], "admin"),
        )

        consumed = await runtime.handle_device_message(ws, "D1", {
            "type": "PUSH_STATE_RETRY_RESULT",
            "status": "success",
            "capabilities": ["push_state_retry_v1", "push_job_id_v1", "push_resume_v1"],
            "push_state": {"status": "available"},
            "push_runtime": {"active": {
                "job_id": job_id,
                "attempt": 1,
                "artifact_id": artifact_id,
                "phase": "downloading",
                "status": "interrupted",
                "revision": assignment["dispatch_revision"],
                "validated_offset": 0,
            }},
        })

        current = await manager.get_snapshot(job_id)
        assert consumed is True
        assert current["devices"]["D1"]["state"] == DeviceState.QUEUED.value
        assert current["dispatch_enabled"] is False
        assert current["dispatch_paused_reason"] == "server_restart"
        assert published[-1]["revision"] == current["revision"]
        assert runtime.scheduler.wake_count == 0
    finally:
        store.close()


@pytest.mark.asyncio
async def test_operator_resume_reconciles_recovered_state_before_dispatch(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
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
            "D1": LiveSession(
                device_id="D1",
                session_id="one",
                ws=Ws(),
                capabilities=frozenset(
                    {"push_state_retry_v1", "push_job_id_v1", "push_resume_v1"}
                ),
                process_instance_id=str(uuid.uuid4()),
                owner_lock=asyncio.Lock(),
                http_base="http://server",
                reported_push_runtime={"active": {"status": "interrupted"}},
            )
        }
        admin = Ws()

        consumed = await runtime.handle_admin_message(
            admin, {"type": "PUSH_FILES", "job_id": job_id}
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
            admin, {"type": "PUSH_FILES", "job_id": job_id}
        )
        assert runtime.scheduler.reconcile_calls == []
    finally:
        store.close()


async def _record_async(target, value):
    target.append(value)


@pytest.mark.asyncio
async def test_initial_snapshot_failure_closes_admin_for_reconnect():
    class Manager:
        async def list_snapshots(self, *_args):
            raise RuntimeError("snapshot unavailable")

    runtime = object.__new__(PushRuntime)
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
        runtime.store = store
        runtime.scheduler = Scheduler()
        runtime.admin_send_timeout = 1
        runtime.legacy = types.SimpleNamespace(MAX_CONCURRENT_TRANSFERS=5)
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
        runtime.store = store
        runtime.scheduler = Scheduler()
        runtime.admin_send_timeout = 0.01
        ws = BlockingWs()
        runtime.legacy = types.SimpleNamespace(
            MAX_CONCURRENT_TRANSFERS=5,
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
        runtime.store = store
        runtime.scheduler = Scheduler()
        runtime.admin_send_timeout = 0.01
        ws = BlockingWs()
        runtime.legacy = types.SimpleNamespace(
            MAX_CONCURRENT_TRANSFERS=5,
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
    runtime.legacy = types.SimpleNamespace(devices={"D1": {"ws": ws}})
    runtime.manager = manager
    runtime.sessions = {}
    runtime.device_locks = {}
    runtime.registration_candidates = {"D1": ws}
    runtime.send_timeout = 1
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
async def test_unavailable_push_state_does_not_report_absence_or_request_reconcile():
    class ReconcilingManager(RegistrationManager):
        async def active_assignment_for_device(self, _device_id):
            return {"state": DeviceState.RECONCILING.value}

    ws = Ws()
    manager = ReconcilingManager()
    runtime = object.__new__(PushRuntime)
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

    assert runtime.sessions["D1"].reported_push_runtime == {"active": None}
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
        await manager.enable_dispatch(next_job["job_id"])

        ws = Ws()
        runtime = object.__new__(PushRuntime)
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
        runtime = object.__new__(PushRuntime)
        runtime.manager = manager
        runtime.sessions = {}
        runtime.device_locks = {}
        runtime.registration_candidates = {}
        runtime.transfers = TransferRegistry()
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
        artifact_id = active["artifact"]["artifact_id"]
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
        assert future.done()
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
async def test_interrupted_registration_without_resume_capability_stays_reconciling(
    tmp_path,
):
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
            deadline=now_ms() + 60_000,
        )
        assignment = await manager.assignment(job_id, "D1")
        ws = Ws()
        runtime = object.__new__(PushRuntime)
        runtime.manager = manager
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
        assert snapshots == []
        assert current["state"] == DeviceState.RECONCILING.value
        assert current["reconciliation_deadline"] is not None
        assert ws.messages[0]["type"] == "PUSH_RESUME_REJECTED"
        assert ws.messages[0]["artifact_id"] == artifact_id
        assert ws.messages[0]["revision"] == assignment["dispatch_revision"]
    finally:
        store.close()


class LegacyEvents:
    def __init__(self):
        self.events = []

    async def forward_to_admins(self, event):
        self.events.append(event)


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

        queued_revision = runtime.pending_publications[job_id]["revision"]
        await runtime._handle_result("D1", result, owned_session=session)
        assert runtime.legacy.events == []
        assert runtime.pending_publications[job_id]["revision"] == queued_revision
        assert session.ws.messages[-1]["accepted"] is True
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
async def test_disconnect_transition_finishes_before_replacement_register(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        active = await downloading_job(store, manager)
        job_id = active["job_id"]
        artifact_id = active["artifact"]["artifact_id"]
        runtime = object.__new__(PushRuntime)
        runtime.manager = manager
        runtime.sessions = {}
        runtime.device_locks = {}
        runtime.registration_candidates = {}
        runtime.transfers = TransferRegistry()
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
        original_active = manager.active_assignment_for_device
        first_query = True

        async def blocked_active(device_id):
            nonlocal first_query
            if first_query:
                first_query = False
                entered.set()
                await release.wait()
            return await original_active(device_id)

        manager.active_assignment_for_device = blocked_active
        disconnect = asyncio.create_task(runtime.disconnect_device("D1", old))
        await asyncio.wait_for(entered.wait(), timeout=1)
        registration = asyncio.create_task(runtime.register_device(
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
        ))
        await asyncio.sleep(0)
        assert not registration.done()

        release.set()
        await asyncio.gather(disconnect, registration)

        current = await manager.assignment(job_id, "D1")
        assert runtime.sessions["D1"].ws is new
        assert current["state"] == DeviceState.DOWNLOADING.value
        assert published[-1]["devices"]["D1"]["state"] == DeviceState.DOWNLOADING.value
    finally:
        store.close()


@pytest.mark.asyncio
async def test_result_racing_unconfirmed_clears_exact_fence_and_is_acked(tmp_path):
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
        settle = store.settle_result

        async def settle_after_timeout(*args, **kwargs):
            await manager.mark_unconfirmed(
                job_id, "D1", "process-a", "reconciliation timed out"
            )
            return await settle(*args, **kwargs)

        store.settle_result = settle_after_timeout
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

        after = await store.get_snapshot(job_id)
        assert after["devices"]["D1"]["state"] == "unconfirmed"
        assert after["devices"]["D1"]["device_fence"] is None
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
async def test_canonical_legacy_result_is_consumed_once(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        legacy_request = request()
        _, created = await manager.create_job(
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
        await manager.enable_dispatch(job_id)
        assert await manager.claim_next(["D1"]) is not None
        await manager.prepare_dispatch(
            job_id,
            "D1",
            protocol_mode=ProtocolMode.LEGACY,
            live_capabilities=set(),
            accept_deadline=None,
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
        assert consumed is True
        assert runtime.legacy.events == []
        assert runtime.pending_publications[job_id]["devices"]["D1"]["state"] == "succeeded"
    finally:
        store.close()
