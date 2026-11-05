"""Shared Push job scenarios and deterministic runtime test doubles."""

import asyncio
import json
import uuid
from contextlib import asynccontextmanager
from types import SimpleNamespace

import pytest

from styly_mdm.push_artifacts import ArtifactStore
from styly_mdm.push_job_manager import PushJobManager
from styly_mdm.push_job_store import PushJobStore, now_ms
from styly_mdm.push_jobs import DeviceState, ProtocolMode, canonicalize_create_request
from styly_mdm.push_runtime import PushRuntime
from styly_mdm.push_scheduler import LiveSession, PushScheduler
from styly_mdm.push_transfer_leases import PushTransferLeases
from styly_mdm.transfer_registry import TransferRegistry


CAPS = frozenset({'push_job_id_v1', 'push_resume_v1'})


@pytest.fixture
def manager(tmp_path):
    store = PushJobStore(tmp_path / 'push_jobs.sqlite3')
    instance = PushJobManager(store)
    try:
        yield instance
    finally:
        instance.store.close()


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


def push_request(device_id: str) -> dict:
    return {
        "client_request_id": str(uuid.uuid4()),
        "target_devices": [device_id],
        "mode": "push",
        "dest_path": "/sdcard/STYLY/recovery",
        "source": {
            "display_name": "recovery-test",
            "declared_file_count": 1,
            "declared_total_bytes": 7,
        },
    }


async def ready_job(store, manager):
    _, created = await manager.store.create_job(
        request(), {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}, 60_000,
    )
    job_id = created["job_id"]
    await store.start_upload(job_id)
    await store.mark_packaging(job_id, 1, 1)
    return await store.publish_artifact(job_id, {
        "artifact_id": str(uuid.uuid4()),
        "storage_name": str(uuid.uuid4()) + ".zip",
        "display_filename": "content.zip",
        "byte_size": 1,
        "sha256": "a" * 64,
        "entry_count": 1,
    })


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


@asynccontextmanager
async def claimed_job_context(tmp_path):
    """Own a manager and an enabled resume-capable job claimed for dispatch."""
    store = PushJobStore(tmp_path / "jobs.sqlite3")
    manager = PushJobManager(store)
    capabilities = CAPS
    try:
        _, job = await manager.store.create_job(
            request(), {"D1": (ProtocolMode.JOB_V1, capabilities)}, 60_000,
        )
        job_id = job["job_id"]
        await store.start_upload(job_id)
        await store.mark_packaging(job_id, 1, 1)
        snapshot = await store.publish_artifact(job_id, {
            "artifact_id": str(uuid.uuid4()), "storage_name": "content.zip",
            "display_filename": "content.zip", "byte_size": 1,
            "sha256": "a" * 64, "entry_count": 1,
        })
        await manager.store.enable_dispatch(job_id)
        claimed = await manager.claim_next(["D1"])
        assert claimed is not None
        yield manager, capabilities, snapshot, claimed
    finally:
        store.close()


async def timed_out_job(manager):
    active = await downloading_job(manager.store, manager)
    await manager.resume_interrupted(
        active['job_id'], 'D1', attempt=1, reason='download_interrupted',
        artifact_id=active['artifact']['artifact_id'],
        dispatch_revision=active['devices']['D1']['dispatch_revision'], validated_offset=1)
    return await manager.get_snapshot(active['job_id'])


async def completed(manager, root):
    states = {'success': ('succeeded', None), 'failed': ('failed', 'download_failed'),
              'interrupted': ('interrupted', 'restart'), 'unknown': ('unconfirmed', 'timeout'),
              'cancelled': ('failed', 'cancelled'), 'active': ('downloading', None)}
    request = canonicalize_create_request({
        'client_request_id': str(uuid.uuid4()), 'target_devices': list(states),
        'mode': 'sync', 'dest_path': '/sdcard/STYLY/content',
        'source': {'display_name': 'content', 'declared_file_count': 1, 'declared_total_bytes': 4},
    })
    _, job = await manager.store.create_job(request, {
        device: (ProtocolMode.JOB_V1, ['push_job_id_v1']) for device in states}, 600_000)
    job_id = job['job_id']
    await manager.store.start_upload(job_id)
    await manager.store.mark_packaging(job_id, 1, 4)
    artifact_id = str(uuid.uuid4())
    (root / (artifact_id + '.zip')).write_bytes(b'data')
    await manager.store.publish_artifact(job_id, {
        'artifact_id': artifact_id, 'storage_name': artifact_id + '.zip',
        'display_filename': 'content.zip', 'byte_size': 4, 'sha256': 'a' * 64, 'entry_count': 1,
    })
    def settle(conn):
        conn.execute("UPDATE push_jobs SET state='completed_with_errors', terminal_at=1 WHERE job_id=?", (job_id,))
        for device, (state, code) in states.items():
            conn.execute('UPDATE push_job_devices SET state=?, failure_code=? WHERE job_id=? AND device_id=?', (state, code, job_id, device))
        conn.execute("INSERT INTO push_device_fences(device_id,blocking_job_id,blocking_attempt,protocol_mode,reason,created_at,updated_at) VALUES ('unknown',?,1,'job_v1','timeout',1,1)", (job_id,))
    await manager.store._call(settle)
    return await manager.get_snapshot(job_id)


class Ws:
    def __init__(self):
        self.messages = []

    async def send_str(self, value):
        self.messages.append(json.loads(value))


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


class LegacyEvents:
    def __init__(self):
        self.events = []
        self.devices = {}

    async def forward_to_admins(self, event):
        self.events.append(event)


async def ignore_publication(_snapshot):
    pass


def dispatch_scheduler(manager, registry, leases, sessions, slots):
    """Build a real scheduler with test-owned sessions and transfer slots."""
    return PushScheduler(
        manager=manager, transfer_registry=registry, leases=leases,
        transfer_slots=lambda: slots, sessions=lambda: sessions, publish=ignore_publication,
        send_timeout=1, accept_timeout=0.1, accept_reconciliation_timeout=60,
        reconciliation_timeout=60,
    )


def session_runtime_for(manager):
    """Build a runtime with a live D1 session and recorded scheduler calls."""
    runtime = object.__new__(PushRuntime)
    runtime.store = manager.store
    runtime.manager = manager
    runtime.artifacts = ArtifactStore(manager.store.path.parent)
    runtime.scheduler = Scheduler()
    runtime.leases = PushTransferLeases()
    runtime.transfers = TransferRegistry(runtime.leases.revoke_now)
    runtime.send_timeout = 1
    runtime.legacy = LegacyEvents()
    runtime.pending_publications = {}
    runtime.publication_revisions = {}
    runtime.publication_wake = asyncio.Event()
    runtime.publish = lambda snapshot: asyncio.sleep(0)
    session = LiveSession('D1', 'session', Ws(), CAPS, str(uuid.uuid4()), asyncio.Lock(), 'http://server')
    runtime.sessions = {'D1': session}
    return runtime, session


class OperatorScheduler:
    """Keep scheduling deterministic while exercising the real reconcile lock path."""

    send_exact_reconcile = PushScheduler.send_exact_reconcile

    def __init__(self, runtime):
        self.runtime = runtime
        self.send_timeout = 0.5
        self.wake_count = 0

    def sessions(self):
        return self.runtime.sessions

    def wake(self):
        self.wake_count += 1


def operator_runtime(manager, artifact_root):
    """Add admin command dependencies and an admin socket to the session runtime."""
    runtime, session = session_runtime_for(manager)
    runtime.device_locks = {"D1": session.owner_lock}
    runtime.admin_send_timeout = 0.5
    runtime.artifacts = SimpleNamespace(artifact_root=artifact_root)
    runtime.legacy.MAX_CONCURRENT_TRANSFERS = 5
    runtime.legacy.devices = {}
    runtime.scheduler = OperatorScheduler(runtime)
    return runtime, session, Ws()


async def admin_action(runtime, admin, message_type, job_id, **fields):
    assert await asyncio.wait_for(runtime.handle_admin_message(admin, {
        "type": message_type, "job_id": job_id, **fields,
    }), timeout=2)
    assert not [message for message in admin.messages if message["type"] == "ERROR"]
