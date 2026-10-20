"""Pre-dispatch failures settle durable assignments and their canonical UI state."""

import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import uuid

import pytest

from styly_mdm.push_job_manager import PushJobManager
from styly_mdm.push_job_store import (
    PushJobStore,
    StoreConflict,
    UploadDeadlineExpired,
    now_ms,
)
from styly_mdm.push_jobs import DeviceState, InvalidTransition, JobState, ProtocolMode, canonicalize_create_request


@pytest.fixture
def store(tmp_path):
    value = PushJobStore(tmp_path / "jobs.sqlite3")
    yield value
    value.close()


async def create(store, targets=("D1", "D2")):
    request = canonicalize_create_request({
        "client_request_id": str(uuid.uuid4()), "target_devices": list(targets),
        "mode": "push", "dest_path": "/sdcard/STYLY/content",
        "source": {"display_name": "content", "declared_file_count": 1,
                   "declared_total_bytes": 4},
    })
    _, job = await store.create_job(request, {
        device: (ProtocolMode.JOB_V1, ["push_job_id_v1"]) for device in targets
    }, 600_000)
    return job


async def ready(store, root, targets=("D1", "D2")):
    job = await create(store, targets)
    job_id = job["job_id"]
    await store.start_upload(job_id)
    await store.mark_packaging(job_id, 1, 4)
    artifact_id = str(uuid.uuid4())
    storage_name = artifact_id + ".zip"
    (root / storage_name).write_bytes(b"data")
    return await store.publish_artifact(job_id, {
        "artifact_id": artifact_id, "storage_name": storage_name,
        "display_filename": "content.zip", "byte_size": 4,
        "sha256": "a" * 64, "entry_count": 1,
    })


async def records(store, job_id):
    return await store._call(lambda conn: (
        dict(conn.execute("SELECT * FROM push_jobs WHERE job_id=?", (job_id,)).fetchone()),
        [dict(row) for row in conn.execute(
            "SELECT * FROM push_job_devices WHERE job_id=? ORDER BY target_ordinal", (job_id,))],
        [dict(row) for row in conn.execute("SELECT * FROM push_device_fences ORDER BY device_id")],
    ))


def recover(store):
    return store.recover_startup_sync(
        accept_reconciliation_timeout_ms=1000, reconciliation_timeout_ms=2000)


def assert_settled(snapshot, raw, *, state, code, detail=None):
    job, devices, _ = raw
    assert snapshot["state"] == state
    assert snapshot["failure"]["code"] == code
    if detail is not None:
        assert snapshot["failure"]["detail"] == detail
    assert snapshot["aggregate"][state] == len(devices)
    assert snapshot["aggregate"]["queued"] == 0
    for device in devices:
        assert device["state"] == state
        assert device["failure_code"] == job["failure_code"]
        assert device["failure_detail"] == job["failure_detail"]
        assert device["terminal_at"] == job["terminal_at"]
        assert device["updated_at"] == job["updated_at"]
        assert device["queue_reason"] is None
        assert device["dispatch_revision"] is None
        assignment = snapshot["devices"][device["device_id"]]
        assert assignment["failure"] == snapshot["failure"]
        assert not assignment["resume_required"]
        assert not assignment["manual_wait"]


@pytest.mark.asyncio
@pytest.mark.parametrize("phase", ["created", "uploading", "packaging"])
@pytest.mark.parametrize("terminal", [JobState.FAILED, JobState.INTERRUPTED])
async def test_fail_pre_dispatch_settles_job_and_queued_targets(store, phase, terminal):
    job = await create(store)
    if phase != "created":
        await store.start_upload(job["job_id"])
    if phase == "packaging":
        await store.mark_packaging(job["job_id"], 1, 4)
    before = await store.get_snapshot(job["job_id"])
    snapshot = await store.fail_pre_dispatch(job["job_id"], terminal, "upload_failed", "source rejected")
    assert snapshot["revision"] == before["revision"] + 1
    assert_settled(snapshot, await records(store, job["job_id"]),
                   state=terminal.value, code="upload_failed", detail="source rejected")
    assert all(not item["retryable"] for item in snapshot["devices"].values())


@pytest.mark.asyncio
async def test_ready_failure_settles_targets_and_retains_artifact_retry(store, tmp_path):
    job = await ready(store, tmp_path)
    snapshot = await store.fail_pre_dispatch(job["job_id"], JobState.FAILED,
                                             "dispatch_rejected", "operator request rejected")
    assert_settled(snapshot, await records(store, job["job_id"]),
                   state="failed", code="dispatch_rejected")
    assert snapshot["artifact"] == job["artifact"]
    assert all(item["retryable"] for item in snapshot["devices"].values())


ROUTES = ["start_upload_expired", "expire_created", "startup_created",
          "startup_uploading", "startup_packaging", "missing_ready_artifact"]


async def prepare_route(store, root, route, targets=("D1", "D2")):
    job = await ready(store, root, targets) if route == "missing_ready_artifact" else await create(store, targets)
    job_id = job["job_id"]
    if route in {"startup_uploading", "startup_packaging"}:
        await store.start_upload(job_id)
        if route == "startup_packaging":
            await store.mark_packaging(job_id, 1, 4)
    if route in {"start_upload_expired", "expire_created", "startup_created"}:
        await store._call(lambda conn: conn.execute(
            "UPDATE push_jobs SET create_expires_at=1 WHERE job_id=?", (job_id,)))
    if route == "missing_ready_artifact":
        (root / (job["artifact"]["artifact_id"] + ".zip")).unlink()
    return await store.get_snapshot(job_id)


async def invoke_route(store, root, route, job_id):
    if route == "start_upload_expired":
        with pytest.raises(UploadDeadlineExpired) as expired:
            await store.start_upload(job_id)
        return expired.value.snapshot
    if route == "expire_created":
        return await store.expire_created(job_id, expected_deadline=1)
    if route.startswith("startup_"):
        snapshots, cleanup = recover(store)
        assert job_id in cleanup
    else:
        snapshots = PushJobManager(store).reconcile_missing_artifacts_sync(root)
    return next(snapshot for snapshot in snapshots if snapshot["job_id"] == job_id)


@pytest.mark.asyncio
@pytest.mark.parametrize("route", ROUTES)
async def test_every_pre_dispatch_terminal_route_settles_targets(store, tmp_path, route):
    before = await prepare_route(store, tmp_path, route)
    snapshot = await invoke_route(store, tmp_path, route, before["job_id"])
    code = ("artifact_missing" if route == "missing_ready_artifact" else
            "server_restart" if route in {"startup_uploading", "startup_packaging"} else
            "upload_not_started_timeout")
    assert snapshot == await store.get_snapshot(before["job_id"])
    assert snapshot["revision"] > before["revision"]
    assert_settled(snapshot, await records(store, before["job_id"]),
                   state="failed" if route == "missing_ready_artifact" else "interrupted", code=code)
    if route == "missing_ready_artifact":
        assert snapshot["artifact"]["artifact_id"] == before["artifact"]["artifact_id"]
        assert all(not item["retryable"] for item in snapshot["devices"].values())


@pytest.mark.asyncio
async def test_created_expiry_preserves_exact_deadline_and_state_guards(store):
    job = await create(store)
    before = await records(store, job["job_id"])
    assert await store.expire_created(job["job_id"]) is None
    assert await store.expire_created(job["job_id"], expected_deadline=1) is None
    assert await records(store, job["job_id"]) == before
    await store.start_upload(job["job_id"])
    uploading = await records(store, job["job_id"])
    assert await store.expire_created(job["job_id"]) is None
    assert await records(store, job["job_id"]) == uploading


@pytest.mark.asyncio
@pytest.mark.parametrize("route", ["fail_pre_dispatch", *ROUTES])
async def test_pre_dispatch_settlement_rolls_back_job_and_targets_together(store, tmp_path, route):
    job = await prepare_route(store, tmp_path, route)
    before = await records(store, job["job_id"])
    await store._call(lambda conn: conn.execute("""
        CREATE TRIGGER reject_terminal_target BEFORE UPDATE OF state ON push_job_devices
        WHEN NEW.state IN ('failed', 'interrupted') AND NEW.device_id='D2'
        BEGIN SELECT RAISE(ABORT, 'injected target failure'); END
    """))
    with pytest.raises(sqlite3.IntegrityError, match="injected target failure"):
        if route == "fail_pre_dispatch":
            await store.fail_pre_dispatch(job["job_id"], JobState.FAILED, "upload_failed", "rejected")
        else:
            await invoke_route(store, tmp_path, route, job["job_id"])
    assert await records(store, job["job_id"]) == before


@pytest.mark.asyncio
async def test_job_update_failure_rolls_back_target_settlement(store):
    job = await create(store)
    before = await records(store, job["job_id"])
    await store._call(lambda conn: conn.execute("""
        CREATE TRIGGER reject_terminal_job BEFORE UPDATE OF state ON push_jobs
        WHEN NEW.state='failed'
        BEGIN SELECT RAISE(ABORT, 'injected job failure'); END
    """))
    with pytest.raises(sqlite3.IntegrityError, match="injected job failure"):
        await store.fail_pre_dispatch(job["job_id"], JobState.FAILED, "upload_failed", "rejected")
    assert await records(store, job["job_id"]) == before


@pytest.mark.asyncio
async def test_historical_revision_failure_rolls_back_repaired_targets(store):
    job = await create(store)
    await store._call(lambda conn: conn.execute(
        "UPDATE push_jobs SET state='failed', terminal_at=11, updated_at=12, "
        "failure_code='historic', failure_detail='original' WHERE job_id=?", (job["job_id"],)))
    before = await records(store, job["job_id"])
    await store._call(lambda conn: conn.execute("""
        CREATE TRIGGER reject_repair_revision BEFORE UPDATE OF revision ON push_jobs
        BEGIN SELECT RAISE(ABORT, 'injected repair revision failure'); END
    """))
    with pytest.raises(sqlite3.IntegrityError, match="injected repair revision failure"):
        recover(store)
    assert await records(store, job["job_id"]) == before


@pytest.mark.asyncio
@pytest.mark.parametrize("terminal", ["failed", "interrupted"])
@pytest.mark.parametrize("artifact_backed", [False, True])
async def test_startup_repairs_historical_queued_targets_once_preserving_terminal_history(
    store, tmp_path, terminal, artifact_backed,
):
    job = await ready(store, tmp_path) if artifact_backed else await create(store)
    job_id = job["job_id"]
    code = "artifact_missing" if artifact_backed else "historic_upload_failure"
    await store._call(lambda conn: conn.execute(
        "UPDATE push_jobs SET state=?, revision=91, updated_at=222, terminal_at=111, "
        "failure_code=?, failure_detail='original failure detail' WHERE job_id=?",
        (terminal, code, job_id)))
    before_job = (await records(store, job_id))[0]
    snapshots, _ = recover(store)
    snapshot = next(item for item in snapshots if item["job_id"] == job_id)
    after = await records(store, job_id)
    assert {key: value for key, value in after[0].items() if key != "revision"} == {
        key: value for key, value in before_job.items() if key != "revision"}
    assert snapshot["revision"] == 92
    assert_settled(snapshot, after, state=terminal, code=code, detail="original failure detail")
    store.close()
    reopened = PushJobStore(store.path)
    try:
        assert recover(reopened) == ([], [])
        assert await records(reopened, job_id) == after
    finally:
        reopened.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("unavailable", ["unpublished", "deleted", "missing_record", "known_missing",
                                         "known_missing_target", "retained"])
async def test_snapshot_retry_requires_published_retained_artifact(store, tmp_path, unavailable):
    job = await create(store) if unavailable == "unpublished" else await ready(store, tmp_path)
    job_id = job["job_id"]
    await store._call(lambda conn: conn.execute(
        "UPDATE push_jobs SET state='failed', terminal_at=1 WHERE job_id=?", (job_id,)))
    await store._call(lambda conn: conn.execute(
        "UPDATE push_job_devices SET state='failed', failure_code='upload_failed' WHERE job_id=?", (job_id,)))
    if unavailable == "deleted":
        await store._call(lambda conn: conn.execute(
            "UPDATE push_artifacts SET retention_state='deleted' WHERE artifact_id=?",
            (job["artifact"]["artifact_id"],)))
    elif unavailable == "missing_record":
        # Model an older database restored without its artifact row.
        def remove_record(conn):
            conn.execute("PRAGMA foreign_keys=OFF")
            conn.execute("DELETE FROM push_artifacts WHERE artifact_id=?", (job["artifact"]["artifact_id"],))
            conn.execute("PRAGMA foreign_keys=ON")
        await store._call(remove_record)
    elif unavailable in {"known_missing", "known_missing_target"}:
        if unavailable == "known_missing":
            await store._call(lambda conn: conn.execute(
                "UPDATE push_jobs SET failure_code='artifact_missing' WHERE job_id=?", (job_id,)))
        await store._call(lambda conn: conn.execute(
            "UPDATE push_job_devices SET failure_code='artifact_missing' WHERE job_id=?", (job_id,)))
    snapshot = await store.get_snapshot(job_id)
    assert all(item["retryable"] is (unavailable == "retained") for item in snapshot["devices"].values())


@pytest.mark.asyncio
@pytest.mark.parametrize("filesystem_failure", ["missing", "truncated"])
async def test_retry_endpoint_still_validates_retained_artifact_bytes(store, tmp_path, filesystem_failure):
    job = await ready(store, tmp_path)
    job_id = job["job_id"]
    await store._call(lambda conn: conn.execute(
        "UPDATE push_jobs SET state='completed_with_errors', terminal_at=1 WHERE job_id=?", (job_id,)))
    await store._call(lambda conn: conn.execute(
        "UPDATE push_job_devices SET state='failed', failure_code='download_failed' WHERE job_id=?", (job_id,)))
    path = tmp_path / (job["artifact"]["artifact_id"] + ".zip")
    path.unlink() if filesystem_failure == "missing" else path.write_bytes(b"")
    assert all(item["retryable"] for item in (await store.get_snapshot(job_id))["devices"].values())
    before = await records(store, job_id)
    with pytest.raises(StoreConflict, match="artifact"):
        await PushJobManager(store).retry_failed(
            job_id, str(uuid.uuid4()), artifact_root=tmp_path, online_devices={"D1", "D2"})
    assert await records(store, job_id) == before
    assert await store._call(lambda conn: conn.execute("SELECT COUNT(*) FROM push_jobs").fetchone()[0]) == 1


PROTECTED_EVIDENCE = [
    ("job", "dispatch_started_at", 7),
    ("job", "dispatch_enabled", 1),
    ("device", "dispatch_revision", 7),
    ("device", "dispatch_capability_snapshot_json", "[]"),
    ("device", "accepted_at", 7),
    ("device", "accept_deadline", 7),
    ("device", "transfer_completed_at", 7),
    ("device", "validation_started_at", 7),
    ("device", "validation_completed_at", 7),
    ("device", "apply_started_at", 7),
    ("device", "validated_offset", 1),
    *[("device", "state", state) for state in
      ("waiting_transfer", "dispatching", "downloading", "validating", "applying", "reconciling", "unconfirmed")],
    ("fence", "exact", None),
]


async def add_evidence(store, job_id, evidence):
    scope, field, value = evidence
    if scope == "fence":
        await store._call(lambda conn: conn.execute(
            "INSERT INTO push_device_fences(device_id,blocking_job_id,blocking_attempt,"
            "protocol_mode,reason,created_at,updated_at) VALUES ('D2',?,1,'job_v1','unknown',11,12)",
            (job_id,)))
    elif scope == "job":
        await store._call(lambda conn: conn.execute(
            f"UPDATE push_jobs SET {field}=? WHERE job_id=?", (value, job_id)))
    else:
        await store._call(lambda conn: conn.execute(
            f"UPDATE push_job_devices SET {field}=? WHERE job_id=? AND device_id='D2'", (value, job_id)))


@pytest.mark.asyncio
@pytest.mark.parametrize("evidence", PROTECTED_EVIDENCE)
async def test_live_failure_rejects_any_dispatch_evidence_without_touching_rows(store, evidence):
    job = await create(store)
    await add_evidence(store, job["job_id"], evidence)
    before = await records(store, job["job_id"])
    with pytest.raises((StoreConflict, InvalidTransition)):
        await store.fail_pre_dispatch(job["job_id"], JobState.FAILED, "upload_failed", "must not overwrite")
    assert await records(store, job["job_id"]) == before


@pytest.mark.asyncio
@pytest.mark.parametrize("state", ["running", "reconciling", "succeeded", "completed_with_errors", "failed", "interrupted"])
async def test_live_failure_rejects_non_predispatch_job_states(store, state):
    job = await create(store)
    await store._call(lambda conn: conn.execute(
        "UPDATE push_jobs SET state=? WHERE job_id=?", (state, job["job_id"])))
    before = await records(store, job["job_id"])
    with pytest.raises((StoreConflict, InvalidTransition)):
        await store.fail_pre_dispatch(job["job_id"], JobState.FAILED, "upload_failed", "must not overwrite")
    assert await records(store, job["job_id"]) == before


@pytest.mark.asyncio
@pytest.mark.parametrize("evidence", PROTECTED_EVIDENCE)
async def test_historical_repair_preserves_entire_job_with_dispatch_evidence(store, evidence):
    job = await create(store)
    await store._call(lambda conn: conn.execute(
        "UPDATE push_jobs SET state='failed', terminal_at=11, updated_at=12, "
        "failure_code='historic', failure_detail='original' WHERE job_id=?", (job["job_id"],)))
    await add_evidence(store, job["job_id"], evidence)
    before = await records(store, job["job_id"])
    snapshots, cleanup = recover(store)
    # Existing recovery owns active states; the historical repair must never
    # rewrite their evidence or settle their queued sibling.
    after = await records(store, job["job_id"])
    assert after[0] == before[0]
    assert after[1][0] == before[1][0]
    assert after[2] == before[2]
    if evidence[1] != "state" or evidence[2] == "unconfirmed":
        assert after == before
        assert not snapshots and not cleanup


@pytest.mark.asyncio
@pytest.mark.parametrize("route", ["startup_uploading", "missing_ready_artifact"])
@pytest.mark.parametrize("evidence", [("device", "dispatch_revision", 7), ("fence", "exact", None)])
async def test_startup_and_missing_ready_routes_preserve_dispatch_evidence(store, tmp_path, route, evidence):
    job = await prepare_route(store, tmp_path, route)
    await add_evidence(store, job["job_id"], evidence)
    before = await records(store, job["job_id"])
    if route == "startup_uploading":
        assert recover(store) == ([], [])
    else:
        assert PushJobManager(store).reconcile_missing_artifacts_sync(tmp_path) == []
    assert await records(store, job["job_id"]) == before


@pytest.mark.asyncio
@pytest.mark.parametrize("fence_kind", ["other_job", "opaque", "other_attempt"])
async def test_unrelated_fence_survives_predispatch_settlement(store, fence_kind):
    job = await create(store)
    other = await create(store, ("other",))
    def insert(conn):
        conn.execute(
            "INSERT INTO push_device_fences(device_id,blocking_job_id,blocking_opaque_identity,"
            "blocking_attempt,protocol_mode,reason,created_at,updated_at) "
            "VALUES ('D1',?,?,?,'job_v1','untouched',11,12)",
            (None if fence_kind == "opaque" else other["job_id"] if fence_kind == "other_job" else job["job_id"],
             "opaque-worker" if fence_kind == "opaque" else None,
             None if fence_kind == "opaque" else 2 if fence_kind == "other_attempt" else 1))
    await store._call(insert)
    fences = (await records(store, job["job_id"]))[2]
    snapshot = await store.fail_pre_dispatch(job["job_id"], JobState.FAILED, "upload_failed", "source rejected")
    assert_settled(snapshot, await records(store, job["job_id"]), state="failed", code="upload_failed")
    assert (await records(store, job["job_id"]))[2] == fences


@pytest.mark.asyncio
async def test_historical_repair_preserves_safe_terminal_targets_and_untouched_revision(store):
    job = await create(store)
    job_id = job["job_id"]
    def historical(conn):
        conn.execute("UPDATE push_jobs SET state='failed', revision=8, terminal_at=11, updated_at=12, "
                     "failure_code='historic', failure_detail='original' WHERE job_id=?", (job_id,))
        conn.execute("UPDATE push_job_devices SET state='failed', failure_code='cancelled', "
                     "failure_detail='already terminal', terminal_at=3, updated_at=4, queue_reason=NULL "
                     "WHERE job_id=? AND device_id='D2'", (job_id,))
    await store._call(historical)
    terminal_before = (await records(store, job_id))[1][1]
    snapshots, _ = recover(store)
    assert len(snapshots) == 1 and snapshots[0]["revision"] == 9
    after = await records(store, job_id)
    assert after[1][1] == terminal_before
    assert after[1][0]["state"] == "failed"
    assert after[1][0]["failure_code"] == "historic"
    assert recover(store) == ([], [])
    assert await records(store, job_id) == after


@pytest.mark.asyncio
@pytest.mark.parametrize("route", ["fail_pre_dispatch", *ROUTES])
async def test_store_snapshots_render_real_console_failure_resume_and_exact_fence(store, tmp_path, route):
    node = shutil.which("node")
    if node is None:
        pytest.skip("Node.js is required for the production console integration")
    manager = PushJobManager(store)
    fenced = await ready(store, tmp_path, ("D1",))
    await store.enable_dispatch(fenced["job_id"])
    assert (await manager.claim_next(["D1"]))["job"]["job_id"] == fenced["job_id"]
    await manager.prepare_dispatch(fenced["job_id"], "D1", accept_deadline=now_ms() + 1000)
    await store.transition_device(fenced["job_id"], "D1", expected={DeviceState.DISPATCHING},
                                  target=DeviceState.RECONCILING)
    await store.transition_device(fenced["job_id"], "D1", expected={DeviceState.RECONCILING},
                                  target=DeviceState.UNCONFIRMED,
                                  fields={"failure_code": "timeout", "terminal_at": now_ms()})
    await store._call(lambda conn: conn.execute(
        "INSERT INTO push_device_fences(device_id,blocking_job_id,blocking_attempt,protocol_mode,"
        "reason,created_at,updated_at) VALUES ('D1',?,1,'job_v1','timeout',11,12)",
        (fenced["job_id"],)))
    failed = await prepare_route(store, tmp_path, route, targets=("D1",))
    if route == "fail_pre_dispatch":
        failed = await store.fail_pre_dispatch(failed["job_id"], JobState.FAILED,
                                             "upload_failed", "archive could not be uploaded")
    else:
        failed = await invoke_route(store, tmp_path, route, failed["job_id"])
    paused = await ready(store, tmp_path, ("D1",))
    fixtures = {"failed": failed, "paused": paused,
                "expectedDetail": failed["failure"]["detail"],
                "fenced": await store.get_snapshot(fenced["job_id"])}
    result = subprocess.run(
        [node, "--test", "--test-name-pattern", "store snapshot",
         str(Path(__file__).with_name("test_push_jobs_v1_console.js"))],
        env={**os.environ, "PUSH_LIFECYCLE_FIXTURES": json.dumps(fixtures)},
        capture_output=True, text=True, timeout=30,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert "store snapshot" in result.stdout
