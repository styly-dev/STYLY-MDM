import asyncio
import json
import time
import uuid
from contextlib import asynccontextmanager
from types import SimpleNamespace

import pytest

from styly_mdm.push_job_manager import PushJobManager
from styly_mdm.push_job_store import PushJobStore, now_ms
from styly_mdm.push_jobs import DeviceState, ProtocolMode, canonicalize_create_request
from styly_mdm.push_runtime import PushRuntime
from styly_mdm.push_scheduler import LiveSession, PushScheduler
from styly_mdm.push_transfer_leases import PushTransferLeases, TRANSFER_IDLE_SECONDS
from styly_mdm.transfer_registry import TransferKey, TransferRegistry


async def _publish(_snapshot):
    pass


@asynccontextmanager
async def _ready(tmp_path):
    store = PushJobStore(tmp_path / "jobs.sqlite3")
    manager = PushJobManager(store)
    capabilities = frozenset({"push_job_id_v1", "push_resume_v1"})
    try:
        _, job = await manager.create_job(canonicalize_create_request({
            "client_request_id": str(uuid.uuid4()),
            "target_devices": ["D1"], "mode": "push",
            "dest_path": "/sdcard/STYLY/content",
            "source": {"display_name": "content", "declared_file_count": 1,
                       "declared_total_bytes": 1},
        }), {"D1": (ProtocolMode.JOB_V1, capabilities)}, 60_000)
        job_id = job["job_id"]
        await store.start_upload(job_id)
        await store.mark_packaging(job_id, 1, 1)
        snapshot = await store.publish_artifact(job_id, {
            "artifact_id": str(uuid.uuid4()), "storage_name": "content.zip",
            "display_filename": "content.zip", "byte_size": 1,
            "sha256": "a" * 64, "entry_count": 1,
        })
        await manager.enable_dispatch(job_id)
        claimed = await manager.claim_next(["D1"])
        assert claimed is not None
        yield manager, capabilities, snapshot, claimed
    finally:
        store.close()


def _scheduler(manager, registry, leases, sessions, slots):
    return PushScheduler(
        manager=manager, transfer_registry=registry, leases=leases,
        transfer_slots=lambda: slots, sessions=lambda: sessions, publish=_publish,
        send_timeout=1, accept_timeout=0.1, accept_reconciliation_timeout=60,
        reconciliation_timeout=60, transfer_timeout=600,
    )


@pytest.mark.asyncio
@pytest.mark.parametrize("finish", ["stalled", "phase", "transfer_complete"])
@pytest.mark.parametrize("resumed", [False, True])
@pytest.mark.parametrize("disconnect_first", [False, True, "late_active_report"])
async def test_lost_acceptance_preserves_http_until_progress_stalls(
    tmp_path, monkeypatch, disconnect_first, resumed, finish
):
    async with _ready(tmp_path) as (manager, capabilities, snapshot, claimed):
        if resumed:
            prior = await manager.prepare_dispatch(
                snapshot["job_id"], "D1", protocol_mode=ProtocolMode.JOB_V1,
                live_capabilities=capabilities, accept_deadline=now_ms() + 1_000,
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
                validated_offset=0, reason="download_retry_exhausted",
            )
            assert outcome == "requeued"
            await manager.enable_dispatch(snapshot["job_id"])
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
        scheduler = _scheduler(manager, registry, leases, sessions, slots)
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
                runtime.manager = manager
                runtime.sessions = sessions
                runtime.device_locks = {"D1": sessions["D1"].owner_lock}
                runtime.registration_candidates = {}
                runtime.legacy = SimpleNamespace(devices={"D1": {"ws": ws}})
                runtime.accept_reconciliation_timeout = 60
                runtime.reconciliation_timeout = 60
                runtime.publish = _publish
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
                runtime.manager = manager
                runtime.store = manager.store
                runtime.transfers = registry
                runtime.publish = _publish
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
    async with _ready(tmp_path) as (manager, capabilities, snapshot, _claimed):
        job_id = snapshot["job_id"]
        deadline = 1234
        snapshot = await manager.prepare_dispatch(
            job_id, "D1", protocol_mode=ProtocolMode.JOB_V1,
            live_capabilities=capabilities, accept_deadline=deadline,
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
        scheduler = _scheduler(manager, registry, leases, {}, asyncio.Semaphore(1))
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
