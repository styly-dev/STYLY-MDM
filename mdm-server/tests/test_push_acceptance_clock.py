"""Recovered acceptance starts the original HTTP owner's bounded preparation wait."""

import asyncio
from types import SimpleNamespace

import pytest

from push_test_helpers import claimed_job_context, dispatch_scheduler, session_runtime_for
from styly_mdm import push_transfer_leases
from styly_mdm.push_jobs import DeviceState
from styly_mdm.transfer_registry import TransferKey


@pytest.mark.asyncio
@pytest.mark.parametrize("route", ["late_ack", "register", "reconcile", "busy", "normal_busy"])
@pytest.mark.parametrize("race", ["none", "get_first", "replace", "cancel", "orphan", "future_replace", "revoked"])
async def test_recovered_acceptance_only_starts_original_live_owner(
    tmp_path, monkeypatch, route, race,
):
    clock = SimpleNamespace(now=0.0)
    monkeypatch.setattr(push_transfer_leases, "time", SimpleNamespace(monotonic=lambda: clock.now))
    async with claimed_job_context(tmp_path) as (manager, _caps, snapshot, _claimed):
        job_id = snapshot["job_id"]
        await manager.prepare_dispatch(job_id, "D1", accept_deadline=100)
        await manager.mark_reconciling(
            job_id, "D1", expected={DeviceState.DISPATCHING},
            reason="command_accept_timeout", deadline=1000,
        )
        runtime, session = session_runtime_for(manager)
        scheduler = dispatch_scheduler(
            manager, runtime.transfers, runtime.leases, runtime.sessions, asyncio.Semaphore(1),
        )
        scheduler.wake = lambda: None
        runtime.scheduler = scheduler
        key = TransferKey("push", "D1", job_id, 1)
        future = asyncio.get_running_loop().create_future()
        if race != "orphan":
            runtime.transfers.register(key, future)
        lease = runtime.leases.issue(key, snapshot["artifact"]["artifact_id"])
        task = asyncio.current_task()
        if race == "get_first":
            clock.now = 10
            assert await runtime.leases.claim(
                lease.artifact_id, lease.token, task, object(),
            ) == "claimed"
            runtime.leases.unclaim(lease.token, task)
        method = "record_progress" if route in {"busy", "normal_busy"} else "reconcile_report"
        original = getattr(manager, method)
        replacement = None

        async def validate_with_race(*args, **kwargs):
            nonlocal replacement
            result = await original(*args, **kwargs)
            if race == "replace":
                clock.now = 30
                replacement = runtime.leases.issue(key, lease.artifact_id)
            elif race == "future_replace":
                future.set_result("replaced")
                runtime.transfers.register(key, asyncio.get_running_loop().create_future())
            elif race == "revoked":
                runtime.leases.revoke_now(key)
            elif race == "cancel":
                await manager.store._call(lambda conn: conn.execute(
                    "UPDATE push_job_devices SET cancel_requested_at=1 WHERE job_id=? AND device_id='D1'",
                    (job_id,),
                ))
            clock.now = 50
            return result

        monkeypatch.setattr(manager, method, validate_with_race)
        report = {
            "job_id": job_id, "attempt": 1, "status": "active", "phase": "downloading",
            "artifact_id": lease.artifact_id,
        }
        clock.now = 40
        if route == "normal_busy":
            acceptance = asyncio.get_running_loop().create_future()
            acceptance.set_result(("busy", {
                "active_job": {"job_id": job_id, "attempt": 1},
            }))
            assert await scheduler._await_acceptance(
                session, await manager.get_snapshot(job_id), "D1", acceptance, key, 100,
            )
        elif route == "register":
            await runtime._registration_active_snapshots("D1", session, report)
        else:
            if route == "late_ack":
                report.update(type="PUSH_JOB_ACCEPTED", phase=None)
            elif route == "busy":
                report.update(type="PUSH_JOB_REJECTED", reason="device_busy",
                              active_job={"job_id": job_id, "attempt": 1})
            else:
                report["type"] = "PUSH_RECONCILE_REPORT"
            await runtime._handle_owned_job_v1_message(session, "D1", report)
        if race == "replace":
            assert replacement is not None
            assert runtime.leases.is_current(replacement)
            assert not replacement.accepted
            assert replacement.last_progress == 30
        elif race == "revoked":
            assert runtime.leases.token(key) is None
            assert not lease.accepted
        elif race == "future_replace":
            assert not lease.accepted
            assert lease.last_progress == 0
            assert runtime.transfers.get(key) is not future
        elif race == "orphan":
            assert runtime.leases.token(key) is None
        elif race == "cancel":
            assert not lease.accepted
            assert lease.last_progress == 0
        else:
            assert lease.accepted
            assert lease.last_progress == (10 if race == "get_first" else 50)
            clock.now = 55
            await scheduler.reconcile_active_transfer(job_id, "D1", 1)
            assert lease.last_progress == (10 if race == "get_first" else 50)
