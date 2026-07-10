"""Routing tests for the integrity-verification messages (issue #37).

No network: the async handlers are driven directly with fake WebSocket stubs that
record what they were sent, mirroring how the server relays INSTALL_APK.
"""

import asyncio
import json

import pytest

from styly_mdm import server


class FakeWS:
    """Minimal stand-in for aiohttp's WebSocketResponse (records sent frames)."""

    def __init__(self):
        self.sent = []

    async def send_str(self, s):
        self.sent.append(json.loads(s))


@pytest.fixture(autouse=True)
def clean_devices():
    saved = dict(server.devices)
    server.devices.clear()
    yield
    server.devices.clear()
    server.devices.update(saved)


def _add_device(device_id):
    ws = FakeWS()
    server.devices[device_id] = {"ws": ws, "device_id": device_id}
    return ws


# --------------------------------------------------------------------------- #
# VERIFY_APK
# --------------------------------------------------------------------------- #

def test_verify_apk_fans_out_and_acks():
    dev = _add_device("DEV1")
    admin = FakeWS()
    asyncio.run(server.handle_verify_apk(admin, {"target_devices": ["DEV1"], "package_name": "com.x"}))
    assert dev.sent == [{"type": "EXECUTE_VERIFY_APK", "package_name": "com.x"}]
    ack = admin.sent[-1]
    assert ack["type"] == "VERIFY_SENT"
    assert ack["package_name"] == "com.x"
    assert ack["sent_count"] == 1
    assert ack["target_count"] == 1


def test_verify_apk_wildcard_targets_all_online():
    d1, d2 = _add_device("A"), _add_device("B")
    admin = FakeWS()
    asyncio.run(server.handle_verify_apk(admin, {"target_devices": ["*"], "package_name": "com.x"}))
    assert len(d1.sent) == 1 and len(d2.sent) == 1
    assert admin.sent[-1]["sent_count"] == 2


def test_verify_apk_requires_package_name():
    admin = FakeWS()
    asyncio.run(server.handle_verify_apk(admin, {"target_devices": ["DEV1"]}))
    assert admin.sent[-1] == {"type": "ERROR", "message": "package_name is required"}


def test_verify_apk_no_matching_devices():
    admin = FakeWS()
    asyncio.run(server.handle_verify_apk(admin, {"target_devices": ["GHOST"], "package_name": "com.x"}))
    assert admin.sent[-1]["type"] == "ERROR"
    assert "online" in admin.sent[-1]["message"].lower()


# --------------------------------------------------------------------------- #
# VERIFY_DIR
# --------------------------------------------------------------------------- #

def test_verify_dir_fans_out_and_acks():
    dev = _add_device("DEV1")
    admin = FakeWS()
    asyncio.run(server.handle_verify_dir(admin, {"target_devices": ["DEV1"], "path": "/sdcard/content"}))
    assert dev.sent == [{"type": "EXECUTE_VERIFY_DIR", "path": "/sdcard/content"}]
    ack = admin.sent[-1]
    assert ack["type"] == "VERIFY_DIR_SENT"
    assert ack["path"] == "/sdcard/content"
    assert ack["sent_count"] == 1


@pytest.mark.parametrize("bad_path", ["", "relative/path", "/sdcard/../data/x", ".."])
def test_verify_dir_rejects_unsafe_path(bad_path):
    _add_device("DEV1")
    admin = FakeWS()
    asyncio.run(server.handle_verify_dir(admin, {"target_devices": ["DEV1"], "path": bad_path}))
    assert admin.sent[-1]["type"] == "ERROR"


@pytest.mark.parametrize("good_path", ["/sdcard", "/sdcard/content", "/storage/emulated/0/x/y"])
def test_is_syntactically_safe_verify_path_accepts(good_path):
    assert server.is_syntactically_safe_verify_path(good_path)


@pytest.mark.parametrize("bad_path", ["", "rel", "/a/../b", "/a/..", "..", "/.."])
def test_is_syntactically_safe_verify_path_rejects(bad_path):
    assert not server.is_syntactically_safe_verify_path(bad_path)


# --------------------------------------------------------------------------- #
# device-result routing (the forward set must relay both result types)
# --------------------------------------------------------------------------- #

def test_result_message_types_are_forwarded():
    """The device handler forwards these verbatim to admins after stamping device_id."""
    import inspect
    src = inspect.getsource(server.device_ws_handler)
    assert "VERIFY_APK_RESULT" in src
    assert "VERIFY_DIR_RESULT" in src
