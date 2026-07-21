"""Tests for remote power control — reboot / power off (issue #74).

REBOOT_DEVICE / POWER_OFF_DEVICE fan out to online target sockets exactly like
LAUNCH_APP (un-throttled, payload-less EXECUTE_* frames) and ack the admin with
a *_SENT count. Unlike a launch there is no meaningful success RESULT: a
successful reboot/shutdown tears the socket down first, so the client's
*_RESULT: accepted / fail is only an acknowledgement, and the real success
signal is the device dropping offline. These tests pin the server-side fan-out
and dispatch shape with the same FakeWS harness test_retire.py uses.
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
# Fan-out — reboot
# ---------------------------------------------------------------------------

def test_reboot_fans_out_payloadless_execute_and_acks():
    async def body():
        admin = add_admin()
        d0 = add_device("dev0")
        d1 = add_device("dev1")

        await server.handle_reboot_device(admin, {"target_devices": ["dev0", "dev1"]})

        # Each target gets exactly one payload-less EXECUTE_REBOOT.
        assert frames_of(d0, "EXECUTE_REBOOT") == [{"type": "EXECUTE_REBOOT"}]
        assert frames_of(d1, "EXECUTE_REBOOT") == [{"type": "EXECUTE_REBOOT"}]
        # The admin is acked with the dispatch counts.
        assert frames_of(admin, "REBOOT_SENT") == [
            {"type": "REBOOT_SENT", "sent_count": 2, "target_count": 2}
        ]

    asyncio.run(body())


def test_power_off_fans_out_payloadless_execute_and_acks():
    async def body():
        admin = add_admin()
        d0 = add_device("dev0")

        await server.handle_power_off_device(admin, {"target_devices": ["dev0"]})

        assert frames_of(d0, "EXECUTE_POWER_OFF") == [{"type": "EXECUTE_POWER_OFF"}]
        assert frames_of(admin, "POWER_OFF_SENT") == [
            {"type": "POWER_OFF_SENT", "sent_count": 1, "target_count": 1}
        ]

    asyncio.run(body())


def test_power_wildcard_targets_all_online_devices():
    async def body():
        admin = add_admin()
        d0 = add_device("dev0")
        d1 = add_device("dev1")

        await server.handle_reboot_device(admin, {"target_devices": ["*"]})

        assert frames_of(d0, "EXECUTE_REBOOT")
        assert frames_of(d1, "EXECUTE_REBOOT")
        assert frames_of(admin, "REBOOT_SENT")[0]["target_count"] == 2

    asyncio.run(body())


def test_power_off_with_no_matching_devices_errors_and_dispatches_nothing():
    async def body():
        admin = add_admin()
        # No devices registered at all: nothing to power off.
        await server.handle_power_off_device(admin, {"target_devices": ["ghost"]})

        errors = frames_of(admin, "ERROR")
        assert errors and "No matching online devices" in errors[0]["message"]
        assert not frames_of(admin, "POWER_OFF_SENT")

    asyncio.run(body())


def test_power_off_skips_unknown_ids_but_dispatches_known_ones():
    async def body():
        admin = add_admin()
        d0 = add_device("dev0")

        await server.handle_power_off_device(
            admin, {"target_devices": ["dev0", "does-not-exist"]}
        )

        assert frames_of(d0, "EXECUTE_POWER_OFF") == [{"type": "EXECUTE_POWER_OFF"}]
        sent = frames_of(admin, "POWER_OFF_SENT")[0]
        assert sent["sent_count"] == 1 and sent["target_count"] == 1

    asyncio.run(body())


# ---------------------------------------------------------------------------
# End-to-end — the device's *_RESULT is forwarded to admins, device_id-stamped
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


async def _register(session, base: str, device_id: str):
    ws = await session.ws_connect(base + "/ws/device")
    await ws.send_json({
        "type": "REGISTER", "device_id": device_id, "model": "M",
        "ip": "1.1.1.2", "version_code": 8, "version_name": "0.4.0",
    })
    return ws


def test_e2e_reboot_result_is_forwarded_to_admin_stamped(tmp_path):
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

                await admin.send_json({"type": "REBOOT_DEVICE", "target_devices": ["dev0"]})
                assert (await _recv_type(admin, "REBOOT_SENT"))["sent_count"] == 1
                assert await _recv_type(d0, "EXECUTE_REBOOT") is not None

                # The client acks receipt before it invokes the SDK call; the server
                # must forward it to admins with the device_id stamped on.
                await d0.send_json({"type": "REBOOT_RESULT", "status": "accepted"})
                fwd = await _recv_type(admin, "REBOOT_RESULT")
                assert fwd is not None
                assert fwd["status"] == "accepted"
                assert fwd["device_id"] == "dev0"

                await d0.close()
                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())
