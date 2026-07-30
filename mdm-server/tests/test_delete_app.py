"""Tests for remote app uninstall — DELETE_APP / EXECUTE_UNINSTALL (issue #63).

DELETE_APP fans out to online target sockets exactly like LAUNCH_APP
(un-throttled, nothing transferred) and acks the admin with DELETE_APP_SENT.
Unlike the retire flow, the client survives the uninstall, so its
DELETE_APP_RESULT is a normal always-arrives result relayed to every admin.

Two rules carry real consequences and are pinned here:
  * STYLY-MDM's own packages are refused, so a typo cannot unmanage the fleet
    behind the console's back (only RETIRE_DEVICE may remove the client).
  * A device that cleared its startup app to uninstall it has that reflected in
    the registry — but only when the uninstall actually succeeded, since the
    client restores the setting it cleared whenever the uninstall fails.
"""

from __future__ import annotations

import asyncio
import json

import aiohttp
import pytest
from aiohttp.test_utils import TestServer

from styly_mdm import server


class FakeWS:
    """Minimal stand-in for a WebSocketResponse that records sent frames."""

    def __init__(self):
        self.sent: list[str] = []

    async def send_str(self, s: str) -> None:
        self.sent.append(s)


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


def add_device(device_id: str) -> FakeWS:
    ws = FakeWS()
    server.devices[device_id] = {
        "ws": ws, "device_id": device_id, "model": "M", "ip": "1.1.1.1",
        "status": "online", "startup_app": None, "battery": None,
        "version_code": 8, "version_name": "0.4.0",
    }
    return ws


def add_admin() -> FakeWS:
    ws = FakeWS()
    server.admin_connections.add(ws)
    return ws


def frames_of(ws: FakeWS, msg_type: str) -> list[dict]:
    return [json.loads(m) for m in ws.sent if json.loads(m).get("type") == msg_type]


# ---------------------------------------------------------------------------
# Fan-out
# ---------------------------------------------------------------------------

def test_delete_app_fans_out_execute_uninstall_and_acks():
    async def body():
        admin = add_admin()
        d0 = add_device("dev0")
        d1 = add_device("dev1")

        await server.handle_delete_app(
            admin, {"target_devices": ["dev0", "dev1"], "package_name": "com.example.app"}
        )

        expected = [{"type": "EXECUTE_UNINSTALL", "package_name": "com.example.app"}]
        assert frames_of(d0, "EXECUTE_UNINSTALL") == expected
        assert frames_of(d1, "EXECUTE_UNINSTALL") == expected
        assert frames_of(admin, "DELETE_APP_SENT") == [{
            "type": "DELETE_APP_SENT",
            "package_name": "com.example.app",
            "sent_count": 2,
            "target_count": 2,
        }]

    asyncio.run(body())


def test_delete_app_without_package_name_errors_and_dispatches_nothing():
    async def body():
        admin = add_admin()
        d0 = add_device("dev0")

        await server.handle_delete_app(admin, {"target_devices": ["dev0"]})

        errors = frames_of(admin, "ERROR")
        assert errors and "package_name is required" in errors[0]["message"]
        assert not frames_of(d0, "EXECUTE_UNINSTALL")

    asyncio.run(body())


@pytest.mark.parametrize("package_name", sorted(server.PROTECTED_PACKAGES))
def test_delete_app_refuses_styly_mdm_packages(package_name):
    """The client and its guard may only be removed through RETIRE_DEVICE.

    Uninstalling either here would skip the ordered retire ceremony and leave the
    device unmanaged with no console-side way back.
    """
    async def body():
        admin = add_admin()
        d0 = add_device("dev0")

        await server.handle_delete_app(
            admin, {"target_devices": ["dev0"], "package_name": package_name}
        )

        errors = frames_of(admin, "ERROR")
        assert errors and "Retire Device" in errors[0]["message"]
        assert not frames_of(d0, "EXECUTE_UNINSTALL")
        assert not frames_of(admin, "DELETE_APP_SENT")

    asyncio.run(body())


def test_delete_app_wildcard_targets_all_online_devices():
    async def body():
        admin = add_admin()
        d0 = add_device("dev0")
        d1 = add_device("dev1")

        await server.handle_delete_app(
            admin, {"target_devices": ["*"], "package_name": "com.example.app"}
        )

        assert frames_of(d0, "EXECUTE_UNINSTALL")
        assert frames_of(d1, "EXECUTE_UNINSTALL")
        assert frames_of(admin, "DELETE_APP_SENT")[0]["target_count"] == 2

    asyncio.run(body())


def test_delete_app_with_no_matching_devices_errors():
    async def body():
        admin = add_admin()

        await server.handle_delete_app(
            admin, {"target_devices": ["ghost"], "package_name": "com.example.app"}
        )

        errors = frames_of(admin, "ERROR")
        assert errors and "No matching online devices" in errors[0]["message"]
        assert not frames_of(admin, "DELETE_APP_SENT")

    asyncio.run(body())


def test_delete_app_skips_unknown_ids_but_dispatches_known_ones():
    async def body():
        admin = add_admin()
        d0 = add_device("dev0")

        await server.handle_delete_app(
            admin,
            {"target_devices": ["dev0", "does-not-exist"], "package_name": "com.example.app"},
        )

        assert frames_of(d0, "EXECUTE_UNINSTALL")
        sent = frames_of(admin, "DELETE_APP_SENT")[0]
        assert sent["sent_count"] == 1 and sent["target_count"] == 1

    asyncio.run(body())


# ---------------------------------------------------------------------------
# End-to-end — the device's DELETE_APP_RESULT and the startup-app follow-up
# ---------------------------------------------------------------------------

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


async def _register(session, base: str, device_id: str, startup_app: dict | None = None):
    ws = await session.ws_connect(base + "/ws/device")
    await ws.send_json({
        "type": "REGISTER", "device_id": device_id, "model": "M",
        "ip": "1.1.1.2", "version_code": 8, "version_name": "0.4.0",
        "startup_app": startup_app,
    })
    return ws


def test_e2e_delete_app_result_is_forwarded_to_admin_stamped(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                d0 = await _register(session, base, "dev0")
                admin = await session.ws_connect(base + "/ws/admin")
                assert await _recv_type(admin, "DEVICE_LIST") is not None

                await admin.send_json({
                    "type": "DELETE_APP",
                    "target_devices": ["dev0"],
                    "package_name": "com.example.app",
                })
                assert (await _recv_type(admin, "DELETE_APP_SENT"))["sent_count"] == 1
                cmd = await _recv_type(d0, "EXECUTE_UNINSTALL")
                assert cmd is not None and cmd["package_name"] == "com.example.app"

                await d0.send_json({
                    "type": "DELETE_APP_RESULT",
                    "status": "success",
                    "package_name": "com.example.app",
                })
                fwd = await _recv_type(admin, "DELETE_APP_RESULT")
                assert fwd is not None
                assert fwd["status"] == "success"
                assert fwd["device_id"] == "dev0"

                await d0.close()
                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_successful_uninstall_clears_the_recorded_startup_app(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                startup = {"package_name": "com.example.app", "extra": ""}
                d0 = await _register(session, base, "dev0", startup_app=startup)
                admin = await session.ws_connect(base + "/ws/admin")
                assert await _recv_type(admin, "DEVICE_LIST") is not None
                assert server.devices["dev0"]["startup_app"] == startup

                await d0.send_json({
                    "type": "DELETE_APP_RESULT",
                    "status": "success",
                    "package_name": "com.example.app",
                    "startup_app_cleared": True,
                })
                assert await _recv_type(admin, "DELETE_APP_RESULT") is not None
                # The console is told about the new state, not just the result.
                assert await _recv_type(admin, "DEVICE_LIST") is not None

                assert server.devices["dev0"]["startup_app"] is None
                assert server.device_registry["dev0"]["startup_app"] is None

                await d0.close()
                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_successful_uninstall_clears_a_stale_startup_app_without_the_flag(tmp_path):
    """The server does not depend on the client's flag to notice its own record.

    A SET_STARTUP_APP is recorded here at dispatch, before the device confirms it,
    so the two sides can disagree — and a record naming the package that was just
    removed is stale whatever the device believed about it.
    """
    async def body():
        server._apply_data_dir(str(tmp_path))
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                startup = {"package_name": "com.example.app", "extra": ""}
                d0 = await _register(session, base, "dev0", startup_app=startup)
                admin = await session.ws_connect(base + "/ws/admin")
                assert await _recv_type(admin, "DEVICE_LIST") is not None

                await d0.send_json({
                    "type": "DELETE_APP_RESULT",
                    "status": "success",
                    "package_name": "com.example.app",
                })
                assert await _recv_type(admin, "DELETE_APP_RESULT") is not None
                assert await _recv_type(admin, "DEVICE_LIST") is not None

                assert server.devices["dev0"]["startup_app"] is None

                await d0.close()
                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_uninstalling_another_package_leaves_the_startup_app_alone(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                startup = {"package_name": "com.example.kiosk", "extra": ""}
                d0 = await _register(session, base, "dev0", startup_app=startup)
                admin = await session.ws_connect(base + "/ws/admin")
                assert await _recv_type(admin, "DEVICE_LIST") is not None

                await d0.send_json({
                    "type": "DELETE_APP_RESULT",
                    "status": "success",
                    "package_name": "com.example.other",
                })
                assert await _recv_type(admin, "DELETE_APP_RESULT") is not None

                assert server.devices["dev0"]["startup_app"] == startup

                await d0.close()
                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_failed_uninstall_keeps_the_recorded_startup_app(tmp_path):
    """A failed uninstall restores the setting device-side, so the server keeps it.

    Acting on startup_app_cleared regardless of status would leave the console
    showing no startup app for a device that still has one.
    """
    async def body():
        server._apply_data_dir(str(tmp_path))
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                startup = {"package_name": "com.example.app", "extra": ""}
                d0 = await _register(session, base, "dev0", startup_app=startup)
                admin = await session.ws_connect(base + "/ws/admin")
                assert await _recv_type(admin, "DEVICE_LIST") is not None

                await d0.send_json({
                    "type": "DELETE_APP_RESULT",
                    "status": "fail",
                    "package_name": "com.example.app",
                    "error": "pbsControlAPPManger returned 1: Failure",
                    "startup_app_cleared": True,
                })
                assert await _recv_type(admin, "DELETE_APP_RESULT") is not None

                assert server.devices["dev0"]["startup_app"] == startup

                await d0.close()
                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())
