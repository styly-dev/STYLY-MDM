"""Wi-Fi registration and status reports."""

from __future__ import annotations

import asyncio
import json

import aiohttp
from aiohttp.test_utils import TestServer

from styly_mdm import configuration_packages, server
from server_test_helpers import reset_server_state


DEVICE_ID = "11111111-1111-4111-8111-111111111111"


def test_priority_unsupported_reasons_are_preserved_in_configuration_state():
    revision = "44444444-4444-4444-8444-444444444444"
    for reason, result_status in (
        ("UNSUPPORTED_HIDDEN_NETWORK", "unsupported"),
        ("UNSUPPORTED_PRIORITY_NETWORK_LIMIT", "unsupported"),
    ):
        state = configuration_packages.public_configuration_state({
            "package_id": revision,
            "revision": revision,
            "status": "complete",
            "results": {
                "wifi": {
                    "status": result_status,
                    "reason": reason,
                    "password": "must-not-be-forwarded",
                },
            },
        })
        assert state == {
            "package_id": revision,
            "revision": revision,
            "status": "complete",
            "results": {"wifi": {"status": result_status, "reason": reason}},
        }
        assert "must-not-be-forwarded" not in json.dumps(state)


def test_priority_monitor_broadcast_and_registration_survive_reconnect(tmp_path):
    async def receive_type(ws, kind):
        while True:
            frame = await asyncio.wait_for(ws.receive_json(), 2)
            if frame.get("type") == kind:
                return frame

    async def receive_device_list(ws, expected_state):
        while True:
            rows = (await receive_type(ws, "DEVICE_LIST"))["devices"]
            if rows and rows[0].get("wifi_state") == expected_state:
                return rows[0]

    async def run_server():
        app_server = TestServer(server.create_app())
        await app_server.start_server()
        return app_server

    async def body():
        server._apply_data_dir(str(tmp_path))
        server.devices.clear()
        server.admin_connections.clear()
        server.REGISTRY_PATH.write_text(json.dumps({"devices": {}, "groups": {}}))
        base_state = {
            "busy": False, "available": True, "unavailable_reason": None,
            "priority_monitor": "monitoring",
        }
        registration = {
            "type": "REGISTER", "identity_scheme": server.IDENTITY_SCHEME,
            "device_id": DEVICE_ID,
            "wifi_state": base_state,
        }

        first_server = await run_server()
        base = str(first_server.make_url("/")).rstrip("/")
        async with aiohttp.ClientSession() as session:
            device = await session.ws_connect(base + "/ws/device", autoping=False)
            await device.send_json(registration)
            await receive_type(device, "REGISTERED")
            admin = await session.ws_connect(base + "/ws/admin")
            row = await receive_device_list(admin, server.public_wifi_state(base_state))
            assert row["wifi_state"]["priority_monitor"] == "monitoring"

            switching = {**base_state, "priority_monitor": "switching"}
            await device.send_json({"type": "WIFI_STATE", "wifi_state": switching})
            row = await receive_device_list(admin, server.public_wifi_state(switching))
            assert row["wifi_state"]["priority_monitor"] == "switching"
            await device.close()
            await admin.close()
        await first_server.close()

        server.devices.clear()
        server.admin_connections.clear()
        server.load_registry()
        assert server.device_registry[DEVICE_ID]["wifi_state"]["priority_monitor"] == "switching"

        second_server = await run_server()
        base = str(second_server.make_url("/")).rstrip("/")
        reconnect_registration = {**registration, "wifi_state": switching}
        async with aiohttp.ClientSession() as session:
            device = await session.ws_connect(base + "/ws/device", autoping=False)
            await device.send_json(reconnect_registration)
            await receive_type(device, "REGISTERED")
            admin = await session.ws_connect(base + "/ws/admin")
            row = await receive_device_list(admin, server.public_wifi_state(switching))
            assert row["wifi_state"]["priority_monitor"] == "switching"
            await device.close()
            await admin.close()
        await second_server.close()

    asyncio.run(body())


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
        state = {"busy": False, "available": True, "unavailable_reason": None, "priority_monitor": "off"}
        registration = {"type": "REGISTER", "identity_scheme": server.IDENTITY_SCHEME,
                        "device_id": DEVICE_ID,
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
            unavailable = {**state, "available": False, "unavailable_reason": "PERMISSION_DENIED"}
            await old.send_json({"type": "WIFI_STATE", "wifi_state": {**state, "busy": True}})
            # PONG proves the server read the preceding frame on this socket before
            # the live connection can overwrite an incorrectly accepted update.
            await old.ping(b"stale-processed")
            await asyncio.wait_for(receive_stale_barrier(old), 2)
            assert server.devices[DEVICE_ID]["wifi_state"] == state
            assert server.device_registry[DEVICE_ID]["wifi_state"] == state
            await live.send_json({"type": "WIFI_STATE", "wifi_state": unavailable})
            rows = await receive_device_list(admin, lambda row: row["wifi_state"] == unavailable)
            assert rows[0]["wifi_state"] == unavailable
            # A repeated or invalid report changes nothing, so the next list is the battery update.
            await live.send_json({"type": "WIFI_STATE", "wifi_state": unavailable})
            await live.send_json({"type": "WIFI_STATE", "wifi_state": {"password": "secret"}})
            await live.send_json({"type": "BATTERY_UPDATE", "level": 55, "charging": False})
            rows = (await receive_type(admin, "DEVICE_LIST"))["devices"]
            assert rows[0]["battery"]["level"] == 55
            assert rows[0]["wifi_state"] == unavailable
            assert "wifi_state_updated_at" not in rows[0]
            assert "secret" not in json.dumps(rows)

            reconnected = await session.ws_connect(base + "/ws/device")
            await reconnected.send_json({key: value for key, value in registration.items() if key != "wifi_state"})
            await receive_type(reconnected, "REGISTERED")
            await receive_device_list(admin, lambda row: row["status"] == "online" and row["wifi_state"] is None)
            assert server.device_registry[DEVICE_ID]["wifi_state"] == unavailable
            await reconnected.close()
            await old.close()
            await live.close()
            await admin.close()
        await test_server.close()
        server.load_registry()
        assert server.device_registry[DEVICE_ID]["wifi_state"] == unavailable
        assert "wifi_state_updated_at" not in server.device_registry[DEVICE_ID]

    asyncio.run(body())
