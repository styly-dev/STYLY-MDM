"""Close-tab orchestration uses the browser lock without requiring a new APK."""

import asyncio
import json

import aiohttp
import pytest
from aiohttp.test_utils import TestServer

from styly_mdm import server
from styly_mdm.browser_control import BrowserControl
from styly_mdm.device_policy import command_allowed
from test_browser_control import Socket, request
from ws_test_helpers import receive_type


class Tabs:
    available = True

    def __init__(self):
        self.calls = []
        self.outcomes = {}

    async def close_all_tabs(self, device):
        self.calls.append(device)
        outcome = self.outcomes.get(device["device_id"], {
            "status": "confirmed", "message": "Closed 3 tabs; 0 remain.",
            "closed_tabs": 3, "remaining_tabs": 0,
        })
        if isinstance(outcome, Exception):
            raise outcome
        if callable(outcome):
            return await outcome()
        return outcome


def setup(timeout=90):
    adapter, device, admin = Tabs(), Socket(), Socket()
    devices = {"device": {"ws": device, "registration_ready": True,
        "identity_kind": "canonical", "status": "online", "capabilities": [], "ip": "192.0.2.1"}}
    return BrowserControl(devices, command_allowed, browser_tabs=adapter, close_timeout=timeout), adapter, device, admin


async def close_tabs(control, admin, **changes):
    await control.start(admin, request(**changes), operation="CLOSE_BROWSER_TABS")
    await asyncio.gather(*list(control.send_tasks))


@pytest.mark.asyncio
async def test_close_without_url_or_open_url_capability_preserves_counts():
    control, adapter, device, admin = setup()
    assert control.tabs_supported(control.devices["device"])
    await close_tabs(control, admin, url=None, target_devices=["device", "device"])
    assert len(adapter.calls) == 1
    assert adapter.calls[0]["device_id"] == "device"
    assert adapter.calls[0]["ip"] == "192.0.2.1"
    assert not device.sent
    assert [m["type"] for m in admin.sent] == ["CLOSE_BROWSER_TABS_SENT", "CLOSE_BROWSER_TABS_RESULT"]
    assert admin.sent[-1]["status"] == "confirmed"
    assert admin.sent[-1]["closed_tabs"] == 3 and admin.sent[-1]["remaining_tabs"] == 0
    assert not control.pending


@pytest.mark.asyncio
async def test_close_mixed_results_and_offline_targets_are_independent():
    control, adapter, device, admin = setup()
    for name in ("partial", "failed", "offline"):
        control.devices[name] = {**control.devices["device"], "ws": Socket()}
    control.devices["offline"]["status"] = "offline"
    adapter.outcomes["partial"] = {"status": "partial", "message": "Closed 2 tabs; 1 remains.",
                                    "closed_tabs": 2, "remaining_tabs": 1}
    adapter.outcomes["failed"] = RuntimeError("Browser controls unavailable.")
    await close_tabs(control, admin, target_devices=["failed", "device", "offline", "partial", "missing"])
    results = {m["device_id"]: m for m in admin.sent if m["type"] == "CLOSE_BROWSER_TABS_RESULT"}
    assert {device: result["status"] for device, result in results.items()} == {
        "device": "confirmed", "partial": "partial", "failed": "fail", "offline": "fail", "missing": "fail",
    }
    assert results["partial"]["remaining_tabs"] == 1
    assert {call["device_id"] for call in adapter.calls} == {"failed", "device", "partial"}
    assert not control.pending


@pytest.mark.parametrize("change", [{"identity_kind": "legacy"}, {"identity_kind": "provisional"},
                                     {"registration_ready": False}, {"status": "offline"}])
@pytest.mark.asyncio
async def test_close_rejects_ineligible_targets(change):
    control, adapter, device, admin = setup()
    control.devices["device"].update(change)
    assert not control.tabs_supported(control.devices["device"])
    await close_tabs(control, admin)
    assert not adapter.calls and admin.sent[-1]["status"] == "fail"


@pytest.mark.asyncio
async def test_missing_adb_and_invalid_targets_do_not_start_adapter():
    control, adapter, device, admin = setup()
    adapter.available = False
    assert not control.tabs_supported(control.devices["device"])
    await close_tabs(control, admin)
    assert admin.sent[-1]["code"] == "unsupported"
    for targets in ([], ["*"], "device", [{}]):
        await close_tabs(control, admin, target_devices=targets)
        assert admin.sent[-1]["type"] == "CLOSE_BROWSER_TABS_RESULT"
        assert admin.sent[-1]["code"] == "invalid_target"
    assert not adapter.calls


@pytest.mark.asyncio
async def test_open_and_close_share_lock_and_client_result_cannot_settle_close():
    control, adapter, device, admin = setup()
    control.devices["device"]["capabilities"] = ["open_url_v1"]
    await control.start(admin, request())
    await close_tabs(control, admin)
    assert admin.sent[-1]["type"] == "CLOSE_BROWSER_TABS_RESULT" and admin.sent[-1]["code"] == "busy"
    assert not adapter.calls
    await control.receive(device, "device", {"request_id": request()["request_id"], "code": "requested"})
    started, release = asyncio.Event(), asyncio.Event()

    async def blocked():
        started.set()
        await release.wait()
        return {"status": "confirmed", "message": "No tabs remain.", "closed_tabs": 0, "remaining_tabs": 0}

    adapter.outcomes["device"] = blocked
    await control.start(admin, request(), operation="CLOSE_BROWSER_TABS")
    await asyncio.wait_for(started.wait(), 1)
    await control.receive(device, "device", {"request_id": request()["request_id"], "code": "requested"})
    assert control.pending["device"]["operation"] == "CLOSE_BROWSER_TABS"
    await control.start(admin, request())
    await asyncio.sleep(0)
    assert admin.sent[-1]["type"] == "OPEN_URL_RESULT" and admin.sent[-1]["code"] == "busy"
    release.set()
    await asyncio.gather(*list(control.send_tasks))
    assert not control.pending


@pytest.mark.asyncio
async def test_close_timeout_and_shutdown_cancel_adapter_before_releasing_lock():
    for shutdown in (False, True):
        control, adapter, device, admin = setup(timeout=0.01 if not shutdown else 90)
        started, cancelled = asyncio.Event(), asyncio.Event()

        async def blocked():
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()

        adapter.outcomes["device"] = blocked
        await control.start(admin, request(), operation="CLOSE_BROWSER_TABS")
        await asyncio.wait_for(started.wait(), 1)
        if shutdown:
            await control.close()
        else:
            await asyncio.gather(*list(control.send_tasks))
            assert admin.sent[-1]["code"] == "close_timeout"
        assert cancelled.is_set()
        assert not control.pending


@pytest.mark.asyncio
async def test_replaced_connection_before_execution_does_not_close_tabs():
    control, adapter, device, admin = setup()
    await control.start(admin, request(), operation="CLOSE_BROWSER_TABS")
    control.devices["device"]["ws"] = Socket()
    await asyncio.gather(*list(control.send_tasks))
    assert not adapter.calls and admin.sent[-1]["status"] == "fail"


@pytest.mark.asyncio
async def test_real_admin_ws_multi_close_without_new_client_and_responsive_followup(tmp_path, monkeypatch):
    ids = [f"64b19041-0b8c-4ef4-82fd-{i:012d}" for i in range(2)]
    server.devices.clear()
    server.device_registry.clear()
    server.admin_connections.clear()
    server._apply_data_dir(str(tmp_path))
    adapter = Tabs()
    monkeypatch.setattr(server.browser_control, "browser_tabs", adapter)
    started, release = asyncio.Event(), asyncio.Event()

    async def blocked():
        started.set()
        await release.wait()
        return {"status": "confirmed", "message": "No tabs remain.", "closed_tabs": 0, "remaining_tabs": 0}

    adapter.outcomes[ids[0]] = blocked
    app = TestServer(server.create_app())
    await app.start_server()
    try:
        async with aiohttp.ClientSession() as session:
            admin = await session.ws_connect(app.make_url("/ws/admin"))
            peers = []
            for device_id in ids:
                peer = await session.ws_connect(app.make_url("/ws/device"))
                peers.append(peer)
                await peer.send_json({"type": "REGISTER", "identity_scheme": "styly_device_id_v1",
                    "device_id": device_id, "model": "PICO", "ip": "192.0.2.1",
                    "capabilities": [], "push_runtime": {"active": None}})
                assert await receive_type(peer, "REGISTERED")
            listing = await receive_type(admin, "DEVICE_LIST", predicate=lambda m:
                len(m["devices"]) == 2 and all(d.get("close_browser_tabs_supported") for d in m["devices"]))
            assert listing is not None and all(not d["open_url_supported"] for d in listing["devices"])
            await admin.send_json({"type": "CLOSE_BROWSER_TABS", **request(url=None, target_devices=ids)})
            first = await receive_type(admin, "CLOSE_BROWSER_TABS_RESULT", predicate=lambda m: m["device_id"] == ids[1])
            assert first["status"] == "confirmed"
            await asyncio.wait_for(started.wait(), 1)
            next_id = "22222222-2222-4222-8222-222222222222"
            await admin.send_json({"type": "CLOSE_BROWSER_TABS", **request(request_id=next_id, target_devices=[ids[1]])})
            second = await receive_type(admin, "CLOSE_BROWSER_TABS_RESULT", timeout=1,
                                        predicate=lambda m: m["request_id"] == next_id)
            assert second is not None and second["status"] == "confirmed"
            assert not release.is_set()
            release.set()
            for peer in peers:
                await peer.close()
            await admin.close()
    finally:
        release.set()
        await app.close()
        server.devices.clear()
        server.device_registry.clear()
        server.admin_connections.clear()
