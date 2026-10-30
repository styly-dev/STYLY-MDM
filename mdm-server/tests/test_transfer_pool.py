"""Tests that install and Push job-v1 share one server-wide transfer budget."""

from __future__ import annotations

import asyncio
import json

import pytest

from styly_mdm import server
from styly_mdm.transfer_registry import TransferKey, TransferRegistry


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
def reset_state(monkeypatch):
    monkeypatch.setattr(server, "transfer_registry", TransferRegistry())
    saved_limit = server.MAX_CONCURRENT_TRANSFERS
    saved_timeout = server.TRANSFER_TIMEOUT
    for collection in (
        server.devices,
        server.admin_connections,
        server._transfer_tasks,
    ):
        collection.clear()
    server.reset_transfer_slots()
    yield
    for collection in (
        server.devices,
        server.admin_connections,
        server._transfer_tasks,
    ):
        collection.clear()
    server.reset_transfer_slots()
    server.MAX_CONCURRENT_TRANSFERS = saved_limit
    server.TRANSFER_TIMEOUT = saved_timeout


def prepare_job_v1_dispatch(manager, capabilities, snapshot, assignment, slots):
    from test_push_acceptance_transport_failure import _scheduler
    from styly_mdm.push_scheduler import LiveSession
    from styly_mdm.push_transfer_leases import PushTransferLeases

    leases = PushTransferLeases()
    registry = TransferRegistry(leases.revoke_now)
    server.transfer_registry = registry
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
        assert registry.get(TransferKey("install", "installer")) is not None

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


@pytest.mark.parametrize("reason, task", [("install_result", "install"), ("disconnect", None)])
@pytest.mark.asyncio
async def test_install_release_preserves_job_v1_push_transfer_and_http_lease(tmp_path, reason, task):
    from test_push_acceptance_transport_failure import _ready

    async with _ready(tmp_path) as (manager, capabilities, snapshot, assignment):
        server.MAX_CONCURRENT_TRANSFERS = 2
        slots = server.transfer_slots()
        start_dispatch, push_sent, waiting, registry, leases, key = (
            prepare_job_v1_dispatch(manager, capabilities, snapshot, assignment, slots)
        )
        install_ws = add_install_device("D1")
        install_task = run_install(["D1"])
        push_task = start_dispatch()
        stream = None
        try:
            await asyncio.wait_for(waiting.wait(), 1)
            assert push_sent.is_set()
            assert count_of(install_ws, "EXECUTE_INSTALL") == 1
            push_transfer = registry.get(key)
            assert push_transfer is not None and not push_transfer.done()
            assert registry.get(TransferKey("install", "D1")) is not None

            token = leases.token(key)
            assert token is not None

            class Transport:
                aborted = False

                def abort(self):
                    self.aborted = True

            transport = Transport()
            stream = asyncio.create_task(asyncio.Event().wait())
            assert await leases.claim(snapshot["artifact"]["artifact_id"], token, stream, transport) == "claimed"
            other_install = asyncio.get_running_loop().create_future()
            other_key = TransferKey("install", "other-device")
            registry.register(other_key, other_install)

            assert server.release_transfer_slot("D1", reason, task=task)
            await asyncio.wait_for(install_task, 1)
            assert not push_transfer.done()
            assert not push_task.done()
            assert not other_install.done()
            assert leases.valid(snapshot["artifact"]["artifact_id"], token)
            assert not stream.done()
            assert not transport.aborted
            registry.remove_if_same(other_key, other_install)

            registry.release_exact(key, "download_complete")
            await asyncio.gather(stream, return_exceptions=True)
            await asyncio.wait_for(push_task, 1)
            assert len(registry) == 0
        finally:
            if stream is not None:
                stream.cancel()
                await asyncio.gather(stream, return_exceptions=True)
            for task in (install_task, push_task):
                if not task.done():
                    task.cancel()
            await asyncio.gather(install_task, push_task, return_exceptions=True)


@pytest.mark.asyncio
async def test_runtime_shutdown_releases_shared_install_waiter(tmp_path, monkeypatch):
    monkeypatch.setattr(server, "DATA_DIR", tmp_path)
    app = server.create_app()
    runtime = app["push_runtime"]
    add_install_device("D1")
    runtime.sessions["D1"] = object()
    install_task = run_install(["D1"])
    try:
        await settle()
        waiter = runtime.transfers.get(TransferKey("install", "D1"))
        assert waiter is not None and not waiter.done()
        await runtime.on_cleanup(app)
        assert waiter.result() == "shutdown"
        await asyncio.wait_for(install_task, 1)
        assert len(runtime.transfers) == 0
    finally:
        if not install_task.done():
            install_task.cancel()
        await asyncio.gather(install_task, return_exceptions=True)
        runtime.store.close()
