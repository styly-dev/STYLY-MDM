"""aiohttp integration for the durable Push/Sync job model (Issue #91).

The repository's established server remains the adapter for unrelated commands.
This module confines Push/Sync interception to explicit protocol messages while
preserving the existing routes and old-client fallback during migration.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import uuid
from collections.abc import Iterator, MutableMapping
from pathlib import Path
from typing import Any

from aiohttp import WSCloseCode, WSMsgType, web as aiohttp_web

from .push_artifacts import ArtifactStore
from .push_job_manager import PushJobManager
from .push_job_store import (
    PushJobStore,
    StoreConflict,
    StoreNotFound,
    UploadDeadlineExpired,
    now_ms,
)
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

_INSTALLED = False
_ORIGINAL_CREATE_APP: Any = None
_RUNTIME_BY_DATA_DIR: dict[Path, "PushRuntime"] = {}
_BACKGROUND_LOOP_RETRY_DELAY = 1.0
_RECONCILIATION_POLL_INTERVAL = 1.0


def _env_seconds(name: str, default: float) -> float:
    value = float(os.environ.get(name, str(default)))
    return max(0.05, value)


def _uuid_v4_or_none(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = uuid.UUID(value)
    except ValueError:
        return None
    return str(parsed) if parsed.version == 4 else None


class _LegacyTransferAdapter(MutableMapping[tuple[str, str], asyncio.Future[str]]):
    """Compatibility facade backed by the typed transfer registry.

    Existing install and migration-only legacy Push code still uses tuple syntax.
    The actual registry key is typed, and a legacy Push receives an opaque identity
    that cannot collide with any canonical ``(job_id, device_id, attempt)`` waiter.
    """

    def __init__(self, registry: TransferRegistry) -> None:
        self.registry = registry
        self._keys: dict[tuple[str, str], TransferKey] = {}

    def _key(self, key: tuple[str, str]) -> TransferKey:
        device_id, task = key
        if task == "install":
            return TransferKey("install", device_id)
        existing = self._keys.get(key)
        if existing is not None:
            return existing
        return TransferKey("push", device_id, f"legacy-{uuid.uuid4()}", 1)

    def __getitem__(self, key: tuple[str, str]) -> asyncio.Future[str]:
        typed = self._keys[key]
        future = self.registry.get(typed)
        if future is None:
            self._keys.pop(key, None)
            raise KeyError(key)
        return future

    def __setitem__(self, key: tuple[str, str], value: asyncio.Future[str]) -> None:
        previous = self._keys.get(key)
        if previous is not None:
            current = self.registry.get(previous)
            if current is not None:
                self.registry.remove_if_same(previous, current)
        typed = self._key(key)
        self.registry.register(typed, value)
        self._keys[key] = typed

    def __delitem__(self, key: tuple[str, str]) -> None:
        typed = self._keys.pop(key)
        future = self.registry.get(typed)
        if future is not None:
            self.registry.remove_if_same(typed, future)

    def __iter__(self) -> Iterator[tuple[str, str]]:
        for key in tuple(self._keys):
            typed = self._keys[key]
            if self.registry.get(typed) is None:
                self._keys.pop(key, None)
            else:
                yield key

    def __len__(self) -> int:
        return sum(1 for _ in self)


class RuntimeWebSocketResponse(aiohttp_web.WebSocketResponse):
    """Consumes only Push-v1 frames before the legacy handler sees them."""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self._push_send_lock = asyncio.Lock()
        self._push_runtime: PushRuntime | None = None
        self._push_path = ""
        self._push_http_base = ""
        self._push_device_id: str | None = None
        self._push_pending_registration: dict[str, Any] | None = None
        self._push_disconnect_notified = False
        self._push_admin_snapshot_sent = False

    async def send_str(self, data: str, compress: int | None = None) -> None:
        async with self._push_send_lock:
            await super().send_str(data, compress=compress)

    async def prepare(self, request: aiohttp_web.Request) -> Any:
        result = await super().prepare(request)
        self._push_path = request.path
        scheme = "https" if request.secure else "http"
        self._push_http_base = f"{scheme}://{request.host}"
        if request.path in {"/ws/device", "/ws/admin"}:
            self._push_runtime = runtime_for_current_server()
        return result

    async def __anext__(self) -> Any:
        if (
            self._push_path == "/ws/admin"
            and not self._push_admin_snapshot_sent
            and self._push_runtime is not None
        ):
            # The established admin handler sends server/APK/device/group state after
            # prepare() and before entering its receive loop. The first __anext__ call
            # is therefore the deterministic boundary immediately after those frames.
            self._push_admin_snapshot_sent = True
            await self._push_runtime.send_initial_snapshot(self)
        while True:
            # Returning REGISTER lets the established server finish its registry and
            # owner update first. The next receive turn then finalizes Push-v1 and sends
            # REGISTERED, preserving one authoritative ordering.
            if self._push_pending_registration is not None and self._push_runtime is not None:
                payload = self._push_pending_registration
                self._push_pending_registration = None
                await self._push_runtime.register_device(self, payload, self._push_http_base)
            message = await super().__anext__()
            if message.type is not WSMsgType.TEXT or self._push_runtime is None:
                return message
            try:
                payload = json.loads(message.data)
            except (TypeError, json.JSONDecodeError):
                return message
            if not isinstance(payload, dict):
                return message
            if self._push_path == "/ws/device":
                if payload.get("type") == "REGISTER":
                    device_id = payload.get("device_id")
                    if isinstance(device_id, str) and device_id:
                        self._push_runtime.note_registration_candidate(
                            self, device_id
                        )
                        self._push_device_id = device_id
                        self._push_pending_registration = payload
                    return message
                if await self._push_runtime.handle_device_message(
                    self, self._push_device_id, payload
                ):
                    continue
            elif self._push_path == "/ws/admin":
                if await self._push_runtime.handle_admin_message(self, payload):
                    continue
            return message

    async def close(self, *args: Any, **kwargs: Any) -> bool:
        if (
            not self._push_disconnect_notified
            and self._push_runtime is not None
            and self._push_path == "/ws/device"
        ):
            self._push_disconnect_notified = True
            await self._push_runtime.disconnect_device(self._push_device_id, self)
        return await super().close(*args, **kwargs)


class PushRuntime:
    def __init__(self, legacy_server: Any, data_dir: Path) -> None:
        self.legacy = legacy_server
        self.data_dir = data_dir
        self.store = PushJobStore(data_dir / "push_jobs.sqlite3")
        self.manager = PushJobManager(self.store)
        self.artifacts = ArtifactStore(data_dir)
        self.transfers = TransferRegistry()
        self.legacy_transfers = _LegacyTransferAdapter(self.transfers)
        self.sessions: dict[str, LiveSession] = {}
        self.device_locks: dict[str, asyncio.Lock] = {}
        self.registration_candidates: dict[str, RuntimeWebSocketResponse] = {}
        self.scheduler: PushScheduler | None = None
        self.housekeeping_task: asyncio.Task[None] | None = None
        self.created_deadline_task: asyncio.Task[None] | None = None
        self.created_deadline_wake = asyncio.Event()
        self.publication_task: asyncio.Task[None] | None = None
        self.publication_wake = asyncio.Event()
        self.pending_publications: dict[str, dict[str, Any]] = {}
        self.publication_revisions: dict[str, int] = {}
        self.publication_stopping = False
        self.startup_snapshots, self.startup_cleanup = self.store.recover_startup_sync(
            accept_reconciliation_timeout_ms=int(
                _env_seconds("MDM_PUSH_ACCEPT_RECONCILIATION_TIMEOUT", 60) * 1000
            ),
            reconciliation_timeout_ms=int(
                _env_seconds("MDM_PUSH_RECONCILIATION_TIMEOUT", 1800) * 1000
            ),
        )
        self.startup_snapshots.extend(
            self.manager.reconcile_missing_artifacts_sync(self.artifacts.artifact_root)
        )
        self.startup_orphan_artifacts = self.manager.orphan_artifacts_sync(
            self.artifacts.artifact_root
        )

        self.create_timeout = _env_seconds("MDM_PUSH_CREATE_TIMEOUT", 600)
        self.send_timeout = _env_seconds("MDM_PUSH_COMMAND_SEND_TIMEOUT", 5)
        self.admin_send_timeout = legacy_server.ADMIN_SEND_TIMEOUT
        self.accept_timeout = _env_seconds("MDM_PUSH_COMMAND_ACCEPT_TIMEOUT", 15)
        self.accept_reconciliation_timeout = _env_seconds(
            "MDM_PUSH_ACCEPT_RECONCILIATION_TIMEOUT", 60
        )
        self.reconciliation_timeout = _env_seconds(
            "MDM_PUSH_RECONCILIATION_TIMEOUT", 1800
        )
        self.recent_limit = max(0, int(os.environ.get("MDM_PUSH_RECENT_JOB_LIMIT", "100")))
        self.recent_days = max(0, int(os.environ.get("MDM_PUSH_RECENT_JOB_DAYS", "30")))
        self.allow_legacy = os.environ.get("MDM_ALLOW_LEGACY_PUSH", "1") != "0"

    def _device_lock(self, device_id: str) -> asyncio.Lock:
        return self.device_locks.setdefault(device_id, asyncio.Lock())

    def _legacy_owns_device(
        self, device_id: str, ws: RuntimeWebSocketResponse
    ) -> bool:
        entry = self.legacy.devices.get(device_id)
        return isinstance(entry, dict) and entry.get("ws") is ws

    def note_registration_candidate(
        self, ws: RuntimeWebSocketResponse, device_id: str
    ) -> None:
        """Record the newest REGISTER without delaying the established handler."""

        self.registration_candidates[device_id] = ws

    def _dispatch_sessions(self) -> dict[str, LiveSession]:
        """Expose only sessions that still own the established device connection."""

        return {
            device_id: session
            for device_id, session in self.sessions.items()
            if (
                self.registration_candidates.get(device_id) in {None, session.ws}
                and self._legacy_owns_device(device_id, session.ws)
            )
        }

    async def on_startup(self, _app: aiohttp_web.Application) -> None:
        self.publication_stopping = False
        self.publication_task = asyncio.create_task(
            self._publication_loop(), name="push-job-publication"
        )
        for job_id in self.startup_cleanup:
            await asyncio.to_thread(self.artifacts.cleanup_work_best_effort, job_id)
        self.startup_cleanup.clear()
        for storage_name in self.startup_orphan_artifacts:
            try:
                await asyncio.to_thread(self.artifacts.remove_orphan, storage_name)
            except (OSError, ValueError):
                log.exception("Could not remove orphan Push artifact %s", storage_name)
        self.startup_orphan_artifacts.clear()
        self.scheduler = PushScheduler(
            manager=self.manager,
            transfer_registry=self.transfers,
            transfer_slots=self.legacy.transfer_slots,
            sessions=self._dispatch_sessions,
            publish=self.publish,
            send_timeout=self.send_timeout,
            accept_timeout=self.accept_timeout,
            accept_reconciliation_timeout=self.accept_reconciliation_timeout,
            reconciliation_timeout=self.reconciliation_timeout,
            transfer_timeout=self.legacy.TRANSFER_TIMEOUT,
            allow_legacy=self.allow_legacy,
        )
        self.scheduler.start()
        self.housekeeping_task = asyncio.create_task(
            self._reconciliation_housekeeping(), name="push-reconciliation-housekeeping"
        )
        self.arm_created_deadline()

    async def on_cleanup(self, _app: aiohttp_web.Application) -> None:
        if self.created_deadline_task is not None:
            self.created_deadline_task.cancel()
        if self.housekeeping_task is not None:
            self.housekeeping_task.cancel()
        await asyncio.gather(
            *(
                task
                for task in (self.created_deadline_task, self.housekeeping_task)
                if task is not None
            ),
            return_exceptions=True,
        )
        if self.scheduler is not None:
            await self.scheduler.stop()
        self.publication_stopping = True
        self.publication_wake.set()
        if self.publication_task is not None:
            await asyncio.gather(self.publication_task, return_exceptions=True)
            self.publication_task = None
        for device_id in tuple(self.sessions):
            self.transfers.release_all_for_device(device_id, "shutdown")
        self.sessions.clear()
        self.registration_candidates.clear()
        self.store.close()
        _RUNTIME_BY_DATA_DIR.pop(self.data_dir.resolve(), None)

    async def send_initial_snapshot(self, ws: RuntimeWebSocketResponse) -> None:
        try:
            cutoff = now_ms() - self.recent_days * 86_400_000
            snapshots = await self.manager.list_snapshots(self.recent_limit, cutoff)
            await asyncio.wait_for(
                ws.send_str(
                    json.dumps(
                        {"type": "PUSH_JOBS_SNAPSHOT", "jobs": snapshots},
                        separators=(",", ":"),
                    )
                ),
                self.admin_send_timeout,
            )
        except asyncio.CancelledError:
            raise
        except Exception as snapshot_error:
            log.exception("Could not send initial Push job snapshot")
            try:
                await asyncio.wait_for(
                    ws.close(
                        code=WSCloseCode.GOING_AWAY,
                        message=b"initial Push snapshot failed",
                        drain=False,
                    ),
                    self.admin_send_timeout,
                )
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("Could not close admin after initial Push snapshot failure")
                raise snapshot_error

    async def _close_failed_admin_socket(
        self, ws: RuntimeWebSocketResponse, message: bytes
    ) -> None:
        connections = getattr(self.legacy, "admin_connections", None)
        if connections is not None:
            connections.discard(ws)
        locks = getattr(self.legacy, "_admin_send_locks", None)
        if locks is not None:
            locks.pop(ws, None)
        try:
            await asyncio.wait_for(
                ws.close(
                    code=WSCloseCode.GOING_AWAY,
                    message=message,
                    drain=False,
                ),
                self.admin_send_timeout,
            )
        except asyncio.CancelledError:
            raise
        except (Exception, asyncio.TimeoutError):
            log.warning("Could not close failed admin WebSocket", exc_info=True)

    async def create_job_handler(self, request: aiohttp_web.Request) -> aiohttp_web.Response:
        try:
            body = await request.json()
            if not isinstance(body, dict):
                raise PushJobError("request body must be a JSON object")
            canonical = canonicalize_create_request(body)
            existing = await self.manager.find_idempotent_job(
                canonical.client_request_id, canonical.fingerprint
            )
            if existing is not None:
                return aiohttp_web.json_response(
                    self._create_response(existing), status=200
                )

            if canonical.source.declared_file_count > self.legacy.MAX_BUNDLE_ENTRIES:
                raise PushJobError("declared file count exceeds the server limit")
            if canonical.source.declared_total_bytes > self.legacy.MAX_BUNDLE_SIZE:
                raise PushJobError("declared total bytes exceed the server limit")

            protocols: dict[str, tuple[ProtocolMode, set[str]]] = {}
            for device_id in canonical.target_devices:
                record = self.legacy.device_registry.get(device_id)
                if record and record.get("retired") is True:
                    raise PushJobError(f"target device is retired: {device_id}")
                session = self.sessions.get(device_id)
                if session is None:
                    raise PushJobError(f"target device is not online: {device_id}")
                if CAP_PUSH_JOB_ID_V1 in session.capabilities:
                    protocols[device_id] = (
                        ProtocolMode.JOB_V1,
                        set(session.capabilities),
                    )
                elif self.allow_legacy:
                    protocols[device_id] = (
                        ProtocolMode.LEGACY,
                        set(session.capabilities),
                    )
                else:
                    raise PushJobError(
                        f"target does not support push_job_id_v1: {device_id}"
                    )
            created, snapshot = await self.store.create_job(
                canonical, protocols, int(self.create_timeout * 1000)
            )
            if created:
                await self.publish(snapshot)
                self.arm_created_deadline()
            return aiohttp_web.json_response(
                self._create_response(snapshot), status=201 if created else 200
            )
        except StoreConflict as exc:
            return aiohttp_web.json_response({"error": str(exc)}, status=409)
        except (PushJobError, ValueError) as exc:
            return aiohttp_web.json_response({"error": str(exc)}, status=422)
        except Exception as exc:
            log.exception("Push job creation failed")
            return aiohttp_web.json_response({"error": str(exc)}, status=500)

    @staticmethod
    def _create_response(snapshot: dict[str, Any]) -> dict[str, Any]:
        return {
            "job_id": snapshot["job_id"],
            "revision": snapshot["revision"],
            "state": snapshot["state"],
            "create_expires_at": snapshot["create_expires_at"],
            "upload_url": f"/api/push-jobs/{snapshot['job_id']}/upload",
            "targets": [
                {
                    "device_id": device_id,
                    "protocol_mode": device["protocol_mode"],
                }
                for device_id, device in snapshot["devices"].items()
            ],
        }

    async def upload_handler(self, request: aiohttp_web.Request) -> aiohttp_web.Response:
        job_id = request.match_info["job_id"]
        packaging = False
        try:
            snapshot = await self.store.start_upload(job_id)
            await self.publish(snapshot)
            self.arm_created_deadline()
            reader = await request.multipart()
            upload_root = await asyncio.to_thread(self.artifacts.upload_dir, job_id)
            seen: set[str] = set()
            actual_count = 0
            actual_bytes = 0
            while True:
                part = await reader.next()
                if part is None:
                    break
                if part.name != "files":
                    while await part.read_chunk():
                        pass
                    continue
                relative = self.legacy.sanitize_relpath(part.filename)
                if relative is None:
                    raise PushJobError(f"unsafe uploaded path: {part.filename!r}")
                if self.legacy.is_os_metadata(relative):
                    while await part.read_chunk():
                        pass
                    continue
                if relative in seen:
                    raise PushJobError(f"duplicate uploaded path: {relative}")
                seen.add(relative)
                actual_count += 1
                if actual_count > self.legacy.MAX_BUNDLE_ENTRIES:
                    raise PushJobError("upload exceeds the entry limit")
                destination = upload_root.joinpath(*relative.split("/"))
                handle = await asyncio.to_thread(self._open_owned_upload, destination)
                try:
                    while True:
                        chunk = await part.read_chunk(size=1024 * 1024)
                        if not chunk:
                            break
                        actual_bytes += len(chunk)
                        if actual_bytes > self.legacy.MAX_BUNDLE_SIZE:
                            raise PushJobError("upload exceeds the byte limit")
                        await asyncio.to_thread(handle.write, chunk)
                    await asyncio.to_thread(self._flush_fsync, handle)
                finally:
                    await asyncio.to_thread(handle.close)
            if actual_count == 0:
                raise PushJobError("upload contains no content files")

            snapshot = await self.store.mark_packaging(
                job_id, actual_count, actual_bytes
            )
            packaging = True
            await self.publish(snapshot)
            artifact = await asyncio.to_thread(
                self._package_and_publish,
                job_id,
                upload_root,
                snapshot["source_label"],
                tuple(sorted(seen)),
            )
            ready = await self.store.publish_artifact(job_id, artifact)
            await self.publish(ready)
            await asyncio.to_thread(self.artifacts.cleanup_work_best_effort, job_id)
            return aiohttp_web.json_response(ready)
        except asyncio.CancelledError:
            await self._record_upload_failure(
                job_id,
                JobState.INTERRUPTED,
                "upload_interrupted",
                "Upload request was cancelled before immutable publication",
            )
            await asyncio.to_thread(self.artifacts.cleanup_work_best_effort, job_id)
            raise
        except StoreNotFound:
            raise aiohttp_web.HTTPNotFound()
        except UploadDeadlineExpired as exc:
            # The Store already committed created -> interrupted. Publish that exact
            # revision instead of returning 409 while admins remain on `created`.
            await self.publish(exc.snapshot)
            await asyncio.to_thread(
                self.artifacts.cleanup_work_best_effort, job_id
            )
            self.arm_created_deadline()
            if self.scheduler is not None:
                self.scheduler.wake()
            return aiohttp_web.json_response({"error": str(exc)}, status=409)
        except StoreConflict as exc:
            return aiohttp_web.json_response({"error": str(exc)}, status=409)
        except BaseException as exc:
            state = JobState.FAILED if packaging else JobState.INTERRUPTED
            code = "packaging_failed" if packaging else "upload_interrupted"
            await self._record_upload_failure(job_id, state, code, str(exc))
            await asyncio.to_thread(self.artifacts.cleanup_work_best_effort, job_id)
            status = 422 if isinstance(exc, (PushJobError, ValueError)) else 500
            return aiohttp_web.json_response({"error": str(exc)}, status=status)

    @staticmethod
    def _open_owned_upload(path: Path) -> Any:
        path.parent.mkdir(parents=True, exist_ok=True)
        return path.open("xb")

    @staticmethod
    def _flush_fsync(handle: Any) -> None:
        handle.flush()
        os.fsync(handle.fileno())

    def _package_and_publish(
        self,
        job_id: str,
        upload_root: Path,
        source_label: str,
        relative_paths: tuple[str, ...],
    ) -> dict[str, object]:
        _common_root, stripped = self.legacy.strip_common_root(list(relative_paths))
        if stripped != list(relative_paths):
            root_name = relative_paths[0].split("/", 1)[0]
            content_root = upload_root / root_name
        else:
            content_root = upload_root
        part_path = self.artifacts.work_dir(job_id) / "artifact.part"
        self.legacy.zip_tree(content_root, part_path)
        safe = re.sub(r"[^A-Za-z0-9._-]", "_", source_label).strip("._-") or "bundle"
        display_filename = safe if safe.lower().endswith(".zip") else f"{safe}.zip"
        return self.artifacts.publish(
            job_id, part_path, display_filename, len(relative_paths)
        )

    async def _record_upload_failure(
        self, job_id: str, state: JobState, code: str, detail: str
    ) -> None:
        try:
            snapshot = await self.store.fail_pre_dispatch(
                job_id, state, code, detail[:2000]
            )
            await self.publish(snapshot)
        except (StoreConflict, StoreNotFound):
            pass
        finally:
            self.arm_created_deadline()
            if self.scheduler is not None:
                self.scheduler.wake()

    async def artifact_handler(self, request: aiohttp_web.Request) -> aiohttp_web.StreamResponse:
        record = await self.store.artifact_record(request.match_info["artifact_id"])
        if record is None:
            raise aiohttp_web.HTTPNotFound()
        try:
            path = self.artifacts.path_for_record(record)
        except ValueError:
            raise aiohttp_web.HTTPNotFound()
        if not path.is_file():
            raise aiohttp_web.HTTPNotFound()
        response = aiohttp_web.StreamResponse(
            status=200,
            headers={
                "ETag": f'"{record["sha256"]}"',
                "Content-Encoding": "identity",
                "Cache-Control": "private, immutable",
                "Content-Type": "application/zip",
                "Content-Length": str(record["byte_size"]),
            },
        )
        await response.prepare(request)
        if request.method != "HEAD":
            handle = await asyncio.to_thread(path.open, "rb")
            try:
                while True:
                    chunk = await asyncio.to_thread(handle.read, 1024 * 1024)
                    if not chunk:
                        break
                    await response.write(chunk)
            finally:
                await asyncio.to_thread(handle.close)
        await response.write_eof()
        return response

    async def register_device(
        self,
        ws: RuntimeWebSocketResponse,
        payload: dict[str, Any],
        http_base: str,
    ) -> None:
        device_id = payload.get("device_id")
        if not isinstance(device_id, str) or not device_id:
            return
        capabilities = parse_capabilities(payload.get("capabilities"))
        process_instance_id = _uuid_v4_or_none(payload.get("process_instance_id"))
        if CAP_PUSH_JOB_ID_V1 in capabilities and process_instance_id is None:
            # Do not advertise safety guarantees the peer cannot fulfill.
            capabilities = frozenset(
                cap for cap in capabilities if cap != CAP_PUSH_JOB_ID_V1
            )

        lock = self._device_lock(device_id)
        snapshots: list[dict[str, Any]] = []
        needs_reconcile = False
        registered = False
        session = LiveSession(
            device_id=device_id,
            session_id=str(uuid.uuid4()),
            ws=ws,
            capabilities=capabilities,
            process_instance_id=process_instance_id,
            owner_lock=lock,
            http_base=http_base,
        )
        async with lock:
            if (
                self.registration_candidates.get(device_id) is not ws
                or not self._legacy_owns_device(device_id, ws)
            ):
                return

            previous = self.sessions.get(device_id)
            if previous is not None and previous.ws is not ws:
                self.sessions.pop(device_id, None)
                active = await self.manager.active_assignment_for_device(device_id)
                if active is not None:
                    job_id = active["job_id"]
                    attempt = active["attempt"]
                    state = DeviceState(active["state"])
                    self.transfers.release_exact(
                        TransferKey("push", device_id, job_id, attempt),
                        "connection_replaced",
                    )
                    try:
                        if state is DeviceState.WAITING_TRANSFER:
                            snapshots.append(
                                await self.manager.transition_device(
                                    job_id,
                                    device_id,
                                    expected={state},
                                    target=DeviceState.QUEUED,
                                    fields={"queue_reason": "awaiting_dispatch"},
                                )
                            )
                        elif state in {
                            DeviceState.DISPATCHING,
                            DeviceState.DOWNLOADING,
                            DeviceState.VALIDATING,
                            DeviceState.APPLYING,
                        }:
                            short = (
                                state is DeviceState.DISPATCHING
                                and active.get("accepted_at") is None
                            )
                            deadline = now_ms() + int(
                                (
                                    self.accept_reconciliation_timeout
                                    if short
                                    else self.reconciliation_timeout
                                )
                                * 1000
                            )
                            snapshots.append(
                                await self.manager.mark_reconciling(
                                    job_id,
                                    device_id,
                                    expected={state},
                                    reason=(
                                        "disconnect_before_accept"
                                        if short
                                        else "device_disconnect"
                                    ),
                                    deadline=deadline,
                                )
                            )
                    except StoreConflict:
                        pass

            runtime = payload.get("push_runtime")
            active_report = (
                runtime.get("active") if isinstance(runtime, dict) else None
            )
            if isinstance(active_report, dict):
                snapshots.extend(
                    await self._registration_active_snapshots(
                        device_id, session, active_report
                    )
                )
            else:
                # A missing process UUID on an offline-timeout fence is safe to
                # replace only when the new job-v1 process explicitly reports no
                # active execution. An active or malformed report must keep the
                # fence until exact reconciliation evidence settles it.
                if (
                    isinstance(runtime, dict)
                    and "active" in runtime
                    and active_report is None
                ):
                    snapshots.extend(
                        await self.manager.clear_fence_on_process_replacement(
                            device_id,
                            process_instance_id,
                            CAP_PUSH_JOB_ID_V1 in capabilities,
                        )
                    )
                active = await self.manager.active_assignment_for_device(device_id)
                needs_reconcile = bool(
                    active
                    and active["state"] == DeviceState.RECONCILING.value
                )

            if (
                self.registration_candidates.get(device_id) is not ws
                or not self._legacy_owns_device(device_id, ws)
            ):
                return
            self.sessions[device_id] = session
            try:
                await asyncio.wait_for(
                    ws.send_str(
                        json.dumps(
                            {"type": "REGISTERED", "session_id": session.session_id},
                            separators=(",", ":"),
                        )
                    ),
                    self.send_timeout,
                )
            except BaseException:
                if self.sessions.get(device_id) is session:
                    self.sessions.pop(device_id, None)
                raise
            if self.registration_candidates.get(device_id) is ws:
                self.registration_candidates.pop(device_id, None)
            registered = True

        for snapshot in snapshots:
            await self.publish(snapshot)
        if not registered:
            return
        if needs_reconcile:
            await self.request_reconcile(device_id)
        if self.scheduler is not None:
            self.scheduler.wake()

    async def _registration_active_snapshots(
        self,
        device_id: str,
        session: LiveSession,
        active_report: dict[str, Any],
    ) -> list[dict[str, Any]]:
        job_id = active_report.get("job_id")
        attempt = active_report.get("attempt")
        phase = active_report.get("phase")
        if not isinstance(job_id, str) or attempt != 1 or not isinstance(phase, str):
            return await self.manager.add_opaque_fence(
                device_id,
                self.manager.opaque_identity_for_active(active_report),
                ProtocolMode.JOB_V1,
                session.process_instance_id,
                "REGISTER reported malformed active Push/Sync state",
            )

        assignment = await self.manager.assignment(job_id, device_id)
        if assignment is None:
            return await self.manager.add_opaque_fence(
                device_id,
                self.manager.opaque_identity_for_active(active_report),
                ProtocolMode.JOB_V1,
                session.process_instance_id,
                "REGISTER reported an active Push/Sync job unknown to this server",
            )

        reported_artifact = active_report.get("artifact_id")
        if (
            not isinstance(reported_artifact, str)
            or reported_artifact != assignment.get("artifact_id")
        ):
            snapshots = await self.manager.add_opaque_fence(
                device_id,
                self.manager.opaque_identity_for_active(active_report),
                ProtocolMode.JOB_V1,
                session.process_instance_id,
                "REGISTER active state conflicted with the canonical artifact",
            )
            current = DeviceState(assignment["state"])
            if current not in {
                DeviceState.SUCCEEDED,
                DeviceState.FAILED,
                DeviceState.INTERRUPTED,
                DeviceState.UNCONFIRMED,
            }:
                try:
                    snapshots.append(
                        await self.manager.transition_device(
                            job_id,
                            device_id,
                            expected={current},
                            target=DeviceState.FAILED,
                            fields={
                                "failure_code": "artifact_identity_mismatch",
                                "failure_detail": (
                                    "REGISTER active artifact did not match the "
                                    "canonical assignment"
                                ),
                                "accept_deadline": None,
                                "reconciliation_reason": None,
                                "reconciliation_deadline": None,
                            },
                        )
                    )
                except StoreConflict:
                    pass
            return snapshots

        if assignment["state"] != DeviceState.RECONCILING.value:
            return []
        try:
            outcome, snapshots = await self.manager.reconcile_report(
                job_id,
                device_id,
                1,
                "active",
                phase,
                None,
            )
            return snapshots if outcome == "active" else []
        except StoreConflict:
            return []

    async def disconnect_device(
        self, device_id: str | None, ws: RuntimeWebSocketResponse
    ) -> None:
        if not device_id:
            return
        lock = self._device_lock(device_id)
        snapshot: dict[str, Any] | None = None
        async with lock:
            if self.registration_candidates.get(device_id) is ws:
                self.registration_candidates.pop(device_id, None)
            session = self.sessions.get(device_id)
            if session is None or session.ws is not ws:
                return
            self.sessions.pop(device_id, None)
            active = await self.manager.active_assignment_for_device(device_id)
            if active is None:
                return
            job_id = active["job_id"]
            attempt = active["attempt"]
            state = DeviceState(active["state"])
            self.transfers.release_exact(
                TransferKey("push", device_id, job_id, attempt), "device_disconnect"
            )
            try:
                if state is DeviceState.WAITING_TRANSFER:
                    snapshot = await self.manager.transition_device(
                        job_id,
                        device_id,
                        expected={state},
                        target=DeviceState.QUEUED,
                        fields={"queue_reason": "awaiting_dispatch"},
                    )
                elif state in {
                    DeviceState.DISPATCHING,
                    DeviceState.DOWNLOADING,
                    DeviceState.VALIDATING,
                    DeviceState.APPLYING,
                }:
                    short = (
                        state is DeviceState.DISPATCHING
                        and active.get("accepted_at") is None
                    )
                    deadline = now_ms() + int(
                        (
                            self.accept_reconciliation_timeout
                            if short
                            else self.reconciliation_timeout
                        )
                        * 1000
                    )
                    snapshot = await self.manager.mark_reconciling(
                        job_id,
                        device_id,
                        expected={state},
                        reason=(
                            "disconnect_before_accept" if short else "device_disconnect"
                        ),
                        deadline=deadline,
                    )
            except StoreConflict:
                pass
        if snapshot is not None:
            await self.publish(snapshot)

    async def handle_device_message(
        self,
        ws: RuntimeWebSocketResponse,
        device_id: str | None,
        payload: dict[str, Any],
    ) -> bool:
        message_type = payload.get("type")
        if not device_id:
            return False
        job_id = payload.get("job_id")
        job_v1 = message_type in {
            "PUSH_JOB_ACCEPTED",
            "PUSH_JOB_REJECTED",
            "PUSH_TRANSFER_COMPLETE",
            "PUSH_PHASE",
            "PUSH_RECONCILE_REPORT",
        } or (message_type in {"DOWNLOAD_COMPLETE", "PUSH_FILES_RESULT"} and isinstance(job_id, str))

        legacy_push_message = (
            message_type == "DOWNLOAD_COMPLETE" and payload.get("task") == "push"
        ) or (
            message_type == "PUSH_FILES_RESULT" and not isinstance(job_id, str)
        )
        if job_v1 or legacy_push_message:
            lock = self._device_lock(device_id)
            async with lock:
                candidate = self.registration_candidates.get(device_id)
                session = self.sessions.get(device_id)
                if (
                    candidate is not None and candidate is not ws
                ) or session is None or session.ws is not ws:
                    # Consume a stale job-v1 frame. For a legacy frame return False so
                    # the established server sees it too, but its own _owns_device guard
                    # will reject the superseded connection.
                    return True if job_v1 else False
                if job_v1:
                    return await self._handle_owned_job_v1_message(
                        session, device_id, payload
                    )
                return await self._handle_owned_legacy_message(
                    session, device_id, payload
                )
        return False

    async def _handle_owned_legacy_message(
        self,
        session: LiveSession,
        device_id: str,
        payload: dict[str, Any],
    ) -> bool:
        """Settle migration-only legacy Push only for the current socket owner."""

        message_type = payload.get("type")
        if message_type == "DOWNLOAD_COMPLETE" and payload.get("task") == "push":
            active = await self.manager.active_assignment_for_device(device_id)
            if active and active["protocol_mode"] == ProtocolMode.LEGACY.value:
                key = TransferKey("push", device_id, active["job_id"], 1)
                self.transfers.release_exact(key, "legacy_download_complete")
                try:
                    snapshot = await self.manager.transition_device(
                        active["job_id"],
                        device_id,
                        expected={DeviceState.DOWNLOADING},
                        target=DeviceState.VALIDATING,
                        fields={"transfer_completed_at": now_ms()},
                    )
                    await self.publish(snapshot)
                except StoreConflict:
                    pass
                return True
            return False
        if message_type == "PUSH_FILES_RESULT":
            active = await self.manager.active_assignment_for_device(device_id)
            if active and active["protocol_mode"] == ProtocolMode.LEGACY.value:
                enriched = dict(payload)
                enriched["job_id"] = active["job_id"]
                enriched["attempt"] = 1
                await self._handle_result(
                    device_id, enriched, owned_session=session
                )
                return True
        return False

    async def _handle_owned_job_v1_message(
        self,
        session: LiveSession,
        device_id: str,
        payload: dict[str, Any],
    ) -> bool:
        message_type = payload.get("type")
        job_id = payload.get("job_id")
        if message_type == "PUSH_JOB_ACCEPTED" and isinstance(job_id, str):
            if payload.get("attempt") != 1:
                return True
            delivered = False
            if self.scheduler is not None:
                delivered = self.scheduler.command_response(
                    job_id=job_id,
                    device_id=device_id,
                    attempt=1,
                    outcome="accepted",
                    payload=payload,
                )
            # wait_for() has already timed out during pre-accept reconciliation, so
            # a late but matching ACK is applied as an exact active report instead of
            # being silently discarded. This preserves the existing transfer slot.
            if not delivered:
                assignment = await self.manager.assignment(job_id, device_id)
                if assignment and assignment["state"] == DeviceState.RECONCILING.value:
                    try:
                        outcome, snapshots = await self.manager.reconcile_report(
                            job_id,
                            device_id,
                            1,
                            "active",
                            payload.get("phase")
                            if isinstance(payload.get("phase"), str)
                            else "downloading",
                            None,
                        )
                        if outcome == "active":
                            for snapshot in snapshots:
                                await self.publish(snapshot)
                            reported_phase = payload.get("phase")
                            if reported_phase in {"validating", "applying"}:
                                self.transfers.release_exact(
                                    TransferKey("push", device_id, job_id, 1),
                                    "late_accept_after_transfer",
                                )
                    except (StoreConflict, StoreNotFound):
                        pass
            return True
        if message_type == "PUSH_JOB_REJECTED" and isinstance(job_id, str):
            if payload.get("attempt") != 1:
                return True
            outcome = "busy" if payload.get("reason") == "device_busy" else "rejected"
            delivered = False
            if self.scheduler is not None:
                delivered = self.scheduler.command_response(
                    job_id=job_id,
                    device_id=device_id,
                    attempt=1,
                    outcome=outcome,
                    payload=payload,
                )
            if not delivered and self.scheduler is not None:
                await self.scheduler.handle_late_command_response(
                    job_id=job_id,
                    device_id=device_id,
                    outcome=outcome,
                    payload=payload,
                    owner_lock_held=True,
                )
            return True
        if message_type == "PUSH_TRANSFER_COMPLETE" and isinstance(job_id, str):
            await self._handle_transfer_complete(device_id, payload)
            return True
        if message_type == "DOWNLOAD_COMPLETE" and isinstance(job_id, str):
            await self._handle_validation_complete(device_id, payload)
            return True
        if message_type == "PUSH_PHASE" and isinstance(job_id, str):
            await self._handle_phase(device_id, payload)
            return True
        if message_type == "PUSH_FILES_RESULT" and isinstance(job_id, str):
            await self._handle_result(device_id, payload, session)
            return True
        if message_type == "PUSH_RECONCILE_REPORT" and isinstance(job_id, str):
            await self._handle_reconcile_report(device_id, payload)
            return True
        return False

    async def _handle_transfer_complete(
        self, device_id: str, payload: dict[str, Any]
    ) -> None:
        job_id = payload.get("job_id")
        if payload.get("attempt") != 1 or not isinstance(job_id, str):
            return
        try:
            snapshot = await self.store.get_snapshot(job_id)
        except StoreNotFound:
            return
        device = snapshot["devices"].get(device_id)
        artifact = snapshot.get("artifact")
        received = payload.get("received_size")
        if (
            device is None
            or artifact is None
            or payload.get("artifact_id") != artifact["artifact_id"]
            or isinstance(received, bool)
            or not isinstance(received, int)
            or received != artifact["byte_size"]
            or device["attempt"] != 1
        ):
            return
        try:
            next_snapshot = await self.manager.transition_device(
                job_id,
                device_id,
                expected={DeviceState.DOWNLOADING},
                target=DeviceState.VALIDATING,
                fields={
                    "transfer_completed_at": now_ms(),
                    "accept_deadline": None,
                    "reconciliation_reason": None,
                    "reconciliation_deadline": None,
                },
            )
        except StoreConflict:
            return
        self.transfers.release_exact(
            TransferKey("push", device_id, job_id, 1), "transfer_complete"
        )
        await self.publish(next_snapshot)

    async def _handle_validation_complete(
        self, device_id: str, payload: dict[str, Any]
    ) -> None:
        job_id = payload.get("job_id")
        if payload.get("attempt") != 1 or not isinstance(job_id, str):
            return
        try:
            snapshot = await self.store.get_snapshot(job_id)
        except StoreNotFound:
            return
        artifact = snapshot.get("artifact")
        device = snapshot["devices"].get(device_id)
        assignment = await self.manager.assignment(job_id, device_id)
        if (
            artifact is None
            or device is None
            or assignment is None
            or payload.get("artifact_id") != artifact["artifact_id"]
            or device["state"] != DeviceState.VALIDATING.value
            or assignment.get("validation_completed_at") is not None
        ):
            return
        try:
            next_snapshot = await self.manager.transition_device(
                job_id,
                device_id,
                expected={DeviceState.VALIDATING},
                target=DeviceState.VALIDATING,
                fields={"validation_completed_at": now_ms()},
            )
            await self.publish(next_snapshot)
        except StoreConflict:
            pass

    async def _handle_phase(self, device_id: str, payload: dict[str, Any]) -> None:
        if payload.get("phase") != "applying" or payload.get("attempt") != 1:
            return
        job_id = payload.get("job_id")
        if not isinstance(job_id, str):
            return
        try:
            snapshot = await self.manager.transition_device(
                job_id,
                device_id,
                expected={DeviceState.VALIDATING, DeviceState.RECONCILING},
                target=DeviceState.APPLYING,
                fields={
                    "apply_started_at": now_ms(),
                    "reconciliation_reason": None,
                    "reconciliation_deadline": None,
                },
            )
            await self.publish(snapshot)
        except (StoreConflict, StoreNotFound):
            pass

    async def _handle_result(
        self,
        device_id: str,
        payload: dict[str, Any],
        owned_session: LiveSession,
    ) -> None:
        job_id = payload.get("job_id")
        attempt = payload.get("attempt")
        status = payload.get("status")
        if not isinstance(job_id, str):
            return
        if attempt != 1:
            await self._send_result_ack(
                device_id,
                job_id,
                False,
                None,
                "stale_result",
                owned_session=owned_session,
            )
            return
        if status not in {"success", "fail"}:
            await self._send_result_ack(
                device_id,
                job_id,
                False,
                None,
                "malformed_terminal_result",
                owned_session=owned_session,
            )
            return
        if status == "success" and any(
            isinstance(payload.get(name), bool)
            or not isinstance(payload.get(name), int)
            or payload.get(name) < 0
            for name in ("added", "updated", "deleted")
        ):
            await self._send_result_ack(
                device_id,
                job_id,
                False,
                None,
                "malformed_terminal_result",
                owned_session=owned_session,
            )
            return
        opaque_matched, opaque_snapshots = (
            await self.manager.clear_matching_opaque_fence(
                device_id, job_id, 1
            )
        )
        if opaque_matched:
            for snapshot in opaque_snapshots:
                await self.publish(snapshot)
            local = next(
                (
                    snapshot
                    for snapshot in opaque_snapshots
                    if snapshot["job_id"] == job_id
                ),
                None,
            )
            await self._send_result_ack(
                device_id,
                job_id,
                True,
                local["revision"] if local else None,
                None,
                owned_session=owned_session,
            )
            if self.scheduler is not None:
                self.scheduler.wake()
            return

        assignment = await self.manager.assignment(job_id, device_id)
        if assignment is None:
            await self._send_result_ack(
                device_id,
                job_id,
                False,
                None,
                "unknown_job",
                owned_session=owned_session,
            )
            return
        if assignment["attempt"] != 1:
            await self._send_result_ack(
                device_id,
                job_id,
                False,
                None,
                "stale_result",
                owned_session=owned_session,
            )
            return
        if assignment["state"] == DeviceState.UNCONFIRMED.value:
            accepted, snapshots = await self.manager.settle_late_fenced_result(
                job_id, device_id, 1
            )
            if accepted:
                self.transfers.release_exact(
                    TransferKey("push", device_id, job_id, 1), "terminal_result"
                )
            for snapshot in snapshots:
                await self.publish(snapshot)
            revision = next(
                (snapshot["revision"] for snapshot in snapshots if snapshot["job_id"] == job_id),
                None,
            )
            await self._send_result_ack(
                device_id,
                job_id,
                accepted,
                revision,
                None if accepted else "stale_result",
                owned_session=owned_session,
            )
            if accepted and self.scheduler is not None:
                self.scheduler.wake()
            return

        failure_code = payload.get("failure_code")
        if not isinstance(failure_code, str) or not failure_code:
            failure_code = "apply_failed" if status == "fail" else None
        elif len(failure_code) > 128:
            failure_code = failure_code[:128]
        detail = payload.get("detail")
        if not isinstance(detail, str):
            detail = payload.get("error") if isinstance(payload.get("error"), str) else ""
        detail = detail[:2000]
        was_terminal = assignment["state"] in {
            DeviceState.SUCCEEDED.value,
            DeviceState.FAILED.value,
            DeviceState.INTERRUPTED.value,
        }
        accepted, reason, snapshot = await self.store.settle_result(
            job_id,
            device_id,
            1,
            status,
            added=self._nonnegative_int(payload.get("added")),
            updated=self._nonnegative_int(payload.get("updated")),
            deleted=self._nonnegative_int(payload.get("deleted")),
            failure_code=failure_code,
            failure_detail=detail,
        )
        late_snapshots: list[dict[str, Any]] = []
        current_after_settle = snapshot["devices"].get(device_id) if snapshot else None
        if (
            not accepted
            and reason in {"unexpected_result_state", "conflicting_terminal_result"}
            and current_after_settle is not None
            and current_after_settle["state"] == DeviceState.UNCONFIRMED.value
        ):
            # Reconciliation housekeeping may commit RECONCILING -> UNCONFIRMED
            # between the assignment read above and settle_result's transaction.
            # Re-check the exact fence through the canonical Manager policy so a
            # matching late result clears it instead of receiving a permanent ACK.
            accepted, late_snapshots = await self.manager.settle_late_fenced_result(
                job_id, device_id, 1
            )
            if accepted:
                reason = None
                snapshot = next(
                    (item for item in late_snapshots if item["job_id"] == job_id),
                    snapshot,
                )
        if accepted:
            self.transfers.release_exact(
                TransferKey("push", device_id, job_id, 1), "terminal_result"
            )
        if accepted and late_snapshots:
            for late_snapshot in late_snapshots:
                await self.publish(late_snapshot)
        elif accepted and snapshot is not None and not was_terminal:
            await self.publish(snapshot)
        await self._send_result_ack(
            device_id,
            job_id,
            accepted,
            snapshot["revision"] if snapshot else None,
            reason,
            owned_session=owned_session,
        )
        if accepted and self.scheduler is not None:
            self.scheduler.wake()

    async def _send_result_ack(
        self,
        device_id: str,
        job_id: str,
        accepted: bool,
        revision: int | None,
        reason: str | None,
        *,
        owned_session: LiveSession,
    ) -> None:
        session = owned_session
        payload: dict[str, Any] = {
            "type": "PUSH_RESULT_ACK",
            "job_id": job_id,
            "attempt": 1,
            "accepted": accepted,
        }
        if revision is not None:
            payload["revision"] = revision
        if reason:
            payload["reason"] = reason
        if not accepted:
            payload["retryable"] = False
        if self.sessions.get(device_id) is not session:
            return
        try:
            await asyncio.wait_for(
                session.ws.send_str(json.dumps(payload, separators=(",", ":"))),
                self.send_timeout,
            )
        except (ConnectionError, asyncio.TimeoutError):
            pass

    async def _handle_reconcile_report(
        self, device_id: str, payload: dict[str, Any]
    ) -> None:
        job_id = payload.get("job_id")
        attempt = payload.get("attempt")
        status = payload.get("status")
        if not isinstance(job_id, str) or attempt != 1 or status not in {
            "active",
            "absent",
            "interrupted",
        }:
            return
        assignment = await self.manager.assignment(job_id, device_id)
        if assignment is None:
            if status in {"absent", "interrupted"}:
                matched, snapshots = await self.manager.clear_matching_opaque_fence(
                    device_id, job_id, 1
                )
                for snapshot in snapshots:
                    await self.publish(snapshot)
                if matched and self.scheduler is not None:
                    self.scheduler.wake()
            return
        if assignment["state"] == DeviceState.UNCONFIRMED.value and status in {
            "absent",
            "interrupted",
        }:
            snapshots = await self.manager.clear_matching_fence(job_id, device_id, 1)
            for snapshot in snapshots:
                await self.publish(snapshot)
            if snapshots and self.scheduler is not None:
                self.scheduler.wake()
            return
        if assignment["state"] != DeviceState.RECONCILING.value:
            return
        try:
            outcome, snapshots = await self.manager.reconcile_report(
                job_id,
                device_id,
                1,
                status,
                payload.get("phase") if isinstance(payload.get("phase"), str) else None,
                payload.get("detail") if isinstance(payload.get("detail"), str) else None,
            )
        except (StoreConflict, StoreNotFound):
            return
        for snapshot in snapshots:
            await self.publish(snapshot)
        if outcome in {"requeued", "interrupted"} or (
            outcome == "active" and payload.get("phase") in {"validating", "applying"}
        ):
            self.transfers.release_exact(
                TransferKey("push", device_id, job_id, 1), outcome
            )
        if self.scheduler is not None:
            self.scheduler.wake()

    async def handle_admin_message(
        self, ws: RuntimeWebSocketResponse, payload: dict[str, Any]
    ) -> bool:
        message_type = payload.get("type")
        if message_type == "PUSH_FILES" and isinstance(payload.get("job_id"), str):
            job_id = payload["job_id"]
            try:
                changed, snapshot = await self.store.enable_dispatch(job_id)
            except (StoreConflict, StoreNotFound) as exc:
                try:
                    await asyncio.wait_for(
                        ws.send_str(json.dumps({"type": "ERROR", "message": str(exc)})),
                        self.admin_send_timeout,
                    )
                except asyncio.CancelledError:
                    raise
                except (Exception, asyncio.TimeoutError):
                    log.warning("Could not send Push dispatch error to admin", exc_info=True)
                    await self._close_failed_admin_socket(ws, b"admin send failed")
                return True
            scheduler_woken = False
            if self.scheduler is not None:
                self.scheduler.wake()
                scheduler_woken = True
            try:
                if changed:
                    await self.publish(snapshot)
                await asyncio.wait_for(
                    ws.send_str(
                        json.dumps({
                            "type": "PUSH_FILES_SENT",
                            "job_id": job_id,
                            "revision": snapshot["revision"],
                            "state": snapshot["state"],
                            "dispatch_enabled": snapshot["dispatch_enabled"],
                            "target_count": snapshot["aggregate"]["total"],
                            "max_concurrent": self.legacy.MAX_CONCURRENT_TRANSFERS,
                            "delete_extras": snapshot["mode"] == "sync",
                            "dest_path": snapshot["dest_path"],
                        }, separators=(",", ":"))
                    ),
                    self.admin_send_timeout,
                )
            except asyncio.CancelledError:
                raise
            except (Exception, asyncio.TimeoutError):
                log.warning("Could not acknowledge Push dispatch to admin", exc_info=True)
                await self._close_failed_admin_socket(ws, b"admin send failed")
            finally:
                if self.scheduler is not None and not scheduler_woken:
                    self.scheduler.wake()
            return True
        if message_type == "RECONCILE_PUSH_DEVICE":
            device_id = payload.get("device_id")
            if isinstance(device_id, str):
                await self.request_reconcile(device_id)
            return True
        return False

    async def request_reconcile(self, device_id: str) -> None:
        session = self.sessions.get(device_id)
        if session is None or self.scheduler is None:
            return
        active = await self.manager.active_assignment_for_device(device_id)
        if active and active["state"] == DeviceState.RECONCILING.value:
            snapshot = await self.store.get_snapshot(active["job_id"])
            await self.scheduler.send_reconcile(session, snapshot, device_id)
            return
        fence = await self.manager.fenced_assignment_for_device(device_id)
        if fence and fence.get("blocking_job_id") and fence.get("blocking_attempt") == 1:
            await self.scheduler.send_exact_reconcile(
                session,
                fence["blocking_job_id"],
                1,
                fence.get("artifact_id"),
            )
            return
        opaque = await self.manager.opaque_reconcile_target(device_id)
        if opaque is not None:
            await self.scheduler.send_exact_reconcile(
                session,
                opaque["job_id"],
                opaque["attempt"],
                opaque.get("artifact_id"),
            )

    async def publish(self, snapshot: dict[str, Any]) -> None:
        job_id = snapshot["job_id"]
        revision = snapshot["revision"]
        if revision <= self.publication_revisions.get(job_id, -1):
            return
        self.publication_revisions[job_id] = revision
        self.pending_publications[job_id] = snapshot
        self.publication_wake.set()

    async def _publication_loop(self) -> None:
        while True:
            await self.publication_wake.wait()
            self.publication_wake.clear()
            while self.pending_publications:
                job_id, snapshot = self.pending_publications.popitem()
                event = {
                    "type": "PUSH_JOB_UPDATED",
                    "job_id": job_id,
                    "revision": snapshot["revision"],
                    "job": snapshot,
                }
                try:
                    await self.legacy.forward_to_admins(event)
                except asyncio.CancelledError:
                    raise
                except Exception:
                    log.exception("Could not publish Push job update for %s", job_id)
            if self.publication_stopping and not self.pending_publications:
                return

    def arm_created_deadline(self) -> None:
        if self.created_deadline_task is None or self.created_deadline_task.done():
            self.created_deadline_task = asyncio.create_task(
                self._created_deadline_loop(), name="push-created-deadline"
            )
            return
        # Wake the single owner task to recalculate the nearest deadline. Cancelling a
        # task while its Store mutation is committing can otherwise lose publication.
        self.created_deadline_wake.set()

    async def _created_deadline_loop(self) -> None:
        while True:
            try:
                self.created_deadline_wake.clear()
                nearest = await self.manager.next_created_deadline()
                if nearest is None:
                    await self.created_deadline_wake.wait()
                    continue
                job_id, deadline = nearest
                delay = max(0, deadline - now_ms()) / 1000
                try:
                    await asyncio.wait_for(
                        self.created_deadline_wake.wait(), timeout=delay
                    )
                    continue
                except asyncio.TimeoutError:
                    pass
                try:
                    snapshot = await self.store.expire_created(job_id, deadline)
                except (StoreConflict, StoreNotFound):
                    snapshot = None
                if snapshot is not None:
                    try:
                        await self.publish(snapshot)
                    finally:
                        try:
                            await asyncio.to_thread(
                                self.artifacts.cleanup_work_best_effort, job_id
                            )
                        finally:
                            if self.scheduler is not None:
                                self.scheduler.wake()
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("Unexpected failure in Push created-deadline loop")
                await asyncio.sleep(_BACKGROUND_LOOP_RETRY_DELAY)

    async def _reconciliation_housekeeping(self) -> None:
        while True:
            await asyncio.sleep(_RECONCILIATION_POLL_INTERVAL)
            try:
                timestamp = now_ms()
                acceptance_rows = await self.manager.expired_acceptances(timestamp)
                rows = await self.manager.expired_reconciliations(timestamp)
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("Could not query expired Push deadlines")
                continue
            for row in acceptance_rows:
                if self.scheduler is not None and self.scheduler.has_live_acceptance_waiter(
                    row["job_id"], row["device_id"], row["attempt"]
                ):
                    continue
                try:
                    job_id = row["job_id"]
                    device_id = row["device_id"]
                    changed, snapshot = await self.manager.mark_acceptance_reconciling(
                        job_id,
                        device_id,
                        expected_accept_deadline=row["accept_deadline"],
                        reconciliation_deadline=(
                            now_ms()
                            + int(self.accept_reconciliation_timeout * 1000)
                        ),
                    )
                    if changed:
                        await self.publish(snapshot)
                    await self.request_reconcile(device_id)
                except StoreConflict:
                    continue
                except asyncio.CancelledError:
                    raise
                except Exception:
                    log.exception(
                        "Could not recover expired Push acceptance %s/%s",
                        row.get("job_id"),
                        row.get("device_id"),
                    )
                finally:
                    if self.scheduler is not None:
                        self.scheduler.wake()
            for row in rows:
                try:
                    job_id = row["job_id"]
                    device_id = row["device_id"]
                    attempt = row["attempt"]
                    deadline = row["reconciliation_deadline"]
                    session = self.sessions.get(device_id)
                    timestamp = now_ms()
                    snapshots = await self.manager.mark_unconfirmed(
                        job_id,
                        device_id,
                        session.process_instance_id if session else None,
                        "reconciliation deadline elapsed without matching evidence",
                        expected_deadline=deadline,
                        observed_now=timestamp,
                    )
                except StoreConflict:
                    continue
                except asyncio.CancelledError:
                    raise
                except Exception:
                    log.exception(
                        "Could not expire Push reconciliation row %r",
                        row,
                    )
                    continue
                try:
                    try:
                        self.transfers.release_exact(
                            TransferKey("push", device_id, job_id, attempt),
                            "reconciliation_timeout",
                        )
                        for snapshot in snapshots:
                            await self.publish(snapshot)
                    finally:
                        if self.scheduler is not None:
                            self.scheduler.wake()
                except asyncio.CancelledError:
                    raise
                except Exception:
                    log.exception(
                        "Could not publish expired Push reconciliation %s/%s",
                        job_id,
                        device_id,
                    )

    @staticmethod
    def _nonnegative_int(value: Any) -> int:
        return (
            value
            if isinstance(value, int) and not isinstance(value, bool) and value >= 0
            else 0
        )


def runtime_for_current_server() -> PushRuntime:
    from . import server

    data_dir = Path(server.DATA_DIR).resolve()
    runtime = _RUNTIME_BY_DATA_DIR.get(data_dir)
    if runtime is None:
        runtime = PushRuntime(server, data_dir)
        _RUNTIME_BY_DATA_DIR[data_dir] = runtime
        server.pending_transfers = runtime.legacy_transfers
    return runtime


def _release_transfer_slot(
    device_id: str, reason: str, task: str | None = None
) -> bool:
    from . import server as legacy_server

    mapping = legacy_server.pending_transfers
    if task in {"install", "push"}:
        future = mapping.get((device_id, task))
        if future is None or future.done():
            return False
        future.set_result(reason)
        return True

    released = False
    for key in tuple(mapping):
        if key[0] != device_id:
            continue
        future = mapping.get(key)
        if future is not None and not future.done():
            future.set_result(reason)
            released = True
    return released


def install(server: Any) -> None:
    global _INSTALLED, _ORIGINAL_CREATE_APP
    if _INSTALLED:
        return
    _INSTALLED = True
    _ORIGINAL_CREATE_APP = server.create_app
    server._websocket_response_factory = RuntimeWebSocketResponse
    server.release_transfer_slot = _release_transfer_slot

    def create_app() -> aiohttp_web.Application:
        runtime = runtime_for_current_server()
        app = _ORIGINAL_CREATE_APP()
        app["push_runtime"] = runtime
        app.router.add_post("/api/push-jobs", runtime.create_job_handler)
        app.router.add_post(
            "/api/push-jobs/{job_id}/upload", runtime.upload_handler
        )
        app.router.add_get("/artifacts/{artifact_id}", runtime.artifact_handler)
        app.on_startup.append(runtime.on_startup)
        app.on_cleanup.append(runtime.on_cleanup)
        return app

    server.create_app = create_app
