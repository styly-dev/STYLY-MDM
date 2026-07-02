"""STYLY-MDM WebSocket Control Server.

Serves as the bridge between PICO VR HMDs and the web admin console.
HMDs connect via /ws/device, admin consoles via /ws/admin.
Static files for the web console are served from ./static/.
"""

import argparse
import asyncio
import json
import logging
import os
import re
import socket
import time
from pathlib import Path
from aiohttp import web

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
log = logging.getLogger("stylymdm")

# Writable runtime data (uploaded APKs, device registry) lives under a
# configurable data directory rather than next to this module. When installed as
# a package (pip/uvx) the module directory is read-only site-packages, so writes
# must go elsewhere. Defaults to the current working directory, which keeps the
# from-source workflow (run.sh does `cd mdm-server`) writing to mdm-server/ as
# before. Override with MDM_DATA_DIR or the --data-dir flag.
DATA_DIR = Path(os.environ.get("MDM_DATA_DIR", ".")).resolve()

APK_DIR = DATA_DIR / "apks"
MAX_APK_SIZE = 2 * 1024 * 1024 * 1024  # 2 GiB

# Persistent per-device registry: serial -> {label, model, ip, last_seen, startup_app}
REGISTRY_PATH = DATA_DIR / "device_registry.json"
MAX_LABEL_LEN = 64
MAX_GROUP_NAME_LEN = 64

# Connected devices: device_id -> {ws, device_id, model, ip, status, startup_app}
devices: dict[str, dict] = {}

# serial -> last-known record {label, model, ip, last_seen, startup_app}. Remembers
# every device that has connected at least once so it stays listed while offline.
# Survives restarts (persisted to REGISTRY_PATH).
device_registry: dict[str, dict] = {}

# Named device groups (many-to-many): group name -> list of member serials. A
# device can belong to zero or more groups; membership is keyed by serial so an
# offline (or not-yet-registered) device can still be grouped. Persisted to
# REGISTRY_PATH alongside the device registry.
device_groups: dict[str, list[str]] = {}

# Connected admin WebSocket sessions
admin_connections: set[web.WebSocketResponse] = set()


# ---------------------------------------------------------------------------
# Device registry (persistent, additive — never the identity key)
# ---------------------------------------------------------------------------

def _coerce_record(value) -> dict | None:
    """Normalize a registry value into a record.

    Accepts the legacy flat shape (a label string) and the current record shape
    (a dict). Returns None for anything else.
    """
    if isinstance(value, str):
        return {"label": value.strip(), "model": "", "ip": "", "last_seen": None, "startup_app": None}
    if isinstance(value, dict):
        label = value.get("label", "")
        model = value.get("model", "")
        ip = value.get("ip", "")
        last_seen = value.get("last_seen")
        startup_app = value.get("startup_app")
        return {
            "label": label.strip() if isinstance(label, str) else "",
            "model": model if isinstance(model, str) else "",
            "ip": ip if isinstance(ip, str) else "",
            "last_seen": last_seen if isinstance(last_seen, (int, float)) else None,
            "startup_app": startup_app if isinstance(startup_app, dict) else None,
        }
    return None


def _coerce_groups(value) -> dict[str, list[str]]:
    """Normalize the persisted groups mapping into {name: [serial, ...]}.

    Drops non-string names/serials, strips and length-caps names, and dedupes
    members while preserving order. Returns an empty mapping for anything else.
    """
    result: dict[str, list[str]] = {}
    if not isinstance(value, dict):
        return result
    for name, members in value.items():
        if not isinstance(name, str):
            continue
        clean_name = name.strip()[:MAX_GROUP_NAME_LEN]
        if not clean_name:
            continue
        serials: list[str] = []
        if isinstance(members, list):
            for s in members:
                if isinstance(s, str) and s and s not in serials:
                    serials.append(s)
        result[clean_name] = serials
    return result


def load_registry() -> None:
    """Load the per-device registry and groups from disk, tolerating a missing/corrupt file.

    Accepts two on-disk shapes:
    - Legacy flat: serial -> label string, or serial -> record dict (no groups).
    - Current wrapped: {"devices": {serial -> record}, "groups": {name -> [serial]}}.

    The wrapped shape is detected by a dict-valued "devices" key. Device serials
    are formatted like "PA94U0...", so a real serial never collides with the
    "devices"/"groups" keys. The server always writes the wrapped shape, so this
    is a one-way auto-migration; an older server reading a new file would mistake
    "devices"/"groups" for phantom devices (downgrade-only, acceptable).
    """
    device_registry.clear()
    device_groups.clear()
    try:
        raw = json.loads(REGISTRY_PATH.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return
    except (json.JSONDecodeError, OSError) as e:
        log.warning("Ignoring unreadable device registry %s: %s", REGISTRY_PATH, e)
        return
    if not isinstance(raw, dict):
        log.warning("Device registry is not an object; ignoring")
        return

    if isinstance(raw.get("devices"), dict):
        devices_raw = raw["devices"]
        device_groups.update(_coerce_groups(raw.get("groups")))
    else:
        devices_raw = raw  # legacy flat shape: the whole object is serial -> value

    for serial, value in devices_raw.items():
        if not isinstance(serial, str):
            continue
        record = _coerce_record(value)
        if record is not None:
            device_registry[serial] = record
    log.info("Loaded %d device(s) and %d group(s) from registry",
             len(device_registry), len(device_groups))


def save_registry() -> None:
    """Persist the per-device registry and groups atomically (temp file + replace)."""
    tmp = REGISTRY_PATH.with_suffix(".json.tmp")
    payload = {"devices": device_registry, "groups": device_groups}
    try:
        tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(REGISTRY_PATH)
    except OSError as e:
        log.error("Failed to persist device registry: %s", e)
        tmp.unlink(missing_ok=True)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def build_device_list_msg() -> str:
    """Build a DEVICE_LIST payload merging online and known-but-offline devices.

    Online devices report live data with status "online". Devices that exist in
    the persistent registry but are not currently connected are included with
    status "offline" and their last-known data, so the full fleet stays visible.
    Each entry carries its display `label` (empty if unassigned). Labeled devices
    sort first by label; unlabeled devices fall to the bottom by serial. The sort
    is independent of online status, so a row keeps its place across transitions.
    """
    device_list = [
        {
            "device_id": d["device_id"],
            "label": device_registry.get(d["device_id"], {}).get("label", ""),
            "model": d["model"],
            "ip": d["ip"],
            "status": "online",
            "startup_app": d.get("startup_app"),
            "last_seen": device_registry.get(d["device_id"], {}).get("last_seen"),
        }
        for d in devices.values()
    ]
    for serial, rec in device_registry.items():
        if serial in devices:
            continue
        device_list.append({
            "device_id": serial,
            "label": rec.get("label", ""),
            "model": rec.get("model", ""),
            "ip": rec.get("ip", ""),
            "status": "offline",
            "startup_app": rec.get("startup_app"),
            "last_seen": rec.get("last_seen"),
        })
    device_list.sort(
        key=lambda e: (e["label"] == "", (e["label"] or e["device_id"]).lower())
    )
    return json.dumps({"type": "DEVICE_LIST", "devices": device_list})


async def broadcast_device_list():
    """Send the current device list to every connected admin."""
    msg = build_device_list_msg()
    stale: list[web.WebSocketResponse] = []
    for ws in admin_connections:
        try:
            await ws.send_str(msg)
        except ConnectionResetError:
            stale.append(ws)
    for ws in stale:
        admin_connections.discard(ws)


def build_group_list_msg() -> str:
    """Build a GROUP_LIST payload: every group with its member serials.

    This is the single source of truth for group membership; the admin console
    derives the per-device "which groups" view from it. Groups are sorted by name
    for a stable display, and members are emitted as plain serial lists (a serial
    may reference an offline or not-yet-registered device).
    """
    groups = {name: list(members) for name, members in sorted(device_groups.items())}
    return json.dumps({"type": "GROUP_LIST", "groups": groups})


async def broadcast_group_list():
    """Send the current group list to every connected admin."""
    msg = build_group_list_msg()
    stale: list[web.WebSocketResponse] = []
    for ws in admin_connections:
        try:
            await ws.send_str(msg)
        except ConnectionResetError:
            stale.append(ws)
    for ws in stale:
        admin_connections.discard(ws)


async def forward_to_admins(payload: dict):
    """Forward a message (e.g. LAUNCH_RESULT) to all admin connections."""
    msg = json.dumps(payload)
    stale: list[web.WebSocketResponse] = []
    for ws in admin_connections:
        try:
            await ws.send_str(msg)
        except ConnectionResetError:
            stale.append(ws)
    for ws in stale:
        admin_connections.discard(ws)


def resolve_target_ids(target_devices: list[str]) -> list[str]:
    """Return online device IDs matching a target list, or all devices for ["*"]."""
    if not target_devices or target_devices == ["*"]:
        return list(devices.keys())
    return [d for d in target_devices if d in devices]


def sanitize_apk_filename(filename: str | None) -> str | None:
    """Return a filesystem-safe APK filename, or None if invalid."""
    if not filename:
        return None
    name = Path(filename).name.strip()
    if not name.lower().endswith(".apk"):
        return None
    safe_name = re.sub(r"[^A-Za-z0-9._-]", "_", name)
    if safe_name in {"", ".apk"}:
        return None
    return safe_name


def unique_apk_path(filename: str) -> Path:
    """Build a unique destination path in APK_DIR for an uploaded APK."""
    destination = APK_DIR / filename
    if not destination.exists():
        return destination

    stem = destination.stem
    suffix = destination.suffix
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    candidate = APK_DIR / f"{stem}-{timestamp}{suffix}"
    counter = 2
    while candidate.exists():
        candidate = APK_DIR / f"{stem}-{timestamp}-{counter}{suffix}"
        counter += 1
    return candidate


# ---------------------------------------------------------------------------
# Device WebSocket handler  (/ws/device)
# ---------------------------------------------------------------------------

async def device_ws_handler(request: web.Request) -> web.WebSocketResponse:
    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)
    log.info("Device WebSocket connected from %s", request.remote)

    device_id: str | None = None

    try:
        async for raw_msg in ws:
            if raw_msg.type == web.WSMsgType.TEXT:
                try:
                    data = json.loads(raw_msg.data)
                except json.JSONDecodeError:
                    log.warning("Device sent invalid JSON: %s", raw_msg.data[:200])
                    continue

                msg_type = data.get("type")

                if msg_type == "REGISTER":
                    device_id = data.get("device_id")
                    if not device_id:
                        log.warning("REGISTER missing device_id")
                        continue
                    model = data.get("model", "unknown")
                    ip = data.get("ip", request.remote or "unknown")
                    startup_app = data.get("startup_app")
                    devices[device_id] = {
                        "ws": ws,
                        "device_id": device_id,
                        "model": model,
                        "ip": ip,
                        "status": "online",
                        "startup_app": startup_app,
                    }
                    # Remember the device persistently so it stays in the list while
                    # offline; preserve any previously assigned label.
                    prev = device_registry.get(device_id, {})
                    device_registry[device_id] = {
                        "label": prev.get("label", ""),
                        "model": model,
                        "ip": ip,
                        "last_seen": time.time(),
                        "startup_app": startup_app,
                    }
                    save_registry()
                    log.info("Device registered: %s (%s)", device_id, model)
                    await broadcast_device_list()

                elif msg_type in {"LAUNCH_RESULT", "INSTALL_RESULT"}:
                    if device_id:
                        data.setdefault("device_id", device_id)
                    log.info("%s from %s: %s", msg_type, device_id, data.get("status"))
                    await forward_to_admins(data)

                elif msg_type == "STARTUP_APP_RESULT":
                    log.info("Startup app result from %s: %s", device_id, data.get("status"))
                    await forward_to_admins(data)

                else:
                    log.warning("Unknown message type from device: %s", msg_type)

            elif raw_msg.type == web.WSMsgType.ERROR:
                log.error("Device WS error: %s", ws.exception())
    finally:
        if device_id and device_id in devices:
            del devices[device_id]
            # Keep the registry entry; just stop reporting the device as online and
            # stamp when it was last seen so it shows as offline in the list.
            rec = device_registry.get(device_id)
            if rec is not None:
                rec["last_seen"] = time.time()
                save_registry()
            log.info("Device disconnected: %s", device_id)
            await broadcast_device_list()

    return ws


# ---------------------------------------------------------------------------
# Admin WebSocket handler  (/ws/admin)
# ---------------------------------------------------------------------------

async def admin_ws_handler(request: web.Request) -> web.WebSocketResponse:
    ws = web.WebSocketResponse(heartbeat=30)
    await ws.prepare(request)
    admin_connections.add(ws)
    log.info("Admin console connected from %s", request.remote)

    # Send current device list and group list immediately on connect
    try:
        await ws.send_str(build_device_list_msg())
        await ws.send_str(build_group_list_msg())
    except ConnectionResetError:
        admin_connections.discard(ws)
        return ws

    try:
        async for raw_msg in ws:
            if raw_msg.type == web.WSMsgType.TEXT:
                try:
                    data = json.loads(raw_msg.data)
                except json.JSONDecodeError:
                    log.warning("Admin sent invalid JSON: %s", raw_msg.data[:200])
                    continue

                msg_type = data.get("type")

                if msg_type == "LAUNCH_APP":
                    await handle_launch_app(ws, data)

                elif msg_type == "INSTALL_APK":
                    await handle_install_apk(ws, data)

                elif msg_type == "SET_STARTUP_APP":
                    await handle_set_startup_app(ws, data)

                elif msg_type == "CLEAR_STARTUP_APP":
                    await handle_clear_startup_app(ws, data)

                elif msg_type == "GET_DEVICE_LIST":
                    await ws.send_str(build_device_list_msg())

                elif msg_type == "SET_DEVICE_LABEL":
                    await handle_set_device_label(ws, data)

                elif msg_type == "FORGET_DEVICE":
                    await handle_forget_device(ws, data)

                elif msg_type == "CREATE_GROUP":
                    await handle_create_group(ws, data)

                elif msg_type == "RENAME_GROUP":
                    await handle_rename_group(ws, data)

                elif msg_type == "DELETE_GROUP":
                    await handle_delete_group(ws, data)

                elif msg_type == "SET_DEVICE_GROUPS":
                    await handle_set_device_groups(ws, data)

                elif msg_type == "SET_GROUP_MEMBERS":
                    await handle_set_group_members(ws, data)

                else:
                    log.warning("Unknown message type from admin: %s", msg_type)

            elif raw_msg.type == web.WSMsgType.ERROR:
                log.error("Admin WS error: %s", ws.exception())
    finally:
        admin_connections.discard(ws)
        log.info("Admin console disconnected")

    return ws


async def handle_set_device_label(admin_ws: web.WebSocketResponse, data: dict):
    """Assign, rename, or clear a device's display label and persist it."""
    serial: str = data.get("device_id", "")
    label: str = (data.get("label") or "").strip()[:MAX_LABEL_LEN]

    if not serial:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "device_id is required",
        }))
        return

    if label:
        # Keep labels unique so two devices never share the same sticker name.
        owner = next(
            (s for s, r in device_registry.items()
             if r.get("label") == label and s != serial),
            None,
        )
        if owner is not None:
            await admin_ws.send_str(json.dumps({
                "type": "ERROR",
                "message": f"Label '{label}' is already assigned to {owner}",
            }))
            return

    # Upsert the record. An empty label clears the assignment but keeps the device
    # known, so it stays listed as offline until explicitly forgotten.
    rec = device_registry.get(serial)
    if rec is None:
        rec = {"label": "", "model": "", "ip": "", "last_seen": None, "startup_app": None}
        device_registry[serial] = rec
    rec["label"] = label

    save_registry()
    log.info("Device label set: %s -> %r", serial, label)

    await admin_ws.send_str(json.dumps({
        "type": "DEVICE_LABEL_SET",
        "device_id": serial,
        "label": label,
    }))
    await broadcast_device_list()


async def handle_forget_device(admin_ws: web.WebSocketResponse, data: dict):
    """Remove a decommissioned device from the persistent registry.

    Only offline devices can be forgotten; an online device would immediately
    re-register and reappear, so that case is rejected with a clear message.
    """
    serial: str = data.get("device_id", "")

    if not serial:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "device_id is required",
        }))
        return

    if serial in devices:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "Cannot forget an online device; disconnect it first",
        }))
        return

    removed = device_registry.pop(serial, None) is not None
    # Decommission cleanly: drop the serial from every group so no group keeps a
    # dangling reference to a device that is no longer known.
    groups_changed = False
    for members in device_groups.values():
        if serial in members:
            members.remove(serial)
            groups_changed = True

    if removed or groups_changed:
        save_registry()
    if removed:
        log.info("Device forgotten: %s", serial)

    await admin_ws.send_str(json.dumps({
        "type": "DEVICE_FORGOTTEN",
        "device_id": serial,
    }))
    await broadcast_device_list()
    if groups_changed:
        await broadcast_group_list()


async def handle_create_group(admin_ws: web.WebSocketResponse, data: dict):
    """Create a new, empty named group and persist it."""
    name: str = (data.get("name") or "").strip()[:MAX_GROUP_NAME_LEN]

    if not name:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "Group name is required",
        }))
        return

    if name in device_groups:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": f"Group '{name}' already exists",
        }))
        return

    device_groups[name] = []
    save_registry()
    log.info("Group created: %r", name)

    await admin_ws.send_str(json.dumps({
        "type": "GROUP_CREATED",
        "name": name,
    }))
    await broadcast_group_list()


async def handle_rename_group(admin_ws: web.WebSocketResponse, data: dict):
    """Rename an existing group, preserving its membership."""
    name: str = (data.get("name") or "").strip()[:MAX_GROUP_NAME_LEN]
    new_name: str = (data.get("new_name") or "").strip()[:MAX_GROUP_NAME_LEN]

    if not name or not new_name:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "Both name and new_name are required",
        }))
        return

    if name not in device_groups:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": f"Group '{name}' does not exist",
        }))
        return

    if new_name != name and new_name in device_groups:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": f"Group '{new_name}' already exists",
        }))
        return

    # Rebuild to preserve insertion order while swapping the key.
    device_groups[new_name] = device_groups.pop(name)
    save_registry()
    log.info("Group renamed: %r -> %r", name, new_name)

    await admin_ws.send_str(json.dumps({
        "type": "GROUP_RENAMED",
        "name": name,
        "new_name": new_name,
    }))
    await broadcast_group_list()


async def handle_delete_group(admin_ws: web.WebSocketResponse, data: dict):
    """Delete a group. Devices are never affected, only the grouping is removed."""
    name: str = (data.get("name") or "").strip()[:MAX_GROUP_NAME_LEN]

    if not name:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "Group name is required",
        }))
        return

    if device_groups.pop(name, None) is not None:
        save_registry()
        log.info("Group deleted: %r", name)

    await admin_ws.send_str(json.dumps({
        "type": "GROUP_DELETED",
        "name": name,
    }))
    await broadcast_group_list()


async def handle_set_device_groups(admin_ws: web.WebSocketResponse, data: dict):
    """Set the exact set of groups a device belongs to.

    Every named group must already exist (created via CREATE_GROUP); unknown names
    are rejected so typos never spawn phantom groups. Membership is keyed by serial
    so an offline device can be (re)assigned.
    """
    serial: str = data.get("device_id", "")
    requested = data.get("groups", [])

    if not serial:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "device_id is required",
        }))
        return

    if not isinstance(requested, list):
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "groups must be a list of group names",
        }))
        return

    # Normalize and validate: dedupe, and require every name to exist already.
    target_names: list[str] = []
    for raw_name in requested:
        if not isinstance(raw_name, str):
            continue
        clean = raw_name.strip()[:MAX_GROUP_NAME_LEN]
        if clean and clean not in target_names:
            target_names.append(clean)

    unknown = [n for n in target_names if n not in device_groups]
    if unknown:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": f"Unknown group(s): {', '.join(unknown)}",
        }))
        return

    # Rebuild membership so the device is in exactly the requested groups.
    target_set = set(target_names)
    for name, members in device_groups.items():
        in_group = serial in members
        if name in target_set and not in_group:
            members.append(serial)
        elif name not in target_set and in_group:
            members.remove(serial)

    save_registry()
    log.info("Device groups set: %s -> %s", serial, target_names)

    await admin_ws.send_str(json.dumps({
        "type": "DEVICE_GROUPS_SET",
        "device_id": serial,
        "groups": target_names,
    }))
    await broadcast_group_list()


async def handle_set_group_members(admin_ws: web.WebSocketResponse, data: dict):
    """Set the exact member list of a group (group-centric assignment).

    The group must already exist. Members are stored as serials; offline or
    not-yet-registered serials are allowed (a device can be grouped while offline),
    so members are NOT filtered against the live device list.
    """
    name: str = (data.get("name") or "").strip()[:MAX_GROUP_NAME_LEN]
    requested = data.get("members", [])

    if not name:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "Group name is required",
        }))
        return

    if name not in device_groups:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": f"Group '{name}' does not exist",
        }))
        return

    if not isinstance(requested, list):
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "members must be a list of serials",
        }))
        return

    members: list[str] = []
    for serial in requested:
        if isinstance(serial, str) and serial and serial not in members:
            members.append(serial)

    device_groups[name] = members
    save_registry()
    log.info("Group members set: %r -> %d member(s)", name, len(members))

    await admin_ws.send_str(json.dumps({
        "type": "GROUP_MEMBERS_SET",
        "name": name,
        "members": members,
    }))
    await broadcast_group_list()


async def handle_launch_app(admin_ws: web.WebSocketResponse, data: dict):
    """Process a LAUNCH_APP command from an admin and forward to target HMDs."""
    target_devices: list[str] = data.get("target_devices", [])
    package_name: str = data.get("package_name", "")
    extra_data: str = data.get("extra_data", "{}")

    if not package_name:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "package_name is required",
        }))
        return

    target_ids = resolve_target_ids(target_devices)

    if not target_ids:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "No matching online devices found",
        }))
        return

    execute_msg = json.dumps({
        "type": "EXECUTE_LAUNCH",
        "package_name": package_name,
        "extra": extra_data,
    })

    sent_count = 0
    for did in target_ids:
        entry = devices.get(did)
        if entry:
            try:
                await entry["ws"].send_str(execute_msg)
                sent_count += 1
            except ConnectionResetError:
                log.warning("Failed to send EXECUTE_LAUNCH to %s (disconnected)", did)

    log.info("LAUNCH_APP: sent %s to %d/%d devices", package_name, sent_count, len(target_ids))

    await admin_ws.send_str(json.dumps({
        "type": "LAUNCH_SENT",
        "package_name": package_name,
        "sent_count": sent_count,
        "target_count": len(target_ids),
    }))


async def handle_install_apk(admin_ws: web.WebSocketResponse, data: dict):
    """Process an INSTALL_APK command from an admin and forward to target HMDs."""
    target_devices: list[str] = data.get("target_devices", [])
    apk_url: str = data.get("apk_url", "")
    apk_filename: str = data.get("apk_filename", "")

    if not apk_url:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "apk_url is required",
        }))
        return

    target_ids = resolve_target_ids(target_devices)

    if not target_ids:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "No matching online devices found",
        }))
        return

    execute_msg = json.dumps({
        "type": "EXECUTE_INSTALL",
        "apk_url": apk_url,
        "apk_filename": apk_filename,
    })

    sent_count = 0
    for did in target_ids:
        entry = devices.get(did)
        if entry:
            try:
                await entry["ws"].send_str(execute_msg)
                sent_count += 1
            except ConnectionResetError:
                log.warning("Failed to send EXECUTE_INSTALL to %s (disconnected)", did)

    log.info("INSTALL_APK: sent %s to %d/%d devices", apk_filename or apk_url, sent_count, len(target_ids))

    await admin_ws.send_str(json.dumps({
        "type": "INSTALL_SENT",
        "apk_filename": apk_filename,
        "apk_url": apk_url,
        "sent_count": sent_count,
        "target_count": len(target_ids),
    }))


# ---------------------------------------------------------------------------
# APK upload handler
# ---------------------------------------------------------------------------

async def upload_apk_handler(request: web.Request) -> web.Response:
    """Accept an APK upload and return the LAN-accessible download URL."""
    try:
        reader = await request.multipart()
    except Exception as e:
        return web.json_response({"error": f"Invalid multipart request: {e}"}, status=400)

    field = await reader.next()
    if field is None or field.name != "apk":
        return web.json_response({"error": "multipart field 'apk' is required"}, status=400)

    safe_name = sanitize_apk_filename(field.filename)
    if safe_name is None:
        return web.json_response({"error": "Uploaded file must have a .apk extension"}, status=400)

    APK_DIR.mkdir(parents=True, exist_ok=True)
    destination = unique_apk_path(safe_name)
    size = 0

    try:
        with destination.open("wb") as f:
            while True:
                chunk = await field.read_chunk(size=1024 * 1024)
                if not chunk:
                    break
                size += len(chunk)
                if size > MAX_APK_SIZE:
                    destination.unlink(missing_ok=True)
                    return web.json_response({"error": "APK exceeds 2 GiB limit"}, status=413)
                f.write(chunk)
    except Exception as e:
        destination.unlink(missing_ok=True)
        log.exception("Failed to store uploaded APK")
        return web.json_response({"error": f"Failed to store APK: {e}"}, status=500)

    if size == 0:
        destination.unlink(missing_ok=True)
        return web.json_response({"error": "Uploaded APK is empty"}, status=400)

    apk_url = f"{request.scheme}://{request.host}/apks/{destination.name}"
    log.info("Uploaded APK: %s (%d bytes)", destination.name, size)

    return web.json_response({
        "apk_filename": destination.name,
        "apk_url": apk_url,
        "size": size,
    })


async def handle_set_startup_app(admin_ws: web.WebSocketResponse, data: dict):
    """Process a SET_STARTUP_APP command from an admin and forward to target HMDs."""
    target_devices: list[str] = data.get("target_devices", [])
    package_name: str = data.get("package_name", "")
    extra_data: str = data.get("extra_data", "")

    if not package_name:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "package_name is required for SET_STARTUP_APP",
        }))
        return

    target_ids = resolve_target_ids(target_devices)

    if not target_ids:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "No matching online devices found",
        }))
        return

    set_msg = json.dumps({
        "type": "SET_STARTUP_APP",
        "package_name": package_name,
        "extra": extra_data,
    })

    sent_count = 0
    for did in target_ids:
        entry = devices.get(did)
        if entry:
            try:
                await entry["ws"].send_str(set_msg)
                sent_count += 1
                # Update server-side record
                entry["startup_app"] = {
                    "package_name": package_name,
                    "extra": extra_data,
                }
                rec = device_registry.get(did)
                if rec is not None:
                    rec["startup_app"] = entry["startup_app"]
            except ConnectionResetError:
                log.warning("Failed to send SET_STARTUP_APP to %s (disconnected)", did)

    save_registry()
    log.info("SET_STARTUP_APP: %s to %d/%d devices", package_name, sent_count, len(target_ids))

    await admin_ws.send_str(json.dumps({
        "type": "SET_STARTUP_APP_SENT",
        "package_name": package_name,
        "sent_count": sent_count,
        "target_count": len(target_ids),
    }))

    await broadcast_device_list()


async def handle_clear_startup_app(admin_ws: web.WebSocketResponse, data: dict):
    """Process a CLEAR_STARTUP_APP command from an admin and forward to target HMDs."""
    target_devices: list[str] = data.get("target_devices", [])

    target_ids = resolve_target_ids(target_devices)

    if not target_ids:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "No matching online devices found",
        }))
        return

    clear_msg = json.dumps({"type": "CLEAR_STARTUP_APP"})

    sent_count = 0
    for did in target_ids:
        entry = devices.get(did)
        if entry:
            try:
                await entry["ws"].send_str(clear_msg)
                sent_count += 1
                entry["startup_app"] = None
                rec = device_registry.get(did)
                if rec is not None:
                    rec["startup_app"] = None
            except ConnectionResetError:
                log.warning("Failed to send CLEAR_STARTUP_APP to %s (disconnected)", did)

    save_registry()
    log.info("CLEAR_STARTUP_APP: sent to %d/%d devices", sent_count, len(target_ids))

    await admin_ws.send_str(json.dumps({
        "type": "CLEAR_STARTUP_APP_SENT",
        "sent_count": sent_count,
        "target_count": len(target_ids),
    }))

    await broadcast_device_list()


# ---------------------------------------------------------------------------
# UDP Discovery Responder
# ---------------------------------------------------------------------------

# Discovery port can be overridden so a development server can run on the same
# LAN as production without the two stealing each other's clients. Defaults to
# the production port when MDM_DISCOVERY_PORT is unset.
DISCOVERY_PORT = int(os.environ.get("MDM_DISCOVERY_PORT", "7071"))
DISCOVERY_REQUEST = b"STYLYMDM_DISCOVER"


class _UdpDiscoveryProtocol(asyncio.DatagramProtocol):
    """Responds to UDP broadcast discovery requests with the server's WebSocket URL."""

    def __init__(self, ws_port: int, ip_addresses: list[str]):
        self._ws_port = ws_port
        self._ip_addresses = ip_addresses

    def connection_made(self, transport: asyncio.DatagramTransport):
        self._transport = transport

    def datagram_received(self, data: bytes, addr: tuple[str, int]):
        if data.strip() == DISCOVERY_REQUEST:
            server_ip = self._ip_addresses[0] if self._ip_addresses else "0.0.0.0"
            response = json.dumps({
                "service": "stylymdm",
                "ws_url": f"ws://{server_ip}:{self._ws_port}/ws/device",
                "version": "1.0",
            }).encode("utf-8")
            self._transport.sendto(response, addr)
            log.info("Discovery request from %s:%d -> responded with ws://%s:%d",
                     addr[0], addr[1], server_ip, self._ws_port)


async def start_discovery_responder(ws_port: int, ip_addresses: list[str]):
    """Start the UDP discovery responder as an asyncio task."""
    loop = asyncio.get_running_loop()
    transport, _ = await loop.create_datagram_endpoint(
        lambda: _UdpDiscoveryProtocol(ws_port, ip_addresses),
        local_addr=("0.0.0.0", DISCOVERY_PORT),
        family=socket.AF_INET,
        allow_broadcast=True,
    )
    log.info("UDP discovery responder listening on port %d", DISCOVERY_PORT)
    return transport


# ---------------------------------------------------------------------------
# Application setup
# ---------------------------------------------------------------------------

def create_app() -> web.Application:
    # Ensure the writable data directory exists before loading/saving the registry.
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    load_registry()
    app = web.Application(client_max_size=MAX_APK_SIZE + 10 * 1024 * 1024)

    # WebSocket endpoints
    app.router.add_get("/ws/device", device_ws_handler)
    app.router.add_get("/ws/admin", admin_ws_handler)
    app.router.add_post("/api/apks", upload_apk_handler)

    # Static files are shipped inside the package (styly_mdm/static/), so this
    # resolves both from source and when installed. It is read-only package data
    # and is never created at runtime.
    static_dir = Path(__file__).parent / "static"

    # Serve index.html at the root path so "/" loads the MDM console
    async def root_handler(_request: web.Request) -> web.FileResponse:
        return web.FileResponse(static_dir / "index.html")

    app.router.add_get("/", root_handler)
    app.router.add_static("/static", static_dir)

    APK_DIR.mkdir(parents=True, exist_ok=True)
    app.router.add_static("/apks", APK_DIR)

    return app


def get_local_ip_addresses() -> list[str]:
    """Return a list of non-loopback IPv4 addresses for this machine."""
    addresses: list[str] = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                addresses.append(ip)
    except OSError:
        pass
    # Deduplicate while preserving order
    return list(dict.fromkeys(addresses))


def _apply_data_dir(path: str) -> None:
    """Point the writable data directory (and its derived paths) at ``path``.

    Called from main() when --data-dir is passed. Reassigns the module globals so
    create_app() and the upload/registry handlers pick up the new location.
    """
    global DATA_DIR, APK_DIR, REGISTRY_PATH
    DATA_DIR = Path(path).resolve()
    APK_DIR = DATA_DIR / "apks"
    REGISTRY_PATH = DATA_DIR / "device_registry.json"


async def run_server(port: int | None = None):
    # WebSocket port is overridable (MDM_WS_PORT / --port) so a development server
    # can run alongside production on the same machine; the discovery response
    # advertises this port, so clients follow it automatically.
    if port is None:
        port = int(os.environ.get("MDM_WS_PORT", "7070"))
    app = create_app()
    ip_addresses = get_local_ip_addresses()

    log.info("Starting STYLY-MDM server on port %d", port)
    if ip_addresses:
        for ip in ip_addresses:
            log.info("  Server running at http://%s:%d", ip, port)
    else:
        log.info("  Server running at http://0.0.0.0:%d", port)

    # Start UDP discovery responder alongside the HTTP server
    discovery_transport = await start_discovery_responder(port, ip_addresses)

    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", port)
    await site.start()

    try:
        await asyncio.Event().wait()
    finally:
        discovery_transport.close()
        await runner.cleanup()


def main():
    parser = argparse.ArgumentParser(
        prog="styly-mdm",
        description="STYLY-MDM control server (WebSocket + web console + LAN discovery).",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=None,
        help="HTTP/WebSocket port (default: $MDM_WS_PORT or 7070).",
    )
    parser.add_argument(
        "--data-dir",
        default=None,
        help="Directory for uploaded APKs and the device registry "
        "(default: $MDM_DATA_DIR or the current directory).",
    )
    args = parser.parse_args()
    if args.data_dir is not None:
        _apply_data_dir(args.data_dir)
    asyncio.run(run_server(port=args.port))


if __name__ == "__main__":
    main()
