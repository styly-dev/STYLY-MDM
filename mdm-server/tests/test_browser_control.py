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
    await control.start(admin, request())
    assert device.sent == [{"type": "EXECUTE_OPEN_URL", "request_id": REQUEST, "url": request()["url"]}]
    assert admin.sent[-1]["type"] == "OPEN_URL_SENT"
    await control.receive(device, "device", {"request_id": REQUEST, "code": "requested", "error": "secret URL"})
    assert admin.sent[-1]["status"] == "requested"
    assert "secret" not in json.dumps(admin.sent)
    assert not control.pending


@pytest.mark.parametrize("changes", [
    {"target_devices": []}, {"target_devices": ["*"]}, {"target_devices": "device"},
    {"target_devices": ["device", "device"]}, {"target_devices": [{}]},
    {"request_id": []}, {"request_id": "bad"}, {"target_connections": []}, {"url": "file:///x"},
])
@pytest.mark.asyncio
async def test_invalid_request_never_dispatches(changes):
    control, device, admin = setup_control()
    await control.start(admin, request(**changes))
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
    await control.start(admin, request())
    assert not device.sent
    assert admin.sent[-1]["status"] == "fail"


@pytest.mark.asyncio
async def test_busy_requests_and_wrong_or_stale_results_do_not_settle():
    control, device, admin = setup_control()
    await control.start(admin, request())
    await control.start(admin, request(request_id="22222222-2222-4222-8222-222222222222"))
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
    await control.start(admin, request())
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
    await control.start(admin, request())
    assert admin.sent[-1]["code"] == "unavailable"
    assert not control.pending
    device.failure = False
    await control.start(admin, request())
    await control.receive(device, "device", {"request_id": REQUEST, "code": ["requested"], "message": "secret"})
    assert admin.sent[-1]["code"] == "launch_failed"
    assert "secret" not in json.dumps(admin.sent)


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
