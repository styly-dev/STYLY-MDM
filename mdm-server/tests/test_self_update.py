"""Tests for the client self-update lifecycle (issue #39).

A client updating *itself* is killed by the package replacement: no INSTALL_RESULT
ever arrives on success, the WebSocket just drops, and the device power-cycles and
re-registers a few minutes later running the new build. The server must therefore

  * ship reference hashes with EXECUTE_INSTALL so the client can verify the
    download before betting its own process on it,
  * treat the disconnect after SELF_UPDATE_STARTING as "updating", not "offline",
  * settle the update when the device re-registers (version_code vs target),
    auto-verify the installed APK, and consume — never forward — the answering
    VERIFY_APK_RESULT, and
  * give up after SELF_UPDATE_TIMEOUT so a genuinely lost device is not shown as
    updating forever.

Structured like test_transfer_pool.py: deterministic unit tests with FakeWS, plus
aiohttp integration tests over loopback that prove the real handler wiring.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import zipfile

import aiohttp
import pytest
from aiohttp.test_utils import TestServer

from styly_mdm import integrity, server


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
    saved_n = server.MAX_CONCURRENT_TRANSFERS
    saved_t = server.TRANSFER_TIMEOUT
    saved_u = server.SELF_UPDATE_TIMEOUT
    saved_v = server.SELF_UPDATE_VERIFY_TIMEOUT
    for coll in (server.devices, server.admin_connections, server.pending_transfers,
                 server._transfer_tasks, server.pending_self_updates,
                 server._apk_hash_cache, server.device_registry):
        coll.clear()
    server.reset_transfer_slots()
    yield
    for coll in (server.devices, server.admin_connections, server.pending_transfers,
                 server._transfer_tasks, server.pending_self_updates,
                 server._apk_hash_cache, server.device_registry):
        coll.clear()
    server.reset_transfer_slots()
    server.MAX_CONCURRENT_TRANSFERS = saved_n
    server.TRANSFER_TIMEOUT = saved_t
    server.SELF_UPDATE_TIMEOUT = saved_u
    server.SELF_UPDATE_VERIFY_TIMEOUT = saved_v


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


def frames_of(ws: FakeWS, msg_type: str) -> list[dict]:
    return [json.loads(m) for m in ws.sent if json.loads(m).get("type") == msg_type]


def make_pending(device_id: str, *, phase: str = "updating", target: int = 7,
                 expected_full: str | None = "ab" * 32,
                 expected_cd: str | None = "cd" * 32) -> dict:
    entry = {
        "phase": phase,
        "correlation_id": "corr-1",
        "target_version_code": target,
        "apk_filename": "x.apk",
        "package_name": CLIENT_PKG,
        "expected_full_sha256": expected_full,
        "expected_cd_sha256": expected_cd,
        "started_at": 0.0,
        "timeout_task": None,
    }
    server.pending_self_updates[device_id] = entry
    return entry


def write_apk(path, payload: bytes = b"content") -> dict:
    """Write a real (tiny) zip and return its reference hashes."""
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("classes.dex", payload)
    data = path.read_bytes()
    return {
        "full_sha256": hashlib.sha256(data).hexdigest(),
        "cd_sha256": integrity.apk_cd_digest(str(path))[1],
    }


async def wait_until(cond, timeout: float = 2.0):
    async def poll():
        while not cond():
            await asyncio.sleep(0.01)
    await asyncio.wait_for(poll(), timeout)


# ---------------------------------------------------------------------------
# Unit tests — reference hashes in EXECUTE_INSTALL
# ---------------------------------------------------------------------------

def test_execute_install_includes_hashes_for_local_apk(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        expected = write_apk(server.APK_DIR / "x.apk")
        ws = add_device("dev0")

        job = asyncio.create_task(
            server._run_install_job("http://x/apks/x.apk", "x.apk", ["dev0"])
        )
        await wait_until(lambda: frames_of(ws, "EXECUTE_INSTALL"))
        msg = frames_of(ws, "EXECUTE_INSTALL")[0]
        assert msg["full_sha256"] == expected["full_sha256"]
        assert msg["cd_sha256"] == expected["cd_sha256"]

        server.release_transfer_slot("dev0", "test", task=server.TASK_INSTALL)
        await job

    asyncio.run(body())


def test_execute_install_omits_hashes_when_file_missing(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        ws = add_device("dev0")

        job = asyncio.create_task(
            server._run_install_job("http://elsewhere/y.apk", "y.apk", ["dev0"])
        )
        await wait_until(lambda: frames_of(ws, "EXECUTE_INSTALL"))
        msg = frames_of(ws, "EXECUTE_INSTALL")[0]
        assert "full_sha256" not in msg
        assert "cd_sha256" not in msg

        server.release_transfer_slot("dev0", "test", task=server.TASK_INSTALL)
        await job

    asyncio.run(body())


def test_apk_hashes_for_rejects_traversal_and_non_zip(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        # Traversal / non-.apk names never resolve to a file.
        assert await server.apk_hashes_for("../../etc/passwd") is None
        assert await server.apk_hashes_for("") is None
        # A file that is not a zip fails to hash rather than raising.
        server.APK_DIR.mkdir(parents=True, exist_ok=True)
        (server.APK_DIR / "junk.apk").write_bytes(b"not a zip at all")
        assert await server.apk_hashes_for("junk.apk") is None

    asyncio.run(body())


def test_hash_cache_hits_and_invalidates_on_change(tmp_path, monkeypatch):
    async def body():
        server._apply_data_dir(str(tmp_path))
        path = server.APK_DIR / "x.apk"
        write_apk(path)

        calls = {"n": 0}
        real = server._compute_apk_hashes

        def counting(p):
            calls["n"] += 1
            return real(p)

        monkeypatch.setattr(server, "_compute_apk_hashes", counting)

        first = await server.apk_hashes_for("x.apk")
        second = await server.apk_hashes_for("x.apk")
        assert first == second
        assert calls["n"] == 1  # cache hit on the second call

        write_apk(path, payload=b"different content")
        os.utime(path, ns=(1, 1))  # force a distinct mtime even on coarse clocks
        third = await server.apk_hashes_for("x.apk")
        assert calls["n"] == 2  # stat identity changed -> recomputed
        assert third != first

    asyncio.run(body())


# ---------------------------------------------------------------------------
# Unit tests — pending lifecycle pieces
# ---------------------------------------------------------------------------

def test_device_list_renders_updating_for_pending_disconnected_device():
    server.device_registry["dev0"] = {
        "label": "HMD-1", "model": "A9210", "ip": "1.1.1.1", "last_seen": 1.0,
        "startup_app": None, "battery": None,
        "version_code": 6, "version_name": "0.2.3",
    }
    make_pending("dev0")

    listed = {d["device_id"]: d for d in json.loads(server.build_device_list_msg())["devices"]}
    assert listed["dev0"]["status"] == "updating"
    assert listed["dev0"]["version_code"] == 6
    assert listed["dev0"]["version_name"] == "0.2.3"

    # Once the pending entry is gone (timeout / settled), the row falls back to offline.
    server.pending_self_updates.clear()
    listed = {d["device_id"]: d for d in json.loads(server.build_device_list_msg())["devices"]}
    assert listed["dev0"]["status"] == "offline"


def test_resolve_success_sends_result_and_auto_verify():
    async def body():
        admin = add_admin()
        ws = add_device("dev0")
        make_pending("dev0", target=7)

        await server._resolve_pending_self_update("dev0", ws, 7)

        results = frames_of(admin, "SELF_UPDATE_RESULT")
        assert len(results) == 1
        assert results[0]["status"] == "success"
        assert results[0]["version_code"] == 7
        assert results[0]["correlation_id"] == "corr-1"
        states = frames_of(admin, "INSTALL_DEVICE_STATE")
        assert states and states[-1]["state"] == "success"
        # Auto-verify was sent to the device, and the entry moved to phase "verifying".
        verifies = frames_of(ws, "EXECUTE_VERIFY_APK")
        assert verifies == [{"type": "EXECUTE_VERIFY_APK", "package_name": CLIENT_PKG}]
        entry = server.pending_self_updates["dev0"]
        assert entry["phase"] == "verifying"
        # A bounded wait for the answer is armed, so a client that never replies
        # cannot pin the entry in "verifying" forever.
        assert entry["timeout_task"] is not None and not entry["timeout_task"].done()
        entry["timeout_task"].cancel()

    asyncio.run(body())


def test_resolve_with_old_version_reports_fail_and_clears():
    async def body():
        admin = add_admin()
        ws = add_device("dev0")
        make_pending("dev0", target=7)

        await server._resolve_pending_self_update("dev0", ws, 6)

        results = frames_of(admin, "SELF_UPDATE_RESULT")
        assert results[0]["status"] == "fail"
        assert "expected >= 7" in results[0]["detail"]
        assert "dev0" not in server.pending_self_updates
        assert not frames_of(ws, "EXECUTE_VERIFY_APK")

    asyncio.run(body())


def test_resolve_without_version_code_reports_fail_with_detail():
    async def body():
        admin = add_admin()
        ws = add_device("dev0")
        make_pending("dev0", target=7)

        await server._resolve_pending_self_update("dev0", ws, None)

        results = frames_of(admin, "SELF_UPDATE_RESULT")
        assert results[0]["status"] == "fail"
        assert results[0]["detail"] == "client reported no version_code"
        assert "dev0" not in server.pending_self_updates

    asyncio.run(body())


def test_resolve_success_without_hashes_skips_verification():
    async def body():
        admin = add_admin()
        ws = add_device("dev0")
        make_pending("dev0", target=7, expected_full=None, expected_cd=None)

        await server._resolve_pending_self_update("dev0", ws, 7)

        verified = frames_of(admin, "SELF_UPDATE_VERIFIED")
        assert verified[0]["status"] == "skipped"
        assert "dev0" not in server.pending_self_updates
        assert not frames_of(ws, "EXECUTE_VERIFY_APK")

    asyncio.run(body())


def test_consume_verify_match_and_mismatch():
    async def body():
        admin = add_admin()
        expected = "ab" * 32
        entry = make_pending("dev0", phase="verifying", expected_full=expected)

        consumed = await server._consume_self_update_verify("dev0", {
            "type": "VERIFY_APK_RESULT", "package_name": CLIENT_PKG,
            "found": True, "full_sha256": expected.upper(),
        })
        assert consumed
        assert frames_of(admin, "SELF_UPDATE_VERIFIED")[0]["status"] == "verified"
        assert "dev0" not in server.pending_self_updates

        # Mismatch path.
        admin.sent.clear()
        server.pending_self_updates["dev0"] = dict(entry)
        consumed = await server._consume_self_update_verify("dev0", {
            "type": "VERIFY_APK_RESULT", "package_name": CLIENT_PKG,
            "found": True, "full_sha256": "ff" * 32,
        })
        assert consumed
        assert frames_of(admin, "SELF_UPDATE_VERIFIED")[0]["status"] == "mismatch"

    asyncio.run(body())


def test_consume_verify_ignores_unrelated_results():
    async def body():
        add_admin()
        make_pending("dev0", phase="verifying")

        # Different package: an admin-initiated verify racing the auto-verify
        # must be forwarded, not swallowed.
        assert not await server._consume_self_update_verify("dev0", {
            "type": "VERIFY_APK_RESULT", "package_name": "com.other.app",
            "found": True, "full_sha256": "ab" * 32,
        })
        # Phase "updating" (no verify outstanding): not ours either.
        server.pending_self_updates["dev0"]["phase"] = "updating"
        assert not await server._consume_self_update_verify("dev0", {
            "type": "VERIFY_APK_RESULT", "package_name": CLIENT_PKG,
            "found": True, "full_sha256": "ab" * 32,
        })

    asyncio.run(body())


def test_consume_verify_cancels_the_verify_timeout():
    async def body():
        add_admin()
        entry = make_pending("dev0", phase="verifying", expected_full="ab" * 32)
        entry["timeout_task"] = asyncio.create_task(
            server._self_update_verify_timeout("dev0", "corr-1")
        )
        await asyncio.sleep(0)

        consumed = await server._consume_self_update_verify("dev0", {
            "type": "VERIFY_APK_RESULT", "package_name": CLIENT_PKG,
            "found": True, "full_sha256": "AB" * 32,
        })
        assert consumed
        await asyncio.sleep(0)
        # The answer arrived, so the pending timeout must be cancelled — otherwise it
        # would later fire a spurious "verify timed out" for a settled update.
        assert entry["timeout_task"].cancelled()

    asyncio.run(body())


def test_verify_timeout_reports_error_and_clears():
    async def body():
        server.SELF_UPDATE_VERIFY_TIMEOUT = 0.01
        admin = add_admin()
        make_pending("dev0", phase="verifying")

        await server._self_update_verify_timeout("dev0", "corr-1")

        assert "dev0" not in server.pending_self_updates
        verified = frames_of(admin, "SELF_UPDATE_VERIFIED")
        assert verified and verified[0]["status"] == "error"
        assert "did not answer" in verified[0]["detail"]

    asyncio.run(body())


def test_stale_verify_timeout_does_not_touch_a_replaced_pending():
    async def body():
        server.SELF_UPDATE_VERIFY_TIMEOUT = 0.01
        admin = add_admin()
        # The entry now belongs to a newer correlation id: the old verify timeout
        # must leave it alone.
        make_pending("dev0", phase="verifying")
        server.pending_self_updates["dev0"]["correlation_id"] = "corr-2"

        await server._self_update_verify_timeout("dev0", "corr-1")

        assert "dev0" in server.pending_self_updates
        assert not frames_of(admin, "SELF_UPDATE_VERIFIED")

    asyncio.run(body())


def test_install_result_clears_pending_and_cancels_timeout():
    async def body():
        entry = make_pending("dev0")
        entry["timeout_task"] = asyncio.create_task(
            server._self_update_timeout("dev0", "corr-1")
        )
        await asyncio.sleep(0)

        server._clear_pending_self_update_on_install_result("dev0")
        assert "dev0" not in server.pending_self_updates
        await asyncio.sleep(0)
        assert entry["timeout_task"].cancelled()

        # A device with no pending entry is a no-op.
        server._clear_pending_self_update_on_install_result("dev1")

    asyncio.run(body())


def test_self_update_timeout_reports_and_falls_back_to_offline():
    async def body():
        server.SELF_UPDATE_TIMEOUT = 0.01
        admin = add_admin()
        server.device_registry["dev0"] = {
            "label": "", "model": "M", "ip": "", "last_seen": 1.0,
            "startup_app": None, "battery": None,
            "version_code": 6, "version_name": "",
        }
        make_pending("dev0")

        await server._self_update_timeout("dev0", "corr-1")

        assert "dev0" not in server.pending_self_updates
        results = frames_of(admin, "SELF_UPDATE_RESULT")
        assert results[0]["status"] == "timeout"
        assert results[0]["version_code"] is None
        states = frames_of(admin, "INSTALL_DEVICE_STATE")
        assert states and states[-1]["state"] == "fail"
        lists = frames_of(admin, "DEVICE_LIST")
        assert lists and lists[-1]["devices"][0]["status"] == "offline"

    asyncio.run(body())


def test_stale_timeout_does_not_touch_a_replaced_pending():
    async def body():
        server.SELF_UPDATE_TIMEOUT = 0.01
        admin = add_admin()
        # The entry now belongs to a newer correlation id: the old timeout must not fire.
        make_pending("dev0")
        server.pending_self_updates["dev0"]["correlation_id"] = "corr-2"

        await server._self_update_timeout("dev0", "corr-1")

        assert "dev0" in server.pending_self_updates
        assert not frames_of(admin, "SELF_UPDATE_RESULT")

    asyncio.run(body())


def test_coerce_record_roundtrips_version_fields():
    rec = server._coerce_record({
        "label": "L", "model": "M", "ip": "1.1.1.1", "last_seen": 1.0,
        "startup_app": None, "battery": None,
        "version_code": 7, "version_name": "0.3.0",
    })
    assert rec["version_code"] == 7
    assert rec["version_name"] == "0.3.0"
    # Junk shapes normalize instead of leaking.
    rec = server._coerce_record({"label": "L", "version_code": True, "version_name": 5})
    assert rec["version_code"] is None
    assert rec["version_name"] == ""
    # Legacy flat records gain the fields.
    rec = server._coerce_record("just-a-label")
    assert rec["version_code"] is None
    assert rec["version_name"] == ""


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


async def _recv_type_recording(ws, msg_type: str, seen: list[dict], timeout: float = 2.0) -> dict:
    """Like _recv_type but records every frame passed over; raises on timeout."""
    async def poll():
        while True:
            msg = await ws.receive()
            if msg.type != aiohttp.WSMsgType.TEXT:
                continue
            data = json.loads(msg.data)
            seen.append(data)
            if data.get("type") == msg_type:
                return data
    return await asyncio.wait_for(poll(), timeout)


async def _register(session, base: str, device_id: str, version_code: int):
    ws = await session.ws_connect(base + "/ws/device")
    await ws.send_json({
        "type": "REGISTER", "device_id": device_id, "model": "M",
        "ip": "1.1.1.2", "version_code": version_code, "version_name": "t",
    })
    return ws


def test_e2e_self_update_happy_path(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        server.MAX_CONCURRENT_TRANSFERS = 5
        server.TRANSFER_TIMEOUT = 60
        expected = write_apk(server.APK_DIR / "x.apk")
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                d0 = await _register(session, base, "dev0", version_code=6)
                admin = await session.ws_connect(base + "/ws/admin")
                assert await _recv_type(admin, "DEVICE_LIST") is not None

                await admin.send_json({
                    "type": "INSTALL_APK", "target_devices": ["dev0"],
                    "apk_url": base + "/apks/x.apk", "apk_filename": "x.apk",
                })
                execute = await _recv_type(d0, "EXECUTE_INSTALL")
                assert execute["full_sha256"] == expected["full_sha256"]
                assert execute["cd_sha256"] == expected["cd_sha256"]

                # The client's last words, in on-device order, then the kill (close).
                await d0.send_json({"type": "DOWNLOAD_COMPLETE", "apk_filename": "x.apk"})
                await d0.send_json({
                    "type": "SELF_UPDATE_STARTING", "correlation_id": "c-1",
                    "target_version_code": 7, "current_version_code": 6,
                    "package_name": CLIENT_PKG, "apk_filename": "x.apk",
                })
                seen: list[dict] = []
                state = await _recv_type_recording(admin, "INSTALL_DEVICE_STATE", seen)
                while state["state"] != "updating":
                    state = await _recv_type_recording(admin, "INSTALL_DEVICE_STATE", seen)
                await d0.close()

                # The disconnect renders the row as updating, not offline.
                dl = await _recv_type(admin, "DEVICE_LIST")
                row = next(d for d in dl["devices"] if d["device_id"] == "dev0")
                assert row["status"] == "updating"

                # The new build re-registers with the target versionCode.
                d0b = await _register(session, base, "dev0", version_code=7)
                seen = []
                result = await _recv_type_recording(admin, "SELF_UPDATE_RESULT", seen)
                assert result["status"] == "success"
                assert result["correlation_id"] == "c-1"
                assert result["version_code"] == 7

                # The server auto-verifies and consumes the raw result.
                verify_cmd = await _recv_type(d0b, "EXECUTE_VERIFY_APK")
                assert verify_cmd == {"type": "EXECUTE_VERIFY_APK", "package_name": CLIENT_PKG}
                await d0b.send_json({
                    "type": "VERIFY_APK_RESULT", "package_name": CLIENT_PKG,
                    "found": True, "full_sha256": expected["full_sha256"],
                    "cd_sha256": expected["cd_sha256"],
                })
                verified = await _recv_type_recording(admin, "SELF_UPDATE_VERIFIED", seen)
                assert verified["status"] == "verified"
                assert all(f.get("type") != "VERIFY_APK_RESULT" for f in seen), \
                    "raw VERIFY_APK_RESULT must be consumed, not forwarded"
                # The row is back online with the new version.
                row = next(
                    d for d in [f for f in seen if f.get("type") == "DEVICE_LIST"][-1]["devices"]
                    if d["device_id"] == "dev0"
                )
                assert row["status"] == "online"
                assert row["version_code"] == 7

                await d0b.close()
                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_second_self_update_starting_replaces_pending(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                d0 = await _register(session, base, "dev0", version_code=6)
                admin = await session.ws_connect(base + "/ws/admin")
                assert await _recv_type(admin, "DEVICE_LIST") is not None

                for corr, target in (("c-1", 7), ("c-2", 8)):
                    await d0.send_json({
                        "type": "SELF_UPDATE_STARTING", "correlation_id": corr,
                        "target_version_code": target, "current_version_code": 6,
                        "package_name": CLIENT_PKG, "apk_filename": "x.apk",
                    })
                # Both broadcasts arrive; the surviving entry is the second.
                assert await _recv_type(admin, "INSTALL_DEVICE_STATE") is not None
                assert await _recv_type(admin, "INSTALL_DEVICE_STATE") is not None
                pend = server.pending_self_updates["dev0"]
                assert pend["correlation_id"] == "c-2"
                assert pend["target_version_code"] == 8

                # Re-register with only the *first* target's version: the surviving
                # (second) update reports fail — exactly one result, for c-2.
                await d0.close()
                d0b = await _register(session, base, "dev0", version_code=7)
                result = await _recv_type(admin, "SELF_UPDATE_RESULT")
                assert result["correlation_id"] == "c-2"
                assert result["status"] == "fail"
                assert await _recv_type(admin, "SELF_UPDATE_RESULT", timeout=0.3) is None

                await d0b.close()
                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_disconnect_mid_update_still_releases_transfer_slot(tmp_path):
    """The updating state and the slot release coexist on disconnect."""
    async def body():
        server._apply_data_dir(str(tmp_path))
        server.MAX_CONCURRENT_TRANSFERS = 1
        server.TRANSFER_TIMEOUT = 60
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                d0 = await _register(session, base, "dev0", version_code=6)
                d1 = await _register(session, base, "dev1", version_code=6)
                admin = await session.ws_connect(base + "/ws/admin")
                assert await _recv_type(admin, "DEVICE_LIST") is not None

                await admin.send_json({
                    "type": "INSTALL_APK", "target_devices": ["dev0", "dev1"],
                    "apk_url": base + "/apks/x.apk", "apk_filename": "x.apk",
                })
                assert await _recv_type(d0, "EXECUTE_INSTALL") is not None
                # dev0 holds the only slot; dev1 is gated.
                assert await _recv_type(d1, "EXECUTE_INSTALL", timeout=0.3) is None

                # dev0 announces a self-update and dies *without* DOWNLOAD_COMPLETE:
                # the disconnect must both free the slot (dev1 dispatches) and leave
                # the row updating.
                await d0.send_json({
                    "type": "SELF_UPDATE_STARTING", "correlation_id": "c-1",
                    "target_version_code": 7, "current_version_code": 6,
                    "package_name": CLIENT_PKG, "apk_filename": "x.apk",
                })
                await d0.close()

                assert await _recv_type(d1, "EXECUTE_INSTALL") is not None
                dl = await _recv_type(admin, "DEVICE_LIST")
                row = next(d for d in dl["devices"] if d["device_id"] == "dev0")
                assert row["status"] == "updating"

                # Settle the pending update so no armed timeout task outlives the loop.
                d0b = await _register(session, base, "dev0", version_code=7)
                assert (await _recv_type(admin, "SELF_UPDATE_RESULT"))["status"] == "success"

                for ws in (d0b, d1, admin):
                    await ws.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_timeout_flips_updating_to_offline(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        server.SELF_UPDATE_TIMEOUT = 0.15
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                d0 = await _register(session, base, "dev0", version_code=6)
                admin = await session.ws_connect(base + "/ws/admin")
                assert await _recv_type(admin, "DEVICE_LIST") is not None

                await d0.send_json({
                    "type": "SELF_UPDATE_STARTING", "correlation_id": "c-1",
                    "target_version_code": 7, "current_version_code": 6,
                    "package_name": CLIENT_PKG, "apk_filename": "x.apk",
                })
                await d0.close()
                dl = await _recv_type(admin, "DEVICE_LIST")
                assert dl["devices"][0]["status"] == "updating"

                result = await _recv_type(admin, "SELF_UPDATE_RESULT")
                assert result["status"] == "timeout"
                dl = await _recv_type(admin, "DEVICE_LIST")
                assert dl["devices"][0]["status"] == "offline"

                await admin.close()
        finally:
            await ts.close()

    asyncio.run(body())
