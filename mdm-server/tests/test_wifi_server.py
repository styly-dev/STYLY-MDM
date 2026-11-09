"""Wi-Fi registration and status reports."""

from __future__ import annotations

import asyncio
import json

import aiohttp
from aiohttp.test_utils import TestServer

from styly_mdm import server


DEVICE_V4 = "11111111-1111-4111-8111-111111111111"
DEVICE_OLD = "22222222-2222-4222-8222-222222222222"


def test_registration_advertises_new_wifi_capability_only():
    kind, registered = server._parse_registration({
        "identity_scheme": server.IDENTITY_SCHEME,
        "device_id": DEVICE_V4,
        "capabilities": ["wifi_settings_v4"],
    }, "127.0.0.1")
    assert kind == "canonical"
    assert "wifi_settings_v4" in registered["capabilities"]

    _, old = server._parse_registration({
        "identity_scheme": server.IDENTITY_SCHEME,
        "device_id": DEVICE_OLD,
        "capabilities": ["wifi_settings_v3"],
    }, "127.0.0.1")
    assert "wifi_settings_v4" not in old["capabilities"]


def test_device_wifi_reports_survive_reconnect_and_reject_stale_socket(tmp_path):
    async def receive_type(ws, kind):
        while True:
            frame = await asyncio.wait_for(ws.receive_json(), 2)
            if frame.get("type") == kind:
                return frame

    async def receive_device_list(ws, predicate):
        async def wait_for_match():
            while True:
                rows = (await receive_type(ws, "DEVICE_LIST"))["devices"]
                if rows and predicate(rows[0]):
                    return rows
        return await asyncio.wait_for(wait_for_match(), 2)

    async def receive_stale_barrier(ws):
        while True:
            frame = await ws.receive()
            if frame.type == aiohttp.WSMsgType.PONG and frame.data == b"stale-processed":
                return
            assert frame.type not in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR)

    async def body():
        server._apply_data_dir(str(tmp_path))
        server.devices.clear()
        server.admin_connections.clear()
        test_server = TestServer(server.create_app())
        await test_server.start_server()
        base = str(test_server.make_url("/")).rstrip("/")
        revision = "44444444-4444-4444-8444-444444444444"
        state = {"managed": True, "policy_revision": revision, "last_result": None,
                 "busy": False, "available": True, "unavailable_reason": None}
        registration = {"type": "REGISTER", "identity_scheme": server.IDENTITY_SCHEME,
                        "device_id": DEVICE_V4, "capabilities": ["wifi_settings_v4"],
                        "wifi_state": state}
        async with aiohttp.ClientSession() as session:
            old = await session.ws_connect(base + "/ws/device", autoping=False)
            await old.send_json(registration)
            await receive_type(old, "REGISTERED")
            admin = await session.ws_connect(base + "/ws/admin")
            rows = (await receive_type(admin, "DEVICE_LIST"))["devices"]
            assert rows[0]["wifi_state"] == state
            live = await session.ws_connect(base + "/ws/device")
            await live.send_json(registration)
            await receive_type(live, "REGISTERED")
            await receive_type(admin, "DEVICE_LIST")
            failed = {**state, "last_result": {"operation_id": DEVICE_OLD, "revision": revision,
                      "status": "failed", "reason": "PERMISSION_DENIED", "rolled_back": False}}
            await old.send_json({"type": "WIFI_STATE", "wifi_state": {**state, "managed": False}})
            # PONG proves the server read the preceding frame on this socket before
            # the live connection can overwrite an incorrectly accepted update.
            await old.ping(b"stale-processed")
            await asyncio.wait_for(receive_stale_barrier(old), 2)
            assert server.devices[DEVICE_V4]["wifi_state"] == state
            assert server.device_registry[DEVICE_V4]["wifi_state"] == state
            await live.send_json({"type": "WIFI_STATE", "wifi_state": failed})
            rows = await receive_device_list(admin, lambda row: row["wifi_state"] == failed)
            assert rows[0]["wifi_state"] == failed
            assert rows[0]["wifi_state_updated_at"] > 0
            await live.send_json({"type": "WIFI_STATE", "wifi_state": {"password": "secret"}})
            await live.send_json({"type": "BATTERY_UPDATE", "level": 55, "charging": False})
            rows = await receive_device_list(admin, lambda row: (row.get("battery") or {}).get("level") == 55)
            assert rows[0]["wifi_state"] == failed
            assert "secret" not in json.dumps(rows)
            await old.close()
            await live.close()
            await admin.close()
        await test_server.close()
        server.load_registry()
        assert server.device_registry[DEVICE_V4]["wifi_state"] == failed
        assert server.device_registry[DEVICE_V4]["wifi_state_updated_at"] > 0

    asyncio.run(body())
