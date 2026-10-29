import asyncio
import json
from dataclasses import replace
from unittest.mock import AsyncMock

import pytest

from styly_mdm.push_job_manager import PushJobManager
from styly_mdm.push_job_store import PushJobStore
from styly_mdm.push_jobs import ProtocolMode
from styly_mdm.push_scheduler import LiveSession, PushScheduler
from styly_mdm.push_transfer_leases import PushTransferLeases
from styly_mdm.transfer_registry import TransferKey, TransferRegistry
from test_push_review_connection import downloading_job


def test_job_v1_command_uses_absolute_artifact_url():
    snapshot = {
        'job_id': 'job',
        'revision': 8,
        'mode': 'sync',
        'dest_path': '/sdcard/STYLY/content',
        'artifact': {
            'artifact_id': 'artifact',
            'url': '/artifacts/artifact',
            'display_filename': 'content.zip',
            'byte_size': 123,
            'sha256': 'a' * 64,
            'etag': '"' + 'a' * 64 + '"',
        },
        'devices': {'D1': {'attempt': 1, 'dispatch_revision': 7}},
    }
    command = PushScheduler._command(
        snapshot, 'D1', 'http://10.0.0.2:7070', lease_token='test-lease',
    )
    assert command['artifact_url'] == 'http://10.0.0.2:7070/artifacts/artifact?lease=test-lease'
    assert command['revision'] == 7
    assert 'bundle_url' not in command
    assert command['delete_extras'] is True


class _Ws:
    def __init__(self):
        self.messages = []

    async def send_str(self, value):
        self.messages.append(json.loads(value))


@pytest.mark.parametrize("capabilities", [
    {"push_job_id_v1"},
    {"push_resume_v1"},
    set(),
])
def test_dispatch_requires_job_id_and_resume_capabilities(capabilities):
    async def scenario():
        session = LiveSession(
            device_id="D1", session_id="session", ws=_Ws(),
            capabilities=frozenset(capabilities), process_instance_id="process",
            owner_lock=asyncio.Lock(), http_base="http://server",
        )
        scheduler = object.__new__(PushScheduler)
        scheduler.sessions = lambda: {"D1": session}
        scheduler.transfer_slots = lambda: asyncio.Semaphore(1)
        scheduler._pause_unavailable_dispatch = AsyncMock()
        await scheduler._dispatch_assignment_inner({
            "job": {
                "job_id": "job", "declared_total_bytes": 10,
                "artifact": {"byte_size": 10},
            },
            "device_id": "D1", "attempt": 1,
        })
        scheduler._pause_unavailable_dispatch.assert_awaited_once_with("job", "D1")
        assert session.ws.messages == []

    asyncio.run(scenario())


def test_active_reconnect_keeps_the_existing_transfer_slot():
    async def scenario():
        scheduler = object.__new__(PushScheduler)
        scheduler.transfer_registry = TransferRegistry()
        scheduler.transfer_slots = lambda: asyncio.Semaphore(0)
        accept = asyncio.get_running_loop().create_future()
        scheduler._accept_waiters = {("job", "D1", 1): accept}
        key = TransferKey("push", "D1", "job", 1)
        future = asyncio.get_running_loop().create_future()
        scheduler.transfer_registry.register(key, future)

        await asyncio.wait_for(
            scheduler.reconcile_active_transfer("job", "D1", 1), timeout=0.5
        )

        assert scheduler.transfer_registry.get(key) is future
        assert not future.done()
        assert accept.result()[0] == "accepted"

    asyncio.run(scenario())


def test_exact_reconcile_can_reuse_held_owner_lock():
    async def scenario():
        lock = asyncio.Lock()
        ws = _Ws()
        session = __import__(
            'styly_mdm.push_scheduler', fromlist=['LiveSession']
        ).LiveSession(
            device_id='D1',
            session_id='session',
            ws=ws,
            capabilities=frozenset({'push_job_id_v1'}),
            process_instance_id='process',
            owner_lock=lock,
            http_base='http://server',
        )
        scheduler = object.__new__(PushScheduler)
        scheduler.send_timeout = 0.5
        scheduler.sessions = lambda: {'D1': session}
        async with lock:
            await asyncio.wait_for(
                scheduler.send_exact_reconcile(
                    session, 'job', 1, 'artifact', owner_lock_held=True
                ),
                timeout=0.5,
            )
        assert ws.messages[0]['type'] == 'PUSH_RECONCILE_REQUEST'

    asyncio.run(scenario())


@pytest.mark.asyncio
async def test_resume_after_disconnect_does_not_duplicate_a_slot_waiting_task(
    tmp_path, monkeypatch, caplog
):
    store = PushJobStore(tmp_path / "jobs.sqlite3")
    manager = PushJobManager(store)
    slots = asyncio.Semaphore(0)
    leases = PushTransferLeases()
    registry = TransferRegistry(leases.revoke_now)
    claim_checked = asyncio.Event()
    downloading = asyncio.Event()
    published_states = []
    original_claim = manager.claim_next

    async def observe_claim(online_devices):
        assignment = await original_claim(online_devices)
        if assignment is None:
            claim_checked.set()
        return assignment

    monkeypatch.setattr(manager, "claim_next", observe_claim)

    class AcceptingWs(_Ws):
        async def send_str(self, value):
            await super().send_str(value)
            if self.messages[-1]["type"] == "EXECUTE_PUSH_FILES":
                scheduler._accept_waiters[(job_id, "D1", 1)].set_result(("accepted", {}))

    ws = AcceptingWs()
    session = LiveSession(
        device_id="D1", session_id="session", ws=ws,
        capabilities=frozenset({"push_job_id_v1", "push_resume_v1"}),
        process_instance_id="process", owner_lock=asyncio.Lock(), http_base="http://server",
    )
    sessions = {"D1": session}

    async def publish(snapshot):
        state = snapshot["devices"]["D1"]["state"]
        published_states.append(state)
        if state == "downloading":
            downloading.set()

    scheduler = PushScheduler(
        manager=manager, transfer_registry=registry, leases=leases,
        transfer_slots=lambda: slots, sessions=lambda: sessions, publish=publish,
        send_timeout=1, accept_timeout=30,
        accept_reconciliation_timeout=30, reconciliation_timeout=60,
    )
    try:
        previous = await downloading_job(store, manager)
        job_id = previous["job_id"]
        outcome, _ = await manager.resume_interrupted(
            job_id, "D1", attempt=1, artifact_id=previous["artifact"]["artifact_id"],
            dispatch_revision=previous["devices"]["D1"]["dispatch_revision"],
            validated_offset=0, reason="download_interrupted",
        )
        assert outcome == "requeued"
        await store.enable_dispatch(job_id, ["D1"])
        scheduler.start()
        await asyncio.wait_for(claim_checked.wait(), 1)
        assert (await manager.assignment(job_id, "D1"))["state"] == "waiting_transfer"
        assert len(scheduler._dispatch_tasks) == 1

        sessions.clear()
        await manager.settle_disconnect(
            "D1", accept_timeout_ms=30_000, reconciliation_timeout_ms=60_000,
        )
        sessions["D1"] = replace(session, session_id="replacement")
        await store.enable_dispatch(job_id, ["D1"])
        claim_checked.clear()
        scheduler.wake()
        await asyncio.wait_for(claim_checked.wait(), 1)
        assert len(scheduler._dispatch_tasks) == 1
        assert (await manager.assignment(job_id, "D1"))["state"] == "queued"

        # Two free slots used to let both tasks enter, corrupting the healthy
        # task's state through the duplicate waiter's exception recovery.
        slots.release()
        slots.release()
        await asyncio.wait_for(downloading.wait(), 1)
        claim_checked.clear()
        scheduler.wake()
        await asyncio.wait_for(claim_checked.wait(), 1)
        assert (await manager.assignment(job_id, "D1"))["state"] == "downloading"
        assert len(scheduler._dispatch_tasks) == 1
        assert [message["type"] for message in ws.messages] == ["EXECUTE_PUSH_FILES"]
        assert "reconciling" not in published_states
        assert not [record for record in caplog.records if record.levelno >= 40]
        key = TransferKey("push", "D1", job_id, 1)
        assert registry.get(key) is not None and not registry.get(key).done()
        assert leases.token(key) is not None
    finally:
        await scheduler.stop()
        store.close()
