import asyncio
from types import SimpleNamespace

import pytest

from styly_mdm import push_scheduler
from styly_mdm.push_scheduler import PushScheduler
from styly_mdm.push_transfer_leases import PushTransferLeases
from styly_mdm.transfer_registry import TransferKey, TransferRegistry


def test_transfer_with_progress_is_not_capped_at_ten_minutes(monkeypatch):
    async def scenario():
        monkeypatch.setattr(push_scheduler, "TRANSFER_IDLE_SECONDS", 0.05)
        monkeypatch.setattr(push_scheduler, "_TRANSFER_IDLE_POLL_INTERVAL", 0.01)
        leases = PushTransferLeases()
        key = TransferKey("push", "D1", "job", 1)
        token = leases.issue(key, "artifact")
        future = asyncio.get_running_loop().create_future()
        scheduler = object.__new__(PushScheduler)
        scheduler.leases = leases
        scheduler.transfer_timeout = 0.02

        waiting = asyncio.create_task(
            scheduler._wait_for_transfer_or_stall(key, future)
        )
        await asyncio.sleep(0)

        # Scale the idle window down for the test. Repeated progress keeps a
        # transfer alive well beyond transfer_timeout, which used to be a hard cap.
        for _ in range(8):
            await asyncio.sleep(0.015)
            leases.progress(token, 1024)
        assert not waiting.done()

        future.set_result("download_complete")
        assert await asyncio.wait_for(waiting, timeout=0.5) is False

    asyncio.run(scenario())


def test_stalled_transfer_stops_http_stream_before_releasing_its_slot(monkeypatch):
    async def scenario():
        monkeypatch.setattr(push_scheduler, "TRANSFER_IDLE_SECONDS", 0.01)
        key = TransferKey("push", "D1", "job", 1)
        leases = PushTransferLeases()
        token = leases.issue(key, "artifact")
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
        assert await leases.claim("artifact", token, stream, Transport()) == "claimed"
        await asyncio.sleep(0)

        await asyncio.sleep(0.02)
        expired = await scheduler._expire_stalled_transfer(key, future, token)

        assert expired is True
        assert order == [
            "transport_aborted",
            "http_stream_stopped",
            ("slot_released", True),
        ]
        assert future.result() == "transfer_stalled"

    asyncio.run(scenario())
