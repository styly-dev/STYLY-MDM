#!/usr/bin/env python3
"""Repair WebSocket/legacy ordering after the bulk Issue #91 patch is applied."""
from pathlib import Path


path = Path("mdm-server/styly_mdm/push_runtime.py")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"expected one runtime marker, found {count}: {old[:120]!r}")
    text = text.replace(old, new, 1)


replace_once(
    '    _push_disconnect_notified: bool = False\n    _push_origin: str = ""\n',
    '    _push_disconnect_notified: bool = False\n'
    '    _push_origin: str = ""\n'
    '    _push_initial_snapshot_scheduled: bool = False\n'
    '    _push_register_task: asyncio.Task[None] | None = None\n',
)

replace_once(
    '''        self._push_path = request.path
        self._push_origin = f"{request.scheme}://{request.host}"
        runtime = runtime_for_current_server()
        if request.path == "/ws/admin":
            asyncio.create_task(runtime.send_initial_snapshot(self))
        return result
''',
    '''        self._push_path = request.path
        self._push_origin = f"{request.scheme}://{request.host}"
        return result
''',
)

methods = '''    async def send_str(self, data: str, compress: int | None = None) -> None:  # type: ignore[override]
        await super().send_str(data, compress=compress)
        if self._push_path != "/ws/admin" or self._push_initial_snapshot_scheduled:
            return
        try:
            payload = json.loads(data)
        except (TypeError, json.JSONDecodeError):
            return
        if not isinstance(payload, dict) or payload.get("type") != "GROUP_LIST":
            return
        # The canonical admin handler emits SERVER_INFO, client APK metadata,
        # DEVICE_LIST, and GROUP_LIST in that order.  Queue the push snapshot only
        # after GROUP_LIST has been written so the new subsystem cannot reorder the
        # established reconstruction prefix.
        self._push_initial_snapshot_scheduled = True
        asyncio.create_task(runtime_for_current_server().send_initial_snapshot(self))

    def _schedule_push_register(self, runtime: "PushRuntime", payload: dict[str, Any]) -> None:
        # Let the established server handler consume REGISTER first.  It installs
        # devices[device_id] synchronously before its first await, preserving the
        # existing ownership and initial DEVICE_LIST ordering.  The push-v1
        # capability/session handshake then runs in the next event-loop turn.
        task = asyncio.create_task(runtime.register_device(self, payload))
        self._push_register_task = task

        def completed(done: asyncio.Task[None]) -> None:
            if self._push_register_task is done:
                self._push_register_task = None
            try:
                done.result()
            except asyncio.CancelledError:
                pass
            except Exception:
                log.exception("Push runtime REGISTER handling failed")

        task.add_done_callback(completed)

'''
replace_once(
    "    async def __anext__(self) -> WSMessage:\n",
    methods + "    async def __anext__(self) -> WSMessage:\n",
)

replace_once(
    '''                    self._push_device_id = device_id
                    await runtime.register_device(self, payload)
                return message
''',
    '''                    self._push_device_id = device_id
                    self._schedule_push_register(runtime, payload)
                return message
''',
)

replace_once(
    '''def _release_transfer_slot(device_id: str, reason: str, task: str | None = None) -> bool:
    runtime = runtime_for_current_server()
    if task == "install":
        return runtime.transfers.release_exact(TransferKey("install", device_id), reason)
    if task == "push":
        future = runtime.legacy_transfers.get((device_id, "push"))
        if future is None:
            return False
        key = runtime.legacy_transfers._key((device_id, "push"))
        return runtime.transfers.release_exact(key, reason)
    return bool(runtime.transfers.release_all_for_device(device_id, reason))
''',
    '''def _release_transfer_slot(device_id: str, reason: str, task: str | None = None) -> bool:
    from . import server

    current = server.pending_transfers
    if isinstance(current, _LegacyTransferAdapter):
        registry = current.registry
        if task == "install":
            return registry.release_exact(TransferKey("install", device_id), reason)
        if task == "push":
            future = current.get((device_id, "push"))
            if future is None:
                return False
            return registry.release_exact(current._key((device_id, "push")), reason)
        return bool(registry.release_all_for_device(device_id, reason))

    # Some established unit-level callers exercise the legacy dispatcher before
    # create_app() has installed the runtime adapter.  Preserve those already-owned
    # futures instead of creating a runtime and replacing the mapping underneath an
    # active job.  Production app startup always switches to the typed adapter first.
    if task is not None:
        future = current.get((device_id, task))
        if future is None or future.done():
            return False
        future.set_result(reason)
        return True
    released = False
    for (owned_device_id, _task), future in list(current.items()):
        if owned_device_id == device_id and not future.done():
            future.set_result(reason)
            released = True
    return released
''',
)

path.write_text(text, encoding="utf-8")
