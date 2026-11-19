"""Single-headset recording requests, fenced to the original device socket."""

import asyncio
import json
import re
import weakref

from .device_policy import command_allowed

CAPABILITY = "recording_control_v1"
REQUEST_ID = re.compile(r"[a-f0-9]{32}")
TIMEOUT_SECONDS = 20


class RecordingRequests:
    def __init__(self):
        self.pending = {}
        self.seen = weakref.WeakKeyDictionary()

    @staticmethod
    def supported(entry):
        return (
            command_allowed(entry, "EXECUTE_RECORDING_CONTROL")
            and entry.get("identity_kind") == "canonical"
            and entry.get("status", "online") == "online"
            and CAPABILITY in entry.get("capabilities", [])
        )

    async def _reply(self, admin, request_id, device_id, action, **result):
        try:
            await admin.send_str(json.dumps({
                "type": "RECORDING_RESULT", "request_id": request_id,
                "device_id": device_id, "action": action, "state": "unknown",
                "changed": False, **result,
            }))
        except (ConnectionError, RuntimeError):
            pass

    async def dispatch(self, admin, data, devices):
        request_id = data.get("request_id")
        action = data.get("action")
        targets = data.get("target_devices")
        device_id = targets[0] if isinstance(targets, list) and len(targets) == 1 and isinstance(targets[0], str) else ""
        error = None
        if not isinstance(request_id, str) or not REQUEST_ID.fullmatch(request_id):
            error = "Invalid recording request ID"
        elif action not in ("status", "start", "stop"):
            error = "Choose recording status, start, or stop"
        elif not device_id or device_id == "*" or "target_connections" in data:
            error = "Select exactly one online headset"
        elif not self.supported(devices.get(device_id)):
            error = "Recording control requires an updated client on one online, identified headset"
        elif action != "status" and request_id in self.seen.get(admin, set()):
            error = "Recording request ID was already used; refresh state before another action"
        elif any(p["device_id"] == device_id or key == request_id for key, p in self.pending.items()):
            error = "Another recording request is in progress; refresh state shortly"
        if error:
            await self._reply(admin, request_id if isinstance(request_id, str) else "", device_id,
                              action if isinstance(action, str) else "", status="fail", detail=error)
            return
        if action != "status":
            self.seen.setdefault(admin, set()).add(request_id)
        entry = devices[device_id]
        pending = {"admin": admin, "device_id": device_id, "socket": entry["ws"], "action": action}
        self.pending[request_id] = pending
        pending["timeout"] = asyncio.create_task(self._expire(request_id, pending))
        try:
            # Recheck immediately before dispatch; the registered socket may have changed.
            if devices.get(device_id) is not entry or not self.supported(entry):
                raise ConnectionError()
            await entry["ws"].send_str(json.dumps({
                "type": "EXECUTE_RECORDING_CONTROL", "request_id": request_id, "action": action,
            }))
        except (ConnectionError, RuntimeError):
            await self._fail(request_id, pending, "Headset disconnected before dispatch; refresh state before another action")

    async def _expire(self, request_id, pending):
        await asyncio.sleep(TIMEOUT_SECONDS)
        await self._fail(request_id, pending, "Recording result timed out; no automatic retry was sent. Refresh state and check the headset")

    async def _fail(self, request_id, pending, detail):
        if self.pending.get(request_id) is not pending:
            return
        self.pending.pop(request_id)
        if pending["timeout"] is not asyncio.current_task():
            pending["timeout"].cancel()
        await self._reply(pending["admin"], request_id, pending["device_id"], pending["action"], status="fail", detail=detail)

    async def consume(self, socket, device_id, data, devices):
        request_id = data.get("request_id")
        if not isinstance(request_id, str):
            return
        pending = self.pending.get(request_id)
        entry = devices.get(device_id)
        if (pending is None or pending["socket"] is not socket or pending["device_id"] != device_id
                or not self.supported(entry) or entry["ws"] is not socket
                or data.get("action") != pending["action"]):
            return
        state = data.get("state")
        status = data.get("status")
        if state not in ("recording", "stopped", "unknown") or status not in ("success", "fail"):
            return
        if status == "success" and (state == "unknown" or
                (pending["action"] == "start" and state != "recording") or
                (pending["action"] == "stop" and state != "stopped")):
            return
        self.pending.pop(request_id)
        pending["timeout"].cancel()
        detail = data.get("detail")
        await self._reply(pending["admin"], request_id, device_id, pending["action"],
                          status=status, state=state, changed=status == "success" and data.get("changed") is True,
                          detail=detail[:300] if isinstance(detail, str) else "")

    async def disconnected(self, socket):
        for request_id, pending in list(self.pending.items()):
            if pending["socket"] is socket:
                await self._fail(request_id, pending, "Headset disconnected; recording outcome is unknown. Refresh state after reconnecting")

    async def lifecycle(self, app):
        yield
        tasks = [p["timeout"] for p in self.pending.values()]
        self.pending.clear()
        self.seen.clear()
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
