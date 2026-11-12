import asyncio
from types import SimpleNamespace

import pytest

from styly_mdm import push_scheduler
from styly_mdm.push_scheduler import PushScheduler
from styly_mdm.push_transfer_leases import PushTransferLeases
from styly_mdm.transfer_registry import TransferKey, TransferRegistry


@pytest.mark.asyncio
async def test_transfer_with_progress_is_not_capped_at_ten_minutes(tmp_path, monkeypatch):
    from push_test_helpers import claimed_job_context, dispatch_scheduler
    from styly_mdm import push_transfer_leases
    from styly_mdm.push_scheduler import LiveSession
    import json

    async with claimed_job_context(tmp_path) as (manager, capabilities, snapshot, claimed):
        loop = asyncio.get_running_loop()
        started_at = loop.time()
        clock = SimpleNamespace(now=started_at)
        real_loop_time = loop.time
        monkeypatch.setattr(loop, "time", lambda: clock.now)
        clock_source = SimpleNamespace(monotonic=lambda: clock.now)
        monkeypatch.setattr(push_scheduler, "time", clock_source)
        monkeypatch.setattr(push_transfer_leases, "time", clock_source)
        leases = PushTransferLeases()
        registry = TransferRegistry(leases.revoke_now)
        sessions = {}
        scheduler = dispatch_scheduler(manager, registry, leases, sessions, asyncio.Semaphore(1))
        waiting = asyncio.Event()
        original_wait = scheduler._wait_for_transfer_or_stall

        async def observe_wait(lease, future):
            waiting.set()
            return await original_wait(lease, future)
        scheduler._wait_for_transfer_or_stall = observe_wait

        class Ws:
            async def send_str(self, text):
                message = json.loads(text)
                if message["type"] == "EXECUTE_PUSH_FILES":
                    scheduler.command_response(
                        job_id=message["job_id"], device_id="D1", attempt=1,
                        outcome="accepted", payload={},
                    )

        sessions["D1"] = LiveSession(
            "D1", "session", Ws(), capabilities, "process", asyncio.Lock(), "http://localhost",
        )
        dispatch = asyncio.create_task(scheduler._dispatch_assignment_inner(claimed))
        try:
            await waiting.wait()
            key = TransferKey("push", "D1", snapshot["job_id"], 1)
            token = leases.token(key)
            future = registry.get(key)
            for _ in range(12):
                clock.now += 55
                leases.progress(token, 1024)
                # Drive real event-loop timers, including any accidental outer
                # 600-second cap, without waiting ten minutes in wall-clock time.
                for _ in range(12):
                    await asyncio.sleep(0)
                assert not dispatch.done()
                assert not future.done()
                assert leases.token(key) == token
            assert clock.now - started_at > 600
            registry.release_exact(key, "download_complete")
            await dispatch
        finally:
            dispatch.cancel()
            await asyncio.gather(dispatch, return_exceptions=True)
            monkeypatch.setattr(loop, "time", real_loop_time)


def test_stalled_transfer_stops_http_stream_before_releasing_its_slot(monkeypatch):
    async def scenario():
        monkeypatch.setattr(push_scheduler, "TRANSFER_IDLE_SECONDS", 0.01)
        key = TransferKey("push", "D1", "job", 1)
        leases = PushTransferLeases()
        lease = leases.issue(key, "artifact")
        order = []

        class Transport:
            def abort(self):
                order.append("transport_aborted")

        async def active_assignment_for_device(_device_id):
            return {
                "job_id": "job",
                "attempt": 1,
                "state": "downloading",
            }

        async def mark_reconciling(*_args, **_kwargs):
            return {"state": "reconciling"}

        async def publish(_snapshot):
            return None

        scheduler = object.__new__(PushScheduler)
        scheduler.leases = leases
        scheduler.manager = SimpleNamespace(
            active_assignment_for_device=active_assignment_for_device,
            mark_reconciling=mark_reconciling,
        )
        scheduler.publish = publish
        scheduler.reconciliation_timeout = 60
        scheduler.sessions = lambda: {}
        registry = TransferRegistry(
            before_release=lambda _key: order.append(("slot_released", stream.done()))
        )
        scheduler.transfer_registry = registry
        future = asyncio.get_running_loop().create_future()
        registry.register(key, future)

        async def stream_body():
            try:
                await asyncio.Future()
            finally:
                order.append("http_stream_stopped")

        stream = asyncio.create_task(stream_body())
        assert await leases.claim("artifact", lease.token, stream, Transport()) == "claimed"
        await asyncio.sleep(0)

        await asyncio.sleep(0.02)
        expired = await scheduler._expire_stalled_transfer(lease, future)

        assert expired is True
        assert order == [
            "transport_aborted",
            "http_stream_stopped",
            ("slot_released", True),
        ]
        assert future.result() == "transfer_stalled"

    asyncio.run(scenario())


def test_revoked_lease_releases_only_its_own_waiter(monkeypatch):
    async def scenario():
        monkeypatch.setattr(push_scheduler, "_TRANSFER_IDLE_POLL_INTERVAL", 0.01)
        key = TransferKey("push", "D1", "job", 1)
        leases = PushTransferLeases()
        lease = leases.issue(key, "artifact")
        scheduler = object.__new__(PushScheduler)
        scheduler.leases = leases
        scheduler.transfer_registry = TransferRegistry(leases.revoke_now)
        future = asyncio.get_running_loop().create_future()
        scheduler.transfer_registry.register(key, future)

        waiting = asyncio.create_task(
            scheduler._wait_for_transfer_or_stall(lease, future)
        )
        await asyncio.sleep(0.02)
        assert not waiting.done()

        # Cancellation revokes the lease before it releases the transfer slot.
        leases.revoke_now(key)
        assert await asyncio.wait_for(waiting, timeout=0.5) is True
        assert not leases.is_current(lease)
        assert await scheduler._expire_stalled_transfer(lease, future) is True
        assert future.result() == "lease_missing"

    asyncio.run(scenario())


@pytest.mark.asyncio
async def test_acceptance_and_first_get_have_separate_bounded_windows(monkeypatch):
    from styly_mdm import push_transfer_leases

    clock = SimpleNamespace(now=0.0)
    monkeypatch.setattr(push_transfer_leases, "time", SimpleNamespace(monotonic=lambda: clock.now))
    monkeypatch.setattr(push_scheduler, "time", SimpleNamespace(monotonic=lambda: clock.now))
    leases = PushTransferLeases()
    lease = leases.issue(TransferKey("push", "D1", "job", 1), "artifact")
    scheduler = object.__new__(PushScheduler)
    scheduler.leases = leases
    clock.now = 50
    leases.accept(lease.key, lease.token)
    clock.now = 109
    leases.accept(lease.key, lease.token)  # Duplicate acceptance cannot renew preparation.
    assert lease.last_progress == 50
    assert not scheduler._transfer_is_stalled(lease)
    clock.now = 110
    assert scheduler._transfer_is_stalled(lease)
    clock.now = 109
    task = asyncio.current_task()
    assert await leases.claim("artifact", lease.token, task, object()) == "claimed"
    clock.now = 168
    assert not scheduler._transfer_is_stalled(lease)
    leases.unclaim(lease.token, task)
    assert await leases.claim("artifact", lease.token, task, object()) == "claimed"
    leases.accept(lease.key, lease.token)
    leases.progress(lease.token, 0)
    assert lease.last_progress == 109
    clock.now = 169
    assert scheduler._transfer_is_stalled(lease)
    leases.progress(lease.token, 1)
    assert not scheduler._transfer_is_stalled(lease)
    leases.unclaim(lease.token, task)


@pytest.mark.asyncio
async def test_get_before_acceptance_and_replaced_lease_cannot_reset_clock(monkeypatch):
    from styly_mdm import push_transfer_leases

    clock = SimpleNamespace(now=0.0)
    monkeypatch.setattr(push_transfer_leases, "time", SimpleNamespace(monotonic=lambda: clock.now))
    leases = PushTransferLeases()
    key = TransferKey("push", "D1", "job", 1)
    lease = leases.issue(key, "artifact")
    task = asyncio.current_task()
    clock.now = 10
    assert await leases.claim("artifact", lease.token, task, object()) == "claimed"
    clock.now = 20
    leases.accept(lease.key, lease.token)
    assert lease.last_progress == 10
    leases.unclaim(lease.token, task)
    replacement = leases.issue(key, "artifact")
    clock.now = 30
    leases.accept(lease.key, lease.token)
    leases.progress(lease.token, 10)
    assert await leases.claim("artifact", lease.token, task, object()) == "revoked"
    assert replacement.last_progress == 20
    leases.revoke_now(key)
    leases.accept(replacement.key, replacement.token)
    assert await leases.claim("artifact", replacement.token, task, object()) == "revoked"


@pytest.mark.asyncio
@pytest.mark.parametrize("get_before_ack", [False, True])
async def test_dispatch_starts_preparation_at_ack_without_resetting_an_early_get(
    tmp_path, monkeypatch, get_before_ack,
):
    from push_test_helpers import claimed_job_context, dispatch_scheduler
    from styly_mdm import push_transfer_leases
    from styly_mdm.push_scheduler import LiveSession

    clock = SimpleNamespace(now=0.0)
    clock_source = SimpleNamespace(monotonic=lambda: clock.now)
    monkeypatch.setattr(push_scheduler, "time", clock_source)
    monkeypatch.setattr(push_transfer_leases, "time", clock_source)
    async with claimed_job_context(tmp_path) as (manager, capabilities, snapshot, claimed):
        leases = PushTransferLeases()
        registry = TransferRegistry(leases.revoke_now)
        sessions = {}
        scheduler = dispatch_scheduler(manager, registry, leases, sessions, asyncio.Semaphore(1))
        key = TransferKey("push", "D1", snapshot["job_id"], 1)
        sent = asyncio.Event()
        waiting = asyncio.Event()
        release = asyncio.Event()

        class Ws:
            async def send_str(self, _text):
                sent.set()

        async def observe_wait(lease, future):
            waiting.set()
            await release.wait()
            return False

        original_record = manager.record_progress

        async def delayed_record(*args, **kwargs):
            # The ACK clock must start before persistence, not after it finishes.
            assert leases.last_progress(key) == (20 if get_before_ack else 50)
            clock.now = 55
            return await original_record(*args, **kwargs)

        monkeypatch.setattr(manager, "record_progress", delayed_record)
        monkeypatch.setattr(scheduler, "_wait_for_transfer_or_stall", observe_wait)
        sessions["D1"] = LiveSession(
            "D1", "session", Ws(), capabilities, "process", asyncio.Lock(), "http://localhost",
        )
        dispatch = asyncio.create_task(scheduler._dispatch_assignment_inner(claimed))
        task = asyncio.current_task()
        token = None
        try:
            await asyncio.wait_for(sent.wait(), 1)
            token = leases.token(key)
            assert leases.last_progress(key) == 0
            if get_before_ack:
                clock.now = 20
                assert await leases.claim(
                    snapshot["artifact"]["artifact_id"], token, task, object(),
                ) == "claimed"
            clock.now = 50
            assert scheduler.command_response(
                job_id=snapshot["job_id"], device_id="D1", attempt=1,
                outcome="accepted", payload={},
            )
            await asyncio.wait_for(waiting.wait(), 1)
            assert not scheduler.command_response(
                job_id=snapshot["job_id"], device_id="D1", attempt=1,
                outcome="accepted", payload={},
            )
            origin = 20 if get_before_ack else 50
            assert leases.last_progress(key) == origin
            clock.now = origin + 59
            assert not scheduler._transfer_is_stalled(leases._by_key[key])
            clock.now = origin + 60
            assert scheduler._transfer_is_stalled(leases._by_key[key])
        finally:
            if token is not None:
                leases.unclaim(token, task)
            release.set()
            dispatch.cancel()
            await asyncio.gather(dispatch, return_exceptions=True)


@pytest.mark.asyncio
@pytest.mark.parametrize("replace", [False, True])
async def test_claim_revocation_while_waiting_for_previous_http_exit(monkeypatch, replace):
    from styly_mdm import push_transfer_leases

    clock = SimpleNamespace(now=0.0)
    monkeypatch.setattr(push_transfer_leases, "time", SimpleNamespace(monotonic=lambda: clock.now))
    leases = PushTransferLeases()
    key = TransferKey("push", "D1", "job", 1)
    lease = leases.issue(key, "artifact")
    started = asyncio.Event()
    exiting = asyncio.Event()
    finish_exit = asyncio.Event()

    class Transport:
        def abort(self):
            pass

    async def previous_stream():
        started.set()
        try:
            await asyncio.Future()
        finally:
            exiting.set()
            # Revoke can cancel the old task again while it is cleaning up.
            while not finish_exit.is_set():
                try:
                    await finish_exit.wait()
                except asyncio.CancelledError:
                    continue

    stream = asyncio.create_task(previous_stream())
    await started.wait()
    assert await leases.claim("artifact", lease.token, stream, Transport()) == "claimed"
    reconnect = asyncio.create_task(leases.claim(
        "artifact", lease.token, asyncio.current_task(), Transport(),
    ))
    try:
        await asyncio.wait_for(exiting.wait(), 1)
        assert not reconnect.done()
        clock.now = 20
        replacement = leases.issue(key, "artifact") if replace else None
        if not replace:
            leases.revoke_now(key)
        clock.now = 30
        finish_exit.set()
        assert await asyncio.wait_for(reconnect, 1) == "revoked"
        if replacement is not None:
            assert leases.is_current(replacement)
            assert replacement.last_progress == 20
            assert not replacement.started
            assert replacement.task is None
        else:
            assert leases.token(key) is None
    finally:
        finish_exit.set()
        reconnect.cancel()
        stream.cancel()
        await asyncio.gather(reconnect, stream, return_exceptions=True)
