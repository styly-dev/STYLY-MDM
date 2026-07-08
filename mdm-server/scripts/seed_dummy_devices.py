#!/usr/bin/env python3
"""Seed (or remove) dummy devices on a *running* STYLY-MDM server, for
development and UI testing (e.g. filling the Manage Groups list so it scrolls).

Why go through the live WebSocket instead of editing device_registry.json?
Because a running server owns that file: any REGISTER / battery / group change
triggers save_registry(), which rewrites the file from in-memory state and
silently clobbers hand-edits. Registering through /ws/device makes the server
itself persist the dummies, so they survive and never get overwritten.

Usage (run against a running server):

    # add 20 offline dummy devices
    python scripts/seed_dummy_devices.py 20

    # keep them connected (shown online) until you press Ctrl-C
    python scripts/seed_dummy_devices.py 20 --online

    # remove every dummy this tool created (matched by serial prefix)
    python scripts/seed_dummy_devices.py --remove

Port resolution order: --port, else UDP discovery on the discovery port, else
$MDM_WS_PORT, else 7070 (mirrors the server's own default). The discovery port
defaults to $MDM_DISCOVERY_PORT or 7071, so custom-port servers are found
automatically as long as this runs on the same host/LAN.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import signal
import socket
import sys

import aiohttp

# Matches styly_mdm.server; kept local so the script stays standalone.
DISCOVERY_REQUEST = b"STYLYMDM_DISCOVER"
MAX_LABEL_LEN = 64
MODELS = ["A94U0", "PICO 4", "PICO 4 Ultra", "PICO 4 Enterprise"]


def default_ws_port() -> int:
    return int(os.environ.get("MDM_WS_PORT", "7070"))


def discovery_port() -> int:
    return int(os.environ.get("MDM_DISCOVERY_PORT", "7071"))


def discover_ws_port(timeout: float = 1.5) -> int | None:
    """Ask the local/LAN server for its WebSocket port via UDP discovery."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        s.settimeout(timeout)
        for host in ("127.0.0.1", "<broadcast>"):
            try:
                s.sendto(DISCOVERY_REQUEST, (host, discovery_port()))
            except OSError:
                pass
        try:
            while True:
                data, _ = s.recvfrom(65535)
                try:
                    obj = json.loads(data.decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError):
                    continue
                if obj.get("service") == "stylymdm" and obj.get("ws_url"):
                    # ws://IP:PORT/ws/device
                    return int(obj["ws_url"].split(":")[2].split("/")[0])
        except socket.timeout:
            return None
        finally:
            s.close()
    except OSError:
        return None


def resolve_port(args) -> int:
    if args.port is not None:
        return args.port
    if not args.no_discover:
        found = discover_ws_port()
        if found is not None:
            return found
    return default_ws_port()


def serial_for(prefix: str, i: int) -> str:
    return f"{prefix}-TEST-{i:04d}"


def serial_glob(prefix: str) -> str:
    return f"{prefix}-TEST-"


async def fetch_device_list(ws) -> list[dict]:
    """Drain admin messages until a DEVICE_LIST arrives (with a short timeout)."""
    for _ in range(8):
        try:
            msg = await asyncio.wait_for(ws.receive(), timeout=2.0)
        except asyncio.TimeoutError:
            break
        if msg.type != aiohttp.WSMsgType.TEXT:
            continue
        obj = json.loads(msg.data)
        if obj.get("type") == "DEVICE_LIST":
            return obj.get("devices", [])
    return []


async def latest_device_list(ws, settle: float = 1.5) -> list[dict]:
    """Return the newest DEVICE_LIST seen within `settle` seconds of asking."""
    await ws.send_str(json.dumps({"type": "GET_DEVICE_LIST"}))
    devices: list[dict] = []
    loop = asyncio.get_running_loop()
    deadline = loop.time() + settle
    while loop.time() < deadline:
        try:
            msg = await asyncio.wait_for(ws.receive(), timeout=deadline - loop.time())
        except asyncio.TimeoutError:
            break
        if msg.type != aiohttp.WSMsgType.TEXT:
            continue
        obj = json.loads(msg.data)
        if obj.get("type") == "DEVICE_LIST":
            devices = obj.get("devices", [])  # keep the most recent
    return devices


async def add_devices(base: str, prefix: str, count: int, online: bool) -> int:
    async with aiohttp.ClientSession() as session:
        # Continue numbering after any dummies that already exist, so repeated
        # runs are additive and labels never collide (server enforces unique labels).
        async with session.ws_connect(f"{base}/ws/admin", heartbeat=None) as admin:
            existing = await fetch_device_list(admin)
        used = []
        for d in existing:
            did = d.get("device_id", "")
            if did.startswith(serial_glob(prefix)):
                try:
                    used.append(int(did[len(serial_glob(prefix)):]))
                except ValueError:
                    pass
        start = (max(used) + 1) if used else 1
        specs = []
        for n in range(count):
            i = start + n
            specs.append({
                "device_id": serial_for(prefix, i),
                "label": f"{prefix}-{i:02d}"[:MAX_LABEL_LEN],
                "model": MODELS[i % len(MODELS)],
                "ip": f"192.168.128.{100 + (i % 150)}",
                "level": 5 + (i * 7) % 95,
                "charging": (i % 4 == 0),
            })

        held = []  # open sessions kept alive for --online
        try:
            for spec in specs:
                ws = await session.ws_connect(f"{base}/ws/device", heartbeat=None)
                await ws.send_str(json.dumps({
                    "type": "REGISTER",
                    "device_id": spec["device_id"],
                    "model": spec["model"],
                    "ip": spec["ip"],
                }))
                await ws.send_str(json.dumps({
                    "type": "BATTERY_UPDATE",
                    "level": spec["level"],
                    "charging": spec["charging"],
                }))
                await asyncio.sleep(0.03)  # let the server persist + broadcast
                if online:
                    held.append(ws)
                else:
                    await ws.close()
            print(f"Registered {len(specs)} device(s): "
                  f"{specs[0]['device_id']} .. {specs[-1]['device_id']}")

            # Assign labels via the admin path (device REGISTER can't set them).
            async with session.ws_connect(f"{base}/ws/admin", heartbeat=None) as admin:
                await fetch_device_list(admin)  # drain initial DEVICE_LIST + GROUP_LIST
                for spec in specs:
                    await admin.send_str(json.dumps({
                        "type": "SET_DEVICE_LABEL",
                        "device_id": spec["device_id"],
                        "label": spec["label"],
                    }))
                    await asyncio.sleep(0.02)
                devs = await latest_device_list(admin)

            wanted = {s["device_id"] for s in specs}
            present = {d["device_id"]: d for d in devs if d["device_id"] in wanted}
            labeled = sum(1 for d in present.values() if d.get("label"))
            print(f"Verified: {len(present)}/{len(specs)} present, "
                  f"{labeled}/{len(specs)} labeled, "
                  f"status={'online' if online else 'offline'}")

            if online:
                print("Holding connections open (devices shown ONLINE). "
                      "Press Ctrl-C to disconnect them.")
                # Explicit signal handlers so both interactive Ctrl-C and `kill`
                # unblock and fall through to the finally-block clean disconnect.
                stop = asyncio.Event()
                loop = asyncio.get_running_loop()
                for sig in (signal.SIGINT, signal.SIGTERM):
                    try:
                        loop.add_signal_handler(sig, stop.set)
                    except NotImplementedError:
                        pass  # not supported on some platforms (e.g. Windows)
                await stop.wait()
                print("\nDisconnecting...")
        finally:
            for ws in held:
                await ws.close()

        return 0 if len(present) == len(specs) else 1


async def remove_devices(base: str, prefix: str) -> int:
    async with aiohttp.ClientSession() as session:
        async with session.ws_connect(f"{base}/ws/admin", heartbeat=None) as admin:
            devs = await fetch_device_list(admin)
            targets = [d["device_id"] for d in devs
                       if d.get("device_id", "").startswith(serial_glob(prefix))]
            if not targets:
                print(f"No dummy devices matching '{serial_glob(prefix)}*' to remove.")
                return 0
            online = [d["device_id"] for d in devs
                      if d.get("device_id", "") in targets and d.get("status") == "online"]
            if online:
                print(f"Warning: {len(online)} device(s) are online and cannot be "
                      f"forgotten; skipping them: {', '.join(sorted(online)[:5])}"
                      f"{' ...' if len(online) > 5 else ''}")
            for serial in targets:
                if serial in online:
                    continue
                await admin.send_str(json.dumps({
                    "type": "FORGET_DEVICE", "device_id": serial,
                }))
                await asyncio.sleep(0.02)
            remaining = await latest_device_list(admin)
        left = [d["device_id"] for d in remaining
                if d.get("device_id", "").startswith(serial_glob(prefix))]
        removed = len(targets) - len(left)
        print(f"Removed {removed} dummy device(s); {len(left)} still present.")
        return 0 if not left or set(left) <= set(online) else 1


def main() -> int:
    p = argparse.ArgumentParser(
        description="Add or remove dummy devices on a running STYLY-MDM server.")
    p.add_argument("count", nargs="?", type=int, default=10,
                   help="number of dummy devices to add (default: 10)")
    p.add_argument("--host", default="127.0.0.1", help="server host (default: 127.0.0.1)")
    p.add_argument("--port", type=int, default=None,
                   help="WebSocket port; skips discovery")
    p.add_argument("--no-discover", action="store_true",
                   help="do not UDP-discover the port")
    p.add_argument("--prefix", default="DUMMY",
                   help="serial/label prefix (default: DUMMY)")
    p.add_argument("--online", action="store_true",
                   help="keep devices connected (shown online) until Ctrl-C")
    p.add_argument("--remove", action="store_true",
                   help="remove all dummy devices matching the prefix instead of adding")
    args = p.parse_args()

    if not args.remove and args.count <= 0:
        p.error("count must be a positive integer")

    port = resolve_port(args)
    base = f"ws://{args.host}:{port}"
    print(f"Target server: {base}")

    try:
        if args.remove:
            return asyncio.run(remove_devices(base, args.prefix))
        return asyncio.run(add_devices(base, args.prefix, args.count, args.online))
    except aiohttp.ClientError as e:
        print(f"ERROR: could not reach the server at {base}: {e}", file=sys.stderr)
        print("Is the server running? Pass --port if it uses a non-default port.",
              file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
