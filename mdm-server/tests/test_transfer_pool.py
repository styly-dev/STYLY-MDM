"""Tests for the server-wide transfer-slot pool (issue #44).

`#35` throttled the APK install fan-out with a semaphore local to each install job.
That left `PUSH_FILES` — which moves bundles just as large — completely unthrottled,
and left the throttle itself scoped to the install *action* rather than to the
transfer *resource*: two overlapping install jobs each allowed N transfers, and an
install and a push could never see each other's in-flight bytes.

These tests pin the fix from the outside: **at most N downloads are live server-wide,
whatever mix of jobs asks for them.**

Structured like `test_install_throttle.py`: deterministic unit tests with in-memory
fake WebSockets (no wall-clock sleeps — the loop is advanced with `asyncio.sleep(0)`
and the timeout is exercised with `timeout == 0`), plus aiohttp integration tests over
loopback that prove the real message-handler wiring.
"""

from __future__ import annotations

import asyncio
import json

import aiohttp
import pytest
from aiohttp.test_utils import TestServer

from styly_mdm import server


BUNDLE_URL = "http://x/bundles/b.zip"
DEST = "/sdcard/STYLY/content"


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
    for coll in (server.devices, server.admin_connections,
                 server.pending_transfers, server._transfer_tasks):
        coll.clear()
    server.reset_transfer_slots()
    yield
    for coll in (server.devices, server.admin_connections,
                 server.pending_transfers, server._transfer_tasks):
        coll.clear()
    server.reset_transfer_slots()
    server.MAX_CONCURRENT_TRANSFERS = saved_n
    server.TRANSFER_TIMEOUT = saved_t


def add_device(device_id: str) -> FakeWS:
    ws = FakeWS()
    server.devices[device_id] = {
        "ws": ws, "device_id": device_id, "model": "M",
        "ip": "1.1.1.1", "status": "online", "startup_app": None, "battery": None,
    }
    return ws


def count_of(ws: FakeWS, msg_type: str) -> int:
    return sum(1 for m in ws.sent if json.loads(m).get("type") == msg_type)


def push_count(ws: FakeWS) -> int:
    return count_of(ws, "EXECUTE_PUSH_FILES")


def install_count(ws: FakeWS) -> int:
    return count_of(ws, "EXECUTE_INSTALL")


async def settle(n: int = 40) -> None:
    """Advance the event loop so queued continuations run (no real time passes)."""
    for _ in range(n):
        await asyncio.sleep(0)


def run_push(targets, delete_extras: bool = False):
    return asyncio.create_task(
        server._run_push_job(BUNDLE_URL, "b.zip", DEST, delete_extras, list(targets))
    )


def run_install(targets):
    return asyncio.create_task(
        server._run_install_job("http://x/a.apk", "a.apk", list(targets))
    )


def last_progress(admin: FakeWS, msg_type: str) -> dict | None:
    seen = [json.loads(m) for m in admin.sent if json.loads(m).get("type") == msg_type]
    return seen[-1] if seen else None


def push_state_of(admin: FakeWS, device_id: str) -> str | None:
    """The last PUSH_DEVICE_STATE broadcast for a device — what its PROGRESS cell shows."""
    latest = None
    for m in admin.sent:
        data = json.loads(m)
        if data.get("type") == "PUSH_DEVICE_STATE" and device_id in data["device_ids"]:
            latest = data["state"]
    return latest


def release_push(device_id: str) -> bool:
    return server.release_transfer_slot(device_id, "download_complete", task=server.TASK_PUSH)


def release_install(device_id: str) -> bool:
    return server.release_transfer_slot(device_id, "download_complete", task=server.TASK_INSTALL)


# ---------------------------------------------------------------------------
# Unit tests — the push fan-out is throttled at all
# ---------------------------------------------------------------------------

def test_push_never_exceeds_max_in_flight():
    async def body():
        server.MAX_CONCURRENT_TRANSFERS = 2
        server.TRANSFER_TIMEOUT = 60  # long: slots stay held until we release them
        targets = ["d0", "d1", "d2", "d3", "d4"]
        wss = {d: add_device(d) for d in targets}

        task = run_push(targets)
        await settle()

        # Only N devices pull the bundle; the rest wait for a slot. This is the
        # regression the issue reported: every target used to be dispatched at once.
        assert sum(push_count(w) for w in wss.values()) == 2
        assert len(server.pending_transfers) == 2

        while server.pending_transfers:
            device_id, _task = next(iter(server.pending_transfers))
            release_push(device_id)
            await settle()
        await asyncio.wait_for(task, 1)

        assert all(push_count(w) == 1 for w in wss.values())
        assert server.pending_transfers == {}

    asyncio.run(body())


def test_push_release_dispatches_next_queued_device():
    async def body():
        server.MAX_CONCURRENT_TRANSFERS = 1
        server.TRANSFER_TIMEOUT = 60
        a = add_device("a"); b = add_device("b")

        task = run_push(["a", "b"])
        await settle()
        assert push_count(a) == 1 and push_count(b) == 0  # b gated behind a

        release_push("a")
        await settle()
        assert push_count(b) == 1

        release_push("b")
        await asyncio.wait_for(task, 1)

    asyncio.run(body())


def test_push_timeout_releases_slot_without_wall_clock():
    async def body():
        server.MAX_CONCURRENT_TRANSFERS = 1
        server.TRANSFER_TIMEOUT = 0  # a held slot times out immediately
        admin = FakeWS(); server.admin_connections.add(admin)
        wss = {d: add_device(d) for d in ["a", "b", "c"]}

        # No device ever reports completion; each slot must free itself on timeout so
        # a stuck device cannot wedge the queue.
        task = run_push(["a", "b", "c"])
        await asyncio.wait_for(task, 2)

        assert all(push_count(w) == 1 for w in wss.values())
        assert server.pending_transfers == {}
        prog = last_progress(admin, "PUSH_PROGRESS")
        assert prog["done"] is True
        assert prog["failed"] == 3 and prog["transferred"] == 0

    asyncio.run(body())


def test_push_offline_target_is_reported_as_failed_not_left_queued():
    async def body():
        server.MAX_CONCURRENT_TRANSFERS = 1
        server.TRANSFER_TIMEOUT = 60
        admin = FakeWS(); server.admin_connections.add(admin)
        online = add_device("online")  # "ghost" is a target that is not connected

        task = run_push(["ghost", "online"])
        await settle()
        # The offline target holds no slot, so "online" is dispatched even though it
        # queued behind "ghost" with N == 1 — and "ghost" must not sit on "Waiting…".
        assert push_count(online) == 1
        assert ("ghost", server.TASK_PUSH) not in server.pending_transfers
        assert push_state_of(admin, "ghost") == "fail"

        release_push("online")
        await asyncio.wait_for(task, 1)

    asyncio.run(body())


def test_push_per_device_states_follow_the_transfer_queue():
    async def body():
        server.MAX_CONCURRENT_TRANSFERS = 1
        server.TRANSFER_TIMEOUT = 60
        admin = FakeWS(); server.admin_connections.add(admin)
        add_device("a"); add_device("b")

        task = run_push(["a", "b"], delete_extras=True)
        await settle()

        # With N == 1 only the slot holder transfers; "b" must not look like it is
        # already syncing files it has not downloaded.
        assert push_state_of(admin, "a") == "transferring"
        assert push_state_of(admin, "b") == "queued"

        # The mode rides along so the console can say "Syncing…" rather than "Pushing…".
        states = [json.loads(m) for m in admin.sent if json.loads(m)["type"] == "PUSH_DEVICE_STATE"]
        assert states[0]["device_ids"] == ["a", "b"] and states[0]["state"] == "queued"
        assert all(s["delete_extras"] is True and s["dest_path"] == DEST for s in states)

        release_push("a")
        await settle()
        assert push_state_of(admin, "b") == "transferring"
        # "applying" belongs to the DOWNLOAD_COMPLETE handler; the dispatcher must stay
        # silent after its slot frees or it would race the terminal PUSH_FILES_RESULT.
        assert push_state_of(admin, "a") == "transferring"

        release_push("b")
        await asyncio.wait_for(task, 1)

    asyncio.run(body())


# ---------------------------------------------------------------------------
# Unit tests — the pool is server-wide, not per job and not per task type
# ---------------------------------------------------------------------------

def test_two_overlapping_install_jobs_share_one_budget():
    """The weakness a per-job semaphore left behind: 2 jobs x N = 2N transfers."""
    async def body():
        server.MAX_CONCURRENT_TRANSFERS = 2
        server.TRANSFER_TIMEOUT = 60
        wss = {d: add_device(d) for d in ["a", "b", "c", "d"]}

        first = run_install(["a", "b"])
        second = run_install(["c", "d"])
        await settle()

        # Not 4: the second job draws on the same two slots the first is holding.
        assert sum(install_count(w) for w in wss.values()) == 2
        assert len(server.pending_transfers) == 2

        while server.pending_transfers:
            device_id, _task = next(iter(server.pending_transfers))
            release_install(device_id)
            await settle()
        await asyncio.wait_for(asyncio.gather(first, second), 1)

        assert all(install_count(w) == 1 for w in wss.values())

    asyncio.run(body())


def test_install_and_push_jobs_share_one_budget():
    """The cross-type gap: an install and a push must not each get their own N."""
    async def body():
        server.MAX_CONCURRENT_TRANSFERS = 2
        server.TRANSFER_TIMEOUT = 60
        wss = {d: add_device(d) for d in ["a", "b", "c", "d"]}

        installing = run_install(["a", "b"])
        pushing = run_push(["c", "d"])
        await settle()

        live = (sum(install_count(w) for w in wss.values())
                + sum(push_count(w) for w in wss.values()))
        assert live == 2
        assert len(server.pending_transfers) == 2

        while server.pending_transfers:
            device_id, task = next(iter(server.pending_transfers))
            server.release_transfer_slot(device_id, "download_complete", task=task)
            await settle()
        await asyncio.wait_for(asyncio.gather(installing, pushing), 1)

        # Everyone was eventually served, just never more than two at a time.
        assert all(install_count(w) + push_count(w) == 1 for w in wss.values())

    asyncio.run(body())


def test_one_device_can_hold_an_install_slot_and_a_push_slot_at_once():
    """Two slots, two futures, two independent releases — the point of the task key."""
    async def body():
        server.MAX_CONCURRENT_TRANSFERS = 2
        server.TRANSFER_TIMEOUT = 60
        ws = add_device("a")

        installing = run_install(["a"])
        pushing = run_push(["a"])
        await settle()

        assert install_count(ws) == 1 and push_count(ws) == 1
        assert ("a", server.TASK_INSTALL) in server.pending_transfers
        assert ("a", server.TASK_PUSH) in server.pending_transfers

        # The install's completion must not free the push's slot.
        release_install("a")
        await settle()
        assert ("a", server.TASK_PUSH) in server.pending_transfers
        await asyncio.wait_for(installing, 1)

        release_push("a")
        await asyncio.wait_for(pushing, 1)
        assert server.pending_transfers == {}

    asyncio.run(body())


def test_handle_push_files_acknowledges_and_spawns_job():
    async def body():
        server.MAX_CONCURRENT_TRANSFERS = 3
        server.TRANSFER_TIMEOUT = 60
        add_device("a")
        admin = FakeWS()

        await server.handle_push_files(admin, {
            "target_devices": ["a"], "bundle_url": BUNDLE_URL,
            "bundle_filename": "b.zip", "dest_path": DEST, "delete_extras": True,
        })
        # Immediate PUSH_FILES_SENT ack carrying the throttle limit; a job task spawned.
        ack = next(json.loads(m) for m in admin.sent if json.loads(m)["type"] == "PUSH_FILES_SENT")
        assert ack["target_count"] == 1 and ack["max_concurrent"] == 3
        assert ack["delete_extras"] is True
        assert len(server._transfer_tasks) == 1

        await settle()
        release_push("a")
        await settle()

    asyncio.run(body())


# ---------------------------------------------------------------------------
# Integration tests — real WebSockets prove the handler wiring
# ---------------------------------------------------------------------------

async def _recv(ws, msg_type: str, timeout: float = 2.0) -> dict | None:
    """Return the next message of the given type a device receives, or None on timeout."""
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


async def _wait_online(admin, expected: set[str], timeout: float = 2.0) -> None:
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


async def _send_push(admin, targets: list[str], base: str) -> None:
    await admin.send_json({
        "type": "PUSH_FILES", "target_devices": targets,
        "bundle_url": base + "/bundles/b.zip", "bundle_filename": "b.zip",
        "dest_path": DEST, "delete_extras": False,
    })


def test_e2e_push_download_complete_and_result_release(tmp_path):
    """The two client-side release paths for a push, newest first."""
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

                await _send_push(admin, ["dev0", "dev1", "dev2"], base)

                # N == 1: only dev0 downloads the bundle; dev1/dev2 are gated.
                assert await _recv(d0, "EXECUTE_PUSH_FILES") is not None
                assert await _recv(d1, "EXECUTE_PUSH_FILES", timeout=0.3) is None

                # Primary release: dev0 signals the bundle download finished, before its
                # local unzip/mirror -> dev1 dispatched.
                await d0.send_json({
                    "type": "DOWNLOAD_COMPLETE", "task": "push", "dest_path": DEST,
                })
                assert await _recv(d1, "EXECUTE_PUSH_FILES") is not None
                assert await _recv(d2, "EXECUTE_PUSH_FILES", timeout=0.3) is None

                # Fallback release: dev1 is an "older client" that only sends
                # PUSH_FILES_RESULT (no DOWNLOAD_COMPLETE for push) -> dev2 dispatched.
                await d1.send_json({
                    "type": "PUSH_FILES_RESULT", "status": "success", "dest_path": DEST,
                    "added": 1, "updated": 0, "deleted": 0,
                })
                assert await _recv(d2, "EXECUTE_PUSH_FILES") is not None

                await d2.send_json({"type": "DOWNLOAD_COMPLETE", "task": "push", "dest_path": DEST})
                for ws in (d0, d1, d2, admin):
                    await ws.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_install_result_does_not_free_a_push_slot(tmp_path):
    """An unrelated INSTALL_RESULT must not let the push queue run ahead."""
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

                await _send_push(admin, ["dev0", "dev1"], base)
                assert await _recv(d0, "EXECUTE_PUSH_FILES") is not None
                assert await _recv(d1, "EXECUTE_PUSH_FILES", timeout=0.3) is None

                # dev0 holds a *push* slot. A stray install result — a late frame from an
                # earlier install job — names a different task and must free nothing.
                await d0.send_json({
                    "type": "INSTALL_RESULT", "status": "success", "apk_filename": "x.apk",
                })
                assert await _recv(d1, "EXECUTE_PUSH_FILES", timeout=0.3) is None

                # The push's own signal does free it.
                await d0.send_json({"type": "DOWNLOAD_COMPLETE", "task": "push", "dest_path": DEST})
                assert await _recv(d1, "EXECUTE_PUSH_FILES") is not None

                await d1.send_json({"type": "DOWNLOAD_COMPLETE", "task": "push", "dest_path": DEST})
                for ws in (d0, d1, admin):
                    await ws.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_applying_state_arrives_before_the_terminal_result(tmp_path):
    """A device's row must reach "applying" and then stop, never the other way.

    Same ordering argument as install's "installing": the state is emitted from the
    DOWNLOAD_COMPLETE handler so it cannot land after the PUSH_FILES_RESULT that
    follows it on the same device connection, which would spin the cell forever.
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

                await _send_push(admin, ["dev0"], base)
                assert await _recv(d0, "EXECUTE_PUSH_FILES") is not None

                await d0.send_json({"type": "DOWNLOAD_COMPLETE", "task": "push", "dest_path": DEST})
                await d0.send_json({
                    "type": "PUSH_FILES_RESULT", "status": "success", "dest_path": DEST,
                    "added": 1, "updated": 0, "deleted": 0,
                })

                seen: list[str] = []

                async def poll():
                    while True:
                        msg = await admin.receive()
                        if msg.type != aiohttp.WSMsgType.TEXT:
                            continue
                        data = json.loads(msg.data)
                        if data.get("type") == "PUSH_DEVICE_STATE":
                            seen.append(data["state"])
                        elif data.get("type") == "PUSH_FILES_RESULT":
                            return

                await asyncio.wait_for(poll(), 2)
                assert seen == ["queued", "transferring", "applying"]

                for ws in (d0, admin):
                    await ws.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_older_client_download_complete_still_frees_an_install_slot(tmp_path):
    """Backward compatibility: no `task` field means the install slot, as before."""
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
                    "type": "INSTALL_APK", "target_devices": ["dev0", "dev1"],
                    "apk_url": base + "/apks/x.apk", "apk_filename": "x.apk",
                })
                assert await _recv(d0, "EXECUTE_INSTALL") is not None
                assert await _recv(d1, "EXECUTE_INSTALL", timeout=0.3) is None

                # A client that predates push throttling omits `task` entirely.
                await d0.send_json({"type": "DOWNLOAD_COMPLETE", "apk_filename": "x.apk"})
                assert await _recv(d1, "EXECUTE_INSTALL") is not None

                await d1.send_json({"type": "DOWNLOAD_COMPLETE", "apk_filename": "x.apk"})
                for ws in (d0, d1, admin):
                    await ws.close()
        finally:
            await ts.close()

    asyncio.run(body())


def test_e2e_disconnect_frees_the_push_slot(tmp_path):
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

                await _send_push(admin, ["dev0", "dev1"], base)
                assert await _recv(d0, "EXECUTE_PUSH_FILES") is not None
                assert await _recv(d1, "EXECUTE_PUSH_FILES", timeout=0.3) is None

                # dev0 drops mid-transfer -> its slot must free so dev1 proceeds.
                await d0.close()
                assert await _recv(d1, "EXECUTE_PUSH_FILES") is not None

                await d1.send_json({"type": "DOWNLOAD_COMPLETE", "task": "push", "dest_path": DEST})
                for ws in (d1, admin):
                    await ws.close()
        finally:
            await ts.close()

    asyncio.run(body())
