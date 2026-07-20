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


async def _recv_type(ws, msg_type: str, timeout: float = 2.0) -> dict | None:
    try:
        while True:
            msg = await asyncio.wait_for(ws.receive(), timeout)
            if msg.type == aiohttp.WSMsgType.TEXT:
                data = json.loads(msg.data)
                if data.get("type") == msg_type:
                    return data
            elif msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.CLOSING,
                              aiohttp.WSMsgType.ERROR):
                return None
    except asyncio.TimeoutError:
        return None


async def _register(session, base: str, device_id: str):
    ws = await session.ws_connect(base + "/ws/device")
    await ws.send_json({
        "type": "REGISTER", "device_id": device_id, "model": "M",
        "ip": "1.1.1.2", "version_code": 7, "version_name": "t",
    })
    return ws


def _row(device_list: dict, device_id: str) -> dict:
    return next(d for d in device_list["devices"] if d["device_id"] == device_id)


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
                stale = await _register(session, base, "dev0")
                admin = await session.ws_connect(base + "/ws/admin")
                dl = await _recv_type(admin, "DEVICE_LIST")
                assert _row(dl, "dev0")["status"] == "online"

                # The rebooted client reconnects and re-registers.
                live = await _register(session, base, "dev0")
                dl = await _recv_type(admin, "DEVICE_LIST")
                assert _row(dl, "dev0")["status"] == "online"

                # The stale socket finally dies. The device is still connected,
                # so it must stay online.
                await stale.close()
                await asyncio.sleep(0.1)
                assert "dev0" in server.devices
                assert server.devices["dev0"]["ws"] is not None

                # And the live connection still works end to end.
                await live.send_json({
                    "type": "BATTERY_UPDATE", "level": 55, "charging": False,
                })
                dl = await _recv_type(admin, "DEVICE_LIST")
                assert _row(dl, "dev0")["status"] == "online"
                assert _row(dl, "dev0")["battery"]["level"] == 55
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
                stale = await _register(session, base, "dev0")
                live = await _register(session, base, "dev0")
                admin = await session.ws_connect(base + "/ws/admin")
                assert await _recv_type(admin, "DEVICE_LIST") is not None

                # Telemetry from the socket the device has already replaced.
                await stale.send_json({
                    "type": "BATTERY_UPDATE", "level": 11, "charging": False,
                })
                await asyncio.sleep(0.1)
                assert server.devices["dev0"].get("battery") is None

                # The live connection is untouched and still owns the device.
                await live.send_json({
                    "type": "BATTERY_UPDATE", "level": 66, "charging": True,
                })
                dl = await _recv_type(admin, "DEVICE_LIST")
                assert _row(dl, "dev0")["battery"]["level"] == 66
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
                only = await _register(session, base, "dev0")
                admin = await session.ws_connect(base + "/ws/admin")
                dl = await _recv_type(admin, "DEVICE_LIST")
                assert _row(dl, "dev0")["status"] == "online"

                await only.close()
                dl = await _recv_type(admin, "DEVICE_LIST")
                assert _row(dl, "dev0")["status"] == "offline"
                assert "dev0" not in server.devices
                assert server.device_registry["dev0"]["last_seen"] > 0

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
                stale = await _register(session, base, "dev0")
                live = await _register(session, base, "dev0")
                await asyncio.sleep(0.1)

                slot = asyncio.get_running_loop().create_future()
                server.pending_transfers[("dev0", server.TASK_INSTALL)] = slot

                await stale.close()
                await asyncio.sleep(0.1)
                assert not slot.done(), "stale teardown freed the live device's slot"

                await live.close()
                await asyncio.sleep(0.1)
                assert slot.done()
        finally:
            await ts.close()

    asyncio.run(body())
