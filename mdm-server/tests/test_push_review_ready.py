import asyncio
import json
import uuid

import pytest

from styly_mdm.push_job_manager import PushJobManager
from styly_mdm.push_job_store import PushJobStore, UploadDeadlineExpired
from styly_mdm.push_jobs import ProtocolMode, PushJobError, canonicalize_create_request
from styly_mdm.push_runtime import PushRuntime
from styly_mdm.push_scheduler import LiveSession
from styly_mdm.transfer_registry import TransferRegistry


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


def test_client_request_id_requires_uuid_v4():
    with pytest.raises(PushJobError, match="UUIDv4"):
        canonicalize_create_request({
            "client_request_id": "00000000-0000-1000-8000-000000000000",
            "target_devices": ["D1"],
            "mode": "push",
            "dest_path": "/sdcard/STYLY/content",
            "source": {
                "display_name": "content",
                "declared_file_count": 1,
                "declared_total_bytes": 1,
            },
        })


@pytest.mark.asyncio
async def test_expired_upload_exposes_the_committed_snapshot(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    try:
        _, created = await store.create_job(request(), protocols(), 0)
        with pytest.raises(UploadDeadlineExpired) as captured:
            await store.start_upload(created["job_id"])
        committed = captured.value.snapshot
        assert committed["state"] == "interrupted"
        assert committed["failure"]["code"] == "upload_not_started_timeout"
        current = await store.get_snapshot(created["job_id"])
        assert current["revision"] == committed["revision"]
    finally:
        store.close()


class _Artifacts:
    def __init__(self):
        self.cleaned = []

    def cleanup_work_best_effort(self, job_id):
        self.cleaned.append(job_id)


class _Request:
    def __init__(self, job_id):
        self.match_info = {"job_id": job_id}


@pytest.mark.asyncio
async def test_upload_handler_publishes_committed_expiry(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    try:
        _, created = await store.create_job(request(), protocols(), 0)
        runtime = object.__new__(PushRuntime)
        runtime.store = store
        runtime.artifacts = _Artifacts()
        runtime.scheduler = _Scheduler()
        published = []
        rearmed = []

        async def publish(snapshot):
            published.append(snapshot)

        runtime.publish = publish
        runtime.arm_created_deadline = lambda: rearmed.append(True)
        response = await runtime.upload_handler(_Request(created["job_id"]))
        assert response.status == 409
        assert published[-1]["state"] == "interrupted"
        assert runtime.artifacts.cleaned == [created["job_id"]]
        assert rearmed == [True]
        assert runtime.scheduler.wake_count == 1
    finally:
        store.close()


@pytest.mark.asyncio
async def test_opaque_job_v1_fence_has_exact_reconcile_and_clear(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        _, visible = await manager.create_job(request(), protocols(), 60_000)
        foreign_job = str(uuid.uuid4())
        foreign_artifact = str(uuid.uuid4())
        opaque = manager.opaque_identity_for_active({
            "job_id": foreign_job,
            "attempt": 1,
            "artifact_id": foreign_artifact,
            "ignored": "not persisted",
        })
        await manager.add_opaque_fence(
            "D1", opaque, ProtocolMode.JOB_V1, "process-a", "unknown active job"
        )
        assert await manager.opaque_reconcile_target("D1") == {
            "job_id": foreign_job,
            "attempt": 1,
            "artifact_id": foreign_artifact,
        }
        matched, snapshots = await manager.clear_matching_opaque_fence(
            "D1", foreign_job, 1
        )
        assert matched
        current = next(item for item in snapshots if item["job_id"] == visible["job_id"])
        assert current["devices"]["D1"]["device_fence"] is None
    finally:
        store.close()


@pytest.mark.asyncio
async def test_mismatched_opaque_evidence_does_not_clear_fence(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        await manager.create_job(request(), protocols(), 60_000)
        foreign_job = str(uuid.uuid4())
        await manager.add_opaque_fence(
            "D1",
            manager.opaque_identity_for_active({"job_id": foreign_job, "attempt": 1}),
            ProtocolMode.JOB_V1,
            "process-a",
            "unknown active job",
        )
        matched, snapshots = await manager.clear_matching_opaque_fence(
            "D1", str(uuid.uuid4()), 1
        )
        assert not matched
        assert snapshots == []
        assert await manager.opaque_reconcile_target("D1") is not None
    finally:
        store.close()


class _Ws:
    def __init__(self):
        self.messages = []

    async def send_str(self, value):
        self.messages.append(json.loads(value))


class _Scheduler:
    def __init__(self):
        self.wake_count = 0
        self.reconciles = []

    def wake(self):
        self.wake_count += 1

    async def send_exact_reconcile(self, session, job_id, attempt, artifact_id):
        self.reconciles.append((session.device_id, job_id, attempt, artifact_id))


@pytest.mark.asyncio
async def test_runtime_acks_unknown_exact_result_and_clears_opaque_fence(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        await manager.create_job(request(), protocols(), 60_000)
        foreign_job = str(uuid.uuid4())
        await manager.add_opaque_fence(
            "D1",
            manager.opaque_identity_for_active({"job_id": foreign_job, "attempt": 1}),
            ProtocolMode.JOB_V1,
            "process-a",
            "unknown active job",
        )
        runtime = object.__new__(PushRuntime)
        runtime.manager = manager
        runtime.transfers = TransferRegistry()
        runtime.scheduler = _Scheduler()
        runtime.send_timeout = 1
        runtime.sessions = {}
        published = []

        async def publish(snapshot):
            published.append(snapshot)

        runtime.publish = publish
        ws = _Ws()
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
        await runtime._handle_result(
            "D1",
            {"job_id": foreign_job, "attempt": 1, "status": "fail"},
            owned_session=session,
        )
        assert ws.messages[-1] == {
            "type": "PUSH_RESULT_ACK",
            "job_id": foreign_job,
            "attempt": 1,
            "accepted": True,
        }
        assert published
        assert await manager.opaque_reconcile_target("D1") is None
    finally:
        store.close()


@pytest.mark.asyncio
async def test_runtime_reconcile_action_targets_canonical_opaque_identity(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        await manager.create_job(request(), protocols(), 60_000)
        foreign_job = str(uuid.uuid4())
        artifact_id = str(uuid.uuid4())
        await manager.add_opaque_fence(
            "D1",
            manager.opaque_identity_for_active({
                "job_id": foreign_job,
                "attempt": 1,
                "artifact_id": artifact_id,
            }),
            ProtocolMode.JOB_V1,
            "process-a",
            "unknown active job",
        )
        runtime = object.__new__(PushRuntime)
        runtime.manager = manager
        runtime.scheduler = _Scheduler()
        session = LiveSession(
            device_id="D1",
            session_id="session",
            ws=_Ws(),
            capabilities=frozenset({"push_job_id_v1"}),
            process_instance_id="process-a",
            owner_lock=asyncio.Lock(),
            http_base="http://server",
        )
        runtime.sessions = {"D1": session}
        await runtime.request_reconcile("D1")
        assert runtime.scheduler.reconciles == [
            ("D1", foreign_job, 1, artifact_id)
        ]
    finally:
        store.close()


@pytest.mark.asyncio
async def test_created_deadline_rearm_wakes_single_owner_without_cancelling():
    runtime = object.__new__(PushRuntime)
    runtime.created_deadline_wake = asyncio.Event()
    blocker = asyncio.create_task(asyncio.Event().wait())
    runtime.created_deadline_task = blocker
    try:
        runtime.arm_created_deadline()
        assert runtime.created_deadline_task is blocker
        assert not blocker.cancelled()
        assert runtime.created_deadline_wake.is_set()
    finally:
        blocker.cancel()
        await asyncio.gather(blocker, return_exceptions=True)



@pytest.mark.asyncio
async def test_created_deadline_loop_wakes_scheduler_after_expiry():
    published = []
    cleaned = []
    wake_event = asyncio.Event()

    class Manager:
        calls = 0

        async def next_created_deadline(self):
            self.calls += 1
            return ("job-1", 0) if self.calls == 1 else None

    class Store:
        async def expire_created(self, job_id, deadline):
            return {"job_id": job_id, "revision": 2}

    class Artifacts:
        def cleanup_work_best_effort(self, job_id):
            cleaned.append(job_id)

    class Scheduler:
        def wake(self):
            wake_event.set()

    runtime = object.__new__(PushRuntime)
    runtime.manager = Manager()
    runtime.store = Store()
    runtime.artifacts = Artifacts()
    runtime.scheduler = Scheduler()
    runtime.created_deadline_task = None
    runtime.created_deadline_wake = asyncio.Event()

    async def publish(snapshot):
        published.append(snapshot)

    runtime.publish = publish
    runtime.arm_created_deadline()
    task = runtime.created_deadline_task
    assert task is not None
    try:
        await asyncio.wait_for(wake_event.wait(), timeout=1)
        assert published == [{"job_id": "job-1", "revision": 2}]
        assert cleaned == ["job-1"]
        assert not task.cancelled()
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


@pytest.mark.asyncio
async def test_pre_dispatch_failure_wakes_scheduler():
    class Store:
        async def fail_pre_dispatch(self, job_id, state, code, detail):
            return {"job_id": job_id, "revision": 2}

    runtime = object.__new__(PushRuntime)
    runtime.store = Store()
    runtime.scheduler = _Scheduler()
    rearmed = []
    published = []
    runtime.arm_created_deadline = lambda: rearmed.append(True)

    async def publish(snapshot):
        published.append(snapshot)

    runtime.publish = publish
    await runtime._record_upload_failure(
        "job-1",
        __import__("styly_mdm.push_jobs", fromlist=["JobState"]).JobState.INTERRUPTED,
        "upload_interrupted",
        "test",
    )
    assert published == [{"job_id": "job-1", "revision": 2}]
    assert rearmed == [True]
    assert runtime.scheduler.wake_count == 1



@pytest.mark.asyncio
async def test_process_replacement_revises_terminal_opaque_fence_display(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        _, created = await manager.create_job(request(), protocols(), 60_000)
        await manager.fail_pre_dispatch(
            created["job_id"],
            __import__("styly_mdm.push_jobs", fromlist=["JobState"]).JobState.INTERRUPTED,
            "test_terminal",
            "terminal before opaque fence",
        )
        foreign_job = str(uuid.uuid4())
        snapshots = await manager.add_opaque_fence(
            "D1",
            manager.opaque_identity_for_active({"job_id": foreign_job, "attempt": 1}),
            ProtocolMode.JOB_V1,
            "process-a",
            "unknown active job",
        )
        before = next(
            item for item in snapshots if item["job_id"] == created["job_id"]
        )
        assert before["devices"]["D1"]["device_fence"] is not None

        cleared = await manager.clear_fence_on_process_replacement(
            "D1", "process-b", True
        )
        current = next(
            item for item in cleared if item["job_id"] == created["job_id"]
        )
        assert current["revision"] == before["revision"] + 1
        assert current["devices"]["D1"]["device_fence"] is None
    finally:
        store.close()


@pytest.mark.asyncio
async def test_malformed_foreign_success_does_not_clear_opaque_fence(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    manager = PushJobManager(store)
    try:
        await manager.create_job(request(), protocols(), 60_000)
        foreign_job = str(uuid.uuid4())
        await manager.add_opaque_fence(
            "D1",
            manager.opaque_identity_for_active({"job_id": foreign_job, "attempt": 1}),
            ProtocolMode.JOB_V1,
            "process-a",
            "unknown active job",
        )
        runtime = object.__new__(PushRuntime)
        runtime.manager = manager
        runtime.transfers = TransferRegistry()
        runtime.scheduler = _Scheduler()
        runtime.send_timeout = 1
        runtime.sessions = {}

        async def publish(_snapshot):
            raise AssertionError("malformed evidence must not mutate canonical state")

        runtime.publish = publish
        ws = _Ws()
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
        await runtime._handle_result(
            "D1",
            {
                "job_id": foreign_job,
                "attempt": 1,
                "status": "success",
                "added": "0",
                "updated": 0,
                "deleted": 0,
            },
            owned_session=session,
        )
        assert ws.messages[-1]["accepted"] is False
        assert ws.messages[-1]["reason"] == "malformed_terminal_result"
        assert await manager.opaque_reconcile_target("D1") is not None
    finally:
        store.close()
