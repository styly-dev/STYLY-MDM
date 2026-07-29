"""Issue #91 integration layer for the existing monolithic aiohttp server.

The repository's current ``server.py`` remains the adapter for established
commands.  This module installs the push-job subsystem before ``create_app``
registers routes, adds the new HTTP API, and intercepts only the new push-v1
WebSocket messages.  That keeps the change reviewable while the canonical job
truth lives in dedicated modules/SQLite rather than new global dictionaries.
"""

from __future__ import annotations

import asyncio
import json
import logging
import mimetypes
import os
import re
import shutil
import uuid
import zipfile
from collections.abc import Iterator, MutableMapping
from pathlib import Path
from typing import Any

from aiohttp import WSMessage, WSMsgType, web as aiohttp_web

from .push_artifacts import ArtifactStore
from .push_job_store import PushJobStore, StoreConflict, StoreNotFound, now_ms
from .push_jobs import (
    CAP_PUSH_JOB_ID_V1,
    DeviceState,
    JobState,
    ProtocolMode,
    PushJobError,
    canonicalize_create_request,
    parse_capabilities,
)
from .push_scheduler import LiveSession, PushScheduler
from .transfer_registry import TransferKey, TransferRegistry

log = logging.getLogger("stylymdm.push")

_RUNTIME_BY_DATA_DIR: dict[Path, "PushRuntime"] = {}
_INSTALLED = False
_ORIGINAL_WS_RESPONSE = aiohttp_web.WebSocketResponse
_ORIGINAL_FILE_RESPONSE = aiohttp_web.FileResponse
_ORIGINAL_CREATE_APP: Any = None


class _LegacyTransferAdapter(MutableMapping[tuple[str, str], asyncio.Future[str]]):
    """Compatibility mapping backed by the typed registry.

    Existing install code still spells keys as ``(device_id, task)``.  The
    adapter normalizes those accesses; new push-v1 code never uses this surface.
    """

    def __init__(self, registry: TransferRegistry) -> None:
        self.registry = registry
        self._legacy_push_tokens: dict[str, str] = {}

    def __getitem__(self, raw: tuple[str, str]) -> asyncio.Future[str]:
        future = self.registry.get(self._key(raw))
        if future is None:
            raise KeyError(raw)
        return future

    def __setitem__(self, raw: tuple[str, str], future: asyncio.Future[str]) -> None:
        key = self._key(raw)
        current = self.registry.get(key)
        if current is not None:
            self.registry.remove_if_same(key, current)
        self.registry.register(key, future)

    def __delitem__(self, raw: tuple[str, str]) -> None:
        key = self._key(raw)
        future = self.registry.get(key)
        if future is None or not self.registry.remove_if_same(key, future):
            raise KeyError(raw)
        if raw[1] == "push":
            self._legacy_push_tokens.pop(raw[0], None)

    def __iter__(self) -> Iterator[tuple[str, str]]:
        # Existing code only uses items(); iteration is provided for Mapping completeness.
        for raw, _future in self.items():
            yield raw

    def __len__(self) -> int:
        return sum(1 for _ in self.items())

    def get(self, raw: tuple[str, str], default: Any = None) -> Any:
        try:
            return self[raw]
        except KeyError:
            return default

    def items(self):  # type: ignore[override]
        for device_id in list(self._legacy_push_tokens):
            key = self._key((device_id, "push"))
            future = self.registry.get(key)
            if future is not None:
                yield (device_id, "push"), future
        # Install keys are not enumerable from TransferRegistry's public API.  Keep
        # explicit shadow membership for the mapping operations below.
        for raw, key in getattr(self, "_install_shadow", {}).items():
            future = self.registry.get(key)
            if future is not None:
                yield raw, future

    def __setattr__(self, name: str, value: Any) -> None:
        object.__setattr__(self, name, value)
        if name == "registry":
            object.__setattr__(self, "_install_shadow", {})

    def _key(self, raw: tuple[str, str]) -> TransferKey:  # type: ignore[override]
        device_id, task = raw
        if task == "install":
            key = TransferKey("install", device_id)
            self._install_shadow[raw] = key
            return key
        if task == "push":
            token = self._legacy_push_tokens.setdefault(device_id, f"legacy-{uuid.uuid4()}")
            return TransferKey("push", device_id, token, 1)
        raise KeyError(raw)


class RuntimeWebSocketResponse(_ORIGINAL_WS_RESPONSE):
    """WebSocketResponse that observes only issue #91 protocol frames."""

    _push_path: str | None = None
    _push_device_id: str | None = None
    _push_disconnect_notified: bool = False

    async def prepare(self, request: aiohttp_web.Request):  # type: ignore[override]
        result = await super().prepare(request)
        self._push_path = request.path
        runtime = runtime_for_current_server()
        if request.path == "/ws/admin":
            asyncio.create_task(runtime.send_initial_snapshot(self))
        return result

    async def __anext__(self) -> WSMessage:
        try:
            message = await super().__anext__()
        except StopAsyncIteration:
            await self._notify_push_disconnect()
            raise
        if message.type is not WSMsgType.TEXT:
            return message
        try:
            payload = json.loads(message.data)
        except (TypeError, json.JSONDecodeError):
            return message
        if not isinstance(payload, dict):
            return message
        runtime = runtime_for_current_server()
        if self._push_path == "/ws/device":
            msg_type = payload.get("type")
            if msg_type == "REGISTER":
                device_id = payload.get("device_id")
                if isinstance(device_id, str) and device_id:
                    self._push_device_id = device_id
                    await runtime.register_device(self, payload)
                return message
            handled = await runtime.handle_device_message(self, self._push_device_id, payload)
            if handled:
                return WSMessage(
                    WSMsgType.TEXT,
                    json.dumps({"type": "PUSH_RUNTIME_HANDLED"}),
                    "",
                )
        elif self._push_path == "/ws/admin":
            handled = await runtime.handle_admin_message(self, payload)
            if handled:
                return WSMessage(
                    WSMsgType.TEXT,
                    json.dumps({"type": "PUSH_RUNTIME_HANDLED"}),
                    "",
                )
        return message

    async def _notify_push_disconnect(self) -> None:
        if (
            self._push_path != "/ws/device"
            or not self._push_device_id
            or self._push_disconnect_notified
        ):
            return
        self._push_disconnect_notified = True
        try:
            await runtime_for_current_server().device_disconnected(
                self._push_device_id, self
            )
        except Exception:
            log.exception("Push runtime disconnect handling failed for %s", self._push_device_id)

    async def close(self, *args: Any, **kwargs: Any):  # type: ignore[override]
        await self._notify_push_disconnect()
        return await super().close(*args, **kwargs)


class PushRuntime:
    def __init__(self, server: Any, data_dir: Path) -> None:
        self.server = server
        self.data_dir = data_dir
        self.store = PushJobStore(data_dir / "push_jobs.sqlite3")
        self.artifacts = ArtifactStore(data_dir)
        self.transfers = TransferRegistry()
        self.legacy_transfers = _LegacyTransferAdapter(self.transfers)
        self.sessions: dict[str, LiveSession] = {}
        self.scheduler: PushScheduler | None = None
        self._housekeeping: asyncio.Task[None] | None = None
        self._startup_snapshots, cleanup_jobs = self.store.recover_startup_sync(
            accept_reconciliation_timeout_ms=int(
                float(os.environ.get("MDM_PUSH_ACCEPT_RECONCILIATION_TIMEOUT", "60")) * 1000
            ),
            reconciliation_timeout_ms=int(
                float(os.environ.get("MDM_PUSH_RECONCILIATION_TIMEOUT", "1800")) * 1000
            ),
        )
        for job_id in cleanup_jobs:
            self.artifacts.cleanup_work_best_effort(job_id)

    @property
    def allow_legacy(self) -> bool:
        return os.environ.get("MDM_ALLOW_LEGACY_PUSH", "1") != "0"

    async def on_startup(self, _app: aiohttp_web.Application) -> None:
        self.scheduler = PushScheduler(
            store=self.store,
            transfer_registry=self.transfers,
            transfer_slots=self.server.transfer_slots,
            sessions=lambda: self.sessions,
            publish=self.publish,
            send_timeout=float(os.environ.get("MDM_PUSH_COMMAND_SEND_TIMEOUT", "5")),
            accept_timeout=float(os.environ.get("MDM_PUSH_COMMAND_ACCEPT_TIMEOUT", "15")),
            accept_reconciliation_timeout=float(
                os.environ.get("MDM_PUSH_ACCEPT_RECONCILIATION_TIMEOUT", "60")
            ),
            reconciliation_timeout=float(os.environ.get("MDM_PUSH_RECONCILIATION_TIMEOUT", "1800")),
            transfer_timeout=float(self.server.TRANSFER_TIMEOUT),
        )
        self.scheduler.start()
        self._housekeeping = asyncio.create_task(self._housekeeping_loop(), name="push-job-housekeeping")
        for snapshot in self._startup_snapshots:
            await self.publish(snapshot)
        self._startup_snapshots.clear()

    async def on_cleanup(self, _app: aiohttp_web.Application) -> None:
        if self._housekeeping is not None:
            self._housekeeping.cancel()
            await asyncio.gather(self._housekeeping, return_exceptions=True)
            self._housekeeping = None
        if self.scheduler is not None:
            await self.scheduler.stop()
            self.scheduler = None
        self.store.close()
        _RUNTIME_BY_DATA_DIR.pop(self.data_dir, None)

    async def create_job_handler(self, request: aiohttp_web.Request) -> aiohttp_web.Response:
        try:
            raw = await request.json()
            if not isinstance(raw, dict):
                raise PushJobError("request body must be an object")
            canonical = canonicalize_create_request(raw)
            if canonical.source.declared_file_count > self.server.MAX_BUNDLE_ENTRIES:
                raise PushJobError("declared file count exceeds the server limit")
            if canonical.source.declared_total_bytes > self.server.MAX_BUNDLE_SIZE:
                raise PushJobError("declared total bytes exceeds the server limit")
            target_protocols: dict[str, tuple[ProtocolMode, frozenset[str]]] = {}
            target_errors: list[dict[str, str]] = []
            for device_id in canonical.target_devices:
                registry = self.server.device_registry.get(device_id)
                session = self.sessions.get(device_id)
                if registry is None:
                    target_errors.append({"device_id": device_id, "reason": "unknown_device"})
                    continue
                if registry.get("retired"):
                    target_errors.append({"device_id": device_id, "reason": "retired"})
                    continue
                if session is None or device_id not in self.server.devices:
                    target_errors.append({"device_id": device_id, "reason": "offline"})
                    continue
                if CAP_PUSH_JOB_ID_V1 in session.capabilities:
                    target_protocols[device_id] = (ProtocolMode.JOB_V1, session.capabilities)
                elif self.allow_legacy:
                    target_protocols[device_id] = (ProtocolMode.LEGACY, session.capabilities)
                else:
                    target_errors.append(
                        {"device_id": device_id, "reason": "unsupported_capability"}
                    )
            if target_errors:
                return aiohttp_web.json_response(
                    {"error": "one or more targets are not eligible", "targets": target_errors},
                    status=422,
                )
            created, snapshot = await self.store.create_job(
                canonical,
                target_protocols,
                int(float(os.environ.get("MDM_PUSH_CREATE_TIMEOUT", "600")) * 1000),
            )
            response = {
                "job_id": snapshot["job_id"],
                "revision": snapshot["revision"],
                "state": snapshot["state"],
                "create_expires_at": snapshot["create_expires_at"],
                "upload_url": f"/api/push-jobs/{snapshot['job_id']}/upload",
                "targets": [
                    {
                        "device_id": device_id,
                        "protocol_mode": snapshot["devices"][device_id]["protocol_mode"],
                    }
                    for device_id in canonical.target_devices
                ],
            }
            await self.publish(snapshot)
            return aiohttp_web.json_response(response, status=201 if created else 200)
        except StoreConflict as exc:
            return aiohttp_web.json_response({"error": str(exc)}, status=409)
        except PushJobError as exc:
            return aiohttp_web.json_response({"error": str(exc)}, status=422)
        except (json.JSONDecodeError, ValueError) as exc:
            return aiohttp_web.json_response({"error": str(exc)}, status=400)

    async def upload_handler(self, request: aiohttp_web.Request) -> aiohttp_web.Response:
        job_id = request.match_info["job_id"]
        try:
            str(uuid.UUID(job_id))
        except ValueError:
            return aiohttp_web.json_response({"error": "invalid job_id"}, status=404)
        started = False
        try:
            snapshot = await self.store.start_upload(job_id)
            started = True
            await self.publish(snapshot)
            upload_root = self.artifacts.upload_dir(job_id)
            reader = await request.multipart()
            relpaths: list[str] = []
            skipped = 0
            total_bytes = 0
            while True:
                field = await reader.next()
                if field is None:
                    break
                if field.name != "files" or not field.filename:
                    while await field.read_chunk():
                        pass
                    continue
                relpath = self.server.sanitize_relpath(field.filename)
                if relpath is None:
                    raise PushJobError(f"invalid upload path: {field.filename!r}")
                if self.server.is_os_metadata(relpath):
                    skipped += 1
                    while await field.read_chunk():
                        pass
                    continue
                if relpath in relpaths:
                    raise PushJobError(f"duplicate upload path: {relpath}")
                if len(relpaths) >= self.server.MAX_BUNDLE_ENTRIES:
                    raise PushJobError("bundle entry limit exceeded")
                target = upload_root / Path(relpath)
                resolved_parent = target.parent.resolve()
                if upload_root.resolve() not in {resolved_parent, *resolved_parent.parents}:
                    raise PushJobError("upload path escaped the job workspace")
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open("wb") as output:
                    while True:
                        chunk = await field.read_chunk(size=1024 * 1024)
                        if not chunk:
                            break
                        total_bytes += len(chunk)
                        if total_bytes > self.server.MAX_BUNDLE_SIZE:
                            raise PushJobError("bundle size limit exceeded")
                        output.write(chunk)
                    output.flush()
                    os.fsync(output.fileno())
                relpaths.append(relpath)
            if not relpaths:
                raise PushJobError("upload contained no usable files")
            snapshot = await self.store.mark_packaging(job_id, len(relpaths), total_bytes)
            await self.publish(snapshot)
            artifact = await asyncio.to_thread(self._package_and_publish, job_id, snapshot, relpaths)
            snapshot = await self.store.publish_artifact(job_id, artifact)
            await self.publish(snapshot)
            await asyncio.to_thread(self.artifacts.cleanup_work_best_effort, job_id)
            return aiohttp_web.json_response(
                {
                    "job_id": job_id,
                    "revision": snapshot["revision"],
                    "state": snapshot["state"],
                    "actual_file_count": snapshot["actual_file_count"],
                    "actual_total_bytes": snapshot["actual_total_bytes"],
                    "skipped_count": skipped,
                    "artifact": snapshot["artifact"],
                    # Compatibility fields consumed by the existing console bridge.
                    "bundle_filename": snapshot["artifact"]["display_filename"],
                    "bundle_url": snapshot["artifact"]["url"],
                    "size": snapshot["artifact"]["byte_size"],
                    "entry_count": snapshot["artifact"]["entry_count"],
                }
            )
        except StoreNotFound:
            return aiohttp_web.json_response({"error": "job not found"}, status=404)
        except StoreConflict as exc:
            return aiohttp_web.json_response({"error": str(exc)}, status=409)
        except asyncio.CancelledError:
            if started:
                await self._record_upload_failure(
                    job_id, JobState.INTERRUPTED, "upload_interrupted", "upload request was cancelled"
                )
            raise
        except (PushJobError, OSError, zipfile.BadZipFile) as exc:
            if started:
                await self._record_upload_failure(
                    job_id,
                    JobState.INTERRUPTED if isinstance(exc, ConnectionError) else JobState.FAILED,
                    "upload_interrupted" if isinstance(exc, ConnectionError) else "packaging_failed",
                    str(exc),
                )
            status = 422 if isinstance(exc, PushJobError) else 500
            return aiohttp_web.json_response({"error": str(exc)}, status=status)
        except Exception as exc:
            log.exception("Push job upload failed for %s", job_id)
            if started:
                await self._record_upload_failure(job_id, JobState.FAILED, "packaging_failed", str(exc))
            return aiohttp_web.json_response({"error": "push upload failed"}, status=500)

    def _package_and_publish(
        self, job_id: str, snapshot: dict[str, Any], relpaths: list[str]
    ) -> dict[str, Any]:
        upload_root = self.artifacts.upload_dir(job_id)
        common_root, _ = self.server.strip_common_root(relpaths)
        content_root = upload_root / common_root if common_root else upload_root
        source_label = snapshot["source_label"]
        safe_label = re.sub(r"[^A-Za-z0-9._-]", "_", source_label).strip("._-") or "bundle"
        display_filename = f"{safe_label}.zip"
        part_path = self.artifacts.work_dir(job_id) / "artifact.part"
        with zipfile.ZipFile(part_path, "w", zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(content_root.rglob("*")):
                if path.is_file():
                    archive.write(path, path.relative_to(content_root).as_posix())
        return self.artifacts.publish(job_id, part_path, display_filename, len(relpaths))

    async def _record_upload_failure(
        self, job_id: str, state: JobState, code: str, detail: str
    ) -> None:
        try:
            snapshot = await self.store.fail_pre_dispatch(job_id, state, code, detail)
            await self.publish(snapshot)
        except Exception:
            log.exception("Could not persist push upload failure for %s", job_id)
        await asyncio.to_thread(self.artifacts.cleanup_work_best_effort, job_id)

    async def get_job_handler(self, request: aiohttp_web.Request) -> aiohttp_web.Response:
        try:
            snapshot = await self.store.get_snapshot(request.match_info["job_id"])
        except (StoreNotFound, ValueError):
            return aiohttp_web.json_response({"error": "job not found"}, status=404)
        return aiohttp_web.json_response(snapshot)

    async def artifact_handler(self, request: aiohttp_web.Request) -> aiohttp_web.StreamResponse:
        artifact_id = request.match_info["artifact_id"]
        try:
            str(uuid.UUID(artifact_id))
        except ValueError:
            raise aiohttp_web.HTTPNotFound()
        record = await self.store.artifact_record(artifact_id)
        if record is None:
            raise aiohttp_web.HTTPNotFound()
        path = self.artifacts.path_for_record(record)
        if not path.is_file():
            raise aiohttp_web.HTTPGone(text="artifact metadata exists but the immutable file is missing")
        response = aiohttp_web.StreamResponse(
            status=200,
            headers={
                "Content-Type": "application/zip",
                "Content-Length": str(record["byte_size"]),
                "Content-Disposition": f'attachment; filename="{record["display_filename"]}"',
                "Content-Encoding": "identity",
                "Cache-Control": "public, immutable",
                "ETag": f'"{record["sha256"]}"',
                "X-Content-Type-Options": "nosniff",
            },
        )
        await response.prepare(request)
        with path.open("rb") as handle:
            while True:
                chunk = await asyncio.to_thread(handle.read, 1024 * 1024)
                if not chunk:
                    break
                await response.write(chunk)
        await response.write_eof()
        return response

    async def register_device(self, ws: RuntimeWebSocketResponse, payload: dict[str, Any]) -> None:
        device_id = payload["device_id"]
        capabilities = parse_capabilities(payload.get("capabilities"))
        process_instance_id = payload.get("process_instance_id")
        if not isinstance(process_instance_id, str) or not process_instance_id:
            process_instance_id = None
        session = LiveSession(
            device_id=device_id,
            session_id=str(uuid.uuid4()),
            ws=ws,
            capabilities=capabilities,
            process_instance_id=process_instance_id,
            send_lock=asyncio.Lock(),
        )
        self.sessions[device_id] = session
        await _ORIGINAL_WS_RESPONSE.send_str(
            ws, json.dumps({"type": "REGISTERED", "session_id": session.session_id})
        )
        for snapshot in await self.store.clear_fence_on_process_replacement(
            device_id, process_instance_id, CAP_PUSH_JOB_ID_V1 in capabilities
        ):
            await self.publish(snapshot)
        runtime = payload.get("push_runtime")
        active = runtime.get("active") if isinstance(runtime, dict) else None
        assignment = await self.store.active_assignment_for_device(device_id)
        if assignment is not None and assignment["state"] == DeviceState.RECONCILING.value:
            if isinstance(active, dict) and active.get("job_id") == assignment["job_id"] and active.get("attempt") == 1:
                try:
                    _status, snapshots = await self.store.reconcile_report(
                        assignment["job_id"],
                        device_id,
                        1,
                        "active",
                        active.get("phase"),
                        None,
                    )
                    for snapshot in snapshots:
                        await self.publish(snapshot)
                except StoreConflict:
                    pass
            else:
                await self._send_reconcile_for_assignment(session, assignment)
        elif isinstance(active, dict) and isinstance(active.get("job_id"), str):
            # The client reports a live worker unknown to this data directory.  Persist
            # an opaque fence instead of inventing a foreign-key job row.
            opaque = json.dumps(
                {
                    "job_id": active.get("job_id"),
                    "attempt": active.get("attempt"),
                    "artifact_id": active.get("artifact_id"),
                },
                sort_keys=True,
                separators=(",", ":"),
            )
            await self.store.add_opaque_fence(
                device_id,
                opaque,
                ProtocolMode.JOB_V1,
                process_instance_id,
                "client_reported_unknown_active_job",
            )
        if self.scheduler is not None:
            self.scheduler.wake()

    async def device_disconnected(self, device_id: str, ws: RuntimeWebSocketResponse) -> None:
        session = self.sessions.get(device_id)
        if session is None or session.ws is not ws:
            return
        del self.sessions[device_id]
        assignment = await self.store.active_assignment_for_device(device_id)
        if assignment is None:
            return
        key = TransferKey("push", device_id, assignment["job_id"], assignment["attempt"])
        self.transfers.release_exact(key, "disconnect")
        current = DeviceState(assignment["state"])
        if current in {
            DeviceState.DISPATCHING,
            DeviceState.DOWNLOADING,
            DeviceState.VALIDATING,
            DeviceState.APPLYING,
        }:
            timeout = (
                float(os.environ.get("MDM_PUSH_ACCEPT_RECONCILIATION_TIMEOUT", "60"))
                if current is DeviceState.DISPATCHING
                else float(os.environ.get("MDM_PUSH_RECONCILIATION_TIMEOUT", "1800"))
            )
            try:
                snapshot = await self.store.mark_reconciling(
                    assignment["job_id"],
                    device_id,
                    expected={current},
                    reason="device_disconnected",
                    deadline=now_ms() + int(timeout * 1000),
                )
                await self.publish(snapshot)
            except StoreConflict:
                pass

    async def handle_admin_message(
        self, admin_ws: RuntimeWebSocketResponse, payload: dict[str, Any]
    ) -> bool:
        msg_type = payload.get("type")
        if msg_type == "PUSH_FILES" and isinstance(payload.get("job_id"), str):
            job_id = payload["job_id"]
            try:
                changed, snapshot = await self.store.enable_dispatch(job_id)
            except StoreNotFound:
                await admin_ws.send_str(json.dumps({"type": "ERROR", "message": "push job not found"}))
                return True
            except StoreConflict as exc:
                await admin_ws.send_str(json.dumps({"type": "ERROR", "message": str(exc)}))
                return True
            await self.publish(snapshot)
            await admin_ws.send_str(
                json.dumps(
                    {
                        "type": "PUSH_FILES_SENT",
                        "job_id": job_id,
                        "revision": snapshot["revision"],
                        "state": snapshot["state"],
                        "dispatch_enabled": snapshot["dispatch_enabled"],
                        "target_count": snapshot["aggregate"]["total"],
                        "max_concurrent": self.server.MAX_CONCURRENT_TRANSFERS,
                        "dest_path": snapshot["dest_path"],
                        "delete_extras": snapshot["mode"] == "sync",
                    }
                )
            )
            if changed and self.scheduler is not None:
                self.scheduler.wake()
            return True
        if msg_type == "RECONCILE_PUSH_DEVICE":
            device_id = payload.get("device_id")
            if isinstance(device_id, str):
                await self.request_reconcile(device_id)
            return True
        return False

    async def handle_device_message(
        self,
        ws: RuntimeWebSocketResponse,
        device_id: str | None,
        payload: dict[str, Any],
    ) -> bool:
        if not device_id or self.sessions.get(device_id, None) is None:
            return False
        if self.sessions[device_id].ws is not ws:
            return payload.get("type", "").startswith("PUSH_")
        msg_type = payload.get("type")
        job_id = payload.get("job_id")
        attempt = payload.get("attempt")

        if msg_type in {"PUSH_JOB_ACCEPTED", "PUSH_JOB_REJECTED"} and isinstance(job_id, str) and attempt == 1:
            if self.scheduler is not None:
                outcome = (
                    "accepted"
                    if msg_type == "PUSH_JOB_ACCEPTED"
                    else ("busy" if payload.get("reason") == "device_busy" else "rejected")
                )
                self.scheduler.command_response(
                    job_id=job_id,
                    device_id=device_id,
                    attempt=1,
                    outcome=outcome,
                    payload=payload,
                )
            return True

        if msg_type == "PUSH_TRANSFER_COMPLETE" and isinstance(job_id, str) and attempt == 1:
            assignment = await self.store.active_assignment_for_device(device_id)
            if not self._matches(assignment, job_id, 1):
                return True
            artifact_id = payload.get("artifact_id")
            if artifact_id != assignment.get("artifact_id"):
                return True
            key = TransferKey("push", device_id, job_id, 1)
            released = self.transfers.release_exact(key, "push_transfer_complete")
            current = DeviceState(assignment["state"])
            if current in {DeviceState.DOWNLOADING, DeviceState.RECONCILING}:
                try:
                    snapshot = await self.store.transition_device(
                        job_id,
                        device_id,
                        expected={current},
                        target=DeviceState.VALIDATING,
                        fields={
                            "transfer_completed_at": now_ms(),
                            "validation_started_at": now_ms(),
                            "reconciliation_reason": None,
                            "reconciliation_deadline": None,
                        },
                    )
                    await self.publish(snapshot)
                except StoreConflict:
                    pass
            log.info("Push transfer complete for %s/%s released=%s", job_id, device_id, released)
            return True

        if msg_type == "DOWNLOAD_COMPLETE" and isinstance(job_id, str) and attempt == 1:
            # Artifact verification checkpoint; deliberately not a slot-release event.
            assignment = await self.store.active_assignment_for_device(device_id)
            if not self._matches(assignment, job_id, 1):
                return True
            if DeviceState(assignment["state"]) is DeviceState.VALIDATING:
                # Timestamp-only changes still receive a canonical revision.
                try:
                    snapshot = await self.store.transition_device(
                        job_id,
                        device_id,
                        expected={DeviceState.VALIDATING},
                        target=DeviceState.APPLYING,
                        fields={
                            "validation_completed_at": now_ms(),
                            "apply_started_at": now_ms(),
                        },
                    )
                    await self.publish(snapshot)
                except StoreConflict:
                    pass
            return True

        if msg_type == "PUSH_PHASE" and isinstance(job_id, str) and attempt == 1:
            if payload.get("phase") == "applying":
                assignment = await self.store.active_assignment_for_device(device_id)
                if self._matches(assignment, job_id, 1):
                    current = DeviceState(assignment["state"])
                    if current in {DeviceState.VALIDATING, DeviceState.RECONCILING}:
                        try:
                            snapshot = await self.store.transition_device(
                                job_id,
                                device_id,
                                expected={current},
                                target=DeviceState.APPLYING,
                                fields={
                                    "validation_completed_at": now_ms(),
                                    "apply_started_at": now_ms(),
                                    "reconciliation_reason": None,
                                    "reconciliation_deadline": None,
                                },
                            )
                            await self.publish(snapshot)
                        except StoreConflict:
                            pass
            return True

        if msg_type == "PUSH_RECONCILE_REPORT" and isinstance(job_id, str) and attempt == 1:
            status = payload.get("status")
            if not isinstance(status, str):
                return True
            assignment = await self.store.active_assignment_for_device(device_id)
            if not self._matches(assignment, job_id, 1):
                # Only an exact explicit absence/interruption report is evidence that
                # a terminal unconfirmed worker no longer exists.  A malformed or
                # unrelated report must never clear the persistent device fence.
                if status in {"absent", "interrupted"}:
                    snapshots = await self.store.clear_matching_fence(job_id, device_id, 1)
                    for snapshot in snapshots:
                        await self.publish(snapshot)
                    if snapshots and self.scheduler is not None:
                        self.scheduler.wake()
                return True
            try:
                outcome, snapshots = await self.store.reconcile_report(
                    job_id,
                    device_id,
                    1,
                    status,
                    payload.get("phase"),
                    payload.get("detail"),
                )
                if outcome != "active" or payload.get("phase") != "downloading":
                    self.transfers.release_exact(
                        TransferKey("push", device_id, job_id, 1), "reconcile_report"
                    )
                for snapshot in snapshots:
                    await self.publish(snapshot)
                if self.scheduler is not None:
                    self.scheduler.wake()
            except StoreConflict:
                pass
            return True

        if msg_type == "PUSH_FILES_RESULT" and isinstance(job_id, str) and attempt == 1:
            await self._handle_job_result(ws, device_id, payload)
            return True

        # Legacy client messages have no job identity.  They are accepted only when
        # exactly one canonical legacy assignment owns the device.
        if msg_type == "DOWNLOAD_COMPLETE" and payload.get("task") == "push" and not job_id:
            assignment = await self.store.active_assignment_for_device(device_id)
            if assignment and assignment["protocol_mode"] == ProtocolMode.LEGACY.value:
                key = TransferKey("push", device_id, assignment["job_id"], 1)
                self.transfers.release_exact(key, "legacy_download_complete")
                current = DeviceState(assignment["state"])
                if current is DeviceState.DOWNLOADING:
                    try:
                        snapshot = await self.store.transition_device(
                            assignment["job_id"],
                            device_id,
                            expected={DeviceState.DOWNLOADING},
                            target=DeviceState.VALIDATING,
                            fields={"transfer_completed_at": now_ms(), "validation_started_at": now_ms()},
                        )
                        snapshot = await self.store.transition_device(
                            assignment["job_id"],
                            device_id,
                            expected={DeviceState.VALIDATING},
                            target=DeviceState.APPLYING,
                            fields={"validation_completed_at": now_ms(), "apply_started_at": now_ms()},
                        )
                        await self.publish(snapshot)
                    except StoreConflict:
                        pass
            return False
        if msg_type == "PUSH_FILES_RESULT" and not job_id:
            assignment = await self.store.active_assignment_for_device(device_id)
            if assignment and assignment["protocol_mode"] == ProtocolMode.LEGACY.value:
                enriched = dict(payload)
                enriched["job_id"] = assignment["job_id"]
                enriched["attempt"] = 1
                await self._handle_job_result(ws, device_id, enriched, send_ack=False)
            return False
        return False

    async def _handle_job_result(
        self,
        ws: RuntimeWebSocketResponse,
        device_id: str,
        payload: dict[str, Any],
        *,
        send_ack: bool = True,
    ) -> None:
        job_id = payload["job_id"]
        attempt = payload.get("attempt")
        if attempt != 1:
            if send_ack:
                await ws.send_str(
                    json.dumps(
                        {
                            "type": "PUSH_RESULT_ACK",
                            "job_id": job_id,
                            "attempt": attempt,
                            "accepted": False,
                            "reason": "stale_attempt",
                        }
                    )
                )
            return
        self.transfers.release_exact(
            TransferKey("push", device_id, job_id, 1), "push_files_result"
        )
        status = "success" if payload.get("status") == "success" else "fail"
        try:
            snapshot_before = await self.store.get_snapshot(job_id)
        except StoreNotFound:
            if send_ack:
                await ws.send_str(
                    json.dumps(
                        {
                            "type": "PUSH_RESULT_ACK",
                            "job_id": job_id,
                            "attempt": 1,
                            "accepted": False,
                            "reason": "unknown_job",
                        }
                    )
                )
            return
        device_snapshot = snapshot_before["devices"].get(device_id)
        if device_snapshot and device_snapshot["state"] == DeviceState.UNCONFIRMED.value:
            accepted, snapshot = await self.store.settle_late_fenced_result(job_id, device_id, 1)
            reason = None if accepted else "stale_result"
        else:
            try:
                accepted, reason, snapshot = await self.store.settle_result(
                    job_id,
                    device_id,
                    1,
                    status,
                    added=self._nonnegative_int(payload.get("added")),
                    updated=self._nonnegative_int(payload.get("updated")),
                    deleted=self._nonnegative_int(payload.get("deleted")),
                    failure_code=None if status == "success" else "apply_failed",
                    failure_detail=(payload.get("error") or payload.get("message"))
                    if status != "success"
                    else None,
                )
            except StoreNotFound:
                accepted, reason, snapshot = False, "unknown_assignment", snapshot_before
        if accepted:
            await self.publish(snapshot)
            if self.scheduler is not None:
                self.scheduler.wake()
        if send_ack:
            await ws.send_str(
                json.dumps(
                    {
                        "type": "PUSH_RESULT_ACK",
                        "job_id": job_id,
                        "attempt": 1,
                        "accepted": accepted,
                        "revision": snapshot["revision"],
                        **({"reason": reason} if reason else {}),
                    }
                )
            )

    async def request_reconcile(self, device_id: str) -> None:
        session = self.sessions.get(device_id)
        assignment = await self.store.active_assignment_for_device(device_id)
        if session is None or assignment is None:
            return
        await self._send_reconcile_for_assignment(session, assignment)

    async def _send_reconcile_for_assignment(
        self, session: LiveSession, assignment: dict[str, Any]
    ) -> None:
        payload = {
            "type": "PUSH_RECONCILE_REQUEST",
            "jobs": [
                {
                    "job_id": assignment["job_id"],
                    "attempt": assignment["attempt"],
                    "artifact_id": assignment.get("artifact_id"),
                }
            ],
        }
        async with session.send_lock:
            if self.sessions.get(session.device_id) is not session:
                return
            await asyncio.wait_for(
                session.ws.send_str(json.dumps(payload)),
                float(os.environ.get("MDM_PUSH_COMMAND_SEND_TIMEOUT", "5")),
            )

    async def send_initial_snapshot(self, ws: RuntimeWebSocketResponse) -> None:
        await asyncio.sleep(0)
        try:
            limit = max(1, int(os.environ.get("MDM_PUSH_RECENT_JOB_LIMIT", "100")))
            days = max(1, int(os.environ.get("MDM_PUSH_RECENT_JOB_DAYS", "30")))
            jobs = await self.store.list_snapshots(limit, now_ms() - days * 86_400_000)
            await ws.send_str(json.dumps({"type": "PUSH_JOBS_SNAPSHOT", "jobs": jobs}))
        except Exception:
            log.exception("Could not send initial push job snapshot")

    async def publish(self, snapshot: dict[str, Any]) -> None:
        await self.server.forward_to_admins(
            {
                "type": "PUSH_JOB_UPDATED",
                "job_id": snapshot["job_id"],
                "revision": snapshot["revision"],
                "job": snapshot,
            }
        )
        aggregate = snapshot["aggregate"]
        artifact = snapshot.get("artifact") or {}
        await self.server.forward_to_admins(
            {
                "type": "PUSH_PROGRESS",
                "job_id": snapshot["job_id"],
                "revision": snapshot["revision"],
                "bundle_filename": artifact.get("display_filename", snapshot["source_label"]),
                "dest_path": snapshot["dest_path"],
                "delete_extras": snapshot["mode"] == "sync",
                "total": aggregate["total"],
                "queued": aggregate.get("queued", 0) + aggregate.get("waiting_transfer", 0),
                "transferring": aggregate.get("dispatching", 0) + aggregate.get("downloading", 0),
                "transferred": aggregate.get("validating", 0)
                + aggregate.get("applying", 0)
                + aggregate.get("succeeded", 0),
                "failed": aggregate.get("failed", 0)
                + aggregate.get("interrupted", 0)
                + aggregate.get("unconfirmed", 0),
                "done": snapshot["terminal_at"] is not None,
                "aggregate": aggregate,
            }
        )
        for device_id, device in snapshot["devices"].items():
            await self.server.forward_to_admins(
                {
                    "type": "PUSH_DEVICE_STATE",
                    "job_id": snapshot["job_id"],
                    "revision": snapshot["revision"],
                    "attempt": device["attempt"],
                    "device_ids": [device_id],
                    "state": device["state"],
                    "dest_path": snapshot["dest_path"],
                    "delete_extras": snapshot["mode"] == "sync",
                    "detail": (device.get("failure") or {}).get("detail", ""),
                }
            )

    async def _housekeeping_loop(self) -> None:
        while True:
            await asyncio.sleep(1)
            timestamp = now_ms()
            for job_id in await self.store.expired_created(timestamp):
                snapshot = await self.store.expire_created(job_id)
                if snapshot is not None:
                    await self.publish(snapshot)
                    await asyncio.to_thread(self.artifacts.cleanup_work_best_effort, job_id)
            for row in await self.store.expired_reconciliations(timestamp):
                session = self.sessions.get(row["device_id"])
                try:
                    snapshot = await self.store.mark_unconfirmed(
                        row["job_id"],
                        row["device_id"],
                        session.process_instance_id if session else None,
                        "reconciliation deadline elapsed without matching evidence",
                    )
                    self.transfers.release_exact(
                        TransferKey("push", row["device_id"], row["job_id"], row["attempt"]),
                        "reconciliation_timeout",
                    )
                    await self.publish(snapshot)
                    if self.scheduler is not None:
                        self.scheduler.wake()
                except StoreConflict:
                    continue

    @staticmethod
    def _matches(assignment: dict[str, Any] | None, job_id: str, attempt: int) -> bool:
        return bool(
            assignment
            and assignment.get("job_id") == job_id
            and assignment.get("attempt") == attempt
        )

    @staticmethod
    def _nonnegative_int(value: Any) -> int:
        return value if isinstance(value, int) and not isinstance(value, bool) and value >= 0 else 0


def runtime_for_current_server() -> PushRuntime:
    from . import server

    data_dir = Path(server.DATA_DIR).resolve()
    runtime = _RUNTIME_BY_DATA_DIR.get(data_dir)
    if runtime is None:
        runtime = PushRuntime(server, data_dir)
        _RUNTIME_BY_DATA_DIR[data_dir] = runtime
        server.pending_transfers = runtime.legacy_transfers
    return runtime


def _release_transfer_slot(device_id: str, reason: str, task: str | None = None) -> bool:
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


def _file_response(path: Any, *args: Any, **kwargs: Any):
    file_path = Path(path)
    if file_path.name == "index.html" and file_path.parent.name == "static":
        text = file_path.read_text(encoding="utf-8")
        marker = '<script src="/static/push-jobs-v1.js"></script>'
        if marker not in text:
            text = text.replace("</head>", f"  {marker}\n</head>")
        return aiohttp_web.Response(text=text, content_type="text/html")
    return _ORIGINAL_FILE_RESPONSE(path, *args, **kwargs)


def install(server: Any) -> None:
    global _INSTALLED, _ORIGINAL_CREATE_APP
    if _INSTALLED:
        return
    _INSTALLED = True
    _ORIGINAL_CREATE_APP = server.create_app
    server.web.WebSocketResponse = RuntimeWebSocketResponse
    server.web.FileResponse = _file_response
    server.release_transfer_slot = _release_transfer_slot

    def create_app() -> aiohttp_web.Application:
        runtime = runtime_for_current_server()
        app = _ORIGINAL_CREATE_APP()
        app["push_runtime"] = runtime
        app.router.add_post("/api/push-jobs", runtime.create_job_handler)
        app.router.add_post("/api/push-jobs/{job_id}/upload", runtime.upload_handler)
        app.router.add_get("/api/push-jobs/{job_id}", runtime.get_job_handler)
        app.router.add_get("/artifacts/{artifact_id}", runtime.artifact_handler)
        app.on_startup.append(runtime.on_startup)
        app.on_cleanup.append(runtime.on_cleanup)
        return app

    server.create_app = create_app
