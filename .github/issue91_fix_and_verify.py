#!/usr/bin/env python3
"""Apply and verify the final Issue #91 hardening pass.

This script is intentionally temporary.  The GitHub Actions workflow runs it in
an actual checkout, executes the complete server/client test suites, then removes
both this file and the workflow before committing the resulting source changes.
"""
from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one occurrence, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


def replace_between(path: str, start: str, end: str, replacement: str) -> None:
    text = read(path)
    begin = text.find(start)
    if begin < 0:
        raise RuntimeError(f"{path}: start marker not found: {start!r}")
    finish = text.find(end, begin + len(start))
    if finish < 0:
        raise RuntimeError(f"{path}: end marker not found: {end!r}")
    write(path, text[:begin] + replacement + text[finish:])


def append_once(path: str, marker: str, addition: str) -> None:
    text = read(path)
    if addition.strip() in text:
        return
    if marker not in text:
        raise RuntimeError(f"{path}: append marker not found: {marker!r}")
    write(path, text.replace(marker, marker + addition, 1))


def patch_python_domain() -> None:
    path = "mdm-server/styly_mdm/push_jobs.py"
    replace_once(path, "from enum import StrEnum\n", "from enum import Enum\n")
    replace_once(
        path,
        'CAP_PUSH_CANCEL_V1 = "push_cancel_v1"\n\n\nclass PushMode(StrEnum):',
        'CAP_PUSH_CANCEL_V1 = "push_cancel_v1"\n\n\nclass StringEnum(str, Enum):\n'
        '    """Python 3.10-compatible equivalent of enum.StrEnum."""\n\n'
        '    def __str__(self) -> str:\n'
        '        return self.value\n\n\n'
        'class PushMode(StringEnum):',
    )
    replace_once(path, "class ProtocolMode(StrEnum):", "class ProtocolMode(StringEnum):")
    replace_once(path, "class JobState(StrEnum):", "class JobState(StringEnum):")
    replace_once(path, "class DeviceState(StrEnum):", "class DeviceState(StringEnum):")


def patch_store() -> None:
    path = "mdm-server/styly_mdm/push_job_store.py"

    # The oldest running/reconciling queued assignment blocks later jobs even when
    # dispatch is paused after restart.  Ready/failed jobs are deliberately outside
    # the active device queue and therefore do not block it.
    replace_once(
        path,
        "                        WHERE d2.device_id=d.device_id AND d2.state='queued'\n"
        "                          AND j2.dispatch_enabled=1\n",
        "                        WHERE d2.device_id=d.device_id AND d2.state='queued'\n"
        "                          AND j2.state IN ('running', 'reconciling')\n",
    )

    replace_once(
        path,
        '        target = DeviceState.SUCCEEDED if status == "success" else DeviceState.FAILED\n',
        """        if status == "success":
            target = DeviceState.SUCCEEDED
        elif status == "interrupted":
            target = DeviceState.INTERRUPTED
        elif status in {"fail", "failed"}:
            target = DeviceState.FAILED
        else:
            raise StoreConflict("invalid_result_status")
""",
    )

    reconcile_method = '''    async def reconcile_report(\n        self,\n        job_id: str,\n        device_id: str,\n        attempt: int,\n        status: str,\n        phase: str | None,\n        detail: str | None,\n        *,\n        from_server_restart: bool = False,\n    ) -> tuple[str, list[dict[str, Any]]]:\n        if attempt != 1:\n            raise StoreConflict("stale_attempt")\n        if status == "active":\n            phase_map = {\n                "downloading": DeviceState.DOWNLOADING,\n                "validating": DeviceState.VALIDATING,\n                "applying": DeviceState.APPLYING,\n            }\n            target = phase_map.get(phase or "")\n            if target is None:\n                raise StoreConflict("invalid reconcile phase")\n            snapshot = await self.transition_device(\n                job_id,\n                device_id,\n                expected={DeviceState.RECONCILING},\n                target=target,\n                fields={\n                    "accepted_at": now_ms(),\n                    "reconciliation_reason": None,\n                    "reconciliation_deadline": None,\n                    "accept_deadline": None,\n                },\n            )\n            return "active", [snapshot]\n        if status not in {"absent", "interrupted"}:\n            raise StoreConflict("unsupported reconcile status")\n\n        def op(conn: sqlite3.Connection) -> tuple[str, list[dict[str, Any]]]:\n            self._begin(conn)\n            try:\n                row = conn.execute(\n                    """\n                    SELECT state, attempt, accept_replay_count, reconciliation_reason\n                    FROM push_job_devices WHERE job_id=? AND device_id=?\n                    """,\n                    (job_id, device_id),\n                ).fetchone()\n                if row is None:\n                    raise StoreNotFound(f"{job_id}/{device_id}")\n                if row["attempt"] != attempt:\n                    raise StoreConflict("stale_attempt")\n                current = DeviceState(row["state"])\n                if current is not DeviceState.RECONCILING:\n                    raise StoreConflict(f"device assignment is {current.value}, expected reconciling")\n\n                timestamp = now_ms()\n                can_replay = (\n                    status == "absent"\n                    and not from_server_restart\n                    and row["reconciliation_reason"] == "command_accept_timeout"\n                    and row["accept_replay_count"] < 1\n                )\n                if can_replay:\n                    validate_device_transition(current, DeviceState.QUEUED)\n                    conn.execute(\n                        """\n                        UPDATE push_job_devices SET state='queued', queue_reason='command_accept_replay',\n                            accept_replay_count=accept_replay_count+1, updated_at=?, terminal_at=NULL,\n                            failure_code=NULL, failure_detail=NULL, reconciliation_reason=NULL,\n                            reconciliation_deadline=NULL, accept_deadline=NULL\n                        WHERE job_id=? AND device_id=?\n                        """,\n                        (timestamp, job_id, device_id),\n                    )\n                    self._increment_revision(conn, job_id, timestamp)\n                    self._rederive_job(conn, job_id, timestamp)\n                    snapshot = self._snapshot(conn, job_id)\n                    self._commit(conn)\n                    return "requeued", [snapshot]\n\n                validate_device_transition(current, DeviceState.INTERRUPTED)\n                affected = set(self._fence_visible_job_ids(conn, device_id))\n                affected.add(job_id)\n                code = "client_restarted" if status == "interrupted" else "client_state_absent"\n                conn.execute(\n                    """\n                    UPDATE push_job_devices SET state='interrupted', updated_at=?, terminal_at=?,\n                        failure_code=?, failure_detail=?, reconciliation_reason=NULL,\n                        reconciliation_deadline=NULL, accept_deadline=NULL\n                    WHERE job_id=? AND device_id=?\n                    """,\n                    (timestamp, timestamp, code, detail or status, job_id, device_id),\n                )\n                conn.execute(\n                    """\n                    DELETE FROM push_device_fences\n                    WHERE device_id=? AND blocking_job_id=? AND blocking_attempt=?\n                    """,\n                    (device_id, job_id, attempt),\n                )\n                for affected_job_id in sorted(affected):\n                    self._increment_revision(conn, affected_job_id, timestamp)\n                    if affected_job_id == job_id:\n                        self._rederive_job(conn, affected_job_id, timestamp)\n                snapshots = [self._snapshot(conn, value) for value in sorted(affected)]\n                self._commit(conn)\n                return status, snapshots\n            except BaseException:\n                self._rollback(conn)\n                raise\n\n        return await self._call(op)\n\n'''
    replace_between(
        path,
        "    async def reconcile_report(\n",
        "    async def mark_unconfirmed(\n",
        reconcile_method,
    )

    mark_unconfirmed = '''    async def mark_unconfirmed(\n        self,\n        job_id: str,\n        device_id: str,\n        process_instance_id: str | None,\n        reason: str,\n    ) -> list[dict[str, Any]]:\n        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:\n            self._begin(conn)\n            try:\n                row = conn.execute(\n                    "SELECT state, attempt, protocol_mode FROM push_job_devices WHERE job_id=? AND device_id=?",\n                    (job_id, device_id),\n                ).fetchone()\n                if row is None:\n                    raise StoreNotFound(f"{job_id}/{device_id}")\n                current = DeviceState(row["state"])\n                if current is DeviceState.UNCONFIRMED:\n                    snapshots = [self._snapshot(conn, job_id)]\n                    self._commit(conn)\n                    return snapshots\n                if current is not DeviceState.RECONCILING:\n                    raise StoreConflict(f"cannot mark {current.value} unconfirmed")\n                affected = set(self._fence_visible_job_ids(conn, device_id))\n                affected.add(job_id)\n                timestamp = now_ms()\n                conn.execute(\n                    """\n                    UPDATE push_job_devices SET state='unconfirmed', updated_at=?, terminal_at=?,\n                        failure_code='reconciliation_timeout', failure_detail=?,\n                        reconciliation_reason=NULL, reconciliation_deadline=NULL, accept_deadline=NULL\n                    WHERE job_id=? AND device_id=?\n                    """,\n                    (timestamp, timestamp, reason, job_id, device_id),\n                )\n                conn.execute(\n                    """\n                    INSERT INTO push_device_fences(\n                        device_id, blocking_job_id, blocking_attempt, protocol_mode,\n                        blocking_process_instance_id, reason, created_at, updated_at\n                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)\n                    ON CONFLICT(device_id) DO UPDATE SET\n                        blocking_job_id=excluded.blocking_job_id,\n                        blocking_opaque_identity=NULL,\n                        blocking_attempt=excluded.blocking_attempt,\n                        protocol_mode=excluded.protocol_mode,\n                        blocking_process_instance_id=excluded.blocking_process_instance_id,\n                        reason=excluded.reason,\n                        updated_at=excluded.updated_at\n                    """,\n                    (\n                        device_id,\n                        job_id,\n                        row["attempt"],\n                        row["protocol_mode"],\n                        process_instance_id,\n                        reason,\n                        timestamp,\n                        timestamp,\n                    ),\n                )\n                for affected_job_id in sorted(affected):\n                    self._increment_revision(conn, affected_job_id, timestamp)\n                    if affected_job_id == job_id:\n                        self._rederive_job(conn, job_id, timestamp)\n                snapshots = [self._snapshot(conn, value) for value in sorted(affected)]\n                self._commit(conn)\n                return snapshots\n            except BaseException:\n                self._rollback(conn)\n                raise\n\n        return await self._call(op)\n\n'''
    replace_between(
        path,
        "    async def mark_unconfirmed(\n",
        "    async def add_opaque_fence(\n",
        mark_unconfirmed,
    )

    add_opaque = '''    async def add_opaque_fence(\n        self,\n        device_id: str,\n        opaque_identity: str,\n        protocol_mode: ProtocolMode,\n        process_instance_id: str | None,\n        reason: str,\n    ) -> list[dict[str, Any]]:\n        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:\n            self._begin(conn)\n            try:\n                affected = set(self._fence_visible_job_ids(conn, device_id))\n                timestamp = now_ms()\n                conn.execute(\n                    """\n                    INSERT INTO push_device_fences(\n                        device_id, blocking_opaque_identity, protocol_mode,\n                        blocking_process_instance_id, reason, created_at, updated_at\n                    ) VALUES (?, ?, ?, ?, ?, ?, ?)\n                    ON CONFLICT(device_id) DO UPDATE SET\n                        blocking_job_id=NULL,\n                        blocking_opaque_identity=excluded.blocking_opaque_identity,\n                        blocking_attempt=NULL,\n                        protocol_mode=excluded.protocol_mode,\n                        blocking_process_instance_id=excluded.blocking_process_instance_id,\n                        reason=excluded.reason,\n                        updated_at=excluded.updated_at\n                    """,\n                    (\n                        device_id, opaque_identity[:4096], protocol_mode.value,\n                        process_instance_id, reason, timestamp, timestamp,\n                    ),\n                )\n                for affected_job_id in sorted(affected):\n                    self._increment_revision(conn, affected_job_id, timestamp)\n                snapshots = [self._snapshot(conn, value) for value in sorted(affected)]\n                self._commit(conn)\n                return snapshots\n            except BaseException:\n                self._rollback(conn)\n                raise\n\n        return await self._call(op)\n\n'''
    replace_between(
        path,
        "    async def add_opaque_fence(\n",
        "    async def clear_matching_fence(\n",
        add_opaque,
    )

    settle_late = '''    async def settle_late_fenced_result(\n        self, job_id: str, device_id: str, attempt: int\n    ) -> tuple[bool, list[dict[str, Any]]]:\n        def op(conn: sqlite3.Connection) -> tuple[bool, list[dict[str, Any]]]:\n            self._begin(conn)\n            try:\n                row = conn.execute(\n                    "SELECT state FROM push_job_devices WHERE job_id=? AND device_id=?",\n                    (job_id, device_id),\n                ).fetchone()\n                if row is None:\n                    raise StoreNotFound(f"{job_id}/{device_id}")\n                fence = conn.execute(\n                    """\n                    SELECT * FROM push_device_fences WHERE device_id=?\n                      AND blocking_job_id=? AND blocking_attempt=?\n                    """,\n                    (device_id, job_id, attempt),\n                ).fetchone()\n                if row["state"] != DeviceState.UNCONFIRMED.value or fence is None:\n                    snapshots = [self._snapshot(conn, job_id)]\n                    self._commit(conn)\n                    return False, snapshots\n                affected = set(self._fence_visible_job_ids(conn, device_id))\n                affected.add(job_id)\n                conn.execute("DELETE FROM push_device_fences WHERE device_id=?", (device_id,))\n                timestamp = now_ms()\n                for affected_job_id in sorted(affected):\n                    self._increment_revision(conn, affected_job_id, timestamp)\n                snapshots = [self._snapshot(conn, value) for value in sorted(affected)]\n                self._commit(conn)\n                return True, snapshots\n            except BaseException:\n                self._rollback(conn)\n                raise\n\n        return await self._call(op)\n\n'''
    replace_between(
        path,
        "    async def settle_late_fenced_result(\n",
        "    async def mark_reconciling(\n",
        settle_late,
    )

    clear_replacement = '''    async def clear_fence_on_process_replacement(\n        self, device_id: str, new_process_instance_id: str | None, has_job_v1: bool\n    ) -> list[dict[str, Any]]:\n        if not has_job_v1 or not new_process_instance_id:\n            return []\n\n        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:\n            self._begin(conn)\n            try:\n                fence = conn.execute(\n                    "SELECT * FROM push_device_fences WHERE device_id=?", (device_id,)\n                ).fetchone()\n                if fence is None or fence["blocking_process_instance_id"] in {None, new_process_instance_id}:\n                    self._commit(conn)\n                    return []\n                affected = set(self._fence_visible_job_ids(conn, device_id))\n                if fence["blocking_job_id"]:\n                    affected.add(fence["blocking_job_id"])\n                conn.execute("DELETE FROM push_device_fences WHERE device_id=?", (device_id,))\n                timestamp = now_ms()\n                for affected_job_id in sorted(affected):\n                    self._increment_revision(conn, affected_job_id, timestamp)\n                snapshots = [self._snapshot(conn, value) for value in sorted(affected)]\n                self._commit(conn)\n                return snapshots\n            except BaseException:\n                self._rollback(conn)\n                raise\n\n        return await self._call(op)\n\n'''
    replace_between(
        path,
        "    async def clear_fence_on_process_replacement(\n",
        "    async def active_assignment_for_device(\n",
        clear_replacement,
    )

    busy_method = '''    async def handle_busy_rejection(\n        self,\n        job_id: str,\n        device_id: str,\n        attempt: int,\n        active_job: Mapping[str, Any] | None,\n        process_instance_id: str | None,\n    ) -> tuple[str, list[dict[str, Any]]]:\n        """Settle a device_busy response without creating a redispatch loop."""\n\n        def op(conn: sqlite3.Connection) -> tuple[str, list[dict[str, Any]]]:\n            self._begin(conn)\n            try:\n                row = conn.execute(\n                    "SELECT state, attempt FROM push_job_devices WHERE job_id=? AND device_id=?",\n                    (job_id, device_id),\n                ).fetchone()\n                if row is None:\n                    raise StoreNotFound(f"{job_id}/{device_id}")\n                if row["attempt"] != attempt:\n                    raise StoreConflict("stale_attempt")\n                if row["state"] != DeviceState.DISPATCHING.value:\n                    raise StoreConflict(f"device assignment is {row['state']}, expected dispatching")\n\n                active_id = active_job.get("job_id") if isinstance(active_job, Mapping) else None\n                active_attempt = active_job.get("attempt") if isinstance(active_job, Mapping) else None\n                fence = conn.execute(\n                    "SELECT * FROM push_device_fences WHERE device_id=?", (device_id,)\n                ).fetchone()\n                matching_fence = bool(\n                    fence\n                    and isinstance(active_id, str)\n                    and fence["blocking_job_id"] == active_id\n                    and fence["blocking_attempt"] == active_attempt\n                )\n                timestamp = now_ms()\n                if matching_fence:\n                    conn.execute(\n                        """\n                        UPDATE push_job_devices SET state='queued', queue_reason='same_device_job',\n                            accept_deadline=NULL, updated_at=?\n                        WHERE job_id=? AND device_id=?\n                        """,\n                        (timestamp, job_id, device_id),\n                    )\n                    self._increment_revision(conn, job_id, timestamp)\n                    self._rederive_job(conn, job_id, timestamp)\n                    snapshot = self._snapshot(conn, job_id)\n                    self._commit(conn)\n                    return "queued", [snapshot]\n\n                opaque = json.dumps(\n                    dict(active_job) if isinstance(active_job, Mapping) else {"legacy": True},\n                    sort_keys=True,\n                    separators=(",", ":"),\n                )[:4096]\n                affected = set(self._fence_visible_job_ids(conn, device_id))\n                affected.add(job_id)\n                conn.execute(\n                    """\n                    INSERT INTO push_device_fences(\n                        device_id, blocking_opaque_identity, protocol_mode,\n                        blocking_process_instance_id, reason, created_at, updated_at\n                    ) VALUES (?, ?, 'job_v1', ?, 'client_state_conflict', ?, ?)\n                    ON CONFLICT(device_id) DO UPDATE SET\n                        blocking_job_id=NULL, blocking_opaque_identity=excluded.blocking_opaque_identity,\n                        blocking_attempt=NULL, protocol_mode='job_v1',\n                        blocking_process_instance_id=excluded.blocking_process_instance_id,\n                        reason=excluded.reason, updated_at=excluded.updated_at\n                    """,\n                    (device_id, opaque, process_instance_id, timestamp, timestamp),\n                )\n                conn.execute(\n                    """\n                    UPDATE push_job_devices SET state='failed', queue_reason=NULL, updated_at=?, terminal_at=?,\n                        failure_code='client_state_conflict',\n                        failure_detail='client rejected command because an unknown Push/Sync worker is active',\n                        accept_deadline=NULL\n                    WHERE job_id=? AND device_id=?\n                    """,\n                    (timestamp, timestamp, job_id, device_id),\n                )\n                for affected_job_id in sorted(affected):\n                    self._increment_revision(conn, affected_job_id, timestamp)\n                    if affected_job_id == job_id:\n                        self._rederive_job(conn, job_id, timestamp)\n                snapshots = [self._snapshot(conn, value) for value in sorted(affected)]\n                self._commit(conn)\n                return "failed", snapshots\n            except BaseException:\n                self._rollback(conn)\n                raise\n\n        return await self._call(op)\n\n'''
    replace_once(
        path,
        "    async def active_assignment_for_device(self, device_id: str) -> dict[str, Any] | None:\n",
        busy_method + "    async def active_assignment_for_device(self, device_id: str) -> dict[str, Any] | None:\n",
    )

    missing_methods = '''    def reconcile_missing_artifacts_sync(\n        self, missing_artifact_ids: Iterable[str]\n    ) -> list[dict[str, Any]]:\n        missing = tuple(sorted(set(missing_artifact_ids)))\n        if not missing:\n            return []\n\n        def op(conn: sqlite3.Connection) -> list[dict[str, Any]]:\n            self._begin(conn)\n            try:\n                placeholders = ",".join("?" for _ in missing)\n                jobs = conn.execute(\n                    f"SELECT job_id, state FROM push_jobs WHERE artifact_id IN ({placeholders})",\n                    missing,\n                ).fetchall()\n                timestamp = now_ms()\n                touched: set[str] = set()\n                for job in jobs:\n                    job_id = job["job_id"]\n                    state = JobState(job["state"])\n                    if state is JobState.READY:\n                        conn.execute(\n                            """\n                            UPDATE push_jobs SET state='failed', revision=revision+1, updated_at=?,\n                                terminal_at=?, failure_code='artifact_missing',\n                                failure_detail='immutable artifact file is missing at server startup'\n                            WHERE job_id=?\n                            """,\n                            (timestamp, timestamp, job_id),\n                        )\n                        touched.add(job_id)\n                    elif state in {JobState.RUNNING, JobState.RECONCILING}:\n                        conn.execute(\n                            """\n                            UPDATE push_job_devices SET state='failed', queue_reason=NULL, updated_at=?,\n                                terminal_at=?, failure_code='artifact_missing',\n                                failure_detail='immutable artifact file is missing at server startup'\n                            WHERE job_id=? AND state='queued'\n                            """,\n                            (timestamp, timestamp, job_id),\n                        )\n                        self._increment_revision(conn, job_id, timestamp)\n                        self._rederive_job(conn, job_id, timestamp)\n                        touched.add(job_id)\n                snapshots = [self._snapshot(conn, value) for value in sorted(touched)]\n                self._commit(conn)\n                return snapshots\n            except BaseException:\n                self._rollback(conn)\n                raise\n\n        return self._call_sync(op)\n\n    def artifact_records_sync(self) -> list[dict[str, Any]]:\n        return self._call_sync(\n            lambda conn: [dict(row) for row in conn.execute("SELECT * FROM push_artifacts").fetchall()]\n        )\n\n'''
    replace_once(
        path,
        "    async def artifact_record(self, artifact_id: str) -> dict[str, Any] | None:\n",
        missing_methods + "    async def artifact_record(self, artifact_id: str) -> dict[str, Any] | None:\n",
    )


def patch_scheduler() -> None:
    path = "mdm-server/styly_mdm/push_scheduler.py"
    replace_once(
        path,
        "    send_lock: asyncio.Lock\n",
        "    send_lock: asyncio.Lock\n    origin: str = \"\"\n",
    )

    dispatch_method = '''    async def _dispatch_assignment(self, assignment: dict[str, Any]) -> None:\n        job = assignment["job"]\n        job_id = job["job_id"]\n        device_id = assignment["device_id"]\n        attempt = assignment["attempt"]\n        protocol = ProtocolMode(assignment["protocol_mode"])\n        key = TransferKey("push", device_id, job_id, attempt)\n        accept_key = (job_id, device_id, attempt)\n        loop = asyncio.get_running_loop()\n        transfer_future: asyncio.Future[str] = loop.create_future()\n        accept_future: asyncio.Future[tuple[str, dict[str, Any]]] | None = None\n        waiter_registered = False\n\n        try:\n            async with self.transfer_slots():\n                session = self.sessions().get(device_id)\n                if session is None:\n                    await self._fail_before_send(\n                        job_id, device_id, "device_offline_before_dispatch",\n                        "Device went offline before dispatch",\n                    )\n                    return\n                if protocol is ProtocolMode.JOB_V1 and CAP_PUSH_JOB_ID_V1 not in session.capabilities:\n                    await self._fail_before_send(\n                        job_id, device_id, "capability_changed_before_dispatch",\n                        "push_job_id_v1 was not present on the live dispatch session",\n                    )\n                    return\n\n                # Register exact waiters before the canonical dispatching commit.  A\n                # disconnect/result racing with the commit can therefore always release\n                # the resource it owns; a failed commit removes these exact waiters.\n                self.transfer_registry.register(key, transfer_future)\n                waiter_registered = True\n                if protocol is ProtocolMode.JOB_V1:\n                    accept_future = loop.create_future()\n                    self._accept_waiters[accept_key] = accept_future\n\n                accept_deadline = now_ms() + int(self.accept_timeout * 1000)\n                try:\n                    snapshot = await self.store.mark_dispatching(\n                        job_id, device_id, session.capabilities, accept_deadline\n                    )\n                except StoreConflict:\n                    self.wake()\n                    return\n                await self.publish(snapshot)\n\n                command = self._command(snapshot, device_id, protocol, session)\n                try:\n                    # send_lock is stable per device (not per WebSocket session).\n                    # REGISTER/disconnect use the same lock, so ownership cannot change\n                    # between this check and bounded send completion.\n                    async with session.send_lock:\n                        current = self.sessions().get(device_id)\n                        if current is not session or current.session_id != session.session_id:\n                            raise ConnectionError("device WebSocket owner changed before send")\n                        await asyncio.wait_for(\n                            session.ws.send_str(json.dumps(command, separators=(",", ":"))),\n                            self.send_timeout,\n                        )\n                except Exception as exc:\n                    self.transfer_registry.release_exact(key, "command_send_failed")\n                    await self._fail_before_send(\n                        job_id, device_id, "command_send_failed",\n                        f"Could not send EXECUTE_PUSH_FILES: {exc}",\n                    )\n                    return\n\n                if protocol is ProtocolMode.LEGACY:\n                    try:\n                        snapshot = await self.store.transition_device(\n                            job_id, device_id,\n                            expected={DeviceState.DISPATCHING},\n                            target=DeviceState.DOWNLOADING,\n                            fields={"accepted_at": now_ms(), "accept_deadline": None},\n                        )\n                        await self.publish(snapshot)\n                    except StoreConflict:\n                        return\n                else:\n                    assert accept_future is not None\n                    try:\n                        outcome, payload = await asyncio.wait_for(accept_future, self.accept_timeout)\n                    except asyncio.TimeoutError:\n                        if self._accept_waiters.get(accept_key) is accept_future:\n                            self._accept_waiters.pop(accept_key, None)\n                        deadline = now_ms() + int(self.accept_reconciliation_timeout * 1000)\n                        try:\n                            snapshot = await self.store.mark_reconciling(\n                                job_id, device_id,\n                                expected={DeviceState.DISPATCHING},\n                                reason="command_accept_timeout",\n                                deadline=deadline,\n                            )\n                            await self.publish(snapshot)\n                            await self._send_reconcile(session, snapshot, device_id)\n                        except (StoreConflict, ConnectionError, asyncio.TimeoutError):\n                            pass\n                    else:\n                        if outcome == "accepted":\n                            try:\n                                snapshot = await self.store.transition_device(\n                                    job_id, device_id,\n                                    expected={DeviceState.DISPATCHING, DeviceState.RECONCILING},\n                                    target=DeviceState.DOWNLOADING,\n                                    fields={\n                                        "accepted_at": now_ms(),\n                                        "accept_deadline": None,\n                                        "reconciliation_reason": None,\n                                        "reconciliation_deadline": None,\n                                    },\n                                )\n                                await self.publish(snapshot)\n                            except StoreConflict:\n                                pass\n                        elif outcome == "busy":\n                            self.transfer_registry.release_exact(key, "device_busy")\n                            try:\n                                _action, snapshots = await self.store.handle_busy_rejection(\n                                    job_id, device_id, attempt, payload.get("active_job"),\n                                    session.process_instance_id,\n                                )\n                                for changed in snapshots:\n                                    await self.publish(changed)\n                            except StoreConflict:\n                                pass\n                            self.wake()\n                            return\n                        else:\n                            self.transfer_registry.release_exact(key, "rejected")\n                            reason = payload.get("reason") or "command_rejected"\n                            await self._fail_before_send(job_id, device_id, reason, reason)\n                            return\n\n                try:\n                    await asyncio.wait_for(transfer_future, self.transfer_timeout)\n                except asyncio.TimeoutError:\n                    # A transfer timeout only recovers the network resource; device\n                    # execution remains owned and enters bounded reconciliation.\n                    deadline = now_ms() + int(self.reconciliation_timeout * 1000)\n                    active = await self.store.active_assignment_for_device(device_id)\n                    if active and active["job_id"] == job_id:\n                        current = DeviceState(active["state"])\n                        if current in {\n                            DeviceState.DISPATCHING, DeviceState.DOWNLOADING,\n                            DeviceState.VALIDATING, DeviceState.APPLYING,\n                        }:\n                            try:\n                                snapshot = await self.store.mark_reconciling(\n                                    job_id, device_id, expected={current},\n                                    reason="transfer_timeout", deadline=deadline,\n                                )\n                                await self.publish(snapshot)\n                            except StoreConflict:\n                                pass\n        finally:\n            if waiter_registered:\n                self.transfer_registry.remove_if_same(key, transfer_future)\n            if accept_future is not None and self._accept_waiters.get(accept_key) is accept_future:\n                self._accept_waiters.pop(accept_key, None)\n\n'''
    replace_between(
        path,
        "    async def _dispatch_assignment(self, assignment: dict[str, Any]) -> None:\n",
        "    async def _fail_before_send(",
        dispatch_method,
    )

    replace_once(
        path,
        "    def _command(snapshot: dict[str, Any], device_id: str, protocol: ProtocolMode) -> dict[str, Any]:\n",
        "    def _command(\n        snapshot: dict[str, Any], device_id: str, protocol: ProtocolMode, session: LiveSession\n    ) -> dict[str, Any]:\n",
    )
    replace_once(
        path,
        '''        common = {\n            "type": "EXECUTE_PUSH_FILES",\n            "bundle_url": artifact["url"],\n            "bundle_filename": artifact["display_filename"],\n''',
        '''        artifact_url = artifact["url"]\n        if artifact_url.startswith("/"):\n            artifact_url = session.origin.rstrip("/") + artifact_url\n        common = {\n            "type": "EXECUTE_PUSH_FILES",\n            "bundle_url": artifact_url,\n            "bundle_filename": artifact["display_filename"],\n''',
    )
    replace_once(path, '                    "artifact_url": artifact["url"],\n', '                    "artifact_url": artifact_url,\n')


def patch_artifacts() -> None:
    path = "mdm-server/styly_mdm/push_artifacts.py"
    replace_once(
        path,
        '''        fd = os.open(path, flags)\n        try:\n            os.fsync(fd)\n        finally:\n            os.close(fd)\n''',
        '''        try:\n            fd = os.open(path, flags)\n        except OSError:\n            # Windows does not support opening directories for fsync.  File fsync\n            # and atomic replace still provide the strongest available guarantee.\n            if os.name == "nt":\n                return\n            raise\n        try:\n            os.fsync(fd)\n        finally:\n            os.close(fd)\n''',
    )


def patch_runtime() -> None:
    path = "mdm-server/styly_mdm/push_runtime.py"
    replace_once(
        path,
        "    _push_disconnect_notified: bool = False\n",
        "    _push_disconnect_notified: bool = False\n    _push_origin: str = \"\"\n",
    )
    replace_once(
        path,
        "        self._push_path = request.path\n",
        "        self._push_path = request.path\n        self._push_origin = f\"{request.scheme}://{request.host}\"\n",
    )
    replace_once(
        path,
        "        self.sessions: dict[str, LiveSession] = {}\n",
        "        self.sessions: dict[str, LiveSession] = {}\n        self._session_locks: dict[str, asyncio.Lock] = {}\n",
    )

    # Reconcile DB references against immutable files before accepting traffic.
    replace_once(
        path,
        '''        for job_id in cleanup_jobs:\n            self.artifacts.cleanup_work_best_effort(job_id)\n''',
        '''        for job_id in cleanup_jobs:\n            self.artifacts.cleanup_work_best_effort(job_id)\n        missing_artifacts = [\n            record["artifact_id"]\n            for record in self.store.artifact_records_sync()\n            if not self.artifacts.path_for_record(record).is_file()\n        ]\n        self._startup_snapshots.extend(\n            self.store.reconcile_missing_artifacts_sync(missing_artifacts)\n        )\n''',
    )

    # Job-owned upload writes must not block aiohttp's event loop.
    replace_once(
        path,
        "                        output.write(chunk)\n                    output.flush()\n                    os.fsync(output.fileno())\n",
        "                        await asyncio.to_thread(output.write, chunk)\n                    await asyncio.to_thread(output.flush)\n                    await asyncio.to_thread(os.fsync, output.fileno())\n",
    )

    register_method = '''    async def register_device(self, ws: RuntimeWebSocketResponse, payload: dict[str, Any]) -> None:\n        device_id = payload["device_id"]\n        lock = self._session_locks.setdefault(device_id, asyncio.Lock())\n        async with lock:\n            capabilities = parse_capabilities(payload.get("capabilities"))\n            process_instance_id = payload.get("process_instance_id")\n            if not isinstance(process_instance_id, str) or not process_instance_id:\n                process_instance_id = None\n            session = LiveSession(\n                device_id=device_id,\n                session_id=str(uuid.uuid4()),\n                ws=ws,\n                capabilities=capabilities,\n                process_instance_id=process_instance_id,\n                send_lock=lock,\n                origin=ws._push_origin,\n            )\n            self.sessions[device_id] = session\n            await _ORIGINAL_WS_RESPONSE.send_str(\n                ws, json.dumps({"type": "REGISTERED", "session_id": session.session_id})\n            )\n        for snapshot in await self.store.clear_fence_on_process_replacement(\n            device_id, process_instance_id, CAP_PUSH_JOB_ID_V1 in capabilities\n        ):\n            await self.publish(snapshot)\n        runtime = payload.get("push_runtime")\n        active = runtime.get("active") if isinstance(runtime, dict) else None\n        assignment = await self.store.active_assignment_for_device(device_id)\n        if assignment is not None and assignment["state"] == DeviceState.RECONCILING.value:\n            if isinstance(active, dict) and active.get("job_id") == assignment["job_id"] and active.get("attempt") == 1:\n                try:\n                    _status, snapshots = await self.store.reconcile_report(\n                        assignment["job_id"],\n                        device_id,\n                        1,\n                        "active",\n                        active.get("phase"),\n                        None,\n                    )\n                    for snapshot in snapshots:\n                        await self.publish(snapshot)\n                except StoreConflict:\n                    pass\n            else:\n                await self._send_reconcile_for_assignment(session, assignment)\n        elif isinstance(active, dict) and isinstance(active.get("job_id"), str):\n            opaque = json.dumps(\n                {\n                    "job_id": active.get("job_id"),\n                    "attempt": active.get("attempt"),\n                    "artifact_id": active.get("artifact_id"),\n                },\n                sort_keys=True,\n                separators=(",", ":"),\n            )\n            for snapshot in await self.store.add_opaque_fence(\n                device_id,\n                opaque,\n                ProtocolMode.JOB_V1,\n                process_instance_id,\n                "client_reported_unknown_active_job",\n            ):\n                await self.publish(snapshot)\n        if self.scheduler is not None:\n            self.scheduler.wake()\n\n'''
    replace_between(
        path,
        "    async def register_device(self, ws: RuntimeWebSocketResponse, payload: dict[str, Any]) -> None:\n",
        "    async def device_disconnected(",
        register_method,
    )

    disconnect_method = '''    async def device_disconnected(self, device_id: str, ws: RuntimeWebSocketResponse) -> None:\n        lock = self._session_locks.setdefault(device_id, asyncio.Lock())\n        async with lock:\n            session = self.sessions.get(device_id)\n            if session is None or session.ws is not ws:\n                return\n            del self.sessions[device_id]\n        assignment = await self.store.active_assignment_for_device(device_id)\n        if assignment is None:\n            return\n        key = TransferKey("push", device_id, assignment["job_id"], assignment["attempt"])\n        self.transfers.release_exact(key, "disconnect")\n        current = DeviceState(assignment["state"])\n        if current in {\n            DeviceState.DISPATCHING,\n            DeviceState.DOWNLOADING,\n            DeviceState.VALIDATING,\n            DeviceState.APPLYING,\n        }:\n            timeout = (\n                float(os.environ.get("MDM_PUSH_ACCEPT_RECONCILIATION_TIMEOUT", "60"))\n                if current is DeviceState.DISPATCHING\n                else float(os.environ.get("MDM_PUSH_RECONCILIATION_TIMEOUT", "1800"))\n            )\n            try:\n                snapshot = await self.store.mark_reconciling(\n                    assignment["job_id"],\n                    device_id,\n                    expected={current},\n                    reason="device_disconnected",\n                    deadline=now_ms() + int(timeout * 1000),\n                )\n                await self.publish(snapshot)\n            except StoreConflict:\n                pass\n\n'''
    replace_between(
        path,
        "    async def device_disconnected(self, device_id: str, ws: RuntimeWebSocketResponse) -> None:\n",
        "    async def handle_admin_message(",
        disconnect_method,
    )

    replace_once(
        path,
        '''        status = "success" if payload.get("status") == "success" else "fail"\n''',
        '''        raw_status = payload.get("status")\n        status = raw_status if raw_status in {"success", "interrupted"} else "fail"\n''',
    )
    replace_once(
        path,
        '''            accepted, snapshot = await self.store.settle_late_fenced_result(job_id, device_id, 1)\n            reason = None if accepted else "stale_result"\n''',
        '''            accepted, snapshots = await self.store.settle_late_fenced_result(job_id, device_id, 1)\n            snapshot = next(\n                (value for value in snapshots if value["job_id"] == job_id),\n                await self.store.get_snapshot(job_id),\n            )\n            for changed in snapshots:\n                await self.publish(changed)\n            reason = None if accepted else "stale_result"\n''',
    )
    replace_once(
        path,
        '''        if accepted:\n            await self.publish(snapshot)\n            if self.scheduler is not None:\n                self.scheduler.wake()\n''',
        '''        if accepted:\n            if device_snapshot is None or device_snapshot["state"] != DeviceState.UNCONFIRMED.value:\n                await self.publish(snapshot)\n            if self.scheduler is not None:\n                self.scheduler.wake()\n''',
    )

    replace_once(
        path,
        '''                    snapshot = await self.store.mark_unconfirmed(\n                        row["job_id"],\n                        row["device_id"],\n                        session.process_instance_id if session else None,\n                        "reconciliation deadline elapsed without matching evidence",\n                    )\n''',
        '''                    snapshots = await self.store.mark_unconfirmed(\n                        row["job_id"],\n                        row["device_id"],\n                        session.process_instance_id if session else None,\n                        "reconciliation deadline elapsed without matching evidence",\n                    )\n''',
    )
    replace_once(
        path,
        '''                    await self.publish(snapshot)\n                    if self.scheduler is not None:\n                        self.scheduler.wake()\n''',
        '''                    for snapshot in snapshots:\n                        await self.publish(snapshot)\n                    if self.scheduler is not None:\n                        self.scheduler.wake()\n''',
    )

    # start_upload may commit an expiry before raising; publish/cleanup that canonical
    # transition even though the second upload request receives HTTP 409.
    replace_once(
        path,
        '''        except StoreConflict as exc:\n            return aiohttp_web.json_response({"error": str(exc)}, status=409)\n        except asyncio.CancelledError:\n''',
        '''        except StoreConflict as exc:\n            if not started:\n                try:\n                    expired = await self.store.get_snapshot(job_id)\n                    if expired["state"] == JobState.INTERRUPTED.value:\n                        await self.publish(expired)\n                        await asyncio.to_thread(self.artifacts.cleanup_work_best_effort, job_id)\n                except (StoreNotFound, ValueError):\n                    pass\n            return aiohttp_web.json_response({"error": str(exc)}, status=409)\n        except asyncio.CancelledError:\n''',
    )


def patch_client_protocol() -> None:
    path = "mdm-client/app/src/main/java/com/styly/mdmclient/PushProtocol.kt"
    replace_once(path, "import java.util.UUID\n", "import java.net.URI\nimport java.security.MessageDigest\nimport java.util.UUID\n")
    replace_once(
        path,
        '''        if (artifactUrl.isBlank()) throw IllegalArgumentException("malformed_command: artifact_url is required")\n        val destPath = payload.optString("dest_path", "").trim()\n        if (destPath.isBlank()) throw IllegalArgumentException("invalid_destination: destination is required")\n''',
        '''        if (artifactUrl.isBlank()) throw IllegalArgumentException("malformed_command: artifact_url is required")\n        if (jobId != null) {\n            val uri = try { URI(artifactUrl) } catch (_: Exception) { null }\n            if (uri == null || !uri.isAbsolute || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {\n                throw IllegalArgumentException("malformed_command: artifact_url must be an absolute HTTP(S) URL")\n            }\n        }\n        val destPath = canonicalDestination(payload.optString("dest_path", ""))\n''',
    )
    replace_once(
        path,
        '''            artifactSha256 = payload.optString("artifact_sha256", "").ifBlank { null },\n''',
        '''            artifactSha256 = payload.optString("artifact_sha256", "").ifBlank { null }.also { value ->\n                if (jobId != null && (value == null || value.length != 64 || value.any { !it.isDigit() && it.lowercaseChar() !in 'a'..'f' })) {\n                    throw IllegalArgumentException("malformed_command: artifact_sha256 must be 64 hexadecimal characters")\n                }\n            },\n''',
    )
    insert = r'''
    private fun canonicalDestination(value: String): String {
        val text = value.trim().replace('\\', '/')
        if (text.isEmpty() || '\u0000' in text || !text.startsWith('/')) {
            throw IllegalArgumentException("invalid_destination: destination must be an absolute shared-storage path")
        }
        val rawParts = text.split('/').filter { it.isNotEmpty() }
        if (rawParts.any { it == ".." }) {
            throw IllegalArgumentException("invalid_destination: '..' is not allowed")
        }
        val normalizedParts = rawParts.filter { it != "." }
        val prefixLength = when {
            normalizedParts.firstOrNull() == "sdcard" -> 1
            normalizedParts.size >= 3 && normalizedParts.take(3) == listOf("storage", "emulated", "0") -> 3
            else -> throw IllegalArgumentException("invalid_destination: destination must be under shared storage")
        }
        val remainder = normalizedParts.drop(prefixLength)
        if (remainder.isEmpty()) throw IllegalArgumentException("invalid_destination: shared-storage root is forbidden")
        val protected = setOf(
            "android", "download", "downloads", "dcim", "pictures", "movies", "music",
            "documents", "alarms", "notifications", "podcasts", "ringtones"
        )
        if (remainder.first().lowercase() in protected) {
            throw IllegalArgumentException("invalid_destination: protected top-level directory")
        }
        return "/sdcard/" + remainder.joinToString("/")
    }
'''
    replace_once(path, "\n    fun commandFromJson(json: JSONObject): Command = Command(\n", insert + "\n    fun commandFromJson(json: JSONObject): Command = Command(\n")


def patch_client_worker() -> None:
    path = "mdm-client/app/src/main/java/com/styly/mdmclient/PushFilesWorker.kt"
    replace_once(
        path,
        "class PushFilesWorker {\n",
        "class PushFilesWorker {\n    companion object {\n        private const val MAX_ENTRIES = 5000\n        private const val MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024\n    }\n",
    )
    replace_once(
        path,
        '''        val seen = HashSet<String>()\n        val kinds = HashMap<String, Boolean>() // true=directory\n        ZipFile(bundle).use { zip ->\n            val entries = zip.entries()\n            while (entries.hasMoreElements()) {\n                val entry = entries.nextElement()\n                val normalized = entry.name.replace('\\\\', '/').trimStart('/')\n''',
        '''        val seen = HashSet<String>()\n        val kinds = HashMap<String, Boolean>() // true=directory\n        var entryCount = 0\n        var extractedBytes = 0L\n        ZipFile(bundle).use { zip ->\n            val entries = zip.entries()\n            while (entries.hasMoreElements()) {\n                val entry = entries.nextElement()\n                entryCount += 1\n                if (entryCount > MAX_ENTRIES) throw IllegalStateException("validation_failed: too many ZIP entries")\n                val raw = entry.name.replace('\\\\', '/')\n                if (raw.startsWith('/')) throw IllegalStateException("validation_failed: absolute ZIP path")\n                val normalized = raw\n''',
    )
    replace_once(
        path,
        '''                        FileOutputStream(target).use { output -> input.copyTo(output) }\n''',
        '''                        FileOutputStream(target).use { output ->\n                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)\n                            while (true) {\n                                val read = input.read(buffer)\n                                if (read < 0) break\n                                extractedBytes += read\n                                if (extractedBytes > MAX_EXTRACTED_BYTES) {\n                                    throw IllegalStateException("validation_failed: extracted size limit exceeded")\n                                }\n                                output.write(buffer, 0, read)\n                            }\n                            output.fd.sync()\n                        }\n''',
    )


def patch_client_coordinator() -> None:
    path = "mdm-client/app/src/main/java/com/styly/mdmclient/PushJobCoordinator.kt"
    replace_once(
        path,
        '''    init {\n        actor.execute {\n            state = recover(store.load())\n            gate.restore(state.active?.command)\n            persist()\n        }\n    }\n''',
        '''    init {\n        // Load/recover the small durable record before the first REGISTER can be\n        // built.  Otherwise a fast WebSocket connection could advertise active=null\n        // while recovery was still queued on the actor.\n        state = recover(store.load())\n        gate.restore(state.active?.command)\n        persist()\n    }\n''',
    )
    replace_once(
        path,
        '''            "fail",\n            active.command.destPath,\n''',
        '''            "interrupted",\n            active.command.destPath,\n''',
    )


def patch_tests() -> None:
    store_test = "mdm-server/tests/test_push_job_store.py"
    replace_once(
        store_test,
        '''    terminal = await store.mark_unconfirmed(job_id, "D1", "process-a", "timeout")\n    assert terminal["state"] == JobState.COMPLETED_WITH_ERRORS.value\n    assert terminal["devices"]["D1"]["device_fence"] is not None\n    accepted, after = await store.settle_late_fenced_result(job_id, "D1", 1)\n    assert accepted\n    assert after["state"] == JobState.COMPLETED_WITH_ERRORS.value\n    assert after["devices"]["D1"]["state"] == DeviceState.UNCONFIRMED.value\n    assert after["devices"]["D1"]["device_fence"] is None\n''',
        '''    terminal_snapshots = await store.mark_unconfirmed(job_id, "D1", "process-a", "timeout")\n    terminal = next(item for item in terminal_snapshots if item["job_id"] == job_id)\n    assert terminal["state"] == JobState.COMPLETED_WITH_ERRORS.value\n    assert terminal["devices"]["D1"]["device_fence"] is not None\n    accepted, after_snapshots = await store.settle_late_fenced_result(job_id, "D1", 1)\n    after = next(item for item in after_snapshots if item["job_id"] == job_id)\n    assert accepted\n    assert after["state"] == JobState.COMPLETED_WITH_ERRORS.value\n    assert after["devices"]["D1"]["state"] == DeviceState.UNCONFIRMED.value\n    assert after["devices"]["D1"]["device_fence"] is None\n''',
    )
    replace_once(
        store_test,
        '    await store.mark_unconfirmed(job_id, "D1", "process-a", "timeout")\n    assert await store.clear_fence_on_process_replacement("D1", "process-a", True) == []\n',
        '    await store.mark_unconfirmed(job_id, "D1", "process-a", "timeout")\n    assert await store.clear_fence_on_process_replacement("D1", "process-a", True) == []\n',
    )
    replace_once(
        store_test,
        '''    terminal = await store.mark_unconfirmed(job_id, "D1", "process-a", "timeout")\n    revision = terminal["revision"]\n''',
        '''    terminal_snapshots = await store.mark_unconfirmed(job_id, "D1", "process-a", "timeout")\n    terminal = next(item for item in terminal_snapshots if item["job_id"] == job_id)\n    revision = terminal["revision"]\n''',
    )

    extra_store_tests = r'''

@pytest.mark.asyncio
async def test_restart_paused_oldest_job_blocks_later_same_device_job(store):
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}

    async def ready_running():
        _, job = await store.create_job(canonical(), protocols, 60_000)
        job_id = job["job_id"]
        await store.start_upload(job_id)
        await store.mark_packaging(job_id, 1, 1)
        await store.publish_artifact(job_id, {
            "artifact_id": str(uuid.uuid4()), "storage_name": str(uuid.uuid4()) + ".zip",
            "display_filename": "x.zip", "byte_size": 1, "sha256": "a" * 64, "entry_count": 1,
        })
        await store.enable_dispatch(job_id)
        return job_id

    first = await ready_running()
    second = await ready_running()
    # Simulate restart's dispatch gate without changing the persisted queue order.
    await store._call(lambda conn: conn.execute(
        "UPDATE push_jobs SET dispatch_enabled=0, dispatch_paused_reason='server_restart' WHERE job_id=?",
        (first,),
    ))
    assert await store.claim_next(["D1"]) is None
    await store.enable_dispatch(first)
    claimed = await store.claim_next(["D1"])
    assert claimed["job"]["job_id"] == first
    assert claimed["job"]["job_id"] != second


@pytest.mark.asyncio
async def test_accept_timeout_absent_requeues_same_attempt_once(store):
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}
    _, job = await store.create_job(canonical(), protocols, 60_000)
    job_id = job["job_id"]
    await store.start_upload(job_id)
    await store.mark_packaging(job_id, 1, 1)
    await store.publish_artifact(job_id, {
        "artifact_id": str(uuid.uuid4()), "storage_name": str(uuid.uuid4()) + ".zip",
        "display_filename": "x.zip", "byte_size": 1, "sha256": "a" * 64, "entry_count": 1,
    })
    await store.enable_dispatch(job_id)
    await store.claim_next(["D1"])
    await store.mark_dispatching(job_id, "D1", {"push_job_id_v1"}, now_ms() + 1_000)
    await store.mark_reconciling(
        job_id, "D1", expected={DeviceState.DISPATCHING},
        reason="command_accept_timeout", deadline=now_ms() + 60_000,
    )
    outcome, snapshots = await store.reconcile_report(job_id, "D1", 1, "absent", None, None)
    assert outcome == "requeued"
    current = snapshots[0]
    assert current["devices"]["D1"]["state"] == DeviceState.QUEUED.value
    assert current["devices"]["D1"]["attempt"] == 1

    await store.claim_next(["D1"])
    await store.mark_dispatching(job_id, "D1", {"push_job_id_v1"}, now_ms() + 1_000)
    await store.mark_reconciling(
        job_id, "D1", expected={DeviceState.DISPATCHING},
        reason="command_accept_timeout", deadline=now_ms() + 60_000,
    )
    outcome, snapshots = await store.reconcile_report(job_id, "D1", 1, "absent", None, None)
    assert outcome == "absent"
    assert snapshots[0]["devices"]["D1"]["state"] == DeviceState.INTERRUPTED.value


@pytest.mark.asyncio
async def test_restart_pre_accept_absent_never_requeues(store):
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}
    _, job = await store.create_job(canonical(), protocols, 60_000)
    job_id = job["job_id"]
    await store.start_upload(job_id)
    await store.mark_packaging(job_id, 1, 1)
    await store.publish_artifact(job_id, {
        "artifact_id": str(uuid.uuid4()), "storage_name": str(uuid.uuid4()) + ".zip",
        "display_filename": "x.zip", "byte_size": 1, "sha256": "a" * 64, "entry_count": 1,
    })
    await store.enable_dispatch(job_id)
    await store.claim_next(["D1"])
    await store.mark_dispatching(job_id, "D1", {"push_job_id_v1"}, now_ms() + 1_000)
    await store.mark_reconciling(
        job_id, "D1", expected={DeviceState.DISPATCHING},
        reason="server_restart_before_accept", deadline=now_ms() + 60_000,
    )
    outcome, snapshots = await store.reconcile_report(
        job_id, "D1", 1, "absent", None, None, from_server_restart=True,
    )
    assert outcome == "absent"
    assert snapshots[0]["devices"]["D1"]["state"] == DeviceState.INTERRUPTED.value


@pytest.mark.asyncio
async def test_interrupted_terminal_result_is_not_collapsed_to_failed(store):
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}
    _, job = await store.create_job(canonical(), protocols, 60_000)
    job_id = job["job_id"]
    await store.start_upload(job_id)
    await store.mark_packaging(job_id, 1, 1)
    await store.publish_artifact(job_id, {
        "artifact_id": str(uuid.uuid4()), "storage_name": str(uuid.uuid4()) + ".zip",
        "display_filename": "x.zip", "byte_size": 1, "sha256": "a" * 64, "entry_count": 1,
    })
    await store.enable_dispatch(job_id)
    await store.claim_next(["D1"])
    await store.mark_dispatching(job_id, "D1", {"push_job_id_v1"}, now_ms() + 1_000)
    accepted, reason, snapshot = await store.settle_result(job_id, "D1", 1, "interrupted")
    assert accepted and reason is None
    assert snapshot["devices"]["D1"]["state"] == DeviceState.INTERRUPTED.value


@pytest.mark.asyncio
async def test_unknown_busy_response_fails_assignment_and_persists_fence(store):
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}
    _, job = await store.create_job(canonical(), protocols, 60_000)
    job_id = job["job_id"]
    await store.start_upload(job_id)
    await store.mark_packaging(job_id, 1, 1)
    await store.publish_artifact(job_id, {
        "artifact_id": str(uuid.uuid4()), "storage_name": str(uuid.uuid4()) + ".zip",
        "display_filename": "x.zip", "byte_size": 1, "sha256": "a" * 64, "entry_count": 1,
    })
    await store.enable_dispatch(job_id)
    await store.claim_next(["D1"])
    await store.mark_dispatching(job_id, "D1", {"push_job_id_v1"}, now_ms() + 1_000)
    action, snapshots = await store.handle_busy_rejection(
        job_id, "D1", 1,
        {"job_id": str(uuid.uuid4()), "attempt": 1}, "process-a",
    )
    assert action == "failed"
    current = next(item for item in snapshots if item["job_id"] == job_id)
    assert current["devices"]["D1"]["state"] == DeviceState.FAILED.value
    assert current["devices"]["D1"]["device_fence"] is not None
'''
    with (ROOT / store_test).open("a", encoding="utf-8") as handle:
        handle.write(extra_store_tests)

    protocol_test = "mdm-client/app/src/test/java/com/styly/mdmclient/PushProtocolTest.kt"
    replace_once(
        protocol_test,
        '            put("artifact_size", 42)\n            put("dest_path", "/sdcard/STYLY/content")\n',
        '            put("artifact_size", 42)\n            put("artifact_sha256", "a".repeat(64))\n            put("dest_path", "/sdcard/STYLY/content")\n',
    )
    replace_once(
        protocol_test,
        "import org.junit.Assert.assertThrows\n",
        "import org.junit.Assert.assertThrows\nimport org.junit.Assert.assertTrue\n",
    )
    extra_protocol_tests = r'''

    @Test
    fun `destination is canonicalized and destructive roots are rejected`() {
        val base = JSONObject().apply {
            put("job_id", UUID.randomUUID().toString())
            put("attempt", 1)
            put("artifact_id", UUID.randomUUID().toString())
            put("artifact_url", "http://192.0.2.1:7070/artifacts/example")
            put("artifact_size", 42)
            put("artifact_sha256", "a".repeat(64))
            put("dest_path", "/storage/emulated/0/STYLY/./content")
        }
        assertEquals("/sdcard/STYLY/content", PushProtocol.parseCommand(base).destPath)
        base.put("dest_path", "/sdcard/Download")
        assertThrows(IllegalArgumentException::class.java) { PushProtocol.parseCommand(base) }
        base.put("dest_path", "/sdcard/STYLY/../Download")
        assertThrows(IllegalArgumentException::class.java) { PushProtocol.parseCommand(base) }
    }

    @Test
    fun `job v1 rejects relative artifact URL and missing sha256`() {
        val payload = JSONObject().apply {
            put("job_id", UUID.randomUUID().toString())
            put("attempt", 1)
            put("artifact_id", UUID.randomUUID().toString())
            put("artifact_url", "/artifacts/example")
            put("artifact_size", 42)
            put("artifact_sha256", "a".repeat(64))
            put("dest_path", "/sdcard/STYLY/content")
        }
        assertThrows(IllegalArgumentException::class.java) { PushProtocol.parseCommand(payload) }
        payload.put("artifact_url", "http://192.0.2.1:7070/artifacts/example")
        payload.remove("artifact_sha256")
        val error = assertThrows(IllegalArgumentException::class.java) { PushProtocol.parseCommand(payload) }
        assertTrue(error.message!!.contains("artifact_sha256"))
    }
'''
    text = read(protocol_test)
    if not text.endswith("}\n"):
        raise RuntimeError("unexpected PushProtocolTest.kt ending")
    write(protocol_test, text[:-2] + extra_protocol_tests + "}\n")

    scheduler_test = "mdm-server/tests/test_push_job_scheduler.py"
    write(
        scheduler_test,
        '''from types import SimpleNamespace\n\nfrom styly_mdm.push_jobs import ProtocolMode\nfrom styly_mdm.push_scheduler import LiveSession, PushScheduler\n\n\ndef test_command_uses_device_visible_absolute_artifact_url():\n    session = LiveSession(\n        device_id="D1", session_id="s", ws=object(),\n        capabilities=frozenset({"push_job_id_v1"}),\n        process_instance_id="p", send_lock=SimpleNamespace(),\n        origin="http://192.0.2.10:7070",\n    )\n    snapshot = {\n        "job_id": "00000000-0000-4000-8000-000000000001",\n        "revision": 3,\n        "dest_path": "/sdcard/STYLY/content",\n        "mode": "push",\n        "artifact": {\n            "artifact_id": "00000000-0000-4000-8000-000000000002",\n            "url": "/artifacts/00000000-0000-4000-8000-000000000002",\n            "display_filename": "x.zip", "byte_size": 1, "sha256": "a" * 64,\n        },\n        "devices": {"D1": {"attempt": 1}},\n    }\n    command = PushScheduler._command(snapshot, "D1", ProtocolMode.JOB_V1, session)\n    assert command["artifact_url"] == (\n        "http://192.0.2.10:7070/artifacts/00000000-0000-4000-8000-000000000002"\n    )\n    assert command["bundle_url"] == command["artifact_url"]\n''',
    )


def patch_docs() -> None:
    path = "docs/PUSH_JOBS.md"
    addition = '''\n## Correctness hardening verified in implementation\n\nThe implementation additionally enforces the following details from the canonical design:\n\n- Python 3.10 remains supported; no Python 3.11-only enum API is required.\n- The oldest active per-device queue entry cannot be bypassed while restart dispatch is paused.\n- A pre-accept `absent` report replays the exact attempt at most once; restart-originated ambiguity never auto-requeues.\n- Transfer and command-accept waiters are registered before `dispatching` is committed.\n- Device WebSocket replacement, disconnect, and command send share one stable per-device ownership lock.\n- Artifact URLs sent to Android are absolute URLs derived from the device-visible WebSocket origin.\n- Unknown `device_busy` identities persist a fence instead of entering an unbounded redispatch loop.\n- Client-side destination, artifact URL, ZIP path/count/extracted-size checks run before apply.\n- Client process restart is preserved as `interrupted`, not collapsed into a generic apply failure.\n- Startup detects SQLite artifact references whose immutable file is missing.\n'''
    with (ROOT / path).open("a", encoding="utf-8") as handle:
        handle.write(addition)


def apply() -> None:
    patch_python_domain()
    patch_store()
    patch_scheduler()
    patch_artifacts()
    patch_runtime()
    patch_client_protocol()
    patch_client_worker()
    patch_client_coordinator()
    patch_tests()
    patch_docs()


def cleanup() -> None:
    for relative in (".github/issue91_fix_and_verify.py", ".github/workflows/issue91-fix-verify.yml"):
        path = ROOT / relative
        if path.exists():
            path.unlink()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("apply", "cleanup"))
    args = parser.parse_args()
    if args.mode == "apply":
        apply()
    else:
        cleanup()


if __name__ == "__main__":
    main()
