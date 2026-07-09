"""Tests for throttled APK install fan-out (issue #35).

Two layers:

* Unit tests drive ``_run_install_job`` / ``release_transfer_slot`` directly with
  in-memory fake WebSockets. They are deterministic and use no wall-clock sleeps —
  the event loop is advanced with ``asyncio.sleep(0)`` and the per-device timeout
  is exercised with ``timeout == 0`` (immediate release), never a real delay.
* Integration tests run the real aiohttp app over loopback with genuine device and
  admin WebSocket connections, proving the message-handler wiring: DOWNLOAD_COMPLETE
  (primary), INSTALL_RESULT (older-client fallback), and disconnect all free a slot.
"""

from __future__ import annotations

import asyncio
import json

import aiohttp
import pytest
from aiohttp.test_utils import TestServer

from styly_mdm import server


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
    """Isolate the module-global server state each test touches."""
    saved_n = server.MAX_CONCURRENT_TRANSFERS
    saved_t = server.TRANSFER_TIMEOUT
    for coll in (server.devices, server.admin_connections,
                 server.pending_transfers, server._install_tasks):
        coll.clear()
    yield
    for coll in (server.devices, server.admin_connections,
                 server.pending_transfers, server._install_tasks):
        coll.clear()
    server.MAX_CONCURRENT_TRANSFERS = saved_n
    server.TRANSFER_TIMEOUT = saved_t


def add_device(device_id: str) -> FakeWS:
    ws = FakeWS()
    server.devices[device_id] = {
        "ws": ws, "device_id": device_id, "model": "M",
        "ip": "1.1.1.1", "status": "online", "startup_app": None, "battery": None,
    }
    return ws


def execute_count(ws: FakeWS) -> int:
    return sum(1 for m in ws.sent if json.loads(m).get("type") == "EXECUTE_INSTALL")


async def settle(n: int = 40) -> None:
    """Advance the event loop so queued continuations run (no real time passes)."""
    for _ in range(n):
        await asyncio.sleep(0)


def last_progress(admin: FakeWS) -> dict | None:
    progress = [json.loads(m) for m in admin.sent if json.loads(m).get("type") == "INSTALL_PROGRESS"]
    return progress[-1] if progress else None


def install_states(admin: FakeWS) -> list[tuple[tuple[str, ...], str]]:
    """Every INSTALL_DEVICE_STATE an admin saw, as (device_ids, state), in order."""
    out = []
    for m in admin.sent:
        data = json.loads(m)
        if data.get("type") == "INSTALL_DEVICE_STATE":
            out.append((tuple(data["device_ids"]), data["state"]))
    return out


def state_of(admin: FakeWS, device_id: str) -> str | None:
    """The last state broadcast for a device — i.e. what its INSTALL cell shows."""
    latest = None
    for ids, state in install_states(admin):
        if device_id in ids:
            latest = state
    return latest


# ---------------------------------------------------------------------------
# Unit tests — bounded dispatch, release, timeout, offline
# ---------------------------------------------------------------------------

def test_never_exceeds_max_in_flight():
    async def body():
        server.MAX_CONCURRENT_TRANSFERS = 2
        server.TRANSFER_TIMEOUT = 60  # long: slots stay held until we release them
        admin = FakeWS(); server.admin_connections.add(admin)
        targets = ["d0", "d1", "d2", "d3", "d4"]
        wss = {d: add_device(d) for d in targets}

        task = asyncio.create_task(server._run_install_job("http://x/a.apk", "a.apk", targets))
        await settle()

        # Only N devices dispatched; the rest are queued waiting for a slot.
        assert sum(execute_count(w) for w in wss.values()) == 2
        assert len(server.pending_transfers) == 2

        # Releasing one slot dispatches exactly one more (still N in flight).
        held = list(server.pending_transfers.keys())
        server.release_transfer_slot(held[0], "download_complete")
        await settle()
        assert sum(execute_count(w) for w in wss.values()) == 3
        assert len(server.pending_transfers) == 2

        # Drain the rest; every device is eventually dispatched exactly once.
        while server.pending_transfers:
            server.release_transfer_slot(next(iter(server.pending_transfers)), "download_complete")
            await settle()
        await asyncio.wait_for(task, 1)

        assert all(execute_count(w) == 1 for w in wss.values())
        assert server.pending_transfers == {}
        prog = last_progress(admin)
        assert prog["done"] is True
        assert prog["transferred"] == 5 and prog["failed"] == 0

    asyncio.run(body())


def test_release_dispatches_next_queued_device():
    async def body():
        server.MAX_CONCURRENT_TRANSFERS = 1
        server.TRANSFER_TIMEOUT = 60
        a = add_device("a"); b = add_device("b")

        task = asyncio.create_task(server._run_install_job("http://x/a.apk", "a.apk", ["a", "b"]))
        await settle()
        assert execute_count(a) == 1 and execute_count(b) == 0  # b gated behind a

        server.release_transfer_slot("a", "download_complete")
        await settle()
        assert execute_count(b) == 1  # slot freed -> b dispatched

        server.release_transfer_slot("b", "download_complete")
        await asyncio.wait_for(task, 1)

    asyncio.run(body())


def test_timeout_releases_slot_without_wall_clock():
    async def body():
        server.MAX_CONCURRENT_TRANSFERS = 1
        server.TRANSFER_TIMEOUT = 0  # a held slot times out immediately
        admin = FakeWS(); server.admin_connections.add(admin)
        wss = {d: add_device(d) for d in ["a", "b", "c"]}

        # No device ever reports completion; each slot must free itself on timeout,
        # so the whole queue still drains and every device is dispatched.
        task = asyncio.create_task(server._run_install_job("http://x/a.apk", "a.apk", ["a", "b", "c"]))
        await asyncio.wait_for(task, 2)

        assert all(execute_count(w) == 1 for w in wss.values())
        assert server.pending_transfers == {}
        prog = last_progress(admin)
        assert prog["done"] is True
        assert prog["failed"] == 3 and prog["transferred"] == 0

    asyncio.run(body())


def test_offline_device_does_not_hold_a_slot():
    async def body():
        server.MAX_CONCURRENT_TRANSFERS = 1
        server.TRANSFER_TIMEOUT = 60
        # "ghost" is a resolved target that is not actually connected.
        online = add_device("online")

        task = asyncio.create_task(
            server._run_install_job("http://x/a.apk", "a.apk", ["ghost", "online"])
        )
        await settle()
        # The offline target is skipped (no slot held), so the online device is
        # dispatched even though it is behind "ghost" in the queue with N == 1.
        assert execute_count(online) == 1
        assert "ghost" not in server.pending_transfers

        server.release_transfer_slot("online", "download_complete")
        await asyncio.wait_for(task, 1)

    asyncio.run(body())


# ---------------------------------------------------------------------------
# Unit tests — per-device state broadcasts (INSTALL_DEVICE_STATE)
# ---------------------------------------------------------------------------

def test_per_device_states_follow_the_transfer_queue():
    async def body():
        server.MAX_CONCURRENT_TRANSFERS = 1
        server.TRANSFER_TIMEOUT = 60
        admin = FakeWS(); server.admin_connections.add(admin)
        add_device("a"); add_device("b")

        task = asyncio.create_task(server._run_install_job("http://x/a.apk", "a.apk", ["a", "b"]))
        await settle()

        # The job opens by naming every target as queued, in one batched message.
        assert install_states(admin)[0] == (("a", "b"), "queued")
        # With N == 1 only the slot holder transfers; "b" must not look installing.
        assert state_of(admin, "a") == "transferring"
        assert state_of(admin, "b") == "queued"

        server.release_transfer_slot("a", "download_complete")
        await settle()
        assert state_of(admin, "b") == "transferring"
        # "installing" belongs to the DOWNLOAD_COMPLETE handler; the dispatcher must
        # stay silent after its slot frees or it would race the terminal result.
        assert state_of(admin, "a") == "transferring"

        server.release_transfer_slot("b", "download_complete")
        await asyncio.wait_for(task, 1)

    asyncio.run(body())


def test_timeout_marks_that_device_failed():
    async def body():
        server.MAX_CONCURRENT_TRANSFERS = 1
        server.TRANSFER_TIMEOUT = 0  # a held slot times out immediately
        admin = FakeWS(); server.admin_connections.add(admin)
        add_device("a")

        task = asyncio.create_task(server._run_install_job("http://x/a.apk", "a.apk", ["a"]))
        await asyncio.wait_for(task, 2)

        assert state_of(admin, "a") == "fail"

    asyncio.run(body())


def test_offline_target_is_reported_as_failed_not_left_queued():
    async def body():
        server.MAX_CONCURRENT_TRANSFERS = 1
        server.TRANSFER_TIMEOUT = 60
        admin = FakeWS(); server.admin_connections.add(admin)
        add_device("online")  # "ghost" is a target that is not actually connected

        task = asyncio.create_task(
            server._run_install_job("http://x/a.apk", "a.apk", ["ghost", "online"])
        )
        await settle()
        # A device that never gets an EXECUTE_INSTALL must not sit on "Waiting…".
        assert state_of(admin, "ghost") == "fail"

        server.release_transfer_slot("online", "download_complete")
        await asyncio.wait_for(task, 1)

    asyncio.run(body())


def test_release_transfer_slot_reports_whether_it_freed_a_slot():
    async def body():
        assert server.release_transfer_slot("nobody", "download_complete") is False

        fut = asyncio.get_running_loop().create_future()
        server.pending_transfers["a"] = fut
        assert server.release_transfer_slot("a", "download_complete") is True
        # The second call is the guard that stops a DOWNLOAD_COMPLETE arriving after
        # a timeout from resurrecting an "installing" cell for a written-off device.
        assert server.release_transfer_slot("a", "download_complete") is False

    asyncio.run(body())


def test_handle_install_apk_acknowledges_and_spawns_job():
    async def body():
        server.MAX_CONCURRENT_TRANSFERS = 3
        server.TRANSFER_TIMEOUT = 60
        add_device("a")
        admin = FakeWS()

        await server.handle_install_apk(admin, {
            "target_devices": ["a"], "apk_url": "http://x/a.apk", "apk_filename": "a.apk",
        })
        # Immediate INSTALL_SENT ack carrying the throttle limit; a job task spawned.
        sent = [json.loads(m) for m in admin.sent]
        ack = next(m for m in sent if m["type"] == "INSTALL_SENT")
        assert ack["target_count"] == 1 and ack["max_concurrent"] == 3
        assert len(server._install_tasks) == 1

        server.release_transfer_slot("a", "download_complete")
        await settle()

    asyncio.run(body())


def test_handle_install_apk_rejects_missing_url_and_no_targets():
    async def body():
        admin = FakeWS()
        await server.handle_install_apk(admin, {"target_devices": ["a"], "apk_url": ""})
        await server.handle_install_apk(admin, {"target_devices": ["nope"], "apk_url": "http://x/a.apk"})
        errors = [json.loads(m) for m in admin.sent if json.loads(m)["type"] == "ERROR"]
        assert len(errors) == 2
        assert not server._install_tasks  # nothing dispatched

    asyncio.run(body())


# ---------------------------------------------------------------------------
# Integration tests — real WebSockets prove the handler wiring
# ---------------------------------------------------------------------------

async def _recv_execute(ws, timeout: float = 2.0) -> dict | None:
    """Return the next EXECUTE_INSTALL a device receives, or None on timeout."""
    try:
        while True:
            msg = await asyncio.wait_for(ws.receive(), timeout)
            if msg.type == aiohttp.WSMsgType.TEXT:
                data = json.loads(msg.data)
                if data.get("type") == "EXECUTE_INSTALL":
                    return data
            elif msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.CLOSING,
                              aiohttp.WSMsgType.ERROR):
                return None
    except asyncio.TimeoutError:
        return None


async def _wait_online(admin, expected: set[str], timeout: float = 2.0) -> None:
    """Read admin frames until DEVICE_LIST shows all expected devices online."""
    async def poll():
        while True:
            msg = await admin.receive()
            if msg.type != aiohttp.WSMsgType.TEXT:
                continue
            data = json.loads(msg.data)
            if data.get("type") == "DEVICE_LIST":
                online = {d["device_id"] for d in data["devices"] if d["status"] == "online"}
                if expected <= online:
                    return
    await asyncio.wait_for(poll(), timeout)


async def _register(session, base: str, device_id: str):
    ws = await session.ws_connect(base + "/ws/device")
    await ws.send_json({"type": "REGISTER", "device_id": device_id, "model": "M", "ip": "1.1.1.2"})
    return ws


def test_e2e_download_complete_and_install_result_release(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        server.MAX_CONCURRENT_TRANSFERS = 1
        server.TRANSFER_TIMEOUT = 60
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                d0 = await _register(session, base, "dev0")
                d1 = await _register(session, base, "dev1")
                d2 = await _register(session, base, "dev2")
                admin = await session.ws_connect(base + "/ws/admin")
                await _wait_online(admin, {"dev0", "dev1", "dev2"})

                await admin.send_json({
                    "type": "INSTALL_APK",
                    "target_devices": ["dev0", "dev1", "dev2"],
                    "apk_url": base + "/apks/x.apk",
                    "apk_filename": "x.apk",
                })

                # N == 1: only dev0 downloads; dev1/dev2 are gated.
                assert await _recv_execute(d0) is not None
                assert await _recv_execute(d1, timeout=0.3) is None

                # Primary release: dev0 reports DOWNLOAD_COMPLETE -> dev1 dispatched.
                await d0.send_json({"type": "DOWNLOAD_COMPLETE", "apk_filename": "x.apk"})
                assert await _recv_execute(d1) is not None
                assert await _recv_execute(d2, timeout=0.3) is None

                # Fallback release: dev1 is an "older client" that only sends
                # INSTALL_RESULT (no DOWNLOAD_COMPLETE) -> dev2 dispatched.
                await d1.send_json({"type": "INSTALL_RESULT", "status": "success", "apk_filename": "x.apk"})
                assert await _recv_execute(d2) is not None

                await d2.send_json({"type": "DOWNLOAD_COMPLETE", "apk_filename": "x.apk"})
                for ws in (d0, d1, d2, admin):
                    await ws.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_installing_state_arrives_before_the_terminal_result(tmp_path):
    """A device's row must reach "installing" and then stop, never the other way.

    The installing state is emitted from the DOWNLOAD_COMPLETE handler precisely so
    it cannot land after the INSTALL_RESULT that follows it on the same device
    connection — if it did, the console would spin on "Installing…" forever.
    """
    async def body():
        server._apply_data_dir(str(tmp_path))
        server.MAX_CONCURRENT_TRANSFERS = 1
        server.TRANSFER_TIMEOUT = 60
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                d0 = await _register(session, base, "dev0")
                admin = await session.ws_connect(base + "/ws/admin")
                await _wait_online(admin, {"dev0"})

                await admin.send_json({
                    "type": "INSTALL_APK",
                    "target_devices": ["dev0"],
                    "apk_url": base + "/apks/x.apk",
                    "apk_filename": "x.apk",
                })
                assert await _recv_execute(d0) is not None

                await d0.send_json({"type": "DOWNLOAD_COMPLETE", "apk_filename": "x.apk"})
                await d0.send_json({"type": "INSTALL_RESULT", "status": "success", "apk_filename": "x.apk"})

                seen: list[str] = []

                async def poll():
                    while True:
                        msg = await admin.receive()
                        if msg.type != aiohttp.WSMsgType.TEXT:
                            continue
                        data = json.loads(msg.data)
                        if data.get("type") == "INSTALL_DEVICE_STATE":
                            seen.append(data["state"])
                        elif data.get("type") == "INSTALL_RESULT":
                            return

                await asyncio.wait_for(poll(), 2)
                assert seen == ["queued", "transferring", "installing"]

                for ws in (d0, admin):
                    await ws.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_disconnect_releases_slot(tmp_path):
    async def body():
        server._apply_data_dir(str(tmp_path))
        server.MAX_CONCURRENT_TRANSFERS = 1
        server.TRANSFER_TIMEOUT = 60
        ts = TestServer(server.create_app())
        await ts.start_server()
        base = f"http://{ts.host}:{ts.port}"
        try:
            async with aiohttp.ClientSession() as session:
                d0 = await _register(session, base, "dev0")
                d1 = await _register(session, base, "dev1")
                admin = await session.ws_connect(base + "/ws/admin")
                await _wait_online(admin, {"dev0", "dev1"})

                await admin.send_json({
                    "type": "INSTALL_APK",
                    "target_devices": ["dev0", "dev1"],
                    "apk_url": base + "/apks/x.apk",
                    "apk_filename": "x.apk",
                })
                assert await _recv_execute(d0) is not None
                assert await _recv_execute(d1, timeout=0.3) is None

                # dev0 drops mid-transfer -> its slot must free so dev1 proceeds.
                await d0.close()
                assert await _recv_execute(d1) is not None

                await d1.send_json({"type": "DOWNLOAD_COMPLETE", "apk_filename": "x.apk"})
                for ws in (d1, admin):
                    await ws.close()
        finally:
            await ts.close()

    asyncio.run(body())
