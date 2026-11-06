import asyncio
import json
import time
from types import SimpleNamespace

import pytest

from styly_mdm.push_job_store import now_ms
from styly_mdm.push_jobs import DeviceState, ProgressEvent
from styly_mdm.push_runtime import PushRuntime
from styly_mdm.push_scheduler import LiveSession
from styly_mdm.push_transfer_leases import PushTransferLeases, TRANSFER_IDLE_SECONDS
from styly_mdm.transfer_registry import TransferKey, TransferRegistry
from push_test_helpers import (
    claimed_job_context, dispatch_scheduler, ignore_publication, ready_job, session_runtime_for,
)


@pytest.mark.asyncio
@pytest.mark.parametrize("late", [False, True])
@pytest.mark.parametrize("resumed", [False, True])
@pytest.mark.parametrize("finish", ["resume", "cancel"])
async def test_persistence_rejection_preserves_manual_wait_through_recovery(
    tmp_path, late, resumed, finish,
):
    async with claimed_job_context(tmp_path) as (manager, _caps, snapshot, _claimed):
        job_id = snapshot["job_id"]
        dispatched = await manager.prepare_dispatch(job_id, "D1", accept_deadline=100)
        revision = dispatched["devices"]["D1"]["dispatch_revision"]
        report = {
            "job_id": job_id, "attempt": 1, "status": "interrupted",
            "phase": "downloading", "reason": "download_interrupted",
            "artifact_id": snapshot["artifact"]["artifact_id"],
            "revision": revision, "validated_offset": 1,
        }
        if resumed:
            await manager.record_progress(job_id, "D1", ProgressEvent.ACCEPTED)
            await manager.resume_interrupted(
                job_id, "D1", attempt=1, artifact_id=report["artifact_id"],
                dispatch_revision=revision, validated_offset=1,
                reason="download_interrupted",
            )
            await manager.store.enable_dispatch(job_id)
            assert await manager.claim_next(["D1"]) is not None
            dispatched = await manager.prepare_dispatch(job_id, "D1", accept_deadline=100)
        before = await manager.assignment(job_id, "D1")
        runtime, session = session_runtime_for(manager)
        scheduler = dispatch_scheduler(manager, runtime.transfers, runtime.leases,
                               runtime.sessions, asyncio.Semaphore(1))
        scheduler.wake = lambda: None
        key = TransferKey("push", "D1", job_id, 1)
        runtime.transfers.register(key, asyncio.get_running_loop().create_future())
        runtime.leases.issue(key, report["artifact_id"])
        rejection = {"reason": "client_persistence_unavailable"}
        if late:
            await manager.mark_reconciling(
                job_id, "D1", expected={DeviceState.DISPATCHING},
                reason="command_accept_timeout", deadline=1,
            )
            await scheduler.handle_late_command_response(
                job_id=job_id, device_id="D1", outcome="rejected", payload=rejection,
            )
        else:
            future = asyncio.get_running_loop().create_future()
            future.set_result(("rejected", rejection))
            assert not await scheduler._await_acceptance(
                session, dispatched, "D1", future, key, 100,
            )
        paused = await manager.assignment(job_id, "D1")
        assert paused["state"] == "queued"
        assert paused["queue_reason"] == "dispatch_paused"
        for field in ("dispatch_revision", "accepted_at", "validated_offset"):
            assert paused[field] == before[field]
        assert runtime.transfers.get(key).done()
        assert runtime.leases.token(key) is None
        assert await manager.expired_reconciliations(now_ms() + 86_400_000) == []
        await runtime._resume_interrupted_report(session, "D1", report, paused)
        assert session.ws.messages == [], "valid retained work must not be rejected"
        later = await ready_job(manager.store, manager)
        await manager.store.enable_dispatch(later["job_id"])
        assert await manager.claim_next(["D1"]) is None
        manager.store.recover_startup_sync(
            accept_reconciliation_timeout_ms=1000, reconciliation_timeout_ms=1000,
        )
        assert (await manager.assignment(job_id, "D1"))["queue_reason"] == "dispatch_paused"
        assert await manager.expired_reconciliations(now_ms() + 86_400_000) == []
        if finish == "cancel":
            await manager.cancel_interrupted(job_id, "D1")
            await runtime._handle_reconcile_report(session, "D1", report)
            assert session.ws.messages[-1]["type"] == "PUSH_RESUME_REJECTED"
            await runtime._handle_reconcile_report(session, "D1", {
                "job_id": job_id, "attempt": 1, "artifact_id": report["artifact_id"],
                "status": "absent",
            })
            assert (await manager.assignment(job_id, "D1"))["failure_code"] == "cancelled"
            await manager.store.enable_dispatch(later["job_id"])
            assert (await manager.claim_next(["D1"]))["job"]["job_id"] == later["job_id"]
        else:
            await manager.store.enable_dispatch(job_id)
            assert (await manager.claim_next(["D1"]))["job"]["job_id"] == job_id
            resumed_snapshot = await manager.prepare_dispatch(job_id, "D1", accept_deadline=200)
            assert resumed_snapshot["devices"]["D1"]["dispatch_revision"] == revision


@pytest.mark.asyncio
@pytest.mark.parametrize("stage", ["before_send", "normal", "late"])
async def test_rejection_commit_failure_retries_the_same_decision(tmp_path, monkeypatch, stage):
    async with claimed_job_context(tmp_path) as (manager, _caps, snapshot, claimed):
        job_id = snapshot["job_id"]
        runtime, session = session_runtime_for(manager)
        scheduler = dispatch_scheduler(manager, runtime.transfers, runtime.leases,
                               runtime.sessions, asyncio.Semaphore(1))
        scheduler.wake = lambda: None
        runtime.scheduler = scheduler
        key = TransferKey("push", "D1", job_id, 1)
        attempts = []
        original_commit = manager.store._commit
        def fail_first_pause(conn):
            row = conn.execute("SELECT state FROM push_job_devices WHERE job_id=?", (job_id,)).fetchone()
            if row["state"] == "queued":
                # No lease/slot may be released before either commit attempt.
                assert not runtime.transfers.get(key).done()
                assert runtime.leases.token(key) is not None or stage == "before_send"
                attempts.append(row["state"])
                if len(attempts) == 1:
                    raise OSError("injected commit failure")
            original_commit(conn)
        async def publish(_snapshot):
            if stage == "before_send":
                session.push_state_available = False
        async def send_command(text):
            session.ws.messages.append(json.loads(text))
            session.push_state_available = False
            scheduler.command_response(
                job_id=job_id, device_id="D1", attempt=1, outcome="rejected",
                payload={"reason": "client_persistence_unavailable"},
            )
        scheduler.publish = publish
        session.ws.send_str = send_command
        monkeypatch.setattr("styly_mdm.push_scheduler._RUN_RETRY_DELAY", 0)
        monkeypatch.setattr(manager.store, "_commit", fail_first_pause)
        if stage == "late":
            await manager.prepare_dispatch(job_id, "D1", accept_deadline=100)
            await manager.mark_reconciling(job_id, "D1", expected={DeviceState.DISPATCHING},
                                          reason="command_accept_timeout", deadline=1)
            runtime.transfers.register(key, asyncio.get_running_loop().create_future())
            runtime.leases.issue(key, snapshot["artifact"]["artifact_id"])
            async with session.owner_lock:
                await runtime._handle_owned_job_v1_message(session, "D1", {
                    "type": "PUSH_JOB_REJECTED", "job_id": job_id, "attempt": 1,
                    "reason": "client_persistence_unavailable",
                })
            assert runtime.transfers.get(key).done()
            runtime.transfers.remove_if_same(key, runtime.transfers.get(key))
        else:
            await scheduler._dispatch_assignment(claimed)
        paused = (await manager.get_snapshot(job_id))["devices"]["D1"]
        assert attempts == ["queued", "queued"]
        assert paused["state"] == "queued" and paused["manual_wait"]
        assert paused["failure"] is None
        assert runtime.transfers.get(key) is None
        assert runtime.leases.token(key) is None
        assert len(session.ws.messages) == (1 if stage == "normal" else 0)


@pytest.mark.asyncio
async def test_rejection_storage_retry_can_be_cancelled(tmp_path, monkeypatch):
    async with claimed_job_context(tmp_path) as (manager, _caps, snapshot, claimed):
        runtime, session = session_runtime_for(manager)
        scheduler = dispatch_scheduler(manager, runtime.transfers, runtime.leases,
                               runtime.sessions, asyncio.Semaphore(1))
        scheduler.wake = lambda: None
        original_commit = manager.store._commit
        failed = asyncio.Event()
        loop = asyncio.get_running_loop()
        def fail_pause(conn):
            row = conn.execute("SELECT state FROM push_job_devices WHERE job_id=?",
                               (snapshot["job_id"],)).fetchone()
            if row["state"] == "queued":
                loop.call_soon_threadsafe(failed.set)
                raise OSError("storage remains unavailable")
            original_commit(conn)
        async def publish(_snapshot):
            session.push_state_available = False
        scheduler.publish = publish
        monkeypatch.setattr(manager.store, "_commit", fail_pause)
        task = asyncio.create_task(scheduler._dispatch_assignment_inner(claimed))
        try:
            await asyncio.wait_for(failed.wait(), 1)
        finally:
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
        assert not session.owner_lock.locked()
        assert len(runtime.transfers) == 0
        assert runtime.leases.token(TransferKey("push", "D1", snapshot["job_id"], 1)) is None
        assert (await manager.assignment(snapshot["job_id"], "D1"))["state"] == "dispatching"


@pytest.mark.asyncio
@pytest.mark.parametrize("stage", ["waiting_slot", "before_send"])
async def test_dispatch_becoming_unavailable_returns_to_manual_wait(tmp_path, stage):
    async with claimed_job_context(tmp_path) as (manager, _caps, snapshot, claimed):
        runtime, session = session_runtime_for(manager)
        scheduler = dispatch_scheduler(manager, runtime.transfers, runtime.leases,
                               runtime.sessions, asyncio.Semaphore(1))
        scheduler.wake = lambda: None
        async def publish(_snapshot):
            session.push_state_available = False
            session.capabilities = frozenset()
        if stage == "waiting_slot":
            session.push_state_available = False
            session.capabilities = frozenset()
        else:
            scheduler.publish = publish
        await scheduler._dispatch_assignment_inner(claimed)
        paused = (await manager.get_snapshot(snapshot["job_id"]))["devices"]["D1"]
        assert paused["state"] == "queued" and paused["manual_wait"]
        assert session.ws.messages == []
        assert await manager.claim_next(["D1"]) is None


@pytest.mark.asyncio
@pytest.mark.parametrize("late", [False, True])
async def test_permanent_rejection_remains_terminal(tmp_path, late):
    async with claimed_job_context(tmp_path) as (manager, _caps, snapshot, _claimed):
        job_id = snapshot["job_id"]
        dispatched = await manager.prepare_dispatch(job_id, "D1", accept_deadline=100)
        runtime, session = session_runtime_for(manager)
        scheduler = dispatch_scheduler(manager, runtime.transfers, runtime.leases,
                               runtime.sessions, asyncio.Semaphore(1))
        scheduler.wake = lambda: None
        payload = {"reason": "artifact_identity_mismatch", "detail": "Conflicting artifact"}
        if late:
            await manager.mark_reconciling(job_id, "D1", expected={DeviceState.DISPATCHING},
                                          reason="command_accept_timeout", deadline=1)
            await scheduler.handle_late_command_response(
                job_id=job_id, device_id="D1", outcome="rejected", payload=payload,
            )
        else:
            response = asyncio.get_running_loop().create_future()
            response.set_result(("rejected", payload))
            await scheduler._await_acceptance(
                session, dispatched, "D1", response, TransferKey("push", "D1", job_id, 1), 100,
            )
        failed = await manager.assignment(job_id, "D1")
        assert failed["state"] == "failed"
        assert failed["failure_code"] == "artifact_identity_mismatch"
        await scheduler.handle_late_command_response(
            job_id=job_id, device_id="D1", outcome="rejected",
            payload={"reason": "client_persistence_unavailable"},
        )
        assert await manager.assignment(job_id, "D1") == failed


@pytest.mark.asyncio
@pytest.mark.parametrize("finish", ["stalled", "phase", "transfer_complete"])
@pytest.mark.parametrize("resumed", [False, True])
@pytest.mark.parametrize("disconnect_first", [False, True, "late_active_report"])
async def test_lost_acceptance_preserves_http_until_progress_stalls(
    tmp_path, monkeypatch, disconnect_first, resumed, finish
):
    async with claimed_job_context(tmp_path) as (manager, capabilities, snapshot, claimed):
        if resumed:
            prior = await manager.prepare_dispatch(
                snapshot["job_id"], "D1",
                accept_deadline=now_ms() + 1_000,
            )
            await manager.transition_device(
                snapshot["job_id"], "D1", expected={DeviceState.DISPATCHING},
                target=DeviceState.DOWNLOADING,
                fields={"accepted_at": now_ms(), "accept_deadline": None},
            )
            outcome, _ = await manager.resume_interrupted(
                snapshot["job_id"], "D1", attempt=1,
                artifact_id=snapshot["artifact"]["artifact_id"],
                dispatch_revision=prior["devices"]["D1"]["dispatch_revision"],
                validated_offset=0, reason="download_interrupted",
            )
            assert outcome == "requeued"
            await manager.store.enable_dispatch(snapshot["job_id"])
            claimed = await manager.claim_next(["D1"])
            assert claimed is not None
            assert claimed["accepted_at"] is not None
        command_sent = asyncio.Event()
        watchdog_started = asyncio.Event()
        probe_failed = asyncio.Event()

        class Ws:
            async def send_str(self, text):
                if json.loads(text)["type"] == "EXECUTE_PUSH_FILES":
                    command_sent.set()
                else:
                    probe_failed.set()
                    raise ConnectionError("management socket closed")

        ws = Ws()
        sessions = {"D1": LiveSession(
            "D1", "session-1", ws, capabilities, "process-1", asyncio.Lock(),
            "http://localhost",
        )}
        leases = PushTransferLeases()
        registry = TransferRegistry(leases.revoke_now)
        slots = asyncio.Semaphore(1)
        scheduler = dispatch_scheduler(manager, registry, leases, sessions, slots)
        wait_for_transfer = scheduler._wait_for_transfer_or_stall

        async def watched_wait(lease, future):
            watchdog_started.set()
            return await wait_for_transfer(lease, future)

        monkeypatch.setattr(scheduler, "_wait_for_transfer_or_stall", watched_wait)
        monkeypatch.setattr("styly_mdm.push_scheduler._TRANSFER_IDLE_POLL_INTERVAL", 0.01)
        dispatch = asyncio.create_task(scheduler._dispatch_assignment_inner(claimed))
        stream = None
        try:
            await asyncio.wait_for(command_sent.wait(), 1)
            if resumed:
                # Housekeeping must also see an unacknowledged Resume if its
                # local acceptance task disappears; accepted_at is older history.
                expired = await manager.expired_acceptances(now_ms() + 1_000)
                assert [row["job_id"] for row in expired] == [snapshot["job_id"]]
            key = TransferKey("push", "D1", snapshot["job_id"], 1)
            token = leases.token(key)
            artifact_id = snapshot["artifact"]["artifact_id"]
            transport = SimpleNamespace(abort_count=0)

            def abort():
                transport.abort_count += 1

            transport.abort = abort
            stream = asyncio.create_task(asyncio.Event().wait())
            assert await leases.claim(artifact_id, token, stream, transport) == "claimed"
            if disconnect_first:
                runtime = object.__new__(PushRuntime)
                runtime.pending_disconnects = set()
                runtime.disconnect_recovery_tasks = {}
                runtime.artifact_gc_task = None
                runtime.manager = manager
                runtime.scheduler = scheduler
                runtime.sessions = sessions
                runtime.device_locks = {"D1": sessions["D1"].owner_lock}
                runtime.registration_candidates = {}
                runtime.legacy = SimpleNamespace(devices={"D1": {"ws": ws}})
                runtime.accept_reconciliation_timeout = 60
                runtime.reconciliation_timeout = 60
                runtime.publish = ignore_publication
                await runtime.disconnect_device("D1", ws)
                if disconnect_first == "late_active_report":
                    original_mark = manager.mark_acceptance_reconciling

                    async def active_report_then_mark(*args, **kwargs):
                        # An exact report wins after wait_for cancels its ACK
                        # waiter, but before the timeout's DB operation executes.
                        outcome, _ = await manager.reconcile_report(
                            snapshot["job_id"], "D1", 1, "active", "downloading", None,
                        )
                        assert outcome == "active"
                        return await original_mark(*args, **kwargs)

                    monkeypatch.setattr(manager, "mark_acceptance_reconciling", active_report_then_mark)

            # Simulate response writes independently of the management socket.
            progress_deadline = time.monotonic() + 1
            while (
                not watchdog_started.is_set()
                and not dispatch.done()
                and time.monotonic() < progress_deadline
            ):
                leases.progress(token, 1024)
                await asyncio.sleep(0.01)
            await asyncio.wait_for(watchdog_started.wait(), 0.5)
            assert not dispatch.done()
            assert slots.locked()
            assert leases.valid(artifact_id, token)
            assert not stream.done()
            assert transport.abort_count == 0
            row = await manager.assignment(snapshot["job_id"], "D1")
            if disconnect_first == "late_active_report":
                assert row["state"] == "downloading"
                assert row["accepted_at"] is not None
            else:
                assert row["reconciliation_reason"] == (
                    ("device_disconnect" if resumed else "disconnect_before_accept")
                    if disconnect_first else "command_accept_timeout"
                )
            if not disconnect_first:
                assert probe_failed.is_set()
                if resumed:
                    assert row["accepted_at"] is not None
                    changed, duplicate = await manager.mark_acceptance_reconciling(
                        snapshot["job_id"], "D1",
                        expected_accept_deadline=row["accept_deadline"],
                        reconciliation_deadline=now_ms() + 1_000,
                    )
                    assert not changed
                    assert duplicate["devices"]["D1"]["state"] == "reconciling"

            if finish != "stalled":
                runtime = object.__new__(PushRuntime)
                runtime.pending_disconnects = set()
                runtime.disconnect_recovery_tasks = {}
                runtime.artifact_gc_task = None
                runtime.manager = manager
                runtime.store = manager.store
                runtime.transfers = registry
                runtime.publish = ignore_publication
                payload = {
                    "job_id": snapshot["job_id"], "attempt": 1,
                    "artifact_id": artifact_id, "phase": "validating",
                    "received_size": snapshot["artifact"]["byte_size"],
                }
                if finish == "phase":
                    await runtime._handle_phase("D1", payload)
                else:
                    await runtime._handle_transfer_complete("D1", payload)
                await asyncio.wait_for(dispatch, 1)
                assert not leases.valid(artifact_id, token)
                assert not slots.locked()
                row = await manager.assignment(snapshot["job_id"], "D1")
                assert row["state"] == "validating"
                assert row["reconciliation_deadline"] is None
                return

            # Loss of progress must still revoke the URL and release the slot.
            leases._by_key[key].last_progress = time.monotonic() - TRANSFER_IDLE_SECONDS - 1
            await asyncio.wait_for(dispatch, 1)
            assert not leases.valid(artifact_id, token)
            assert stream.cancelled()
            assert transport.abort_count == 1
            assert not slots.locked()
            assert registry.get(key) is None
            row = await manager.assignment(snapshot["job_id"], "D1")
            assert row["reconciliation_reason"] == "transfer_stalled"
        finally:
            dispatch.cancel()
            if stream is not None:
                stream.cancel()
            await asyncio.gather(dispatch, *([stream] if stream else []), return_exceptions=True)


@pytest.mark.asyncio
@pytest.mark.parametrize("changed", [
    "deadline", "terminal", "manual_wait", "cancelled", "waiter", "lease",
    "validating", "applying",
])
async def test_disconnect_timeout_cannot_keep_obsolete_transfer(tmp_path, changed):
    async with claimed_job_context(tmp_path) as (manager, capabilities, snapshot, _claimed):
        job_id = snapshot["job_id"]
        deadline = 1234
        snapshot = await manager.prepare_dispatch(
            job_id, "D1",
            accept_deadline=deadline,
        )
        await manager.mark_reconciling(
            job_id, "D1", expected={DeviceState.DISPATCHING},
            reason="disconnect_before_accept", deadline=5678,
        )
        leases = PushTransferLeases()
        registry = TransferRegistry(leases.revoke_now)
        key = TransferKey("push", "D1", job_id, 1)
        transfer = asyncio.get_running_loop().create_future()
        registry.register(key, transfer)
        lease = leases.issue(key, snapshot["artifact"]["artifact_id"])
        scheduler = dispatch_scheduler(manager, registry, leases, {}, asyncio.Semaphore(1))
        original_mark = manager.mark_acceptance_reconciling

        async def change_then_mark(*args, **kwargs):
            if changed == "waiter":
                transfer.set_result("replaced")
                registry.register(key, asyncio.get_running_loop().create_future())
            elif changed == "lease":
                leases.issue(key, lease.artifact_id)
            elif changed in {"validating", "applying"}:
                await manager.reconcile_report(job_id, "D1", 1, "active", changed, None)
            else:
                fields = {}
                target = DeviceState.RECONCILING
                if changed == "deadline":
                    fields["accept_deadline"] = deadline + 1
                elif changed == "terminal":
                    target = DeviceState.FAILED
                elif changed == "manual_wait":
                    target = DeviceState.QUEUED
                    fields["queue_reason"] = "dispatch_paused"
                elif changed == "cancelled":
                    await manager.store._call(lambda conn: conn.execute(
                        "UPDATE push_job_devices SET cancel_requested_at=1 "
                        "WHERE job_id=? AND device_id='D1'", (job_id,),
                    ))
                await manager.transition_device(
                    job_id, "D1", expected={DeviceState.RECONCILING},
                    target=target, fields=fields,
                )
            return await original_mark(*args, **kwargs)

        manager.mark_acceptance_reconciling = change_then_mark
        scheduler.accept_timeout = 0
        assert await scheduler._await_acceptance(
            None, snapshot, "D1", asyncio.get_running_loop().create_future(), key, deadline,
        ) is False
