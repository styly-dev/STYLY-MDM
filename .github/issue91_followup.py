#!/usr/bin/env python3
"""Apply the final Issue #91 recovery/idempotency hardening pass.

Temporary verification helper.  The companion workflow removes this file and
itself before committing the verified source tree.
"""
from __future__ import annotations

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
        raise RuntimeError(f"{path}: expected one occurrence, found {count}: {old[:140]!r}")
    write(path, text.replace(old, new, 1))


def insert_before(path: str, marker: str, addition: str) -> None:
    text = read(path)
    if marker not in text:
        raise RuntimeError(f"{path}: marker not found: {marker!r}")
    if addition.strip() in text:
        return
    write(path, text.replace(marker, addition + marker, 1))


def patch_domain() -> None:
    path = "mdm-server/styly_mdm/push_jobs.py"
    replace_once(
        path,
        """            DeviceState.QUEUED,
            DeviceState.DOWNLOADING,
            DeviceState.RECONCILING,
            DeviceState.FAILED,
""",
        """            DeviceState.QUEUED,
            DeviceState.DOWNLOADING,
            # Exact duplicate command acceptance can report that the durable
            # client worker has already advanced beyond download.  These direct
            # forward transitions are recovery-only and never start new work.
            DeviceState.VALIDATING,
            DeviceState.APPLYING,
            DeviceState.RECONCILING,
            DeviceState.FAILED,
""",
    )


def patch_store() -> None:
    path = "mdm-server/styly_mdm/push_job_store.py"

    replace_once(
        path,
        '                    "protocol_mode": fence["protocol_mode"],\n                    "reason": fence["reason"],\n',
        '                    "protocol_mode": fence["protocol_mode"],\n'
        '                    "blocking_process_instance_id": fence["blocking_process_instance_id"],\n'
        '                    "reason": fence["reason"],\n',
    )

    lookup = '''    async def lookup_create_request(
        self, client_request_id: str, request_fingerprint: str
    ) -> dict[str, Any] | None:
        """Return an idempotent create replay before rechecking live targets.

        Live session/capability state is deliberately not part of request identity.
        A response-loss retry must therefore return the accepted job even if a
        target disconnected after the original transaction committed.
        """

        def op(conn: sqlite3.Connection) -> dict[str, Any] | None:
            row = conn.execute(
                "SELECT job_id, request_fingerprint FROM push_jobs WHERE client_request_id=?",
                (client_request_id,),
            ).fetchone()
            if row is None:
                return None
            if row["request_fingerprint"] != request_fingerprint:
                raise StoreConflict("client_request_id was already used for a different request")
            return self._snapshot(conn, row["job_id"])

        return await self._call(op)

'''
    insert_before(path, "    async def create_job(\n", lookup)

    replace_once(
        path,
        '''            terminal = conn.execute(
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
''',
        '''            fenced = conn.execute(
                """
                SELECT DISTINCT blocking_job_id AS job_id
                FROM push_device_fences
                WHERE blocking_job_id IS NOT NULL
                ORDER BY blocking_job_id
                """
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
            ids.extend(row["job_id"] for row in fenced if row["job_id"] not in ids)
            ids.extend(row["job_id"] for row in terminal if row["job_id"] not in ids)
            return [self._snapshot(conn, job_id) for job_id in ids]
''',
    )

    replace_once(
        path,
        '''                if fence is None or fence["blocking_process_instance_id"] in {None, new_process_instance_id}:
                    self._commit(conn)
                    return []
''',
        '''                if fence is None:
                    self._commit(conn)
                    return []
                previous_process = fence["blocking_process_instance_id"]
                legacy_upgrade = (
                    fence["protocol_mode"] == ProtocolMode.LEGACY.value
                    and previous_process is None
                )
                if not legacy_upgrade and previous_process in {None, new_process_instance_id}:
                    self._commit(conn)
                    return []
''',
    )

    fence_lookup = '''    async def fence_for_device(self, device_id: str) -> dict[str, Any] | None:
        def op(conn: sqlite3.Connection) -> dict[str, Any] | None:
            row = conn.execute(
                """
                SELECT f.*, j.artifact_id
                FROM push_device_fences f
                LEFT JOIN push_jobs j ON j.job_id=f.blocking_job_id
                WHERE f.device_id=?
                """,
                (device_id,),
            ).fetchone()
            return dict(row) if row else None

        return await self._call(op)

'''
    insert_before(path, "    async def active_assignment_for_device(self, device_id: str)", fence_lookup)

    replace_once(
        path,
        '''                SELECT d.*, j.artifact_id, j.mode, j.dest_path
                FROM push_job_devices d JOIN push_jobs j ON j.job_id=d.job_id
''',
        '''                SELECT d.*, j.artifact_id, j.mode, j.dest_path,
                       a.byte_size AS artifact_size, a.sha256 AS artifact_sha256
                FROM push_job_devices d
                JOIN push_jobs j ON j.job_id=d.job_id
                LEFT JOIN push_artifacts a ON a.artifact_id=j.artifact_id
''',
    )


def patch_scheduler() -> None:
    path = "mdm-server/styly_mdm/push_scheduler.py"
    replace_once(
        path,
        '''                        if outcome == "accepted":
                            try:
                                snapshot = await self.store.transition_device(
                                    job_id, device_id,
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
''',
        '''                        if outcome == "accepted":
                            target = self._accepted_phase(payload)
                            if target is None:
                                self.transfer_registry.release_exact(key, "malformed_accept_phase")
                                await self._fail_before_send(
                                    job_id,
                                    device_id,
                                    "client_state_conflict",
                                    "PUSH_JOB_ACCEPTED carried an invalid phase",
                                )
                                return
                            timestamp = now_ms()
                            fields: dict[str, Any] = {
                                "accepted_at": timestamp,
                                "accept_deadline": None,
                                "reconciliation_reason": None,
                                "reconciliation_deadline": None,
                            }
                            if target in {DeviceState.VALIDATING, DeviceState.APPLYING}:
                                fields.update(
                                    {
                                        "transfer_completed_at": timestamp,
                                        "validation_started_at": timestamp,
                                    }
                                )
                            if target is DeviceState.APPLYING:
                                fields.update(
                                    {
                                        "validation_completed_at": timestamp,
                                        "apply_started_at": timestamp,
                                    }
                                )
                            try:
                                snapshot = await self.store.transition_device(
                                    job_id,
                                    device_id,
                                    expected={DeviceState.DISPATCHING, DeviceState.RECONCILING},
                                    target=target,
                                    fields=fields,
                                )
                                await self.publish(snapshot)
                                if target is not DeviceState.DOWNLOADING:
                                    # The duplicate-safe client report proves the
                                    # network phase already ended; do not retain a
                                    # transfer slot while validation/apply continues.
                                    self.transfer_registry.release_exact(key, "accepted_advanced_phase")
                            except StoreConflict:
                                pass
''',
    )

    helper = '''    @staticmethod
    def _accepted_phase(payload: Mapping[str, Any]) -> DeviceState | None:
        return {
            "downloading": DeviceState.DOWNLOADING,
            "validating": DeviceState.VALIDATING,
            "applying": DeviceState.APPLYING,
        }.get(payload.get("phase"))

'''
    insert_before(path, "    @staticmethod\n    def _command(\n", helper)


def patch_runtime() -> None:
    path = "mdm-server/styly_mdm/push_runtime.py"

    helpers = '''    @staticmethod
    def _create_response(snapshot: Mapping[str, Any]) -> dict[str, Any]:
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

    @staticmethod
    def _fence_reconcile_identity(fence: Mapping[str, Any] | None) -> dict[str, Any] | None:
        if not fence:
            return None
        job_id = fence.get("blocking_job_id")
        attempt = fence.get("blocking_attempt")
        artifact_id = fence.get("artifact_id")
        if not isinstance(job_id, str):
            raw = fence.get("blocking_opaque_identity")
            if not isinstance(raw, str):
                return None
            try:
                opaque = json.loads(raw)
            except (TypeError, json.JSONDecodeError):
                return None
            if not isinstance(opaque, dict):
                return None
            job_id = opaque.get("job_id")
            attempt = opaque.get("attempt")
            artifact_id = opaque.get("artifact_id")
        try:
            job_id = str(uuid.UUID(job_id)) if isinstance(job_id, str) else None
        except ValueError:
            return None
        if job_id is None or attempt != 1:
            return None
        if artifact_id is not None:
            try:
                artifact_id = str(uuid.UUID(artifact_id))
            except (ValueError, TypeError):
                return None
        return {"job_id": job_id, "attempt": 1, "artifact_id": artifact_id}

    @staticmethod
    def _artifact_event_error(
        assignment: Mapping[str, Any], payload: Mapping[str, Any], *, require_size: bool
    ) -> str | None:
        if payload.get("artifact_id") != assignment.get("artifact_id"):
            return "artifact_id did not match the active assignment"
        if require_size:
            received = payload.get("received_size")
            expected = assignment.get("artifact_size")
            if isinstance(received, bool) or not isinstance(received, int) or received < 0:
                return "received_size was missing or invalid"
            if isinstance(expected, bool) or not isinstance(expected, int) or received != expected:
                return "received_size did not match immutable artifact metadata"
        return None

'''
    insert_before(path, "    async def on_startup(self, _app: aiohttp_web.Application) -> None:\n", helpers)

    replace_once(
        path,
        '''            canonical = canonicalize_create_request(raw)
            if canonical.source.declared_file_count > self.server.MAX_BUNDLE_ENTRIES:
''',
        '''            canonical = canonicalize_create_request(raw)
            existing = await self.store.lookup_create_request(
                canonical.client_request_id, canonical.fingerprint
            )
            if existing is not None:
                # Idempotent replay is resolved from durable request identity before
                # live target/capability policy is re-evaluated.  A target may have
                # disconnected after the original response was lost.
                return aiohttp_web.json_response(self._create_response(existing), status=200)
            if canonical.source.declared_file_count > self.server.MAX_BUNDLE_ENTRIES:
''',
    )

    replace_once(
        path,
        '''            response = {
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
''',
        '''            if created:
                await self.publish(snapshot)
            return aiohttp_web.json_response(
                self._create_response(snapshot), status=201 if created else 200
            )
''',
    )

    replace_once(
        path,
        '''        runtime = payload.get("push_runtime")
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
            opaque = json.dumps(
                {
                    "job_id": active.get("job_id"),
                    "attempt": active.get("attempt"),
                    "artifact_id": active.get("artifact_id"),
                },
                sort_keys=True,
                separators=(",", ":"),
            )
            for snapshot in await self.store.add_opaque_fence(
                device_id,
                opaque,
                ProtocolMode.JOB_V1,
                process_instance_id,
                "client_reported_unknown_active_job",
            ):
                await self.publish(snapshot)
''',
        '''        runtime = payload.get("push_runtime")
        active = runtime.get("active") if isinstance(runtime, dict) else None
        assignment = await self.store.active_assignment_for_device(device_id)
        fence = await self.store.fence_for_device(device_id)
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
        elif fence is not None:
            # Never overwrite a canonical unconfirmed fence merely because REGISTER
            # repeats the same still-active worker as push_runtime.active.  Preserve
            # the exact local identity so its late result can clear the outbox/fence.
            identity = self._fence_reconcile_identity(fence)
            if identity is not None and CAP_PUSH_JOB_ID_V1 in capabilities:
                await self._send_reconcile_identity(session, identity)
        elif isinstance(active, dict) and isinstance(active.get("job_id"), str):
            opaque = json.dumps(
                {
                    "job_id": active.get("job_id"),
                    "attempt": active.get("attempt"),
                    "artifact_id": active.get("artifact_id"),
                },
                sort_keys=True,
                separators=(",", ":"),
            )
            for snapshot in await self.store.add_opaque_fence(
                device_id,
                opaque,
                ProtocolMode.JOB_V1,
                process_instance_id,
                "client_reported_unknown_active_job",
            ):
                await self.publish(snapshot)
''',
    )

    replace_once(
        path,
        '''            artifact_id = payload.get("artifact_id")
            if artifact_id != assignment.get("artifact_id"):
                return True
            key = TransferKey("push", device_id, job_id, 1)
            released = self.transfers.release_exact(key, "push_transfer_complete")
            current = DeviceState(assignment["state"])
            if current in {DeviceState.DOWNLOADING, DeviceState.RECONCILING}:
''',
        '''            error = self._artifact_event_error(assignment, payload, require_size=True)
            if error is not None:
                await self._fail_artifact_event(job_id, device_id, assignment, error)
                return True
            key = TransferKey("push", device_id, job_id, 1)
            released = self.transfers.release_exact(key, "push_transfer_complete")
            current = DeviceState(assignment["state"])
            if current in {
                DeviceState.DISPATCHING,
                DeviceState.DOWNLOADING,
                DeviceState.RECONCILING,
            }:
''',
    )

    replace_once(
        path,
        '''            if not self._matches(assignment, job_id, 1):
                return True
            if DeviceState(assignment["state"]) is DeviceState.VALIDATING:
''',
        '''            if not self._matches(assignment, job_id, 1):
                return True
            error = self._artifact_event_error(assignment, payload, require_size=False)
            if error is not None:
                await self._fail_artifact_event(job_id, device_id, assignment, error)
                return True
            if DeviceState(assignment["state"]) is DeviceState.VALIDATING:
''',
    )

    artifact_failure = '''    async def _fail_artifact_event(
        self,
        job_id: str,
        device_id: str,
        assignment: Mapping[str, Any],
        detail: str,
    ) -> None:
        self.transfers.release_exact(
            TransferKey("push", device_id, job_id, 1), "artifact_identity_mismatch"
        )
        current = DeviceState(assignment["state"])
        if current not in {
            DeviceState.DISPATCHING,
            DeviceState.DOWNLOADING,
            DeviceState.VALIDATING,
            DeviceState.RECONCILING,
        }:
            return
        try:
            snapshot = await self.store.transition_device(
                job_id,
                device_id,
                expected={current},
                target=DeviceState.FAILED,
                fields={
                    "failure_code": "artifact_identity_mismatch",
                    "failure_detail": detail,
                    "accept_deadline": None,
                    "reconciliation_reason": None,
                    "reconciliation_deadline": None,
                },
            )
            await self.publish(snapshot)
        except StoreConflict:
            return
        if self.scheduler is not None:
            self.scheduler.wake()

'''
    insert_before(path, "    async def _handle_job_result(\n", artifact_failure)

    replace_once(
        path,
        '''    async def request_reconcile(self, device_id: str) -> None:
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
''',
        '''    async def request_reconcile(self, device_id: str) -> None:
        session = self.sessions.get(device_id)
        if session is None or CAP_PUSH_JOB_ID_V1 not in session.capabilities:
            return
        assignment = await self.store.active_assignment_for_device(device_id)
        if assignment is not None:
            identity = {
                "job_id": assignment["job_id"],
                "attempt": assignment["attempt"],
                "artifact_id": assignment.get("artifact_id"),
            }
        else:
            identity = self._fence_reconcile_identity(
                await self.store.fence_for_device(device_id)
            )
        if identity is None:
            return
        try:
            await self._send_reconcile_identity(session, identity)
        except (ConnectionError, asyncio.TimeoutError):
            log.warning("Could not send push reconcile request to %s", device_id)

    async def _send_reconcile_for_assignment(
        self, session: LiveSession, assignment: dict[str, Any]
    ) -> None:
        await self._send_reconcile_identity(
            session,
            {
                "job_id": assignment["job_id"],
                "attempt": assignment["attempt"],
                "artifact_id": assignment.get("artifact_id"),
            },
        )

    async def _send_reconcile_identity(
        self, session: LiveSession, identity: Mapping[str, Any]
    ) -> None:
        payload = {
            "type": "PUSH_RECONCILE_REQUEST",
            "jobs": [dict(identity)],
        }
''',
    )


def patch_client() -> None:
    path = "mdm-client/app/src/main/java/com/styly/mdmclient/PushProtocol.kt"
    replace_once(path, "import java.security.MessageDigest\n", "")
    replace_once(
        path,
        '''        if (artifactSize != null && artifactSize < 0L) {
            throw IllegalArgumentException("malformed_command: artifact_size must be non-negative")
        }
''',
        '''        if (jobId != null && artifactSize == null) {
            throw IllegalArgumentException("malformed_command: artifact_size is required")
        }
        if (artifactSize != null && artifactSize < 0L) {
            throw IllegalArgumentException("malformed_command: artifact_size must be non-negative")
        }
''',
    )

    test_path = "mdm-client/app/src/test/java/com/styly/mdmclient/PushProtocolTest.kt"
    replace_once(
        test_path,
        '''        payload.put("artifact_url", "http://192.0.2.1:7070/artifacts/example")
        payload.remove("artifact_sha256")
        val error = assertThrows(IllegalArgumentException::class.java) { PushProtocol.parseCommand(payload) }
        assertTrue(error.message!!.contains("artifact_sha256"))
''',
        '''        payload.put("artifact_url", "http://192.0.2.1:7070/artifacts/example")
        payload.remove("artifact_sha256")
        val error = assertThrows(IllegalArgumentException::class.java) { PushProtocol.parseCommand(payload) }
        assertTrue(error.message!!.contains("artifact_sha256"))
        payload.put("artifact_sha256", "a".repeat(64))
        payload.remove("artifact_size")
        val sizeError = assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseCommand(payload)
        }
        assertTrue(sizeError.message!!.contains("artifact_size"))
''',
    )


def patch_console() -> None:
    path = "mdm-server/styly_mdm/static/push-jobs-v1.js"
    replace_once(
        path,
        '''    const fenced = Object.keys(job.devices || {}).filter(function (id) {
      return job.devices[id] && job.devices[id].device_fence;
    });
''',
        '''    const fenced = Object.keys(job.devices || {}).filter(function (id) {
      return job.devices[id] && job.devices[id].device_fence;
    });
    const fenceDetails = fenced.map(function (id) {
      const fence = job.devices[id].device_fence || {};
      let identity = fence.blocking_job_id;
      if (identity) identity = identity.slice(0, 8);
      else if (fence.blocking_opaque_identity) identity = String(fence.blocking_opaque_identity).slice(0, 80);
      else identity = 'unknown';
      return id + ' ← ' + identity + (fence.reason ? ' (' + fence.reason + ')' : '');
    });
''',
    )
    replace_once(
        path,
        "      ': ' + job.state + '; ' + aggregateText(job) + (fenced.length ? '; fenced: ' + fenced.join(', ') : '');\n",
        "      ': ' + job.state + '; ' + aggregateText(job) + (fenceDetails.length ? '; fenced: ' + fenceDetails.join(', ') : '');\n",
    )


def patch_server_warning_bridge() -> None:
    path = "mdm-server/styly_mdm/server.py"
    replace_once(
        path,
        '''                elif msg_type == "STARTUP_APP_RESULT":
                    log.info("Startup app result from %s: %s", device_id, data.get("status"))
                    await forward_to_admins(data)

                else:
                    log.warning("Unknown message type from device: %s", msg_type)
''',
        '''                elif msg_type == "STARTUP_APP_RESULT":
                    log.info("Startup app result from %s: %s", device_id, data.get("status"))
                    await forward_to_admins(data)

                elif msg_type == "PUSH_RUNTIME_HANDLED":
                    # push_runtime consumed the original frame before the legacy
                    # server loop observed it; this synthetic marker is a no-op.
                    pass

                else:
                    log.warning("Unknown message type from device: %s", msg_type)
''',
    )
    replace_once(
        path,
        '''                elif msg_type == "SET_GROUP_MEMBERS":
                    await handle_set_group_members(ws, data)

                else:
                    log.warning("Unknown message type from admin: %s", msg_type)
''',
        '''                elif msg_type == "SET_GROUP_MEMBERS":
                    await handle_set_group_members(ws, data)

                elif msg_type == "PUSH_RUNTIME_HANDLED":
                    # See the device-side no-op above.
                    pass

                else:
                    log.warning("Unknown message type from admin: %s", msg_type)
''',
    )


def patch_tests() -> None:
    store_path = "mdm-server/tests/test_push_job_store.py"
    additions = r'''

@pytest.mark.asyncio
async def test_lookup_create_request_returns_durable_replay_before_live_revalidation(store):
    req = canonical()
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}
    _, created = await store.create_job(req, protocols, 60_000)
    replay = await store.lookup_create_request(req.client_request_id, req.fingerprint)
    assert replay is not None
    assert replay["job_id"] == created["job_id"]
    with pytest.raises(StoreConflict):
        await store.lookup_create_request(req.client_request_id, "0" * 64)


@pytest.mark.asyncio
async def test_legacy_fence_without_process_id_clears_after_job_v1_client_upgrade(store):
    protocols = {"D1": (ProtocolMode.LEGACY, set())}
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
    await store.mark_dispatching(job_id, "D1", set(), now_ms() + 1000)
    await store.mark_reconciling(
        job_id, "D1", expected={DeviceState.DISPATCHING}, reason="lost", deadline=now_ms()
    )
    await store.mark_unconfirmed(job_id, "D1", None, "legacy timeout")
    snapshots = await store.clear_fence_on_process_replacement("D1", "new-process", True)
    current = next(item for item in snapshots if item["job_id"] == job_id)
    assert current["devices"]["D1"]["device_fence"] is None


@pytest.mark.asyncio
async def test_fenced_terminal_job_is_included_beyond_recent_snapshot_window(store):
    protocols = {"D1": (ProtocolMode.JOB_V1, {"push_job_id_v1"})}
    _, job = await store.create_job(canonical(), protocols, 60_000)
    job_id = job["job_id"]
    await store.start_upload(job_id)
    await store.mark_packaging(job_id, 1, 1)
    artifact_id = str(uuid.uuid4())
    await store.publish_artifact(job_id, {
        "artifact_id": artifact_id, "storage_name": str(uuid.uuid4()) + ".zip",
        "display_filename": "x.zip", "byte_size": 1, "sha256": "a" * 64, "entry_count": 1,
    })
    await store.enable_dispatch(job_id)
    await store.claim_next(["D1"])
    await store.mark_dispatching(job_id, "D1", {"push_job_id_v1"}, now_ms() + 1000)
    await store.mark_reconciling(
        job_id, "D1", expected={DeviceState.DISPATCHING}, reason="lost", deadline=now_ms()
    )
    await store.mark_unconfirmed(job_id, "D1", "process-a", "timeout")
    snapshots = await store.list_snapshots(1, now_ms() + 1)
    assert [item["job_id"] for item in snapshots] == [job_id]
    fence = await store.fence_for_device("D1")
    assert fence is not None
    assert fence["blocking_job_id"] == job_id
    assert fence["artifact_id"] == artifact_id


@pytest.mark.asyncio
async def test_duplicate_acceptance_can_restore_advanced_phase_without_rollback(store):
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
    await store.mark_dispatching(job_id, "D1", {"push_job_id_v1"}, now_ms() + 1000)
    snapshot = await store.transition_device(
        job_id,
        "D1",
        expected={DeviceState.DISPATCHING},
        target=DeviceState.APPLYING,
        fields={"accepted_at": now_ms(), "apply_started_at": now_ms()},
    )
    assert snapshot["devices"]["D1"]["state"] == DeviceState.APPLYING.value
'''
    with (ROOT / store_path).open("a", encoding="utf-8") as handle:
        handle.write(additions)

    scheduler_path = "mdm-server/tests/test_push_job_scheduler.py"
    replace_once(
        scheduler_path,
        "from styly_mdm.push_jobs import ProtocolMode\n",
        "from styly_mdm.push_jobs import DeviceState, ProtocolMode\n",
    )
    with (ROOT / scheduler_path).open("a", encoding="utf-8") as handle:
        handle.write(
            r'''


def test_accepted_phase_restores_client_durable_phase():
    assert PushScheduler._accepted_phase({"phase": "downloading"}) is DeviceState.DOWNLOADING
    assert PushScheduler._accepted_phase({"phase": "validating"}) is DeviceState.VALIDATING
    assert PushScheduler._accepted_phase({"phase": "applying"}) is DeviceState.APPLYING
    assert PushScheduler._accepted_phase({"phase": "unknown"}) is None
'''
        )

    runtime_test = "mdm-server/tests/test_push_runtime_helpers.py"
    write(
        runtime_test,
        r'''import json
import uuid

from styly_mdm.push_runtime import PushRuntime


def test_fence_reconcile_identity_preserves_local_and_opaque_identity():
    job_id = str(uuid.uuid4())
    artifact_id = str(uuid.uuid4())
    local = PushRuntime._fence_reconcile_identity({
        "blocking_job_id": job_id,
        "blocking_attempt": 1,
        "artifact_id": artifact_id,
    })
    assert local == {"job_id": job_id, "attempt": 1, "artifact_id": artifact_id}

    opaque = PushRuntime._fence_reconcile_identity({
        "blocking_job_id": None,
        "blocking_attempt": None,
        "blocking_opaque_identity": json.dumps({
            "job_id": job_id, "attempt": 1, "artifact_id": artifact_id,
        }),
    })
    assert opaque == local
    assert PushRuntime._fence_reconcile_identity({
        "blocking_opaque_identity": "not-json",
    }) is None


def test_artifact_events_require_exact_identity_and_size():
    artifact_id = str(uuid.uuid4())
    assignment = {"artifact_id": artifact_id, "artifact_size": 42}
    assert PushRuntime._artifact_event_error(
        assignment,
        {"artifact_id": artifact_id, "received_size": 42},
        require_size=True,
    ) is None
    assert PushRuntime._artifact_event_error(
        assignment,
        {"artifact_id": str(uuid.uuid4()), "received_size": 42},
        require_size=True,
    ) is not None
    assert PushRuntime._artifact_event_error(
        assignment,
        {"artifact_id": artifact_id, "received_size": 41},
        require_size=True,
    ) is not None
    assert PushRuntime._artifact_event_error(
        assignment,
        {"artifact_id": artifact_id},
        require_size=False,
    ) is None
''',
    )


def patch_docs() -> None:
    path = "docs/PUSH_JOBS.md"
    with (ROOT / path).open("a", encoding="utf-8") as handle:
        handle.write(
            """
- Idempotent create replays are resolved from SQLite before live target eligibility is rechecked.
- Terminal jobs referenced by a persistent fence remain in admin recovery snapshots beyond the recent-job window.
- The safe reconcile action targets an unconfirmed fence identity, not only a currently active assignment.
- REGISTER preserves a matching local fence instead of replacing it with an opaque identity.
- A job-v1 client replacing an identity-less legacy process can clear the legacy fence safely.
- Duplicate command acceptance restores `downloading`, `validating`, or `applying` without phase rollback.
- Transfer/validation events verify exact artifact ID and received byte size before advancing.
"""
        )


def apply() -> None:
    patch_domain()
    patch_store()
    patch_scheduler()
    patch_runtime()
    patch_client()
    patch_console()
    patch_server_warning_bridge()
    patch_tests()
    patch_docs()


if __name__ == "__main__":
    apply()
