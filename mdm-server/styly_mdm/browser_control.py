"""Validated, single-headset browser requests over the existing client socket."""

import asyncio
import json
import re
from urllib.parse import urlsplit

CAPABILITY = "open_url_v1"
REQUEST_ID = re.compile(r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
RESULT_MESSAGES = {
    "requested": "Browser launch requested. Check page loading inside the headset.",
    "invalid_url": "Enter a complete HTTP(S) URL without credentials or whitespace.",
    "no_browser": "No browser is available. Enable a browser on the headset.",
    "choose_browser": "Choose a default browser inside the headset, then try again.",
    "unavailable": "The headset browser launcher is unavailable.",
    "launch_failed": "The headset could not open its browser. Check headset policy.",
    "busy": "A browser request is already in progress for this headset.",
    "timeout": "Browser launch was not confirmed. Check the headset before trying again.",
}


def validate_browser_url(value):
    """Return the original URL; validation must never normalize query data."""
    if not isinstance(value, str) or not value or len(value) > 4096:
        raise ValueError(RESULT_MESSAGES["invalid_url"])
    if ("\\" in value or re.search(r"%(?![0-9A-Fa-f]{2})", value)
            or any(c.isspace() or ord(c) < 32 or 127 <= ord(c) <= 159 for c in value)):
        raise ValueError(RESULT_MESSAGES["invalid_url"])
    try:
        parsed = urlsplit(value)
        host = parsed.hostname
        if (parsed.scheme not in {"http", "https"} or not host
                or parsed.username is not None or parsed.password is not None
                or parsed.port is not None and not 1 <= parsed.port <= 65535
                or not re.fullmatch(r"[A-Za-z0-9_.:-]+", host.encode("idna").decode("ascii"))):
            raise ValueError()
    except (ValueError, UnicodeError):
        raise ValueError(RESULT_MESSAGES["invalid_url"]) from None
    return value


class BrowserControl:
    def __init__(self, devices, command_allowed, timeout=18):
        self.devices = devices
        self.command_allowed = command_allowed
        self.timeout = timeout
        self.pending = {}

    async def _reply(self, admin, request_id, device_id, code, message=None):
        try:
            await admin.send_str(json.dumps({
                "type": "OPEN_URL_RESULT", "request_id": request_id,
                "device_id": device_id, "status": "requested" if code == "requested" else "fail",
                "code": code, "message": message or RESULT_MESSAGES[code],
            }))
        except ConnectionError:
            pass

    async def start(self, admin, data):
        request_id = data.get("request_id")
        if not isinstance(request_id, str) or not REQUEST_ID.fullmatch(request_id):
            await self._reply(admin, None, None, "invalid_request", "A UUID request_id is required.")
            return
        targets = data.get("target_devices")
        if (not isinstance(targets, list) or len(targets) != 1
                or not isinstance(targets[0], str) or targets[0] == "*"
                or "target_connections" in data):
            await self._reply(admin, request_id, None, "invalid_target", "Select exactly one online headset.")
            return
        device_id = targets[0]
        try:
            url = validate_browser_url(data.get("url"))
        except ValueError:
            await self._reply(admin, request_id, device_id, "invalid_url")
            return
        entry = self.devices.get(device_id)
        if not self.command_allowed(entry, "EXECUTE_OPEN_URL") or entry.get("status", "online") != "online":
            await self._reply(admin, request_id, device_id, "invalid_target", "Select a ready online headset with a canonical identity.")
            return
        if CAPABILITY not in entry.get("capabilities", []):
            await self._reply(admin, request_id, device_id, "unsupported", "Update the headset's MDM client to use Open URL.")
            return
        if device_id in self.pending:
            await self._reply(admin, request_id, device_id, "busy")
            return
        pending = {"request_id": request_id, "socket": entry["ws"], "admin": admin, "timer": None}
        self.pending[device_id] = pending
        try:
            await entry["ws"].send_str(json.dumps({
                "type": "EXECUTE_OPEN_URL", "request_id": request_id, "url": url,
            }))
        except ConnectionError:
            self.pending.pop(device_id, None)
            await self._reply(admin, request_id, device_id, "unavailable")
            return
        # A fast result can settle while send_str is suspended.
        if self.pending.get(device_id) is pending:
            pending["timer"] = asyncio.create_task(self._expire(device_id, pending))
        try:
            await admin.send_str(json.dumps({
                "type": "OPEN_URL_SENT", "request_id": request_id, "device_id": device_id,
            }))
        except ConnectionError:
            pass

    async def _expire(self, device_id, pending):
        await asyncio.sleep(self.timeout)
        if self.pending.get(device_id) is pending:
            self.pending.pop(device_id)
            await self._reply(pending["admin"], pending["request_id"], device_id, "timeout")

    async def receive(self, socket, device_id, data):
        pending = self.pending.get(device_id)
        entry = self.devices.get(device_id)
        if (pending is None or pending["socket"] is not socket
                or entry is None or entry["ws"] is not socket
                or not self.command_allowed(entry, "EXECUTE_OPEN_URL")
                or pending["request_id"] != data.get("request_id")):
            return
        code = data.get("code")
        if not isinstance(code, str) or code not in RESULT_MESSAGES:
            code = "launch_failed"
        self.pending.pop(device_id)
        if pending["timer"] is not None:
            pending["timer"].cancel()
        # Only static messages cross the admin boundary; client diagnostics may contain URLs.
        await self._reply(pending["admin"], pending["request_id"], device_id, code)

    async def close(self, app=None):
        timers = [p["timer"] for p in self.pending.values() if p["timer"] is not None]
        self.pending.clear()
        for timer in timers:
            timer.cancel()
        await asyncio.gather(*timers, return_exceptions=True)
