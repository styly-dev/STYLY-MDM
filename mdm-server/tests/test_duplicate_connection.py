"""Tests for superseded device connections (issue #70).

A reboot does not always close the old socket cleanly, so the pre-reboot
WebSocket can still be open server-side when the rebooted client reconnects and
re-registers. Two connections then carry the same device_id, and the server must
treat the *newest* registration as the owner:

  * the stale socket's teardown must not delete the live registration (the
    original bug: the console showed a connected device as "offline"),
  * nor free the live connection's transfer slots or settle its pending state,
  * telemetry arriving late on the stale socket must be ignored rather than
    raise KeyError and take the surviving connection's handler down with it,
  * and a normal single-connection disconnect must still go offline as before.

Structured like test_retire.py: deterministic unit tests with FakeWS, plus
aiohttp integration tests over loopback that prove the real handler wiring.
"""

from __future__ import annotations

import asyncio
import json

import aiohttp
import pytest
from aiohttp.test_utils import TestServer

from styly_mdm import server
from ws_test_helpers import (
    device_row_matches,
    receive_device_list as _recv_device_list,
    receive_type as _recv_type,
)


@pytest.fixture(autouse=True)
def reset_state():
    collections = (
        server.devices, server.admin_connections, server.pending_transfers,
        server._transfer_tasks, server.pending_self_updates, server.pending_retires,
        server._apk_hash_cache, server.device_registry, server.device_groups,
    )
    for coll in collections:
        coll.clear()
    server.reset_transfer_slots()
    yield
    for coll in collections:
        coll.clear()
    server.reset_transfer_slots()


async def _register(session, base: str, device_id: str, version_code: int = 7):
    ws = await session.ws_connect(base + "/ws/device")
    await ws.send_json({
        "type": "REGISTER", "identity_scheme": server.IDENTITY_SCHEME, "device_id": device_id, "model": "M",
        "ip": "1.1.1.2", "version_code": version_code, "version_name": "t",
    })
    await _recv_type(ws, "REGISTERED")
    return ws


def _row(device_list: dict, device_id: str) -> dict:
    return next(d for d in device_list["devices"] if d["device_id"] == device_id)


def test_device_list_wait_skips_the_registering_snapshot(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        device_id = "64b19041-0b8c-4ef4-82fd-000000000000"
        cancellations_started = asyncio.Event()
        finish_cancellations = asyncio.Event()
        registering_snapshot_seen = asyncio.Event()
        device = None
        admin = None
        release_task = None
        runtime = ts.app["push_runtime"]
        original_pending_cancellations = runtime.manager.pending_cancellations_for_device

        async def delayed_pending_cancellations(requested_device_id):
            if requested_device_id == device_id:
                cancellations_started.set()
                await finish_cancellations.wait()
            return await original_pending_cancellations(requested_device_id)

        runtime.manager.pending_cancellations_for_device = delayed_pending_cancellations

        class ObserveRegisteringSnapshot:
            def __init__(self, websocket):
                self.websocket = websocket

            async def receive(self):
                message = await self.websocket.receive()
                if message.type == aiohttp.WSMsgType.TEXT:
                    data = json.loads(message.data)
                    if device_row_matches(data, device_id, status="registering"):
                        registering_snapshot_seen.set()
                return message

        try:
            async with aiohttp.ClientSession() as session:
                device = await session.ws_connect(base + "/ws/device")
                await device.send_json({
                    "type": "REGISTER",
                    "identity_scheme": server.IDENTITY_SCHEME,
                    "device_id": device_id,
                    "model": "M",
                    "ip": "1.1.1.2",
                    "version_code": 7,
                    "version_name": "t",
                    "push_state": {"status": "available"},
                })
                assert await _recv_type(device, "REGISTERED") is not None
                await asyncio.wait_for(cancellations_started.wait(), timeout=2)
                assert server.devices[device_id]["registration_ready"] is True
                assert server.devices[device_id]["status"] == "registering"

                admin = await session.ws_connect(base + "/ws/admin")

                async def release_after_stale_snapshot():
                    await asyncio.wait_for(registering_snapshot_seen.wait(), timeout=2)
                    finish_cancellations.set()

                release_task = asyncio.create_task(release_after_stale_snapshot())
                final_list = await _recv_type(
                    ObserveRegisteringSnapshot(admin),
                    "DEVICE_LIST",
                    predicate=lambda message: device_row_matches(
                        message, device_id, status="online"
                    ),
                )
                assert registering_snapshot_seen.is_set()
                assert final_list is not None
                assert device_row_matches(final_list, device_id, status="online")
                await release_task
                await device.close()
                await admin.close()
        finally:
            finish_cancellations.set()
            if release_task is not None and not release_task.done():
                release_task.cancel()
                await asyncio.gather(release_task, return_exceptions=True)
            await ts.close()

    asyncio.run(body())


def test_e2e_stale_connection_teardown_keeps_the_live_device_online(tmp_path):
    """The reported bug: the pre-reboot socket's teardown evicted the live one."""
    async def body():
        server._apply_data_dir(str(tmp_path))
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                # The pre-reboot connection, still open server-side.
                stale = await _register(session, base, "64b19041-0b8c-4ef4-82fd-000000000000")
                admin = await session.ws_connect(base + "/ws/admin")
                device_id = "64b19041-0b8c-4ef4-82fd-000000000000"
                dl = await _recv_device_list(admin, device_id, status="online")
                assert dl is not None

                # The rebooted client reconnects and re-registers.
                live = await _register(
                    session, base, device_id, version_code=8
                )
                dl = await _recv_device_list(
                    admin, device_id, status="online", version_code=8
                )
                assert dl is not None

                # The stale socket finally dies. The device is still connected,
                # so it must stay online.
                await stale.close()
                await asyncio.sleep(0.1)
                assert "64b19041-0b8c-4ef4-82fd-000000000000" in server.devices
                assert server.devices["64b19041-0b8c-4ef4-82fd-000000000000"]["ws"] is not None

                # And the live connection still works end to end.
                await live.send_json({
                    "type": "BATTERY_UPDATE", "level": 55, "charging": False,
                })
                dl = await _recv_device_list(
                    admin, device_id, status="online", battery={"level": 55}
                )
                assert dl is not None
                assert _row(dl, "64b19041-0b8c-4ef4-82fd-000000000000")["battery"]["level"] == 55
                assert not live.closed

                await live.close()
                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_battery_update_on_a_superseded_socket_is_ignored(tmp_path):
    """Late telemetry must not raise KeyError and kill the surviving handler."""
    async def body():
        server._apply_data_dir(str(tmp_path))
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                stale = await _register(session, base, "64b19041-0b8c-4ef4-82fd-000000000000")
                live = await _register(
                    session, base, "64b19041-0b8c-4ef4-82fd-000000000000",
                    version_code=8,
                )
                admin = await session.ws_connect(base + "/ws/admin")
                assert await _recv_device_list(
                    admin,
                    "64b19041-0b8c-4ef4-82fd-000000000000",
                    status="online",
                    version_code=8,
                ) is not None

                # Telemetry from the socket the device has already replaced.
                await stale.send_json({
                    "type": "BATTERY_UPDATE", "level": 11, "charging": False,
                })
                await asyncio.sleep(0.1)
                assert server.devices["64b19041-0b8c-4ef4-82fd-000000000000"].get("battery") is None

                # The live connection is untouched and still owns the device.
                await live.send_json({
                    "type": "BATTERY_UPDATE", "level": 66, "charging": True,
                })
                dl = await _recv_device_list(
                    admin,
                    "64b19041-0b8c-4ef4-82fd-000000000000",
                    status="online",
                    battery={"level": 66},
                )
                assert dl is not None
                assert _row(dl, "64b19041-0b8c-4ef4-82fd-000000000000")["battery"]["level"] == 66
                assert not live.closed

                await stale.close()
                await live.close()
                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_single_connection_disconnect_still_goes_offline(tmp_path):
    """The guard must not keep a genuinely gone device pinned online."""
    async def body():
        server._apply_data_dir(str(tmp_path))
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                only = await _register(session, base, "64b19041-0b8c-4ef4-82fd-000000000000")
                admin = await session.ws_connect(base + "/ws/admin")
                device_id = "64b19041-0b8c-4ef4-82fd-000000000000"
                dl = await _recv_device_list(admin, device_id, status="online")
                assert dl is not None

                await only.close()
                dl = await _recv_device_list(admin, device_id, status="offline")
                assert dl is not None
                assert "64b19041-0b8c-4ef4-82fd-000000000000" not in server.devices
                assert server.device_registry["64b19041-0b8c-4ef4-82fd-000000000000"]["last_seen"] > 0

                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_download_complete_on_a_superseded_socket_does_not_release_the_slot(tmp_path):
    """A stale socket must not advance the live connection's install (PR #71 review).

    The job was dispatched to the connection that owns the device_id; a leftover
    socket reporting progress would finish it behind the live client's back.
    """
    async def body():
        server._apply_data_dir(str(tmp_path))
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                stale = await _register(session, base, "64b19041-0b8c-4ef4-82fd-000000000000")
                live = await _register(session, base, "64b19041-0b8c-4ef4-82fd-000000000000")
                admin = await session.ws_connect(base + "/ws/admin")
                assert await _recv_device_list(
                    admin,
                    "64b19041-0b8c-4ef4-82fd-000000000000",
                    status="online",
                ) is not None
                await asyncio.sleep(0.1)

                slot = asyncio.get_running_loop().create_future()
                server.pending_transfers[("64b19041-0b8c-4ef4-82fd-000000000000", server.TASK_INSTALL)] = slot

                await stale.send_json({
                    "type": "DOWNLOAD_COMPLETE", "task": server.TASK_INSTALL,
                    "apk_filename": "x.apk",
                })
                await asyncio.sleep(0.1)
                assert not slot.done(), "stale socket released the live install's slot"
                assert await _recv_type(admin, "INSTALL_PROGRESS", timeout=0.3) is None

                # The owning connection still drives the job.
                await live.send_json({
                    "type": "DOWNLOAD_COMPLETE", "task": server.TASK_INSTALL,
                    "apk_filename": "x.apk",
                })
                await asyncio.sleep(0.1)
                assert slot.done()

                await stale.close()
                await live.close()
                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_terminal_result_on_a_superseded_socket_is_not_forwarded(tmp_path):
    """INSTALL_RESULT from a leftover socket must not settle the live job."""
    async def body():
        server._apply_data_dir(str(tmp_path))
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                stale = await _register(session, base, "64b19041-0b8c-4ef4-82fd-000000000000")
                live = await _register(session, base, "64b19041-0b8c-4ef4-82fd-000000000000")
                admin = await session.ws_connect(base + "/ws/admin")
                assert await _recv_device_list(
                    admin, "64b19041-0b8c-4ef4-82fd-000000000000", status="online"
                ) is not None
                await asyncio.sleep(0.1)

                await stale.send_json({
                    "type": "INSTALL_RESULT", "device_id": "64b19041-0b8c-4ef4-82fd-000000000000",
                    "status": "fail", "detail": "from the stale socket",
                })
                assert await _recv_type(admin, "INSTALL_RESULT", timeout=0.3) is None

                # The owning connection's result still reaches the console.
                await live.send_json({
                    "type": "INSTALL_RESULT", "device_id": "64b19041-0b8c-4ef4-82fd-000000000000",
                    "status": "success", "detail": "",
                })
                forwarded = await _recv_type(admin, "INSTALL_RESULT")
                assert forwarded is not None and forwarded["status"] == "success"

                await stale.close()
                await live.close()
                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_stale_teardown_does_not_free_the_live_transfer_slot(tmp_path):
    """A superseded socket closing must not release the live device's slot."""
    async def body():
        server._apply_data_dir(str(tmp_path))
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                stale = await _register(session, base, "64b19041-0b8c-4ef4-82fd-000000000000")
                live = await _register(session, base, "64b19041-0b8c-4ef4-82fd-000000000000")
                await asyncio.sleep(0.1)

                slot = asyncio.get_running_loop().create_future()
                server.pending_transfers[("64b19041-0b8c-4ef4-82fd-000000000000", server.TASK_INSTALL)] = slot

                await stale.close()
                await asyncio.sleep(0.1)
                assert not slot.done(), "stale teardown freed the live device's slot"

                await live.close()
                await asyncio.sleep(0.1)
                assert slot.done()
        finally:
            await ts.close()

    asyncio.run(body())
