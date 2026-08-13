"""High-level transactional invariants for durable Push/Sync jobs.

The SQLite schema and primitive lifecycle operations remain in ``push_job_store``.
This manager owns operations that span queue ordering, visible fences, idempotent
HTTP handshakes, and reconciliation policy.  Every operation still executes on
the store's dedicated DB worker and returns exact-revision snapshots built before
commit, matching the Issue #91 design.
"""

from __future__ import annotations

import json
import sqlite3
import uuid
from collections.abc import Iterable, Mapping
from pathlib import Path
from typing import Any

from .push_job_store import PushJobStore, StoreConflict, StoreNotFound, now_ms
from .push_jobs import (
    DeviceState,
    JobState,
    ProtocolMode,
    TERMINAL_DEVICE_STATES,
    canonicalize_create_request,
    validate_device_transition,
)


NON_TERMINAL_JOB_VALUES = (
    JobState.CREATED.value,
    JobState.UPLOADING.value,
    JobState.PACKAGING.value,
    JobState.READY.value,
    JobState.RUNNING.value,
    JobState.RECONCILING.value,
)


class PushJobManager:
    """Policy layer over :class:`PushJobStore`.

    ``PushJobStore`` remains the only SQLite owner.  The manager deliberately uses
    the store's serialized worker for compound operations instead of performing a
    read and a later mutation in separate awaits.
    """

    def __init__(self, store: PushJobStore) -> None:
        self.store = store
        self.verify_schema_sync()

    def __getattr__(self, name: str) -> Any:
        return getattr(self.store, name)

    @staticmethod
    def canonical_opaque_job_identity(active: Mapping[str, Any]) -> str | None:
        """Return a bounded exact job-v1 identity suitable for a persistent fence."""

        job_id = active.get("job_id")
        attempt = active.get("attempt")
        if (
            not isinstance(job_id, str)
            or isinstance(attempt, bool)
            or attempt != 1
        ):
            return None
        try:
            parsed_job = uuid.UUID(job_id)
        except (ValueError, AttributeError):
            return None
        if parsed_job.version != 4:
            return None

        artifact_id = active.get("artifact_id")
        canonical_artifact: str | None = None
        if artifact_id is not None:
            if not isinstance(artifact_id, str):
                return None
            try:
                parsed_artifact = uuid.UUID(artifact_id)
            except (ValueError, AttributeError):
                return None
            if parsed_artifact.version != 4:
                return None
            canonical_artifact = str(parsed_artifact)

        payload: dict[str, Any] = {
            "job_id": str(parsed_job),
            "attempt": 1,
        }
        if canonical_artifact is not None:
            payload["artifact_id"] = canonical_artifact
        return json.dumps(payload, sort_keys=True, separators=(",", ":"))

    @classmethod
    def opaque_identity_for_active(cls, active: Mapping[str, Any]) -> str:
        canonical = cls.canonical_opaque_job_identity(active)
        if canonical is not None:
            return canonical
        # Malformed/legacy ambiguity remains fenced and can only be cleared by safe
        # process-replacement evidence. Determinism prevents duplicate revision churn.
        return '{"token":"unknown-active-job"}'

    @classmethod
    def parse_opaque_job_identity(cls, value: str | None) -> dict[str, Any] | None:
        if not isinstance(value, str) or not value or len(value) > 2048:
            return None
        try:
            decoded = json.loads(value)
        except (TypeError, json.JSONDecodeError):
            return None
        if not isinstance(decoded, Mapping):
            return None
        canonical = cls.canonical_opaque_job_identity(decoded)
        if canonical is None:
            return None
        return json.loads(canonical)

    def verify_schema_sync(self) -> None:
        def op(conn: sqlite3.Connection) -> None:
            row = conn.execute(
                "SELECT value FROM server_metadata WHERE key='schema_version'"
            ).fetchone()
            if row is None or int(row["value"]) != self.store.SCHEMA_VERSION:
                actual = None if row is None else row["value"]
                raise RuntimeError(
                    f"unsupported push job schema version {actual!r}; "
                    f"expected {self.store.SCHEMA_VERSION}"
                )

        self.store._call_sync(op)

    async def find_idempotent_job(
        self, client_request_id: str, request_fingerprint: str
    ) -> dict[str, Any] | None:
        """Return an existing create result before live-target preflight.

        Idempotent replay must not turn into a 422 merely because a previously
        accepted target disconnected after the original response was lost.
        """

        def op(conn: sqlite3.Connection) -> dict[str, Any] | None:
            row = conn.execute(
                "SELECT job_id, request_fingerprint FROM push_jobs "
                "WHERE client_request_id=?",
                (client_request_id,),
            ).fetchone()
            if row is None:
                return None
            if row["request_fingerprint"] != request_fingerprint:
                raise StoreConflict(
                    "client_request_id is already associated with a different request"
                )
            return self.store._snapshot(conn, row["job_id"])

        return await self.store._call(op)

    async def list_snapshots(
        self, recent_limit: int, terminal_cutoff_ms: int
    ) -> list[dict[str, Any]]:
        """Return all non-terminal, bounded recent terminal, and fence owners."""

        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:
            ids: list[str] = []
            seen: set[str] = set()

            def add(rows: Iterable[sqlite3.Row]) -> None:
                for row in rows:
                    value = row["job_id"]
                    if value not in seen:
                        seen.add(value)
                        ids.append(value)

            placeholders = ",".join("?" for _ in NON_TERMINAL_JOB_VALUES)
            add(
                conn.execute(
                    f"SELECT job_id FROM push_jobs WHERE state IN ({placeholders}) "
                    "ORDER BY updated_at DESC",
                    NON_TERMINAL_JOB_VALUES,
                ).fetchall()
            )
            add(
                conn.execute(
                    f"SELECT job_id FROM push_jobs WHERE state NOT IN ({placeholders}) "
                    "AND updated_at>=? ORDER BY updated_at DESC LIMIT ?",
                    (*NON_TERMINAL_JOB_VALUES, terminal_cutoff_ms, max(0, recent_limit)),
                ).fetchall()
            )
            # A persistent fence can outlive the normal 100-job/30-day display bound.
            add(
                conn.execute(
                    "SELECT DISTINCT blocking_job_id AS job_id FROM push_device_fences "
                    "WHERE blocking_job_id IS NOT NULL"
                ).fetchall()
            )
            # Opaque fences have no local blocking-job FK. Keep the latest job that
            # targets the fenced device visible beyond the usual terminal bound so the
            # console still exposes the fence and its safe reconcile action.
            add(
                conn.execute(
                    """
                    SELECT j.job_id
                    FROM push_device_fences f
                    JOIN push_job_devices d ON d.device_id=f.device_id
                    JOIN push_jobs j ON j.job_id=d.job_id
                    WHERE f.blocking_job_id IS NULL
                      AND j.job_id=(
                          SELECT j2.job_id
                          FROM push_job_devices d2
                          JOIN push_jobs j2 ON j2.job_id=d2.job_id
                          WHERE d2.device_id=f.device_id
                          ORDER BY j2.updated_at DESC, j2.job_id DESC
                          LIMIT 1
                      )
                    """
                ).fetchall()
            )
            snapshots = [self.store._snapshot(conn, job_id) for job_id in ids]
            snapshots.sort(key=lambda item: item["updated_at"], reverse=True)
            return snapshots

        return await self.store._call(op)


    async def expired_reconciliations(self, timestamp: int) -> list[dict[str, Any]]:
        """Return identity plus the exact deadline a timeout callback must recheck."""

        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:
            rows = conn.execute(
                """
                SELECT job_id, device_id, attempt, protocol_mode,
                       reconciliation_deadline
                FROM push_job_devices
                WHERE state=? AND reconciliation_deadline IS NOT NULL
                  AND reconciliation_deadline<=?
                ORDER BY reconciliation_deadline, enqueue_seq
                """,
                (DeviceState.RECONCILING.value, timestamp),
            ).fetchall()
            return [dict(row) for row in rows]

        return await self.store._call(op)

    async def expired_acceptances(self, timestamp: int) -> list[dict[str, Any]]:
        """Return dispatches whose in-memory acceptance waiter may have been lost."""

        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:
            rows = conn.execute(
                """
                SELECT job_id, device_id, attempt, accept_deadline
                FROM push_job_devices
                WHERE state=? AND accepted_at IS NULL AND accept_deadline IS NOT NULL
                  AND accept_deadline<=?
                ORDER BY accept_deadline, enqueue_seq
                """,
                (DeviceState.DISPATCHING.value, timestamp),
            ).fetchall()
            return [dict(row) for row in rows]

        return await self.store._call(op)

    async def mark_acceptance_reconciling(
        self,
        job_id: str,
        device_id: str,
        *,
        expected_accept_deadline: int,
        reconciliation_deadline: int,
    ) -> tuple[bool, dict[str, Any]]:
        """Move one exact expired dispatch to reconciliation.

        The accept deadline is the durable dispatch token. Keeping and comparing it
        prevents a stale housekeeping row from changing a later replay of the same
        job/attempt.
        """

        def op(conn: sqlite3.Connection) -> tuple[bool, dict[str, Any]]:
            self.store._begin(conn)
            try:
                row = conn.execute(
                    "SELECT state, accepted_at, accept_deadline, reconciliation_reason "
                    "FROM push_job_devices WHERE job_id=? AND device_id=?",
                    (job_id, device_id),
                ).fetchone()
                if row is None:
                    raise StoreNotFound(f"{job_id}/{device_id}")
                current = DeviceState(row["state"])
                exact_deadline = row["accept_deadline"] == expected_accept_deadline
                if (
                    current is DeviceState.RECONCILING
                    and row["accepted_at"] is None
                    and exact_deadline
                    and row["reconciliation_reason"] == "command_accept_timeout"
                ):
                    snapshot = self.store._snapshot(conn, job_id)
                    self.store._commit(conn)
                    return False, snapshot
                if (
                    current is not DeviceState.DISPATCHING
                    or row["accepted_at"] is not None
                    or not exact_deadline
                ):
                    raise StoreConflict("accept deadline no longer owns dispatch")
                validate_device_transition(current, DeviceState.RECONCILING)
                timestamp = now_ms()
                conn.execute(
                    "UPDATE push_job_devices SET state=?, reconciliation_reason=?, "
                    "reconciliation_deadline=?, updated_at=? "
                    "WHERE job_id=? AND device_id=?",
                    (
                        DeviceState.RECONCILING.value,
                        "command_accept_timeout",
                        reconciliation_deadline,
                        timestamp,
                        job_id,
                        device_id,
                    ),
                )
                self.store._increment_revision(conn, job_id, timestamp)
                self.store._rederive_job(conn, job_id, timestamp)
                snapshot = self.store._snapshot(conn, job_id)
                self.store._commit(conn)
                return True, snapshot
            except BaseException:
                self.store._rollback(conn)
                raise

        return await self.store._call(op)

    async def next_created_deadline(self) -> tuple[str, int] | None:
        def op(conn: sqlite3.Connection) -> tuple[str, int] | None:
            row = conn.execute(
                "SELECT job_id, create_expires_at FROM push_jobs WHERE state=? "
                "ORDER BY create_expires_at, job_id LIMIT 1",
                (JobState.CREATED.value,),
            ).fetchone()
            return None if row is None else (row["job_id"], row["create_expires_at"])

        return await self.store._call(op)

    async def claim_next(self, online_device_ids: Iterable[str]) -> dict[str, Any] | None:
        """Atomically claim the globally oldest eligible row for a device.

        An enabled later job must never jump over an older non-terminal queued row
        whose dispatch gate is paused or whose upload has not yet completed.
        """

        online = tuple(sorted(set(online_device_ids)))
        if not online:
            return None

        def op(conn: sqlite3.Connection) -> dict[str, Any] | None:
            self.store._begin(conn)
            try:
                online_marks = ",".join("?" for _ in online)
                non_terminal_marks = ",".join("?" for _ in NON_TERMINAL_JOB_VALUES)
                row = conn.execute(
                    f"""
                    SELECT d.job_id, d.device_id, d.enqueue_seq, d.attempt,
                           d.protocol_mode
                    FROM push_job_devices d
                    JOIN push_jobs j ON j.job_id=d.job_id
                    WHERE d.state=?
                      AND d.device_id IN ({online_marks})
                      AND j.dispatch_enabled=1
                      AND j.state IN (?, ?)
                      AND NOT EXISTS (
                          SELECT 1 FROM push_device_fences f
                          WHERE f.device_id=d.device_id
                      )
                      AND NOT EXISTS (
                          SELECT 1 FROM push_job_devices active
                          WHERE active.device_id=d.device_id
                            AND active.state IN (
                                'waiting_transfer','dispatching','downloading',
                                'validating','applying','reconciling'
                            )
                      )
                      AND d.enqueue_seq=(
                          SELECT MIN(d2.enqueue_seq)
                          FROM push_job_devices d2
                          JOIN push_jobs j2 ON j2.job_id=d2.job_id
                          WHERE d2.device_id=d.device_id
                            AND d2.state=?
                            AND j2.state IN ({non_terminal_marks})
                      )
                    ORDER BY d.enqueue_seq
                    LIMIT 1
                    """,
                    (
                        DeviceState.QUEUED.value,
                        *online,
                        JobState.RUNNING.value,
                        JobState.RECONCILING.value,
                        DeviceState.QUEUED.value,
                        *NON_TERMINAL_JOB_VALUES,
                    ),
                ).fetchone()
                if row is None:
                    self.store._commit(conn)
                    return None
                timestamp = now_ms()
                updated = conn.execute(
                    "UPDATE push_job_devices SET state=?, queue_reason=NULL, updated_at=? "
                    "WHERE job_id=? AND device_id=? AND state=?",
                    (
                        DeviceState.WAITING_TRANSFER.value,
                        timestamp,
                        row["job_id"],
                        row["device_id"],
                        DeviceState.QUEUED.value,
                    ),
                )
                if updated.rowcount != 1:
                    self.store._rollback(conn)
                    return None
                self.store._increment_revision(conn, row["job_id"], timestamp)
                self.store._rederive_job(conn, row["job_id"], timestamp)
                snapshot = self.store._snapshot(conn, row["job_id"])
                assignment = conn.execute(
                    "SELECT * FROM push_job_devices WHERE job_id=? AND device_id=?",
                    (row["job_id"], row["device_id"]),
                ).fetchone()
                self.store._commit(conn)
                assert assignment is not None
                return {
                    **dict(assignment),
                    "job": snapshot,
                }
            except sqlite3.IntegrityError:
                self.store._rollback(conn)
                return None
            except BaseException:
                self.store._rollback(conn)
                raise

        return await self.store._call(op)

    async def prepare_dispatch(
        self,
        job_id: str,
        device_id: str,
        *,
        protocol_mode: ProtocolMode,
        live_capabilities: Iterable[str],
        accept_deadline: int | None,
    ) -> dict[str, Any]:
        """Commit waiter-ready dispatch metadata after the exact waiter exists."""

        capabilities_json = json.dumps(
            sorted(set(live_capabilities)), separators=(",", ":")
        )

        def op(conn: sqlite3.Connection) -> dict[str, Any]:
            self.store._begin(conn)
            try:
                row = conn.execute(
                    "SELECT state, attempt FROM push_job_devices "
                    "WHERE job_id=? AND device_id=?",
                    (job_id, device_id),
                ).fetchone()
                if row is None:
                    raise StoreNotFound(f"{job_id}/{device_id}")
                if row["attempt"] != 1:
                    raise StoreConflict("stale_attempt")
                current = DeviceState(row["state"])
                validate_device_transition(current, DeviceState.DISPATCHING)
                timestamp = now_ms()
                result = conn.execute(
                    "UPDATE push_job_devices SET state=?, protocol_mode=?, "
                    "dispatch_capability_snapshot_json=?, accept_deadline=?, "
                    "updated_at=? WHERE job_id=? AND device_id=? AND state=?",
                    (
                        DeviceState.DISPATCHING.value,
                        protocol_mode.value,
                        capabilities_json,
                        accept_deadline,
                        timestamp,
                        job_id,
                        device_id,
                        DeviceState.WAITING_TRANSFER.value,
                    ),
                )
                if result.rowcount != 1:
                    raise StoreConflict("assignment changed before dispatch")
                self.store._increment_revision(conn, job_id, timestamp)
                self.store._rederive_job(conn, job_id, timestamp)
                snapshot = self.store._snapshot(conn, job_id)
                self.store._commit(conn)
                return snapshot
            except BaseException:
                self.store._rollback(conn)
                raise

        return await self.store._call(op)

    async def assignment(self, job_id: str, device_id: str) -> dict[str, Any] | None:
        def op(conn: sqlite3.Connection) -> dict[str, Any] | None:
            row = conn.execute(
                """
                SELECT d.*, j.state AS job_state, j.dispatch_enabled, j.artifact_id,
                       j.mode, j.dest_path
                FROM push_job_devices d
                JOIN push_jobs j ON j.job_id=d.job_id
                WHERE d.job_id=? AND d.device_id=?
                """,
                (job_id, device_id),
            ).fetchone()
            return None if row is None else dict(row)

        return await self.store._call(op)

    async def fenced_assignment_for_device(self, device_id: str) -> dict[str, Any] | None:
        def op(conn: sqlite3.Connection) -> dict[str, Any] | None:
            row = conn.execute(
                """
                SELECT f.*, d.state, j.artifact_id
                FROM push_device_fences f
                LEFT JOIN push_job_devices d
                  ON d.job_id=f.blocking_job_id AND d.device_id=f.device_id
                LEFT JOIN push_jobs j ON j.job_id=f.blocking_job_id
                WHERE f.device_id=?
                """,
                (device_id,),
            ).fetchone()
            return None if row is None else dict(row)

        return await self.store._call(op)

    async def opaque_reconcile_target(self, device_id: str) -> dict[str, Any] | None:
        def op(conn: sqlite3.Connection) -> dict[str, Any] | None:
            row = conn.execute(
                "SELECT blocking_opaque_identity, protocol_mode "
                "FROM push_device_fences WHERE device_id=? AND blocking_job_id IS NULL",
                (device_id,),
            ).fetchone()
            if row is None or row["protocol_mode"] != ProtocolMode.JOB_V1.value:
                return None
            return self.parse_opaque_job_identity(row["blocking_opaque_identity"])

        return await self.store._call(op)

    @staticmethod
    def _visible_job_ids(
        store: PushJobStore,
        conn: sqlite3.Connection,
        device_id: str,
        blocking_job_id: str | None = None,
    ) -> list[str]:
        ids = store._fence_visible_job_ids(conn, device_id)
        if blocking_job_id and blocking_job_id not in ids:
            if conn.execute(
                "SELECT 1 FROM push_jobs WHERE job_id=?", (blocking_job_id,)
            ).fetchone():
                ids.append(blocking_job_id)
        fence = conn.execute(
            "SELECT blocking_job_id FROM push_device_fences WHERE device_id=?",
            (device_id,),
        ).fetchone()
        if fence is not None and fence["blocking_job_id"] is None:
            latest = conn.execute(
                """
                SELECT j.job_id
                FROM push_job_devices d
                JOIN push_jobs j ON j.job_id=d.job_id
                WHERE d.device_id=?
                ORDER BY j.updated_at DESC, j.job_id DESC
                LIMIT 1
                """,
                (device_id,),
            ).fetchone()
            if latest is not None and latest["job_id"] not in ids:
                ids.append(latest["job_id"])
        return ids

    async def mark_unconfirmed(
        self,
        job_id: str,
        device_id: str,
        process_instance_id: str | None,
        detail: str,
        *,
        expected_deadline: int | None = None,
        observed_now: int | None = None,
    ) -> list[dict[str, Any]]:
        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:
            self.store._begin(conn)
            try:
                row = conn.execute(
                    "SELECT state, attempt, protocol_mode, reconciliation_deadline "
                    "FROM push_job_devices WHERE job_id=? AND device_id=?",
                    (job_id, device_id),
                ).fetchone()
                if row is None:
                    raise StoreNotFound(f"{job_id}/{device_id}")
                if row["attempt"] != 1:
                    raise StoreConflict("stale_attempt")
                if DeviceState(row["state"]) is not DeviceState.RECONCILING:
                    raise StoreConflict("assignment is not reconciling")
                timestamp = now_ms() if observed_now is None else observed_now
                if expected_deadline is not None and (
                    row["reconciliation_deadline"] != expected_deadline
                    or timestamp < expected_deadline
                ):
                    raise StoreConflict("stale reconciliation deadline")
                conn.execute(
                    "UPDATE push_job_devices SET state=?, terminal_at=?, updated_at=?, "
                    "failure_code=?, failure_detail=?, reconciliation_reason=NULL, "
                    "reconciliation_deadline=NULL, accept_deadline=NULL "
                    "WHERE job_id=? AND device_id=?",
                    (
                        DeviceState.UNCONFIRMED.value,
                        timestamp,
                        timestamp,
                        "reconciliation_timeout",
                        detail[:2000],
                        job_id,
                        device_id,
                    ),
                )
                conn.execute(
                    """
                    INSERT INTO push_device_fences(
                        device_id, blocking_job_id, blocking_opaque_identity,
                        blocking_attempt, protocol_mode,
                        blocking_process_instance_id, reason, created_at, updated_at
                    ) VALUES(?, ?, NULL, 1, ?, ?, ?, ?, ?)
                    ON CONFLICT(device_id) DO UPDATE SET
                        blocking_job_id=excluded.blocking_job_id,
                        blocking_opaque_identity=NULL,
                        blocking_attempt=1,
                        protocol_mode=excluded.protocol_mode,
                        blocking_process_instance_id=excluded.blocking_process_instance_id,
                        reason=excluded.reason,
                        updated_at=excluded.updated_at
                    """,
                    (
                        device_id,
                        job_id,
                        row["protocol_mode"],
                        process_instance_id,
                        detail[:2000],
                        timestamp,
                        timestamp,
                    ),
                )
                ids = self._visible_job_ids(self.store, conn, device_id, job_id)
                for affected in ids:
                    self.store._increment_revision(conn, affected, timestamp)
                    self.store._rederive_job(conn, affected, timestamp)
                snapshots = [self.store._snapshot(conn, affected) for affected in ids]
                self.store._commit(conn)
                return snapshots
            except BaseException:
                self.store._rollback(conn)
                raise

        return await self.store._call(op)

    async def add_opaque_fence(
        self,
        device_id: str,
        opaque_identity: str,
        protocol_mode: ProtocolMode,
        process_instance_id: str | None,
        reason: str,
    ) -> list[dict[str, Any]]:
        if not opaque_identity or len(opaque_identity) > 2048:
            raise StoreConflict("opaque fence identity is invalid")

        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:
            self.store._begin(conn)
            try:
                existing = conn.execute(
                    "SELECT * FROM push_device_fences WHERE device_id=?", (device_id,)
                ).fetchone()
                canonical = (
                    None,
                    opaque_identity,
                    None,
                    protocol_mode.value,
                    process_instance_id,
                    reason[:2000],
                )
                if existing is not None:
                    current = (
                        existing["blocking_job_id"],
                        existing["blocking_opaque_identity"],
                        existing["blocking_attempt"],
                        existing["protocol_mode"],
                        existing["blocking_process_instance_id"],
                        existing["reason"],
                    )
                    if current == canonical:
                        self.store._commit(conn)
                        return []
                timestamp = now_ms()
                conn.execute(
                    """
                    INSERT INTO push_device_fences(
                        device_id, blocking_job_id, blocking_opaque_identity,
                        blocking_attempt, protocol_mode,
                        blocking_process_instance_id, reason, created_at, updated_at
                    ) VALUES(?, NULL, ?, NULL, ?, ?, ?, ?, ?)
                    ON CONFLICT(device_id) DO UPDATE SET
                        blocking_job_id=NULL,
                        blocking_opaque_identity=excluded.blocking_opaque_identity,
                        blocking_attempt=NULL,
                        protocol_mode=excluded.protocol_mode,
                        blocking_process_instance_id=excluded.blocking_process_instance_id,
                        reason=excluded.reason,
                        updated_at=excluded.updated_at
                    """,
                    (
                        device_id,
                        opaque_identity,
                        protocol_mode.value,
                        process_instance_id,
                        reason[:2000],
                        timestamp,
                        timestamp,
                    ),
                )
                ids = self._visible_job_ids(self.store, conn, device_id)
                for affected in ids:
                    self.store._increment_revision(conn, affected, timestamp)
                snapshots = [self.store._snapshot(conn, affected) for affected in ids]
                self.store._commit(conn)
                return snapshots
            except BaseException:
                self.store._rollback(conn)
                raise

        return await self.store._call(op)

    async def clear_fence_on_process_replacement(
        self,
        device_id: str,
        process_instance_id: str | None,
        has_job_v1: bool,
    ) -> list[dict[str, Any]]:
        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:
            self.store._begin(conn)
            try:
                fence = conn.execute(
                    "SELECT * FROM push_device_fences WHERE device_id=?", (device_id,)
                ).fetchone()
                if fence is None or not has_job_v1 or not process_instance_id:
                    self.store._commit(conn)
                    return []
                old_process = fence["blocking_process_instance_id"]
                protocol = ProtocolMode(fence["protocol_mode"])
                # A different recorded process proves replacement.  For a legacy fence
                # no process UUID existed; a new job-v1 process is itself the safe
                # migration evidence required by §19.3.
                replaced = (
                    old_process is not None and old_process != process_instance_id
                ) or (old_process is None and protocol is ProtocolMode.LEGACY)
                if not replaced:
                    self.store._commit(conn)
                    return []
                blocking_job_id = fence["blocking_job_id"]
                ids = self._visible_job_ids(
                    self.store, conn, device_id, blocking_job_id
                )
                conn.execute("DELETE FROM push_device_fences WHERE device_id=?", (device_id,))
                timestamp = now_ms()
                for affected in ids:
                    self.store._increment_revision(conn, affected, timestamp)
                snapshots = [self.store._snapshot(conn, affected) for affected in ids]
                self.store._commit(conn)
                return snapshots
            except BaseException:
                self.store._rollback(conn)
                raise

        return await self.store._call(op)


    async def clear_matching_fence(
        self, job_id: str, device_id: str, attempt: int
    ) -> list[dict[str, Any]]:
        """Clear a fence only for exact absent/interrupted evidence.

        The terminal assignment remains unchanged; every job whose snapshot exposes
        this device fence receives a revision in the same transaction.
        """

        if attempt != 1:
            return []

        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:
            self.store._begin(conn)
            try:
                fence = conn.execute(
                    "SELECT * FROM push_device_fences WHERE device_id=?",
                    (device_id,),
                ).fetchone()
                if (
                    fence is None
                    or fence["blocking_job_id"] != job_id
                    or fence["blocking_attempt"] != attempt
                ):
                    self.store._commit(conn)
                    return []
                conn.execute("DELETE FROM push_device_fences WHERE device_id=?", (device_id,))
                timestamp = now_ms()
                ids = self._visible_job_ids(self.store, conn, device_id, job_id)
                for affected in ids:
                    self.store._increment_revision(conn, affected, timestamp)
                snapshots = [self.store._snapshot(conn, affected) for affected in ids]
                self.store._commit(conn)
                return snapshots
            except BaseException:
                self.store._rollback(conn)
                raise

        return await self.store._call(op)

    async def clear_matching_opaque_fence(
        self,
        device_id: str,
        job_id: str,
        attempt: int,
    ) -> tuple[bool, list[dict[str, Any]]]:
        """Clear only a canonical opaque job-v1 fence matching exact evidence."""

        expected = self.canonical_opaque_job_identity(
            {"job_id": job_id, "attempt": attempt}
        )
        if expected is None:
            return False, []
        expected_identity = json.loads(expected)

        def op(conn: sqlite3.Connection) -> tuple[bool, list[dict[str, Any]]]:
            self.store._begin(conn)
            try:
                fence = conn.execute(
                    "SELECT * FROM push_device_fences WHERE device_id=?",
                    (device_id,),
                ).fetchone()
                if (
                    fence is None
                    or fence["blocking_job_id"] is not None
                    or fence["protocol_mode"] != ProtocolMode.JOB_V1.value
                ):
                    self.store._commit(conn)
                    return False, []
                stored = self.parse_opaque_job_identity(
                    fence["blocking_opaque_identity"]
                )
                if (
                    stored is None
                    or stored["job_id"] != expected_identity["job_id"]
                    or stored["attempt"] != expected_identity["attempt"]
                ):
                    self.store._commit(conn)
                    return False, []
                # Capture every snapshot that currently exposes the fence before
                # deleting it; otherwise a terminal opaque-fence display row could be
                # omitted from the clear transaction's revision set.
                ids = self._visible_job_ids(self.store, conn, device_id)
                conn.execute(
                    "DELETE FROM push_device_fences WHERE device_id=?", (device_id,)
                )
                timestamp = now_ms()
                for affected in ids:
                    self.store._increment_revision(conn, affected, timestamp)
                snapshots = [
                    self.store._snapshot(conn, affected) for affected in ids
                ]
                self.store._commit(conn)
                return True, snapshots
            except BaseException:
                self.store._rollback(conn)
                raise

        return await self.store._call(op)

    async def settle_late_fenced_result(
        self, job_id: str, device_id: str, attempt: int
    ) -> tuple[bool, list[dict[str, Any]]]:
        if attempt != 1:
            return False, []

        def op(conn: sqlite3.Connection) -> tuple[bool, list[dict[str, Any]]]:
            self.store._begin(conn)
            try:
                assignment = conn.execute(
                    "SELECT state FROM push_job_devices WHERE job_id=? AND device_id=? "
                    "AND attempt=1",
                    (job_id, device_id),
                ).fetchone()
                fence = conn.execute(
                    "SELECT * FROM push_device_fences WHERE device_id=?",
                    (device_id,),
                ).fetchone()
                if (
                    assignment is None
                    or assignment["state"] != DeviceState.UNCONFIRMED.value
                ):
                    self.store._commit(conn)
                    return False, []
                if fence is None:
                    # Exact identity is already terminal and safe evidence cleared
                    # the fence earlier. ACK without manufacturing a new revision.
                    snapshot = self.store._snapshot(conn, job_id)
                    self.store._commit(conn)
                    return True, [snapshot]
                if (
                    fence["blocking_job_id"] != job_id
                    or fence["blocking_attempt"] != 1
                ):
                    self.store._commit(conn)
                    return False, []
                conn.execute("DELETE FROM push_device_fences WHERE device_id=?", (device_id,))
                timestamp = now_ms()
                ids = self._visible_job_ids(self.store, conn, device_id, job_id)
                for affected in ids:
                    self.store._increment_revision(conn, affected, timestamp)
                snapshots = [self.store._snapshot(conn, affected) for affected in ids]
                self.store._commit(conn)
                return True, snapshots
            except BaseException:
                self.store._rollback(conn)
                raise

        return await self.store._call(op)

    async def reconcile_report(
        self,
        job_id: str,
        device_id: str,
        attempt: int,
        status: str,
        phase: str | None,
        detail: str | None,
    ) -> tuple[str, list[dict[str, Any]]]:
        """Apply exact report, including the one allowed pre-accept replay."""

        if attempt != 1:
            raise StoreConflict("stale_attempt")

        def op(conn: sqlite3.Connection) -> tuple[str, list[dict[str, Any]]]:
            self.store._begin(conn)
            try:
                row = conn.execute(
                    "SELECT d.*, j.dispatch_enabled FROM push_job_devices d "
                    "JOIN push_jobs j ON j.job_id=d.job_id "
                    "WHERE d.job_id=? AND d.device_id=?",
                    (job_id, device_id),
                ).fetchone()
                if row is None:
                    raise StoreNotFound(f"{job_id}/{device_id}")
                if row["attempt"] != 1:
                    raise StoreConflict("stale_attempt")
                current = DeviceState(row["state"])
                if current is DeviceState.UNCONFIRMED and status in {
                    "absent",
                    "interrupted",
                }:
                    fence = conn.execute(
                        "SELECT * FROM push_device_fences WHERE device_id=?",
                        (device_id,),
                    ).fetchone()
                    if (
                        fence is None
                        or fence["blocking_job_id"] != job_id
                        or fence["blocking_attempt"] != attempt
                    ):
                        raise StoreConflict("exact reconciliation fence is absent")
                    conn.execute(
                        "DELETE FROM push_device_fences WHERE device_id=?",
                        (device_id,),
                    )
                    timestamp = now_ms()
                    ids = self._visible_job_ids(self.store, conn, device_id, job_id)
                    for affected in ids:
                        self.store._increment_revision(conn, affected, timestamp)
                    snapshots = [
                        self.store._snapshot(conn, affected) for affected in ids
                    ]
                    self.store._commit(conn)
                    return "fence_cleared", snapshots
                if current is not DeviceState.RECONCILING:
                    raise StoreConflict("assignment is not reconciling")
                timestamp = now_ms()
                outcome: str
                if status == "active":
                    phase_map = {
                        "downloading": DeviceState.DOWNLOADING,
                        "validating": DeviceState.VALIDATING,
                        "applying": DeviceState.APPLYING,
                    }
                    target = phase_map.get(phase or "")
                    if target is None:
                        raise StoreConflict("invalid active phase")
                    validate_device_transition(current, target)
                    conn.execute(
                        "UPDATE push_job_devices SET state=?, accepted_at=COALESCE(accepted_at, ?), "
                        "accept_deadline=NULL, reconciliation_reason=NULL, "
                        "reconciliation_deadline=NULL, updated_at=? "
                        "WHERE job_id=? AND device_id=?",
                        (target.value, timestamp, timestamp, job_id, device_id),
                    )
                    outcome = "active"
                elif status in {"absent", "interrupted"}:
                    replayable = (
                        status == "absent"
                        and row["accepted_at"] is None
                        and row["reconciliation_reason"]
                        in {"command_accept_timeout", "disconnect_before_accept"}
                        and row["accept_replay_count"] < 1
                        and bool(row["dispatch_enabled"])
                    )
                    if replayable:
                        validate_device_transition(current, DeviceState.QUEUED)
                        conn.execute(
                            "UPDATE push_job_devices SET state=?, queue_reason=?, "
                            "accept_replay_count=accept_replay_count+1, accept_deadline=NULL, "
                            "reconciliation_reason=NULL, reconciliation_deadline=NULL, "
                            "failure_code=NULL, failure_detail=NULL, updated_at=? "
                            "WHERE job_id=? AND device_id=?",
                            (
                                DeviceState.QUEUED.value,
                                "command_accept_replay",
                                timestamp,
                                job_id,
                                device_id,
                            ),
                        )
                        outcome = "requeued"
                    else:
                        validate_device_transition(current, DeviceState.INTERRUPTED)
                        conn.execute(
                            "UPDATE push_job_devices SET state=?, terminal_at=?, "
                            "failure_code=?, failure_detail=?, accept_deadline=NULL, "
                            "reconciliation_reason=NULL, reconciliation_deadline=NULL, "
                            "updated_at=? WHERE job_id=? AND device_id=?",
                            (
                                DeviceState.INTERRUPTED.value,
                                timestamp,
                                "client_restarted"
                                if status == "interrupted"
                                else "client_state_absent",
                                (detail or f"client reported {status}")[:2000],
                                timestamp,
                                job_id,
                                device_id,
                            ),
                        )
                        outcome = "interrupted"
                else:
                    raise StoreConflict("invalid reconciliation status")
                self.store._increment_revision(conn, job_id, timestamp)
                self.store._rederive_job(conn, job_id, timestamp)
                snapshot = self.store._snapshot(conn, job_id)
                self.store._commit(conn)
                return outcome, [snapshot]
            except BaseException:
                self.store._rollback(conn)
                raise

        return await self.store._call(op)


    def orphan_artifacts_sync(self, artifact_root: Path) -> list[str]:
        """Return canonical publication files that no artifact row owns.

        Only UUIDv4 ``.zip`` names produced by this lifecycle are considered. Unknown
        files remain for the general stale-file policy rather than being guessed at.
        """

        referenced = self.store._call_sync(
            lambda conn: {
                row["storage_name"]
                for row in conn.execute("SELECT storage_name FROM push_artifacts")
            }
        )
        result: list[str] = []
        for path in artifact_root.iterdir() if artifact_root.exists() else ():
            if not path.is_file() or path.suffix.lower() != ".zip":
                continue
            try:
                parsed = uuid.UUID(path.stem)
            except ValueError:
                continue
            if parsed.version == 4 and path.name not in referenced:
                result.append(path.name)
        result.sort()
        return result

    def reconcile_missing_artifacts_sync(
        self, artifact_root: Path
    ) -> list[dict[str, Any]]:
        """Fail work that cannot be served without silently substituting bytes."""

        root = artifact_root.resolve()

        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:
            rows = conn.execute(
                """
                SELECT j.job_id, j.state, a.storage_name
                FROM push_jobs j
                JOIN push_artifacts a ON a.artifact_id=j.artifact_id
                WHERE j.state IN ('ready','running','reconciling')
                """
            ).fetchall()
            missing = [
                row
                for row in rows
                if not (root / row["storage_name"]).is_file()
            ]
            if not missing:
                return []
            self.store._begin(conn)
            try:
                timestamp = now_ms()
                touched: list[str] = []
                for row in missing:
                    job_id = row["job_id"]
                    state = JobState(row["state"])
                    if state is JobState.READY:
                        conn.execute(
                            "UPDATE push_jobs SET state=?, dispatch_enabled=0, "
                            "failure_code='artifact_missing', failure_detail=?, "
                            "terminal_at=?, revision=revision+1, updated_at=? "
                            "WHERE job_id=?",
                            (
                                JobState.FAILED.value,
                                f"referenced immutable artifact {row['storage_name']} is missing",
                                timestamp,
                                timestamp,
                                job_id,
                            ),
                        )
                    else:
                        # Rows that never reached local validation cannot proceed. A
                        # reconciling validating/applying worker may already own the
                        # bytes, so leave it reconciling rather than inventing absence.
                        conn.execute(
                            """
                            UPDATE push_job_devices
                            SET state=?, terminal_at=?, failure_code='artifact_missing',
                                failure_detail=?, updated_at=?
                            WHERE job_id=? AND state IN (
                                'queued','waiting_transfer','dispatching','downloading'
                            )
                            """,
                            (
                                DeviceState.FAILED.value,
                                timestamp,
                                f"referenced immutable artifact {row['storage_name']} is missing",
                                timestamp,
                                job_id,
                            ),
                        )
                        self.store._increment_revision(conn, job_id, timestamp)
                        self.store._rederive_job(conn, job_id, timestamp)
                    touched.append(job_id)
                snapshots = [self.store._snapshot(conn, job_id) for job_id in touched]
                self.store._commit(conn)
                return snapshots
            except BaseException:
                self.store._rollback(conn)
                raise

        return self.store._call_sync(op)
