"""Tests that install and Push job-v1 share one server-wide transfer budget."""

from __future__ import annotations

import asyncio
import json

import pytest

from styly_mdm import server


class FakeWS:
    def __init__(self):
        self.sent: list[str] = []

    async def send_str(self, message: str) -> None:
        self.sent.append(message)


def add_install_device(device_id: str) -> FakeWS:
    ws = FakeWS()
    server.devices[device_id] = {
        "registration_ready": True,
        "identity_kind": "canonical",
        "ws": ws,
        "device_id": device_id,
        "model": "M",
        "ip": "1.1.1.1",
        "status": "online",
        "startup_app": None,
        "battery": None,
    }
    return ws


def count_of(ws: FakeWS, message_type: str) -> int:
    return sum(json.loads(message).get("type") == message_type for message in ws.sent)


def run_install(targets: list[str]) -> asyncio.Task:
    return asyncio.create_task(server._run_install_job("http://x/a.apk", "a.apk", targets))


async def settle(turns: int = 40) -> None:
    for _ in range(turns):
        await asyncio.sleep(0)


@pytest.fixture(autouse=True)
def reset_state():
    saved_limit = server.MAX_CONCURRENT_TRANSFERS
    saved_timeout = server.TRANSFER_TIMEOUT
    saved_pending_transfers = server.pending_transfers
    for collection in (
        server.devices,
        server.admin_connections,
        server._transfer_tasks,
    ):
        collection.clear()
    saved_pending_transfers.clear()
    server.pending_transfers = {}
    server.reset_transfer_slots()
    yield
    for collection in (
        server.devices,
        server.admin_connections,
        server._transfer_tasks,
    ):
        collection.clear()
    server.pending_transfers.clear()
    saved_pending_transfers.clear()
    server.pending_transfers = saved_pending_transfers
    server.reset_transfer_slots()
    server.MAX_CONCURRENT_TRANSFERS = saved_limit
    server.TRANSFER_TIMEOUT = saved_timeout


def prepare_job_v1_dispatch(manager, capabilities, snapshot, assignment, slots):
    from test_push_acceptance_transport_failure import _scheduler
    from styly_mdm.push_runtime import _LegacyTransferAdapter
    from styly_mdm.push_scheduler import LiveSession
    from styly_mdm.push_transfer_leases import PushTransferLeases
    from styly_mdm.transfer_registry import TransferKey, TransferRegistry

    leases = PushTransferLeases()
    registry = TransferRegistry(leases.revoke_now)
    # The install path still uses the tuple-key facade. Back it with the same typed
    # registry as Push-v1 so the tests exercise the production shared-release boundary.
    server.pending_transfers = _LegacyTransferAdapter(registry)
    sessions = {}
    scheduler = _scheduler(manager, registry, leases, sessions, slots)
    sent = asyncio.Event()
    waiting_for_transfer = asyncio.Event()

    class PushWS:
        async def send_str(self, text: str) -> None:
            message = json.loads(text)
            if message["type"] == "EXECUTE_PUSH_FILES":
                sent.set()
                scheduler.command_response(
                    job_id=message["job_id"],
                    device_id="D1",
                    attempt=message["attempt"],
                    outcome="accepted",
                    payload={},
                )

    ws = PushWS()
    sessions["D1"] = LiveSession(
        device_id="D1",
        session_id="push-session",
        ws=ws,
        capabilities=capabilities,
        process_instance_id="process",
        owner_lock=asyncio.Lock(),
        http_base="http://server",
    )
    original_wait = scheduler._wait_for_transfer_or_stall

    async def observe_transfer_wait(lease, future):
        waiting_for_transfer.set()
        return await original_wait(lease, future)

    scheduler._wait_for_transfer_or_stall = observe_transfer_wait
    key = TransferKey("push", "D1", snapshot["job_id"], 1)

    def start_dispatch():
        return asyncio.create_task(scheduler._dispatch_assignment_inner(assignment))

    return start_dispatch, sent, waiting_for_transfer, registry, leases, key


@pytest.mark.asyncio
async def test_job_v1_push_waits_for_install_to_release_shared_budget(tmp_path):
    from test_push_acceptance_transport_failure import _ready

    async with _ready(tmp_path) as (manager, capabilities, snapshot, assignment):
        server.MAX_CONCURRENT_TRANSFERS = 1
        slots = server.transfer_slots()
        start_dispatch, push_sent, waiting, registry, _leases, key = (
            prepare_job_v1_dispatch(manager, capabilities, snapshot, assignment, slots)
        )
        install_ws = add_install_device("installer")
        install_task = run_install(["installer"])
        await settle()
        assert count_of(install_ws, "EXECUTE_INSTALL") == 1
        assert ("installer", server.TASK_INSTALL) in server.pending_transfers

        push_task = start_dispatch()
        try:
            await settle()
            assert not push_sent.is_set()
            assert not push_task.done()

            assert server.release_transfer_slot(
                "installer", "download_complete", task=server.TASK_INSTALL
            )
            await install_task
            await asyncio.wait_for(waiting.wait(), 1)
            assert push_sent.is_set()
            assert slots.locked()
            assert registry.get(key) is not None

            registry.release_exact(key, "download_complete")
            await asyncio.wait_for(push_task, 1)
            assert not slots.locked()
        finally:
            for task in (install_task, push_task):
                if not task.done():
                    task.cancel()
            await asyncio.gather(install_task, push_task, return_exceptions=True)


@pytest.mark.asyncio
async def test_install_completion_does_not_release_job_v1_push_transfer(tmp_path):
    from test_push_acceptance_transport_failure import _ready

    async with _ready(tmp_path) as (manager, capabilities, snapshot, assignment):
        server.MAX_CONCURRENT_TRANSFERS = 2
        slots = server.transfer_slots()
        start_dispatch, push_sent, waiting, registry, _leases, key = (
            prepare_job_v1_dispatch(manager, capabilities, snapshot, assignment, slots)
        )
        install_ws = add_install_device("D1")
        install_task = run_install(["D1"])
        push_task = start_dispatch()
        try:
            await asyncio.wait_for(waiting.wait(), 1)
            assert push_sent.is_set()
            assert count_of(install_ws, "EXECUTE_INSTALL") == 1
            push_transfer = registry.get(key)
            assert push_transfer is not None and not push_transfer.done()
            assert ("D1", server.TASK_INSTALL) in server.pending_transfers

            assert server.release_transfer_slot("D1", "install_result", task=server.TASK_INSTALL)
            await asyncio.wait_for(install_task, 1)
            assert not push_transfer.done()
            assert not push_task.done()

            registry.release_exact(key, "download_complete")
            await asyncio.wait_for(push_task, 1)
            assert server.pending_transfers == {}
        finally:
            for task in (install_task, push_task):
                if not task.done():
                    task.cancel()
            await asyncio.gather(install_task, push_task, return_exceptions=True)
