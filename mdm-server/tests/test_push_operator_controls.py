"""Exercise operator commands across runtime, durable state, and owner locks."""
import asyncio
import uuid
from types import SimpleNamespace

import pytest

from styly_mdm.push_job_manager import PushJobManager
from styly_mdm.push_job_store import PushJobStore, now_ms
from styly_mdm.push_jobs import DeviceState, ProtocolMode, canonicalize_create_request
from styly_mdm.push_scheduler import PushScheduler
from test_push_cancellation import CAPS, runtime_for, timed_out_job
from test_push_retry_failed import completed
from test_push_review_connection import Ws, downloading_job


@pytest.fixture
def manager(tmp_path):
    store = PushJobStore(tmp_path / "operator.sqlite3")
    yield PushJobManager(store)
    store.close()


class OperatorScheduler:
    """Keep scheduling deterministic while exercising the real reconcile lock path."""

    send_exact_reconcile = PushScheduler.send_exact_reconcile

    def __init__(self, runtime):
        self.runtime = runtime
        self.send_timeout = 0.5
        self.wake_count = 0

    def sessions(self):
        return self.runtime.sessions

    def wake(self):
        self.wake_count += 1


def operator_runtime(manager, artifact_root):
    runtime, session = runtime_for(manager)
    runtime.device_locks = {"D1": session.owner_lock}
    runtime.admin_send_timeout = 0.5
    runtime.artifacts = SimpleNamespace(artifact_root=artifact_root)
    runtime.legacy.MAX_CONCURRENT_TRANSFERS = 5
    runtime.legacy.devices = {}
    runtime.scheduler = OperatorScheduler(runtime)
    return runtime, session, Ws()


async def admin_action(runtime, admin, message_type, job_id, **fields):
    assert await asyncio.wait_for(runtime.handle_admin_message(admin, {
        "type": message_type, "job_id": job_id, **fields,
    }), timeout=2)
    assert not [message for message in admin.messages if message["type"] == "ERROR"]


@pytest.mark.asyncio
async def test_admin_queued_cancel_requests_exact_reconcile_under_owner_lock(manager, tmp_path):
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
    current = await manager.store.get_snapshot(original["job_id"])
    assert current["devices"]["D1"]["cancel_requested"]
    await admin_action(runtime, admin, "PUSH_FILES", original["job_id"], target_devices=list(original["devices"]))
    assert await manager.claim_next(["D1"]) is None
    assert not any(item["type"] == "EXECUTE_PUSH_FILES" for item in session.ws.messages)


@pytest.mark.asyncio
async def test_queued_cancel_is_confirmed_by_absent_from_client_without_interrupted_work(
    manager, tmp_path
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
    device = (await manager.store.get_snapshot(original["job_id"]))["devices"]["D1"]
    assert device["state"] == "failed"
    assert device["failure"]["code"] == "cancelled"


@pytest.mark.asyncio
async def test_queued_cancel_rejects_interrupted_work_still_held_by_client(manager, tmp_path):
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
    assert (await manager.store.get_snapshot(original["job_id"]))["devices"]["D1"]["cancel_requested"]


@pytest.mark.asyncio
async def test_admin_offline_timeout_cancel_finishes_without_device_command(manager, tmp_path):
    original = await timed_out_job(manager)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    runtime.sessions.clear()
    await admin_action(runtime, admin, "CANCEL_PUSH_JOB", original["job_id"])
    assert session.ws.messages == []
    current = await manager.store.get_snapshot(original["job_id"])
    assert current["devices"]["D1"]["cancel_requested"]


@pytest.mark.asyncio
async def test_cancel_intent_survives_exact_reconcile_send_failure(manager, tmp_path):
    active = await downloading_job(manager.store, manager)
    await manager.store.mark_reconciling(active["job_id"], "D1", expected={DeviceState.DOWNLOADING},
                                   reason="device_disconnect", deadline=1)
    await manager.mark_unconfirmed(active["job_id"], "D1", None, "timeout", observed_now=2)
    runtime, _session, admin = operator_runtime(manager, tmp_path)

    async def fail_send(*_args, **_kwargs):
        raise ConnectionError("socket closed")

    runtime.scheduler.send_exact_reconcile = fail_send
    await admin_action(runtime, admin, "CANCEL_PUSH_JOB", active["job_id"], target_devices=["D1"])
    assert (await manager.store.get_snapshot(active["job_id"]))["devices"]["D1"]["cancel_requested"]
    assert admin.messages[-1]["type"] == "PUSH_JOB_ACTION_SENT"


@pytest.mark.asyncio
@pytest.mark.parametrize("resumed", [False, True])
async def test_admin_cancel_rejects_active_or_already_resumed_work(manager, tmp_path, resumed):
    original = await timed_out_job(manager) if resumed else await downloading_job(manager.store, manager)
    if resumed:
        _, original = await manager.store.enable_dispatch(original["job_id"], ["D1"])
    runtime, session, admin = operator_runtime(manager, tmp_path)
    assert await runtime.handle_admin_message(admin, {"type": "CANCEL_PUSH_JOB", "job_id": original["job_id"]})
    assert any(message["type"] == "ERROR" for message in admin.messages)
    assert await manager.store.get_snapshot(original["job_id"]) == original
    assert session.ws.messages == []


@pytest.mark.asyncio
async def test_admin_retry_creates_enabled_job_reusing_artifact_and_failed_targets(manager, tmp_path):
    original = await completed(manager, tmp_path)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    runtime.sessions = {device: session for device in ("failed", "interrupted", "unknown")}
    await admin_action(runtime, admin, "RETRY_FAILED_PUSH_JOB", original["job_id"],
                       client_request_id=str(uuid.uuid4()))
    retry_id = admin.messages[-1]["job_id"]
    assert retry_id != original["job_id"]
    retry = await manager.store.get_snapshot(retry_id)
    assert retry["dispatch_enabled"] is True
    assert retry["state"] == "running"
    assert retry["artifact"] == original["artifact"]
    assert set(retry["devices"]) == {"failed", "interrupted", "unknown"}
    previous = await manager.store.get_snapshot(original["job_id"])
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
            active = await manager.store.transition_device(job_id, device_id,
                expected={DeviceState.DISPATCHING}, target=DeviceState.DOWNLOADING,
                fields={"accepted_at": 1})
            if interrupted:
                await manager.resume_interrupted(job_id, device_id, attempt=1,
                    artifact_id=active["artifact"]["artifact_id"],
                    dispatch_revision=active["devices"][device_id]["dispatch_revision"],
                    validated_offset=1, reason="download_interrupted")
    return await manager.store.get_snapshot(job_id)


@pytest.mark.asyncio
@pytest.mark.parametrize("action", ["PUSH_FILES", "CANCEL_PUSH_JOB"])
async def test_individual_action_leaves_other_interrupted_device_untouched(manager, tmp_path, action):
    original = await two_device_job(manager, interrupted=True)
    other_before = await manager.assignment(original["job_id"], "D2")
    runtime, _, admin = operator_runtime(manager, tmp_path)
    await admin_action(runtime, admin, action, original["job_id"], target_devices=["D1"])
    assert await manager.assignment(original["job_id"], "D2") == other_before
    assert await manager.claim_next(["D2"]) is None
    current = await manager.store.get_snapshot(original["job_id"])
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
async def test_individual_resume_from_global_pause_keeps_other_device_paused_until_resume_all(manager, tmp_path, restart):
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
    await admin_action(runtime, admin, "PUSH_FILES", original["job_id"], target_devices=list(original["devices"]))
    assert (await manager.claim_next(["D2"]))["device_id"] == "D2"


@pytest.mark.asyncio
@pytest.mark.parametrize("action", ["PUSH_FILES", "CANCEL_PUSH_JOB"])
@pytest.mark.parametrize("targets", [[], ["unknown"], ["D1", "unknown"], "D1", [1], None])
async def test_invalid_explicit_targets_never_expand_to_all_devices(manager, tmp_path, action, targets):
    original = await two_device_job(manager, interrupted=True)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    assert await asyncio.wait_for(runtime.handle_admin_message(admin, {
        "type": action, "job_id": original["job_id"], "target_devices": targets,
    }), timeout=2)
    assert [message for message in admin.messages if message["type"] == "ERROR"]
    assert await manager.store.get_snapshot(original["job_id"]) == original
    assert session.ws.messages == []
    assert await manager.claim_next(["D1", "D2"]) is None


@pytest.mark.asyncio
async def test_restart_individual_resume_does_not_authorize_other_reconnecting_worker(manager, tmp_path):
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
    await admin_action(runtime, admin, "PUSH_FILES", original["job_id"], target_devices=list(original["devices"]))
    assert (await manager.claim_next(["D2"]))["device_id"] == "D2"


@pytest.mark.asyncio
async def test_offline_resume_rejected_but_cancel_allowed(manager, tmp_path):
    original = await timed_out_job(manager)
    runtime, _, admin = operator_runtime(manager, tmp_path)
    runtime.sessions.clear()
    await runtime.handle_admin_message(admin, {
        "type": "PUSH_FILES", "job_id": original["job_id"], "target_devices": ["D1"],
    })
    assert admin.messages[-1]["type"] == "ERROR"
    assert await manager.store.get_snapshot(original["job_id"]) == original
    admin.messages.clear()
    await admin_action(runtime, admin, "CANCEL_PUSH_JOB", original["job_id"], target_devices=["D1"])
    assert (await manager.store.get_snapshot(original["job_id"]))["devices"]["D1"]["cancel_requested"]


@pytest.mark.asyncio
async def test_resume_all_keeps_offline_timeout_waiting(manager, tmp_path):
    original = await two_device_job(manager, interrupted=True)
    runtime, _, admin = operator_runtime(manager, tmp_path)
    await admin_action(runtime, admin, "PUSH_FILES", original["job_id"], target_devices=list(original["devices"]))
    current = await manager.store.get_snapshot(original["job_id"])
    assert current["devices"]["D1"]["queue_reason"] == "resumable_replay"
    assert current["devices"]["D2"]["queue_reason"] == "download_interrupted"
    runtime.sessions["D2"] = runtime.sessions["D1"]
    assert await manager.claim_next(["D2"]) is None


@pytest.mark.asyncio
async def test_active_report_consumes_pending_resume_authorization(manager):
    active = await downloading_job(manager.store, manager)
    job_id = active["job_id"]
    await manager.store.mark_reconciling(job_id, "D1", expected={DeviceState.DOWNLOADING},
                                   reason="device_disconnect", deadline=now_ms() + 60_000)
    await manager.store._call(lambda conn: conn.execute(
        "UPDATE push_job_devices SET queue_reason='resumable_replay' WHERE job_id=?", (job_id,)))
    outcome, _ = await manager.reconcile_report(job_id, "D1", 1, "active", "downloading", None)
    assert outcome == "active"
    device = (await manager.store.get_snapshot(job_id))["devices"]["D1"]
    # A later client_restarted interruption must not inherit the consumed Resume.
    assert device["state"] == "downloading" and device["queue_reason"] is None


@pytest.mark.asyncio
async def test_reconcile_is_deferred_while_push_state_is_unavailable(manager, tmp_path):
    original = await timed_out_job(manager)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    session.push_state_available = False
    await admin_action(runtime, admin, "CANCEL_PUSH_JOB", original["job_id"])
    # An empty, unloaded client state would answer absent, which is not evidence.
    assert session.ws.messages == []
    assert (await manager.store.get_snapshot(original["job_id"]))["devices"]["D1"]["cancel_requested"]


@pytest.mark.asyncio
@pytest.mark.parametrize("claimed", [False, True])
@pytest.mark.parametrize("resume_online", [False, True])
async def test_restart_unsent_offline_target_can_be_cancelled_without_client(
    manager, tmp_path, claimed, resume_online,
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
        await admin_action(runtime, admin, "PUSH_FILES", job_id, target_devices=["D1", "D2"])
    else:
        runtime.sessions.clear()
    before = await manager.store.get_snapshot(job_id)
    offline = before["devices"]["D2"]
    assert offline["dispatch_revision"] is None
    assert offline["cancellable"] and not offline["resume_supported"]
    other_before = await manager.assignment(job_id, "D1")

    await admin_action(runtime, admin, "CANCEL_PUSH_JOB", job_id, target_devices=["D2"])
    cancelled = await manager.store.get_snapshot(job_id)
    target = cancelled["devices"]["D2"]
    assert target["state"] == "failed" and target["failure"]["code"] == "cancelled"
    assert not target["cancel_requested"] and not target["cancellable"] and not target["retryable"]
    assert (await manager.assignment(job_id, "D2"))["terminal_at"] is not None
    assert await manager.assignment(job_id, "D1") == other_before
    assert session.ws.messages == []
    await admin_action(runtime, admin, "CANCEL_PUSH_JOB", job_id, target_devices=["D2"])
    assert await manager.store.get_snapshot(job_id) == cancelled
    assert await manager.claim_next(["D2"]) is None


@pytest.mark.asyncio
async def test_cancel_all_unsent_restart_targets_finishes_job_and_releases_artifact(manager, tmp_path):
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
    finished = await manager.store.get_snapshot(job_id)
    assert finished["state"] == "failed" and finished["terminal_at"] is not None
    assert all(d["failure"]["code"] == "cancelled" for d in finished["devices"].values())
    assert session.ws.messages == []
    assert manager.store.gc_artifacts_sync(tmp_path, retry_window_ms=0, timestamp=now_ms() + 1) == [
        original["artifact"]["artifact_id"]
    ]
    assert not (tmp_path / "operators.zip").exists()


@pytest.mark.asyncio
@pytest.mark.parametrize("paused", ["restart", "interrupted", "ready"])
async def test_retry_request_replay_preserves_dispatch_authority(manager, tmp_path, paused):
    original = await completed(manager, tmp_path)
    runtime, session, admin = operator_runtime(manager, tmp_path)
    runtime.sessions = {device: session for device in ("failed", "interrupted", "unknown")}
    request_id = str(uuid.uuid4())
    if paused == "ready":
        _, retry = await manager.retry_failed(
            original["job_id"], request_id, artifact_root=tmp_path,
            online_devices=runtime.sessions,
        )
        retry_id = retry["job_id"]
    else:
        await admin_action(runtime, admin, "RETRY_FAILED_PUSH_JOB", original["job_id"],
                           client_request_id=request_id)
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
    before = await manager.store.get_snapshot(retry_id)
    rows_before = {d: await manager.assignment(retry_id, d) for d in before["devices"]}
    # A request replay must also work when none of the original targets are online.
    runtime.sessions.clear()
    await admin_action(runtime, admin, "RETRY_FAILED_PUSH_JOB", original["job_id"],
                       client_request_id=request_id)
    assert admin.messages[-1]["job_id"] == retry_id
    assert await manager.store.get_snapshot(retry_id) == before
    assert {d: await manager.assignment(retry_id, d) for d in before["devices"]} == rows_before

    if paused == "ready":
        runtime.sessions = {device: session for device in before["devices"]}
        await admin_action(runtime, admin, "PUSH_FILES", retry_id)
        assert (await manager.store.get_snapshot(retry_id))["dispatch_enabled"]
        assert (await manager.claim_next(["failed"]))["job"]["job_id"] == retry_id


@pytest.mark.asyncio
@pytest.mark.parametrize("restart", [False, True])
async def test_initial_dispatch_replay_does_not_resume_paused_targets(manager, tmp_path, restart):
    original = await two_device_job(manager, interrupted=True)
    if restart:
        manager.store.recover_startup_sync(
            accept_reconciliation_timeout_ms=1000, reconciliation_timeout_ms=1000,
        )
    runtime, session, admin = operator_runtime(manager, tmp_path)
    before = await manager.store.get_snapshot(original["job_id"])
    await admin_action(runtime, admin, "PUSH_FILES", original["job_id"])
    assert await manager.store.get_snapshot(original["job_id"]) == before
    assert admin.messages[-1]["type"] == "PUSH_FILES_SENT"
    assert admin.messages[-1]["resume"] is False
    assert session.ws.messages == []
    assert await manager.claim_next(["D1", "D2"]) is None
