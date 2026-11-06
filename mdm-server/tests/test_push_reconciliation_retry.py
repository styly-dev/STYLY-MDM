"""Bounded, connection-owned probes do not change durable dispatch authority."""

import asyncio
import json
from types import SimpleNamespace

import pytest
import pytest_asyncio

from styly_mdm import push_scheduler
from styly_mdm.push_job_manager import PushJobManager
from styly_mdm.push_scheduler import LiveSession, PushScheduler
from styly_mdm.push_transfer_leases import PushTransferLeases
from styly_mdm.transfer_registry import TransferKey, TransferRegistry
from push_test_helpers import downloading_job, manager, operator_runtime, timed_out_job


class Manager:
    reconciliation_target_for_device = PushJobManager.reconciliation_target_for_device

    def __init__(self):
        self.active = {"job_id": "job", "attempt": 1, "artifact_id": "artifact", "state": "reconciling"}
        self.fence = None
        self.opaque = None

    async def active_assignment_for_device(self, _device_id):
        return self.active

    async def assignment(self, job_id, _device_id):
        return self.active if self.active and self.active["job_id"] == job_id else None

    async def pending_cancellations_for_device(self, _device_id):
        return []

    async def fenced_assignment_for_device(self, _device_id):
        return self.fence

    async def opaque_reconcile_target(self, _device_id):
        return self.opaque


class Ws:
    def __init__(self):
        self.messages = []
        self.fail = False

    async def send_str(self, message):
        self.messages.append(json.loads(message))
        if self.fail:
            raise ConnectionError("temporary send failure")


@pytest_asyncio.fixture
async def probes(monkeypatch):
    waits = asyncio.Queue()
    real_sleep = asyncio.sleep

    async def controlled_sleep(delay):
        if asyncio.current_task().get_name().startswith("push-reconcile-"):
            gate = asyncio.Event()
            waits.put_nowait((delay, gate))
            await gate.wait()
        else:
            await real_sleep(delay)

    monkeypatch.setattr(push_scheduler.asyncio, "sleep", controlled_sleep)
    manager = Manager()
    ws = Ws()
    session = LiveSession("D1", "session-a", ws, frozenset(), "process-a", asyncio.Lock(), "http://server")
    sessions = {"D1": session}
    events = []
    leases = PushTransferLeases()
    scheduler = PushScheduler(
        manager=manager, transfer_registry=TransferRegistry(), leases=leases,
        transfer_slots=lambda: asyncio.Semaphore(1), sessions=lambda: sessions,
        publish=lambda _snapshot: real_sleep(0), send_timeout=0.1,
        accept_timeout=1, accept_reconciliation_timeout=60, reconciliation_timeout=1800,
        reconciliation_changed=lambda: events.append(scheduler.reconciliation_snapshot()),
    )
    async def next_wait(expected_delay):
        delay, gate = await asyncio.wait_for(waits.get(), 1)
        assert delay == expected_delay
        return gate

    h = SimpleNamespace(scheduler=scheduler, manager=manager, session=session,
                        sessions=sessions, ws=ws, events=events, next_wait=next_wait)
    yield h
    await scheduler.stop()


@pytest.mark.asyncio
async def test_probes_back_off_and_stop_without_resetting_on_duplicate_triggers(probes):
    h = probes
    await h.scheduler.send_exact_reconcile(h.session, "job", 1, "artifact")
    assert len(h.ws.messages) == 1
    for delay in (1, 2, 4, 8):
        gate = await h.next_wait(delay)
        await h.scheduler.send_exact_reconcile(h.session, "job", 1, "artifact")
        gate.set()
    gate = await h.next_wait(8)  # Allow the final reply to arrive before declaring exhaustion.
    assert len(h.ws.messages) == 5
    task = h.scheduler.reconciliation_probes["D1"].task
    gate.set()
    await task
    assert h.events[-1]["devices"][0]["status"] == "unavailable"
    await h.scheduler.send_exact_reconcile(h.session, "job", 1, "artifact")
    assert len(h.ws.messages) == 5
    assert h.manager.active["state"] == "reconciling"
    assert [event["revision"] for event in h.events] == [1, 2]


@pytest.mark.asyncio
async def test_canonical_resolution_cancels_checks_and_accepts_late_resolution(probes):
    h = probes
    await h.scheduler.send_exact_reconcile(h.session, "job", 1, "artifact")
    gate = await h.next_wait(1)
    task = h.scheduler.reconciliation_probes["D1"].task
    h.manager.active["state"] = "queued"  # Existing evidence handler retained a manual Resume gate.
    async with h.session.owner_lock:
        await h.scheduler.refresh_reconciliation(h.session)
    assert h.scheduler.reconciliation_snapshot()["devices"] == []
    gate.set()
    await asyncio.gather(task, return_exceptions=True)
    assert len(h.ws.messages) == 1
    assert h.manager.active["state"] == "queued"


@pytest.mark.asyncio
@pytest.mark.parametrize("opaque", [False, True])
async def test_interrupted_cleanup_keeps_checking_until_exact_absence(probes, opaque):
    h = probes
    h.manager.active = None
    if opaque:
        h.manager.opaque = {"job_id": "job", "attempt": 1, "artifact_id": "artifact"}
    else:
        h.manager.fence = {"blocking_job_id": "job", "blocking_attempt": 1, "artifact_id": "artifact"}
    await h.scheduler.send_exact_reconcile(h.session, "job", 1, "artifact")
    gate = await h.next_wait(1)
    await h.scheduler.refresh_reconciliation(h.session)
    assert h.scheduler.reconciliation_snapshot()["devices"]
    gate.set()
    await h.next_wait(2)
    assert len(h.ws.messages) == 2
    h.manager.opaque = h.manager.fence = None
    await h.scheduler.refresh_reconciliation(h.session)
    assert h.scheduler.reconciliation_snapshot()["devices"] == []


@pytest.mark.asyncio
async def test_replacement_connection_gets_fresh_cycle_without_old_probe_sending(probes):
    h = probes
    await h.scheduler.send_exact_reconcile(h.session, "job", 1, "artifact")
    old_gate = await h.next_wait(1)
    old_task = h.scheduler.reconciliation_probes["D1"].task
    ws = Ws()
    new_session = LiveSession("D1", "session-b", ws, frozenset(), "process-a", h.session.owner_lock, "http://server")
    h.sessions["D1"] = new_session
    await h.scheduler.send_exact_reconcile(new_session, "job", 1, "artifact")
    old_gate.set()
    await asyncio.gather(old_task, return_exceptions=True)
    await h.next_wait(1)
    assert len(h.ws.messages) == len(ws.messages) == 1
    assert h.scheduler.reconciliation_probes["D1"].session is new_session


@pytest.mark.asyncio
async def test_offline_or_unavailable_state_stops_probes_and_recovery_rearms(probes):
    h = probes
    h.session.push_state_available = False
    await h.scheduler.send_exact_reconcile(h.session, "job", 1, "artifact")
    assert h.ws.messages == []
    h.session.push_state_available = True
    await h.scheduler.send_exact_reconcile(h.session, "job", 1, "artifact")
    gate = await h.next_wait(1)
    h.sessions.clear()
    task = h.scheduler.reconciliation_probes["D1"].task
    gate.set()
    await task
    assert len(h.ws.messages) == 1
    assert h.scheduler.reconciliation_snapshot()["devices"] == []


@pytest.mark.asyncio
async def test_initial_send_failure_still_has_bounded_automatic_retries(probes):
    h = probes
    h.ws.fail = True
    with pytest.raises(ConnectionError):
        await h.scheduler.send_exact_reconcile(h.session, "job", 1, "artifact")
    gate = await h.next_wait(1)
    h.ws.fail = False
    gate.set()
    await h.next_wait(2)
    assert len(h.ws.messages) == 2


@pytest.mark.asyncio
async def test_stale_connection_cannot_replace_new_probe_and_shutdown_cancels_wait(probes):
    h = probes
    await h.scheduler.send_exact_reconcile(h.session, "job", 1, "artifact")
    await h.next_wait(1)
    newer = LiveSession("D1", "new", Ws(), frozenset(), "process-b", h.session.owner_lock, "http://server")
    h.sessions["D1"] = newer
    await h.scheduler.send_exact_reconcile(newer, "job", 1, "artifact")
    await h.next_wait(1)
    probe = h.scheduler.reconciliation_probes["D1"]
    await h.scheduler.send_exact_reconcile(h.session, "job", 1, "artifact")
    assert h.scheduler.reconciliation_probes["D1"] is probe
    await h.scheduler.stop()
    assert probe.task.done()
    assert h.scheduler.reconciliation_snapshot()["devices"] == []
    assert len(newer.ws.messages) == 1


@pytest.mark.asyncio
async def test_exhausted_cycle_accepts_late_canonical_resolution(probes):
    h = probes
    await h.scheduler.send_exact_reconcile(h.session, "job", 1, "artifact")
    task = h.scheduler.reconciliation_probes["D1"].task
    for delay in (1, 2, 4, 8, 8):
        (await h.next_wait(delay)).set()
    await task
    assert h.scheduler.reconciliation_snapshot()["devices"][0]["status"] == "unavailable"
    h.manager.active = None
    await h.scheduler.refresh_reconciliation(h.session)
    assert h.scheduler.reconciliation_snapshot()["devices"] == []


@pytest.mark.asyncio
async def test_state_unavailability_cancels_cycle_then_recovery_gets_new_budget(probes):
    h = probes
    await h.scheduler.send_exact_reconcile(h.session, "job", 1, "artifact")
    await h.next_wait(1)
    h.session.push_state_available = False
    await h.scheduler.refresh_reconciliation(h.session)
    assert h.scheduler.reconciliation_snapshot()["devices"] == []
    h.session.push_state_available = True
    await h.scheduler.send_exact_reconcile(h.session, "job", 1, "artifact")
    await h.next_wait(1)
    assert len(h.ws.messages) == 2


@pytest.mark.asyncio
@pytest.mark.parametrize("origin", ["operator", "registration"])
@pytest.mark.parametrize("interrupted_reply", [False, True])
async def test_queued_cancellation_retries_until_exact_absence(
    operator_runtime, probes, manager, tmp_path, origin, interrupted_reply,
):
    h = probes
    original = await timed_out_job(manager)
    job_id = original["job_id"]
    artifact_id = original["artifact"]["artifact_id"]
    runtime, _session, admin = operator_runtime(manager, tmp_path)
    runtime.scheduler = h.scheduler
    h.scheduler.manager = manager
    runtime.sessions = h.sessions
    runtime.device_locks = {"D1": h.session.owner_lock}
    runtime.registration_candidates = {}
    runtime.legacy.devices["D1"] = {
        "ws": h.ws, "identity_kind": "canonical", "registration_ready": True,
    }
    if origin == "operator":
        await runtime.handle_admin_message(admin, {"type": "CANCEL_PUSH_JOB", "job_id": job_id})
    else:
        await manager.cancel_interrupted(job_id, "D1")
        await runtime._after_registration(h.session, {})
    gate = await h.next_wait(1)
    if interrupted_reply:
        await runtime.handle_device_message(h.ws, "D1", {
            "type": "PUSH_RECONCILE_REPORT", "job_id": job_id, "attempt": 1,
            "artifact_id": artifact_id, "revision": original["devices"]["D1"]["dispatch_revision"],
            "status": "interrupted", "phase": "downloading",
        })
        assert h.ws.messages[-1]["type"] == "PUSH_RESUME_REJECTED"
    gate.set()
    await h.next_wait(2)
    probes_sent = [message for message in h.ws.messages if message["type"] == "PUSH_RECONCILE_REQUEST"]
    assert len(probes_sent) == 2
    assert await manager.pending_cancellations_for_device("D1")
    await runtime.handle_device_message(h.ws, "D1", {
        "type": "PUSH_RECONCILE_REPORT", "job_id": job_id, "attempt": 1,
        "artifact_id": "different-artifact", "status": "absent",
    })
    assert await manager.pending_cancellations_for_device("D1")
    assert h.scheduler.reconciliation_snapshot()["devices"]
    await runtime.handle_device_message(h.ws, "D1", {
        "type": "PUSH_RECONCILE_REPORT", "job_id": job_id, "attempt": 1,
        "artifact_id": artifact_id, "status": "absent",
    })
    assert not await manager.pending_cancellations_for_device("D1")
    assert (await manager.assignment(job_id, "D1"))["failure_code"] == "cancelled"
    assert h.scheduler.reconciliation_snapshot()["devices"] == []


@pytest.mark.asyncio
@pytest.mark.parametrize("initial_send_fails", [False, True])
async def test_transfer_stall_starts_bounded_checks_after_releasing_http(probes, manager, initial_send_fails):
    h = probes
    active = await downloading_job(manager.store, manager)
    h.scheduler.manager = manager
    key = TransferKey("push", "D1", active["job_id"], 1)
    future = asyncio.get_running_loop().create_future()
    h.scheduler.transfer_registry.register(key, future)
    lease = h.scheduler.leases.issue(key, active["artifact"]["artifact_id"])
    lease.last_progress -= push_scheduler.TRANSFER_IDLE_SECONDS + 1
    send = h.ws.send_str

    async def send_after_release(message):
        assert h.scheduler.leases.token(key) is None
        assert future.done()
        await send(message)

    h.ws.send_str = send_after_release
    h.ws.fail = initial_send_fails
    assert await h.scheduler._expire_stalled_transfer(lease, future)
    h.ws.fail = False
    gate = await h.next_wait(1)
    assert len(h.ws.messages) == 1
    assert (await manager.assignment(active["job_id"], "D1"))["state"] == "reconciling"
    gate.set()
    await h.next_wait(2)
    assert len(h.ws.messages) == 2
