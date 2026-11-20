"""Exercise identity checks and data-preserving tab control over real local HTTP."""

import asyncio
import json
import shlex
import sys
from unittest.mock import AsyncMock, Mock

from aiohttp import web
from aiohttp.test_utils import TestServer
import pytest
import pytest_asyncio

from styly_mdm import browser_tabs as browser_tabs_module
from styly_mdm.browser_tabs import BrowserTabs


GUID = "01234567-89ab-cdef-0123-456789abcdef"
OTHER_GUID = "11111111-2222-3333-4444-555555555555"
DEVICE = {"device_id": GUID, "identity_kind": "canonical", "ip": "192.0.2.1", "status": "online"}
PACKAGE = "com.pico.browser.overseas"
IDENTITY = f"Row: 0 _id=10, _display_name={GUID}.png, date_added=1700000000"


@pytest.mark.asyncio
async def test_adb_failure_does_not_expose_raw_diagnostics(monkeypatch):
    browser = BrowserTabs(adb=sys.executable)
    process = Mock(returncode=1)
    process.communicate = AsyncMock(return_value=(b"Failed opening https://private.invalid/?secret=token", None))
    monkeypatch.setattr(browser_tabs_module.asyncio, "create_subprocess_exec", AsyncMock(return_value=process))
    with pytest.raises(RuntimeError) as error:
        await browser.command("connect", "192.0.2.1:5555")
    assert "private" not in str(error.value) and "secret" not in str(error.value)


@pytest.mark.asyncio
async def test_cancelled_adb_process_is_killed_and_reaped(monkeypatch):
    browser = BrowserTabs(adb=sys.executable)
    process = Mock(returncode=None)
    process.communicate = AsyncMock(side_effect=asyncio.CancelledError)
    process.wait = AsyncMock()
    monkeypatch.setattr(browser_tabs_module.asyncio, "create_subprocess_exec", AsyncMock(return_value=process))
    with pytest.raises(asyncio.CancelledError):
        await browser.command("connect", "192.0.2.1:5555")
    process.kill.assert_called_once_with()
    process.wait.assert_awaited_once_with()


@pytest.mark.asyncio
async def test_identity_matches_pinned_provider_namespace_without_writing():
    browser = BrowserTabs(adb=sys.executable)
    browser.command = AsyncMock(side_effect=["connected", IDENTITY])
    assert await browser.connect(DEVICE) == "192.0.2.1:5555"
    query = shlex.split(browser.command.await_args.args[3])
    assert query[:4] == ["content", "query", "--uri", "content://media/external/images/media"]
    assert query[query.index("--where") + 1] == (
        "relative_path LIKE 'Pictures/Device-ID-Provider/%' AND _display_name LIKE '%.png' AND is_pending=0"
    )
    assert query[query.index("--sort") + 1] == "date_added ASC, _id ASC"
    assert query[query.index("--projection") + 1] == "_id:_display_name:date_added"


@pytest.mark.parametrize("output", [
    "No result found.",
    IDENTITY.replace(GUID, OTHER_GUID),
    IDENTITY + "\n" + IDENTITY.replace("Row: 0", "Row: 1").replace(GUID, OTHER_GUID),
    IDENTITY + "\n" + IDENTITY.replace("Row: 0", "Row: 1"),
    "Error while accessing provider: permission denied",
    IDENTITY.replace("_display_name=", "display_name="),
    IDENTITY + "\nRow: 1 _id=11, _display_name=" + OTHER_GUID + ".png, date_added=NULL",
    IDENTITY + "\nRow: 1 _id=11, _display_name=" + OTHER_GUID + ".png",
])
@pytest.mark.asyncio
async def test_missing_mismatched_ambiguous_or_unparseable_identity_never_proceeds(output):
    browser = BrowserTabs(adb=sys.executable)
    browser.command = AsyncMock(side_effect=["connected", output])
    with pytest.raises(RuntimeError, match="identity"):
        await browser.connect(DEVICE)
    assert browser.command.await_count == 2


@pytest.mark.asyncio
async def test_unrelated_image_names_do_not_become_device_id_candidates():
    browser = BrowserTabs(adb=sys.executable)
    browser.command = AsyncMock(side_effect=["connected", IDENTITY +
        "\nRow: 1 _id=11, _display_name=unrelated.png, date_added=1700000001"])
    assert await browser.connect(DEVICE) == "192.0.2.1:5555"


@pytest_asyncio.fixture
async def tab_browser():
    browser = BrowserTabs(adb=sys.executable)
    state = {
        "tabs": [{"id": "one", "type": "page", "url": "https://private.invalid/?token=secret"},
                 {"id": "two", "type": "page", "url": "https://example.com/"},
                 {"id": "worker", "type": "service_worker"}],
        "requests": [], "mode": "normal", "running": True,
        "started": asyncio.Event(), "release": asyncio.Event(),
    }

    async def listing(request):
        if state["mode"] == "wait":
            state["started"].set()
            await state["release"].wait()
        if state["mode"] == "redirect":
            raise web.HTTPFound("/unexpected?private=secret")
        if state["mode"] == "oversized":
            return web.Response(body=b" " * (1024 * 1024 + 1))
        if state["mode"] == "bad_utf8":
            return web.Response(body=b"\xff")
        if state["mode"] == "bad_json":
            return web.Response(text="private=secret")
        response = web.StreamResponse(headers={"Content-Type": "application/json"})
        await response.prepare(request)
        data = json.dumps(state["tabs"]).encode()
        await response.write(data[:5])
        await asyncio.sleep(0)
        await response.write(data[5:])
        await response.write_eof()
        return response

    async def close(request):
        key = request.match_info["target"]
        state["requests"].append(key)
        if state["mode"] != "unchanged":
            state["tabs"] = [tab for tab in state["tabs"] if tab["id"] != key]
            if not any(tab["type"] == "page" for tab in state["tabs"]) and state.get("replacement"):
                state["tabs"].append({"id": "replacement", "type": "page", "url": state["replacement"]})
        return web.Response(text="private=secret", status=500 if state["mode"] == "lost" else 200)

    app = web.Application()
    app.router.add_get("/json/list", listing)
    app.router.add_get("/json/close/{target}", close)
    async with TestServer(app) as transport:
        state["port"] = str(transport.port)

        async def command(*args, **kwargs):
            if args[0] == "connect":
                return "connected"
            if args[2] == "shell" and args[3].startswith("content query"):
                return IDENTITY
            if args[2] == "shell" and args[3].startswith("cmd package resolve-activity"):
                return PACKAGE + "/.Main"
            if args[2] == "shell" and args[3].startswith("pidof "):
                return state.get("pids", "123" if state["running"] else "")
            if args[2:] == ("shell", "cat", "/proc/net/unix"):
                return state.get("sockets", "other @weblayer_devtools_remote_999\nbrowser @weblayer_devtools_remote_123")
            if args[2] == "shell" and args[3].startswith("am start "):
                if state.get("blocked"):
                    return "Warning: Activity not started because the current activity is being kept for the user.\nStatus: ok"
                state["running"] = True
                return "Status: ok"
            if args[2:] == ("forward", "tcp:0", "localabstract:weblayer_devtools_remote_123"):
                return str(transport.port)
            if args[2:] == ("forward", "--remove", "tcp:" + str(transport.port)):
                return ""
            raise AssertionError(args)

        browser.command = AsyncMock(side_effect=command)
        try:
            yield browser, state
        finally:
            state["release"].set()


def assert_forward_removed(browser, state):
    assert browser.command.await_args.args[2:] == ("forward", "--remove", "tcp:" + state["port"])


@pytest.mark.asyncio
async def test_closes_only_snapshot_pages_and_preserves_workers_and_browser_data(tab_browser):
    browser, state = tab_browser
    result = await browser.close_all_tabs(DEVICE)
    assert result["status"] == "confirmed"
    assert result["closed_tabs"] == 2 and result["remaining_tabs"] == 0
    assert state["requests"] == ["one", "two"]
    assert state["tabs"] == [{"id": "worker", "type": "service_worker"}]
    assert "private" not in str(result) and "secret" not in str(result)
    assert_forward_removed(browser, state)
    calls = [call.args for call in browser.command.await_args_list]
    assert sum("tcp:0" in call for call in calls) == 1
    assert not any(any(word in str(call) for word in ("pm clear", "force-stop", "install", "remove-all")) for call in calls)


@pytest.mark.asyncio
async def test_non_running_browser_restores_saved_tabs_without_opening_url(tab_browser):
    browser, state = tab_browser
    state["running"] = False
    assert (await browser.close_all_tabs(DEVICE))["status"] == "confirmed"
    launches = [call.args[3] for call in browser.command.await_args_list
                if len(call.args) > 3 and call.args[3].startswith("am start ")]
    assert len(launches) == 1 and "android.intent.action.MAIN" in launches[0]
    assert " -d " not in launches[0]


@pytest.mark.asyncio
async def test_empty_browser_requires_no_close_calls(tab_browser):
    browser, state = tab_browser
    state["tabs"] = []
    result = await browser.close_all_tabs(DEVICE)
    assert result == {"status": "confirmed", "closed_tabs": 0, "remaining_tabs": 0, "message": "No open browser tabs."}
    assert not state["requests"]


@pytest.mark.parametrize("replacement,expected", [
    ("about:blank", "confirmed"), ("chrome://newtab/", "confirmed"),
    ("https://private.invalid/new?secret=value", "partial"),
])
@pytest.mark.asyncio
async def test_replacement_tabs_are_not_chased_and_only_empty_one_counts_as_complete(tab_browser, replacement, expected):
    browser, state = tab_browser
    state["replacement"] = replacement
    result = await browser.close_all_tabs(DEVICE)
    assert result["status"] == expected
    assert result["closed_tabs"] == 2 and result["remaining_tabs"] == 1
    assert state["requests"] == ["one", "two"]
    assert "private" not in str(result) and "secret" not in str(result)
    assert_forward_removed(browser, state)


@pytest.mark.asyncio
async def test_success_responses_do_not_hide_remaining_tabs(tab_browser):
    browser, state = tab_browser
    state["mode"] = "unchanged"
    result = await browser.close_all_tabs(DEVICE)
    assert result["status"] == "partial" and result["closed_tabs"] == 0 and result["remaining_tabs"] == 2
    assert state["requests"] == ["one", "two"]


@pytest.mark.asyncio
async def test_lost_close_responses_are_verified_without_retry(tab_browser):
    browser, state = tab_browser
    state["mode"] = "lost"
    assert (await browser.close_all_tabs(DEVICE))["status"] == "confirmed"
    assert state["requests"] == ["one", "two"]


@pytest.mark.parametrize("tabs", [None, {}, ["invalid"], [{"type": "page", "id": "../../other"}],
    [{"type": "page", "id": "one"}, {"type": "page", "id": "one"}],
    [{"type": "page", "id": "one", "url": []}]])
@pytest.mark.asyncio
async def test_invalid_metadata_never_closes_and_removes_forward(tab_browser, tabs):
    browser, state = tab_browser
    state["tabs"] = tabs
    with pytest.raises(RuntimeError, match="tab list"):
        await browser.close_all_tabs(DEVICE)
    assert not state["requests"]
    assert_forward_removed(browser, state)


@pytest.mark.parametrize("mode", ["redirect", "oversized", "bad_utf8", "bad_json"])
@pytest.mark.asyncio
async def test_http_failures_do_not_leak_browsing_data_and_remove_forward(tab_browser, mode):
    browser, state = tab_browser
    state["mode"] = mode
    with pytest.raises(RuntimeError) as error:
        await browser.close_all_tabs(DEVICE)
    assert "private" not in str(error.value) and "secret" not in str(error.value)
    assert not state["requests"]
    assert_forward_removed(browser, state)


@pytest.mark.asyncio
async def test_cancellation_removes_only_its_dynamic_forward(tab_browser):
    browser, state = tab_browser
    state["mode"] = "wait"
    operation = asyncio.create_task(browser.close_all_tabs(DEVICE))
    await asyncio.wait_for(state["started"].wait(), 3)
    operation.cancel()
    with pytest.raises(asyncio.CancelledError):
        await operation
    state["release"].set()
    assert_forward_removed(browser, state)


@pytest.mark.parametrize("pids", ["123 456", "unexpected"])
@pytest.mark.asyncio
async def test_ambiguous_browser_pid_never_forwards_another_app_socket(tab_browser, pids):
    browser, state = tab_browser
    state["pids"] = pids
    with pytest.raises(RuntimeError, match="browser process"):
        await browser.close_all_tabs(DEVICE)
    assert not any("forward" in call.args for call in browser.command.await_args_list)
    assert not state["requests"]


@pytest.mark.asyncio
async def test_unrelated_socket_is_never_used_when_browser_launch_blocked(tab_browser):
    browser, state = tab_browser
    state["sockets"] = "other @weblayer_devtools_remote_999"
    state["blocked"] = True
    with pytest.raises(RuntimeError, match="did not allow"):
        await browser.close_all_tabs(DEVICE)
    assert not any("forward" in call.args for call in browser.command.await_args_list)
    assert not state["requests"]


@pytest.mark.asyncio
async def test_offline_missing_adb_and_noncanonical_targets_do_not_connect(tab_browser):
    browser, state = tab_browser
    with pytest.raises(ValueError, match="online"):
        await browser.close_all_tabs({**DEVICE, "status": "offline"})
    with pytest.raises(ValueError, match="canonical"):
        await browser.close_all_tabs({**DEVICE, "identity_kind": "legacy"})
    browser.adb = None
    with pytest.raises(ValueError, match="platform-tools"):
        await browser.close_all_tabs(DEVICE)
    browser.command.assert_not_awaited()
    assert not state["requests"]
