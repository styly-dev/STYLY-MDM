"""Narrow recovery of still-owned reconciliation timeouts, never arbitrary history."""

import asyncio
import json
import uuid
from types import SimpleNamespace

import pytest

from styly_mdm import push_runtime
from styly_mdm.push_job_store import PushJobStore, StoreConflict, StoreNotFound, now_ms
from styly_mdm.push_jobs import DeviceState, ProgressEvent, ProtocolMode, canonicalize_create_request
from styly_mdm.transfer_registry import TransferKey
from push_test_helpers import CAPS, manager, session_runtime_for, Ws


async def ready(manager, root, devices=("D1",), destination="/sdcard/STYLY/content"):
    request = canonicalize_create_request({
        "client_request_id": str(uuid.uuid4()), "target_devices": list(devices),
        "mode": "push", "dest_path": destination,
        "source": {"display_name": "content", "declared_file_count": 1, "declared_total_bytes": 4},
    })
    _, job = await manager.store.create_job(
        request, {device: (ProtocolMode.JOB_V1, CAPS) for device in devices}, 60_000,
    )
    job_id = job["job_id"]
    await manager.store.start_upload(job_id)
    await manager.store.mark_packaging(job_id, 1, 4)
    artifact_id = str(uuid.uuid4())
    root.mkdir(exist_ok=True)
    (root / f"{artifact_id}.zip").write_bytes(b"data")
    return await manager.store.publish_artifact(job_id, {
        "artifact_id": artifact_id, "storage_name": f"{artifact_id}.zip",
        "display_filename": "content.zip", "byte_size": 4,
        "sha256": "a" * 64, "entry_count": 1,
    })


async def interrupted(manager, root, devices=("D1",), *, timeout=True):
    job = await ready(manager, root, devices)
    job_id = job["job_id"]
    await manager.store.enable_dispatch(job_id)
    for device in devices:
        assert (await manager.claim_next([device]))["device_id"] == device
        await manager.prepare_dispatch(job_id, device, accept_deadline=1)
        await manager.record_progress(job_id, device, ProgressEvent.ACCEPTED)
    await manager.mark_reconciling(
        job_id, "D1", expected={DeviceState.DOWNLOADING}, reason="device_disconnect", deadline=1,
    )
    if timeout:
        await manager.mark_unconfirmed(job_id, "D1", "old-process", "deadline", expected_deadline=1, observed_now=2)
    job = await manager.get_snapshot(job_id)
    report = {
        "job_id": job_id, "attempt": 1, "artifact_id": job["artifact"]["artifact_id"],
        "revision": job["devices"]["D1"]["dispatch_revision"],
        "validated_offset": 2, "reason": "download_interrupted",
        "status": "interrupted", "phase": "downloading",
    }
    return job, report


async def recover(manager, root, report, **overrides):
    evidence = {
        "attempt": report["attempt"], "artifact_id": report["artifact_id"],
        "dispatch_revision": report["revision"], "validated_offset": report["validated_offset"],
        "reason": report["reason"], "artifact_root": root,
    }
    evidence.update(overrides)
    return await manager.resume_interrupted(report["job_id"], "D1", **evidence)


def restart(manager):
    path = manager.store.path
    manager.store.close()
    manager.store = PushJobStore(path)
    manager.store.recover_startup_sync(
        accept_reconciliation_timeout_ms=60_000, reconciliation_timeout_ms=60_000,
    )


@pytest.mark.asyncio
@pytest.mark.parametrize("route", ["register", "reconcile"])
@pytest.mark.parametrize("after_restart", [False, True])
async def test_late_exact_report_preserves_manual_resume_through_both_routes(manager, tmp_path, route, after_restart):
    root = tmp_path / "push-artifacts"
    before, report = await interrupted(manager, root)
    original = await manager.assignment(before["job_id"], "D1")
    # An old grant cannot survive terminal timeout recovery.
    await manager.store._call(lambda conn: conn.execute(
        "UPDATE push_job_devices SET queue_reason='resumable_replay' WHERE job_id=?", (before["job_id"],),
    ))
    if after_restart:
        restart(manager)
    runtime, session = session_runtime_for(manager)
    published = []

    async def publish(snapshot):
        published.append(snapshot)

    runtime.publish = publish
    if route == "register":
        runtime.sessions.clear()
        runtime.device_locks = {}
        runtime.registration_candidates = {}
        runtime.pending_disconnects = set()
        runtime.disconnect_recovery_tasks = {}
        runtime.accept_reconciliation_timeout = 60
        runtime.reconciliation_timeout = 60
        ws = Ws()
        runtime.legacy.devices = {"D1": {"ws": ws}}
        runtime.note_registration_candidate(ws, "D1")
        await runtime.register_device(ws, {
            "device_id": "D1", "process_instance_id": str(uuid.uuid4()),
            "capabilities": list(CAPS), "push_runtime": {"active": report},
        }, "http://server")
        session = runtime.sessions["D1"]
    else:
        await runtime._handle_reconcile_report(session, "D1", report)
    assert not [message for message in session.ws.messages if message["type"] == "PUSH_RESUME_REJECTED"]
    current = await manager.get_snapshot(before["job_id"])
    row = await manager.assignment(before["job_id"], "D1")
    assert current["state"] == "running" and current["terminal_at"] is None
    assert row["state"] == "queued" and row["queue_reason"] == "download_interrupted"
    assert current["devices"]["D1"]["device_fence"] is None
    assert current["devices"]["D1"]["result"] is None
    for key in ("job_id", "attempt", "enqueue_seq", "artifact_id", "dispatch_revision", "accepted_at"):
        assert row[key] == original[key]
    for key in ("terminal_at", "failure_code", "failure_detail", "accept_deadline", "reconciliation_deadline", "reconciliation_reason"):
        assert row[key] is None
    assert row["validated_offset"] == 2
    assert published[-1] == current
    assert await manager.claim_next(["D1"]) is None
    assert await manager.expired_reconciliations(now_ms() + 10**8) == []
    with pytest.raises(StoreConflict):
        await manager.mark_unconfirmed(before["job_id"], "D1", None, "stale", expected_deadline=1, observed_now=2)
    restart(manager)
    assert manager.gc_artifacts_sync(root, retry_window_ms=0, timestamp=now_ms() + 10**8) == []
    assert await manager.claim_next(["D1"]) is None
    await manager.store.enable_dispatch(before["job_id"], ["D1"])
    assert (await manager.claim_next(["D1"]))["job"]["job_id"] == before["job_id"]
    replay = await manager.prepare_dispatch(before["job_id"], "D1", accept_deadline=100)
    assert replay["devices"]["D1"]["dispatch_revision"] == report["revision"]
    assert replay["devices"]["D1"]["validated_offset"] == 2


@pytest.mark.asyncio
@pytest.mark.parametrize("field,value", [
    ("attempt", True), ("attempt", 1.0), ("attempt", "1"), ("attempt", 2),
    ("artifact_id", None), ("artifact_id", 1), ("artifact_id", str(uuid.uuid4())),
    ("dispatch_revision", None), ("dispatch_revision", True), ("dispatch_revision", "1"),
    ("dispatch_revision", -1), ("dispatch_revision", "float"),
    ("validated_offset", True), ("validated_offset", 1.0), ("validated_offset", "1"),
    ("validated_offset", None), ("validated_offset", -1), ("validated_offset", 5),
])
async def test_late_recovery_rejects_inexact_identity_and_offsets(manager, tmp_path, field, value):
    before, report = await interrupted(manager, tmp_path)
    if value == "float":
        value = float(report["revision"])
    outcome, snapshots = await recover(manager, tmp_path, report, **{field: value})
    assert outcome == "rejected" and snapshots == [before]
    assert await manager.get_snapshot(before["job_id"]) == before


@pytest.mark.asyncio
@pytest.mark.parametrize("offset", [0, 4])
async def test_late_recovery_accepts_both_offset_boundaries(manager, tmp_path, offset):
    _, report = await interrupted(manager, tmp_path)
    outcome, [snapshot] = await recover(manager, tmp_path, report, validated_offset=offset)
    assert outcome == "requeued"
    assert snapshot["devices"]["D1"]["validated_offset"] == offset


@pytest.mark.asyncio
@pytest.mark.parametrize("job_id,device_id", [(str(uuid.uuid4()), "D1"), (None, "D2")])
async def test_late_recovery_cannot_select_another_assignment(manager, tmp_path, job_id, device_id):
    before, report = await interrupted(manager, tmp_path)
    with pytest.raises(StoreNotFound):
        await manager.resume_interrupted(job_id or before["job_id"], device_id, attempt=1,
            artifact_id=report["artifact_id"], dispatch_revision=report["revision"],
            validated_offset=2, artifact_root=tmp_path)
    assert await manager.get_snapshot(before["job_id"]) == before


@pytest.mark.asyncio
@pytest.mark.parametrize("fence", ["missing", "attempt", "foreign", "legacy", "opaque"])
async def test_late_recovery_requires_the_exact_job_v1_fence(manager, tmp_path, fence):
    job, report = await interrupted(manager, tmp_path)
    if fence == "missing":
        await manager.clear_matching_fence(job["job_id"], "D1", 1)
    elif fence == "opaque":
        await manager.add_opaque_fence("D1", json.dumps({"job_id": job["job_id"], "attempt": 1}), ProtocolMode.JOB_V1, None, "unknown")
    else:
        column, value = {"attempt": ("blocking_attempt", 2), "legacy": ("protocol_mode", "legacy"), "foreign": ("blocking_job_id", None)}[fence]
        if fence == "foreign":
            value = (await ready(manager, tmp_path, ("D2",)))["job_id"]
        await manager.store._call(lambda conn: conn.execute(
            f"UPDATE push_device_fences SET {column}=? WHERE device_id='D1'", (value,),
        ))
    before = await manager.get_snapshot(job["job_id"])
    fences = await manager.store._call(lambda conn: [dict(row) for row in conn.execute("SELECT * FROM push_device_fences")])
    assert (await recover(manager, tmp_path, report))[0] == "rejected"
    assert await manager.get_snapshot(job["job_id"]) == before
    assert await manager.store._call(lambda conn: [dict(row) for row in conn.execute("SELECT * FROM push_device_fences")]) == fences


@pytest.mark.asyncio
@pytest.mark.parametrize("condition", ["missing", "truncated", "deleted", "directory", "escape", "no_root"])
async def test_late_recovery_requires_retained_canonical_complete_bytes(manager, tmp_path, condition):
    root = tmp_path / "artifacts"
    job, report = await interrupted(manager, root)
    path = root / f"{report['artifact_id']}.zip"
    if condition == "missing":
        path.unlink()
    elif condition == "truncated":
        path.write_bytes(b"x")
    elif condition == "directory":
        path.unlink()
        path.mkdir()
    elif condition == "deleted":
        # A delayed GC unlink leaves real bytes, but its tombstone is decisive.
        await manager.store._call(lambda conn: conn.execute("UPDATE push_artifacts SET retention_state='deleted'"))
        assert path.is_file()
    elif condition == "escape":
        outside = tmp_path / "outside.zip"
        outside.write_bytes(b"data")
        await manager.store._call(lambda conn: conn.execute("UPDATE push_artifacts SET storage_name='../outside.zip'"))
    before = await manager.get_snapshot(job["job_id"])
    assert (await recover(manager, None if condition == "no_root" else root, report))[0] == "rejected"
    assert await manager.get_snapshot(job["job_id"]) == before


@pytest.mark.asyncio
@pytest.mark.parametrize("condition", ["cancel", "retry", "new_pending", "new_dispatched", "other_owner", "failed", "interrupted", "succeeded", "other_failure"])
async def test_late_recovery_cannot_overrule_a_later_decision(manager, tmp_path, condition):
    job, report = await interrupted(manager, tmp_path)
    job_id = job["job_id"]
    if condition == "cancel":
        await manager.cancel_interrupted(job_id, "D1")
    elif condition == "retry":
        await manager.retry_failed(job_id, str(uuid.uuid4()), artifact_root=tmp_path, online_devices=["D1"])
    elif condition in {"new_pending", "new_dispatched", "other_owner"}:
        later = await ready(manager, tmp_path, destination="/sdcard/STYLY/other" if condition == "other_owner" else job["dest_path"])
        if condition != "new_pending":
            await manager.store._call(lambda conn: conn.execute(
                "UPDATE push_job_devices SET state=?, dispatch_revision=1 WHERE job_id=?",
                ("downloading" if condition == "other_owner" else "succeeded", later["job_id"]),
            ))
    elif condition == "other_failure":
        await manager.store._call(lambda conn: conn.execute("UPDATE push_job_devices SET failure_code='artifact_missing' WHERE job_id=?", (job_id,)))
    else:
        await manager.store._call(lambda conn: conn.execute("UPDATE push_jobs SET state=? WHERE job_id=?", (condition, job_id)))
    before = await manager.get_snapshot(job_id)
    assert (await recover(manager, tmp_path, report))[0] == "rejected"
    assert await manager.get_snapshot(job_id) == before


@pytest.mark.asyncio
@pytest.mark.parametrize("other_state", ["downloading", "reconciling"])
async def test_multidevice_recovery_revises_every_affected_snapshot(manager, tmp_path, other_state):
    job, report = await interrupted(manager, tmp_path, ("D1", "D2"))
    if other_state == "reconciling":
        await manager.mark_reconciling(job["job_id"], "D2", expected={DeviceState.DOWNLOADING}, reason="disconnect", deadline=100)
    later = await ready(manager, tmp_path, destination="/sdcard/STYLY/other")
    before = await manager.get_snapshot(job["job_id"])
    outcome, snapshots = await recover(manager, tmp_path, report)
    assert outcome == "requeued" and {s["job_id"] for s in snapshots} == {job["job_id"], later["job_id"]}
    for snapshot in snapshots:
        original = before if snapshot["job_id"] == job["job_id"] else later
        assert snapshot["revision"] == original["revision"] + 1
        assert snapshot == await manager.get_snapshot(snapshot["job_id"])
    assert snapshots[0]["devices"]["D2"] == before["devices"]["D2"]
    assert await manager.claim_next(["D1"]) is None


@pytest.mark.asyncio
@pytest.mark.parametrize("reason", ["download_interrupted", "server_lease_revoked", "storage_write_failed", "client_restarted"])
@pytest.mark.parametrize("consume", ["claim", "accepted", "active", "disconnect"])
async def test_delayed_interruption_preserves_only_an_unconsumed_resume(manager, tmp_path, reason, consume):
    job, report = await interrupted(manager, tmp_path, timeout=False)
    restart(manager)
    await manager.store.enable_dispatch(job["job_id"], ["D1"])
    if consume == "disconnect":
        await manager.settle_disconnect("D1", accept_timeout_ms=1000, reconciliation_timeout_ms=1000)
    elif consume == "accepted":
        await manager.record_progress(job["job_id"], "D1", ProgressEvent.ACCEPTED)
    elif consume == "active":
        await manager.reconcile_report(job["job_id"], "D1", 1, "active", "downloading", None)
    if consume in {"accepted", "active"}:
        await manager.mark_reconciling(job["job_id"], "D1", expected={DeviceState.DOWNLOADING}, reason="device_disconnect", deadline=100)
    outcome, [snapshot] = await recover(manager, tmp_path, report, reason=reason)
    assert outcome == "requeued"
    assert (snapshot["devices"]["D1"]["queue_reason"] == "resumable_replay") == (consume == "claim")
    if consume == "claim":
        assert await manager.claim_next(["D1"]) is not None
        await manager.prepare_dispatch(job["job_id"], "D1", accept_deadline=100)
        await manager.record_progress(job["job_id"], "D1", ProgressEvent.ACCEPTED)
        await manager.mark_reconciling(job["job_id"], "D1", expected={DeviceState.DOWNLOADING}, reason="device_disconnect", deadline=100)
        outcome, [snapshot] = await recover(manager, tmp_path, report, reason=reason)
        assert outcome == "requeued" and snapshot["devices"]["D1"]["manual_wait"]
    assert await manager.claim_next(["D1"]) is None


@pytest.mark.asyncio
@pytest.mark.parametrize("timeout_first", [False, True])
async def test_timeout_and_report_transactions_converge_to_manual_wait(manager, tmp_path, timeout_first):
    job, report = await interrupted(manager, tmp_path, timeout=False)
    timeout = manager.mark_unconfirmed(job["job_id"], "D1", None, "timeout", expected_deadline=1, observed_now=2)
    recovery = recover(manager, tmp_path, report)
    results = await asyncio.gather(*( [timeout, recovery] if timeout_first else [recovery, timeout]), return_exceptions=True)
    if not timeout_first:
        assert isinstance(results[1], StoreConflict)
    current = await manager.get_snapshot(job["job_id"])
    assert current["devices"]["D1"]["manual_wait"]
    assert current["devices"]["D1"]["device_fence"] is None
    assert await manager.claim_next(["D1"]) is None


@pytest.mark.asyncio
@pytest.mark.parametrize("recovery_first", [False, True])
@pytest.mark.parametrize("decision", ["retry", "cancel", "gc"])
async def test_recovery_orders_atomically_with_retry_cancel_and_gc(manager, tmp_path, recovery_first, decision):
    job, report = await interrupted(manager, tmp_path)
    if decision == "retry":
        action = manager.retry_failed(job["job_id"], str(uuid.uuid4()), artifact_root=tmp_path, online_devices=["D1"])
    elif decision == "cancel":
        action = manager.cancel_interrupted(job["job_id"], "D1")
    else:
        # GC uses the same DB worker, committing its tombstone before filesystem cleanup.
        async def gc():
            return await asyncio.to_thread(manager.gc_artifacts_sync, tmp_path, retry_window_ms=0, timestamp=now_ms() + 10**8)
        action = gc()
    if decision == "gc":
        # Await ordered operations because GC submits from a separate thread.
        if recovery_first:
            recovery = await recover(manager, tmp_path, report)
            action_result = await action
        else:
            action_result = await action
            recovery = await recover(manager, tmp_path, report)
    else:
        operations = [recover(manager, tmp_path, report), action] if recovery_first else [action, recover(manager, tmp_path, report)]
        results = await asyncio.gather(*operations, return_exceptions=True)
        recovery, action_result = results if recovery_first else reversed(results)
    assert recovery[0] == ("requeued" if recovery_first else "rejected")
    current = await manager.get_snapshot(job["job_id"])
    assert current["devices"]["D1"]["state"] == ("queued" if recovery_first else "unconfirmed")
    if recovery_first and decision == "retry":
        assert isinstance(action_result, StoreConflict)
    if recovery_first and decision == "gc":
        assert action_result == []
        assert (await manager.store.artifact_record(report["artifact_id"]))["retention_state"] == "retained"
    if decision == "cancel":
        assert current["devices"]["D1"]["cancel_requested"]
    assert await manager.claim_next(["D1"]) is None


@pytest.mark.asyncio
async def test_duplicate_report_preserves_revision_and_replacement_waiter(manager, tmp_path):
    _, report = await interrupted(manager, tmp_path)
    runtime, session = session_runtime_for(manager)
    runtime.artifacts = SimpleNamespace(artifact_root=tmp_path)
    key = TransferKey("push", "D1", report["job_id"], 1)
    original = asyncio.get_running_loop().create_future()
    replacement = asyncio.get_running_loop().create_future()
    runtime.transfers.register(key, original)
    resume = manager.resume_interrupted

    async def replace_after_commit(*args, **kwargs):
        result = await resume(*args, **kwargs)
        runtime.transfers.release_exact(key, "old_done")
        runtime.transfers.register(key, replacement)
        return result

    manager.resume_interrupted = replace_after_commit
    await runtime._handle_reconcile_report(session, "D1", report)
    assert original.result() == "old_done" and not replacement.done()
    manager.resume_interrupted = resume
    before = await manager.get_snapshot(report["job_id"])
    await runtime._handle_reconcile_report(session, "D1", report)
    assert await manager.get_snapshot(report["job_id"]) == before
    assert not replacement.done() and session.ws.messages == []


@pytest.mark.asyncio
async def test_timeout_cleanup_cannot_release_recovered_same_attempt_waiter(manager, tmp_path, monkeypatch):
    job, report = await interrupted(manager, tmp_path, timeout=False)
    runtime, session = session_runtime_for(manager)
    runtime.artifacts = SimpleNamespace(artifact_root=tmp_path)
    key = TransferKey("push", "D1", job["job_id"], 1)
    original = asyncio.get_running_loop().create_future()
    replacement = asyncio.get_running_loop().create_future()
    runtime.transfers.register(key, original)
    mark = manager.mark_unconfirmed
    published = asyncio.Event()

    async def recover_before_timeout_cleanup(*args, **kwargs):
        snapshots = await mark(*args, **kwargs)
        await runtime._handle_reconcile_report(session, "D1", report)
        assert original.done()
        await manager.store.enable_dispatch(job["job_id"], ["D1"])
        assert await manager.claim_next(["D1"]) is not None
        runtime.transfers.register(key, replacement)
        await manager.prepare_dispatch(job["job_id"], "D1", accept_deadline=now_ms() + 60_000)
        return snapshots

    async def publish(snapshot):
        if snapshot["devices"]["D1"]["state"] == "unconfirmed":
            published.set()

    monkeypatch.setattr(manager, "mark_unconfirmed", recover_before_timeout_cleanup)
    monkeypatch.setattr(push_runtime, "_RECONCILIATION_POLL_INTERVAL", 0)
    runtime.publish = publish
    task = asyncio.create_task(runtime._reconciliation_housekeeping())
    try:
        await asyncio.wait_for(published.wait(), 1)
        assert not replacement.done()
        assert (await manager.assignment(job["job_id"], "D1"))["state"] == "dispatching"
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
