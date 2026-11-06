"""Exercise operator commands across runtime, durable state, and owner locks."""
import asyncio
import uuid

import pytest

from styly_mdm.push_job_store import now_ms
from styly_mdm.push_jobs import DeviceState, ProtocolMode, canonicalize_create_request
from push_test_helpers import (
    CAPS, admin_action, completed, downloading_job, manager, operator_runtime, timed_out_job,
)


@pytest.mark.asyncio
async def test_resume_during_persistence_failure_keeps_manual_wait(operator_runtime, manager, tmp_path):
    original = await timed_out_job(manager)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    session.push_state_available = False
    session.capabilities = frozenset()
    await runtime.handle_admin_message(admin, {
        "type": "PUSH_FILES", "job_id": original["job_id"], "target_devices": ["D1"],
    })
    assert admin.messages[-1]["type"] == "ERROR"
    assert "available Push state" in admin.messages[-1]["message"]
    assert await manager.get_snapshot(original["job_id"]) == original
    session.push_state_available = True
    session.capabilities = CAPS
    admin.messages.clear()
    await admin_action(runtime, admin, "PUSH_FILES", original["job_id"], target_devices=["D1"])
    assert (await manager.claim_next(["D1"]))["job"]["job_id"] == original["job_id"]


@pytest.mark.asyncio
async def test_admin_queued_cancel_requests_exact_reconcile_under_owner_lock(operator_runtime, manager, tmp_path):
    original = await timed_out_job(manager)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    original_send = session.ws.send_str

    async def send_while_owned(message):
        assert session.owner_lock.locked()
        await original_send(message)

    session.ws.send_str = send_while_owned
    await admin_action(runtime, admin, "CANCEL_PUSH_JOB", original["job_id"])
    assert not session.owner_lock.locked()
    assert session.ws.messages == [{
        "type": "PUSH_RECONCILE_REQUEST",
        "jobs": [{
            "job_id": original["job_id"],
            "attempt": 1,
            "artifact_id": original["artifact"]["artifact_id"],
        }],
    }]
    current = await manager.get_snapshot(original["job_id"])
    assert current["devices"]["D1"]["cancel_requested"]
    await admin_action(runtime, admin, "PUSH_FILES", original["job_id"])
    assert await manager.claim_next(["D1"]) is None
    assert not any(item["type"] == "EXECUTE_PUSH_FILES" for item in session.ws.messages)


@pytest.mark.asyncio
async def test_queued_cancel_is_confirmed_by_absent_from_client_without_interrupted_work(
    operator_runtime, manager, tmp_path
):
    """A client that no longer holds the interrupted identity (for example after a
    state reset) answers absent; the cancellation must settle while it stays online."""
    original = await timed_out_job(manager)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    await admin_action(runtime, admin, "CANCEL_PUSH_JOB", original["job_id"])
    session.ws.messages.clear()
    await runtime._handle_reconcile_report(session, "D1", {
        "job_id": original["job_id"],
        "attempt": 1,
        "artifact_id": original["artifact"]["artifact_id"],
        "status": "absent",
    })
    device = (await manager.get_snapshot(original["job_id"]))["devices"]["D1"]
    assert device["state"] == "failed"
    assert device["failure"]["code"] == "cancelled"


@pytest.mark.asyncio
async def test_queued_cancel_rejects_interrupted_work_still_held_by_client(operator_runtime, manager, tmp_path):
    original = await timed_out_job(manager)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    await admin_action(runtime, admin, "CANCEL_PUSH_JOB", original["job_id"])
    session.ws.messages.clear()
    await runtime._handle_reconcile_report(session, "D1", {
        "job_id": original["job_id"],
        "attempt": 1,
        "artifact_id": original["artifact"]["artifact_id"],
        "revision": original["devices"]["D1"]["dispatch_revision"],
        "status": "interrupted",
    })
    assert [message["type"] for message in session.ws.messages] == ["PUSH_RESUME_REJECTED"]
    assert (await manager.get_snapshot(original["job_id"]))["devices"]["D1"]["cancel_requested"]


@pytest.mark.asyncio
async def test_admin_offline_timeout_cancel_finishes_without_device_command(operator_runtime, manager, tmp_path):
    original = await timed_out_job(manager)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    runtime.sessions.clear()
    await admin_action(runtime, admin, "CANCEL_PUSH_JOB", original["job_id"])
    assert session.ws.messages == []
    current = await manager.get_snapshot(original["job_id"])
    assert current["devices"]["D1"]["cancel_requested"]


@pytest.mark.asyncio
async def test_cancel_intent_survives_exact_reconcile_send_failure(operator_runtime, manager, tmp_path):
    active = await downloading_job(manager.store, manager)
    await manager.mark_reconciling(active["job_id"], "D1", expected={DeviceState.DOWNLOADING},
                                   reason="device_disconnect", deadline=1)
    await manager.mark_unconfirmed(active["job_id"], "D1", None, "timeout", observed_now=2)
    runtime, _session, admin = operator_runtime(manager, tmp_path)

    async def fail_send(*_args, **_kwargs):
        raise ConnectionError("socket closed")

    runtime.scheduler.send_exact_reconcile = fail_send
    await admin_action(runtime, admin, "CANCEL_PUSH_JOB", active["job_id"], target_devices=["D1"])
    assert (await manager.get_snapshot(active["job_id"]))["devices"]["D1"]["cancel_requested"]
    assert admin.messages[-1]["type"] == "PUSH_JOB_ACTION_SENT"


@pytest.mark.asyncio
@pytest.mark.parametrize("resumed", [False, True])
async def test_admin_cancel_rejects_active_or_already_resumed_work(operator_runtime, manager, tmp_path, resumed):
    original = await timed_out_job(manager) if resumed else await downloading_job(manager.store, manager)
    if resumed:
        _, original = await manager.store.enable_dispatch(original["job_id"])
    runtime, session, admin = operator_runtime(manager, tmp_path)
    assert await runtime.handle_admin_message(admin, {"type": "CANCEL_PUSH_JOB", "job_id": original["job_id"]})
    assert [message["type"] for message in admin.messages] == ["ERROR"]
    assert admin.messages[0]["status"] == "rejected"
    assert admin.messages[0]["accepted_devices"] == []
    assert set(admin.messages[0]["rejected_devices"]) == {"D1"}
    assert await manager.get_snapshot(original["job_id"]) == original
    assert session.ws.messages == []


@pytest.mark.asyncio
async def test_admin_retry_creates_enabled_job_reusing_artifact_and_failed_targets(operator_runtime, manager, tmp_path):
    original = await completed(manager, tmp_path)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    runtime.sessions = {device: session for device in ("failed", "interrupted", "unknown")}
    await admin_action(runtime, admin, "RETRY_PUSH_DEVICES", original["job_id"],
                       client_request_id=str(uuid.uuid4()), target_devices=["failed", "interrupted", "unknown"])
    retry_id = admin.messages[-1]["job_id"]
    assert retry_id != original["job_id"]
    retry = await manager.get_snapshot(retry_id)
    assert retry["dispatch_enabled"] is True
    assert retry["state"] == "running"
    assert retry["artifact"] == original["artifact"]
    assert set(retry["devices"]) == {"failed", "interrupted", "unknown"}
    previous = await manager.get_snapshot(original["job_id"])
    assert previous["devices"]["failed"]["retry_job_id"] == retry_id
    assert previous["devices"]["success"]["retry_job_id"] is None
    assert await manager.store._call(
        lambda conn: conn.execute("SELECT COUNT(*) FROM push_artifacts").fetchone()[0]
    ) == 1
    assert runtime.scheduler.wake_count == 1


async def two_device_job(manager, *, interrupted=False, downloading=False):
    request = canonicalize_create_request({
        "client_request_id": str(uuid.uuid4()), "target_devices": ["D1", "D2"],
        "mode": "push", "dest_path": "/sdcard/STYLY/content",
        "source": {"display_name": "content", "declared_file_count": 1,
                   "declared_total_bytes": 1},
    })
    _, created = await manager.store.create_job(request, {
        device: (ProtocolMode.JOB_V1, CAPS) for device in ["D1", "D2"]
    }, 60_000)
    job_id = created["job_id"]
    await manager.store.start_upload(job_id)
    await manager.store.mark_packaging(job_id, 1, 1)
    await manager.store.publish_artifact(job_id, {
        "artifact_id": str(uuid.uuid4()), "storage_name": "operators.zip",
        "display_filename": "operators.zip", "byte_size": 1,
        "sha256": "a" * 64, "entry_count": 1,
    })
    if interrupted or downloading:
        await manager.store.enable_dispatch(job_id)
        for device_id in ["D1", "D2"]:
            assert (await manager.claim_next([device_id]))["device_id"] == device_id
            await manager.prepare_dispatch(job_id, device_id,
                                           accept_deadline=None)
            active = await manager.transition_device(job_id, device_id,
                expected={DeviceState.DISPATCHING}, target=DeviceState.DOWNLOADING,
                fields={"accepted_at": 1})
            if interrupted:
                await manager.resume_interrupted(job_id, device_id, attempt=1,
                    artifact_id=active["artifact"]["artifact_id"],
                    dispatch_revision=active["devices"][device_id]["dispatch_revision"],
                    validated_offset=1, reason="download_interrupted")
    return await manager.get_snapshot(job_id)


@pytest.mark.asyncio
@pytest.mark.parametrize("action", ["PUSH_FILES", "CANCEL_PUSH_JOB"])
async def test_individual_action_leaves_other_interrupted_device_untouched(operator_runtime, manager, tmp_path, action):
    original = await two_device_job(manager, interrupted=True)
    other_before = await manager.assignment(original["job_id"], "D2")
    runtime, _, admin = operator_runtime(manager, tmp_path)
    await admin_action(runtime, admin, action, original["job_id"], target_devices=["D1"])
    assert await manager.assignment(original["job_id"], "D2") == other_before
    assert await manager.claim_next(["D2"]) is None
    current = await manager.get_snapshot(original["job_id"])
    if action == "PUSH_FILES":
        assert current["devices"]["D1"]["queue_reason"] == "resumable_replay"
        assert (await manager.claim_next(["D1"]))["device_id"] == "D1"
        sent = next(message for message in admin.messages if message["type"] == "PUSH_FILES_SENT")
        assert sent["resume"] is True
        assert sent["target_count"] == 1
    else:
        assert current["devices"]["D1"]["cancel_requested"]
        assert current["devices"]["D2"]["state"] == "queued"


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("state", "reason"),
    [
        ("queued", "download_interrupted"),
        ("queued", "dispatch_paused"),
        ("queued", "device_offline"),
        ("queued", "client_restarted"),
        ("reconciling", "client_restarted"),
    ],
)
async def test_targeted_resume_preserves_non_target_manual_wait(manager, state, reason):
    original = await two_device_job(manager)
    job_id = original["job_id"]
    manager.store._call_sync(
        lambda conn: (
            conn.execute(
                "UPDATE push_jobs SET dispatch_enabled=0, "
                "dispatch_paused_reason='server_restart' WHERE job_id=?",
                (job_id,),
            ),
            conn.execute(
                "UPDATE push_job_devices SET queue_reason='download_interrupted' "
                "WHERE job_id=? AND device_id='D1'",
                (job_id,),
            ),
            conn.execute(
                "UPDATE push_job_devices SET state=?, queue_reason=?, updated_at=17 "
                "WHERE job_id=? AND device_id='D2'",
                (state, reason, job_id),
            ),
            conn.commit(),
        )
    )
    non_target_before = await manager.assignment(job_id, "D2")

    enabled, resumed = await manager.store.enable_dispatch(job_id, target_devices=["D1"])

    assert enabled is True
    assert resumed["devices"]["D1"]["queue_reason"] == "resumable_replay"
    assert resumed["devices"]["D2"]["queue_reason"] == reason
    assert resumed["devices"]["D2"]["state"] == state
    non_target_after = await manager.assignment(job_id, "D2")
    assert (
        non_target_after["state"],
        non_target_after["queue_reason"],
        non_target_after["updated_at"],
    ) == (
        non_target_before["state"],
        non_target_before["queue_reason"],
        non_target_before["updated_at"],
    )
    assert resumed["devices"]["D2"]["manual_wait"] is True


@pytest.mark.asyncio
async def test_targeted_resume_timestamps_non_target_that_it_pauses(manager):
    original = await two_device_job(manager)
    job_id = original["job_id"]
    manager.store._call_sync(
        lambda conn: (
            conn.execute(
                "UPDATE push_jobs SET dispatch_enabled=0, "
                "dispatch_paused_reason='server_restart' WHERE job_id=?",
                (job_id,),
            ),
            conn.execute(
                "UPDATE push_job_devices SET queue_reason='download_interrupted' "
                "WHERE job_id=? AND device_id='D1'",
                (job_id,),
            ),
            conn.execute(
                "UPDATE push_job_devices SET state='reconciling', queue_reason=NULL, updated_at=17 "
                "WHERE job_id=? AND device_id='D2'",
                (job_id,),
            ),
            conn.commit(),
        )
    )
    non_target_before = await manager.assignment(job_id, "D2")

    enabled, resumed = await manager.store.enable_dispatch(job_id, target_devices=["D1"])

    assert enabled is True
    assert resumed["devices"]["D2"]["queue_reason"] == "dispatch_paused"
    non_target_after = await manager.assignment(job_id, "D2")
    assert non_target_after["updated_at"] != non_target_before["updated_at"]


@pytest.mark.asyncio
@pytest.mark.parametrize("restart", [False, True])
async def test_individual_resume_from_global_pause_keeps_other_device_paused_until_resume_all(operator_runtime, manager, tmp_path, restart):
    original = await two_device_job(manager)
    if restart:
        await manager.store.enable_dispatch(original["job_id"])
        manager.store.recover_startup_sync(accept_reconciliation_timeout_ms=1000,
                                     reconciliation_timeout_ms=1000)
    runtime, _, admin = operator_runtime(manager, tmp_path)
    await admin_action(runtime, admin, "PUSH_FILES", original["job_id"], target_devices=["D1"])
    assert await manager.claim_next(["D2"]) is None
    assert (await manager.claim_next(["D1"]))["device_id"] == "D1"
    runtime.sessions["D2"] = runtime.sessions["D1"]
    await admin_action(runtime, admin, "PUSH_FILES", original["job_id"])
    assert (await manager.claim_next(["D2"]))["device_id"] == "D2"


@pytest.mark.asyncio
@pytest.mark.parametrize("action", ["PUSH_FILES", "CANCEL_PUSH_JOB"])
@pytest.mark.parametrize("targets", [[], ["unknown"], ["D1", "unknown"], "D1", [1], None])
async def test_invalid_explicit_targets_never_expand_to_all_devices(operator_runtime, manager, tmp_path, action, targets):
    original = await two_device_job(manager, interrupted=True)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    assert await asyncio.wait_for(runtime.handle_admin_message(admin, {
        "type": action, "job_id": original["job_id"], "target_devices": targets,
    }), timeout=2)
    assert [message for message in admin.messages if message["type"] == "ERROR"]
    assert await manager.get_snapshot(original["job_id"]) == original
    assert session.ws.messages == []
    assert await manager.claim_next(["D1", "D2"]) is None


@pytest.mark.asyncio
async def test_restart_individual_resume_does_not_authorize_other_reconnecting_worker(operator_runtime, manager, tmp_path):
    original = await two_device_job(manager, downloading=True)
    manager.store.recover_startup_sync(accept_reconciliation_timeout_ms=1000,
                                 reconciliation_timeout_ms=1000)
    runtime, _, admin = operator_runtime(manager, tmp_path)
    requested = []

    async def request_reconcile(device_id):
        requested.append(device_id)

    runtime.request_reconcile = request_reconcile
    await admin_action(runtime, admin, "PUSH_FILES", original["job_id"], target_devices=["D1"])
    assert requested == ["D1"]
    for device_id in ["D1", "D2"]:
        outcome, _ = await manager.resume_interrupted(original["job_id"], device_id,
            attempt=1, artifact_id=original["artifact"]["artifact_id"],
            dispatch_revision=original["devices"][device_id]["dispatch_revision"],
            validated_offset=1, reason="client_restarted")
        assert outcome == "requeued"
    assert await manager.claim_next(["D2"]) is None
    assert (await manager.claim_next(["D1"]))["device_id"] == "D1"
    runtime.sessions["D2"] = runtime.sessions["D1"]
    await admin_action(runtime, admin, "PUSH_FILES", original["job_id"])
    assert (await manager.claim_next(["D2"]))["device_id"] == "D2"


@pytest.mark.asyncio
async def test_offline_resume_rejected_but_cancel_allowed(operator_runtime, manager, tmp_path):
    original = await timed_out_job(manager)
    runtime, _, admin = operator_runtime(manager, tmp_path)
    runtime.sessions.clear()
    await runtime.handle_admin_message(admin, {
        "type": "PUSH_FILES", "job_id": original["job_id"], "target_devices": ["D1"],
    })
    assert admin.messages[-1]["type"] == "ERROR"
    assert await manager.get_snapshot(original["job_id"]) == original
    admin.messages.clear()
    await admin_action(runtime, admin, "CANCEL_PUSH_JOB", original["job_id"], target_devices=["D1"])
    assert (await manager.get_snapshot(original["job_id"]))["devices"]["D1"]["cancel_requested"]


@pytest.mark.asyncio
async def test_resume_all_keeps_offline_timeout_waiting(operator_runtime, manager, tmp_path):
    original = await two_device_job(manager, interrupted=True)
    runtime, _, admin = operator_runtime(manager, tmp_path)
    await admin_action(runtime, admin, "PUSH_FILES", original["job_id"])
    current = await manager.get_snapshot(original["job_id"])
    assert current["devices"]["D1"]["queue_reason"] == "resumable_replay"
    assert current["devices"]["D2"]["queue_reason"] == "download_interrupted"
    runtime.sessions["D2"] = runtime.sessions["D1"]
    assert await manager.claim_next(["D2"]) is None


@pytest.mark.asyncio
async def test_active_report_consumes_pending_resume_authorization(manager):
    active = await downloading_job(manager.store, manager)
    job_id = active["job_id"]
    await manager.mark_reconciling(job_id, "D1", expected={DeviceState.DOWNLOADING},
                                   reason="device_disconnect", deadline=now_ms() + 60_000)
    await manager.store._call(lambda conn: conn.execute(
        "UPDATE push_job_devices SET queue_reason='resumable_replay' WHERE job_id=?", (job_id,)))
    outcome, _ = await manager.reconcile_report(job_id, "D1", 1, "active", "downloading", None)
    assert outcome == "active"
    device = (await manager.get_snapshot(job_id))["devices"]["D1"]
    # A later client_restarted interruption must not inherit the consumed Resume.
    assert device["state"] == "downloading" and device["queue_reason"] is None


@pytest.mark.asyncio
async def test_reconcile_is_deferred_while_push_state_is_unavailable(operator_runtime, manager, tmp_path):
    original = await timed_out_job(manager)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    session.push_state_available = False
    await admin_action(runtime, admin, "CANCEL_PUSH_JOB", original["job_id"])
    # An empty, unloaded client state would answer absent, which is not evidence.
    assert session.ws.messages == []
    assert (await manager.get_snapshot(original["job_id"]))["devices"]["D1"]["cancel_requested"]


@pytest.mark.asyncio
@pytest.mark.parametrize("claimed", [False, True])
@pytest.mark.parametrize("resume_online", [False, True])
async def test_restart_unsent_offline_target_can_be_cancelled_without_client(
    operator_runtime, manager, tmp_path, claimed, resume_online,
):
    original = await two_device_job(manager)
    job_id = original["job_id"]
    await manager.store.enable_dispatch(job_id)
    if claimed:
        assert (await manager.claim_next(["D2"]))["device_id"] == "D2"
    manager.store.recover_startup_sync(
        accept_reconciliation_timeout_ms=1000, reconciliation_timeout_ms=1000,
    )
    runtime, session, admin = operator_runtime(manager, tmp_path)
    if resume_online:
        await admin_action(runtime, admin, "PUSH_FILES", job_id)
    else:
        runtime.sessions.clear()
    before = await manager.get_snapshot(job_id)
    offline = before["devices"]["D2"]
    assert offline["dispatch_revision"] is None
    assert offline["cancellable"] and not offline["resume_supported"]
    other_before = await manager.assignment(job_id, "D1")

    await admin_action(runtime, admin, "CANCEL_PUSH_JOB", job_id, target_devices=["D2"])
    cancelled = await manager.get_snapshot(job_id)
    target = cancelled["devices"]["D2"]
    assert target["state"] == "failed" and target["failure"]["code"] == "cancelled"
    assert not target["cancel_requested"] and not target["cancellable"] and not target["retryable"]
    assert (await manager.assignment(job_id, "D2"))["terminal_at"] is not None
    assert await manager.assignment(job_id, "D1") == other_before
    assert session.ws.messages == []
    response = admin.messages[-1]
    assert response["status"] == "accepted"
    assert response["accepted_devices"] == ["D2"]
    assert response["rejected_devices"] == {}
    await admin_action(runtime, admin, "CANCEL_PUSH_JOB", job_id, target_devices=["D2"])
    assert admin.messages[-1] == response
    assert await manager.get_snapshot(job_id) == cancelled
    assert await manager.claim_next(["D2"]) is None


@pytest.mark.asyncio
async def test_cancel_all_unsent_restart_targets_finishes_job_and_releases_artifact(operator_runtime, manager, tmp_path):
    original = await two_device_job(manager)
    job_id = original["job_id"]
    await manager.store.enable_dispatch(job_id)
    manager.store.recover_startup_sync(
        accept_reconciliation_timeout_ms=1000, reconciliation_timeout_ms=1000,
    )
    runtime, session, admin = operator_runtime(manager, tmp_path)
    runtime.sessions.clear()
    (tmp_path / "operators.zip").write_bytes(b"a")

    await admin_action(runtime, admin, "CANCEL_PUSH_JOB", job_id)
    finished = await manager.get_snapshot(job_id)
    assert finished["state"] == "failed" and finished["terminal_at"] is not None
    assert all(d["failure"]["code"] == "cancelled" for d in finished["devices"].values())
    assert session.ws.messages == []
    assert manager.gc_artifacts_sync(tmp_path, retry_window_ms=0, timestamp=now_ms() + 1) == [
        original["artifact"]["artifact_id"]
    ]
    assert not (tmp_path / "operators.zip").exists()


@pytest.mark.asyncio
@pytest.mark.parametrize("paused", ["restart", "interrupted", "ready"])
async def test_retry_request_replay_preserves_dispatch_authority(operator_runtime, manager, tmp_path, paused):
    original = await completed(manager, tmp_path)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    runtime.sessions = {device: session for device in ("failed", "interrupted", "unknown")}
    request_id = str(uuid.uuid4())
    if paused == "ready":
        _, retry = await manager.retry_failed(
            original["job_id"], request_id, artifact_root=tmp_path,
            target_devices=runtime.sessions, online_devices=runtime.sessions,
        )
        retry_id = retry["job_id"]
    else:
        await admin_action(runtime, admin, "RETRY_PUSH_DEVICES", original["job_id"],
                           client_request_id=request_id, target_devices=["failed", "interrupted", "unknown"])
        retry_id = admin.messages[-1]["job_id"]
        if paused == "restart":
            manager.store.recover_startup_sync(
                accept_reconciliation_timeout_ms=1000, reconciliation_timeout_ms=1000,
            )
        else:
            await manager.claim_next(["failed"])
            active = await manager.prepare_dispatch(retry_id, "failed", accept_deadline=None)
            await manager.resume_interrupted(
                retry_id, "failed", attempt=1, artifact_id=active["artifact"]["artifact_id"],
                dispatch_revision=active["devices"]["failed"]["dispatch_revision"],
                validated_offset=0, reason="download_interrupted",
            )
    before = await manager.get_snapshot(retry_id)
    rows_before = {d: await manager.assignment(retry_id, d) for d in before["devices"]}
    # A request replay must also work when none of the original targets are online.
    runtime.sessions.clear()
    await admin_action(runtime, admin, "RETRY_PUSH_DEVICES", original["job_id"],
                       client_request_id=request_id, target_devices=["failed", "interrupted", "unknown"])
    assert admin.messages[-1]["job_id"] == retry_id
    assert await manager.get_snapshot(retry_id) == before
    assert {d: await manager.assignment(retry_id, d) for d in before["devices"]} == rows_before

    if paused == "ready":
        runtime.sessions = {device: session for device in before["devices"]}
        await admin_action(runtime, admin, "PUSH_FILES", retry_id)
        assert (await manager.get_snapshot(retry_id))["dispatch_enabled"]
        assert (await manager.claim_next(["failed"]))["job"]["job_id"] == retry_id


@pytest.mark.asyncio
@pytest.mark.parametrize("rejected_targets", [[], ["D1"], ["D1", "D2"]])
async def test_cancel_returns_one_correlated_outcome_for_every_target(
    operator_runtime, manager, tmp_path, rejected_targets
):
    original = await two_device_job(manager, interrupted=True)
    job_id = original["job_id"]
    if rejected_targets:
        await manager.store.enable_dispatch(job_id, rejected_targets)
    runtime, _session, admin = operator_runtime(manager, tmp_path)
    requested = ["D2", "D1"]
    accepted = [device_id for device_id in requested if device_id not in rejected_targets]
    before_rejected = {
        device_id: await manager.assignment(job_id, device_id)
        for device_id in rejected_targets
    }
    await runtime.handle_admin_message(admin, {
        "type": "CANCEL_PUSH_JOB", "job_id": job_id,
        "target_devices": requested + ["D2"], "request_id": "cancel-request",
    })

    assert len(admin.messages) == 1
    response = admin.messages[0]
    assert response["type"] == ("PUSH_JOB_ACTION_SENT" if accepted else "ERROR")
    assert response["status"] == (
        "partial" if accepted and rejected_targets else "accepted" if accepted else "rejected"
    )
    assert response["action"] == "CANCEL_PUSH_JOB"
    assert response["job_id"] == job_id
    assert response["request_id"] == "cancel-request"
    assert response["target_devices"] == requested
    assert response["accepted_devices"] == accepted
    assert set(response["rejected_devices"]) == set(rejected_targets)
    assert all("Only paused transfers or Retry candidates" in reason for reason in response["rejected_devices"].values())
    assert runtime.scheduler.wake_count == bool(accepted)
    for device_id in rejected_targets:
        assert await manager.assignment(job_id, device_id) == before_rejected[device_id]
    snapshot = await manager.get_snapshot(job_id)
    assert all(snapshot["devices"][device_id]["cancel_requested"] for device_id in accepted)

    # Repeating an already recorded request has the same accurate outcome and
    # cannot convert rejected targets into accepted ones or mutate durable state.
    admin.messages.clear()
    await runtime.handle_admin_message(admin, {
        "type": "CANCEL_PUSH_JOB", "job_id": job_id,
        "target_devices": requested, "request_id": "cancel-request",
    })
    assert admin.messages == [response]
    assert await manager.get_snapshot(job_id) == snapshot


@pytest.mark.asyncio
async def test_cancel_acknowledgement_failure_preserves_intent_and_can_be_retried(operator_runtime, manager, tmp_path):
    original = await timed_out_job(manager)
    runtime, _session, admin = operator_runtime(manager, tmp_path)
    attempted = []
    closed = []

    async def fail_send(_ws, response):
        attempted.append(response)
        raise ConnectionError("admin disconnected")

    async def close_failed(ws, _reason):
        closed.append(ws)

    real_send = runtime.admin_send
    runtime.admin_send = fail_send
    runtime._close_failed_admin_socket = close_failed
    request = {
        "type": "CANCEL_PUSH_JOB", "job_id": original["job_id"],
        "target_devices": ["D1"], "request_id": "lost-ack",
    }
    assert await runtime.handle_admin_message(admin, request)
    current = await manager.get_snapshot(original["job_id"])
    assert current["devices"]["D1"]["cancel_requested"]
    assert attempted[0]["status"] == "accepted"
    assert attempted[0]["accepted_devices"] == ["D1"]
    assert closed == [admin]

    runtime.admin_send = real_send
    assert await runtime.handle_admin_message(admin, {**request, "request_id": "retry-ack"})
    assert admin.messages == [{**attempted[0], "request_id": "retry-ack"}]
    assert await manager.get_snapshot(original["job_id"]) == current


@pytest.mark.asyncio
@pytest.mark.parametrize("action", ["CANCEL_PUSH_JOB", "PUSH_FILES"])
@pytest.mark.parametrize("targets", [[], ["unknown"], ["D1", "unknown"]])
async def test_invalid_operator_action_returns_correlated_error(operator_runtime, manager, tmp_path, action, targets):
    original = await two_device_job(manager, interrupted=True)
    runtime, _session, admin = operator_runtime(manager, tmp_path)
    assert await runtime.handle_admin_message(admin, {
        "type": action, "job_id": original["job_id"], "target_devices": targets,
        "request_id": "invalid-action",
    })
    assert len(admin.messages) == 1
    response = admin.messages[0]
    assert response["type"] == "ERROR"
    assert response["action"] == action
    assert response["job_id"] == original["job_id"]
    assert response["request_id"] == "invalid-action"
    assert response["target_devices"] == targets
    assert await manager.get_snapshot(original["job_id"]) == original


@pytest.mark.asyncio
@pytest.mark.parametrize('interrupted', [False, True])
async def test_resume_acknowledgement_echoes_requested_targets_before_online_filter(
    operator_runtime, manager, tmp_path, interrupted,
):
    original = await two_device_job(manager, interrupted=interrupted)
    runtime, _session, admin = operator_runtime(manager, tmp_path)
    await admin_action(runtime, admin, "PUSH_FILES", original["job_id"],
                       target_devices=["D2", "D1", "D2"], request_id="resume-request")
    response = admin.messages[0]
    assert response["type"] == "PUSH_FILES_SENT"
    assert response["action"] == "PUSH_FILES"
    assert response["request_id"] == "resume-request"
    assert response["target_devices"] == ["D2", "D1"]
    assert response["target_count"] == 1
    assert response["accepted_devices"] == ["D1"]
    current = await manager.get_snapshot(original["job_id"])
    assert current["devices"]["D1"]["queue_reason"] == ('resumable_replay' if interrupted else 'awaiting_dispatch')
    assert current["devices"]["D2"]["queue_reason"] == ('download_interrupted' if interrupted else 'dispatch_paused')


@pytest.mark.asyncio
@pytest.mark.parametrize("action", ["CANCEL_PUSH_JOB", "PUSH_FILES"])
@pytest.mark.parametrize("committed", [False, True])
async def test_socket_wrapper_correlates_unexpected_failure_without_claiming_outcome(
    operator_runtime, manager, tmp_path, monkeypatch, action, committed,
):
    import json
    import sqlite3

    from aiohttp import WSMessage, WSMsgType, web
    from styly_mdm.push_runtime import RuntimeWebSocketResponse

    original = await two_device_job(manager, interrupted=True)
    runtime, session, _admin = operator_runtime(manager, tmp_path)
    runtime.sessions["D2"] = session
    request = {
        "type": action, "job_id": original["job_id"], "request_id": "failed-operation",
        "target_devices": ["D1", "D2"],
    }
    owner = manager if action == "CANCEL_PUSH_JOB" else manager.store
    method = "cancel_interrupted" if action == "CANCEL_PUSH_JOB" else "enable_dispatch"
    original_method = getattr(owner, method)

    async def fail_operation(*args, **kwargs):
        if committed:
            await original_method(*args, **kwargs)
        raise sqlite3.OperationalError("injected database failure")

    monkeypatch.setattr(owner, method, fail_operation)
    marker = WSMessage(WSMsgType.BINARY, b"next frame", "")
    incoming = iter([WSMessage(WSMsgType.TEXT, json.dumps(request), ""), marker])

    async def next_frame(_socket):
        return next(incoming)

    monkeypatch.setattr(web.WebSocketResponse, "__anext__", next_frame)
    socket = RuntimeWebSocketResponse()
    socket._push_runtime = runtime
    socket._push_path = "/ws/admin"
    socket._push_admin_snapshot_sent = True
    responses = []

    async def record_response(recipient, response):
        assert recipient is socket
        responses.append(response)

    runtime.admin_send = record_response
    # The wrapper consumes the failed command and continues reading the connection.
    assert await socket.__anext__() is marker
    assert len(responses) == 1
    response = responses[0]
    assert response["type"] == "ERROR"
    for field in ("job_id", "request_id", "target_devices"):
        assert response[field] == request[field]
    assert response["action"] == action
    assert "could not be confirmed" in response["message"]
    assert "changes may already be recorded" in response["message"]
    assert not {"status", "accepted_devices", "rejected_devices"} & response.keys()
    current = await manager.get_snapshot(original["job_id"])
    if not committed:
        assert current == original
    elif action == "CANCEL_PUSH_JOB":
        # The first target committed, then the handler failed before reaching D2.
        assert current["devices"]["D1"]["cancel_requested"]
        assert not current["devices"]["D2"]["cancel_requested"]
    else:
        assert all(device["queue_reason"] == "resumable_replay" for device in current["devices"].values())


@pytest.mark.asyncio
async def test_targeted_retry_ack_and_error_correlate_source_and_request(operator_runtime, manager, tmp_path):
    original = await completed(manager, tmp_path)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    runtime.sessions = {device: session for device in ('failed', 'interrupted')}
    request = {
        'type': 'RETRY_PUSH_DEVICES', 'job_id': original['job_id'],
        'client_request_id': str(uuid.uuid4()), 'target_devices': ['failed'],
    }
    assert await runtime.handle_admin_message(admin, request)
    ack = admin.messages[-1]
    assert ack['type'] == 'PUSH_JOB_ACTION_SENT'
    assert ack['source_job_id'] == original['job_id']
    assert ack['client_request_id'] == request['client_request_id']
    assert ack['target_devices'] == ['failed']
    assert set((await manager.get_snapshot(ack['job_id']))['devices']) == {'failed'}
    assert (await manager.get_snapshot(original['job_id']))['devices']['interrupted'] == original['devices']['interrupted']
    await runtime.handle_admin_message(admin, {**request, 'target_devices': ['interrupted']})
    error = admin.messages[-1]
    assert error['type'] == 'ERROR' and 'different request' in error['message']
    assert error['job_id'] == request['job_id']
    assert error['client_request_id'] == request['client_request_id']
    assert error['target_devices'] == ['interrupted']


@pytest.mark.asyncio
@pytest.mark.parametrize('targets', [None, [], 'failed', ['foreign'], ['failed', 1]])
async def test_retry_rejects_missing_or_invalid_scope_from_old_or_invalid_console(
    operator_runtime, manager, tmp_path, targets,
):
    original = await completed(manager, tmp_path)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    runtime.sessions = {'failed': session}
    request = {
        'type': 'RETRY_PUSH_DEVICES', 'job_id': original['job_id'],
        'client_request_id': str(uuid.uuid4()),
    }
    if targets is not None:
        request['target_devices'] = targets
    await runtime.handle_admin_message(admin, request)
    assert admin.messages[-1]['type'] == 'ERROR'
    assert admin.messages[-1]['client_request_id'] == request['client_request_id']
    assert await manager.get_snapshot(original['job_id']) == original


@pytest.mark.asyncio
@pytest.mark.parametrize('device_id,dispatched', [('failed', False), ('failed', True), ('interrupted', True), ('unknown', True)])
async def test_cancel_withdraws_terminal_candidate_without_device_io_or_rewriting_result(
    operator_runtime, manager, tmp_path, device_id, dispatched,
):
    original = await completed(manager, tmp_path)
    job_id = original['job_id']
    await manager.store._call(lambda conn: (
        conn.execute('DELETE FROM push_device_fences WHERE device_id=?', (device_id,)),
        conn.execute('UPDATE push_job_devices SET dispatch_revision=? WHERE job_id=? AND device_id=?',
                     (1 if dispatched else None, job_id, device_id)),
    ))
    before = await manager.assignment(job_id, device_id)
    original = await manager.get_snapshot(job_id)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    runtime.sessions = {device_id: session}
    await admin_action(runtime, admin, 'CANCEL_PUSH_JOB', job_id, target_devices=[device_id])
    cancelled = await manager.get_snapshot(job_id)
    row = await manager.assignment(job_id, device_id)
    assert row['cancel_requested_at'] is not None
    assert {k: v for k, v in row.items() if k not in {'cancel_requested_at', 'updated_at'}} == {
        k: v for k, v in before.items() if k not in {'cancel_requested_at', 'updated_at'}
    }
    assert not cancelled['devices'][device_id]['retryable']
    assert not cancelled['devices'][device_id]['cancellable']
    assert not cancelled['devices'][device_id]['resume_required']
    for peer in original['devices'].keys() - {device_id}:
        assert cancelled['devices'][peer] == original['devices'][peer]
    assert session.ws.messages == []
    assert await manager.pending_cancellations_for_device(device_id) == []
    assert await manager.reconciliation_target_for_device(device_id, job_id=job_id) is None
    assert await manager.confirm_cancel(job_id, device_id) == []
    assert await manager.cancel_interrupted(job_id, device_id) == cancelled
    manager.store.recover_startup_sync(accept_reconciliation_timeout_ms=1000, reconciliation_timeout_ms=1000)
    after_restart = await manager.get_snapshot(job_id)
    assert after_restart['devices'][device_id]['cancel_requested']
    assert not after_restart['devices'][device_id]['retryable']


@pytest.mark.asyncio
async def test_retired_job_wide_retry_is_rejected_without_dispatch(operator_runtime, manager, tmp_path):
    original = await completed(manager, tmp_path)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    runtime.sessions = {device: session for device in ('failed', 'interrupted', 'unknown')}
    assert await runtime.handle_admin_message(admin, {
        'type': 'RETRY_FAILED_PUSH_JOB', 'job_id': original['job_id'],
        'client_request_id': str(uuid.uuid4()),
    })
    assert admin.messages[-1]['type'] == 'ERROR'
    assert 'retired' in admin.messages[-1]['message']
    assert await manager.get_snapshot(original['job_id']) == original
    assert await manager.store._call(lambda conn: conn.execute('SELECT COUNT(*) FROM push_jobs').fetchone()[0]) == 1
    assert runtime.scheduler.wake_count == 0


@pytest.mark.asyncio
async def test_withdrawing_failed_candidate_preserves_unrelated_opaque_owner(
    operator_runtime, manager, tmp_path,
):
    original = await completed(manager, tmp_path)
    job_id = original['job_id']
    identity = manager.opaque_identity_for_active({'job_id': str(uuid.uuid4()), 'attempt': 1})
    await manager.add_opaque_fence('failed', identity, ProtocolMode.JOB_V1, 'process-a', 'unknown active job')
    before = await manager.get_snapshot(job_id)
    fence = before['devices']['failed']['device_fence']
    runtime, session, admin = operator_runtime(manager, tmp_path)
    runtime.sessions = {'failed': session}
    await admin_action(runtime, admin, 'CANCEL_PUSH_JOB', job_id, target_devices=['failed'])
    after = await manager.get_snapshot(job_id)
    assert after['devices']['failed']['cancel_requested']
    assert not after['devices']['failed']['retryable']
    assert after['devices']['failed']['device_fence'] == fence
    assert after['devices']['failed']['failure'] == before['devices']['failed']['failure']
    assert (await manager.fenced_assignment_for_device('failed'))['blocking_opaque_identity'] == identity
    assert session.ws.messages == []
