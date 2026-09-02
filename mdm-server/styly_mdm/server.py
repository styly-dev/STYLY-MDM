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
import shutil
import socket
import sys
import tempfile
import time
import uuid
import zipfile
from pathlib import Path
from urllib.parse import unquote
from aiohttp import WSCloseCode, web

# Bundling and integrity references must agree on what counts as content, or a reference
# built from a folder could never match the device that folder was pushed to.
# The hash helpers compute the reference a client checks a downloaded APK against
# before installing it (#39) — same algorithms as the #37 verify feature.
from .integrity import apk_cd_digest, file_sha256, is_os_metadata

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

# The signed styly-mdm-client APK matching this server is bundled inside the wheel
# (read-only package data) and seeded into APK_DIR on startup, so the console can
# offer it to out-of-date devices without an operator upload. Release APKs follow
# the styly-mdm-client_<version>.apk convention (.github/workflows/release.yml).
# The regex tolerates an optional "v" prefix and any collision suffix that
# unique_apk_path() appends on re-upload (e.g. "-20260712-200000", optionally plus
# "-<counter>"), so a re-uploaded build is still detected — the mtime tie-break in
# latest_client_apk() then prefers it over the seeded original.
BUNDLED_CLIENT_DIR = Path(__file__).resolve().parent / "client"
CLIENT_APK_RE = re.compile(r"^styly-mdm-client_v?(\d+(?:\.\d+)+)(?:-.*)?\.apk$")

# Maximum number of device downloads allowed in flight across the whole server.
# Fanning a file out to a large group otherwise makes every device pull it from the
# server at the same instant (an APK can be up to 2 GiB, a push bundle likewise),
# spiking LAN/server bandwidth and stalling transfers. Every byte-moving fan-out —
# install, push, sync — gates its dispatch on the same pool of this many slots; a
# slot frees as soon as its device reports the download finished. Read when the pool
# is first created so tests and the --max-concurrent-transfers flag can override it.
MAX_CONCURRENT_TRANSFERS = max(1, int(os.environ.get("MDM_MAX_CONCURRENT_TRANSFERS", "5")))

# Seconds a single device may hold a transfer slot before it is force-released, so
# a silent/offline/stuck device cannot block the queue indefinitely. Generous by
# default: a large APK over a slow LAN can legitimately take minutes. Lowering it
# recovers stuck slots sooner at the risk of releasing a slow-but-healthy transfer
# early, which only relaxes throttling and never drops the job itself.
TRANSFER_TIMEOUT = float(os.environ.get("MDM_TRANSFER_TIMEOUT", "600"))

# A stalled browser must not serialize all server-side publications behind its
# WebSocket. Each admin send is bounded independently in _broadcast_admin_message.
ADMIN_SEND_TIMEOUT = max(0.1, float(os.environ.get("MDM_ADMIN_SEND_TIMEOUT", "5")))

# The two kinds of byte-moving job. A device can be in one of each at the same time
# (an admin can push files to a group already installing an APK), so both the slot
# registry and the release path are keyed by task, never by device alone.
TASK_INSTALL = "install"
TASK_PUSH = "push"

# Seconds a self-updating client may stay away before the update is declared lost.
# The client schedules a device power cycle (shutdown at +2 min, startup at +3 min)
# before killing itself with the install, then boots and re-registers; the window
# must cover that whole cycle plus boot and reconnect backoff with margin.
SELF_UPDATE_TIMEOUT = float(os.environ.get("MDM_SELF_UPDATE_TIMEOUT", "480"))

# A device that has re-registered is online and responsive, so the post-update
# auto-verify should answer within seconds. Bound it anyway: a client that never
# returns VERIFY_APK_RESULT while staying connected would otherwise pin its entry
# (and the "verifying" phase) in memory until the next disconnect or update.
SELF_UPDATE_VERIFY_TIMEOUT = float(os.environ.get("MDM_SELF_UPDATE_VERIFY_TIMEOUT", "120"))

# Seconds a retiring client may stay silent before the retire is declared
# successful — for a retire (#49), silence IS the success signal: a removed
# client can send nothing. Both failure modes resurface well inside this window:
# a client whose uninstall never ran still holds its WebSocket (checked directly
# at the deadline), and one that was merely killed is restarted by the ToBService
# keep-alive and re-registers within the ≤30 s reconnect backoff. No reboot is
# involved, so this is much shorter than SELF_UPDATE_TIMEOUT.
RETIRE_TIMEOUT = float(os.environ.get("MDM_RETIRE_TIMEOUT", "120"))

# device_id -> in-flight client self-update, created when the device announces
# SELF_UPDATE_STARTING right before killing itself with the install:
#   {phase: "updating"|"verifying", correlation_id, target_version_code,
#    apk_filename, package_name, expected_full_sha256, expected_cd_sha256,
#    started_at, timeout_task}
# Deliberately in-memory: if the server restarts mid-update the device still
# recovers on its own (the power cycle is device-side) and simply re-registers
# through the no-pending path; only the "updating" label and the result report
# are lost.
pending_self_updates: dict[str, dict] = {}

# device_id -> in-flight retire (#49), created when the device announces
# SELF_UNINSTALL_STARTING right before uninstalling itself:
#   {correlation_id, started_at, timeout_task}
# The self-update semantics inverted: staying away past RETIRE_TIMEOUT is the
# success (the registry record is then flagged retired), re-registering is the
# failure. Deliberately in-memory: a server restart mid-retire loses the entry,
# so a device can never be marked retired spuriously — it simply shows offline
# and the operator re-checks it and uses Forget.
pending_retires: dict[str, dict] = {}

# device_id -> {apk_filename, full_sha256, cd_sha256} captured when EXECUTE_INSTALL
# is dispatched to that device. A self-update announced later (SELF_UPDATE_STARTING)
# verifies against these dispatched bytes rather than re-hashing whatever the
# client-echoed filename now points at: a same-name re-upload between dispatch and
# announcement — or an empty/wrong echo — would otherwise shift the verify
# reference off the file we actually told the device to install. Overwritten on the
# next dispatch to the device and evicted on disconnect, so it holds at most one
# entry per device. Hashes are None when the dispatched APK was not a local upload.
last_install_dispatch: dict[str, dict] = {}

# (path, size, mtime_ns) -> {"full_sha256", "cd_sha256"} for uploaded APKs, so a
# fan-out to N devices hashes the file once, not N times. Keyed on stat identity:
# replacing the file invalidates naturally.
_apk_hash_cache: dict[tuple[str, int, int], dict] = {}

# Generated file/folder bundles (for the push-files sync feature) live alongside the APKs.
# The console uploads a file or folder; the server zips it into BUNDLE_DIR and serves it to
# devices, which mirror it into a destination directory. Kept separate from APK_DIR so the
# APK workflow is untouched.
BUNDLE_DIR = DATA_DIR / "bundles"
MAX_BUNDLE_SIZE = 2 * 1024 * 1024 * 1024  # 2 GiB (total uploaded bytes before zipping)
MAX_BUNDLE_ENTRIES = 5000  # cap files per bundle to bound tree reconstruction

# Packages DELETE_APP refuses to touch (#63). Removing either of these behind the
# console's back would leave the device unmanaged: the client must only ever go
# through the ordered retire ceremony (RETIRE_DEVICE), which uninstalls the guard
# first, drops the keep-alive and announces itself before disappearing. Nothing
# enforces that these strings track the client's applicationId / GuardLink.
# GUARD_PACKAGE — the client re-checks against its own package names on arrival,
# so a drift here degrades to a device-side refusal rather than a lost device.
PROTECTED_PACKAGES = {"com.styly.mdmclient", "com.styly.mdmguard"}

# Persistent per-device registry: serial -> {label, model, ip, last_seen, startup_app, battery}
REGISTRY_PATH = DATA_DIR / "device_registry.json"
MAX_LABEL_LEN = 64
MAX_GROUP_NAME_LEN = 64

# Connected devices: device_id -> {ws, device_id, model, ip, status, startup_app, battery}
devices: dict[str, dict] = {}

# serial -> last-known record {label, model, ip, last_seen, startup_app, battery}.
# Remembers every device that has connected at least once so it stays listed
# while offline. Survives restarts (persisted to REGISTRY_PATH).
device_registry: dict[str, dict] = {}

# Named device groups (many-to-many): group name -> list of member serials. A
# device can belong to zero or more groups; membership is keyed by serial so an
# offline (or not-yet-registered) device can still be grouped. Persisted to
# REGISTRY_PATH alongside the device registry.
device_groups: dict[str, list[str]] = {}

# Connected admin WebSocket sessions
admin_connections: set[web.WebSocketResponse] = set()
_admin_send_locks: dict[web.WebSocketResponse, asyncio.Lock] = {}

# PushRuntime replaces only this server-local construction seam. Mutating
# aiohttp.web.WebSocketResponse process-wide changes unrelated applications and
# makes import order observable.
_websocket_response_factory = web.WebSocketResponse

# (device_id, task) -> Future for the transfer slot a device holds during a job.
# Resolving the future releases the slot so the next queued device is dispatched
# (see release_transfer_slot / _run_install_job / _run_push_job). Module-global so
# device-originated messages (DOWNLOAD_COMPLETE / INSTALL_RESULT / PUSH_FILES_RESULT /
# disconnect) can route a release to the dispatcher coroutine waiting on it. The task
# is part of the key so a push's terminal message cannot free the install slot the
# same device is holding, and vice versa.
pending_transfers: dict[tuple[str, str], "asyncio.Future[str]"] = {}

# Strong references to in-flight transfer-job tasks. asyncio only keeps weak
# references to tasks, so without this a backgrounded job could be garbage
# collected mid-run. Entries are discarded when the task finishes.
_transfer_tasks: set["asyncio.Task"] = set()

# Server-wide pool of transfer slots, shared by every byte-moving fan-out. The
# throttle belongs to the transfer *resource* (the LAN, the server's uplink), not to
# any one action: a per-job semaphore would let two overlapping install jobs run 2N
# transfers, and would leave a concurrent install and push blind to each other.
#
# Built lazily rather than at import because an asyncio.Semaphore binds to the first
# event loop that awaits it, and the test suite runs each case in a fresh loop.
_transfer_sem: "asyncio.Semaphore | None" = None
_transfer_sem_loop: "asyncio.AbstractEventLoop | None" = None


# ---------------------------------------------------------------------------
# Device registry (persistent, additive — never the identity key)
# ---------------------------------------------------------------------------

def _coerce_record(value) -> dict | None:
    """Normalize a registry value into a record.

    Accepts the legacy flat shape (a label string) and the current record shape
    (a dict). Returns None for anything else.
    """
    if isinstance(value, str):
        return {
            "label": value.strip(),
            "model": "",
            "ip": "",
            "last_seen": None,
            "startup_app": None,
            "battery": None,
            "version_code": None,
            "version_name": "",
            "retired": False,
            "push_state_retry_supported": False,
            "push_state_status": None,
        }
    if isinstance(value, dict):
        label = value.get("label", "")
        model = value.get("model", "")
        ip = value.get("ip", "")
        last_seen = value.get("last_seen")
        startup_app = value.get("startup_app")
        battery = _coerce_battery(value.get("battery"))
        version_code = value.get("version_code")
        if isinstance(version_code, bool) or not isinstance(version_code, int):
            version_code = None
        version_name = value.get("version_name")
        return {
            "label": label.strip() if isinstance(label, str) else "",
            "model": model if isinstance(model, str) else "",
            "ip": ip if isinstance(ip, str) else "",
            "last_seen": last_seen if isinstance(last_seen, (int, float)) else None,
            "startup_app": startup_app if isinstance(startup_app, dict) else None,
            "battery": battery,
            "version_code": version_code,
            "version_name": version_name if isinstance(version_name, str) else "",
            "retired": value.get("retired") is True,
            "push_state_retry_supported": value.get("push_state_retry_supported") is True,
            "push_state_status": value.get("push_state_status")
            if value.get("push_state_status") in {"available", "unavailable"}
            else None,
        }
    return None


def _coerce_battery(value) -> dict | None:
    """Normalize battery telemetry into {level, charging, last_seen}.

    Battery telemetry is optional for backward compatibility with older clients.
    """
    if not isinstance(value, dict):
        return None

    level = value.get("level")
    if isinstance(level, bool) or not isinstance(level, (int, float)):
        return None
    level = int(level)
    if level < 0 or level > 100:
        return None

    charging = value.get("charging")
    if not isinstance(charging, bool):
        return None

    last_seen = value.get("last_seen", value.get("timestamp"))
    if not isinstance(last_seen, (int, float)) or isinstance(last_seen, bool):
        last_seen = time.time()

    return {
        "level": level,
        "charging": charging,
        "last_seen": last_seen,
    }


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
            "battery": d.get("battery"),
            "last_seen": device_registry.get(d["device_id"], {}).get("last_seen"),
            "version_code": d.get("version_code"),
            "version_name": d.get("version_name", ""),
            "push_state_retry_supported": d.get("push_state_retry_supported", False),
            "push_state_status": d.get("push_state_status"),
        }
        for d in devices.values()
    ]
    for serial, rec in device_registry.items():
        if serial in devices:
            continue
        # A device that killed itself for a self-update is expected back within
        # minutes; label it "updating" rather than "offline" until it re-registers
        # or the update times out. A device that announced a self-uninstall is
        # "retiring" until its timeout settles it, and "retired" is the terminal
        # state a successful retire parks the record in.
        pend = pending_self_updates.get(serial)
        if serial in pending_retires:
            status = "retiring"
        elif rec.get("retired"):
            status = "retired"
        elif pend is not None and pend.get("phase") == "updating":
            status = "updating"
        else:
            status = "offline"
        device_list.append({
            "device_id": serial,
            "label": rec.get("label", ""),
            "model": rec.get("model", ""),
            "ip": rec.get("ip", ""),
            "status": status,
            "startup_app": rec.get("startup_app"),
            "battery": rec.get("battery"),
            "last_seen": rec.get("last_seen"),
            "version_code": rec.get("version_code"),
            "version_name": rec.get("version_name", ""),
            "push_state_retry_supported": rec.get("push_state_retry_supported", False),
            "push_state_status": rec.get("push_state_status"),
        })
    device_list.sort(
        key=lambda e: (e["label"] == "", (e["label"] or e["device_id"]).lower())
    )
    return json.dumps({"type": "DEVICE_LIST", "devices": device_list})


async def broadcast_device_list():
    """Send the current device list to every connected admin."""
    await _broadcast_admin_message(build_device_list_msg())


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
    await _broadcast_admin_message(build_group_list_msg())


async def _broadcast_admin_message(message: str) -> None:
    """Best-effort admin fan-out; one stale socket must not stop server work."""
    async def send_one(ws: web.WebSocketResponse) -> None:
        lock = _admin_send_locks.setdefault(ws, asyncio.Lock())
        try:
            async with lock:
                if ws not in admin_connections:
                    return
                await asyncio.wait_for(ws.send_str(message), ADMIN_SEND_TIMEOUT)
        except asyncio.CancelledError:
            raise
        except (Exception, asyncio.TimeoutError):
            log.warning("Could not send message to admin WebSocket", exc_info=True)
            admin_connections.discard(ws)
            _admin_send_locks.pop(ws, None)
            try:
                await asyncio.wait_for(
                    ws.close(
                        code=WSCloseCode.GOING_AWAY,
                        message=b"admin send failed",
                        drain=False,
                    ),
                    ADMIN_SEND_TIMEOUT,
                )
            except asyncio.CancelledError:
                raise
            except (Exception, asyncio.TimeoutError):
                log.warning("Could not close failed admin WebSocket", exc_info=True)

    connections = tuple(admin_connections)
    if not connections:
        return

    fanout = asyncio.gather(*(send_one(ws) for ws in connections))
    try:
        await asyncio.shield(fanout)
    except asyncio.CancelledError:
        # aiohttp may cancel a device request handler while its finally block is
        # broadcasting the disconnect. Keep the already-bounded admin sends alive
        # so the console receives that terminal state before cancellation escapes.
        await asyncio.shield(fanout)
        raise


async def forward_to_admins(payload: dict):
    """Forward a message (e.g. LAUNCH_RESULT) to all admin connections."""
    await _broadcast_admin_message(json.dumps(payload))


def _client_apk_version(name: str) -> tuple[str, tuple[int, ...]] | None:
    """Parse a styly-mdm-client APK filename into (version_str, version_tuple).

    Returns None when the name is not a client APK. The tuple is for ordering
    (so "0.10.0" > "0.2.0"); the string is what the console displays/compares.
    """
    m = CLIENT_APK_RE.match(name)
    if not m:
        return None
    ver_str = m.group(1)
    return ver_str, tuple(int(part) for part in ver_str.split("."))


def latest_client_apk() -> dict | None:
    """The newest styly-mdm-client APK in APK_DIR, or None if there is none.

    Newest wins by version tuple, ties broken by mtime (a re-upload of the same
    tag lands as ``..._v0.3.0-1.apk``). The returned url is relative to the server
    root; the console makes it absolute against its own origin, exactly like an
    operator-uploaded APK's url.
    """
    best: tuple[tuple[tuple[int, ...], float], Path, str] | None = None
    try:
        entries = list(APK_DIR.glob("*.apk"))
    except OSError:
        return None
    for path in entries:
        parsed = _client_apk_version(path.name)
        if parsed is None:
            continue
        ver_str, ver_tuple = parsed
        try:
            mtime = path.stat().st_mtime
        except OSError:
            continue
        key = (ver_tuple, mtime)
        if best is None or key > best[0]:
            best = (key, path, ver_str)
    if best is None:
        return None
    _, path, ver_str = best
    return {"filename": path.name, "url": f"/apks/{path.name}", "version": ver_str}


def client_apk_info_payload() -> dict:
    """The CLIENT_APK_INFO message body (apk is None when none is available)."""
    return {"type": "CLIENT_APK_INFO", "apk": latest_client_apk()}


def build_client_apk_msg() -> str:
    return json.dumps(client_apk_info_payload())


def seed_bundled_client_apk() -> None:
    """Copy the wheel-bundled signed client APK into the writable APK_DIR.

    The wheel ships the client APK under ``styly_mdm/client/`` (read-only
    site-packages); copying it into APK_DIR makes it served and detected exactly
    like an operator upload. Idempotent: names already present are left untouched,
    so an operator's own upload of the same file is never clobbered.
    """
    if not BUNDLED_CLIENT_DIR.is_dir():
        return
    APK_DIR.mkdir(parents=True, exist_ok=True)
    for src in sorted(BUNDLED_CLIENT_DIR.glob("*.apk")):
        if CLIENT_APK_RE.match(src.name) is None:
            continue
        dest = APK_DIR / src.name
        if dest.exists():
            continue
        try:
            shutil.copy2(src, dest)
            log.info("Seeded bundled client APK: %s", src.name)
        except OSError:
            log.exception("Failed to seed bundled client APK %s", src.name)


def transfer_slots() -> asyncio.Semaphore:
    """The server-wide transfer-slot pool, created on first use in this event loop.

    Sized from MAX_CONCURRENT_TRANSFERS at creation. It is deliberately never resized
    afterwards: swapping in a larger semaphore would strand the coroutines already
    parked on the old one and briefly allow twice the cap. The CLI flag and the env
    var are both read before the loop starts, so the size is settled by then.
    """
    global _transfer_sem, _transfer_sem_loop
    loop = asyncio.get_running_loop()
    if _transfer_sem is None or _transfer_sem_loop is not loop:
        _transfer_sem = asyncio.Semaphore(max(1, MAX_CONCURRENT_TRANSFERS))
        _transfer_sem_loop = loop
    return _transfer_sem


def reset_transfer_slots() -> None:
    """Drop the pool so the next transfer_slots() call rebuilds it. Tests only."""
    global _transfer_sem, _transfer_sem_loop
    _transfer_sem = None
    _transfer_sem_loop = None


def release_transfer_slot(device_id: str, reason: str, task: str | None = None) -> bool:
    """Free the transfer slot(s) a device holds, if any.

    Resolving the pending future wakes the dispatcher coroutine parked on it so the
    next queued device is dispatched. Safe to call for a device that holds no slot
    (an older client's INSTALL_RESULT, an idle device disconnecting) and safe to call
    more than once for the same device and task (e.g. DOWNLOAD_COMPLETE followed by
    INSTALL_RESULT) — the second call is a no-op.

    ``task`` names which job to release: a device may be transferring for an install
    and a push at the same time, and each job's terminal message must free only its
    own slot. Pass None to free every slot the device holds, which is what a
    disconnect means.

    Returns True only when this call actually released a live slot, so callers can
    tell a real transfer→apply transition from a late message for a device whose
    transfer already timed out or finished.
    """
    if task is not None:
        fut = pending_transfers.get((device_id, task))
        if fut is not None and not fut.done():
            fut.set_result(reason)
            return True
        return False

    released = False
    for (did, _task), fut in list(pending_transfers.items()):
        if did == device_id and not fut.done():
            fut.set_result(reason)
            released = True
    return released


async def broadcast_install_state(
    device_ids: list[str],
    state: str,
    apk_filename: str = "",
    detail: str = "",
) -> None:
    """Tell admins where the named devices are in an install job.

    INSTALL_PROGRESS carries only aggregate counts, which the console cannot map
    back to rows; this per-device companion drives the INSTALL column so a device
    waiting for a transfer slot is not shown as if it were already installing.

    States: queued -> transferring -> installing -> (INSTALL_RESULT: success/fail),
    with fail also emitted here for devices that never reach a client at all
    (offline before their turn, failed dispatch, transfer timeout). A client
    *self*-update adds updating (announced by SELF_UPDATE_STARTING, spanning the
    device's kill-and-power-cycle window) with success/fail emitted here when the
    device re-registers or the update times out.
    """
    if not device_ids:
        return
    await forward_to_admins({
        "type": "INSTALL_DEVICE_STATE",
        "device_ids": device_ids,
        "state": state,
        "apk_filename": apk_filename,
        "detail": detail,
    })


async def broadcast_push_state(
    device_ids: list[str],
    state: str,
    dest_path: str = "",
    delete_extras: bool = False,
    detail: str = "",
) -> None:
    """The push/sync counterpart of broadcast_install_state.

    States: queued -> transferring -> applying -> (PUSH_FILES_RESULT: success/fail),
    where applying covers the client's local unzip and mirror — the phase that runs
    after the slot is freed. As with install, fail is also emitted here for devices
    that never reach a client (offline before their turn, failed dispatch, timeout).

    ``delete_extras`` rides along so the console can name the action (Push vs Sync)
    without having to remember what it dispatched.
    """
    if not device_ids:
        return
    await forward_to_admins({
        "type": "PUSH_DEVICE_STATE",
        "device_ids": device_ids,
        "state": state,
        "dest_path": dest_path,
        "delete_extras": delete_extras,
        "detail": detail,
    })


async def _self_update_timeout(device_id: str, correlation_id: str) -> None:
    """Give up on a self-update whose device never re-registered.

    The client schedules a device power cycle before killing itself, so in every
    survivable outcome the device comes back with *some* version within a few
    minutes. A device still away after SELF_UPDATE_TIMEOUT is genuinely lost
    (powered off, boot loop, network gone): report a terminal result and let the
    row fall back to offline rather than showing "updating" forever.
    """
    # Cancellation (the device re-registered or the install failed) propagates:
    # swallowing it would make the task look "finished" instead of cancelled.
    await asyncio.sleep(SELF_UPDATE_TIMEOUT)
    entry = pending_self_updates.get(device_id)
    if (
        entry is None
        or entry.get("correlation_id") != correlation_id
        or entry.get("phase") != "updating"
    ):
        return
    del pending_self_updates[device_id]
    log.warning("Self-update timed out for %s (%s)", device_id, correlation_id)
    await forward_to_admins({
        "type": "SELF_UPDATE_RESULT",
        "device_id": device_id,
        "correlation_id": correlation_id,
        "status": "timeout",
        "version_code": None,
        "target_version_code": entry["target_version_code"],
        "detail": f"device did not re-register within {int(SELF_UPDATE_TIMEOUT)}s",
    })
    await broadcast_install_state(
        [device_id], "fail", entry["apk_filename"], "self-update timed out",
    )
    await broadcast_device_list()


async def _self_update_verify_timeout(device_id: str, correlation_id: str) -> None:
    """Close out a self-update stuck awaiting its auto-verify answer.

    The device already re-registered (the update itself reported success), but never
    returned the VERIFY_APK_RESULT for the server-initiated check. Report the
    verification as errored and drop the entry rather than leaving it "verifying"
    forever. Cancelled the moment the answer or a disconnect arrives.
    """
    await asyncio.sleep(SELF_UPDATE_VERIFY_TIMEOUT)
    entry = pending_self_updates.get(device_id)
    if (
        entry is None
        or entry.get("correlation_id") != correlation_id
        or entry.get("phase") != "verifying"
    ):
        return
    del pending_self_updates[device_id]
    log.warning("Self-update verify timed out for %s (%s)", device_id, correlation_id)
    await forward_to_admins({
        "type": "SELF_UPDATE_VERIFIED",
        "device_id": device_id,
        "correlation_id": correlation_id,
        "status": "error",
        "detail": f"device did not answer the verify command within {int(SELF_UPDATE_VERIFY_TIMEOUT)}s",
    })


async def _resolve_pending_self_update(
    device_id: str, ws: web.WebSocketResponse, version_code: int | None,
) -> None:
    """Settle an in-flight self-update when its device re-registers.

    Success means the device came back running at least the target versionCode.
    On success, when reference hashes exist, the update is additionally closed
    out with an automatic EXECUTE_VERIFY_APK against the client's own package;
    the answering VERIFY_APK_RESULT is consumed by _consume_self_update_verify.
    """
    entry = pending_self_updates.get(device_id)
    if entry is None or entry.get("phase") != "updating":
        return
    timeout_task = entry.get("timeout_task")
    if timeout_task is not None:
        timeout_task.cancel()
    target = entry["target_version_code"]
    success = version_code is not None and version_code >= target
    if success:
        detail = f"re-registered with version_code {version_code}"
    elif version_code is None:
        detail = "client reported no version_code"
    else:
        detail = f"re-registered with version_code {version_code}, expected >= {target}"
    log.info("Self-update %s for %s: %s",
             "succeeded" if success else "failed", device_id, detail)
    await forward_to_admins({
        "type": "SELF_UPDATE_RESULT",
        "device_id": device_id,
        "correlation_id": entry["correlation_id"],
        "status": "success" if success else "fail",
        "version_code": version_code,
        "target_version_code": target,
        "detail": detail,
    })
    await broadcast_install_state(
        [device_id], "success" if success else "fail", entry["apk_filename"], detail,
    )
    if not success:
        del pending_self_updates[device_id]
        return
    if entry.get("expected_full_sha256") and entry.get("package_name"):
        entry["phase"] = "verifying"
        entry["timeout_task"] = None
        try:
            await ws.send_str(json.dumps({
                "type": "EXECUTE_VERIFY_APK",
                "package_name": entry["package_name"],
            }))
        except Exception as e:
            del pending_self_updates[device_id]
            await forward_to_admins({
                "type": "SELF_UPDATE_VERIFIED",
                "device_id": device_id,
                "correlation_id": entry["correlation_id"],
                "status": "error",
                "detail": f"could not send the verify command: {e}",
            })
        else:
            # Only once the command is on the wire: bound the wait for its answer so
            # a silent client cannot pin the entry in the "verifying" phase forever.
            entry["timeout_task"] = asyncio.create_task(
                _self_update_verify_timeout(device_id, entry["correlation_id"])
            )
    else:
        del pending_self_updates[device_id]
        await forward_to_admins({
            "type": "SELF_UPDATE_VERIFIED",
            "device_id": device_id,
            "correlation_id": entry["correlation_id"],
            "status": "skipped",
            "detail": "no reference hashes for the installed APK",
        })


async def _consume_self_update_verify(device_id: str, data: dict) -> bool:
    """Absorb the VERIFY_APK_RESULT that answers a self-update auto-verify.

    Returns True when the message was consumed. It must NOT be forwarded raw:
    the console classifies a VERIFY_APK_RESULT against the reference the admin
    picked in the browser, and for this server-initiated check there is none —
    the row would flip to "✗ error (no local reference)". The comparison happens
    here instead, and only the outcome goes to admins.
    """
    entry = pending_self_updates.get(device_id)
    if entry is None or entry.get("phase") != "verifying":
        return False
    if data.get("package_name") != entry.get("package_name"):
        return False
    timeout_task = entry.get("timeout_task")
    if timeout_task is not None:
        timeout_task.cancel()
    del pending_self_updates[device_id]
    expected = entry.get("expected_full_sha256") or ""
    got = data.get("full_sha256")
    if not data.get("found"):
        status, detail = "error", "package not found after the update"
    elif not isinstance(got, str) or not got:
        status, detail = "error", "device reported no full_sha256"
    elif got.lower() == expected.lower():
        status = "verified"
        detail = f"installed APK matches the dispatched file (full_sha256 {expected[:12]}…)"
    else:
        status = "mismatch"
        detail = (
            f"installed full_sha256 {got[:12]}… does not match "
            f"dispatched {expected[:12]}…"
        )
    log.info("Self-update verification for %s: %s (%s)", device_id, status, detail)
    await forward_to_admins({
        "type": "SELF_UPDATE_VERIFIED",
        "device_id": device_id,
        "correlation_id": entry["correlation_id"],
        "status": status,
        "detail": detail,
    })
    return True


def _clear_pending_self_update_on_install_result(device_id: str) -> None:
    """Drop a pending self-update when its device reports INSTALL_RESULT.

    A *successful* self-update never produces one — the process is killed before
    the callback can fire — so an INSTALL_RESULT while phase is "updating" means
    the install failed on-device after SELF_UPDATE_STARTING was sent (e.g. the
    installer returned an error and the client cancelled its power cycle). The
    forwarded INSTALL_RESULT is the console's terminal state; the pending entry
    must not linger and fire a spurious timeout.
    """
    entry = pending_self_updates.get(device_id)
    if entry is None or entry.get("phase") != "updating":
        return
    timeout_task = entry.get("timeout_task")
    if timeout_task is not None:
        timeout_task.cancel()
    del pending_self_updates[device_id]
    log.info("Self-update cancelled by INSTALL_RESULT from %s", device_id)


async def _retire_timeout(device_id: str, correlation_id: str) -> None:
    """Settle a retire whose device stayed away — for a retire, silence is success.

    A removed client can send nothing, so the terminal signal is the absence of a
    re-registration for RETIRE_TIMEOUT after the announcement. The one failure
    mode a silent wait would misread — an uninstall that never ran at all, leaving
    the client alive and connected — is caught by checking the live connection at
    the deadline.
    """
    # Cancellation (the device re-registered, reported failure, or was forgotten)
    # propagates: swallowing it would make the task look "finished".
    await asyncio.sleep(RETIRE_TIMEOUT)
    entry = pending_retires.get(device_id)
    if entry is None or entry.get("correlation_id") != correlation_id:
        return
    del pending_retires[device_id]
    if device_id in devices:
        log.warning("Retire failed for %s: still connected (%s)", device_id, correlation_id)
        await forward_to_admins({
            "type": "RETIRE_RESULT",
            "device_id": device_id,
            "correlation_id": correlation_id,
            "status": "fail",
            "detail": (
                f"still connected {int(RETIRE_TIMEOUT)}s after announcing; "
                "the uninstall apparently never ran"
            ),
        })
        await broadcast_device_list()
        return
    # Tolerate a record forgotten mid-retire: the retire still happened, there is
    # just nothing left to flag.
    rec = device_registry.get(device_id)
    if rec is not None:
        rec["retired"] = True
        save_registry()
    log.info("Device retired: %s (%s)", device_id, correlation_id)
    await forward_to_admins({
        "type": "RETIRE_RESULT",
        "device_id": device_id,
        "correlation_id": correlation_id,
        "status": "success",
        "detail": f"no re-registration within {int(RETIRE_TIMEOUT)}s of the announcement",
    })
    await broadcast_device_list()


async def _resolve_pending_retire(device_id: str) -> None:
    """Fail an in-flight retire when its device re-registers.

    The inverse of _resolve_pending_self_update: a retiring device reporting for
    duty means the uninstall did not stick (ToBService refused it, or the process
    was merely killed and the keep-alive restarted it).
    """
    entry = pending_retires.pop(device_id, None)
    if entry is None:
        return
    timeout_task = entry.get("timeout_task")
    if timeout_task is not None:
        timeout_task.cancel()
    log.warning("Retire failed for %s: device re-registered", device_id)
    await forward_to_admins({
        "type": "RETIRE_RESULT",
        "device_id": device_id,
        "correlation_id": entry["correlation_id"],
        "status": "fail",
        "detail": "device re-registered; the uninstall did not complete",
    })


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


def _compute_apk_hashes(path: Path) -> dict:
    """Blocking hash pass over an APK file; run in an executor."""
    return {
        "full_sha256": file_sha256(str(path)),
        "cd_sha256": apk_cd_digest(str(path))[1],
    }


async def apk_hashes_for(apk_filename: str) -> dict | None:
    """Reference hashes for an uploaded APK, or None when it cannot be hashed.

    None covers: an invalid/traversal filename, a file that is not in APK_DIR
    (e.g. the admin pointed apk_url somewhere else), or a file that fails to
    parse as a ZIP. EXECUTE_INSTALL then simply omits the hash fields — a normal
    install proceeds unverified as before, while a client refuses a *self*-update
    without them (a bad self-update bricks the client, so it never installs
    unverified bytes over itself).
    """
    safe = sanitize_apk_filename(apk_filename)
    if safe is None:
        return None
    path = APK_DIR / safe
    try:
        st = path.stat()
    except OSError:
        return None
    key = (str(path), st.st_size, st.st_mtime_ns)
    cached = _apk_hash_cache.get(key)
    if cached is not None:
        return cached
    loop = asyncio.get_running_loop()
    try:
        hashes = await loop.run_in_executor(None, _compute_apk_hashes, path)
    except (OSError, ValueError) as e:
        log.warning("Could not hash %s for install verification: %s", safe, e)
        return None
    if len(_apk_hash_cache) >= 16:
        _apk_hash_cache.clear()
    _apk_hash_cache[key] = hashes
    return hashes


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
# File/folder bundle helpers (push-files sync)
# ---------------------------------------------------------------------------

# Primary/shared external storage mount points on Android. Push-files destinations must live
# under one of these: the PICO ToBService exposes no privileged file-copy API, so the client
# can only reach shared storage with plain java.io.File I/O (app-scoped Android/data/<pkg>/
# is unreadable across UIDs — the same wall the APK download documents).
SHARED_STORAGE_PREFIXES = ("/sdcard/", "/storage/emulated/0/")

# Well-known top-level directories under shared storage that full-mirror sync must never
# target: mirroring into them would delete unrelated user/media/app data on every selected
# device. Matched case-insensitively against the first segment below the storage root.
PROTECTED_TOPLEVEL_DIRS = frozenset({
    "android", "download", "downloads", "dcim", "pictures", "movies", "music",
    "documents", "alarms", "notifications", "podcasts", "ringtones",
})


def sanitize_relpath(raw: str | None) -> str | None:
    """Return a safe forward-slash relative path for a bundle entry, or None if invalid.

    Rejects absolute paths, parent traversal (``..``) and NUL; drops empty/``.`` segments and
    normalizes backslashes. Used to reconstruct an uploaded folder tree under a temp root
    without any entry escaping it.
    """
    if not raw:
        return None
    text = raw.replace("\\", "/")
    if "\x00" in text:
        return None
    parts: list[str] = []
    for seg in text.split("/"):
        seg = seg.strip()
        if seg in ("", "."):
            continue
        if seg == "..":
            return None
        parts.append(seg)
    if not parts:
        return None
    return "/".join(parts)


def validate_dest_path(dest: str | None) -> str | None:
    """Return an error message if a device destination path is unsafe, else None.

    A full-mirror sync deletes anything at the destination not in the bundle, so a wrong path
    would wipe unrelated data on every selected device. Pushes are held to the same rule, so a
    copy cannot quietly reach somewhere a sync may not. The path must be an absolute
    shared-storage path, contain no ``..``, and be neither the storage root nor a protected
    top-level directory. This is a first-line syntactic guard; the client re-validates
    against the real canonical filesystem before applying.
    """
    if not isinstance(dest, str) or not dest.strip():
        return "Destination path is required"
    text = dest.strip()
    if "\x00" in text:
        return "Destination path contains an invalid character"
    if not text.startswith("/"):
        return "Destination must be an absolute path"
    # Reject on the raw path (matching the client) so a '..' that would normalize away here
    # can't be accepted by the server yet rejected by the client's stricter raw check.
    if ".." in text.split("/"):
        return "Destination path must not contain '..'"
    with_slash = os.path.normpath(text).rstrip("/") + "/"
    prefix = next((p for p in SHARED_STORAGE_PREFIXES if with_slash.startswith(p)), None)
    if prefix is None:
        return "Destination must be under shared storage (/sdcard or /storage/emulated/0)"
    remainder = with_slash[len(prefix):].strip("/")
    if not remainder:
        return "Destination must be a subdirectory, not the shared-storage root"
    first_segment = remainder.split("/")[0]
    if first_segment.lower() in PROTECTED_TOPLEVEL_DIRS:
        return f"Destination must not be inside the protected '{first_segment}' directory"
    return None


def unique_bundle_path(filename: str) -> Path:
    """Build a unique destination path in BUNDLE_DIR for a generated bundle zip."""
    destination = BUNDLE_DIR / filename
    if not destination.exists():
        return destination

    stem = destination.stem
    suffix = destination.suffix
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    candidate = BUNDLE_DIR / f"{stem}-{timestamp}{suffix}"
    counter = 2
    while candidate.exists():
        candidate = BUNDLE_DIR / f"{stem}-{timestamp}-{counter}{suffix}"
        counter += 1
    return candidate


def bundle_basename(relpaths: list[str]) -> str:
    """Derive a safe base name (no extension) for the bundle zip from its entries.

    A single file → its stem; a folder tree sharing one top-level directory → that directory
    name; otherwise → ``bundle``.
    """
    if not relpaths:
        return "bundle"
    if len(relpaths) == 1 and "/" not in relpaths[0]:
        raw = Path(relpaths[0]).stem or "bundle"
    else:
        first_segments = {p.split("/")[0] for p in relpaths}
        raw = next(iter(first_segments)) if len(first_segments) == 1 else "bundle"
    safe = re.sub(r"[^A-Za-z0-9._-]", "_", raw).strip("._-")
    return safe or "bundle"


def strip_common_root(relpaths: list[str]) -> tuple[str | None, list[str]]:
    """If every entry is nested under one shared top-level directory, return
    ``(root_name, paths_without_root)``; otherwise ``(None, relpaths)``.

    A folder pick (``webkitdirectory``) yields paths all prefixed with the picked
    folder's name (``myfolder/a.txt``, ``myfolder/sub/b.txt``). The destination should
    mirror that folder's *contents* (``a.txt``, ``sub/b.txt``) — "identical to the pushed
    folder" — so the shared root is stripped from the archived paths and reused as the
    bundle name. Single-file or multi-file picks (a bare filename among the entries) have
    no shared directory root and are returned unchanged.
    """
    if not relpaths or any("/" not in p for p in relpaths):
        return None, relpaths
    roots = {p.split("/", 1)[0] for p in relpaths}
    if len(roots) != 1:
        return None, relpaths
    root = next(iter(roots))
    return root, [p.split("/", 1)[1] for p in relpaths]


def zip_tree(root: Path, dest_zip: Path) -> None:
    """Zip every file under ``root`` into ``dest_zip`` using forward-slash relative names."""
    with zipfile.ZipFile(dest_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(root.rglob("*")):
            if path.is_file():
                zf.write(path, path.relative_to(root).as_posix())


# ---------------------------------------------------------------------------
# Device WebSocket handler  (/ws/device)
# ---------------------------------------------------------------------------

def _owns_device(device_id: str | None, ws: web.WebSocketResponse) -> bool:
    """True when ``ws`` is the connection that currently owns ``device_id``.

    A reboot does not always close the client's socket cleanly, so the server can
    still hold the pre-reboot connection open (it only notices at the next
    heartbeat) when the rebooted client has already reconnected and re-registered.
    Both sockets then carry the same device_id, and the newest REGISTER is the
    owner: ``devices[device_id]["ws"]`` is where commands are sent, so it is also
    the only connection allowed to mutate that device's state or tear it down
    (#70).
    """
    entry = devices.get(device_id) if device_id else None
    return entry is not None and entry.get("ws") is ws


async def device_ws_handler(request: web.Request) -> web.WebSocketResponse:
    ws = _websocket_response_factory(heartbeat=30)
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

                # A newer REGISTER has taken ownership of this device_id, so this
                # socket is a leftover from a reboot the server has not noticed yet.
                # It must not advance or settle any per-device state — releasing a
                # transfer slot or forwarding a terminal result from here would
                # finish the *live* connection's job behind its back (#70). REGISTER
                # is the one exception: that is how a connection claims ownership.
                if msg_type != "REGISTER" and device_id and not _owns_device(device_id, ws):
                    log.info("Ignoring %s from superseded connection: %s",
                             msg_type, device_id)
                    continue

                if msg_type == "REGISTER":
                    device_id = data.get("device_id")
                    if not device_id:
                        log.warning("REGISTER missing device_id")
                        continue
                    model = data.get("model", "unknown")
                    ip = data.get("ip", request.remote or "unknown")
                    startup_app = data.get("startup_app")
                    version_code = data.get("version_code")
                    if isinstance(version_code, bool) or not isinstance(version_code, int):
                        version_code = None
                    version_name = data.get("version_name")
                    if not isinstance(version_name, str):
                        version_name = ""
                    capabilities = data.get("capabilities")
                    retry_supported = (
                        isinstance(capabilities, list)
                        and "push_state_retry_v1" in capabilities
                    )
                    push_state = data.get("push_state")
                    push_state_status = (
                        push_state.get("status")
                        if isinstance(push_state, dict)
                        and push_state.get("status") in {"available", "unavailable"}
                        else None
                    )
                    prev = device_registry.get(device_id, {})
                    devices[device_id] = {
                        "ws": ws,
                        "device_id": device_id,
                        "model": model,
                        "ip": ip,
                        "status": "online",
                        "startup_app": startup_app,
                        "battery": prev.get("battery"),
                        "version_code": version_code,
                        "version_name": version_name,
                        "push_state_retry_supported": retry_supported,
                        "push_state_status": push_state_status,
                    }
                    # Remember the device persistently so it stays in the list while
                    # offline; preserve any previously assigned label.
                    device_registry[device_id] = {
                        "label": prev.get("label", ""),
                        "model": model,
                        "ip": ip,
                        "last_seen": time.time(),
                        "startup_app": startup_app,
                        "battery": prev.get("battery"),
                        "version_code": version_code,
                        "version_name": version_name,
                        "push_state_retry_supported": retry_supported,
                        "push_state_status": push_state_status,
                    }
                    save_registry()
                    log.info(
                        "Device registered: %s (%s, client v%s)",
                        device_id, model, version_code if version_code is not None else "?",
                    )
                    # A registration during a pending retire is the retire's
                    # failure signal. Independently, a retired device reporting
                    # for duty means its client was reinstalled: the registry
                    # rewrite above deliberately dropped the terminal retired
                    # flag, re-adopting the device.
                    if prev.get("retired"):
                        log.info("Retired device re-registered, re-adopting: %s", device_id)
                    await _resolve_pending_retire(device_id)
                    # A re-registration is also how a self-updated client reports
                    # back for duty: settle any pending update before the list
                    # broadcast so admins see the result, then the row transition.
                    await _resolve_pending_self_update(device_id, ws, version_code)
                    await broadcast_device_list()

                elif msg_type == "BATTERY_UPDATE":
                    if not device_id:
                        log.warning("BATTERY_UPDATE before REGISTER")
                        continue
                    battery = _coerce_battery({
                        "level": data.get("level"),
                        "charging": data.get("charging"),
                        "last_seen": data.get("timestamp"),
                    })
                    if battery is None:
                        log.warning("Invalid BATTERY_UPDATE from %s: %s", device_id, data)
                        continue
                    # Use server receipt time for stale-display semantics; client clocks
                    # can drift, and the console only needs last-known freshness.
                    battery["last_seen"] = time.time()
                    # The ownership guard above the dispatch chain has already dropped
                    # frames from a superseded socket, so the entry is present here.
                    devices[device_id]["battery"] = battery
                    rec = device_registry.get(device_id)
                    if rec is not None:
                        rec["battery"] = battery
                        rec["last_seen"] = time.time()
                    save_registry()
                    log.info(
                        "Battery update from %s: %d%% charging=%s",
                        device_id, battery["level"], battery["charging"],
                    )
                    await broadcast_device_list()

                elif msg_type == "DOWNLOAD_COMPLETE":
                    # Primary transfer-slot release: the network-heavy download has
                    # finished, so free this device's slot right away and let the
                    # local install (or unzip/mirror) proceed without holding up the
                    # queue. `task` names which job finished downloading; clients that
                    # predate push throttling only ever send it for an install.
                    if device_id:
                        task = data.get("task") or TASK_INSTALL
                        released = release_transfer_slot(
                            device_id, "download_complete", task=task,
                        )
                        log.info(
                            "DOWNLOAD_COMPLETE (%s) from %s: %s",
                            task, device_id,
                            data.get("apk_filename") or data.get("dest_path", ""),
                        )
                        # Announce the transferring -> installing/applying transition
                        # here, in the receive loop, so it is ordered before the
                        # terminal result that follows on this same connection.
                        # Emitting it from the dispatcher instead would race that
                        # result and could leave the console stuck on "Installing…".
                        # Skipped when the slot was already gone (timed out), so a
                        # late message cannot resurrect a device the job has written
                        # off as failed.
                        if released and task == TASK_PUSH:
                            await broadcast_push_state(
                                [device_id], "applying", data.get("dest_path", ""),
                                bool(data.get("delete_extras")),
                            )
                        elif released:
                            await broadcast_install_state(
                                [device_id], "installing", data.get("apk_filename", ""),
                            )
                    else:
                        log.warning("DOWNLOAD_COMPLETE before REGISTER")

                elif msg_type == "SELF_UPDATE_STARTING":
                    # The client's last words before killing itself with the
                    # install: remember the update so the coming disconnect reads
                    # as "updating" rather than "offline", and so the re-register
                    # can be matched back by correlation id.
                    if not device_id:
                        log.warning("SELF_UPDATE_STARTING before REGISTER")
                        continue
                    correlation_id = data.get("correlation_id")
                    target_vc = data.get("target_version_code")
                    if (
                        not isinstance(correlation_id, str) or not correlation_id
                        or isinstance(target_vc, bool) or not isinstance(target_vc, int)
                    ):
                        log.warning(
                            "Malformed SELF_UPDATE_STARTING from %s: %s",
                            device_id, raw_msg.data[:200],
                        )
                        continue
                    apk_filename = data.get("apk_filename", "")
                    prev_pend = pending_self_updates.get(device_id)
                    if prev_pend is not None and prev_pend.get("timeout_task"):
                        prev_pend["timeout_task"].cancel()
                    # Prefer the hashes captured when EXECUTE_INSTALL was dispatched
                    # to this device: they pin the verify reference to the bytes we
                    # actually sent, immune to a same-name re-upload or a wrong/empty
                    # echo. Fall back to hashing the echoed filename only when there
                    # is no dispatch record (e.g. the server restarted mid-update).
                    dispatched = last_install_dispatch.get(device_id)
                    if dispatched is not None:
                        if apk_filename and apk_filename != dispatched.get("apk_filename"):
                            log.warning(
                                "SELF_UPDATE_STARTING from %s echoed apk_filename %r but "
                                "last dispatch was %r; verifying against the dispatched file",
                                device_id, apk_filename, dispatched.get("apk_filename"),
                            )
                        expected = {
                            "full_sha256": dispatched.get("full_sha256"),
                            "cd_sha256": dispatched.get("cd_sha256"),
                        }
                    else:
                        expected = await apk_hashes_for(apk_filename) or {}
                    entry = {
                        "phase": "updating",
                        "correlation_id": correlation_id,
                        "target_version_code": target_vc,
                        "apk_filename": apk_filename,
                        "package_name": data.get("package_name", ""),
                        "expected_full_sha256": expected.get("full_sha256"),
                        "expected_cd_sha256": expected.get("cd_sha256"),
                        "started_at": time.time(),
                        # The entry holds the only strong reference to its task.
                        "timeout_task": None,
                    }
                    pending_self_updates[device_id] = entry
                    entry["timeout_task"] = asyncio.create_task(
                        _self_update_timeout(device_id, correlation_id)
                    )
                    log.info(
                        "Self-update starting on %s: -> version_code %s (%s)",
                        device_id, target_vc, correlation_id,
                    )
                    await broadcast_install_state([device_id], "updating", apk_filename)

                elif msg_type == "SELF_UNINSTALL_STARTING":
                    # The client's last words before uninstalling itself (#49):
                    # remember the retire so the coming disconnect reads as
                    # "retiring" rather than "offline", and start the clock —
                    # silence past RETIRE_TIMEOUT is the success signal.
                    if not device_id:
                        log.warning("SELF_UNINSTALL_STARTING before REGISTER")
                        continue
                    correlation_id = data.get("correlation_id")
                    if not isinstance(correlation_id, str) or not correlation_id:
                        log.warning(
                            "Malformed SELF_UNINSTALL_STARTING from %s: %s",
                            device_id, raw_msg.data[:200],
                        )
                        continue
                    prev_pend = pending_retires.get(device_id)
                    if prev_pend is not None and prev_pend.get("timeout_task"):
                        prev_pend["timeout_task"].cancel()
                    # A retire supersedes any in-flight self-update: drop its
                    # pending entry (whatever phase) so its timeout cannot later
                    # fire a spurious result for a deliberately removed device.
                    stale = pending_self_updates.pop(device_id, None)
                    if stale is not None and stale.get("timeout_task"):
                        stale["timeout_task"].cancel()
                    entry = {
                        "correlation_id": correlation_id,
                        "started_at": time.time(),
                        # The entry holds the only strong reference to its task.
                        "timeout_task": None,
                    }
                    pending_retires[device_id] = entry
                    entry["timeout_task"] = asyncio.create_task(
                        _retire_timeout(device_id, correlation_id)
                    )
                    log.info(
                        "Self-uninstall starting on %s (%s)", device_id, correlation_id,
                    )

                elif msg_type == "SELF_UNINSTALL_RESULT":
                    # Only failures arrive on this type: a successful self-uninstall
                    # kills the client before any callback can run. Settle the
                    # pending retire (when the failure came after the announcement)
                    # and surface the failure to admins.
                    if not device_id:
                        log.warning("SELF_UNINSTALL_RESULT before REGISTER")
                        continue
                    entry = pending_retires.pop(device_id, None)
                    if entry is not None and entry.get("timeout_task"):
                        entry["timeout_task"].cancel()
                    detail = data.get("detail")
                    if not isinstance(detail, str) or not detail:
                        detail = "client reported the uninstall failed"
                    log.warning("Self-uninstall failed on %s: %s", device_id, detail)
                    await forward_to_admins({
                        "type": "RETIRE_RESULT",
                        "device_id": device_id,
                        "correlation_id": data.get("correlation_id"),
                        "status": "fail",
                        "detail": detail,
                    })

                elif msg_type in {
                    "LAUNCH_RESULT",
                    "REBOOT_RESULT",
                    "POWER_OFF_RESULT",
                    "INSTALL_RESULT",
                    "PUSH_FILES_RESULT",
                    "VERIFY_APK_RESULT",
                    "VERIFY_DIR_RESULT",
                    "DELETE_APP_RESULT",
                }:
                    if device_id:
                        data.setdefault("device_id", device_id)
                    # Fallback transfer-slot release: an older client that never
                    # emits DOWNLOAD_COMPLETE, or a client whose download failed and
                    # jumped straight to a terminal result, frees its slot here. A
                    # no-op if DOWNLOAD_COMPLETE already released it. Each result only
                    # ever frees its own job's slot.
                    if device_id and msg_type == "INSTALL_RESULT":
                        release_transfer_slot(device_id, "install_result", task=TASK_INSTALL)
                        _clear_pending_self_update_on_install_result(device_id)
                    elif device_id and msg_type == "PUSH_FILES_RESULT":
                        release_transfer_slot(device_id, "push_files_result", task=TASK_PUSH)
                    elif device_id and msg_type == "VERIFY_APK_RESULT":
                        if await _consume_self_update_verify(device_id, data):
                            continue
                    log.info("%s from %s: %s", msg_type, device_id, data.get("status"))
                    await forward_to_admins(data)
                    # An uninstalled package cannot stay the device's startup app,
                    # so the client clears the setting before removing it (#63).
                    # Only a successful uninstall is acted on: on a failure the
                    # client restores what it cleared, and the app is still
                    # installed either way.
                    #
                    # What is cleared is the *record naming the removed package* —
                    # never merely "whatever is recorded now", and not on the
                    # client's startup_app_cleared flag alone. A SET_STARTUP_APP
                    # issued while the uninstall was in flight is already recorded
                    # here (handle_set_startup_app records at dispatch), and a
                    # late result must not wipe that newer, still-valid setting.
                    if (
                        device_id
                        and msg_type == "DELETE_APP_RESULT"
                        and data.get("status") == "success"
                        and _startup_app_is(device_id, data.get("package_name"))
                    ):
                        _clear_startup_app_record(device_id)
                        await broadcast_device_list()

                elif msg_type == "STARTUP_APP_RESULT":
                    log.info("Startup app result from %s: %s", device_id, data.get("status"))
                    await forward_to_admins(data)

                else:
                    log.warning("Unknown message type from device: %s", msg_type)

            elif raw_msg.type == web.WSMsgType.ERROR:
                log.error("Device WS error: %s", ws.exception())
    finally:
        # Every teardown step below belongs to the connection that currently owns
        # the device_id: a superseded socket must not free the live connection's
        # transfer slots, settle its pending state, or delete its registration —
        # that last one left a connected device stuck on "offline" until the next
        # BATTERY_UPDATE crashed the survivor (#70). See _owns_device.
        is_current = _owns_device(device_id, ws)
        if device_id and not is_current:
            log.info("Superseded device connection closed, ignoring: %s", device_id)
        if device_id and is_current:
            # Install keeps the legacy disconnect release. Push is deliberately
            # retained by the runtime adapter because its Android HTTP worker outlives
            # this WebSocket and remains bounded by exact completion or timeout.
            release_transfer_slot(device_id, "disconnect")
            # The dispatch record only bridges dispatch -> SELF_UPDATE_STARTING; by a
            # disconnect that announcement has already copied what it needed, so drop
            # it rather than let it linger until the next install overwrites it.
            last_install_dispatch.pop(device_id, None)
            # A disconnect during a pending retire is likewise the *expected*
            # teardown: the entry survives (no code needed — it lives in
            # pending_retires) and the broadcast below renders the row as
            # "retiring" until _retire_timeout settles it.
            # A disconnect during a pending self-update is the *expected* kill-and-
            # power-cycle, so the entry survives and the list broadcast below renders
            # the row as "updating". A disconnect during the post-update verification
            # phase, however, means the answer will never arrive: close it out.
            pend = pending_self_updates.get(device_id)
            if pend is not None and pend.get("phase") == "verifying":
                verify_timeout = pend.get("timeout_task")
                if verify_timeout is not None:
                    verify_timeout.cancel()
                del pending_self_updates[device_id]
                await forward_to_admins({
                    "type": "SELF_UPDATE_VERIFIED",
                    "device_id": device_id,
                    "correlation_id": pend["correlation_id"],
                    "status": "error",
                    "detail": "device disconnected before verification completed",
                })
        if device_id and is_current:
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
    # On aiohttp 3.14.3, the server-side reader rejected Chrome's first PUSH_FILES
    # text frame after a long upload with "Received frame with non-zero reserved
    # bits". The failure was observed only on the inbound Chrome admin frame.
    # The device channel receives compressed OkHttp frames through the same
    # reader, so device-side immunity is unverified and remains under observation.
    # Admin messages are small control JSON, so compression saves effectively
    # nothing on this channel.
    # Keep compression enabled for the device channel, where messages can be
    # larger, but remove the extension from this browser-facing channel.
    ws = _websocket_response_factory(heartbeat=30, compress=False)
    await ws.prepare(request)
    admin_connections.add(ws)
    log.info("Admin console connected from %s", request.remote)

    # Send server info, current device list and group list immediately on connect.
    # __version__ is imported lazily to avoid a circular import (see run_server).
    from . import __version__
    try:
        await ws.send_str(json.dumps({"type": "SERVER_INFO", "version": __version__}))
        await ws.send_str(build_client_apk_msg())
        await ws.send_str(build_device_list_msg())
        await ws.send_str(build_group_list_msg())
    except ConnectionResetError:
        admin_connections.discard(ws)
        _admin_send_locks.pop(ws, None)
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

                elif msg_type == "DELETE_APP":
                    await handle_delete_app(ws, data)

                elif msg_type == "REBOOT_DEVICE":
                    await handle_reboot_device(ws, data)

                elif msg_type == "POWER_OFF_DEVICE":
                    await handle_power_off_device(ws, data)

                elif msg_type == "INSTALL_APK":
                    await handle_install_apk(ws, data)

                elif msg_type == "PUSH_FILES":
                    await handle_push_files(ws, data)

                elif msg_type == "VERIFY_APK":
                    await handle_verify_apk(ws, data)

                elif msg_type == "VERIFY_DIR":
                    await handle_verify_dir(ws, data)

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

                elif msg_type == "RETIRE_DEVICE":
                    await handle_retire_device(ws, data)

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
        _admin_send_locks.pop(ws, None)
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
    # A forget mid-"retiring" must also drop the pending retire, or its timeout
    # would later report a result for a device the operator already removed.
    pend = pending_retires.pop(serial, None)
    if pend is not None and pend.get("timeout_task"):
        pend["timeout_task"].cancel()
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


def _startup_app_is(device_id: str, package_name: str | None) -> bool:
    """True when the device's recorded startup app is this package."""
    if not package_name:
        return False
    entry = devices.get(device_id) or device_registry.get(device_id) or {}
    startup = entry.get("startup_app")
    return isinstance(startup, dict) and startup.get("package_name") == package_name


def _clear_startup_app_record(device_id: str) -> None:
    """Forget a device's startup app after the client cleared it on the device."""
    entry = devices.get(device_id)
    if entry is not None:
        entry["startup_app"] = None
    rec = device_registry.get(device_id)
    if rec is not None:
        rec["startup_app"] = None
    save_registry()
    log.info("Startup app cleared on %s (its package was uninstalled)", device_id)


async def handle_delete_app(admin_ws: web.WebSocketResponse, data: dict):
    """Process a DELETE_APP command from an admin and uninstall on target HMDs (#63).

    The launch-style un-throttled fan-out: nothing is transferred, so there is no
    slot to take. The devices answer individually with DELETE_APP_RESULT, which
    the device loop relays to every admin.
    """
    target_devices: list[str] = data.get("target_devices", [])
    package_name: str = data.get("package_name", "").strip()

    if not package_name:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "package_name is required",
        }))
        return

    if package_name in PROTECTED_PACKAGES:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": (
                f"{package_name} belongs to STYLY-MDM itself; "
                "use Retire Device to remove the MDM client"
            ),
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
        "type": "EXECUTE_UNINSTALL",
        "package_name": package_name,
    })

    sent_count = 0
    for did in target_ids:
        entry = devices.get(did)
        if entry:
            try:
                await entry["ws"].send_str(execute_msg)
                sent_count += 1
            except ConnectionResetError:
                log.warning("Failed to send EXECUTE_UNINSTALL to %s (disconnected)", did)

    log.info("DELETE_APP: sent %s to %d/%d devices", package_name, sent_count, len(target_ids))

    await admin_ws.send_str(json.dumps({
        "type": "DELETE_APP_SENT",
        "package_name": package_name,
        "sent_count": sent_count,
        "target_count": len(target_ids),
    }))


async def handle_reboot_device(admin_ws: web.WebSocketResponse, data: dict):
    """Process a REBOOT_DEVICE command from an admin and reboot target HMDs."""
    await _dispatch_power_command(
        admin_ws, data,
        execute_type="EXECUTE_REBOOT",
        sent_type="REBOOT_SENT",
        label="REBOOT_DEVICE",
    )


async def handle_power_off_device(admin_ws: web.WebSocketResponse, data: dict):
    """Process a POWER_OFF_DEVICE command from an admin and shut down target HMDs."""
    await _dispatch_power_command(
        admin_ws, data,
        execute_type="EXECUTE_POWER_OFF",
        sent_type="POWER_OFF_SENT",
        label="POWER_OFF_DEVICE",
    )


async def _dispatch_power_command(
    admin_ws: web.WebSocketResponse,
    data: dict,
    *,
    execute_type: str,
    sent_type: str,
    label: str,
):
    """Fan a payload-less power command out to the resolved target device sockets.

    Reboot and power-off carry no transfer and no per-device state, so this is
    the un-throttled launch-style fan-out, not the queued install path. The
    device acknowledges receipt with a ``*_RESULT: accepted`` before it invokes
    the SDK call — a successful reboot/shutdown tears the socket down before any
    success frame could flush, so the real success signal is the device going
    offline (and, for reboot, reconnecting), tracked by the connection status.
    """
    target_devices: list[str] = data.get("target_devices", [])
    target_ids = resolve_target_ids(target_devices)

    if not target_ids:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "No matching online devices found",
        }))
        return

    execute_msg = json.dumps({"type": execute_type})

    sent_count = 0
    for did in target_ids:
        entry = devices.get(did)
        if entry:
            try:
                await entry["ws"].send_str(execute_msg)
                sent_count += 1
            except ConnectionResetError:
                log.warning("Failed to send %s to %s (disconnected)", execute_type, did)

    log.info("%s: sent to %d/%d devices", label, sent_count, len(target_ids))

    await admin_ws.send_str(json.dumps({
        "type": sent_type,
        "sent_count": sent_count,
        "target_count": len(target_ids),
    }))


async def handle_retire_device(admin_ws: web.WebSocketResponse, data: dict):
    """Process a RETIRE_DEVICE command: tell target clients to uninstall themselves.

    Retiring is remotely irreversible — recovery means physical adb access per
    unit — so the console gates this behind its heaviest confirmation. Only
    online devices can be retired (the uninstall has to run on the device); an
    offline unit must be booted first. Each dispatch carries a fresh
    server-generated correlation id that the client echoes back in
    SELF_UNINSTALL_STARTING, tying announcement and result to this fan-out.
    """
    target_devices: list[str] = data.get("target_devices", [])
    target_ids = resolve_target_ids(target_devices)

    if not target_ids:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "No matching online devices found",
        }))
        return

    sent_count = 0
    for did in target_ids:
        entry = devices.get(did)
        if entry:
            try:
                await entry["ws"].send_str(json.dumps({
                    "type": "EXECUTE_SELF_UNINSTALL",
                    "correlation_id": uuid.uuid4().hex,
                }))
                sent_count += 1
            except ConnectionResetError:
                log.warning("Failed to send EXECUTE_SELF_UNINSTALL to %s (disconnected)", did)

    log.info("RETIRE_DEVICE: sent to %d/%d devices", sent_count, len(target_ids))

    await admin_ws.send_str(json.dumps({
        "type": "RETIRE_SENT",
        "sent_count": sent_count,
        "target_count": len(target_ids),
    }))


async def handle_install_apk(admin_ws: web.WebSocketResponse, data: dict):
    """Process an INSTALL_APK command from an admin and forward to target HMDs.

    The EXECUTE_INSTALL fan-out is throttled: the actual dispatch runs as a
    background job (see _run_install_job) that acquires from the server-wide pool of
    MAX_CONCURRENT_TRANSFERS transfer slots, so a large group does not make every
    device pull the APK from the server at the same instant. This handler validates
    the request, acknowledges it, and returns immediately so the admin can keep
    issuing commands while the (potentially minutes-long) job proceeds.
    """
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

    await admin_ws.send_str(json.dumps({
        "type": "INSTALL_SENT",
        "apk_filename": apk_filename,
        "apk_url": apk_url,
        "target_count": len(target_ids),
        "max_concurrent": MAX_CONCURRENT_TRANSFERS,
    }))

    task = asyncio.create_task(_run_install_job(apk_url, apk_filename, target_ids))
    _transfer_tasks.add(task)
    task.add_done_callback(_transfer_tasks.discard)


async def _run_install_job(apk_url: str, apk_filename: str, target_ids: list[str]):
    """Fan out EXECUTE_INSTALL to target devices with bounded concurrency.

    Dispatch is gated on the server-wide transfer pool, so at most
    MAX_CONCURRENT_TRANSFERS devices are downloading at once across every install
    and push job combined. A device's slot is freed as soon as it reports
    DOWNLOAD_COMPLETE (primary), sends a terminal INSTALL_RESULT (fallback for older
    clients / download-then-fail), disconnects, or hits the per-device timeout.
    Aggregate progress is broadcast to all admins as INSTALL_PROGRESS so the console
    can show queued / in-progress / done counts, and each device's own transition is
    broadcast as INSTALL_DEVICE_STATE so the console can label its row waiting /
    transferring / installing rather than showing the whole target set as if it were
    installing at once.
    """
    timeout = TRANSFER_TIMEOUT
    total = len(target_ids)
    loop = asyncio.get_running_loop()
    sem = transfer_slots()

    execute_payload = {
        "type": "EXECUTE_INSTALL",
        "apk_url": apk_url,
        "apk_filename": apk_filename,
    }
    # Reference hashes of the file being dispatched, so the client can verify the
    # download before invoking the installer (#39). Hashed once per job, not per
    # device. Absent when the APK is not a local upload — see apk_hashes_for.
    hashes = await apk_hashes_for(apk_filename)
    if hashes:
        execute_payload.update(hashes)
    execute_msg = json.dumps(execute_payload)

    # Job-local progress counters. The invariant
    #   total == queued + transferring + transferred + failed
    # holds after every transition below (single-threaded event loop, so no lock).
    counts = {"queued": total, "transferring": 0, "transferred": 0, "failed": 0}

    async def broadcast_progress(done: bool = False):
        await forward_to_admins({
            "type": "INSTALL_PROGRESS",
            "apk_filename": apk_filename,
            "apk_url": apk_url,
            "total": total,
            "queued": counts["queued"],
            "transferring": counts["transferring"],
            "transferred": counts["transferred"],
            "failed": counts["failed"],
            "done": done,
        })

    async def fail_one(device_id: str, detail: str):
        counts["failed"] += 1
        await broadcast_install_state([device_id], "fail", apk_filename, detail)
        await broadcast_progress()

    async def dispatch_one(device_id: str):
        # The semaphore IS the concurrency cap: at most MAX_CONCURRENT_TRANSFERS
        # coroutines hold it at once — server-wide, across every transfer job — so at
        # most that many downloads are live.
        async with sem:
            counts["queued"] -= 1
            entry = devices.get(device_id)
            if entry is None:
                # Went offline (or never online) before its turn — do not hold a
                # slot waiting for a device that cannot download.
                await fail_one(device_id, "Device went offline before its turn")
                return
            try:
                await entry["ws"].send_str(execute_msg)
            except Exception as e:
                # Any send failure (disconnect mid-fan-out, closing transport) is a
                # failed dispatch; the semaphore is freed by the async-with on exit.
                log.warning("Failed to send EXECUTE_INSTALL to %s: %s", device_id, e)
                await fail_one(device_id, "Failed to send the install command")
                return

            # Remember exactly what we dispatched, so a self-update this device may
            # announce next verifies against these bytes (see last_install_dispatch).
            last_install_dispatch[device_id] = {
                "apk_filename": apk_filename,
                "full_sha256": (hashes or {}).get("full_sha256"),
                "cd_sha256": (hashes or {}).get("cd_sha256"),
            }

            counts["transferring"] += 1
            key = (device_id, TASK_INSTALL)
            fut: "asyncio.Future[str]" = loop.create_future()
            pending_transfers[key] = fut
            try:
                await broadcast_install_state([device_id], "transferring", apk_filename)
                await broadcast_progress()
                reason = await asyncio.wait_for(fut, timeout)
                counts["transferred"] += 1
                log.info("Transfer slot released for %s (%s)", device_id, reason)
                # The installing state is announced by the DOWNLOAD_COMPLETE handler,
                # and success/fail by the forwarded INSTALL_RESULT, so nothing to emit
                # here: doing so would race those messages.
            except asyncio.TimeoutError:
                counts["failed"] += 1
                log.warning(
                    "Transfer slot timeout for %s after %.0fs; releasing to keep the queue moving",
                    device_id, timeout,
                )
                await broadcast_install_state(
                    [device_id], "fail", apk_filename, "Transfer timed out",
                )
            except Exception:
                # Never let one device's failure abort the whole job; keep the
                # invariant (this device counts as failed, not lost).
                counts["failed"] += 1
                log.exception("Unexpected error awaiting transfer completion for %s", device_id)
                await broadcast_install_state(
                    [device_id], "fail", apk_filename, "Transfer failed",
                )
            finally:
                # Remove only our own future — a later job targeting the same
                # device may already have replaced the entry.
                if pending_transfers.get(key) is fut:
                    del pending_transfers[key]
                counts["transferring"] -= 1
            await broadcast_progress()

    log.info(
        "INSTALL_APK: dispatching %s to %d device(s), max %d concurrent transfer(s) server-wide",
        apk_filename or apk_url, total, MAX_CONCURRENT_TRANSFERS,
    )
    try:
        # Every target starts out waiting for a slot. One batched message keeps a
        # large group from costing one broadcast per device before work begins.
        await broadcast_install_state(target_ids, "queued", apk_filename)
        await broadcast_progress()
        # return_exceptions=True so an unexpected failure in one dispatch never
        # prevents the siblings from finishing or the terminal progress below.
        await asyncio.gather(*(dispatch_one(did) for did in target_ids), return_exceptions=True)
    finally:
        await broadcast_progress(done=True)
    log.info(
        "INSTALL_APK: %s finished — %d transferred, %d failed/timed out of %d",
        apk_filename or apk_url, counts["transferred"], counts["failed"], total,
    )


async def handle_push_files(admin_ws: web.WebSocketResponse, data: dict):
    """Process a PUSH_FILES command: validate then fan out EXECUTE_PUSH_FILES to target HMDs.

    ``delete_extras`` selects the semantics the client applies: false (the default) copies and
    overwrites without deleting anything; true full-mirrors, pruning whatever at the destination
    is not in the bundle. The server only passes the flag through — the delete itself lives in
    the client — but it coerces the value here so a malformed field cannot decode to true.

    The destination is validated here as a first-line guard (a mirror deletes extras, so a bad
    path is destructive); the client re-validates against its real filesystem before applying.

    Like INSTALL_APK, the fan-out is throttled against the server-wide transfer pool (a push
    bundle can be as large as an APK), so this handler validates, acknowledges, and returns
    while _run_push_job dispatches in the background.
    """
    target_devices: list[str] = data.get("target_devices", [])
    bundle_url: str = data.get("bundle_url", "")
    bundle_filename: str = data.get("bundle_filename", "")
    dest_path: str = (data.get("dest_path") or "").strip()
    delete_extras: bool = data.get("delete_extras") is True

    if not bundle_url:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "bundle_url is required",
        }))
        return

    dest_error = validate_dest_path(dest_path)
    if dest_error is not None:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": dest_error,
        }))
        return

    target_ids = resolve_target_ids(target_devices)

    if not target_ids:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "No matching online devices found",
        }))
        return

    await admin_ws.send_str(json.dumps({
        "type": "PUSH_FILES_SENT",
        "bundle_filename": bundle_filename,
        "dest_path": dest_path,
        "delete_extras": delete_extras,
        "target_count": len(target_ids),
        "max_concurrent": MAX_CONCURRENT_TRANSFERS,
    }))

    task = asyncio.create_task(
        _run_push_job(bundle_url, bundle_filename, dest_path, delete_extras, target_ids)
    )
    _transfer_tasks.add(task)
    task.add_done_callback(_transfer_tasks.discard)


async def _run_push_job(
    bundle_url: str,
    bundle_filename: str,
    dest_path: str,
    delete_extras: bool,
    target_ids: list[str],
):
    """Fan out EXECUTE_PUSH_FILES to target devices with bounded concurrency.

    The install-job twin (_run_install_job): same slot pool, same release triggers, same
    shape of progress. They stay two functions because everything else — the message they
    dispatch, the counters, the states they name — is theirs alone; only the pool and
    ``pending_transfers`` are shared, and the counters here are strictly job-local.

    A device's slot is freed as soon as it reports DOWNLOAD_COMPLETE for the push
    (primary — the unzip and mirror that follow move no bytes over the network), sends a
    terminal PUSH_FILES_RESULT (fallback for older clients, which never signal the
    download separately), disconnects, or hits the per-device timeout.
    """
    timeout = TRANSFER_TIMEOUT
    total = len(target_ids)
    loop = asyncio.get_running_loop()
    sem = transfer_slots()
    verb = "sync" if delete_extras else "push"

    execute_msg = json.dumps({
        "type": "EXECUTE_PUSH_FILES",
        "bundle_url": bundle_url,
        "bundle_filename": bundle_filename,
        "dest_path": dest_path,
        "delete_extras": delete_extras,
    })

    # Job-local progress counters. The invariant
    #   total == queued + transferring + transferred + failed
    # holds after every transition below (single-threaded event loop, so no lock).
    counts = {"queued": total, "transferring": 0, "transferred": 0, "failed": 0}

    async def broadcast_progress(done: bool = False):
        await forward_to_admins({
            "type": "PUSH_PROGRESS",
            "bundle_filename": bundle_filename,
            "dest_path": dest_path,
            "delete_extras": delete_extras,
            "total": total,
            "queued": counts["queued"],
            "transferring": counts["transferring"],
            "transferred": counts["transferred"],
            "failed": counts["failed"],
            "done": done,
        })

    async def fail_one(device_id: str, detail: str):
        counts["failed"] += 1
        await broadcast_push_state([device_id], "fail", dest_path, delete_extras, detail)
        await broadcast_progress()

    async def dispatch_one(device_id: str):
        async with sem:
            counts["queued"] -= 1
            entry = devices.get(device_id)
            if entry is None:
                await fail_one(device_id, "Device went offline before its turn")
                return
            try:
                await entry["ws"].send_str(execute_msg)
            except Exception as e:
                log.warning("Failed to send EXECUTE_PUSH_FILES to %s: %s", device_id, e)
                await fail_one(device_id, f"Failed to send the {verb} command")
                return

            counts["transferring"] += 1
            key = (device_id, TASK_PUSH)
            fut: "asyncio.Future[str]" = loop.create_future()
            pending_transfers[key] = fut
            try:
                await broadcast_push_state(
                    [device_id], "transferring", dest_path, delete_extras,
                )
                await broadcast_progress()
                reason = await asyncio.wait_for(fut, timeout)
                counts["transferred"] += 1
                log.info("Transfer slot released for %s (%s)", device_id, reason)
                # "applying" is announced by the DOWNLOAD_COMPLETE handler, and
                # success/fail by the forwarded PUSH_FILES_RESULT, so nothing to emit
                # here: doing so would race those messages.
            except asyncio.TimeoutError:
                counts["failed"] += 1
                log.warning(
                    "Transfer slot timeout for %s after %.0fs; releasing to keep the queue moving",
                    device_id, timeout,
                )
                await broadcast_push_state(
                    [device_id], "fail", dest_path, delete_extras, "Transfer timed out",
                )
            except Exception:
                counts["failed"] += 1
                log.exception("Unexpected error awaiting transfer completion for %s", device_id)
                await broadcast_push_state(
                    [device_id], "fail", dest_path, delete_extras, "Transfer failed",
                )
            finally:
                if pending_transfers.get(key) is fut:
                    del pending_transfers[key]
                counts["transferring"] -= 1
            await broadcast_progress()

    log.info(
        "PUSH_FILES (%s): dispatching %s -> %s to %d device(s), "
        "max %d concurrent transfer(s) server-wide",
        verb, bundle_filename or bundle_url, dest_path, total, MAX_CONCURRENT_TRANSFERS,
    )
    try:
        await broadcast_push_state(target_ids, "queued", dest_path, delete_extras)
        await broadcast_progress()
        await asyncio.gather(*(dispatch_one(did) for did in target_ids), return_exceptions=True)
    finally:
        await broadcast_progress(done=True)
    log.info(
        "PUSH_FILES (%s): %s finished — %d transferred, %d failed/timed out of %d",
        verb, bundle_filename or bundle_url, counts["transferred"], counts["failed"], total,
    )


# ---------------------------------------------------------------------------
# Integrity verification (issue #37) — relay only; no hashing on the server.
# The reference is computed client-side in the browser (or via `styly-mdm hash`)
# and the match/mismatch comparison happens in the browser. The server just fans
# EXECUTE_VERIFY_* out to devices, exactly like INSTALL_APK, and forwards each
# device's VERIFY_*_RESULT back to the admins (see the device forward set above).
# ---------------------------------------------------------------------------

async def _fanout_execute(
    admin_ws: web.WebSocketResponse,
    target_devices: list[str],
    execute_msg: str,
    verb: str,
) -> tuple[int, int] | None:
    """Send an already-serialized EXECUTE_* message to the resolved online targets.

    Returns (sent_count, target_count), or None (after sending an ERROR to the admin)
    when no online target matched.
    """
    target_ids = resolve_target_ids(target_devices)
    if not target_ids:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "No matching online devices found",
        }))
        return None

    sent_count = 0
    for did in target_ids:
        entry = devices.get(did)
        if entry:
            try:
                await entry["ws"].send_str(execute_msg)
                sent_count += 1
            except ConnectionResetError:
                log.warning("Failed to send %s to %s (disconnected)", verb, did)

    return sent_count, len(target_ids)


async def handle_verify_apk(admin_ws: web.WebSocketResponse, data: dict):
    """Forward a VERIFY_APK command to target HMDs (mirrors handle_install_apk).

    VERIFY_APK carries only {target_devices, package_name}; the local reference
    (size + Central-Directory digest) never leaves the browser.
    """
    package_name: str = data.get("package_name", "")
    if not package_name:
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "package_name is required",
        }))
        return

    execute_msg = json.dumps({
        "type": "EXECUTE_VERIFY_APK",
        "package_name": package_name,
    })
    result = await _fanout_execute(
        admin_ws, data.get("target_devices", []), execute_msg, "EXECUTE_VERIFY_APK"
    )
    if result is None:
        return
    sent_count, target_count = result
    log.info("VERIFY_APK: sent %s to %d/%d devices", package_name, sent_count, target_count)

    await admin_ws.send_str(json.dumps({
        "type": "VERIFY_SENT",
        "package_name": package_name,
        "sent_count": sent_count,
        "target_count": target_count,
    }))


def is_syntactically_safe_verify_path(path: str) -> bool:
    """Light syntactic guard for a device directory path (VERIFY_DIR).

    Verification only reads, so this is advisory; the device performs the
    authoritative canonical check that the path stays within shared external
    storage. Here we just reject the obviously-wrong: empty, relative, or
    containing a `..` traversal component.
    """
    if not path or not path.startswith("/"):
        return False
    parts = [p for p in path.split("/") if p]
    return ".." not in parts


async def handle_verify_dir(admin_ws: web.WebSocketResponse, data: dict):
    """Forward a VERIFY_DIR command to target HMDs.

    VERIFY_DIR carries only {target_devices, path}; the local reference
    (manifest + tree hash) never leaves the browser.
    """
    path: str = data.get("path", "")
    if not is_syntactically_safe_verify_path(path):
        await admin_ws.send_str(json.dumps({
            "type": "ERROR",
            "message": "A valid absolute path without '..' is required",
        }))
        return

    execute_msg = json.dumps({
        "type": "EXECUTE_VERIFY_DIR",
        "path": path,
    })
    result = await _fanout_execute(
        admin_ws, data.get("target_devices", []), execute_msg, "EXECUTE_VERIFY_DIR"
    )
    if result is None:
        return
    sent_count, target_count = result
    log.info("VERIFY_DIR: sent %s to %d/%d devices", path, sent_count, target_count)

    await admin_ws.send_str(json.dumps({
        "type": "VERIFY_DIR_SENT",
        "path": path,
        "sent_count": sent_count,
        "target_count": target_count,
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

    # A freshly uploaded client APK may be the newest one — refresh the console's
    # per-device Update buttons for every admin, not just the uploader.
    await forward_to_admins(client_apk_info_payload())

    return web.json_response({
        "apk_filename": destination.name,
        "apk_url": apk_url,
        "size": size,
    })


# ---------------------------------------------------------------------------
# File/folder bundle upload handler
# ---------------------------------------------------------------------------

async def upload_bundle_handler(request: web.Request) -> web.Response:
    """Accept a file/folder upload, zip it into a bundle, and return its download URL.

    Each multipart part is one file whose *filename* carries its (possibly nested) relative
    path, so a whole folder tree can be uploaded with its structure preserved (the console
    sends ``file.webkitRelativePath``). Parts are reconstructed under a temp directory with
    per-path sanitization, then zipped; the temp tree is discarded and only the zip is kept.
    """
    try:
        reader = await request.multipart()
    except Exception as e:
        return web.json_response({"error": f"Invalid multipart request: {e}"}, status=400)

    BUNDLE_DIR.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix="upload-", dir=BUNDLE_DIR))
    total_size = 0
    relpaths: list[str] = []
    skipped: list[str] = []

    try:
        while True:
            field = await reader.next()
            if field is None:
                break
            if field.name != "files":
                continue

            # Browsers send the folder-relative path as the multipart filename; some
            # multipart encoders percent-encode the path separators, so decode before
            # sanitizing (sanitize_relpath still rejects any traversal after decoding).
            raw_name = field.filename
            relpath = sanitize_relpath(unquote(raw_name) if raw_name else None)
            if relpath is None:
                return web.json_response(
                    {"error": f"Invalid file path in upload: {field.filename!r}"}, status=400)

            # Drop OS metadata before it is written to staging, so it never reaches the zip
            # and never counts against the entry limit. reader.next() drains the skipped part.
            if is_os_metadata(relpath):
                skipped.append(relpath)
                continue

            if len(relpaths) >= MAX_BUNDLE_ENTRIES:
                return web.json_response(
                    {"error": f"Bundle exceeds the {MAX_BUNDLE_ENTRIES}-file limit"}, status=413)

            target = staging / relpath
            # Defense in depth: a sanitized relpath cannot escape, but confirm the join stays
            # under the staging root before writing.
            if not _is_within(staging, target):
                return web.json_response({"error": "Invalid file path in upload"}, status=400)

            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("wb") as f:
                while True:
                    chunk = await field.read_chunk(size=1024 * 1024)
                    if not chunk:
                        break
                    total_size += len(chunk)
                    if total_size > MAX_BUNDLE_SIZE:
                        return web.json_response({"error": "Bundle exceeds 2 GiB limit"}, status=413)
                    f.write(chunk)
            relpaths.append(relpath)

        if not relpaths:
            if skipped:
                return web.json_response(
                    {"error": "Upload contained only OS metadata files; nothing to send"},
                    status=400)
            return web.json_response({"error": "multipart field 'files' is required"}, status=400)

        # A folder pick nests every file under the picked folder; mirror that folder's
        # *contents* into the destination, so zip from the shared root (and name the
        # bundle after it) rather than wrapping the destination in an extra directory.
        root, _stripped = strip_common_root(relpaths)
        content_root = staging / root if root is not None else staging
        base = bundle_basename([root]) if root is not None else bundle_basename(relpaths)
        bundle_path = unique_bundle_path(f"{base}.zip")
        zip_tree(content_root, bundle_path)
    except Exception as e:
        log.exception("Failed to build uploaded bundle")
        return web.json_response({"error": f"Failed to build bundle: {e}"}, status=500)
    finally:
        shutil.rmtree(staging, ignore_errors=True)

    size = bundle_path.stat().st_size
    bundle_url = f"{request.scheme}://{request.host}/bundles/{bundle_path.name}"
    log.info("Built bundle: %s (%d entries, %d bytes zipped)",
             bundle_path.name, len(relpaths), size)
    if skipped:
        log.info("Excluded %d OS metadata file(s) from %s: %s",
                 len(skipped), bundle_path.name, ", ".join(skipped[:10]))

    return web.json_response({
        "bundle_filename": bundle_path.name,
        "bundle_url": bundle_url,
        "size": size,
        "entry_count": len(relpaths),
        "skipped_count": len(skipped),
    })


def _is_within(root: Path, target: Path) -> bool:
    """Return True if ``target`` resolves to a path inside ``root``."""
    try:
        target.resolve().relative_to(root.resolve())
        return True
    except ValueError:
        return False


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


def source_ip_for(peer: str) -> str | None:
    """Return the local IPv4 the kernel would use to reach ``peer``, or None.

    A connected UDP socket sends nothing — ``connect()`` only consults the routing
    table — so this is a cheap, side-effect-free way to ask "which of my interfaces
    faces this device?". That question is the whole point: a machine with several
    interfaces (Wi-Fi plus an unplugged Ethernet holding a self-assigned
    169.254.x.x, or Windows ICS alongside an uplink NIC) has no single "own" IP,
    and the right answer differs per requester.
    """
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect((peer, 9))
            ip = s.getsockname()[0]
    except OSError:
        return None
    return ip if ip and not ip.startswith("127.") else None


class _UdpDiscoveryProtocol(asyncio.DatagramProtocol):
    """Responds to UDP broadcast discovery requests with the server's WebSocket URL."""

    def __init__(self, ws_port: int, ip_addresses: list[str]):
        self._ws_port = ws_port
        self._ip_addresses = ip_addresses

    def connection_made(self, transport: asyncio.DatagramTransport):
        self._transport = transport

    def datagram_received(self, data: bytes, addr: tuple[str, int]):
        if data.strip() == DISCOVERY_REQUEST:
            # Answer with the address on the interface facing *this* requester. The
            # startup-time list is only a fallback: it is fixed at boot and cannot
            # know which network a device broadcast from.
            server_ip = source_ip_for(addr[0])
            if server_ip is None:
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


def probe_existing_server(timeout: float = 1.0) -> str | None:
    """Return the IP of another STYLY-MDM server already answering discovery
    requests on ``DISCOVERY_PORT``, or ``None`` if none responds.

    Broadcasts a single discovery request and waits up to ``timeout`` seconds for
    a valid ``stylymdm`` reply. Called before this process binds its own discovery
    responder, so any reply can only come from a *different* server (elsewhere on
    the LAN or a second instance on this host). The returned value is the
    responder's packet source IP (``addr[0]``) -- its real network identity,
    which is more reliable than the advertised ``ws_url``.

    A bind-and-catch check would only catch a same-host second instance; the
    cross-machine duplicate this guards against is only observable by putting a
    packet on the wire.

    Best-effort: two servers starting at the same instant may both probe before
    either responder is listening and miss each other. Never raises -- any socket
    failure returns ``None`` so a probe problem cannot block startup.
    """
    deadline = time.monotonic() + timeout
    sock: socket.socket | None = None
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.sendto(DISCOVERY_REQUEST, ("<broadcast>", DISCOVERY_PORT))
        # Keep reading until the deadline so a stray unrelated packet on the port
        # does not mask a real server's reply. The per-recv timeout shrinks toward
        # the deadline, bounding total probe time to ``timeout``.
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return None
            sock.settimeout(remaining)
            data, addr = sock.recvfrom(1024)
            try:
                payload = json.loads(data.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                continue  # not a STYLY-MDM reply; keep listening until the deadline
            if isinstance(payload, dict) and payload.get("service") == "stylymdm":
                return addr[0]
    except socket.timeout:
        return None
    except OSError as exc:
        log.debug("Discovery probe skipped: %s", exc)
        return None
    finally:
        if sock is not None:
            sock.close()


# ---------------------------------------------------------------------------
# Application setup
# ---------------------------------------------------------------------------

def create_app() -> web.Application:
    # Ensure the writable data directory exists before loading/saving the registry.
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    load_registry()
    app = web.Application(
        client_max_size=max(MAX_APK_SIZE, MAX_BUNDLE_SIZE) + 10 * 1024 * 1024)

    # WebSocket endpoints
    app.router.add_get("/ws/device", device_ws_handler)
    app.router.add_get("/ws/admin", admin_ws_handler)
    app.router.add_post("/api/apks", upload_apk_handler)
    app.router.add_post("/api/bundles", upload_bundle_handler)

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
    seed_bundled_client_apk()
    app.router.add_static("/apks", APK_DIR)

    BUNDLE_DIR.mkdir(parents=True, exist_ok=True)
    app.router.add_static("/bundles", BUNDLE_DIR)

    return app


def get_local_ip_addresses() -> list[str]:
    """Return this machine's usable non-loopback IPv4 addresses.

    Feeds the startup banner and the discovery responder's fallback. Link-local
    (169.254.0.0/16) addresses are dropped: they are self-assigned to interfaces
    that failed DHCP — an unplugged Ethernet port or a Thunderbolt bridge — so they
    are never reachable from a device on the real LAN. Advertising one to a client
    is strictly worse than advertising nothing.
    """
    addresses: list[str] = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and not ip.startswith("169.254."):
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
    global DATA_DIR, APK_DIR, BUNDLE_DIR, REGISTRY_PATH
    DATA_DIR = Path(path).resolve()
    APK_DIR = DATA_DIR / "apks"
    BUNDLE_DIR = DATA_DIR / "bundles"
    REGISTRY_PATH = DATA_DIR / "device_registry.json"


async def run_server(port: int | None = None):
    # WebSocket port is overridable (MDM_WS_PORT / --port) so a development server
    # can run alongside production on the same machine; the discovery response
    # advertises this port, so clients follow it automatically.
    if port is None:
        port = int(os.environ.get("MDM_WS_PORT", "7070"))
    app = create_app()
    ip_addresses = get_local_ip_addresses()

    # Imported lazily to avoid a circular import: __init__ defines __version__ but
    # also imports this module. setuptools_scm reports "0.0.0" from a checkout with
    # no tags (or an install outside a git tree); label that so the log never reads
    # like a real release.
    from . import __version__
    version_label = f"{__version__} (untagged)" if __version__ == "0.0.0" else __version__
    log.info("Starting STYLY-MDM server v%s on port %d", version_label, port)
    if ip_addresses:
        for ip in ip_addresses:
            log.info("  Server running at http://%s:%d", ip, port)
    else:
        log.info("  Server running at http://0.0.0.0:%d", port)

    # Data-dir paths are CWD-relative by default (see DATA_DIR), so log the resolved
    # locations to make "where does this write" unambiguous at a glance.
    log.info("Configuration:")
    log.info("  Data directory:   %s", DATA_DIR)
    log.info("  APK directory:    %s", APK_DIR)
    log.info("  Bundle directory: %s", BUNDLE_DIR)
    log.info("  Device registry:  %s", REGISTRY_PATH)
    log.info("  WebSocket port:   %d", port)
    log.info("  Discovery port:   %d", DISCOVERY_PORT)

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


def run_hash_cli(path: str, manifest_cap: int | None) -> int:
    """`styly-mdm hash <path>`: print a local integrity reference as JSON.

    A file is treated as an APK/ZIP (size + Central-Directory digest); a directory
    is walked into a manifest + tree hash. This is the deterministic, large-tree
    reference the console compares device results against (issue #37), and it runs
    on the machine that holds the tree — which may differ from the server host.
    The tree is never uploaded.
    """
    from . import integrity

    if os.path.isdir(path):
        result = integrity.dir_manifest(path, manifest_entry_cap=manifest_cap)
    elif os.path.isfile(path):
        try:
            size, cd_sha256 = integrity.apk_cd_digest(path)
        except integrity.Zip64UnsupportedError:
            print("error: zip64 archives are not supported", file=sys.stderr)
            return 1
        except ValueError as e:
            print(f"error: {e}", file=sys.stderr)
            return 1
        result = {"size": size, "cd_sha256": cd_sha256}
    else:
        print(f"error: no such file or directory: {path}", file=sys.stderr)
        return 1

    print(json.dumps(result, ensure_ascii=False))
    return 0


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
    parser.add_argument(
        "--max-concurrent-transfers",
        type=int,
        default=None,
        help="Max simultaneous device downloads server-wide, across all install "
        "and push jobs (default: $MDM_MAX_CONCURRENT_TRANSFERS or 5).",
    )
    subparsers = parser.add_subparsers(dest="command")
    hash_parser = subparsers.add_parser(
        "hash",
        help="Compute a local integrity reference (APK CD-digest or directory "
        "tree hash) and print it as JSON. Nothing is uploaded.",
    )
    hash_parser.add_argument("path", help="Path to an .apk file or a directory.")
    hash_parser.add_argument(
        "--manifest-cap",
        type=int,
        default=None,
        help="For a directory, omit the per-file manifest when it has more than "
        "this many entries (tree_hash is always printed).",
    )
    args = parser.parse_args()

    if args.command == "hash":
        raise SystemExit(run_hash_cli(args.path, args.manifest_cap))

    if args.data_dir is not None:
        _apply_data_dir(args.data_dir)
    if args.max_concurrent_transfers is not None:
        global MAX_CONCURRENT_TRANSFERS
        MAX_CONCURRENT_TRANSFERS = max(1, args.max_concurrent_transfers)

    # Refuse to start if another STYLY-MDM server is already answering discovery
    # requests on this LAN's discovery port. Clients take the first responder, so
    # two servers on the same port would split devices between them
    # nondeterministically. Run a dev server alongside production by giving it a
    # different MDM_DISCOVERY_PORT instead.
    existing_ip = probe_existing_server()
    if existing_ip is not None:
        log.error(
            "Another STYLY-MDM server is already running on this network and "
            "responding on discovery port %d (from %s). Refusing to start so "
            "devices cannot connect to the wrong server. Stop the other server, "
            "or set MDM_DISCOVERY_PORT to a different value to run alongside it.",
            DISCOVERY_PORT,
            existing_ip,
        )
        raise SystemExit(1)

    asyncio.run(run_server(port=args.port))


if __name__ == "__main__":
    main()
