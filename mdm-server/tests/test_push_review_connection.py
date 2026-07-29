import asyncio
import json
import types
import uuid

import pytest

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


async def downloading_job(store, manager):
    ready = await ready_job(store, manager)
    job_id = ready["job_id"]
    await manager.enable_dispatch(job_id)
    claimed = await manager.claim_next(["D1"])
    assert claimed is not None
    await manager.prepare_dispatch(
        job_id,
        "D1",
        protocol_mode=ProtocolMode.JOB_V1,
        live_capabilities={"push_job_id_v1"},
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


class Scheduler:
    def __init__(self):
        self.wake_count = 0

    def wake(self):
        self.wake_count += 1


class RegistrationManager:
    def __init__(self):
        self.clear_calls = 0

    async def clear_fence_on_process_replacement(self, *_args):
        self.clear_calls += 1
        return []

    async def active_assignment_for_device(self, _device_id):
        return None


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


class LegacyEvents:
    def __init__(self):
        self.events = []

    async def forward_to_admins(self, event):
        self.events.append(event)


@pytest.mark.asyncio
async def test_first_terminal_result_is_derived_once_and_duplicate_is_noop(tmp_path):
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
        derived = [
            event for event in runtime.legacy.events
            if event.get("type") == "PUSH_FILES_RESULT"
        ]
        device_events = [
            event for event in runtime.legacy.events
            if event.get("type") == "PUSH_DEVICE_STATE"
        ]
        assert len(derived) == 1
        assert derived[0]["job_id"] == job_id
        assert isinstance(derived[0]["revision"], int)
        assert device_events[-1]["device_ids"] == ["D1"]

        event_count = len(runtime.legacy.events)
        await runtime._handle_result("D1", result, owned_session=session)
        assert len(runtime.legacy.events) == event_count
        assert session.ws.messages[-1]["accepted"] is True
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
        derived = [
            event for event in runtime.legacy.events
            if event.get("type") == "PUSH_FILES_RESULT"
        ]
        assert len(derived) == 1
        assert derived[0]["job_id"] == job_id
    finally:
        store.close()
