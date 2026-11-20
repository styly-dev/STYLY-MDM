"""Browser URL validation, dispatch, correlation, and fail-closed compatibility."""

import asyncio
import json

import pytest
import aiohttp
from aiohttp.test_utils import TestServer

from styly_mdm import server
from styly_mdm.browser_control import BrowserControl, validate_browser_url
from styly_mdm.device_policy import command_allowed
from ws_test_helpers import receive_type

REQUEST = "11111111-1111-4111-8111-111111111111"


class Socket:
    def __init__(self):
        self.sent = []
        self.failure = False

    async def send_str(self, message):
        if self.failure:
            raise ConnectionResetError()
        self.sent.append(json.loads(message))


def setup_control(timeout=18):
    device, admin = Socket(), Socket()
    devices = {"device": {"ws": device, "registration_ready": True,
        "identity_kind": "canonical", "status": "online", "capabilities": ["open_url_v1"]}}
    return BrowserControl(devices, command_allowed, timeout), device, admin


async def start_and_flush(control, admin, data):
    await control.start(admin, data)
    await asyncio.gather(*list(control.send_tasks))


def request(**changes):
    return {"request_id": REQUEST, "target_devices": ["device"],
            "url": "https://example.com/a?q=one%26two&token=secret#part", **changes}


@pytest.mark.parametrize("url", [
    "https://example.com/a?q=one%26two&token=secret#part", "http://192.168.1.20:8811/",
    "http://[::1]:8080/", "HTTP://example.com/", "https://例え.jp/hello",
])
def test_preserves_valid_url(url):
    assert validate_browser_url(url) == url


@pytest.mark.parametrize("url", [
    None, 7, [], "", "example.com", "file:///sdcard/file", "intent://example.com",
    "javascript:alert(1)", "https://user:pass@example.com", "https://@example.com",
    "https://example.com\\@evil.com", "https://example.com/\n?token=secret", " https://example.com",
    "https://example.com ", "https://example.com:0/", "https://example.com:65536/",
    "https://example.com:bad/", "https://", "https://example.com/\x7f", "https://example.com/\u00a0",
    "https://example.com/%xx", "https://example.com/" + "a" * 4096,
])
def test_rejects_invalid_url_without_echoing_it(url):
    with pytest.raises(ValueError) as error:
        validate_browser_url(url)
    assert "secret" not in str(error.value)


@pytest.mark.asyncio
async def test_dispatch_preserves_url_and_result_hides_diagnostics():
    control, device, admin = setup_control()
    await start_and_flush(control, admin, request())
    assert device.sent == [{"type": "EXECUTE_OPEN_URL", "request_id": REQUEST, "url": request()["url"]}]
    assert admin.sent[-1]["type"] == "OPEN_URL_SENT"
    await control.receive(device, "device", {"request_id": REQUEST, "code": "requested", "error": "secret URL"})
    assert admin.sent[-1]["status"] == "requested"
    assert "secret" not in json.dumps(admin.sent)
    assert not control.pending


@pytest.mark.parametrize("changes", [
    {"target_devices": []}, {"target_devices": ["*"]}, {"target_devices": "device"},
    {"target_devices": ["device", "*"]}, {"target_devices": [""]}, {"target_devices": [{}]},
    {"request_id": []}, {"request_id": "bad"}, {"target_connections": []}, {"url": "file:///x"},
])
@pytest.mark.asyncio
async def test_invalid_request_never_dispatches(changes):
    control, device, admin = setup_control()
    await start_and_flush(control, admin, request(**changes))
    assert not device.sent
    assert admin.sent[-1]["status"] == "fail"


@pytest.mark.parametrize("changes", [
    {"capabilities": []}, {"identity_kind": "legacy"}, {"identity_kind": "provisional"},
    {"registration_ready": False}, {"status": "offline"},
])
@pytest.mark.asyncio
async def test_ineligible_or_old_client_never_dispatches(changes):
    control, device, admin = setup_control()
    control.devices["device"].update(changes)
    await start_and_flush(control, admin, request())
    assert not device.sent
    assert admin.sent[-1]["status"] == "fail"


@pytest.mark.asyncio
async def test_busy_requests_and_wrong_or_stale_results_do_not_settle():
    control, device, admin = setup_control()
    await start_and_flush(control, admin, request())
    await start_and_flush(control, admin, request(request_id="22222222-2222-4222-8222-222222222222"))
    assert admin.sent[-1]["code"] == "busy"
    assert len(device.sent) == 1
    await control.receive(device, "device", {"request_id": "wrong", "code": "requested"})
    await control.receive(Socket(), "device", {"request_id": REQUEST, "code": "requested"})
    control.devices["device"]["ws"] = Socket()
    await control.receive(device, "device", {"request_id": REQUEST, "code": "requested"})
    assert "device" in control.pending
    await control.close()


@pytest.mark.asyncio
async def test_timeout_does_not_retry_and_late_result_is_ignored():
    control, device, admin = setup_control(timeout=0.01)
    await start_and_flush(control, admin, request())
    await asyncio.sleep(0.03)
    assert admin.sent[-1]["code"] == "timeout"
    assert len(device.sent) == 1
    count = len(admin.sent)
    await control.receive(device, "device", {"request_id": REQUEST, "code": "requested"})
    assert len(admin.sent) == count
    assert not control.pending


@pytest.mark.asyncio
async def test_dispatch_failure_and_invalid_result_never_claim_success():
    control, device, admin = setup_control()
    device.failure = True
    await start_and_flush(control, admin, request())
    assert admin.sent[-1]["code"] == "unavailable"
    assert not control.pending
    device.failure = False
    await start_and_flush(control, admin, request())
    await control.receive(device, "device", {"request_id": REQUEST, "code": ["requested"], "message": "secret"})
    assert admin.sent[-1]["code"] == "launch_failed"
    assert "secret" not in json.dumps(admin.sent)


@pytest.mark.asyncio
async def test_multi_target_dispatch_deduplicates_and_keeps_failures_independent():
    control, device, admin = setup_control()
    healthy = Socket()
    broken = Socket()
    broken.failure = True
    control.devices.update({
        "healthy": {**control.devices["device"], "ws": healthy},
        "unsupported": {**control.devices["device"], "ws": Socket(), "capabilities": []},
        "offline": {**control.devices["device"], "ws": Socket(), "status": "offline"},
        "broken": {**control.devices["device"], "ws": broken},
    })
    await start_and_flush(control, admin, request(target_devices=[
        "unsupported", "device", "device", "offline", "missing", "broken", "healthy",
    ]))
    assert len(device.sent) == len(healthy.sent) == 1
    assert device.sent[0] == healthy.sent[0]
    assert set(control.pending) == {"device", "healthy"}
    failures = {m["device_id"]: m["code"] for m in admin.sent if m["type"] == "OPEN_URL_RESULT"}
    assert failures == {"unsupported": "unsupported", "offline": "invalid_target",
                        "missing": "invalid_target", "broken": "unavailable"}
    await control.receive(healthy, "healthy", {"request_id": REQUEST, "code": "requested"})
    assert set(control.pending) == {"device"}
    await control.receive(device, "device", {"request_id": REQUEST, "code": "no_browser"})
    assert not control.pending
    assert [(m["device_id"], m["status"]) for m in admin.sent[-2:]] == [
        ("healthy", "requested"), ("device", "fail")]


@pytest.mark.asyncio
async def test_busy_headset_does_not_block_new_peer_request():
    control, device, admin = setup_control()
    healthy = Socket()
    control.devices["healthy"] = {**control.devices["device"], "ws": healthy}
    await start_and_flush(control, admin, request())
    other_id = "22222222-2222-4222-8222-222222222222"
    await start_and_flush(control, admin, request(request_id=other_id, target_devices=["device", "healthy"]))
    assert len(device.sent) == 1
    assert healthy.sent[0]["request_id"] == other_id
    assert any(m.get("code") == "busy" and m["device_id"] == "device"
               and m["request_id"] == other_id for m in admin.sent)
    assert control.pending["device"]["request_id"] == REQUEST
    await control.close()


@pytest.mark.asyncio
async def test_timeout_only_fails_unfinished_peers_and_does_not_resend():
    control, device, admin = setup_control(timeout=0.01)
    healthy = Socket()
    control.devices["healthy"] = {**control.devices["device"], "ws": healthy}
    await start_and_flush(control, admin, request(target_devices=["device", "healthy"]))
    await control.receive(healthy, "healthy", {"request_id": REQUEST, "code": "requested"})
    await asyncio.sleep(0.03)
    results = [m for m in admin.sent if m["type"] == "OPEN_URL_RESULT"]
    assert [(m["device_id"], m["code"]) for m in results] == [
        ("healthy", "requested"), ("device", "timeout")]
    assert len(device.sent) == len(healthy.sent) == 1
    await control.receive(device, "device", {"request_id": REQUEST, "code": "requested"})
    assert len([m for m in admin.sent if m["type"] == "OPEN_URL_RESULT"]) == 2
    assert not control.pending


@pytest.mark.asyncio
async def test_slow_socket_does_not_delay_dispatch_to_another_headset():
    control, device, admin = setup_control()
    release = asyncio.Event()
    sent = asyncio.Event()

    class SlowSocket(Socket):
        async def send_str(self, message):
            await release.wait()
            raise RuntimeError("Transport closed")

    class HealthySocket(Socket):
        async def send_str(self, message):
            await super().send_str(message)
            sent.set()

    healthy = HealthySocket()
    control.devices["device"]["ws"] = SlowSocket()
    control.devices["healthy"] = {**control.devices["device"], "ws": healthy}
    dispatch = asyncio.create_task(control.start(admin, request(target_devices=["device", "healthy"])))
    try:
        await asyncio.wait_for(sent.wait(), 1)
        assert dispatch.done()
        await control.receive(healthy, "healthy", {"request_id": REQUEST, "code": "requested"})
        assert any(m.get("status") == "requested" and m["device_id"] == "healthy" for m in admin.sent)
    finally:
        release.set()
        await dispatch
        await asyncio.gather(*list(control.send_tasks))
        await control.close()
    assert any(m.get("code") == "unavailable" and m["device_id"] == "device" for m in admin.sent)


@pytest.mark.asyncio
async def test_real_websocket_registration_dispatch_result_and_capability_refresh(tmp_path, caplog):
    device_id = "64b19041-0b8c-4ef4-82fd-000000000000"
    server.devices.clear()
    server.device_registry.clear()
    server.admin_connections.clear()
    server._apply_data_dir(str(tmp_path))
    app = TestServer(server.create_app())
    await app.start_server()
    registration = {
        "type": "REGISTER", "identity_scheme": "styly_device_id_v1",
        "device_id": device_id, "model": "PICO", "ip": "127.0.0.1",
        "capabilities": ["open_url_v1"], "push_runtime": {"active": None},
    }
    try:
        async with aiohttp.ClientSession() as session:
            admin = await session.ws_connect(app.make_url("/ws/admin"))
            device = await session.ws_connect(app.make_url("/ws/device"))
            await device.send_json(registration)
            assert await receive_type(device, "REGISTERED")
            listing = await receive_type(admin, "DEVICE_LIST", predicate=lambda m: any(
                d.get("open_url_supported") for d in m["devices"]))
            assert listing is not None
            await admin.send_str('{"type":"OPEN_URL","url":"https://private-url-sentinel.invalid/"')
            await admin.send_json({"type": "OPEN_URL", **request(target_devices=[device_id])})
            execute = await receive_type(device, "EXECUTE_OPEN_URL")
            assert execute["url"] == request()["url"]
            assert await receive_type(admin, "OPEN_URL_SENT")
            await device.send_json({"type": "OPEN_URL_RESULT", "request_id": REQUEST,
                                    "code": "requested", "error": "secret URL"})
            result = await receive_type(admin, "OPEN_URL_RESULT")
            assert result["device_id"] == device_id
            assert result["status"] == "requested"
            assert "secret" not in json.dumps(result)
            assert "private-url-sentinel" not in caplog.text
            await device.send_json({**registration, "capabilities": []})
            assert await receive_type(device, "REGISTERED")
            listing = await receive_type(admin, "DEVICE_LIST", predicate=lambda m: any(
                d["device_id"] == device_id and d.get("open_url_supported") is False for d in m["devices"]))
            assert listing is not None
            await admin.send_json({"type": "OPEN_URL", **request(target_devices=[device_id])})
            result = await receive_type(admin, "OPEN_URL_RESULT")
            assert result["code"] == "unsupported"
            await device.close()
            await admin.close()
    finally:
        await app.close()
        server.devices.clear()
        server.device_registry.clear()
        server.admin_connections.clear()


@pytest.mark.asyncio
async def test_real_websockets_open_multiple_headsets_with_mixed_outcomes(tmp_path, monkeypatch):
    ids = [f"64b19041-0b8c-4ef4-82fd-{i:012d}" for i in range(4)]
    server.devices.clear()
    server.device_registry.clear()
    server.admin_connections.clear()
    server._apply_data_dir(str(tmp_path))
    app = TestServer(server.create_app())
    await app.start_server()
    try:
        async with aiohttp.ClientSession() as session:
            admin = await session.ws_connect(app.make_url("/ws/admin"))
            peers = []
            for index, device_id in enumerate(ids[:3]):
                peer = await session.ws_connect(app.make_url("/ws/device"))
                peers.append(peer)
                await peer.send_json({
                    "type": "REGISTER", "identity_scheme": "styly_device_id_v1",
                    "device_id": device_id, "model": "PICO", "ip": "127.0.0.1",
                    "capabilities": ["open_url_v1"] if index < 2 else [],
                    "push_runtime": {"active": None},
                })
                assert await receive_type(peer, "REGISTERED")
            assert await receive_type(admin, "DEVICE_LIST", predicate=lambda m:
                                      len(m["devices"]) == 3 and all(d["status"] == "online" for d in m["devices"]))
            await admin.send_json({"type": "OPEN_URL", **request(target_devices=ids + [ids[0]])})
            commands = await asyncio.gather(*(receive_type(peer, "EXECUTE_OPEN_URL") for peer in peers[:2]))
            assert commands[0] == commands[1]
            assert commands[0]["url"] == request()["url"]
            dispatch_frames = [await asyncio.wait_for(admin.receive_json(), 2) for _ in range(4)]
            assert {m["device_id"] for m in dispatch_frames if m["type"] == "OPEN_URL_SENT"} == set(ids[:2])
            assert {m["device_id"]: m["code"] for m in dispatch_frames if m["type"] == "OPEN_URL_RESULT"} == {
                ids[2]: "unsupported", ids[3]: "invalid_target",
            }
            await peers[1].send_json({"type": "OPEN_URL_RESULT", "request_id": REQUEST, "code": "requested"})
            result = await receive_type(admin, "OPEN_URL_RESULT")
            assert result["device_id"] == ids[1] and result["status"] == "requested"
            assert ids[0] in server.browser_control.pending
            await peers[0].send_json({"type": "OPEN_URL_RESULT", "request_id": REQUEST, "code": "no_browser"})
            result = await receive_type(admin, "OPEN_URL_RESULT")
            assert result["device_id"] == ids[0] and result["status"] == "fail"
            assert not server.browser_control.pending

            # A blocked target send must not hold up this same admin WebSocket's
            # next command after a healthy peer has already completed.
            slow_socket = server.devices[ids[0]]["ws"]
            original_send = slow_socket.send_str
            release = asyncio.Event()
            blocked = asyncio.Event()

            async def blocked_send(message, **kwargs):
                if json.loads(message).get("type") == "EXECUTE_OPEN_URL":
                    blocked.set()
                    await release.wait()
                return await original_send(message, **kwargs)

            monkeypatch.setattr(slow_socket, "send_str", blocked_send)
            first = "22222222-2222-4222-8222-222222222222"
            second = "33333333-3333-4333-8333-333333333333"
            try:
                await admin.send_json({"type": "OPEN_URL", **request(request_id=first, target_devices=ids[:2])})
                assert await receive_type(peers[1], "EXECUTE_OPEN_URL")
                await asyncio.wait_for(blocked.wait(), 1)
                await peers[1].send_json({"type": "OPEN_URL_RESULT", "request_id": first, "code": "requested"})
                assert await receive_type(admin, "OPEN_URL_RESULT", predicate=lambda m: m["request_id"] == first)
                await admin.send_json({"type": "OPEN_URL", **request(request_id=second, target_devices=[ids[1]])})
                next_command = await receive_type(peers[1], "EXECUTE_OPEN_URL", timeout=1)
                assert next_command is not None and next_command["request_id"] == second
                assert not release.is_set()
            finally:
                release.set()
            for peer in peers:
                await peer.close()
            await admin.close()
    finally:
        await app.close()
        server.devices.clear()
        server.device_registry.clear()
        server.admin_connections.clear()
