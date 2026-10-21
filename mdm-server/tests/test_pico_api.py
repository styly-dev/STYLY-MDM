from __future__ import annotations

import asyncio
import json
from types import SimpleNamespace

import aiohttp
import pytest
from aiohttp.test_utils import TestServer

from styly_mdm import push_runtime, server
from styly_mdm.device_policy import PICO_STARTUP_CAPABILITY


GUID = "64b19041-0b8c-4ef4-82fd-000000000000"


class FakeWS:
    def __init__(self):
        self.sent: list[str] = []

    async def send_str(self, message: str) -> None:
        self.sent.append(message)


class DisconnectedWS(FakeWS):
    async def send_str(self, message: str) -> None:
        raise ConnectionResetError("peer closed")


@pytest.fixture(autouse=True)
def reset_server_state():
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


def add_device(
    device_id: str,
    ws: FakeWS | None = None,
    *,
    supported: bool = True,
    identity_kind: str = "canonical",
    ready: bool = True,
) -> FakeWS:
    ws = ws or FakeWS()
    capabilities = [PICO_STARTUP_CAPABILITY] if supported else []
    server.devices[device_id] = {
        "ws": ws,
        "device_id": device_id,
        "identity_kind": identity_kind,
        "registration_ready": ready,
        "capabilities": capabilities,
        "model": "PICO 4 Enterprise",
        "ip": "192.168.1.20",
        "status": "online",
        "startup_app": {"package_name": "com.mdm.launcher", "extra": ""},
        "battery": None,
        "version_code": 10,
        "version_name": "0.6.0",
    }
    server.device_registry[device_id] = {
        "label": "Test device",
        "model": "PICO 4 Enterprise",
        "ip": "192.168.1.20",
        "startup_app": {"package_name": "com.mdm.launcher", "extra": ""},
    }
    return ws


def frames_of(ws: FakeWS, message_type: str) -> list[dict]:
    return [
        payload
        for payload in (json.loads(message) for message in ws.sent)
        if payload.get("type") == message_type
    ]


def valid_command(**changes) -> dict:
    return {
        "type": "TRIGGER_PICO_API",
        "api": "setLauncher",
        "request_id": "startup_1",
        "target_devices": [GUID],
        "package_name": "com.example.app",
        **changes,
    }


def test_dispatch_preserves_explicit_package_and_null_without_changing_mdm_record():
    async def body():
        admin = FakeWS()
        device = add_device(GUID)
        previous_device_record = dict(server.devices[GUID]["startup_app"])
        previous_registry_record = dict(server.device_registry[GUID]["startup_app"])

        for package_name in ("com.example.app", None):
            await server.handle_pico_api(admin, valid_command(package_name=package_name))

        commands = frames_of(device, "TRIGGER_PICO_API")
        assert commands == [
            {
                "type": "TRIGGER_PICO_API",
                "api": "setLauncher",
                "request_id": "startup_1",
                "package_name": "com.example.app",
            },
            {
                "type": "TRIGGER_PICO_API",
                "api": "setLauncher",
                "request_id": "startup_1",
                "package_name": None,
            },
        ]
        assert frames_of(admin, "PICO_API_SENT") == [
            {"type": "PICO_API_SENT", "request_id": "startup_1", "sent_count": 1, "target_count": 1},
            {"type": "PICO_API_SENT", "request_id": "startup_1", "sent_count": 1, "target_count": 1},
        ]
        assert server.devices[GUID]["startup_app"] == previous_device_record
        assert server.device_registry[GUID]["startup_app"] == previous_registry_record

    asyncio.run(body())


@pytest.mark.parametrize(
    "changes",
    [
        {"api": "openTimingStartup"},
        {"request_id": "x" * 81},
        {"request_id": "not valid"},
        {"request_id": None},
        {"target_devices": None},
        {"target_devices": []},
        {"target_devices": [""]},
        {"target_devices": [GUID, GUID]},
        {"target_devices": ["*", GUID]},
        {"target_devices": [1]},
        {"package_name": ""},
        {"package_name": "com.example.bad-name"},
        {"package_name": 7},
        {"package_name": False},
        {"package_name": "x" * 256},
        {"package_name": "com.example.app", "omit_package_name": True},
    ],
)
def test_invalid_request_shape_is_rejected_without_dispatch_or_implicit_all(changes):
    async def body():
        admin = FakeWS()
        device = add_device(GUID)
        command = valid_command()
        omit_package_name = changes.pop("omit_package_name", False)
        command.update(changes)
        if omit_package_name:
            command.pop("package_name")

        await server.handle_pico_api(admin, command)

        assert frames_of(admin, "ERROR")
        assert not frames_of(admin, "PICO_API_SENT")
        assert not frames_of(device, "TRIGGER_PICO_API")

    asyncio.run(body())


def test_only_ready_canonical_capable_devices_are_dispatched_and_socket_loss_is_not_counted():
    async def body():
        admin = FakeWS()
        capable = add_device("capable")
        old_client = add_device("old-client", supported=False)
        provisional = add_device("provisional", identity_kind="provisional")
        legacy = add_device("legacy", identity_kind="legacy")
        registering = add_device("registering", ready=False)
        disconnected = add_device("disconnected", ws=DisconnectedWS())

        await server.handle_pico_api(
            admin,
            valid_command(target_devices=[
                "capable", "old-client", "provisional", "legacy", "registering", "disconnected",
            ]),
        )

        assert len(frames_of(capable, "TRIGGER_PICO_API")) == 1
        for ws in (old_client, provisional, legacy, registering):
            assert not frames_of(ws, "TRIGGER_PICO_API")
        assert frames_of(admin, "PICO_API_SENT") == [{
            "type": "PICO_API_SENT",
            "request_id": "startup_1",
            "sent_count": 1,
            "target_count": 6,
        }]
        assert not frames_of(admin, "ERROR")
        assert server.device_registry["capable"]["startup_app"]["package_name"] == "com.mdm.launcher"

    asyncio.run(body())


def test_no_supported_targets_returns_error_and_does_not_mutate_registry():
    async def body():
        admin = FakeWS()
        old_client = add_device("old-client", supported=False)

        await server.handle_pico_api(admin, valid_command(target_devices=["old-client"]))

        assert not frames_of(old_client, "TRIGGER_PICO_API")
        error = frames_of(admin, "ERROR")
        assert len(error) == 1
        assert error[0]["request_id"] == "startup_1"
        assert "update the client" in error[0]["message"]
        assert not frames_of(admin, "PICO_API_SENT")
        assert server.devices["old-client"]["startup_app"]["package_name"] == "com.mdm.launcher"
        assert server.device_registry["old-client"]["startup_app"]["package_name"] == "com.mdm.launcher"

    asyncio.run(body())


def test_explicit_wildcard_is_the_only_broad_target_form():
    async def body():
        admin = FakeWS()
        first = add_device("first")
        second = add_device("second")

        await server.handle_pico_api(admin, valid_command(target_devices=["*"]))

        assert len(frames_of(first, "TRIGGER_PICO_API")) == 1
        assert len(frames_of(second, "TRIGGER_PICO_API")) == 1
        assert frames_of(admin, "PICO_API_SENT")[0]["sent_count"] == 2
        assert frames_of(admin, "PICO_API_SENT")[0]["target_count"] == 2

    asyncio.run(body())


def test_final_socket_guard_rechecks_capability_after_target_selection():
    async def body():
        admin = FakeWS()
        ws = push_runtime.RuntimeWebSocketResponse()
        add_device(GUID, ws)
        runtime = SimpleNamespace(legacy=server, sessions={GUID: SimpleNamespace(ws=ws)})
        ws._push_path = "/ws/device"
        ws._push_device_id = GUID
        ws._push_runtime = runtime
        original_send = ws.send_str

        async def revoke_capability_then_send(message: str, compress=None):
            server.devices[GUID]["capabilities"] = []
            await original_send(message, compress=compress)

        ws.send_str = revoke_capability_then_send
        await server.handle_pico_api(admin, valid_command())

        assert not frames_of(admin, "PICO_API_SENT")
        errors = frames_of(admin, "ERROR")
        assert len(errors) == 1
        assert "No selected online devices" in errors[0]["message"]

    asyncio.run(body())


async def recv_type(ws, expected: str, timeout: float = 2.0) -> dict:
    while True:
        message = await asyncio.wait_for(ws.receive(), timeout)
        assert message.type == aiohttp.WSMsgType.TEXT
        payload = json.loads(message.data)
        if payload.get("type") == expected:
            return payload


async def register_device(session, base: str, device_id: str, capabilities: list[str]):
    ws = await session.ws_connect(base + "/ws/device")
    await ws.send_json({
        "type": "REGISTER",
        "identity_scheme": server.IDENTITY_SCHEME,
        "device_id": device_id,
        "model": "PICO 4 Enterprise",
        "ip": "192.168.1.20",
        "version_code": 10,
        "version_name": "0.6.0",
        "capabilities": capabilities,
        "startup_app": None,
    })
    await recv_type(ws, "REGISTERED")
    return ws


def test_e2e_registration_ui_targeting_and_result_identity_stamping(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        test_server = TestServer(server.create_app())
        await test_server.start_server()
        base = f"http://{test_server.host}:{test_server.port}"
        try:
            async with aiohttp.ClientSession() as session:
                device = await register_device(
                    session,
                    base,
                    GUID,
                    [PICO_STARTUP_CAPABILITY],
                )
                admin = await session.ws_connect(base + "/ws/admin")
                initial_list = await recv_type(admin, "DEVICE_LIST")
                row = next(item for item in initial_list["devices"] if item["device_id"] == GUID)
                assert row["pico_startup_app_supported"] is True
                assert row["startup_app"] is None

                await admin.send_json(valid_command(package_name=None))
                sent = await recv_type(admin, "PICO_API_SENT")
                assert sent == {
                    "type": "PICO_API_SENT",
                    "request_id": "startup_1",
                    "sent_count": 1,
                    "target_count": 1,
                }
                command = await recv_type(device, "TRIGGER_PICO_API")
                assert command == {
                    "type": "TRIGGER_PICO_API",
                    "api": "setLauncher",
                    "request_id": "startup_1",
                    "package_name": None,
                }

                await device.send_json({
                    "type": "PICO_API_RESULT",
                    "api": "setLauncher",
                    "request_id": "startup_1",
                    "status": "accepted",
                    "result_code": 0,
                    "device_id": "forged-device-id",
                    "connection_id": "forged-connection-id",
                })
                result = await recv_type(admin, "PICO_API_RESULT")
                assert result["device_id"] == GUID
                assert result["status"] == "accepted"
                assert result["result_code"] == 0
                assert "connection_id" not in result
                assert server.devices[GUID]["startup_app"] is None
                assert server.device_registry[GUID]["startup_app"] is None

                await device.close()
                await admin.close()
        finally:
            await test_server.close()

    asyncio.run(body())


def test_result_from_registered_device_without_capability_is_suppressed(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        test_server = TestServer(server.create_app())
        await test_server.start_server()
        base = f"http://{test_server.host}:{test_server.port}"
        try:
            async with aiohttp.ClientSession() as session:
                device = await register_device(session, base, GUID, [])
                admin = await session.ws_connect(base + "/ws/admin")
                await recv_type(admin, "DEVICE_LIST")
                row = next(item for item in json.loads(server.build_device_list_msg())["devices"] if item["device_id"] == GUID)
                assert row["pico_startup_app_supported"] is False

                await device.send_json({
                    "type": "PICO_API_RESULT",
                    "request_id": "unsolicited",
                    "status": "accepted",
                })
                with pytest.raises(asyncio.TimeoutError):
                    await asyncio.wait_for(recv_type(admin, "PICO_API_RESULT", 0.1), 0.2)

                await device.close()
                await admin.close()
        finally:
            await test_server.close()

    asyncio.run(body())
