"""SQLite-backed canonical push job store.

All SQLite access is serialized on one dedicated worker thread.  Public async
methods await concurrent futures, keeping aiohttp's event loop and WebSocket
heartbeats independent from WAL/fsync/busy waits.
"""

from __future__ import annotations

import asyncio
import concurrent.futures
import json
import queue
import sqlite3
import threading
import time
import uuid
from collections.abc import Callable, Iterable, Mapping
from pathlib import Path
from typing import Any, TypeVar

from .push_jobs import (
    ACTIVE_DEVICE_STATES,
    DeviceState,
    JobState,
    ProtocolMode,
    TERMINAL_DEVICE_STATES,
    aggregate_device_states,
    derive_dispatched_job_state,
    is_terminal_job_state,
    validate_device_transition,
    validate_job_transition,
)

T = TypeVar("T")


def now_ms() -> int:
    return time.time_ns() // 1_000_000


class StoreConflict(RuntimeError):
    pass


class StoreNotFound(KeyError):
    pass


class _DbWorker:
    def __init__(self, path: Path) -> None:
        self._path = path
        self._queue: queue.Queue[tuple[Callable[[sqlite3.Connection], Any] | None, concurrent.futures.Future[Any] | None]] = queue.Queue()
        self._thread = threading.Thread(target=self._run, name="push-job-db", daemon=True)
        self._ready = threading.Event()
        self._startup_error: BaseException | None = None
        self._thread.start()
        self._ready.wait()
        if self._startup_error is not None:
            raise RuntimeError("failed to initialize push job database") from self._startup_error

    def _run(self) -> None:
        conn: sqlite3.Connection | None = None
        try:
            self._path.parent.mkdir(parents=True, exist_ok=True)
            conn = sqlite3.connect(self._path, isolation_level=None, timeout=5.0)
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA journal_mode = WAL")
            conn.execute("PRAGMA synchronous = FULL")
            conn.execute("PRAGMA foreign_keys = ON")
            conn.execute("PRAGMA busy_timeout = 5000")
        except BaseException as exc:  # pragma: no cover - initialization failure path
            self._startup_error = exc
            self._ready.set()
            return
        self._ready.set()
        while True:
            fn, future = self._queue.get()
            if fn is None:
                break
            assert future is not None
            if future.cancelled():
                continue
            try:
                future.set_result(fn(conn))
            except BaseException as exc:
                future.set_exception(exc)
        conn.close()

    def submit(self, fn: Callable[[sqlite3.Connection], T]) -> concurrent.futures.Future[T]:
        future: concurrent.futures.Future[T] = concurrent.futures.Future()
        self._queue.put((fn, future))
        return future

    def close(self) -> None:
        self._queue.put((None, None))
        self._thread.join(timeout=5)


class PushJobStore:
    SCHEMA_VERSION = 1

    def __init__(self, path: Path) -> None:
        self.path = path
        self._worker = _DbWorker(path)
        self._call_sync(self._initialize)

    def close(self) -> None:
        self._worker.close()

    def _call_sync(self, fn: Callable[[sqlite3.Connection], T]) -> T:
        return self._worker.submit(fn).result()

    async def _call(self, fn: Callable[[sqlite3.Connection], T]) -> T:
        return await asyncio.wrap_future(self._worker.submit(fn))

    @staticmethod
    def _initialize(conn: sqlite3.Connection) -> None:
        conn.executescript(
            """
            BEGIN IMMEDIATE;
            CREATE TABLE IF NOT EXISTS server_metadata (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS push_artifacts (
                artifact_id TEXT PRIMARY KEY,
                storage_name TEXT NOT NULL UNIQUE,
                display_filename TEXT NOT NULL,
                byte_size INTEGER NOT NULL,
                sha256 TEXT NOT NULL,
                entry_count INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                retention_state TEXT NOT NULL DEFAULT 'retained'
            );
            CREATE TABLE IF NOT EXISTS push_jobs (
                job_id TEXT PRIMARY KEY,
                client_request_id TEXT NOT NULL UNIQUE,
                request_fingerprint TEXT NOT NULL,
                revision INTEGER NOT NULL,
                state TEXT NOT NULL,
                mode TEXT NOT NULL CHECK (mode IN ('push', 'sync')),
                dest_path TEXT NOT NULL,
                source_label TEXT NOT NULL,
                declared_file_count INTEGER NOT NULL,
                declared_total_bytes INTEGER NOT NULL,
                actual_file_count INTEGER,
                actual_total_bytes INTEGER,
                artifact_id TEXT,
                dispatch_enabled INTEGER NOT NULL DEFAULT 0 CHECK (dispatch_enabled IN (0, 1)),
                dispatch_paused_reason TEXT,
                created_at INTEGER NOT NULL,
                create_expires_at INTEGER NOT NULL,
                upload_started_at INTEGER,
                packaging_started_at INTEGER,
                ready_at INTEGER,
                dispatch_started_at INTEGER,
                updated_at INTEGER NOT NULL,
                terminal_at INTEGER,
                failure_code TEXT,
                failure_detail TEXT,
                FOREIGN KEY (artifact_id) REFERENCES push_artifacts(artifact_id)
            );
            CREATE TABLE IF NOT EXISTS push_job_devices (
                job_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                enqueue_seq INTEGER NOT NULL UNIQUE,
                target_ordinal INTEGER NOT NULL,
                protocol_mode TEXT NOT NULL CHECK (protocol_mode IN ('job_v1', 'legacy')),
                create_capability_snapshot_json TEXT NOT NULL,
                dispatch_capability_snapshot_json TEXT,
                state TEXT NOT NULL,
                queue_reason TEXT,
                attempt INTEGER NOT NULL DEFAULT 1 CHECK (attempt = 1),
                accept_replay_count INTEGER NOT NULL DEFAULT 0,
                validated_offset INTEGER NOT NULL DEFAULT 0,
                accept_deadline INTEGER,
                accepted_at INTEGER,
                transfer_completed_at INTEGER,
                validation_started_at INTEGER,
                validation_completed_at INTEGER,
                apply_started_at INTEGER,
                updated_at INTEGER NOT NULL,
                terminal_at INTEGER,
                failure_code TEXT,
                failure_detail TEXT,
                added INTEGER,
                updated INTEGER,
                deleted INTEGER,
                reconciliation_reason TEXT,
                reconciliation_deadline INTEGER,
                PRIMARY KEY (job_id, device_id),
                FOREIGN KEY (job_id) REFERENCES push_jobs(job_id) ON DELETE CASCADE
            );
            CREATE TABLE IF NOT EXISTS push_device_fences (
                device_id TEXT PRIMARY KEY,
                blocking_job_id TEXT,
                blocking_opaque_identity TEXT,
                blocking_attempt INTEGER,
                protocol_mode TEXT NOT NULL CHECK (protocol_mode IN ('job_v1', 'legacy')),
                blocking_process_instance_id TEXT,
                reason TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                CHECK (
                    (blocking_job_id IS NOT NULL AND blocking_opaque_identity IS NULL)
                    OR (blocking_job_id IS NULL AND blocking_opaque_identity IS NOT NULL)
                ),
                FOREIGN KEY (blocking_job_id) REFERENCES push_jobs(job_id)
            );
            CREATE UNIQUE INDEX IF NOT EXISTS ux_push_job_device_active
            ON push_job_devices(device_id)
            WHERE state IN (
                'waiting_transfer', 'dispatching', 'downloading',
                'validating', 'applying', 'reconciling'
            );
            INSERT OR IGNORE INTO server_metadata(key, value) VALUES ('schema_version', '1');
            INSERT OR IGNORE INTO server_metadata(key, value) VALUES ('next_enqueue_seq', '1');
            COMMIT;
            """
        )

    @staticmethod
    def _begin(conn: sqlite3.Connection) -> None:
        conn.execute("BEGIN IMMEDIATE")

    @staticmethod
    def _commit(conn: sqlite3.Connection) -> None:
        conn.execute("COMMIT")

    @staticmethod
    def _rollback(conn: sqlite3.Connection) -> None:
        if conn.in_transaction:
            conn.execute("ROLLBACK")

    @staticmethod
    def _next_enqueue_seq(conn: sqlite3.Connection, count: int) -> int:
        row = conn.execute("SELECT value FROM server_metadata WHERE key='next_enqueue_seq'").fetchone()
        start = int(row["value"] if row else 1)
        conn.execute(
            "INSERT INTO server_metadata(key, value) VALUES ('next_enqueue_seq', ?) "
            "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (str(start + count),),
        )
        return start

    @staticmethod
    def _increment_revision(conn: sqlite3.Connection, job_id: str, timestamp: int) -> None:
        conn.execute(
            "UPDATE push_jobs SET revision=revision+1, updated_at=? WHERE job_id=?",
            (timestamp, job_id),
        )

    @staticmethod
    def _fence_visible_job_ids(conn: sqlite3.Connection, device_id: str) -> list[str]:
        rows = conn.execute(
            """
            SELECT DISTINCT j.job_id
            FROM push_jobs j
            JOIN push_job_devices d ON d.job_id=j.job_id
            WHERE d.device_id=?
              AND j.state IN ('created','uploading','packaging','ready','running','reconciling')
            ORDER BY j.job_id
            """,
            (device_id,),
        ).fetchall()
        return [row["job_id"] for row in rows]

    @staticmethod
    def _rederive_job(conn: sqlite3.Connection, job_id: str, timestamp: int) -> None:
        job = conn.execute("SELECT state FROM push_jobs WHERE job_id=?", (job_id,)).fetchone()
        if job is None:
            raise StoreNotFound(job_id)
        current = JobState(job["state"])
        if current in {JobState.CREATED, JobState.UPLOADING, JobState.PACKAGING, JobState.READY, JobState.INTERRUPTED, JobState.FAILED}:
            return
        rows = conn.execute("SELECT state FROM push_job_devices WHERE job_id=?", (job_id,)).fetchall()
        derived = derive_dispatched_job_state(row["state"] for row in rows)
        if derived is current:
            return
        terminal_at = timestamp if is_terminal_job_state(derived) else None
        conn.execute(
            "UPDATE push_jobs SET state=?, terminal_at=?, updated_at=? WHERE job_id=?",
            (derived.value, terminal_at, timestamp, job_id),
        )

    @staticmethod
    def _snapshot(conn: sqlite3.Connection, job_id: str) -> dict[str, Any]:
        job = conn.execute(
            """
            SELECT j.*, a.storage_name, a.display_filename, a.byte_size, a.sha256,
                   a.entry_count, a.created_at AS artifact_created_at
            FROM push_jobs j
            LEFT JOIN push_artifacts a ON a.artifact_id=j.artifact_id
            WHERE j.job_id=?
            """,
            (job_id,),
        ).fetchone()
        if job is None:
            raise StoreNotFound(job_id)
        device_rows = conn.execute(
            "SELECT * FROM push_job_devices WHERE job_id=? ORDER BY target_ordinal",
            (job_id,),
        ).fetchall()
        aggregate = aggregate_device_states(row["state"] for row in device_rows)
        devices: dict[str, Any] = {}
        for row in device_rows:
            fence = conn.execute(
                "SELECT * FROM push_device_fences WHERE device_id=?", (row["device_id"],)
            ).fetchone()
            fence_payload = None
            if fence is not None:
                fence_payload = {
                    "blocking_job_id": fence["blocking_job_id"],
                    "blocking_opaque_identity": fence["blocking_opaque_identity"],
                    "blocking_attempt": fence["blocking_attempt"],
                    "protocol_mode": fence["protocol_mode"],
                    "reason": fence["reason"],
                    "created_at": fence["created_at"],
                    "updated_at": fence["updated_at"],
                }
            failure = None
            if row["failure_code"] or row["failure_detail"]:
                failure = {"code": row["failure_code"], "detail": row["failure_detail"]}
            devices[row["device_id"]] = {
                "state": row["state"],
                "queue_reason": row["queue_reason"],
                "protocol_mode": row["protocol_mode"],
                "attempt": row["attempt"],
                "enqueue_seq": row["enqueue_seq"],
                "validated_offset": row["validated_offset"],
                "accepted_at": row["accepted_at"],
                "failure": failure,
                "result": {
                    "added": row["added"],
                    "updated": row["updated"],
                    "deleted": row["deleted"],
                }
                if row["terminal_at"] is not None
                else None,
                "reconciliation_reason": row["reconciliation_reason"],
                "reconciliation_deadline": row["reconciliation_deadline"],
                "device_fence": fence_payload,
            }
        artifact = None
        if job["artifact_id"]:
            artifact = {
                "artifact_id": job["artifact_id"],
                "url": f"/artifacts/{job['artifact_id']}",
                "display_filename": job["display_filename"],
                "byte_size": job["byte_size"],
                "sha256": job["sha256"],
                "entry_count": job["entry_count"],
                "created_at": job["artifact_created_at"],
            }
        failure = None
        if job["failure_code"] or job["failure_detail"]:
            failure = {"code": job["failure_code"], "detail": job["failure_detail"]}
        return {
            "job_id": job["job_id"],
            "client_request_id": job["client_request_id"],
            "revision": job["revision"],
            "state": job["state"],
            "dispatch_enabled": bool(job["dispatch_enabled"]),
            "dispatch_paused_reason": job["dispatch_paused_reason"],
            "mode": job["mode"],
            "dest_path": job["dest_path"],
            "source_label": job["source_label"],
            "declared_file_count": job["declared_file_count"],
            "declared_total_bytes": job["declared_total_bytes"],
            "actual_file_count": job["actual_file_count"],
            "actual_total_bytes": job["actual_total_bytes"],
            "created_at": job["created_at"],
            "create_expires_at": job["create_expires_at"],
            "updated_at": job["updated_at"],
            "terminal_at": job["terminal_at"],
            "failure": failure,
            "artifact": artifact,
            "aggregate": aggregate,
            "devices": devices,
        }

    async def create_job(
        self,
        request: Any,
        target_protocols: Mapping[str, tuple[ProtocolMode, Iterable[str]]],
        create_timeout_ms: int,
    ) -> tuple[bool, dict[str, Any]]:
        def op(conn: sqlite3.Connection) -> tuple[bool, dict[str, Any]]:
            self._begin(conn)
            try:
                existing = conn.execute(
                    "SELECT job_id, request_fingerprint FROM push_jobs WHERE client_request_id=?",
                    (request.client_request_id,),
                ).fetchone()
                if existing is not None:
                    if existing["request_fingerprint"] != request.fingerprint:
                        raise StoreConflict("client_request_id was already used for a different request")
                    snapshot = self._snapshot(conn, existing["job_id"])
                    self._commit(conn)
                    return False, snapshot
                timestamp = now_ms()
                job_id = str(uuid.uuid4())
                expires = timestamp + create_timeout_ms
                conn.execute(
                    """
                    INSERT INTO push_jobs(
                        job_id, client_request_id, request_fingerprint, revision, state,
                        mode, dest_path, source_label, declared_file_count,
                        declared_total_bytes, dispatch_enabled, created_at,
                        create_expires_at, updated_at
                    ) VALUES (?, ?, ?, 1, 'created', ?, ?, ?, ?, ?, 0, ?, ?, ?)
                    """,
                    (
                        job_id,
                        request.client_request_id,
                        request.fingerprint,
                        request.mode.value,
                        request.dest_path,
                        request.source.display_name,
                        request.source.declared_file_count,
                        request.source.declared_total_bytes,
                        timestamp,
                        expires,
                        timestamp,
                    ),
                )
                start_seq = self._next_enqueue_seq(conn, len(request.target_devices))
                for ordinal, device_id in enumerate(request.target_devices):
                    protocol, capabilities = target_protocols[device_id]
                    conn.execute(
                        """
                        INSERT INTO push_job_devices(
                            job_id, device_id, enqueue_seq, target_ordinal,
                            protocol_mode, create_capability_snapshot_json,
                            state, queue_reason, attempt, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, 'queued', 'awaiting_dispatch', 1, ?)
                        """,
                        (
                            job_id,
                            device_id,
                            start_seq + ordinal,
                            ordinal,
                            protocol.value,
                            json.dumps(sorted(set(capabilities))),
                            timestamp,
                        ),
                    )
                snapshot = self._snapshot(conn, job_id)
                self._commit(conn)
                return True, snapshot
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)

    async def get_snapshot(self, job_id: str) -> dict[str, Any]:
        return await self._call(lambda conn: self._snapshot(conn, job_id))

    async def list_snapshots(self, recent_limit: int, recent_since_ms: int) -> list[dict[str, Any]]:
        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:
            active = conn.execute(
                "SELECT job_id FROM push_jobs WHERE terminal_at IS NULL ORDER BY created_at"
            ).fetchall()
            terminal = conn.execute(
                """
                SELECT job_id FROM push_jobs
                WHERE terminal_at IS NOT NULL AND terminal_at >= ?
                ORDER BY terminal_at DESC LIMIT ?
                """,
                (recent_since_ms, recent_limit),
            ).fetchall()
            ids = [row["job_id"] for row in active]
            ids.extend(row["job_id"] for row in terminal if row["job_id"] not in ids)
            return [self._snapshot(conn, job_id) for job_id in ids]

        return await self._call(op)

    async def start_upload(self, job_id: str) -> dict[str, Any]:
        def op(conn: sqlite3.Connection) -> dict[str, Any]:
            self._begin(conn)
            try:
                row = conn.execute(
                    "SELECT state, create_expires_at FROM push_jobs WHERE job_id=?", (job_id,)
                ).fetchone()
                if row is None:
                    raise StoreNotFound(job_id)
                timestamp = now_ms()
                if row["state"] != JobState.CREATED.value:
                    raise StoreConflict(f"job is {row['state']}, not created")
                if timestamp >= row["create_expires_at"]:
                    conn.execute(
                        """
                        UPDATE push_jobs SET state='interrupted', revision=revision+1,
                            updated_at=?, terminal_at=?, failure_code='upload_not_started_timeout',
                            failure_detail='upload did not start before create_expires_at'
                        WHERE job_id=?
                        """,
                        (timestamp, timestamp, job_id),
                    )
                    snapshot = self._snapshot(conn, job_id)
                    self._commit(conn)
                    raise StoreConflict("job upload deadline expired")
                conn.execute(
                    """
                    UPDATE push_jobs SET state='uploading', revision=revision+1,
                        upload_started_at=?, updated_at=? WHERE job_id=?
                    """,
                    (timestamp, timestamp, job_id),
                )
                snapshot = self._snapshot(conn, job_id)
                self._commit(conn)
                return snapshot
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)

    async def mark_packaging(
        self, job_id: str, actual_file_count: int, actual_total_bytes: int
    ) -> dict[str, Any]:
        return await self._simple_job_transition(
            job_id,
            JobState.UPLOADING,
            JobState.PACKAGING,
            extra={
                "actual_file_count": actual_file_count,
                "actual_total_bytes": actual_total_bytes,
                "packaging_started_at": now_ms(),
            },
        )

    async def publish_artifact(self, job_id: str, artifact: Mapping[str, Any]) -> dict[str, Any]:
        def op(conn: sqlite3.Connection) -> dict[str, Any]:
            self._begin(conn)
            try:
                row = conn.execute("SELECT state FROM push_jobs WHERE job_id=?", (job_id,)).fetchone()
                if row is None:
                    raise StoreNotFound(job_id)
                if row["state"] != JobState.PACKAGING.value:
                    raise StoreConflict(f"job is {row['state']}, not packaging")
                timestamp = now_ms()
                conn.execute(
                    """
                    INSERT INTO push_artifacts(
                        artifact_id, storage_name, display_filename, byte_size,
                        sha256, entry_count, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        artifact["artifact_id"],
                        artifact["storage_name"],
                        artifact["display_filename"],
                        artifact["byte_size"],
                        artifact["sha256"],
                        artifact["entry_count"],
                        timestamp,
                    ),
                )
                conn.execute(
                    """
                    UPDATE push_jobs SET state='ready', artifact_id=?, ready_at=?,
                        revision=revision+1, updated_at=? WHERE job_id=?
                    """,
                    (artifact["artifact_id"], timestamp, timestamp, job_id),
                )
                snapshot = self._snapshot(conn, job_id)
                self._commit(conn)
                return snapshot
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)

    async def fail_pre_dispatch(
        self, job_id: str, state: JobState, code: str, detail: str
    ) -> dict[str, Any]:
        if state not in {JobState.FAILED, JobState.INTERRUPTED}:
            raise ValueError("pre-dispatch failure must be failed or interrupted")

        def op(conn: sqlite3.Connection) -> dict[str, Any]:
            self._begin(conn)
            try:
                row = conn.execute("SELECT state FROM push_jobs WHERE job_id=?", (job_id,)).fetchone()
                if row is None:
                    raise StoreNotFound(job_id)
                current = JobState(row["state"])
                validate_job_transition(current, state)
                timestamp = now_ms()
                conn.execute(
                    """
                    UPDATE push_jobs SET state=?, revision=revision+1, updated_at=?,
                        terminal_at=?, failure_code=?, failure_detail=? WHERE job_id=?
                    """,
                    (state.value, timestamp, timestamp, code, detail, job_id),
                )
                snapshot = self._snapshot(conn, job_id)
                self._commit(conn)
                return snapshot
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)

    async def enable_dispatch(self, job_id: str) -> tuple[bool, dict[str, Any]]:
        def op(conn: sqlite3.Connection) -> tuple[bool, dict[str, Any]]:
            self._begin(conn)
            try:
                row = conn.execute(
                    "SELECT state, dispatch_enabled FROM push_jobs WHERE job_id=?", (job_id,)
                ).fetchone()
                if row is None:
                    raise StoreNotFound(job_id)
                state = JobState(row["state"])
                if state in {JobState.READY, JobState.RUNNING, JobState.RECONCILING}:
                    if bool(row["dispatch_enabled"]):
                        snapshot = self._snapshot(conn, job_id)
                        self._commit(conn)
                        return False, snapshot
                    timestamp = now_ms()
                    next_state = JobState.RUNNING if state is JobState.READY else state
                    conn.execute(
                        """
                        UPDATE push_jobs SET state=?, dispatch_enabled=1,
                            dispatch_paused_reason=NULL, dispatch_started_at=COALESCE(dispatch_started_at, ?),
                            revision=revision+1, updated_at=? WHERE job_id=?
                        """,
                        (next_state.value, timestamp, timestamp, job_id),
                    )
                    snapshot = self._snapshot(conn, job_id)
                    self._commit(conn)
                    return True, snapshot
                if is_terminal_job_state(state):
                    snapshot = self._snapshot(conn, job_id)
                    self._commit(conn)
                    return False, snapshot
                raise StoreConflict(f"job cannot be dispatched from state {state.value}")
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)

    async def claim_next(self, online_device_ids: Iterable[str]) -> dict[str, Any] | None:
        online = tuple(sorted(set(online_device_ids)))
        if not online:
            return None

        def op(conn: sqlite3.Connection) -> dict[str, Any] | None:
            self._begin(conn)
            try:
                placeholders = ",".join("?" for _ in online)
                row = conn.execute(
                    f"""
                    SELECT d.job_id, d.device_id, d.protocol_mode, d.attempt, d.enqueue_seq
                    FROM push_job_devices d
                    JOIN push_jobs j ON j.job_id=d.job_id
                    WHERE d.state='queued'
                      AND j.dispatch_enabled=1
                      AND j.state IN ('running', 'reconciling')
                      AND d.device_id IN ({placeholders})
                      AND NOT EXISTS (
                        SELECT 1 FROM push_device_fences f WHERE f.device_id=d.device_id
                      )
                      AND d.enqueue_seq=(
                        SELECT MIN(d2.enqueue_seq) FROM push_job_devices d2
                        JOIN push_jobs j2 ON j2.job_id=d2.job_id
                        WHERE d2.device_id=d.device_id AND d2.state='queued'
                          AND j2.state IN ('running', 'reconciling')
                      )
                      AND NOT EXISTS (
                        SELECT 1 FROM push_job_devices active
                        WHERE active.device_id=d.device_id
                          AND active.state IN ('waiting_transfer','dispatching','downloading','validating','applying','reconciling')
                      )
                    ORDER BY d.enqueue_seq LIMIT 1
                    """,
                    online,
                ).fetchone()
                if row is None:
                    self._commit(conn)
                    return None
                timestamp = now_ms()
                cursor = conn.execute(
                    """
                    UPDATE push_job_devices SET state='waiting_transfer', queue_reason=NULL,
                        updated_at=? WHERE job_id=? AND device_id=? AND state='queued'
                    """,
                    (timestamp, row["job_id"], row["device_id"]),
                )
                if cursor.rowcount != 1:
                    self._commit(conn)
                    return None
                self._increment_revision(conn, row["job_id"], timestamp)
                self._rederive_job(conn, row["job_id"], timestamp)
                snapshot = self._snapshot(conn, row["job_id"])
                self._commit(conn)
                device = snapshot["devices"][row["device_id"]]
                return {
                    "job": snapshot,
                    "device_id": row["device_id"],
                    "protocol_mode": row["protocol_mode"],
                    "attempt": row["attempt"],
                    "enqueue_seq": row["enqueue_seq"],
                    "device": device,
                }
            except sqlite3.IntegrityError:
                self._rollback(conn)
                return None
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)

    async def mark_dispatching(
        self,
        job_id: str,
        device_id: str,
        capabilities: Iterable[str],
        accept_deadline: int,
    ) -> dict[str, Any]:
        return await self.transition_device(
            job_id,
            device_id,
            expected={DeviceState.WAITING_TRANSFER},
            target=DeviceState.DISPATCHING,
            fields={
                "dispatch_capability_snapshot_json": json.dumps(sorted(set(capabilities))),
                "accept_deadline": accept_deadline,
                "reconciliation_reason": None,
                "reconciliation_deadline": None,
            },
        )

    async def transition_device(
        self,
        job_id: str,
        device_id: str,
        *,
        expected: set[DeviceState] | None,
        target: DeviceState,
        fields: Mapping[str, Any] | None = None,
        allow_terminal_idempotent: bool = False,
    ) -> dict[str, Any]:
        fields = dict(fields or {})
        allowed_columns = {
            "queue_reason",
            "accept_replay_count",
            "validated_offset",
            "accept_deadline",
            "accepted_at",
            "transfer_completed_at",
            "validation_started_at",
            "validation_completed_at",
            "apply_started_at",
            "terminal_at",
            "failure_code",
            "failure_detail",
            "added",
            "updated",
            "deleted",
            "reconciliation_reason",
            "reconciliation_deadline",
            "dispatch_capability_snapshot_json",
        }
        unknown = set(fields) - allowed_columns
        if unknown:
            raise ValueError(f"unsupported device fields: {sorted(unknown)}")

        def op(conn: sqlite3.Connection) -> dict[str, Any]:
            self._begin(conn)
            try:
                row = conn.execute(
                    "SELECT * FROM push_job_devices WHERE job_id=? AND device_id=?",
                    (job_id, device_id),
                ).fetchone()
                if row is None:
                    raise StoreNotFound(f"{job_id}/{device_id}")
                current = DeviceState(row["state"])
                if current is target and allow_terminal_idempotent:
                    snapshot = self._snapshot(conn, job_id)
                    self._commit(conn)
                    return snapshot
                if expected is not None and current not in expected:
                    raise StoreConflict(
                        f"device assignment is {current.value}, expected one of {[s.value for s in expected]}"
                    )
                validate_device_transition(current, target)
                timestamp = now_ms()
                if target in TERMINAL_DEVICE_STATES and "terminal_at" not in fields:
                    fields["terminal_at"] = timestamp
                assignments = ["state=?", "updated_at=?"]
                values: list[Any] = [target.value, timestamp]
                for key, value in fields.items():
                    assignments.append(f"{key}=?")
                    values.append(value)
                values.extend([job_id, device_id])
                conn.execute(
                    f"UPDATE push_job_devices SET {', '.join(assignments)} WHERE job_id=? AND device_id=?",
                    values,
                )
                self._increment_revision(conn, job_id, timestamp)
                self._rederive_job(conn, job_id, timestamp)
                snapshot = self._snapshot(conn, job_id)
                self._commit(conn)
                return snapshot
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)

    async def settle_result(
        self,
        job_id: str,
        device_id: str,
        attempt: int,
        status: str,
        *,
        added: int = 0,
        updated: int = 0,
        deleted: int = 0,
        failure_code: str | None = None,
        failure_detail: str | None = None,
    ) -> tuple[bool, str | None, dict[str, Any]]:
        if attempt != 1:
            raise StoreConflict("stale_attempt")
        if status == "success":
            target = DeviceState.SUCCEEDED
        elif status == "interrupted":
            target = DeviceState.INTERRUPTED
        elif status in {"fail", "failed"}:
            target = DeviceState.FAILED
        else:
            raise StoreConflict("invalid_result_status")

        def op(conn: sqlite3.Connection) -> tuple[bool, str | None, dict[str, Any]]:
            self._begin(conn)
            try:
                row = conn.execute(
                    "SELECT * FROM push_job_devices WHERE job_id=? AND device_id=?",
                    (job_id, device_id),
                ).fetchone()
                if row is None:
                    raise StoreNotFound(f"{job_id}/{device_id}")
                if row["attempt"] != attempt:
                    snapshot = self._snapshot(conn, job_id)
                    self._commit(conn)
                    return False, "stale_attempt", snapshot
                current = DeviceState(row["state"])
                if current in TERMINAL_DEVICE_STATES:
                    same = current is target and (
                        current is not DeviceState.SUCCEEDED
                        or (row["added"], row["updated"], row["deleted"])
                        == (added, updated, deleted)
                    )
                    snapshot = self._snapshot(conn, job_id)
                    self._commit(conn)
                    return same, None if same else "conflicting_terminal_result", snapshot
                timestamp = now_ms()
                conn.execute(
                    """
                    UPDATE push_job_devices SET state=?, queue_reason=NULL, updated_at=?,
                        terminal_at=?, failure_code=?, failure_detail=?, added=?, updated=?, deleted=?,
                        accept_deadline=NULL, reconciliation_reason=NULL, reconciliation_deadline=NULL
                    WHERE job_id=? AND device_id=?
                    """,
                    (
                        target.value,
                        timestamp,
                        timestamp,
                        failure_code,
                        failure_detail,
                        added,
                        updated,
                        deleted,
                        job_id,
                        device_id,
                    ),
                )
                # A matching late terminal result proves a fenced worker is finished.  It
                # must not rewrite an already-unconfirmed assignment, but non-terminal rows
                # settle normally here.
                conn.execute(
                    "DELETE FROM push_device_fences WHERE device_id=? AND blocking_job_id=? AND blocking_attempt=?",
                    (device_id, job_id, attempt),
                )
                self._increment_revision(conn, job_id, timestamp)
                self._rederive_job(conn, job_id, timestamp)
                snapshot = self._snapshot(conn, job_id)
                self._commit(conn)
                return True, None, snapshot
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)

    async def settle_late_fenced_result(
        self, job_id: str, device_id: str, attempt: int
    ) -> tuple[bool, list[dict[str, Any]]]:
        def op(conn: sqlite3.Connection) -> tuple[bool, list[dict[str, Any]]]:
            self._begin(conn)
            try:
                row = conn.execute(
                    "SELECT state FROM push_job_devices WHERE job_id=? AND device_id=?",
                    (job_id, device_id),
                ).fetchone()
                if row is None:
                    raise StoreNotFound(f"{job_id}/{device_id}")
                fence = conn.execute(
                    """
                    SELECT * FROM push_device_fences WHERE device_id=?
                      AND blocking_job_id=? AND blocking_attempt=?
                    """,
                    (device_id, job_id, attempt),
                ).fetchone()
                if row["state"] != DeviceState.UNCONFIRMED.value or fence is None:
                    snapshots = [self._snapshot(conn, job_id)]
                    self._commit(conn)
                    return False, snapshots
                affected = set(self._fence_visible_job_ids(conn, device_id))
                affected.add(job_id)
                conn.execute("DELETE FROM push_device_fences WHERE device_id=?", (device_id,))
                timestamp = now_ms()
                for affected_job_id in sorted(affected):
                    self._increment_revision(conn, affected_job_id, timestamp)
                snapshots = [self._snapshot(conn, value) for value in sorted(affected)]
                self._commit(conn)
                return True, snapshots
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)

    async def mark_reconciling(
        self,
        job_id: str,
        device_id: str,
        *,
        expected: set[DeviceState],
        reason: str,
        deadline: int,
    ) -> dict[str, Any]:
        return await self.transition_device(
            job_id,
            device_id,
            expected=expected,
            target=DeviceState.RECONCILING,
            fields={
                "reconciliation_reason": reason,
                "reconciliation_deadline": deadline,
                "accept_deadline": None,
            },
        )

    async def reconcile_report(
        self,
        job_id: str,
        device_id: str,
        attempt: int,
        status: str,
        phase: str | None,
        detail: str | None,
        *,
        from_server_restart: bool = False,
    ) -> tuple[str, list[dict[str, Any]]]:
        if attempt != 1:
            raise StoreConflict("stale_attempt")
        if status == "active":
            phase_map = {
                "downloading": DeviceState.DOWNLOADING,
                "validating": DeviceState.VALIDATING,
                "applying": DeviceState.APPLYING,
            }
            target = phase_map.get(phase or "")
            if target is None:
                raise StoreConflict("invalid reconcile phase")
            snapshot = await self.transition_device(
                job_id,
                device_id,
                expected={DeviceState.RECONCILING},
                target=target,
                fields={
                    "accepted_at": now_ms(),
                    "reconciliation_reason": None,
                    "reconciliation_deadline": None,
                    "accept_deadline": None,
                },
            )
            return "active", [snapshot]
        if status not in {"absent", "interrupted"}:
            raise StoreConflict("unsupported reconcile status")

        def op(conn: sqlite3.Connection) -> tuple[str, list[dict[str, Any]]]:
            self._begin(conn)
            try:
                row = conn.execute(
                    """
                    SELECT state, attempt, accept_replay_count, reconciliation_reason
                    FROM push_job_devices WHERE job_id=? AND device_id=?
                    """,
                    (job_id, device_id),
                ).fetchone()
                if row is None:
                    raise StoreNotFound(f"{job_id}/{device_id}")
                if row["attempt"] != attempt:
                    raise StoreConflict("stale_attempt")
                current = DeviceState(row["state"])
                if current is not DeviceState.RECONCILING:
                    raise StoreConflict(f"device assignment is {current.value}, expected reconciling")

                timestamp = now_ms()
                can_replay = (
                    status == "absent"
                    and not from_server_restart
                    and row["reconciliation_reason"] == "command_accept_timeout"
                    and row["accept_replay_count"] < 1
                )
                if can_replay:
                    validate_device_transition(current, DeviceState.QUEUED)
                    conn.execute(
                        """
                        UPDATE push_job_devices SET state='queued', queue_reason='command_accept_replay',
                            accept_replay_count=accept_replay_count+1, updated_at=?, terminal_at=NULL,
                            failure_code=NULL, failure_detail=NULL, reconciliation_reason=NULL,
                            reconciliation_deadline=NULL, accept_deadline=NULL
                        WHERE job_id=? AND device_id=?
                        """,
                        (timestamp, job_id, device_id),
                    )
                    self._increment_revision(conn, job_id, timestamp)
                    self._rederive_job(conn, job_id, timestamp)
                    snapshot = self._snapshot(conn, job_id)
                    self._commit(conn)
                    return "requeued", [snapshot]

                validate_device_transition(current, DeviceState.INTERRUPTED)
                affected = set(self._fence_visible_job_ids(conn, device_id))
                affected.add(job_id)
                code = "client_restarted" if status == "interrupted" else "client_state_absent"
                conn.execute(
                    """
                    UPDATE push_job_devices SET state='interrupted', updated_at=?, terminal_at=?,
                        failure_code=?, failure_detail=?, reconciliation_reason=NULL,
                        reconciliation_deadline=NULL, accept_deadline=NULL
                    WHERE job_id=? AND device_id=?
                    """,
                    (timestamp, timestamp, code, detail or status, job_id, device_id),
                )
                conn.execute(
                    """
                    DELETE FROM push_device_fences
                    WHERE device_id=? AND blocking_job_id=? AND blocking_attempt=?
                    """,
                    (device_id, job_id, attempt),
                )
                for affected_job_id in sorted(affected):
                    self._increment_revision(conn, affected_job_id, timestamp)
                    if affected_job_id == job_id:
                        self._rederive_job(conn, affected_job_id, timestamp)
                snapshots = [self._snapshot(conn, value) for value in sorted(affected)]
                self._commit(conn)
                return status, snapshots
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)

    async def mark_unconfirmed(
        self,
        job_id: str,
        device_id: str,
        process_instance_id: str | None,
        reason: str,
    ) -> list[dict[str, Any]]:
        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:
            self._begin(conn)
            try:
                row = conn.execute(
                    "SELECT state, attempt, protocol_mode FROM push_job_devices WHERE job_id=? AND device_id=?",
                    (job_id, device_id),
                ).fetchone()
                if row is None:
                    raise StoreNotFound(f"{job_id}/{device_id}")
                current = DeviceState(row["state"])
                if current is DeviceState.UNCONFIRMED:
                    snapshots = [self._snapshot(conn, job_id)]
                    self._commit(conn)
                    return snapshots
                if current is not DeviceState.RECONCILING:
                    raise StoreConflict(f"cannot mark {current.value} unconfirmed")
                affected = set(self._fence_visible_job_ids(conn, device_id))
                affected.add(job_id)
                timestamp = now_ms()
                conn.execute(
                    """
                    UPDATE push_job_devices SET state='unconfirmed', updated_at=?, terminal_at=?,
                        failure_code='reconciliation_timeout', failure_detail=?,
                        reconciliation_reason=NULL, reconciliation_deadline=NULL, accept_deadline=NULL
                    WHERE job_id=? AND device_id=?
                    """,
                    (timestamp, timestamp, reason, job_id, device_id),
                )
                conn.execute(
                    """
                    INSERT INTO push_device_fences(
                        device_id, blocking_job_id, blocking_attempt, protocol_mode,
                        blocking_process_instance_id, reason, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(device_id) DO UPDATE SET
                        blocking_job_id=excluded.blocking_job_id,
                        blocking_opaque_identity=NULL,
                        blocking_attempt=excluded.blocking_attempt,
                        protocol_mode=excluded.protocol_mode,
                        blocking_process_instance_id=excluded.blocking_process_instance_id,
                        reason=excluded.reason,
                        updated_at=excluded.updated_at
                    """,
                    (
                        device_id,
                        job_id,
                        row["attempt"],
                        row["protocol_mode"],
                        process_instance_id,
                        reason,
                        timestamp,
                        timestamp,
                    ),
                )
                for affected_job_id in sorted(affected):
                    self._increment_revision(conn, affected_job_id, timestamp)
                    if affected_job_id == job_id:
                        self._rederive_job(conn, job_id, timestamp)
                snapshots = [self._snapshot(conn, value) for value in sorted(affected)]
                self._commit(conn)
                return snapshots
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)

    async def add_opaque_fence(
        self,
        device_id: str,
        opaque_identity: str,
        protocol_mode: ProtocolMode,
        process_instance_id: str | None,
        reason: str,
    ) -> list[dict[str, Any]]:
        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:
            self._begin(conn)
            try:
                affected = set(self._fence_visible_job_ids(conn, device_id))
                timestamp = now_ms()
                conn.execute(
                    """
                    INSERT INTO push_device_fences(
                        device_id, blocking_opaque_identity, protocol_mode,
                        blocking_process_instance_id, reason, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
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
                        device_id, opaque_identity[:4096], protocol_mode.value,
                        process_instance_id, reason, timestamp, timestamp,
                    ),
                )
                for affected_job_id in sorted(affected):
                    self._increment_revision(conn, affected_job_id, timestamp)
                snapshots = [self._snapshot(conn, value) for value in sorted(affected)]
                self._commit(conn)
                return snapshots
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)

    async def clear_matching_fence(
        self, job_id: str, device_id: str, attempt: int
    ) -> list[dict[str, Any]]:
        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:
            self._begin(conn)
            try:
                fence = conn.execute(
                    """
                    SELECT 1 FROM push_device_fences
                    WHERE device_id=? AND blocking_job_id=? AND blocking_attempt=?
                    """,
                    (device_id, job_id, attempt),
                ).fetchone()
                if fence is None:
                    self._commit(conn)
                    return []
                affected = set(self._fence_visible_job_ids(conn, device_id))
                affected.add(job_id)
                conn.execute(
                    """
                    DELETE FROM push_device_fences
                    WHERE device_id=? AND blocking_job_id=? AND blocking_attempt=?
                    """,
                    (device_id, job_id, attempt),
                )
                timestamp = now_ms()
                for affected_job_id in sorted(affected):
                    self._increment_revision(conn, affected_job_id, timestamp)
                snapshots = [self._snapshot(conn, value) for value in sorted(affected)]
                self._commit(conn)
                return snapshots
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)

    async def clear_fence_on_process_replacement(
        self, device_id: str, new_process_instance_id: str | None, has_job_v1: bool
    ) -> list[dict[str, Any]]:
        if not has_job_v1 or not new_process_instance_id:
            return []

        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:
            self._begin(conn)
            try:
                fence = conn.execute(
                    "SELECT * FROM push_device_fences WHERE device_id=?", (device_id,)
                ).fetchone()
                if fence is None or fence["blocking_process_instance_id"] in {None, new_process_instance_id}:
                    self._commit(conn)
                    return []
                affected = set(self._fence_visible_job_ids(conn, device_id))
                if fence["blocking_job_id"]:
                    affected.add(fence["blocking_job_id"])
                conn.execute("DELETE FROM push_device_fences WHERE device_id=?", (device_id,))
                timestamp = now_ms()
                for affected_job_id in sorted(affected):
                    self._increment_revision(conn, affected_job_id, timestamp)
                snapshots = [self._snapshot(conn, value) for value in sorted(affected)]
                self._commit(conn)
                return snapshots
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)

    async def handle_busy_rejection(
        self,
        job_id: str,
        device_id: str,
        attempt: int,
        active_job: Mapping[str, Any] | None,
        process_instance_id: str | None,
    ) -> tuple[str, list[dict[str, Any]]]:
        """Settle a device_busy response without creating a redispatch loop."""

        def op(conn: sqlite3.Connection) -> tuple[str, list[dict[str, Any]]]:
            self._begin(conn)
            try:
                row = conn.execute(
                    "SELECT state, attempt FROM push_job_devices WHERE job_id=? AND device_id=?",
                    (job_id, device_id),
                ).fetchone()
                if row is None:
                    raise StoreNotFound(f"{job_id}/{device_id}")
                if row["attempt"] != attempt:
                    raise StoreConflict("stale_attempt")
                if row["state"] != DeviceState.DISPATCHING.value:
                    raise StoreConflict(f"device assignment is {row['state']}, expected dispatching")

                active_id = active_job.get("job_id") if isinstance(active_job, Mapping) else None
                active_attempt = active_job.get("attempt") if isinstance(active_job, Mapping) else None
                fence = conn.execute(
                    "SELECT * FROM push_device_fences WHERE device_id=?", (device_id,)
                ).fetchone()
                matching_fence = bool(
                    fence
                    and isinstance(active_id, str)
                    and fence["blocking_job_id"] == active_id
                    and fence["blocking_attempt"] == active_attempt
                )
                timestamp = now_ms()
                if matching_fence:
                    conn.execute(
                        """
                        UPDATE push_job_devices SET state='queued', queue_reason='same_device_job',
                            accept_deadline=NULL, updated_at=?
                        WHERE job_id=? AND device_id=?
                        """,
                        (timestamp, job_id, device_id),
                    )
                    self._increment_revision(conn, job_id, timestamp)
                    self._rederive_job(conn, job_id, timestamp)
                    snapshot = self._snapshot(conn, job_id)
                    self._commit(conn)
                    return "queued", [snapshot]

                opaque = json.dumps(
                    dict(active_job) if isinstance(active_job, Mapping) else {"legacy": True},
                    sort_keys=True,
                    separators=(",", ":"),
                )[:4096]
                affected = set(self._fence_visible_job_ids(conn, device_id))
                affected.add(job_id)
                conn.execute(
                    """
                    INSERT INTO push_device_fences(
                        device_id, blocking_opaque_identity, protocol_mode,
                        blocking_process_instance_id, reason, created_at, updated_at
                    ) VALUES (?, ?, 'job_v1', ?, 'client_state_conflict', ?, ?)
                    ON CONFLICT(device_id) DO UPDATE SET
                        blocking_job_id=NULL, blocking_opaque_identity=excluded.blocking_opaque_identity,
                        blocking_attempt=NULL, protocol_mode='job_v1',
                        blocking_process_instance_id=excluded.blocking_process_instance_id,
                        reason=excluded.reason, updated_at=excluded.updated_at
                    """,
                    (device_id, opaque, process_instance_id, timestamp, timestamp),
                )
                conn.execute(
                    """
                    UPDATE push_job_devices SET state='failed', queue_reason=NULL, updated_at=?, terminal_at=?,
                        failure_code='client_state_conflict',
                        failure_detail='client rejected command because an unknown Push/Sync worker is active',
                        accept_deadline=NULL
                    WHERE job_id=? AND device_id=?
                    """,
                    (timestamp, timestamp, job_id, device_id),
                )
                for affected_job_id in sorted(affected):
                    self._increment_revision(conn, affected_job_id, timestamp)
                    if affected_job_id == job_id:
                        self._rederive_job(conn, job_id, timestamp)
                snapshots = [self._snapshot(conn, value) for value in sorted(affected)]
                self._commit(conn)
                return "failed", snapshots
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)

    async def active_assignment_for_device(self, device_id: str) -> dict[str, Any] | None:
        def op(conn: sqlite3.Connection) -> dict[str, Any] | None:
            row = conn.execute(
                """
                SELECT d.*, j.artifact_id, j.mode, j.dest_path
                FROM push_job_devices d JOIN push_jobs j ON j.job_id=d.job_id
                WHERE d.device_id=? AND d.state IN (
                    'waiting_transfer','dispatching','downloading','validating','applying','reconciling'
                )
                LIMIT 1
                """,
                (device_id,),
            ).fetchone()
            return dict(row) if row else None

        return await self._call(op)

    async def expired_reconciliations(self, timestamp: int) -> list[dict[str, Any]]:
        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:
            rows = conn.execute(
                """
                SELECT d.job_id, d.device_id, d.attempt, d.protocol_mode
                FROM push_job_devices d
                WHERE d.state='reconciling' AND d.reconciliation_deadline IS NOT NULL
                  AND d.reconciliation_deadline <= ?
                ORDER BY d.reconciliation_deadline
                """,
                (timestamp,),
            ).fetchall()
            return [dict(row) for row in rows]

        return await self._call(op)

    async def expired_created(self, timestamp: int) -> list[str]:
        def op(conn: sqlite3.Connection) -> list[str]:
            rows = conn.execute(
                "SELECT job_id FROM push_jobs WHERE state='created' AND create_expires_at <= ?",
                (timestamp,),
            ).fetchall()
            return [row["job_id"] for row in rows]

        return await self._call(op)

    async def expire_created(self, job_id: str, expected_deadline: int | None = None) -> dict[str, Any] | None:
        def op(conn: sqlite3.Connection) -> dict[str, Any] | None:
            self._begin(conn)
            try:
                row = conn.execute(
                    "SELECT state, create_expires_at FROM push_jobs WHERE job_id=?", (job_id,)
                ).fetchone()
                if row is None:
                    self._commit(conn)
                    return None
                if row["state"] != JobState.CREATED.value:
                    self._commit(conn)
                    return None
                if expected_deadline is not None and row["create_expires_at"] != expected_deadline:
                    self._commit(conn)
                    return None
                timestamp = now_ms()
                if timestamp < row["create_expires_at"]:
                    self._commit(conn)
                    return None
                conn.execute(
                    """
                    UPDATE push_jobs SET state='interrupted', revision=revision+1,
                        updated_at=?, terminal_at=?, failure_code='upload_not_started_timeout',
                        failure_detail='upload did not start before create_expires_at'
                    WHERE job_id=?
                    """,
                    (timestamp, timestamp, job_id),
                )
                snapshot = self._snapshot(conn, job_id)
                self._commit(conn)
                return snapshot
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)

    def recover_startup_sync(
        self,
        *,
        accept_reconciliation_timeout_ms: int,
        reconciliation_timeout_ms: int,
    ) -> tuple[list[dict[str, Any]], list[str]]:
        def op(conn: sqlite3.Connection) -> tuple[list[dict[str, Any]], list[str]]:
            self._begin(conn)
            try:
                timestamp = now_ms()
                cleanup_jobs: list[str] = []
                touched: set[str] = set()
                rows = conn.execute("SELECT job_id, state, create_expires_at FROM push_jobs").fetchall()
                for row in rows:
                    state = JobState(row["state"])
                    job_id = row["job_id"]
                    if state is JobState.CREATED and timestamp >= row["create_expires_at"]:
                        conn.execute(
                            """
                            UPDATE push_jobs SET state='interrupted', revision=revision+1,
                                updated_at=?, terminal_at=?, failure_code='upload_not_started_timeout',
                                failure_detail='upload did not start before create_expires_at'
                            WHERE job_id=?
                            """,
                            (timestamp, timestamp, job_id),
                        )
                        cleanup_jobs.append(job_id)
                        touched.add(job_id)
                    elif state in {JobState.UPLOADING, JobState.PACKAGING}:
                        conn.execute(
                            """
                            UPDATE push_jobs SET state='interrupted', revision=revision+1,
                                updated_at=?, terminal_at=?, failure_code='server_restart',
                                failure_detail='server restarted during non-resumable upload or packaging'
                            WHERE job_id=?
                            """,
                            (timestamp, timestamp, job_id),
                        )
                        cleanup_jobs.append(job_id)
                        touched.add(job_id)
                    elif state in {JobState.RUNNING, JobState.RECONCILING}:
                        conn.execute(
                            """
                            UPDATE push_jobs SET dispatch_enabled=0,
                                dispatch_paused_reason='server_restart', revision=revision+1,
                                updated_at=? WHERE job_id=?
                            """,
                            (timestamp, job_id),
                        )
                        touched.add(job_id)
                device_rows = conn.execute(
                    "SELECT job_id, device_id, state FROM push_job_devices"
                ).fetchall()
                for row in device_rows:
                    state = DeviceState(row["state"])
                    job_id, device_id = row["job_id"], row["device_id"]
                    if state is DeviceState.WAITING_TRANSFER:
                        conn.execute(
                            """
                            UPDATE push_job_devices SET state='queued', queue_reason='dispatch_paused',
                                updated_at=? WHERE job_id=? AND device_id=?
                            """,
                            (timestamp, job_id, device_id),
                        )
                        touched.add(job_id)
                    elif state is DeviceState.DISPATCHING:
                        conn.execute(
                            """
                            UPDATE push_job_devices SET state='reconciling',
                                reconciliation_reason='server_restart_before_accept',
                                reconciliation_deadline=?, accept_deadline=NULL, updated_at=?
                            WHERE job_id=? AND device_id=?
                            """,
                            (timestamp + accept_reconciliation_timeout_ms, timestamp, job_id, device_id),
                        )
                        touched.add(job_id)
                    elif state in {DeviceState.DOWNLOADING, DeviceState.VALIDATING, DeviceState.APPLYING}:
                        conn.execute(
                            """
                            UPDATE push_job_devices SET state='reconciling',
                                reconciliation_reason='server_restart_after_accept',
                                reconciliation_deadline=?, accept_deadline=NULL, updated_at=?
                            WHERE job_id=? AND device_id=?
                            """,
                            (timestamp + reconciliation_timeout_ms, timestamp, job_id, device_id),
                        )
                        touched.add(job_id)
                for job_id in touched:
                    # Device changes and dispatch gate changes are represented by one
                    # startup revision per job, unless the job transition above already
                    # incremented it; exact count is less important than monotonicity and
                    # exact snapshots, so coalesce in this serialized recovery command.
                    self._rederive_job(conn, job_id, timestamp)
                snapshots = [self._snapshot(conn, job_id) for job_id in sorted(touched)]
                self._commit(conn)
                return snapshots, cleanup_jobs
            except BaseException:
                self._rollback(conn)
                raise

        return self._call_sync(op)

    def reconcile_missing_artifacts_sync(
        self, missing_artifact_ids: Iterable[str]
    ) -> list[dict[str, Any]]:
        missing = tuple(sorted(set(missing_artifact_ids)))
        if not missing:
            return []

        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:
            self._begin(conn)
            try:
                placeholders = ",".join("?" for _ in missing)
                jobs = conn.execute(
                    f"SELECT job_id, state FROM push_jobs WHERE artifact_id IN ({placeholders})",
                    missing,
                ).fetchall()
                timestamp = now_ms()
                touched: set[str] = set()
                for job in jobs:
                    job_id = job["job_id"]
                    state = JobState(job["state"])
                    if state is JobState.READY:
                        conn.execute(
                            """
                            UPDATE push_jobs SET state='failed', revision=revision+1, updated_at=?,
                                terminal_at=?, failure_code='artifact_missing',
                                failure_detail='immutable artifact file is missing at server startup'
                            WHERE job_id=?
                            """,
                            (timestamp, timestamp, job_id),
                        )
                        touched.add(job_id)
                    elif state in {JobState.RUNNING, JobState.RECONCILING}:
                        conn.execute(
                            """
                            UPDATE push_job_devices SET state='failed', queue_reason=NULL, updated_at=?,
                                terminal_at=?, failure_code='artifact_missing',
                                failure_detail='immutable artifact file is missing at server startup'
                            WHERE job_id=? AND state='queued'
                            """,
                            (timestamp, timestamp, job_id),
                        )
                        self._increment_revision(conn, job_id, timestamp)
                        self._rederive_job(conn, job_id, timestamp)
                        touched.add(job_id)
                snapshots = [self._snapshot(conn, value) for value in sorted(touched)]
                self._commit(conn)
                return snapshots
            except BaseException:
                self._rollback(conn)
                raise

        return self._call_sync(op)

    def artifact_records_sync(self) -> list[dict[str, Any]]:
        return self._call_sync(
            lambda conn: [dict(row) for row in conn.execute("SELECT * FROM push_artifacts").fetchall()]
        )

    async def artifact_record(self, artifact_id: str) -> dict[str, Any] | None:
        def op(conn: sqlite3.Connection) -> dict[str, Any] | None:
            row = conn.execute("SELECT * FROM push_artifacts WHERE artifact_id=?", (artifact_id,)).fetchone()
            return dict(row) if row else None

        return await self._call(op)

    async def _simple_job_transition(
        self,
        job_id: str,
        expected: JobState,
        target: JobState,
        *,
        extra: Mapping[str, Any] | None = None,
    ) -> dict[str, Any]:
        extra = dict(extra or {})

        def op(conn: sqlite3.Connection) -> dict[str, Any]:
            self._begin(conn)
            try:
                row = conn.execute("SELECT state FROM push_jobs WHERE job_id=?", (job_id,)).fetchone()
                if row is None:
                    raise StoreNotFound(job_id)
                current = JobState(row["state"])
                if current is not expected:
                    raise StoreConflict(f"job is {current.value}, expected {expected.value}")
                validate_job_transition(current, target)
                timestamp = now_ms()
                assignments = ["state=?", "revision=revision+1", "updated_at=?"]
                values: list[Any] = [target.value, timestamp]
                for key, value in extra.items():
                    assignments.append(f"{key}=?")
                    values.append(value)
                values.append(job_id)
                conn.execute(
                    f"UPDATE push_jobs SET {', '.join(assignments)} WHERE job_id=?", values
                )
                snapshot = self._snapshot(conn, job_id)
                self._commit(conn)
                return snapshot
            except BaseException:
                self._rollback(conn)
                raise

        return await self._call(op)
