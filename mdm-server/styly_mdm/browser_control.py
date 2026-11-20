"""Validated browser requests with independent per-headset dispatch and results."""

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
    "close_failed": "Closing browser tabs could not be confirmed. Check the headset before trying again.",
    "close_timeout": "Closing browser tabs timed out. Some tabs may already be closed; check the headset before trying again.",
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
    def __init__(self, devices, command_allowed, timeout=18, browser_tabs=None, close_timeout=90):
        self.devices = devices
        self.command_allowed = command_allowed
        self.timeout = timeout
        self.browser_tabs = browser_tabs
        self.close_timeout = close_timeout
        self.pending = {}
        self.send_tasks = set()

    async def _reply(self, admin, request_id, device_id, code, message=None, *, operation="OPEN_URL", status=None, **counts):
        try:
            await admin.send_str(json.dumps({
                "type": operation + "_RESULT", "request_id": request_id,
                "device_id": device_id, "status": status or ("requested" if code == "requested" else "fail"),
                "code": code, "message": message or RESULT_MESSAGES[code], **counts,
            }))
        except ConnectionError:
            pass

    def tabs_supported(self, entry):
        return (self.browser_tabs is not None and self.browser_tabs.available
                and self.command_allowed(entry) and entry.get("status") == "online")

    async def start(self, admin, data, *, operation="OPEN_URL"):
        request_id = data.get("request_id")
        if not isinstance(request_id, str) or not REQUEST_ID.fullmatch(request_id):
            await self._reply(admin, None, None, "invalid_request", "A UUID request_id is required.", operation=operation)
            return
        targets = data.get("target_devices")
        if (not isinstance(targets, list) or not targets
                or any(not isinstance(target, str) or not target or target == "*" for target in targets)
                or "target_connections" in data):
            await self._reply(admin, request_id, None, "invalid_target", "Select one or more headsets using explicit device IDs.", operation=operation)
            return
        targets = list(dict.fromkeys(targets))
        try:
            url = validate_browser_url(data.get("url")) if operation == "OPEN_URL" else None
        except ValueError:
            await self._reply(admin, request_id, targets[0] if len(targets) == 1 else None, "invalid_url", operation=operation)
            return
        # Each target owns its eligibility check, pending slot, timeout, and result.
        # A slow or failed socket must not delay dispatch to another headset.
        for device_id in targets:
            task = asyncio.create_task(self._admit(admin, request_id, device_id, url, operation))
            self.send_tasks.add(task)
            task.add_done_callback(self.send_tasks.discard)

    def _admit(self, admin, request_id, device_id, url, operation="OPEN_URL"):
        entry = self.devices.get(device_id)
        if not self.command_allowed(entry, "EXECUTE_OPEN_URL") or entry.get("status", "online") != "online":
            return self._reply(admin, request_id, device_id, "invalid_target", "Select a ready online headset with a canonical identity.", operation=operation)
        if operation == "OPEN_URL" and CAPABILITY not in entry.get("capabilities", []):
            return self._reply(admin, request_id, device_id, "unsupported", "Update the headset's MDM client to use Open URL.")
        if operation == "CLOSE_BROWSER_TABS" and not self.tabs_supported(entry):
            return self._reply(admin, request_id, device_id, "unsupported",
                               "Close All Tabs requires available ADB tools on the server.", operation=operation)
        if device_id in self.pending:
            return self._reply(admin, request_id, device_id, "busy", operation=operation)
        pending = {"request_id": request_id, "socket": entry["ws"], "admin": admin, "timer": None, "operation": operation}
        self.pending[device_id] = pending
        if operation == "CLOSE_BROWSER_TABS":
            return self._close_tabs(device_id, pending)
        pending["timer"] = asyncio.create_task(self._expire(device_id, pending))
        return self._send(device_id, pending, url)

    async def _send(self, device_id, pending, url):
        admin, request_id = pending["admin"], pending["request_id"]
        try:
            await asyncio.wait_for(pending["socket"].send_str(json.dumps({
                "type": "EXECUTE_OPEN_URL", "request_id": request_id, "url": url,
            })), timeout=min(self.timeout, 5))
        except (OSError, RuntimeError, asyncio.TimeoutError):
            if self.pending.get(device_id) is pending:
                self.pending.pop(device_id)
                pending["timer"].cancel()
                await self._reply(admin, request_id, device_id, "unavailable")
            return
        try:
            await admin.send_str(json.dumps({
                "type": "OPEN_URL_SENT", "request_id": request_id, "device_id": device_id,
            }))
        except ConnectionError:
            pass

    async def _perform_close(self, device_id, pending):
        try:
            await asyncio.wait_for(pending["admin"].send_str(json.dumps({
                "type": "CLOSE_BROWSER_TABS_SENT", "request_id": pending["request_id"],
                "device_id": device_id,
            })), timeout=5)
        except (OSError, RuntimeError, asyncio.TimeoutError):
            pass
        entry = self.devices.get(device_id)
        if (entry is None or entry["ws"] is not pending["socket"] or not self.tabs_supported(entry)):
            raise RuntimeError("The selected headset connection changed before closing tabs.")
        return await self.browser_tabs.close_all_tabs({**entry, "device_id": device_id})

    async def _close_tabs(self, device_id, pending):
        code, status, message, counts = "close_failed", "fail", None, {}
        try:
            result = await asyncio.wait_for(self._perform_close(device_id, pending), self.close_timeout)
            if (result.get("status") not in {"confirmed", "partial"}
                    or any(type(result.get(key)) is not int or result[key] < 0
                           for key in ("closed_tabs", "remaining_tabs"))):
                raise RuntimeError("Closing browser tabs returned an invalid result. Check the headset.")
            code = status = result["status"]
            message = result["message"]
            counts = {key: result[key] for key in ("closed_tabs", "remaining_tabs")}
        except asyncio.TimeoutError:
            code = "close_timeout"
        except (ValueError, RuntimeError) as error:
            # The adapter contract exposes static operator-safe errors only.
            message = str(error)
        except Exception:
            # Unexpected transport diagnostics must never expose page data.
            pass
        finally:
            if self.pending.get(device_id) is pending:
                self.pending.pop(device_id)
        await self._reply(pending["admin"], pending["request_id"], device_id, code, message,
                          operation="CLOSE_BROWSER_TABS", status=status, **counts)

    async def _expire(self, device_id, pending):
        await asyncio.sleep(self.timeout)
        if self.pending.get(device_id) is pending:
            self.pending.pop(device_id)
            await self._reply(pending["admin"], pending["request_id"], device_id, "timeout")

    async def receive(self, socket, device_id, data):
        pending = self.pending.get(device_id)
        entry = self.devices.get(device_id)
        if (pending is None or pending.get("operation") != "OPEN_URL" or pending["socket"] is not socket
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
        tasks = list(self.send_tasks) + timers
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        self.send_tasks.clear()
