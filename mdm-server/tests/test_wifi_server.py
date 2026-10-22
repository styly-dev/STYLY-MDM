"""HTTP Wi-Fi profile API and explicit device deployment tests."""

from __future__ import annotations

import asyncio
import json

import aiohttp
from aiohttp import web
from aiohttp.test_utils import TestServer
import pytest

from styly_mdm import server
from styly_mdm.wifi_settings import WiFiSettingsStore, validate_profile_networks


DEVICE_V4 = "11111111-1111-4111-8111-111111111111"
DEVICE_OLD = "22222222-2222-4222-8222-222222222222"
DEVICE_FAIL = "33333333-3333-4333-8333-333333333333"


class _Socket:
    closed = False

    def __init__(self, fail=False):
        self.messages = []
        self.fail = fail

    async def send_str(self, payload):
        if self.fail:
            raise ConnectionError("injected socket failure")
        self.messages.append(json.loads(payload))


def _networks(password="office-secret"):
    return validate_profile_networks([{
        "ssid": "Office", "security": "WPA_PSK", "password": password, "hidden": False,
    }])


def _make_app(tmp_path, members):
    server._apply_data_dir(str(tmp_path))
    server.device_registry.clear()
    server.device_groups.clear()
    server.device_groups["Studio"] = list(members)
    server.devices.clear()
    server._wifi_profiles_encrypted = None
    server._wifi_profiles = None
    store = WiFiSettingsStore(persist=server._persist_wifi_profiles)
    app = web.Application()
    app["wifi_enabled"] = True
    app["wifi_store"] = store
    app["wifi_send_locks"] = {}
    app.router.add_get("/api/wifi/state", server.wifi_state_handler)
    app.router.add_route("GET", "/api/wifi/groups/{name}", server.wifi_group_handler)
    app.router.add_route("PUT", "/api/wifi/groups/{name}", server.wifi_group_handler)
    app.router.add_route("DELETE", "/api/wifi/groups/{name}", server.wifi_group_handler)
    app.router.add_post("/api/wifi/groups/{name}/deploy", server.wifi_deploy_handler)
    return app, store


def _connect(device_id, socket, capability):
    server.devices[device_id] = {
        "device_id": device_id,
        "ws": socket,
        "status": "online",
        "identity_kind": "canonical",
        "capabilities": ["wifi_settings_v4"] if capability else [],
    }


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


def test_http_save_is_unauthenticated_and_delete_sends_only_after_explicit_deploy(tmp_path):
    async def body():
        app, store = _make_app(tmp_path, [DEVICE_V4])
        socket = _Socket()
        _connect(DEVICE_V4, socket, True)
        profile = store.put_profile("Studio", _networks())
        test_server = TestServer(app)
        await test_server.start_server()
        async with aiohttp.ClientSession() as session:
            base = str(test_server.make_url("/")).rstrip("/")
            origin = {"Origin": base}

            async with session.get(base + "/api/wifi/state") as response:
                assert response.status == 200
                state = await response.json()
                assert "devices" not in state
                assert "allow_http" not in state
                assert state == {"enabled": True}

            async with session.delete(
                base + "/api/wifi/groups/Studio",
                headers=origin,
                json={"expected_revision": profile["revision"]},
            ) as response:
                assert response.status == 200
                unmanaged = await response.json()
            assert unmanaged["mode"] == "unmanaged"
            assert socket.messages == []

            async with session.post(
                base + "/api/wifi/groups/Studio/deploy",
                headers=origin,
                json={"expected_revision": unmanaged["revision"]},
            ) as response:
                assert response.status == 200
                result = await response.json()
            assert result["online_target_count"] == 1
            assert result["operations"][0]["status"] == "sent"
            assert socket.messages == [{
                "type": "EXECUTE_WIFI_APPLY",
                "operation_id": result["operations"][0]["operation_id"],
                "revision": unmanaged["revision"],
                "mode": "unmanaged",
                "networks": [],
            }]

            async with session.post(base + "/api/wifi/groups/Studio/recovery", headers=origin) as response:
                assert response.status == 404
        await test_server.close()

    asyncio.run(body())


def test_deploy_freezes_online_targets_and_keeps_outcomes_only_in_response(tmp_path):
    async def body():
        app, store = _make_app(tmp_path, [DEVICE_V4, DEVICE_OLD, DEVICE_FAIL, "offline-device"])
        capable = _Socket()
        old = _Socket()
        broken = _Socket(fail=True)
        _connect(DEVICE_V4, capable, True)
        _connect(DEVICE_OLD, old, False)
        _connect(DEVICE_FAIL, broken, True)
        profile = store.put_profile("Studio", _networks())
        test_server = TestServer(app)
        await test_server.start_server()
        async with aiohttp.ClientSession() as session:
            base = str(test_server.make_url("/")).rstrip("/")
            async with session.post(
                base + "/api/wifi/groups/Studio/deploy",
                headers={"Origin": base},
                json={"expected_revision": profile["revision"]},
            ) as response:
                result = await response.json()
            assert result["online_target_count"] == 3
            by_id = {item["device_id"]: item for item in result["operations"]}
            assert by_id[DEVICE_V4]["status"] == "sent"
            assert by_id[DEVICE_OLD]["status"] == "failed"
            assert by_id[DEVICE_OLD]["reason"] == "unsupported"
            assert by_id[DEVICE_FAIL]["status"] == "unknown"
            assert by_id[DEVICE_FAIL]["reason"] == "socket_send_failed"
            assert len(capable.messages) == 1
            assert old.messages == []
            with open(server.REGISTRY_PATH, encoding="utf-8") as handle:
                registry = json.load(handle)
            saved = WiFiSettingsStore(registry["wifi_profiles"])
            assert saved.state == store.state
            assert "devices" not in saved.state
        await test_server.close()

    asyncio.run(body())


def test_profile_revision_conflict_and_origin_check(tmp_path):
    async def body():
        app, store = _make_app(tmp_path, [])
        profile = store.put_profile("Studio", _networks())
        test_server = TestServer(app)
        await test_server.start_server()
        async with aiohttp.ClientSession() as session:
            base = str(test_server.make_url("/")).rstrip("/")
            payload = {"expected_revision": profile["revision"], "networks": [{
                "ssid": "Guest", "security": "OPEN", "password": "", "hidden": False,
            }]}
            async with session.put(
                base + "/api/wifi/groups/Studio", headers={"Origin": "http://attacker.invalid"}, json=payload,
            ) as response:
                assert response.status == 403
            async with session.put(
                base + "/api/wifi/groups/Studio", headers={"Origin": base}, json=payload,
            ) as response:
                assert response.status == 200
                saved = await response.json()
            async with session.post(
                base + "/api/wifi/groups/Studio/deploy",
                headers={"Origin": base},
                json={"expected_revision": profile["revision"]},
            ) as response:
                assert response.status == 409
            assert store.profile("Studio")["revision"] == saved["revision"]
        await test_server.close()

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
            old = await session.ws_connect(base + "/ws/device")
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
