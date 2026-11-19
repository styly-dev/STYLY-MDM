"""Recording dispatch, correlation, stale sockets, and failure boundaries."""
import asyncio
import json

import pytest
import pytest_asyncio

from styly_mdm import recording_control as recording


class Socket:
    def __init__(self):
        self.sent = []
        self.fail = False

    async def send_str(self, value):
        if self.fail:
            raise ConnectionResetError()
        self.sent.append(json.loads(value))


@pytest_asyncio.fixture
async def setup_control():
    control = recording.RecordingRequests()
    admin, device = Socket(), Socket()
    entry = {"ws": device, "registration_ready": True, "identity_kind": "canonical",
             "status": "online", "capabilities": [recording.CAPABILITY]}
    devices = {"headset": entry}
    yield control, admin, device, devices
    lifecycle = control.lifecycle(None)
    await anext(lifecycle)
    with pytest.raises(StopAsyncIteration):
        await anext(lifecycle)


def request(**changes):
    return {"request_id": "a" * 32, "action": "start", "target_devices": ["headset"], **changes}


def result(**changes):
    return {"request_id": "a" * 32, "action": "start", "status": "success", "state": "recording", "changed": True, **changes}


@pytest.mark.asyncio
async def test_dispatch_and_result_are_correlated(setup_control):
    c, admin, device, devices = setup_control
    await c.dispatch(admin, request(), devices)
    assert device.sent == [{"type": "EXECUTE_RECORDING_CONTROL", "request_id": "a" * 32, "action": "start"}]
    assert admin.sent == []
    await c.consume(device, "headset", result(device_id="spoof", detail="Done"), devices)
    assert admin.sent[0]["device_id"] == "headset"
    assert admin.sent[0]["state"] == "recording"
    assert c.pending == {}
    await c.dispatch(admin, request(), devices)
    assert len(device.sent) == 1
    assert admin.sent[-1]["status"] == "fail"


@pytest.mark.parametrize("changes", [
    {"request_id": 42}, {"request_id": "bad"}, {"action": "toggle"}, {"action": {}},
    {"target_devices": "headset"}, {"target_devices": []}, {"target_devices": ["*"]},
    {"target_devices": ["headset", "other"]}, {"target_devices": [{}]}, {"target_connections": ["a"]},
])
@pytest.mark.asyncio
async def test_malformed_requests_never_dispatch(setup_control, changes):
    c, admin, device, devices = setup_control
    await c.dispatch(admin, request(**changes), devices)
    assert not device.sent
    assert admin.sent[-1]["status"] == "fail"


@pytest.mark.parametrize("changes", [
    {"status": "offline"}, {"identity_kind": "provisional"}, {"identity_kind": "legacy"},
    {"registration_ready": False}, {"capabilities": []},
])
@pytest.mark.asyncio
async def test_only_online_capable_canonical_clients_dispatch(setup_control, changes):
    c, admin, device, devices = setup_control
    devices["headset"].update(changes)
    await c.dispatch(admin, request(), devices)
    assert not device.sent
    assert admin.sent[-1]["status"] == "fail"


@pytest.mark.asyncio
async def test_concurrent_requests_and_stale_results(setup_control):
    c, admin, device, devices = setup_control
    await c.dispatch(admin, request(), devices)
    second_admin = Socket()
    await c.dispatch(second_admin, request(request_id="b" * 32), devices)
    assert second_admin.sent[0]["status"] == "fail"
    assert len(device.sent) == 1
    await c.consume(device, "headset", result(request_id="b" * 32), devices)
    await c.consume(device, "headset", result(action="stop"), devices)
    await c.consume(device, "headset", result(state="stopped"), devices)
    await c.consume(device, "headset", result(status="accepted"), devices)
    replacement = Socket()
    await c.consume(replacement, "headset", result(), devices)
    devices["headset"]["ws"] = replacement
    await c.consume(device, "headset", result(), devices)
    assert admin.sent == []
    await c.disconnected(device)
    assert admin.sent[-1]["status"] == "fail"
    assert c.pending == {}


@pytest.mark.asyncio
async def test_dispatch_failure_does_not_retry(setup_control):
    c, admin, device, devices = setup_control
    device.fail = True
    await c.dispatch(admin, request(), devices)
    assert admin.sent[0]["status"] == "fail"
    assert c.pending == {}


@pytest.mark.asyncio
async def test_timeout_reports_unknown_without_retry(setup_control, monkeypatch):
    c, admin, device, devices = setup_control
    monkeypatch.setattr(recording, "TIMEOUT_SECONDS", 0.001)
    await c.dispatch(admin, request(), devices)
    await asyncio.sleep(0.01)
    assert admin.sent[0]["status"] == "fail"
    assert admin.sent[0]["state"] == "unknown"
    assert len(device.sent) == 1
    await c.consume(device, "headset", result(), devices)
    assert len(admin.sent) == 1


@pytest.mark.asyncio
async def test_real_websocket_registration_and_capability_refresh(tmp_path):
    import aiohttp
    from aiohttp.test_utils import TestServer
    from styly_mdm import server

    collections = (server.devices, server.device_registry, server.device_groups,
                   server.provisional_connections, server.admin_connections)
    for collection in collections:
        collection.clear()
    server._apply_data_dir(str(tmp_path))
    app = TestServer(server.create_app())
    await app.start_server()
    device_id = "64b19041-0b8c-4ef4-82fd-000000000000"

    async def receive(socket, expected):
        while True:
            data = await asyncio.wait_for(socket.receive_json(), 3)
            if data.get("type") == expected:
                return data

    try:
        async with aiohttp.ClientSession() as session:
            base = f"http://{app.host}:{app.port}"
            device = await session.ws_connect(base + "/ws/device")
            registration = {"type": "REGISTER", "identity_scheme": server.IDENTITY_SCHEME,
                            "device_id": device_id, "model": "PICO", "capabilities": [recording.CAPABILITY]}
            await device.send_json(registration)
            await receive(device, "REGISTERED")
            admin = await session.ws_connect(base + "/ws/admin")
            listed = await receive(admin, "DEVICE_LIST")
            while listed["devices"][0]["status"] == "registering":
                listed = await receive(admin, "DEVICE_LIST")
            assert listed["devices"][0]["recording_control_supported"] is True, server.devices
            await admin.send_json({"type": "RECORDING_CONTROL", **request(target_devices=[device_id])})
            command = await receive(device, "EXECUTE_RECORDING_CONTROL")
            await device.send_json({"type": "RECORDING_RESULT", **result(request_id=command["request_id"])})
            reply = await receive(admin, "RECORDING_RESULT")
            assert reply["device_id"] == device_id
            assert reply["status"] == "success"
            await device.send_json({**registration, "capabilities": []})
            await receive(device, "REGISTERED")
            while (await receive(admin, "DEVICE_LIST"))["devices"][0]["recording_control_supported"]:
                pass
            await admin.send_json({"type": "RECORDING_CONTROL", **request(request_id="b" * 32, target_devices=[device_id])})
            assert (await receive(admin, "RECORDING_RESULT"))["status"] == "fail"
    finally:
        await app.close()
        for collection in collections:
            collection.clear()
