"""Tests for the device-retire lifecycle (issue #49).

Retiring makes the client uninstall itself (and its guard) at venue handover.
The semantics are the self-update's inverted: the client announces
SELF_UNINSTALL_STARTING, the uninstall kills it, and *silence* is the success
signal — the server must

  * fan RETIRE_DEVICE out as EXECUTE_SELF_UNINSTALL with a server-generated
    correlation id per device,
  * treat the disconnect after the announcement as "retiring", not "offline",
  * declare success only when the device stays away for RETIRE_TIMEOUT (a device
    still connected at the deadline means the uninstall never ran),
  * fail the retire when the device re-registers within the window,
  * park a successful retire in the terminal, persisted "retired" registry state,
    and drop the flag again when a reinstalled device re-registers.

Structured like test_self_update.py: deterministic unit tests with FakeWS, plus
aiohttp integration tests over loopback that prove the real handler wiring.
"""

from __future__ import annotations

import asyncio
import json

import aiohttp
import pytest
from aiohttp.test_utils import TestServer

from styly_mdm import server


CLIENT_PKG = "com.styly.mdmclient"


# ---------------------------------------------------------------------------
# Shared fixtures / helpers
# ---------------------------------------------------------------------------

class FakeWS:
    """Minimal stand-in for a WebSocketResponse that records sent frames."""

    def __init__(self):
        self.sent: list[str] = []

    async def send_str(self, s: str) -> None:
        self.sent.append(s)


@pytest.fixture(autouse=True)
def reset_state():
    saved_r = server.RETIRE_TIMEOUT
    saved_u = server.SELF_UPDATE_TIMEOUT
    collections = (
        server.devices, server.admin_connections, server.pending_transfers,
        server._transfer_tasks, server.pending_self_updates, server.pending_retires,
        server._apk_hash_cache, server.device_registry, server.device_groups,
    )
    for coll in collections:
        coll.clear()
    server.reset_transfer_slots()
    yield
    for coll in collections:
        coll.clear()
    server.reset_transfer_slots()
    server.RETIRE_TIMEOUT = saved_r
    server.SELF_UPDATE_TIMEOUT = saved_u


def add_device(device_id: str) -> FakeWS:
    ws = FakeWS()
    server.devices[device_id] = {
        "ws": ws, "device_id": device_id, "model": "M", "ip": "1.1.1.1",
        "status": "online", "startup_app": None, "battery": None,
        "version_code": 6, "version_name": "0.2.3",
    }
    return ws


def add_admin() -> FakeWS:
    ws = FakeWS()
    server.admin_connections.add(ws)
    return ws


def add_registry_record(device_id: str, *, retired: bool = False) -> dict:
    rec = {
        "label": "", "model": "M", "ip": "1.1.1.1", "last_seen": 1.0,
        "startup_app": None, "battery": None,
        "version_code": 6, "version_name": "0.2.3", "retired": retired,
    }
    server.device_registry[device_id] = rec
    return rec


def make_retire_pending(device_id: str, corr: str = "corr-1") -> dict:
    entry = {"correlation_id": corr, "started_at": 0.0, "timeout_task": None}
    server.pending_retires[device_id] = entry
    return entry


def frames_of(ws: FakeWS, msg_type: str) -> list[dict]:
    return [json.loads(m) for m in ws.sent if json.loads(m).get("type") == msg_type]


# ---------------------------------------------------------------------------
# Unit tests — fan-out
# ---------------------------------------------------------------------------

def test_retire_device_fans_out_unique_correlation_ids_and_acks():
    async def body():
        admin = add_admin()
        d0 = add_device("dev0")
        d1 = add_device("dev1")

        await server.handle_retire_device(admin, {"target_devices": ["dev0", "dev1"]})

        cmds = frames_of(d0, "EXECUTE_SELF_UNINSTALL") + frames_of(d1, "EXECUTE_SELF_UNINSTALL")
        assert len(cmds) == 2
        cids = [c["correlation_id"] for c in cmds]
        assert all(isinstance(c, str) and c for c in cids)
        assert cids[0] != cids[1]
        acks = frames_of(admin, "RETIRE_SENT")
        assert acks == [{"type": "RETIRE_SENT", "sent_count": 2, "target_count": 2}]

    asyncio.run(body())


def test_retire_device_with_no_online_targets_errors():
    async def body():
        admin = add_admin()
        # Known but offline: a retire has to run on the device, so it is rejected.
        add_registry_record("dev0")

        await server.handle_retire_device(admin, {"target_devices": ["dev0"]})

        errors = frames_of(admin, "ERROR")
        assert errors and "No matching online devices" in errors[0]["message"]

    asyncio.run(body())


# ---------------------------------------------------------------------------
# Unit tests — pending lifecycle
# ---------------------------------------------------------------------------

def test_retire_timeout_marks_retired_and_persists(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        server.RETIRE_TIMEOUT = 0.01
        admin = add_admin()
        add_registry_record("dev0")
        make_retire_pending("dev0")

        await server._retire_timeout("dev0", "corr-1")

        assert "dev0" not in server.pending_retires
        assert server.device_registry["dev0"]["retired"] is True
        results = frames_of(admin, "RETIRE_RESULT")
        assert results[0]["status"] == "success"
        assert results[0]["correlation_id"] == "corr-1"
        lists = frames_of(admin, "DEVICE_LIST")
        assert lists and lists[-1]["devices"][0]["status"] == "retired"
        # The terminal state survives a server restart.
        persisted = json.loads(server.REGISTRY_PATH.read_text(encoding="utf-8"))
        assert persisted["devices"]["dev0"]["retired"] is True

    asyncio.run(body())


def test_retire_timeout_fails_when_device_still_connected():
    async def body():
        server.RETIRE_TIMEOUT = 0.01
        admin = add_admin()
        add_device("dev0")
        add_registry_record("dev0")
        make_retire_pending("dev0")

        await server._retire_timeout("dev0", "corr-1")

        assert "dev0" not in server.pending_retires
        assert server.device_registry["dev0"]["retired"] is False
        results = frames_of(admin, "RETIRE_RESULT")
        assert results[0]["status"] == "fail"
        assert "still connected" in results[0]["detail"]

    asyncio.run(body())


def test_retire_timeout_tolerates_a_forgotten_record():
    async def body():
        server.RETIRE_TIMEOUT = 0.01
        admin = add_admin()
        # No registry record: the operator forgot the device mid-retire.
        make_retire_pending("dev0")

        await server._retire_timeout("dev0", "corr-1")

        assert "dev0" not in server.pending_retires
        assert "dev0" not in server.device_registry
        assert frames_of(admin, "RETIRE_RESULT")[0]["status"] == "success"

    asyncio.run(body())


def test_stale_retire_timeout_ignores_replaced_pending():
    async def body():
        server.RETIRE_TIMEOUT = 0.01
        admin = add_admin()
        make_retire_pending("dev0", corr="corr-2")

        await server._retire_timeout("dev0", "corr-1")

        assert "dev0" in server.pending_retires
        assert not frames_of(admin, "RETIRE_RESULT")

    asyncio.run(body())


def test_resolve_pending_retire_reports_fail_and_cancels_timeout():
    async def body():
        server.RETIRE_TIMEOUT = 60
        admin = add_admin()
        entry = make_retire_pending("dev0")
        entry["timeout_task"] = asyncio.create_task(
            server._retire_timeout("dev0", "corr-1")
        )
        await asyncio.sleep(0)

        await server._resolve_pending_retire("dev0")

        assert "dev0" not in server.pending_retires
        results = frames_of(admin, "RETIRE_RESULT")
        assert results[0]["status"] == "fail"
        assert "re-registered" in results[0]["detail"]
        await asyncio.sleep(0)
        assert entry["timeout_task"].cancelled()

        # A device with no pending entry is a no-op.
        admin.sent.clear()
        await server._resolve_pending_retire("dev1")
        assert not frames_of(admin, "RETIRE_RESULT")

    asyncio.run(body())


def test_device_list_shows_retiring_then_retired():
    add_registry_record("dev0")
    make_retire_pending("dev0")

    listed = {d["device_id"]: d for d in json.loads(server.build_device_list_msg())["devices"]}
    assert listed["dev0"]["status"] == "retiring"

    server.pending_retires.clear()
    server.device_registry["dev0"]["retired"] = True
    listed = {d["device_id"]: d for d in json.loads(server.build_device_list_msg())["devices"]}
    assert listed["dev0"]["status"] == "retired"


def test_coerce_record_roundtrips_retired_flag():
    rec = server._coerce_record({"label": "L", "retired": True})
    assert rec["retired"] is True
    # Absent or junk shapes normalize to False instead of leaking truthiness.
    assert server._coerce_record({"label": "L"})["retired"] is False
    assert server._coerce_record({"label": "L", "retired": "yes"})["retired"] is False
    # Legacy flat records gain the field.
    assert server._coerce_record("just-a-label")["retired"] is False


def test_forget_device_removes_retired_entry_and_cancels_pending(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        server.RETIRE_TIMEOUT = 60
        admin = add_admin()
        add_registry_record("dev0", retired=True)
        entry = make_retire_pending("dev0")
        entry["timeout_task"] = asyncio.create_task(
            server._retire_timeout("dev0", "corr-1")
        )
        await asyncio.sleep(0)

        await server.handle_forget_device(admin, {"device_id": "dev0"})

        assert "dev0" not in server.device_registry
        assert "dev0" not in server.pending_retires
        await asyncio.sleep(0)
        assert entry["timeout_task"].cancelled()
        assert frames_of(admin, "DEVICE_FORGOTTEN") == [
            {"type": "DEVICE_FORGOTTEN", "device_id": "dev0"},
        ]

    asyncio.run(body())


# ---------------------------------------------------------------------------
# Integration tests — real WebSockets prove the handler wiring
# ---------------------------------------------------------------------------

async def _recv_type(ws, msg_type: str, timeout: float = 2.0) -> dict | None:
    try:
        while True:
            msg = await asyncio.wait_for(ws.receive(), timeout)
            if msg.type == aiohttp.WSMsgType.TEXT:
                data = json.loads(msg.data)
                if data.get("type") == msg_type:
                    return data
            elif msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.CLOSING,
                              aiohttp.WSMsgType.ERROR):
                return None
    except asyncio.TimeoutError:
        return None


async def _register(session, base: str, device_id: str, version_code: int = 6):
    ws = await session.ws_connect(base + "/ws/device")
    await ws.send_json({
        "type": "REGISTER", "device_id": device_id, "model": "M",
        "ip": "1.1.1.2", "version_code": version_code, "version_name": "t",
    })
    return ws


def _row(device_list: dict, device_id: str) -> dict:
    return next(d for d in device_list["devices"] if d["device_id"] == device_id)


def test_e2e_retire_happy_path(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        server.RETIRE_TIMEOUT = 0.2
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                d0 = await _register(session, base, "dev0")
                admin = await session.ws_connect(base + "/ws/admin")
                assert await _recv_type(admin, "DEVICE_LIST") is not None

                await admin.send_json({"type": "RETIRE_DEVICE", "target_devices": ["dev0"]})
                ack = await _recv_type(admin, "RETIRE_SENT")
                assert ack["sent_count"] == 1 and ack["target_count"] == 1
                cmd = await _recv_type(d0, "EXECUTE_SELF_UNINSTALL")
                assert cmd["correlation_id"]

                # The client's last words, then the uninstall kills it (close).
                await d0.send_json({
                    "type": "SELF_UNINSTALL_STARTING",
                    "correlation_id": cmd["correlation_id"],
                    "package_name": CLIENT_PKG, "version_code": 6,
                })
                await d0.close()

                # The disconnect renders the row as retiring, not offline.
                dl = await _recv_type(admin, "DEVICE_LIST")
                assert _row(dl, "dev0")["status"] == "retiring"

                # Silence past the window settles the retire as a success and the
                # row lands in the terminal, persisted state.
                result = await _recv_type(admin, "RETIRE_RESULT")
                assert result["status"] == "success"
                assert result["correlation_id"] == cmd["correlation_id"]
                dl = await _recv_type(admin, "DEVICE_LIST")
                assert _row(dl, "dev0")["status"] == "retired"

                # A retired row is offline, so the existing forget path removes it.
                await admin.send_json({"type": "FORGET_DEVICE", "device_id": "dev0"})
                forgotten = await _recv_type(admin, "DEVICE_FORGOTTEN")
                assert forgotten["device_id"] == "dev0"

                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_reregister_within_timeout_is_failure_and_unretires(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        server.RETIRE_TIMEOUT = 60
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                d0 = await _register(session, base, "dev0")
                admin = await session.ws_connect(base + "/ws/admin")
                assert await _recv_type(admin, "DEVICE_LIST") is not None

                await admin.send_json({"type": "RETIRE_DEVICE", "target_devices": ["dev0"]})
                cmd = await _recv_type(d0, "EXECUTE_SELF_UNINSTALL")
                await d0.send_json({
                    "type": "SELF_UNINSTALL_STARTING",
                    "correlation_id": cmd["correlation_id"],
                    "package_name": CLIENT_PKG, "version_code": 6,
                })
                await d0.close()
                dl = await _recv_type(admin, "DEVICE_LIST")
                assert _row(dl, "dev0")["status"] == "retiring"

                # The keep-alive restarted the merely-killed client: the retire
                # failed, and the device is back to plain online.
                d0b = await _register(session, base, "dev0")
                result = await _recv_type(admin, "RETIRE_RESULT")
                assert result["status"] == "fail"
                assert "re-registered" in result["detail"]
                dl = await _recv_type(admin, "DEVICE_LIST")
                assert _row(dl, "dev0")["status"] == "online"
                assert server.device_registry["dev0"].get("retired") is not True

                await d0b.close()
                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_client_reported_failure_without_disconnect(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        server.RETIRE_TIMEOUT = 60
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                d0 = await _register(session, base, "dev0")
                admin = await session.ws_connect(base + "/ws/admin")
                assert await _recv_type(admin, "DEVICE_LIST") is not None

                await admin.send_json({"type": "RETIRE_DEVICE", "target_devices": ["dev0"]})
                cmd = await _recv_type(d0, "EXECUTE_SELF_UNINSTALL")
                await d0.send_json({
                    "type": "SELF_UNINSTALL_STARTING",
                    "correlation_id": cmd["correlation_id"],
                    "package_name": CLIENT_PKG, "version_code": 6,
                })
                # ToBService refused the uninstall: the client survives to report it.
                await d0.send_json({
                    "type": "SELF_UNINSTALL_RESULT",
                    "correlation_id": cmd["correlation_id"],
                    "status": "fail",
                    "detail": "pbsControlAPPManger returned 2: Unauthorized",
                    "result_code": 2,
                })

                result = await _recv_type(admin, "RETIRE_RESULT")
                assert result["status"] == "fail"
                assert "Unauthorized" in result["detail"]
                assert "dev0" not in server.pending_retires
                assert server.device_registry["dev0"].get("retired") is not True

                await d0.close()
                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_retire_announce_supersedes_pending_self_update(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        server.RETIRE_TIMEOUT = 60
        server.SELF_UPDATE_TIMEOUT = 60
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                d0 = await _register(session, base, "dev0")
                admin = await session.ws_connect(base + "/ws/admin")
                assert await _recv_type(admin, "DEVICE_LIST") is not None

                # A self-update is announced but never completes...
                await d0.send_json({
                    "type": "SELF_UPDATE_STARTING", "correlation_id": "c-up",
                    "target_version_code": 7, "current_version_code": 6,
                    "package_name": CLIENT_PKG, "apk_filename": "x.apk",
                })
                assert await _recv_type(admin, "INSTALL_DEVICE_STATE") is not None
                assert "dev0" in server.pending_self_updates

                # ...and a retire supersedes it: the self-update entry (and its
                # timeout) must go, or it would later fire a spurious result.
                update_timeout = server.pending_self_updates["dev0"]["timeout_task"]
                await d0.send_json({
                    "type": "SELF_UNINSTALL_STARTING", "correlation_id": "c-ret",
                    "package_name": CLIENT_PKG, "version_code": 6,
                })
                await d0.close()
                dl = await _recv_type(admin, "DEVICE_LIST")
                assert _row(dl, "dev0")["status"] == "retiring"
                assert "dev0" not in server.pending_self_updates
                assert update_timeout.cancelled()

                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())
