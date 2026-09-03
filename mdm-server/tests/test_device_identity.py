from __future__ import annotations

import asyncio
import json
from types import SimpleNamespace

import aiohttp
import pytest
from aiohttp.test_utils import TestServer

from styly_mdm import push_runtime, server


GUID = "64b19041-0b8c-4ef4-82fd-000000000000"


@pytest.fixture(autouse=True)
def reset_state(monkeypatch):
    monkeypatch.setenv("MDM_DEVICE_IDENTITY_MODE", "legacy-compatible")
    for collection in (
        server.devices,
        server.device_registry,
        server.device_groups,
        server.provisional_connections,
        server.admin_connections,
        server.pending_self_updates,
        server.pending_transfers,
        server.last_install_dispatch,
        server.pending_retires,
    ):
        collection.clear()
    yield
    server.provisional_connections.clear()


async def recv_type(ws, expected: str, timeout: float = 2.0) -> dict:
    while True:
        message = await asyncio.wait_for(ws.receive(), timeout)
        assert message.type == aiohttp.WSMsgType.TEXT
        payload = json.loads(message.data)
        if payload.get("type") == expected:
            return payload


def provisional(status: str = "access_denied", diagnostic: str = "permission required") -> dict:
    return {
        "type": "REGISTER",
        "identity_scheme": server.IDENTITY_SCHEME,
        "device_id": None,
        "model": "PICO 4 Enterprise",
        "ip": "192.168.1.20",
        "version_code": 10,
        "version_name": "0.6.0",
        "identity": {
            "state": "provisional",
            "status": status,
            "diagnostic": diagnostic,
            "mint_attempted": False,
        },
    }


def canonical(device_id: str = GUID) -> dict:
    return {
        "type": "REGISTER",
        "identity_scheme": server.IDENTITY_SCHEME,
        "device_id": device_id,
        "model": "PICO 4 Enterprise",
        "ip": "192.168.1.20",
        "version_code": 10,
        "version_name": "0.6.0",
        "capabilities": [],
        "push_runtime": {"active": None},
        "startup_app": None,
    }


def test_provisional_registration_promotes_without_persistence(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        test_server = TestServer(server.create_app())
        await test_server.start_server()
        base = f"http://{test_server.host}:{test_server.port}"
        try:
            async with aiohttp.ClientSession() as session:
                admin = await session.ws_connect(base + "/ws/admin")
                await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")
                device = await session.ws_connect(base + "/ws/device")
                payload = provisional(diagnostic="line one\nline two<script>")
                await device.send_json(payload)
                await recv_type(device, "REGISTERED_PROVISIONAL")
                snapshot = await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")
                assert len(snapshot["connections"]) == 1
                assert snapshot["connections"][0]["diagnostic"] == "line one line two<script>"
                assert server.device_registry == {}

                await device.send_json(canonical())
                await recv_type(device, "REGISTERED")
                snapshot = await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")
                assert snapshot["connections"] == []
                listed = await recv_type(admin, "DEVICE_LIST")
                assert [row["device_id"] for row in listed["devices"]] == [GUID]
                assert GUID in server.device_registry
                assert not server.provisional_connections

                await device.close()
                await admin.close()
        finally:
            await test_server.close()

    asyncio.run(body())


def test_repeated_provisional_updates_one_entry_and_disconnect_removes_it(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        test_server = TestServer(server.create_app())
        await test_server.start_server()
        base = f"http://{test_server.host}:{test_server.port}"
        try:
            async with aiohttp.ClientSession() as session:
                admin = await session.ws_connect(base + "/ws/admin")
                await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")
                device = await session.ws_connect(base + "/ws/device")
                await device.send_json(provisional(status="resolving", diagnostic="first"))
                await recv_type(device, "REGISTERED_PROVISIONAL")
                first = await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")

                await device.send_json(provisional(status="io_error", diagnostic="second"))
                await recv_type(device, "REGISTERED_PROVISIONAL")
                second = await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")
                assert len(second["connections"]) == 1
                assert second["connections"][0]["identity_status"] == "io_error"
                assert second["connections"][0]["diagnostic"] == "second"
                assert second["connections"][0]["connected_at"] == first["connections"][0]["connected_at"]

                await device.close()
                disconnected = await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")
                assert disconnected["connections"] == []
                await admin.close()
        finally:
            await test_server.close()

    asyncio.run(body())


def test_malformed_provisional_registration_is_rejected(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        test_server = TestServer(server.create_app())
        await test_server.start_server()
        base = f"http://{test_server.host}:{test_server.port}"
        try:
            async with aiohttp.ClientSession() as session:
                device = await session.ws_connect(base + "/ws/device")
                payload = provisional()
                payload["identity"]["mint_attempted"] = "false"
                await device.send_json(payload)
                error = await recv_type(device, "ERROR")
                assert "mint_attempted" in error["message"]
                assert server.device_registry == {}
                assert server.provisional_connections == {}
        finally:
            await test_server.close()

    asyncio.run(body())


def test_canonical_socket_rejects_identity_change(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        test_server = TestServer(server.create_app())
        await test_server.start_server()
        base = f"http://{test_server.host}:{test_server.port}"
        try:
            async with aiohttp.ClientSession() as session:
                device = await session.ws_connect(base + "/ws/device")
                await device.send_json(canonical())
                await recv_type(device, "REGISTERED")
                changed = canonical("74b19041-0b8c-4ef4-82fd-000000000000")
                await device.send_json(changed)
                error = await recv_type(device, "ERROR")
                assert "cannot change" in error["message"]
                assert GUID in server.device_registry
                assert changed["device_id"] not in server.device_registry
        finally:
            await test_server.close()

    asyncio.run(body())


def test_strict_mode_rejects_scheme_less_registration(tmp_path, monkeypatch):
    async def body():
        monkeypatch.setenv("MDM_DEVICE_IDENTITY_MODE", "cutover-strict")
        server._apply_data_dir(str(tmp_path))
        test_server = TestServer(server.create_app())
        await test_server.start_server()
        base = f"http://{test_server.host}:{test_server.port}"
        try:
            async with aiohttp.ClientSession() as session:
                device = await session.ws_connect(base + "/ws/device")
                await device.send_json({"type": "REGISTER", "device_id": "SERIAL-1"})
                error = await recv_type(device, "ERROR")
                assert "disabled" in error["message"]
                assert server.device_registry == {}
        finally:
            await test_server.close()

    asyncio.run(body())


def test_canonical_registration_is_new_device_and_does_not_settle_legacy_update(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        test_server = TestServer(server.create_app())
        await test_server.start_server()
        base = f"http://{test_server.host}:{test_server.port}"
        try:
            async with aiohttp.ClientSession() as session:
                legacy = await session.ws_connect(base + "/ws/device")
                await legacy.send_json({
                    "type": "REGISTER",
                    "device_id": "SERIAL-1",
                    "model": "PICO",
                    "version_code": 9,
                })
                await recv_type(legacy, "REGISTERED")
                server.device_registry["SERIAL-1"]["label"] = "Old label"
                server.device_groups["old-group"] = ["SERIAL-1"]
                await legacy.send_json({
                    "type": "SELF_UPDATE_STARTING",
                    "correlation_id": "update-1",
                    "target_version_code": 10,
                    "package_name": "com.styly.mdmclient",
                    "apk_filename": "",
                })
                await asyncio.sleep(0)

                current = await session.ws_connect(base + "/ws/device")
                await current.send_json(canonical())
                await recv_type(current, "REGISTERED")

                assert "SERIAL-1" in server.device_registry
                assert GUID in server.device_registry
                assert server.device_registry[GUID]["label"] == ""
                assert server.device_groups == {"old-group": ["SERIAL-1"]}
                assert "SERIAL-1" in server.pending_self_updates
                persisted = json.loads(server.REGISTRY_PATH.read_text())
                assert set(persisted["devices"]) == {"SERIAL-1", GUID}
                assert persisted["devices"]["SERIAL-1"]["identity_kind"] == "legacy"
                assert persisted["devices"][GUID]["identity_kind"] == "canonical"
                assert persisted["groups"] == {"old-group": ["SERIAL-1"]}

                await legacy.close()
                replacement_failed = await session.ws_connect(base + "/ws/device")
                await replacement_failed.send_json({
                    "type": "REGISTER",
                    "device_id": "SERIAL-1",
                    "model": "PICO",
                    "version_code": 9,
                })
                await recv_type(replacement_failed, "REGISTERED")
                await asyncio.sleep(0)
                assert "SERIAL-1" not in server.pending_self_updates

                await replacement_failed.close()
                await current.close()
        finally:
            await test_server.close()

    asyncio.run(body())
def test_provisional_socket_rejects_legacy_registration(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        test_server = TestServer(server.create_app())
        await test_server.start_server()
        base = f"http://{test_server.host}:{test_server.port}"
        try:
            async with aiohttp.ClientSession() as session:
                device = await session.ws_connect(base + "/ws/device")
                await device.send_json(provisional())
                await recv_type(device, "REGISTERED_PROVISIONAL")
                await device.send_json({"type": "REGISTER", "device_id": "SERIAL-1"})
                error = await recv_type(device, "ERROR")
                assert "only promote" in error["message"]
                assert server.device_registry == {}
        finally:
            await test_server.close()

    asyncio.run(body())


def test_invalid_identity_mode_fails_startup(tmp_path, monkeypatch):
    monkeypatch.setenv("MDM_DEVICE_IDENTITY_MODE", "strict-ish")
    server._apply_data_dir(str(tmp_path))
    with pytest.raises(ValueError, match="MDM_DEVICE_IDENTITY_MODE"):
        server.create_app()


def test_strict_mode_does_not_reclassify_guid_shaped_legacy_record(tmp_path, monkeypatch):
    monkeypatch.setenv("MDM_DEVICE_IDENTITY_MODE", "cutover-strict")
    server._apply_data_dir(str(tmp_path))
    server.REGISTRY_PATH.write_text(json.dumps({
        "devices": {GUID: {"model": "old client", "identity_kind": "legacy"}},
        "groups": {"old": [GUID]},
    }))

    original = server.REGISTRY_PATH.read_text()
    with pytest.raises(RuntimeError, match="complete the documented reset"):
        server.load_registry()

    assert server.device_registry == {}
    assert server.device_groups == {}
    assert server.REGISTRY_PATH.read_text() == original


def test_strict_mode_rejects_noncanonical_group_member_without_rewriting(tmp_path, monkeypatch):
    monkeypatch.setenv("MDM_DEVICE_IDENTITY_MODE", "cutover-strict")
    server._apply_data_dir(str(tmp_path))
    server.REGISTRY_PATH.write_text(json.dumps({
        "devices": {},
        "groups": {"old": ["SERIAL-1"]},
    }))

    original = server.REGISTRY_PATH.read_text()
    with pytest.raises(RuntimeError, match="invalid group membership"):
        server.load_registry()

    assert server.device_registry == {}
    assert server.device_groups == {}
    assert server.REGISTRY_PATH.read_text() == original


def test_strict_mode_preserves_canonical_group_member_not_yet_registered(tmp_path, monkeypatch):
    monkeypatch.setenv("MDM_DEVICE_IDENTITY_MODE", "cutover-strict")
    server._apply_data_dir(str(tmp_path))
    server.REGISTRY_PATH.write_text(json.dumps({
        "devices": {},
        "groups": {"reserved": [GUID]},
    }))

    server.load_registry()

    assert server.device_registry == {}
    assert server.device_groups == {"reserved": [GUID]}


def test_push_session_is_not_dispatchable_before_registration_acknowledgement():
    runtime = object.__new__(push_runtime.PushRuntime)
    ws = object()
    runtime.sessions = {GUID: SimpleNamespace(ws=ws)}
    runtime.registration_candidates = {}
    runtime.ready_sessions = set()
    runtime._legacy_owns_device = lambda device_id, owner: device_id == GUID and owner is ws

    assert runtime._dispatch_sessions() == {}
    runtime.ready_sessions.add(GUID)
    assert list(runtime._dispatch_sessions()) == [GUID]
