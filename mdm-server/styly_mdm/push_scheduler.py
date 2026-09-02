"""Durable per-device Push/Sync scheduler for Issue #91."""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass
from typing import Any
from urllib.parse import urljoin

from .push_job_manager import PushJobManager
from .push_job_store import StoreConflict, now_ms
from .push_jobs import (
    ACTIVE_DEVICE_STATES,
    CAP_PUSH_JOB_ID_V1,
    CAP_PUSH_RESUME_V1,
    DeviceState,
    ProtocolMode,
)
from .transfer_registry import TransferKey, TransferRegistry

log = logging.getLogger("stylymdm.push")

_RUN_RETRY_DELAY = 1.0


class ConnectionOwnerChanged(ConnectionError):
    pass


@dataclass(slots=True)
class LiveSession:
    device_id: str
    session_id: str
    ws: Any
    capabilities: frozenset[str]
    process_instance_id: str | None
    owner_lock: asyncio.Lock
    http_base: str
    reported_push_runtime: dict[str, Any] | None = None


class PushScheduler:
    def __init__(
        self,
        *,
        manager: PushJobManager,
        transfer_registry: TransferRegistry,
        transfer_slots: Callable[[], asyncio.Semaphore],
        sessions: Callable[[], Mapping[str, LiveSession]],
        publish: Callable[[dict[str, Any]], Awaitable[None]],
        send_timeout: float,
        accept_timeout: float,
        accept_reconciliation_timeout: float,
        reconciliation_timeout: float,
        transfer_timeout: float,
        allow_legacy: bool,
        resume_threshold_bytes: int = 64 * 1024 * 1024,
    ) -> None:
        self.manager = manager
        self.transfer_registry = transfer_registry
        self.transfer_slots = transfer_slots
        self.sessions = sessions
        self.publish = publish
        self.send_timeout = send_timeout
        self.accept_timeout = accept_timeout
        self.accept_reconciliation_timeout = accept_reconciliation_timeout
        self.reconciliation_timeout = reconciliation_timeout
        self.transfer_timeout = transfer_timeout
        self.allow_legacy = allow_legacy
        self.resume_threshold_bytes = max(0, resume_threshold_bytes)
        self._wake = asyncio.Event()
        self._runner: asyncio.Task[None] | None = None
        self._stopping = False
        self._dispatch_tasks: set[asyncio.Task[None]] = set()
        self._accept_waiters: dict[
            tuple[str, str, int], asyncio.Future[tuple[str, dict[str, Any]]]
        ] = {}
        self._dispatch_waiters: dict[
            asyncio.Task[Any],
            tuple[
                TransferKey,
                asyncio.Future[str],
                asyncio.Future[tuple[str, dict[str, Any]]] | None,
            ],
        ] = {}

    def start(self) -> None:
        if not self._stopping and (self._runner is None or self._runner.done()):
            self._runner = asyncio.create_task(self._run(), name="push-job-scheduler")
            self._wake.set()

    async def stop(self) -> None:
        self._stopping = True
        if self._runner is not None:
            self._runner.cancel()
        for task in tuple(self._dispatch_tasks):
            task.cancel()
        await asyncio.gather(
            *(task for task in ([self._runner] if self._runner else []) + list(self._dispatch_tasks)),
            return_exceptions=True,
        )
        self._runner = None
        self._dispatch_tasks.clear()
        for future in self._accept_waiters.values():
            if not future.done():
                future.cancel()
        self._accept_waiters.clear()
        self._dispatch_waiters.clear()
        self._stopping = False

    def wake(self) -> None:
        if self._runner is None or self._runner.done():
            self.start()
        self._wake.set()

    def has_live_acceptance_waiter(
        self, job_id: str, device_id: str, attempt: int
    ) -> bool:
        """Return whether the local dispatch task still owns the exact ACK wait."""

        waiter = self._accept_waiters.get((job_id, device_id, attempt))
        if waiter is None or waiter.done():
            return False
        expected_key = TransferKey("push", device_id, job_id, attempt)
        return any(
            not task.done()
            and key == expected_key
            and accept_future is waiter
            for task, (key, _transfer_future, accept_future) in self._dispatch_waiters.items()
        )

    async def _run(self) -> None:
        while True:
            await self._wake.wait()
            self._wake.clear()
            while True:
                try:
                    assignment = await self.manager.claim_next(self.sessions().keys())
                    if assignment is None:
                        break
                    job_id = assignment["job"]["job_id"]
                    device_id = assignment["device_id"]
                    task = asyncio.create_task(
                        self._dispatch_assignment(assignment),
                        name=f"push-{job_id}-{device_id}",
                    )
                    self._dispatch_tasks.add(task)
                    task.add_done_callback(self._dispatch_done)
                except Exception:
                    log.exception("Unexpected failure in Push scheduler loop")
                    await asyncio.sleep(_RUN_RETRY_DELAY)

    def _dispatch_done(self, task: asyncio.Task[None]) -> None:
        self._dispatch_tasks.discard(task)
        if task.cancelled():
            return
        error = task.exception()
        if error is not None:
            log.error(
                "Unexpected Push dispatch failure",
                exc_info=(type(error), error, error.__traceback__),
            )
        self.wake()

    async def _dispatch_assignment(self, assignment: dict[str, Any]) -> None:
        try:
            await self._dispatch_assignment_inner(assignment)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            log.exception(
                "Unexpected Push dispatch failure for %s/%s",
                assignment["job"]["job_id"],
                assignment["device_id"],
            )
            await self._recover_unexpected_dispatch_failure(assignment, error)

    async def _dispatch_assignment_inner(self, assignment: dict[str, Any]) -> None:
        job = assignment["job"]
        job_id = job["job_id"]
        device_id = assignment["device_id"]
        attempt = assignment["attempt"]
        key = TransferKey("push", device_id, job_id, attempt)
        loop = asyncio.get_running_loop()
        transfer_future: asyncio.Future[str] = loop.create_future()
        accept_future: asyncio.Future[tuple[str, dict[str, Any]]] | None = None

        async with self.transfer_slots():
            session = self.sessions().get(device_id)
            if session is None:
                await self._fail_current(
                    job_id,
                    device_id,
                    {DeviceState.WAITING_TRANSFER},
                    "device_offline_before_dispatch",
                    "Device went offline before its queue turn",
                )
                return

            protocol = self._protocol_for(
                session, int((job.get("artifact") or {}).get("byte_size") or 0)
            )
            if protocol is None:
                await self._fail_current(
                    job_id,
                    device_id,
                    {DeviceState.WAITING_TRANSFER},
                    "capability_changed_before_dispatch",
                    (
                        "Live session no longer supports push_resume_v1 for this large artifact"
                        if int((job.get("artifact") or {}).get("byte_size") or 0)
                        > self.resume_threshold_bytes
                        else "Live session no longer supports push_job_id_v1 and legacy fallback is disabled"
                    ),
                )
                return

            # Register before the canonical waiting_transfer -> dispatching commit. A
            # client frame can arrive immediately after send; no completion window may
            # exist without an exact waiter.
            self.transfer_registry.register(key, transfer_future)
            if protocol is ProtocolMode.JOB_V1:
                accept_future = loop.create_future()
                self._accept_waiters[(job_id, device_id, attempt)] = accept_future
            current_task = asyncio.current_task()
            if current_task is not None:
                self._dispatch_waiters[current_task] = (
                    key,
                    transfer_future,
                    accept_future,
                )
            accept_deadline = (
                now_ms() + int(self.accept_timeout * 1000)
                if protocol is ProtocolMode.JOB_V1
                else None
            )
            try:
                snapshot = await self.manager.prepare_dispatch(
                    job_id,
                    device_id,
                    protocol_mode=protocol,
                    live_capabilities=session.capabilities,
                    accept_deadline=accept_deadline,
                )
            except BaseException:
                self._clear_dispatch_waiters(key, transfer_future, accept_future)
                raise
            try:
                await self.publish(snapshot)
            except BaseException:
                self._clear_dispatch_waiters(key, transfer_future, accept_future)
                raise

            command = self._command(snapshot, device_id, protocol, session.http_base)
            try:
                # REGISTER replacement, disconnect, final owner check, and send all
                # share this per-device lock. No other await occurs while it is held
                # except the bounded send itself.
                async with session.owner_lock:
                    current = self.sessions().get(device_id)
                    if current is not session or current.session_id != session.session_id:
                        raise ConnectionOwnerChanged(
                            "device WebSocket owner changed before command send"
                        )
                    await asyncio.wait_for(
                        session.ws.send_str(json.dumps(command, separators=(",", ":"))),
                        self.send_timeout,
                    )
            except ConnectionOwnerChanged as exc:
                self.transfer_registry.release_exact(key, "connection_replaced")
                self._clear_dispatch_waiters(key, transfer_future, accept_future)
                await self._requeue_after_send_race(job_id, device_id, str(exc))
                return
            except asyncio.CancelledError:
                self._clear_dispatch_waiters(key, transfer_future, accept_future)
                raise
            except BaseException as exc:
                self.transfer_registry.release_exact(key, "command_send_failed")
                self._clear_dispatch_waiters(key, transfer_future, accept_future)
                await self._fail_current(
                    job_id,
                    device_id,
                    {DeviceState.DISPATCHING},
                    "command_send_failed",
                    f"Could not send EXECUTE_PUSH_FILES: {exc}",
                )
                return

            if protocol is ProtocolMode.LEGACY:
                try:
                    snapshot = await self.manager.transition_device(
                        job_id,
                        device_id,
                        expected={DeviceState.DISPATCHING},
                        target=DeviceState.DOWNLOADING,
                        fields={"accepted_at": now_ms(), "accept_deadline": None},
                    )
                    await self.publish(snapshot)
                except StoreConflict:
                    self._clear_dispatch_waiters(key, transfer_future, accept_future)
                    return
            else:
                assert accept_future is not None
                try:
                    accepted = await self._await_acceptance(
                        session,
                        snapshot,
                        device_id,
                        accept_future,
                        key,
                        accept_deadline,
                    )
                except BaseException:
                    self._clear_dispatch_waiters(key, transfer_future, accept_future)
                    raise
                if not accepted:
                    self._clear_dispatch_waiters(key, transfer_future, accept_future)
                    return

            try:
                await asyncio.wait_for(transfer_future, self.transfer_timeout)
            except asyncio.TimeoutError:
                # This is resource recovery only. The device execution remains owned
                # and moves to reconciliation rather than becoming terminal.
                active = await self.manager.active_assignment_for_device(device_id)
                if active and active["job_id"] == job_id and active["attempt"] == attempt:
                    current = DeviceState(active["state"])
                    if current in {
                        DeviceState.DISPATCHING,
                        DeviceState.DOWNLOADING,
                        DeviceState.VALIDATING,
                        DeviceState.APPLYING,
                    }:
                        deadline = now_ms() + int(self.reconciliation_timeout * 1000)
                        try:
                            snapshot = await self.manager.mark_reconciling(
                                job_id,
                                device_id,
                                expected={current},
                                reason="transfer_timeout",
                                deadline=deadline,
                            )
                            await self.publish(snapshot)
                            live = self.sessions().get(device_id)
                            if live is not None:
                                await self.send_reconcile(live, snapshot, device_id)
                        except (StoreConflict, ConnectionError, asyncio.TimeoutError):
                            pass
            finally:
                self._clear_dispatch_waiters(key, transfer_future, accept_future)

    def _clear_dispatch_waiters(
        self,
        key: TransferKey,
        transfer_future: asyncio.Future[str],
        accept_future: asyncio.Future[tuple[str, dict[str, Any]]] | None,
    ) -> None:
        assert key.job_id is not None
        assert key.attempt is not None
        if not transfer_future.done():
            transfer_future.cancel()
        self.transfer_registry.remove_if_same(key, transfer_future)
        accept_key = (key.job_id, key.device_id, key.attempt)
        if accept_future is not None and self._accept_waiters.get(accept_key) is accept_future:
            self._accept_waiters.pop(accept_key, None)
            if not accept_future.done():
                accept_future.cancel()
        current_task = asyncio.current_task()
        if current_task is not None:
            owned = self._dispatch_waiters.get(current_task)
            if owned is not None and owned[1] is transfer_future:
                self._dispatch_waiters.pop(current_task, None)
        self.wake()

    async def _recover_unexpected_dispatch_failure(
        self, assignment: dict[str, Any], error: Exception
    ) -> None:
        """Recover durable ownership after an unexpected dispatch-task exception."""

        job_id = assignment["job"]["job_id"]
        device_id = assignment["device_id"]
        attempt = assignment["attempt"]
        current_task = asyncio.current_task()
        owned = (
            self._dispatch_waiters.pop(current_task, None)
            if current_task is not None
            else None
        )
        if owned is not None:
            self._clear_dispatch_waiters(*owned)

        while not self._stopping:
            try:
                current_row = await self.manager.assignment(job_id, device_id)
                if current_row is None or current_row["attempt"] != attempt:
                    return
                current = DeviceState(current_row["state"])
                if current is DeviceState.WAITING_TRANSFER:
                    snapshot = await self.manager.transition_device(
                        job_id,
                        device_id,
                        expected={DeviceState.WAITING_TRANSFER},
                        target=DeviceState.QUEUED,
                        fields={
                            "queue_reason": "dispatch_recovery",
                            "failure_code": None,
                            "failure_detail": str(error)[:2000],
                        },
                    )
                    await self.publish(snapshot)
                    return
                if current in {
                    DeviceState.DISPATCHING,
                    DeviceState.DOWNLOADING,
                    DeviceState.VALIDATING,
                    DeviceState.APPLYING,
                }:
                    pre_accept = current is DeviceState.DISPATCHING and (
                        current_row.get("accepted_at") is None
                    )
                    timeout = (
                        self.accept_reconciliation_timeout
                        if pre_accept
                        else self.reconciliation_timeout
                    )
                    snapshot = await self.manager.mark_reconciling(
                        job_id,
                        device_id,
                        expected={current},
                        reason="unexpected_dispatch_failure",
                        deadline=now_ms() + int(timeout * 1000),
                    )
                elif current is DeviceState.RECONCILING:
                    snapshot = await self.manager.get_snapshot(job_id)
                else:
                    return

                await self.publish(snapshot)
                session = self.sessions().get(device_id)
                if (
                    session is not None
                    and current_row.get("protocol_mode") == ProtocolMode.JOB_V1.value
                ):
                    try:
                        await self.send_reconcile(session, snapshot, device_id)
                    except (ConnectionError, asyncio.TimeoutError):
                        log.info(
                            "Could not send recovery reconciliation for %s/%s",
                            job_id,
                            device_id,
                        )
                return
            except asyncio.CancelledError:
                raise
            except StoreConflict:
                await asyncio.sleep(_RUN_RETRY_DELAY)
            except Exception:
                log.exception(
                    "Could not recover Push dispatch ownership for %s/%s",
                    job_id,
                    device_id,
                )
                await asyncio.sleep(_RUN_RETRY_DELAY)

    def _protocol_for(
        self, session: LiveSession, artifact_size: int = 0
    ) -> ProtocolMode | None:
        if CAP_PUSH_JOB_ID_V1 in session.capabilities:
            if (
                artifact_size > self.resume_threshold_bytes
                and CAP_PUSH_RESUME_V1 not in session.capabilities
            ):
                return None
            return ProtocolMode.JOB_V1
        if self.allow_legacy and artifact_size <= self.resume_threshold_bytes:
            return ProtocolMode.LEGACY
        return None

    async def _await_acceptance(
        self,
        session: LiveSession,
        snapshot: dict[str, Any],
        device_id: str,
        accept_future: asyncio.Future[tuple[str, dict[str, Any]]],
        key: TransferKey,
        accept_deadline: int,
    ) -> bool:
        job_id = snapshot["job_id"]
        attempt = snapshot["devices"][device_id]["attempt"]
        try:
            outcome, payload = await asyncio.wait_for(accept_future, self.accept_timeout)
        except asyncio.TimeoutError:
            deadline = now_ms() + int(self.accept_reconciliation_timeout * 1000)
            try:
                changed, next_snapshot = await self.manager.mark_acceptance_reconciling(
                    job_id,
                    device_id,
                    expected_accept_deadline=accept_deadline,
                    reconciliation_deadline=deadline,
                )
                if changed:
                    await self.publish(next_snapshot)
                await self.send_reconcile(session, next_snapshot, device_id)
                # Keep the exact transfer slot while the short probe is unresolved.
                return True
            except (StoreConflict, ConnectionError, asyncio.TimeoutError):
                return False

        if outcome == "accepted":
            try:
                next_snapshot = await self.manager.transition_device(
                    job_id,
                    device_id,
                    expected={DeviceState.DISPATCHING, DeviceState.RECONCILING},
                    target=DeviceState.DOWNLOADING,
                    fields={
                        "accepted_at": now_ms(),
                        "accept_deadline": None,
                        "reconciliation_reason": None,
                        "reconciliation_deadline": None,
                    },
                )
                await self.publish(next_snapshot)
            except StoreConflict:
                pass
            return True

        if outcome == "busy":
            continue_transfer = await self._handle_busy(snapshot, device_id, payload)
            if continue_transfer:
                return True
            self.transfer_registry.release_exact(key, "device_busy")
            return False

        self.transfer_registry.release_exact(key, "command_rejected")
        reason = payload.get("reason")
        if not isinstance(reason, str) or not reason:
            reason = "command_rejected"
        detail = payload.get("detail")
        if not isinstance(detail, str) or not detail:
            detail = reason
        await self._fail_current(
            job_id,
            device_id,
            {DeviceState.DISPATCHING, DeviceState.RECONCILING},
            reason,
            detail,
        )
        return False

    async def _handle_busy(
        self,
        snapshot: dict[str, Any],
        device_id: str,
        payload: dict[str, Any],
        *,
        owner_lock_held: bool = False,
    ) -> bool:
        job_id = snapshot["job_id"]
        active = payload.get("active_job")
        if not isinstance(active, dict):
            active = {}
        active_job_id = active.get("job_id")
        active_attempt = active.get("attempt")

        if active.get("legacy") is True:
            canonical_legacy = await self.manager.active_assignment_for_device(device_id)
            if (
                canonical_legacy is not None
                and canonical_legacy["job_id"] != job_id
                and canonical_legacy["protocol_mode"] == ProtocolMode.LEGACY.value
            ):
                try:
                    queued = await self.manager.transition_device(
                        job_id,
                        device_id,
                        expected={DeviceState.DISPATCHING, DeviceState.RECONCILING},
                        target=DeviceState.QUEUED,
                        fields={
                            "queue_reason": "same_device_job",
                            "accept_deadline": None,
                        },
                    )
                    await self.publish(queued)
                except StoreConflict:
                    return False
                self.wake()
                return False

        if active_job_id == job_id and active_attempt == 1:
            # A duplicate-safe client should normally answer ACCEPTED, but treating
            # this exact identity as accepted is safer than creating a second worker.
            try:
                next_snapshot = await self.manager.transition_device(
                    job_id,
                    device_id,
                    expected={DeviceState.DISPATCHING, DeviceState.RECONCILING},
                    target=DeviceState.DOWNLOADING,
                    fields={"accepted_at": now_ms(), "accept_deadline": None},
                )
                await self.publish(next_snapshot)
            except StoreConflict:
                return False
            return True

        known = None
        if isinstance(active_job_id, str) and active_attempt == 1:
            known = await self.manager.assignment(active_job_id, device_id)
        if known is not None:
            known_state = DeviceState(known["state"])
            fence = await self.manager.fenced_assignment_for_device(device_id)
            matches_fence = bool(
                fence
                and fence.get("blocking_job_id") == active_job_id
                and fence.get("blocking_attempt") == 1
            )
            if known_state in ACTIVE_DEVICE_STATES or (
                known_state is DeviceState.UNCONFIRMED and matches_fence
            ):
                try:
                    queued = await self.manager.transition_device(
                        job_id,
                        device_id,
                        expected={DeviceState.DISPATCHING, DeviceState.RECONCILING},
                        target=DeviceState.QUEUED,
                        fields={
                            "queue_reason": "same_device_job",
                            "accept_deadline": None,
                        },
                    )
                    await self.publish(queued)
                except StoreConflict:
                    return False
                if matches_fence:
                    live = self.sessions().get(device_id)
                    if live is not None:
                        await self.send_exact_reconcile(
                            live,
                            active_job_id,
                            1,
                            known.get("artifact_id"),
                            owner_lock_held=owner_lock_held,
                        )
                self.wake()
                return False

        # The client asserts an execution that cannot be tied to local canonical
        # state. Preserve that ambiguity in a persistent opaque fence and fail this
        # assignment rather than queueing forever or risking parallel apply.
        opaque = self.manager.opaque_identity_for_active(active)
        protocol = (
            ProtocolMode.LEGACY if active.get("legacy") is True else ProtocolMode.JOB_V1
        )
        live = self.sessions().get(device_id)
        fence_snapshots = await self.manager.add_opaque_fence(
            device_id,
            opaque or "unknown-active-job",
            protocol,
            live.process_instance_id if live else None,
            "client reported an active Push/Sync execution unknown to this server",
        )
        for affected in fence_snapshots:
            await self.publish(affected)
        await self._fail_current(
            job_id,
            device_id,
            {DeviceState.DISPATCHING, DeviceState.RECONCILING},
            "client_state_conflict",
            "Device reported an active Push/Sync execution that is not canonical on this server",
        )
        return False

    async def _requeue_after_send_race(
        self, job_id: str, device_id: str, detail: str
    ) -> None:
        try:
            snapshot = await self.manager.transition_device(
                job_id,
                device_id,
                expected={DeviceState.DISPATCHING},
                target=DeviceState.QUEUED,
                fields={
                    "queue_reason": "awaiting_dispatch",
                    "accept_deadline": None,
                    "failure_code": None,
                    "failure_detail": detail[:2000],
                },
            )
            await self.publish(snapshot)
        except StoreConflict:
            pass
        self.wake()

    async def _fail_current(
        self,
        job_id: str,
        device_id: str,
        expected: set[DeviceState],
        code: str,
        detail: str,
    ) -> None:
        try:
            snapshot = await self.manager.transition_device(
                job_id,
                device_id,
                expected=expected,
                target=DeviceState.FAILED,
                fields={
                    "failure_code": code,
                    "failure_detail": detail[:2000],
                    "accept_deadline": None,
                    "reconciliation_reason": None,
                    "reconciliation_deadline": None,
                },
            )
            await self.publish(snapshot)
        except StoreConflict:
            pass
        finally:
            self.wake()

    @staticmethod
    def _command(
        snapshot: dict[str, Any],
        device_id: str,
        protocol: ProtocolMode,
        http_base: str,
    ) -> dict[str, Any]:
        artifact = snapshot["artifact"]
        assert artifact is not None
        artifact_url = urljoin(http_base.rstrip("/") + "/", artifact["url"].lstrip("/"))
        common: dict[str, Any] = {
            "type": "EXECUTE_PUSH_FILES",
            "bundle_url": artifact_url,
            "bundle_filename": artifact["display_filename"],
            "dest_path": snapshot["dest_path"],
            "delete_extras": snapshot["mode"] == "sync",
        }
        if protocol is ProtocolMode.JOB_V1:
            device = snapshot["devices"][device_id]
            assignment_revision = device.get("dispatch_revision")
            if not isinstance(assignment_revision, int):
                assignment_revision = snapshot["revision"]
            common.update(
                {
                    "job_id": snapshot["job_id"],
                    "attempt": device["attempt"],
                    # This is immutable for one (job, device, attempt) assignment;
                    # aggregate job revisions may advance during restart recovery.
                    "revision": assignment_revision,
                    "artifact_id": artifact["artifact_id"],
                    "artifact_url": artifact_url,
                    "artifact_size": artifact["byte_size"],
                    "artifact_sha256": artifact["sha256"],
                    "artifact_etag": artifact.get(
                        "etag", f'"{artifact["sha256"]}"'
                    ),
                }
            )
        return common

    async def send_reconcile(
        self, session: LiveSession, snapshot: dict[str, Any], device_id: str
    ) -> None:
        """Send reconciliation for one canonical assignment."""

        artifact = snapshot.get("artifact") or {}
        await self.send_exact_reconcile(
            session,
            snapshot["job_id"],
            snapshot["devices"][device_id]["attempt"],
            artifact.get("artifact_id"),
        )

    async def send_exact_reconcile(
        self,
        session: LiveSession,
        job_id: str,
        attempt: int,
        artifact_id: str | None,
        *,
        owner_lock_held: bool = False,
    ) -> None:
        payload = {
            "type": "PUSH_RECONCILE_REQUEST",
            "jobs": [
                {
                    "job_id": job_id,
                    "attempt": attempt,
                    "artifact_id": artifact_id,
                }
            ],
        }

        async def send_owned() -> None:
            if self.sessions().get(session.device_id) is not session:
                raise ConnectionOwnerChanged("device owner changed before reconcile request")
            await asyncio.wait_for(
                session.ws.send_str(json.dumps(payload, separators=(",", ":"))),
                self.send_timeout,
            )

        if owner_lock_held:
            await send_owned()
        else:
            async with session.owner_lock:
                await send_owned()

    async def handle_late_command_response(
        self,
        *,
        job_id: str,
        device_id: str,
        outcome: str,
        payload: dict[str, Any],
        owner_lock_held: bool = False,
    ) -> None:
        """Apply a command response that arrived after the acceptance waiter expired."""

        assignment = await self.manager.assignment(job_id, device_id)
        if assignment is None or assignment["state"] != DeviceState.RECONCILING.value:
            return
        key = TransferKey("push", device_id, job_id, 1)
        if outcome == "busy":
            snapshot = await self.manager.get_snapshot(job_id)
            keep_transfer = await self._handle_busy(
                snapshot,
                device_id,
                payload,
                owner_lock_held=owner_lock_held,
            )
            if not keep_transfer:
                self.transfer_registry.release_exact(key, "late_device_busy")
            return

        self.transfer_registry.release_exact(key, "late_command_rejected")
        reason = payload.get("reason")
        if not isinstance(reason, str) or not reason:
            reason = "command_rejected"
        detail = payload.get("detail")
        if not isinstance(detail, str) or not detail:
            detail = reason
        await self._fail_current(
            job_id,
            device_id,
            {DeviceState.RECONCILING},
            reason,
            detail,
        )

    def command_response(
        self,
        *,
        job_id: str,
        device_id: str,
        attempt: int,
        outcome: str,
        payload: dict[str, Any],
    ) -> bool:
        future = self._accept_waiters.get((job_id, device_id, attempt))
        if future is None or future.done():
            return False
        future.set_result((outcome, payload))
        return True
