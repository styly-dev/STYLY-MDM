from __future__ import annotations

import asyncio
import json
from types import SimpleNamespace

import aiohttp
import pytest
from aiohttp.test_utils import TestServer

from styly_mdm import push_runtime, server
from styly_mdm.device_policy import CommandNotAllowedError


GUID = "64b19041-0b8c-4ef4-82fd-000000000000"


@pytest.fixture(autouse=True)
def reset_state(monkeypatch):
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
        "capabilities": ["provisional_power_control_v1"],
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


@pytest.mark.parametrize("device_id", [
    "64b19041-0b8c-4ef4-82fd-00000000000g",
    "64b19041-0b8c-4ef4-82fd-00000000000",
    "64b19041-0b8c-4ef4-82fd-0000000000000",
    "64b190410b8c4ef482fd000000000000",
    GUID.upper(),
    GUID + "\n",
])
def test_malformed_canonical_guid_is_rejected(device_id):
    kind, error = server._parse_registration(canonical(device_id), None)
    assert kind is None
    assert error == "device_id must be a canonical lowercase GUID"


@pytest.mark.parametrize("device_id", [
    GUID,
    "00000000-0000-0000-0000-000000000000",
    "ffffffff-ffff-ffff-ffff-ffffffffffff",
    "64b19041-0b8c-7ef4-82fd-000000000000",
    "64b19041-0b8c-4ef4-02fd-000000000000",
])
def test_provisional_registration_promotes_without_persistence(tmp_path, device_id):
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
                connection_id = snapshot["connections"][0]["connection_id"]
                assert connection_id
                assert snapshot["connections"][0]["power_control_supported"] is True
                assert server.device_registry == {}

                await device.send_json(canonical(device_id))
                await recv_type(device, "REGISTERED")
                snapshot = await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")
                assert snapshot["connections"] == []
                listed = await recv_type(admin, "DEVICE_LIST")
                assert [row["device_id"] for row in listed["devices"]] == [device_id]
                assert device_id in server.device_registry
                assert not server.provisional_connections

                # A queued provisional result cannot cross the promotion boundary
                # and be reclassified as a canonical result.
                await device.send_json({
                    "type": "REBOOT_RESULT",
                    "status": "accepted",
                    "connection_id": connection_id,
                })
                with pytest.raises(asyncio.TimeoutError):
                    await asyncio.wait_for(admin.receive(), 0.1)

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
                first_id = first["connections"][0]["connection_id"]
                assert first_id

                await device.send_json(provisional(status="io_error", diagnostic="second"))
                await recv_type(device, "REGISTERED_PROVISIONAL")
                second = await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")
                assert len(second["connections"]) == 1
                assert second["connections"][0]["identity_status"] == "io_error"
                assert second["connections"][0]["diagnostic"] == "second"
                assert second["connections"][0]["connected_at"] == first["connections"][0]["connected_at"]
                assert second["connections"][0]["connection_id"] == first_id

                await device.close()
                disconnected = await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")
                assert disconnected["connections"] == []

                replacement = await session.ws_connect(base + "/ws/device")
                await replacement.send_json(provisional(status="resolving"))
                replacement_ack = await recv_type(replacement, "REGISTERED_PROVISIONAL")
                replacement_list = await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")
                assert replacement_ack["connection_id"] == replacement_list["connections"][0]["connection_id"]
                assert replacement_ack["connection_id"] != first_id
                await replacement.close()
                await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")
                await admin.close()
        finally:
            await test_server.close()

    asyncio.run(body())


def test_provisional_power_targets_are_strict_and_results_keep_connection_id(tmp_path):
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
                await device.send_json(provisional())
                provisional_ack = await recv_type(device, "REGISTERED_PROVISIONAL")
                connection_id = provisional_ack["connection_id"]
                await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")
                device_server_ws = next(iter(server.provisional_connections))
                with pytest.raises(CommandNotAllowedError):
                    await device_server_ws.send_str(json.dumps({
                        "type": "PUSH_RESULT_ACK",
                        "connection_id": connection_id,
                    }))

                await admin.send_json({
                    "type": "REBOOT_DEVICE",
                    "target_connections": [connection_id],
                })
                sent = await recv_type(admin, "REBOOT_SENT")
                assert sent["sent_count"] == 1
                assert sent["target_connections"] == [connection_id]
                command = await recv_type(device, "EXECUTE_REBOOT")
                assert command["connection_id"] == connection_id

                await device.send_json({
                    "type": "REBOOT_RESULT",
                    "status": "accepted",
                    "connection_id": connection_id,
                    "device_id": "must-not-cross-boundary",
                })
                result = await recv_type(admin, "REBOOT_RESULT")
                assert result["connection_id"] == connection_id
                assert "device_id" not in result

                # The dedicated field cannot be combined with the normal target
                # field, and an empty list never means "all devices".
                for payload, message in [
                    ({"target_connections": []}, "non-empty"),
                    ({"target_connections": ["*"]}, "wildcards"),
                    ({"target_connections": [connection_id], "target_devices": ["*"]}, "combined"),
                ]:
                    await admin.send_json({"type": "POWER_OFF_DEVICE", **payload})
                    error = await recv_type(admin, "ERROR")
                    assert message in error["message"]

                # A stale connection ID is never resolved through the ordinary
                # target_devices fallback and does not reach the device.
                await device.close()
                await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")
                await admin.send_json({
                    "type": "REBOOT_DEVICE",
                    "target_connections": [connection_id],
                })
                error = await recv_type(admin, "ERROR")
                assert "provisional" in error["message"]

                await admin.send_json({
                    "type": "LAUNCH_APP",
                    "target_connections": [connection_id],
                    "package_name": "example.app",
                })
                error = await recv_type(admin, "ERROR")
                assert "only supported for power control" in error["message"]
                await admin.close()
        finally:
            await test_server.close()

    asyncio.run(body())


def test_old_provisional_client_is_visible_but_cannot_receive_power_command(tmp_path):
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
                old_payload = provisional()
                old_payload.pop("capabilities")
                await device.send_json(old_payload)
                await recv_type(device, "REGISTERED_PROVISIONAL")
                snapshot = await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")
                connection = snapshot["connections"][0]
                assert connection["power_control_supported"] is False

                await admin.send_json({
                    "type": "POWER_OFF_DEVICE",
                    "target_connections": [connection["connection_id"]],
                })
                error = await recv_type(admin, "ERROR")
                assert "eligible provisional" in error["message"]
                with pytest.raises(asyncio.TimeoutError):
                    await asyncio.wait_for(device.receive(), 0.1)
                await device.send_json({
                    "type": "REBOOT_RESULT",
                    "status": "accepted",
                    "connection_id": connection["connection_id"],
                })
                with pytest.raises(asyncio.TimeoutError):
                    await asyncio.wait_for(admin.receive(), 0.1)
                await device.close()
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






def test_push_session_is_not_dispatchable_before_registration_acknowledgement():
    runtime = object.__new__(push_runtime.PushRuntime)
    ws = object()
    runtime.sessions = {GUID: SimpleNamespace(ws=ws)}
    runtime.registration_candidates = {}
    runtime.legacy = SimpleNamespace(devices={GUID: {"ws": ws, "identity_kind": "canonical", "registration_ready": False}})
    runtime._legacy_owns_device = lambda device_id, owner: device_id == GUID and owner is ws

    assert runtime._dispatch_sessions() == {}
    runtime.legacy.devices[GUID]["registration_ready"] = True
    assert list(runtime._dispatch_sessions()) == [GUID]


@pytest.mark.parametrize("identity_kind", ["canonical", "legacy"])
def test_registration_wait_does_not_dispatch_commands(tmp_path, identity_kind):
    async def body():
        server._apply_data_dir(str(tmp_path))
        app = server.create_app()
        runtime = app["push_runtime"]
        async with TestServer(app) as ts, aiohttp.ClientSession() as client:
            admin = await client.ws_connect(ts.make_url("/ws/admin"))
            await recv_type(admin, "GROUP_LIST")
            device = await client.ws_connect(ts.make_url("/ws/device"))
            lock = runtime._device_lock(GUID)
            await lock.acquire()
            try:
                payload = canonical()
                if identity_kind == "legacy":
                    payload.pop("identity_scheme")
                await device.send_json(payload)
                async def await_owner():
                    while GUID not in server.devices:
                        await asyncio.sleep(0)
                await asyncio.wait_for(await_owner(), 2)
                row = json.loads(server.build_device_list_msg())["devices"][0]
                assert row["status"] == "registering"
                assert row["identity_kind"] == identity_kind
                assert server.resolve_target_ids([GUID], allow_legacy=True) == []
                await admin.send_json({"type": "INSTALL_APK", "target_devices": [GUID],
                                       "apk_url": "http://example.invalid/test.apk"})
                assert "No matching" in (await recv_type(admin, "ERROR"))["message"]
            finally:
                lock.release()
            # No command may precede the acknowledgement.
            assert (await device.receive_json())["type"] == "REGISTERED"
            await device.send_json(payload)
            await recv_type(device, "REGISTERED")
            assert server.resolve_target_ids([GUID], allow_legacy=True) == [GUID]
            assert server.resolve_target_ids([GUID]) == ([GUID] if identity_kind == "canonical" else [])
            await device.close()
            await admin.close()
    asyncio.run(body())


def test_admin_label_and_group_survive_reload_without_mode(tmp_path, monkeypatch):
    async def body():
        # A stale deployment environment must no longer change registration policy.
        monkeypatch.setenv("MDM_DEVICE_IDENTITY_MODE", "cutover-strict")
        server._apply_data_dir(str(tmp_path))
        app = server.create_app()
        async with TestServer(app) as ts, aiohttp.ClientSession() as client:
            admin = await client.ws_connect(ts.make_url("/ws/admin"))
            await recv_type(admin, "GROUP_LIST")
            await admin.send_json({"type": "SET_DEVICE_LABEL", "device_id": GUID, "label": "Reserved"})
            await recv_type(admin, "DEVICE_LABEL_SET")
            await admin.send_json({"type": "CREATE_GROUP", "name": "Old"})
            await recv_type(admin, "GROUP_CREATED")
            await admin.send_json({"type": "SET_GROUP_MEMBERS", "name": "Old", "members": ["SERIAL-1", GUID]})
            await recv_type(admin, "GROUP_MEMBERS_SET")
            server.load_registry()
            assert server.device_registry[GUID]["label"] == "Reserved"
            assert server.device_groups["Old"] == ["SERIAL-1", GUID]
            device = await client.ws_connect(ts.make_url("/ws/device"))
            await device.send_json({"type": "REGISTER", "device_id": "SERIAL-1"})
            await recv_type(device, "REGISTERED")
            assert server.device_registry["SERIAL-1"]["identity_kind"] == "legacy"
            await device.close()
            await admin.close()
    asyncio.run(body())


def test_legacy_commands_are_blocked_but_apk_install_is_sent(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        app = server.create_app()
        runtime = app["push_runtime"]
        async with TestServer(app) as ts, aiohttp.ClientSession() as client:
            admin = await client.ws_connect(ts.make_url("/ws/admin"))
            await recv_type(admin, "GROUP_LIST")
            device = await client.ws_connect(ts.make_url("/ws/device"))
            await device.send_json({"type": "REGISTER", "device_id": "SERIAL-1",
                                    "capabilities": ["push_job_id_v1"],
                                    "process_instance_id": "64b19041-0b8c-4ef4-82fd-000000000001"})
            await recv_type(device, "REGISTERED")
            assert "SERIAL-1" not in runtime._dispatch_sessions()
            response = await client.post(ts.make_url("/api/push-jobs"), json={
                "client_request_id": "64b19041-0b8c-4ef4-82fd-000000000002",
                "target_devices": ["SERIAL-1"], "mode": "push",
                "dest_path": "/sdcard/STYLY/content",
                "source": {"display_name": "content", "declared_file_count": 1,
                           "declared_total_bytes": 1},
            })
            assert response.status == 422
            assert "legacy device" in (await response.json())["error"]
            owner = server.devices["SERIAL-1"]["ws"]
            for command in ["EXECUTE_LAUNCH", "EXECUTE_REBOOT", "EXECUTE_POWER_OFF",
                            "EXECUTE_UNINSTALL", "EXECUTE_PUSH_FILES", "EXECUTE_VERIFY_APK",
                            "EXECUTE_VERIFY_DIR", "SET_STARTUP_APP", "CLEAR_STARTUP_APP", "PUSH_RECONCILE_REQUEST"]:
                with pytest.raises(ConnectionResetError):
                    await owner.send_str(json.dumps({"type": command}))
            await admin.send_json({"type": "LAUNCH_APP", "target_devices": ["SERIAL-1"],
                                   "package_name": "example.app"})
            await recv_type(admin, "ERROR")
            await admin.send_json({"type": "INSTALL_APK", "target_devices": ["SERIAL-1"],
                                   "apk_url": "http://example.invalid/update.apk"})
            sent = await recv_type(admin, "INSTALL_SENT")
            assert sent["target_count"] == 1
            assert (await device.receive_json())["type"] == "EXECUTE_INSTALL"
            await device.send_json({"type": "INSTALL_RESULT", "status": "success"})
            await recv_type(admin, "INSTALL_RESULT")
            await device.close()
            await admin.close()
    asyncio.run(body())


def test_mixed_selection_only_includes_legacy_for_install():
    server.devices.update({
        "new": {"identity_kind": "canonical", "registration_ready": True},
        "old": {"identity_kind": "legacy", "registration_ready": True},
        "waiting": {"identity_kind": "canonical", "registration_ready": False},
    })
    assert server.resolve_target_ids(["*"]) == ["new"]
    assert server.resolve_target_ids(["*"], allow_legacy=True) == ["new", "old"]



def test_replacement_does_not_reuse_previous_owners_ready_flag(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        app = server.create_app()
        runtime = app["push_runtime"]
        async with TestServer(app) as ts, aiohttp.ClientSession() as client:
            old = await client.ws_connect(ts.make_url("/ws/device"))
            await old.send_json(canonical())
            await recv_type(old, "REGISTERED")
            new = await client.ws_connect(ts.make_url("/ws/device"))
            old_owner = server.devices[GUID]["ws"]
            async with runtime._device_lock(GUID):
                await new.send_json(canonical())
                async def await_replacement():
                    while server.devices[GUID]["ws"] is old_owner:
                        await asyncio.sleep(0)
                await asyncio.wait_for(await_replacement(), 2)
                assert server.devices[GUID]["registration_ready"] is False
                new_owner = server.devices[GUID]["ws"]
                for command in ["EXECUTE_INSTALL", "SET_STARTUP_APP", "CLEAR_STARTUP_APP"]:
                    with pytest.raises(ConnectionResetError):
                        await new_owner.send_str(json.dumps({"type": command}))
            assert (await new.receive_json())["type"] == "REGISTERED"
            await old.close()
            await new.close()
    asyncio.run(body())


@pytest.mark.parametrize("entry", [None, {}, {"identity_kind": "canonical"},
    {"registration_ready": True}, {"registration_ready": True, "identity_kind": "unknown"}])
def test_incomplete_identity_never_grants_command_access(entry):
    from styly_mdm.device_policy import command_allowed
    assert not command_allowed(entry)
    assert not command_allowed(entry, "EXECUTE_INSTALL")


def test_provisional_policy_allows_only_capability_gated_power_commands():
    from styly_mdm.device_policy import command_allowed

    entry = {
        "identity_kind": "provisional",
        "registration_ready": True,
        "capabilities": ["provisional_power_control_v1"],
    }
    assert command_allowed(entry, "EXECUTE_REBOOT")
    assert command_allowed(entry, "EXECUTE_POWER_OFF")
    assert not command_allowed(entry, "EXECUTE_LAUNCH")
    assert not command_allowed(entry, "EXECUTE_INSTALL")
    entry["capabilities"] = []
    assert not command_allowed(entry, "EXECUTE_REBOOT")


@pytest.mark.parametrize("kind", ["canonical", "legacy"])
@pytest.mark.parametrize("ready,current", [(True, True), (False, True), (True, False)])
def test_registration_ack_requires_current_ready_owner(kind, ready, current):
    async def body():
        sent = []
        class Socket:
            async def send_str(self, message):
                sent.append(json.loads(message))
        ws = Socket()
        runtime = object.__new__(push_runtime.PushRuntime)
        runtime.sessions = {GUID: SimpleNamespace(ws=ws, session_id="session")}
        runtime.legacy = SimpleNamespace(devices={GUID: {
            "ws": ws if current else object(),
            "identity_kind": kind,
            "registration_ready": ready,
        }})
        runtime.send_timeout = 1
        await runtime.acknowledge_registration(ws, GUID)
        assert sent == ([{"type": "REGISTERED", "session_id": "session"}]
                        if ready and current else [])
    asyncio.run(body())


def test_offline_legacy_push_reports_apk_only_policy(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        app = server.create_app()
        async with TestServer(app) as ts, aiohttp.ClientSession() as client:
            server.device_registry["SERIAL-OFFLINE"] = {"identity_kind": "legacy"}
            response = await client.post(ts.make_url("/api/push-jobs"), json={
                "client_request_id": "64b19041-0b8c-4ef4-82fd-000000000003",
                "target_devices": ["SERIAL-OFFLINE"], "mode": "push",
                "dest_path": "/sdcard/STYLY/content",
                "source": {"display_name": "content", "declared_file_count": 1,
                           "declared_total_bytes": 1},
            })
            assert response.status == 422
            assert "legacy device only supports APK" in (await response.json())["error"]
    asyncio.run(body())


@pytest.mark.parametrize("failure", [ConnectionResetError("peer closed"), ValueError("unexpected")])
def test_protocol_error_closes_socket_without_hiding_unexpected_errors(failure):
    async def body():
        closed = []
        class Socket:
            async def send_str(self, message):
                raise failure
            async def close(self, **kwargs):
                closed.append(kwargs)
        if isinstance(failure, ConnectionResetError):
            await server._close_protocol_error(Socket(), "invalid registration")
        else:
            with pytest.raises(ValueError, match="unexpected"):
                await server._close_protocol_error(Socket(), "invalid registration")
        assert len(closed) == 1
        assert closed[0]["code"] == server.WSCloseCode.PROTOCOL_ERROR
    asyncio.run(body())


def test_final_dispatch_policy_denial_has_distinct_exception():
    from styly_mdm.device_policy import CommandNotAllowedError

    async def body():
        ws = push_runtime.RuntimeWebSocketResponse()
        ws._push_path = "/ws/device"
        ws._push_device_id = GUID
        ws._push_runtime = SimpleNamespace(
            legacy=SimpleNamespace(devices={GUID: {
                "ws": ws, "identity_kind": "canonical", "registration_ready": False,
            }}, provisional_connections={}),
            sessions={GUID: SimpleNamespace(ws=ws)},
        )
        with pytest.raises(CommandNotAllowedError, match="not ready"):
            await ws.send_str(json.dumps({"type": "EXECUTE_REBOOT"}))

    asyncio.run(body())


@pytest.mark.parametrize("repeated", [False, True])
def test_provisional_ack_wait_preserves_only_existing_readiness(tmp_path, monkeypatch, repeated):
    async def body():
        server._apply_data_dir(str(tmp_path))
        entered = asyncio.Event()
        release = asyncio.Event()
        original = push_runtime.RuntimeWebSocketResponse.send_str
        ack_count = 0

        async def delayed_ack(ws, data, compress=None):
            nonlocal ack_count
            if json.loads(data).get("type") == "REGISTERED_PROVISIONAL":
                ack_count += 1
                if ack_count == (2 if repeated else 1):
                    entered.set()
                    await release.wait()
            return await original(ws, data, compress=compress)

        monkeypatch.setattr(push_runtime.RuntimeWebSocketResponse, "send_str", delayed_ack)
        test_server = TestServer(server.create_app())
        await test_server.start_server()
        try:
            async with aiohttp.ClientSession() as session:
                admin = await session.ws_connect(test_server.make_url("/ws/admin"))
                await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")
                device = await session.ws_connect(test_server.make_url("/ws/device"))
                await device.send_json(provisional(status="resolving"))
                if repeated:
                    ack = await recv_type(device, "REGISTERED_PROVISIONAL")
                    await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")
                    await device.send_json(provisional(status="io_error"))
                await asyncio.wait_for(entered.wait(), 2)
                entry = next(iter(server.provisional_connections.values()))
                connection_id = entry["connection_id"]
                assert entry["registration_ready"] is repeated
                if repeated:
                    assert connection_id == ack["connection_id"]
                await admin.send_json({
                    "type": "REBOOT_DEVICE", "target_connections": [connection_id],
                })
                if repeated:
                    sent = await recv_type(admin, "REBOOT_SENT")
                    assert sent["sent_count"] == 1
                    command = await recv_type(device, "EXECUTE_REBOOT")
                    assert command["connection_id"] == connection_id
                else:
                    error = await recv_type(admin, "ERROR")
                    assert "eligible provisional" in error["message"]
                release.set()
                await recv_type(device, "REGISTERED_PROVISIONAL")
                await recv_type(admin, "PROVISIONAL_CONNECTION_LIST")
                await device.close()
                await admin.close()
        finally:
            release.set()
            await test_server.close()

    asyncio.run(body())
