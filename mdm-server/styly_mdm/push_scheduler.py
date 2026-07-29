"""Durable per-device push scheduler for issue #91."""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import Awaitable, Callable, Iterable, Mapping
from dataclasses import dataclass
from typing import Any

from .push_job_store import PushJobStore, StoreConflict, now_ms
from .push_jobs import CAP_PUSH_JOB_ID_V1, DeviceState, ProtocolMode
from .transfer_registry import TransferKey, TransferRegistry

log = logging.getLogger("stylymdm.push")


@dataclass(slots=True)
class LiveSession:
    device_id: str
    session_id: str
    ws: Any
    capabilities: frozenset[str]
    process_instance_id: str | None
    send_lock: asyncio.Lock


class PushScheduler:
    def __init__(
        self,
        *,
        store: PushJobStore,
        transfer_registry: TransferRegistry,
        transfer_slots: Callable[[], asyncio.Semaphore],
        sessions: Callable[[], Mapping[str, LiveSession]],
        publish: Callable[[dict[str, Any]], Awaitable[None]],
        send_timeout: float,
        accept_timeout: float,
        accept_reconciliation_timeout: float,
        reconciliation_timeout: float,
        transfer_timeout: float,
    ) -> None:
        self.store = store
        self.transfer_registry = transfer_registry
        self.transfer_slots = transfer_slots
        self.sessions = sessions
        self.publish = publish
        self.send_timeout = send_timeout
        self.accept_timeout = accept_timeout
        self.accept_reconciliation_timeout = accept_reconciliation_timeout
        self.reconciliation_timeout = reconciliation_timeout
        self.transfer_timeout = transfer_timeout
        self._wake = asyncio.Event()
        self._runner: asyncio.Task[None] | None = None
        self._dispatch_tasks: set[asyncio.Task[None]] = set()
        self._accept_waiters: dict[tuple[str, str, int], asyncio.Future[tuple[str, dict[str, Any]]]] = {}

    def start(self) -> None:
        if self._runner is None or self._runner.done():
            self._runner = asyncio.create_task(self._run(), name="push-job-scheduler")
            self._wake.set()

    async def stop(self) -> None:
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

    def wake(self) -> None:
        self._wake.set()

    async def _run(self) -> None:
        while True:
            await self._wake.wait()
            self._wake.clear()
            while True:
                assignment = await self.store.claim_next(self.sessions().keys())
                if assignment is None:
                    break
                task = asyncio.create_task(
                    self._dispatch_assignment(assignment),
                    name=f"push-{assignment['job']['job_id']}-{assignment['device_id']}",
                )
                self._dispatch_tasks.add(task)
                task.add_done_callback(self._dispatch_tasks.discard)

    async def _dispatch_assignment(self, assignment: dict[str, Any]) -> None:
        job = assignment["job"]
        job_id = job["job_id"]
        device_id = assignment["device_id"]
        attempt = assignment["attempt"]
        protocol = ProtocolMode(assignment["protocol_mode"])
        key = TransferKey("push", device_id, job_id, attempt)
        loop = asyncio.get_running_loop()
        transfer_future: asyncio.Future[str] = loop.create_future()
        accept_future: asyncio.Future[tuple[str, dict[str, Any]]] | None = None

        async with self.transfer_slots():
            session = self.sessions().get(device_id)
            if session is None:
                await self._fail_before_send(job_id, device_id, "device_offline_before_dispatch", "Device went offline before dispatch")
                return
            if protocol is ProtocolMode.JOB_V1 and CAP_PUSH_JOB_ID_V1 not in session.capabilities:
                await self._fail_before_send(
                    job_id,
                    device_id,
                    "capability_changed_before_dispatch",
                    "push_job_id_v1 was not present on the live dispatch session",
                )
                return

            accept_deadline = now_ms() + int(self.accept_timeout * 1000)
            try:
                snapshot = await self.store.mark_dispatching(
                    job_id, device_id, session.capabilities, accept_deadline
                )
            except StoreConflict:
                self.wake()
                return
            await self.publish(snapshot)
            self.transfer_registry.register(key, transfer_future)
            if protocol is ProtocolMode.JOB_V1:
                accept_future = loop.create_future()
                self._accept_waiters[(job_id, device_id, attempt)] = accept_future

            command = self._command(snapshot, device_id, protocol)
            try:
                async with session.send_lock:
                    current = self.sessions().get(device_id)
                    if current is not session or current.session_id != session.session_id:
                        raise ConnectionError("device WebSocket owner changed before send")
                    await asyncio.wait_for(
                        session.ws.send_str(json.dumps(command, separators=(",", ":"))),
                        self.send_timeout,
                    )
            except Exception as exc:
                self.transfer_registry.release_exact(key, "command_send_failed")
                await self._fail_before_send(
                    job_id, device_id, "command_send_failed", f"Could not send EXECUTE_PUSH_FILES: {exc}"
                )
                return

            if protocol is ProtocolMode.LEGACY:
                try:
                    snapshot = await self.store.transition_device(
                        job_id,
                        device_id,
                        expected={DeviceState.DISPATCHING},
                        target=DeviceState.DOWNLOADING,
                        fields={"accepted_at": now_ms(), "accept_deadline": None},
                    )
                    await self.publish(snapshot)
                except StoreConflict:
                    return
            else:
                assert accept_future is not None
                try:
                    outcome, payload = await asyncio.wait_for(accept_future, self.accept_timeout)
                except asyncio.TimeoutError:
                    deadline = now_ms() + int(self.accept_reconciliation_timeout * 1000)
                    try:
                        snapshot = await self.store.mark_reconciling(
                            job_id,
                            device_id,
                            expected={DeviceState.DISPATCHING},
                            reason="command_accept_timeout",
                            deadline=deadline,
                        )
                        await self.publish(snapshot)
                        await self._send_reconcile(session, snapshot, device_id)
                    except (StoreConflict, ConnectionError):
                        pass
                else:
                    if outcome == "accepted":
                        try:
                            snapshot = await self.store.transition_device(
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
                            await self.publish(snapshot)
                        except StoreConflict:
                            pass
                    elif outcome == "busy":
                        self.transfer_registry.release_exact(key, "device_busy")
                        try:
                            snapshot = await self.store.transition_device(
                                job_id,
                                device_id,
                                expected={DeviceState.DISPATCHING},
                                target=DeviceState.QUEUED,
                                fields={
                                    "queue_reason": "same_device_job",
                                    "accept_deadline": None,
                                },
                            )
                            await self.publish(snapshot)
                        except StoreConflict:
                            pass
                        self.wake()
                        return
                    else:
                        self.transfer_registry.release_exact(key, "rejected")
                        reason = payload.get("reason") or "command_rejected"
                        await self._fail_before_send(job_id, device_id, reason, reason)
                        return

            try:
                await asyncio.wait_for(transfer_future, self.transfer_timeout)
            except asyncio.TimeoutError:
                # Resource timeout is not a terminal outcome.  Recover the global slot,
                # then reconcile the accepted/possibly-running device execution.
                deadline = now_ms() + int(self.reconciliation_timeout * 1000)
                active = await self.store.active_assignment_for_device(device_id)
                if active and active["job_id"] == job_id:
                    current = DeviceState(active["state"])
                    if current in {
                        DeviceState.DISPATCHING,
                        DeviceState.DOWNLOADING,
                        DeviceState.VALIDATING,
                        DeviceState.APPLYING,
                    }:
                        try:
                            snapshot = await self.store.mark_reconciling(
                                job_id,
                                device_id,
                                expected={current},
                                reason="transfer_timeout",
                                deadline=deadline,
                            )
                            await self.publish(snapshot)
                        except StoreConflict:
                            pass
            finally:
                self.transfer_registry.remove_if_same(key, transfer_future)
                self._accept_waiters.pop((job_id, device_id, attempt), None)

    async def _fail_before_send(self, job_id: str, device_id: str, code: str, detail: str) -> None:
        active = await self.store.active_assignment_for_device(device_id)
        if not active or active["job_id"] != job_id:
            return
        current = DeviceState(active["state"])
        if current not in {DeviceState.WAITING_TRANSFER, DeviceState.DISPATCHING}:
            return
        try:
            snapshot = await self.store.transition_device(
                job_id,
                device_id,
                expected={current},
                target=DeviceState.FAILED,
                fields={"failure_code": code, "failure_detail": detail, "accept_deadline": None},
            )
            await self.publish(snapshot)
        finally:
            self.wake()

    @staticmethod
    def _command(snapshot: dict[str, Any], device_id: str, protocol: ProtocolMode) -> dict[str, Any]:
        artifact = snapshot["artifact"]
        assert artifact is not None
        common = {
            "type": "EXECUTE_PUSH_FILES",
            "bundle_url": artifact["url"],
            "bundle_filename": artifact["display_filename"],
            "dest_path": snapshot["dest_path"],
            "delete_extras": snapshot["mode"] == "sync",
        }
        if protocol is ProtocolMode.JOB_V1:
            common.update(
                {
                    "job_id": snapshot["job_id"],
                    "attempt": snapshot["devices"][device_id]["attempt"],
                    "revision": snapshot["revision"],
                    "artifact_id": artifact["artifact_id"],
                    "artifact_url": artifact["url"],
                    "artifact_size": artifact["byte_size"],
                    "artifact_sha256": artifact["sha256"],
                }
            )
        return common

    async def _send_reconcile(
        self, session: LiveSession, snapshot: dict[str, Any], device_id: str
    ) -> None:
        artifact = snapshot.get("artifact") or {}
        payload = {
            "type": "PUSH_RECONCILE_REQUEST",
            "jobs": [
                {
                    "job_id": snapshot["job_id"],
                    "attempt": snapshot["devices"][device_id]["attempt"],
                    "artifact_id": artifact.get("artifact_id"),
                }
            ],
        }
        async with session.send_lock:
            if self.sessions().get(device_id) is not session:
                raise ConnectionError("device owner changed before reconcile request")
            await asyncio.wait_for(
                session.ws.send_str(json.dumps(payload, separators=(",", ":"))), self.send_timeout
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
