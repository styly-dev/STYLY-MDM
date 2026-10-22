"""Regression tests for Push lifecycle ownership and recovery boundaries."""

import asyncio
import sqlite3
import threading
import types
import uuid

import pytest

from styly_mdm import push_runtime, push_scheduler
from styly_mdm.push_job_manager import PushJobManager
from styly_mdm.push_job_store import PushJobStore, StoreConflict, now_ms
from styly_mdm.push_jobs import DeviceState
from styly_mdm.push_runtime import PushRuntime
from styly_mdm.push_scheduler import LiveSession, PushScheduler
from styly_mdm.push_transfer_leases import PushTransferLeases, TRANSFER_IDLE_SECONDS
from styly_mdm.transfer_registry import TransferKey, TransferRegistry
from test_push_review_connection import Ws, downloading_job, ready_job


@pytest.fixture
def manager(tmp_path):
    store = PushJobStore(tmp_path / "push_jobs.sqlite3")
    try:
        yield PushJobManager(store)
    finally:
        store.close()


def _registration_runtime(manager, ws):
    runtime = object.__new__(PushRuntime)
    runtime.pending_disconnects = set()
    runtime.disconnect_recovery_tasks = {}
    runtime.manager = manager
    runtime.sessions = {}
    runtime.device_locks = {}
    runtime.registration_candidates = {"D1": ws}
    runtime.legacy = types.SimpleNamespace(devices={"D1": {"ws": ws}})
    runtime.send_timeout = 1
    runtime.accept_reconciliation_timeout = 1
    runtime.reconciliation_timeout = 5
    runtime.transfers = TransferRegistry()
    runtime.leases = PushTransferLeases()
    runtime.scheduler = types.SimpleNamespace(
        wake=lambda: None,
        reconcile_active_transfer=lambda *_args: asyncio.sleep(0),
    )
    runtime.publish = lambda _snapshot: asyncio.sleep(0)
    runtime.request_reconcile = lambda _device_id: asyncio.sleep(0)
    runtime._spawn_background = lambda coro, **_kwargs: coro.close()
    return runtime


@pytest.mark.asyncio
@pytest.mark.parametrize("timeout_first", [False, True])
async def test_accept_timeout_and_disconnect_order_never_authorizes_absent_replay(
    manager, timeout_first
):
    ready = await ready_job(manager.store, manager)
    job_id = ready["job_id"]
    await manager.store.enable_dispatch(job_id)
    assert await manager.claim_next(["D1"])
    await manager.prepare_dispatch(
        job_id, "D1", accept_deadline=now_ms() + 60_000,
    )
    prepared = await manager.assignment(job_id, "D1")
    deadline = prepared["accept_deadline"]

    async def expire_acceptance():
        return await manager.mark_acceptance_reconciling(
            job_id,
            "D1",
            expected_accept_deadline=deadline,
            reconciliation_deadline=now_ms() + 60_000,
        )

    async def disconnect():
        return await manager.settle_disconnect(
            "D1", accept_timeout_ms=1_000, reconciliation_timeout_ms=60_000,
        )

    if timeout_first:
        await expire_acceptance()
        await disconnect()
    else:
        await disconnect()
        with pytest.raises(StoreConflict, match="accept deadline no longer owns dispatch"):
            await expire_acceptance()

    outcome, snapshots = await manager.reconcile_report(
        job_id, "D1", 1, "absent", None, None,
    )
    assert outcome == "interrupted"
    assert snapshots[0]["devices"]["D1"]["state"] == DeviceState.INTERRUPTED.value
    assert await manager.claim_next(["D1"]) is None


@pytest.mark.asyncio
async def test_disconnect_database_failure_is_retried_without_reconnect(
    manager, monkeypatch
):
    active = await downloading_job(manager.store, manager)
    ws = Ws()
    lock = asyncio.Lock()
    runtime = object.__new__(PushRuntime)
    runtime.pending_disconnects = set()
    runtime.disconnect_recovery_tasks = {}
    runtime.manager = manager
    runtime.sessions = {
        "D1": LiveSession("D1", "session", ws, frozenset(), None, lock, "http://server")
    }
    runtime.device_locks = {"D1": lock}
    runtime.registration_candidates = {}
    runtime.legacy = types.SimpleNamespace(devices={"D1": {"ws": ws}})
    runtime.accept_reconciliation_timeout = 1
    runtime.reconciliation_timeout = 60
    runtime.scheduler = types.SimpleNamespace(wake=lambda: None)
    runtime.publish = lambda _snapshot: asyncio.sleep(0)
    monkeypatch.setattr(push_runtime, "_BACKGROUND_LOOP_RETRY_DELAY", 0.01)

    original = manager.settle_disconnect
    recovered = asyncio.Event()
    calls = 0

    async def fail_once(device_id, **kwargs):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise sqlite3.OperationalError("injected transient write failure")
        result = await original(device_id, **kwargs)
        recovered.set()
        return result

    manager.settle_disconnect = fail_once
    await runtime.disconnect_device("D1", ws)
    assert "D1" in runtime.pending_disconnects
    recovery_tasks = tuple(runtime.disconnect_recovery_tasks.values())
    assert len(recovery_tasks) == 1
    await asyncio.wait_for(recovered.wait(), timeout=1)
    await asyncio.wait_for(asyncio.gather(*recovery_tasks), timeout=1)

    current = await manager.assignment(active["job_id"], "D1")
    assert calls == 2
    assert "D1" not in runtime.pending_disconnects
    assert current["state"] == DeviceState.RECONCILING.value
    assert current["reconciliation_reason"] == "device_disconnect"


@pytest.mark.asyncio
async def test_registration_flushes_pending_disconnect_before_accepting_active_progress(
    manager, monkeypatch
):
    active = await downloading_job(manager.store, manager)
    job_id = active["job_id"]
    artifact_id = active["artifact"]["artifact_id"]
    old_ws, new_ws = Ws(), Ws()
    lock = asyncio.Lock()
    runtime = _registration_runtime(manager, new_ws)
    runtime.sessions["D1"] = LiveSession(
        "D1", "old-session", old_ws, frozenset({"push_job_id_v1"}),
        "old-process", lock, "http://server",
    )
    runtime.device_locks["D1"] = lock
    runtime.legacy.devices["D1"]["registration_ready"] = True
    runtime.accept_reconciliation_timeout = 1
    runtime.reconciliation_timeout = 60
    runtime.leases.issue(TransferKey("push", "D1", job_id, 1), artifact_id)
    monkeypatch.setattr(push_runtime, "_BACKGROUND_LOOP_RETRY_DELAY", 30)

    original = manager.settle_disconnect
    retry_entered = asyncio.Event()
    allow_retry = asyncio.Event()
    calls = 0

    async def fail_disconnect_once(device_id, **kwargs):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise sqlite3.OperationalError("injected transient write failure")
        if calls == 2:
            retry_entered.set()
            await allow_retry.wait()
        return await original(device_id, **kwargs)

    manager.settle_disconnect = fail_disconnect_once
    await runtime.disconnect_device("D1", old_ws)
    assert "D1" in runtime.pending_disconnects

    registration = asyncio.create_task(runtime.register_device(
        new_ws,
        {
            "device_id": "D1",
            "process_instance_id": str(uuid.uuid4()),
            "capabilities": ["push_job_id_v1"],
            "push_state": {"status": "available"},
            "push_runtime": {"active": {
                "job_id": job_id, "attempt": 1, "artifact_id": artifact_id,
                "phase": "downloading",
            }},
        },
        "http://server",
    ))
    await asyncio.wait_for(retry_entered.wait(), timeout=1)
    assert "D1" not in runtime.sessions
    allow_retry.set()
    await asyncio.wait_for(registration, timeout=1)

    recovery = runtime.disconnect_recovery_tasks.get("D1")
    if recovery is not None:
        recovery.cancel()
        await asyncio.gather(recovery, return_exceptions=True)
    current = await manager.assignment(job_id, "D1")
    assert runtime.sessions["D1"].ws is new_ws
    assert current["state"] == DeviceState.DOWNLOADING.value
    assert current["reconciliation_reason"] is None
    assert "D1" not in runtime.pending_disconnects


@pytest.mark.asyncio
async def test_shutdown_cancels_disconnect_retry_task(tmp_path, monkeypatch):
    runtime = PushRuntime(types.SimpleNamespace(ADMIN_SEND_TIMEOUT=1), tmp_path)
    monkeypatch.setattr(push_runtime, "_BACKGROUND_LOOP_RETRY_DELAY", 30)
    entered = asyncio.Event()
    calls = 0

    async def unavailable_database(_device_id, **_kwargs):
        nonlocal calls
        calls += 1
        entered.set()
        raise sqlite3.OperationalError("database unavailable")

    runtime.manager.settle_disconnect = unavailable_database
    runtime.pending_disconnects.add("D1")
    runtime._start_disconnect_recovery("D1")
    task = runtime.disconnect_recovery_tasks["D1"]
    await asyncio.wait_for(entered.wait(), timeout=1)

    await runtime.on_cleanup(None)
    assert task.cancelled()
    assert calls == 1
    assert runtime.disconnect_recovery_tasks == {}


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("push_state", "push_runtime", "expected_state", "expected_reason"),
    [
        ({"status": "available"}, {"active": None}, "reconciling", "client_runtime_absent"),
        ({"status": "available"}, {"active": "malformed"}, "downloading", None),
        ({"status": "unavailable"}, {"active": None}, "downloading", None),
    ],
)
async def test_registration_only_repairs_authoritative_runtime_absence(
    manager, push_state, push_runtime, expected_state, expected_reason
):
    active = await downloading_job(manager.store, manager)
    ws = Ws()
    runtime = _registration_runtime(manager, ws)
    await runtime.register_device(
        ws,
        {
            "device_id": "D1",
            "process_instance_id": str(uuid.uuid4()),
            "capabilities": ["push_job_id_v1"],
            "push_state": push_state,
            "push_runtime": push_runtime,
        },
        "http://server",
    )

    current = await manager.assignment(active["job_id"], "D1")
    assert current["state"] == expected_state
    assert current["reconciliation_reason"] == expected_reason


@pytest.mark.asyncio
async def test_stalled_transfer_db_failure_releases_http_slot_then_outer_recovery_persists(
    manager, monkeypatch
):
    active = await downloading_job(manager.store, manager)
    job_id = active["job_id"]
    artifact_id = active["artifact"]["artifact_id"]
    key = TransferKey("push", "D1", job_id, 1)
    leases = PushTransferLeases()
    registry = TransferRegistry(leases.revoke_now)
    lease = leases.issue(key, artifact_id)
    lease.last_progress -= TRANSFER_IDLE_SECONDS + 1
    future = asyncio.get_running_loop().create_future()
    registry.register(key, future)
    stream = asyncio.create_task(asyncio.Event().wait())
    aborts = []

    class Transport:
        def abort(self):
            aborts.append(True)

    assert await leases.claim(artifact_id, lease.token, stream, Transport()) == "claimed"
    slot = asyncio.Semaphore(1)
    scheduler = object.__new__(PushScheduler)
    scheduler.manager = manager
    scheduler.leases = leases
    scheduler.transfer_registry = registry
    scheduler.transfer_slots = lambda: slot
    scheduler.sessions = lambda: {}
    scheduler.publish = lambda _snapshot: asyncio.sleep(0)
    scheduler.send_reconcile = lambda *_args: asyncio.sleep(0)
    scheduler.reconciliation_timeout = 60
    scheduler._dispatch_waiters = {}
    scheduler._stopping = False
    scheduler._wake = asyncio.Event()
    original_mark = manager.mark_reconciling
    calls = 0

    async def fail_persistence_once(*args, **kwargs):
        nonlocal calls
        calls += 1
        if calls == 1:
            raise sqlite3.OperationalError("injected transient write failure")
        return await original_mark(*args, **kwargs)

    manager.mark_reconciling = fail_persistence_once
    assignment = {"job": {"job_id": job_id}, "device_id": "D1", "attempt": 1}

    async def expire_from_slot(_assignment):
        try:
            async with slot:
                await scheduler._expire_stalled_transfer(lease, future)
        finally:
            scheduler._clear_dispatch_waiters(key, future, None)

    scheduler._dispatch_assignment_inner = expire_from_slot
    try:
        await scheduler._dispatch_assignment(assignment)
        current = await manager.assignment(job_id, "D1")
        assert calls == 2
        assert current["state"] == DeviceState.RECONCILING.value
        assert current["reconciliation_reason"] == "unexpected_dispatch_failure"
        assert future.result() == "transfer_stalled"
        assert stream.cancelled()
        assert aborts == [True]
        assert not slot.locked()
        assert registry.get(key) is None
    finally:
        if not stream.done():
            stream.cancel()
        await asyncio.gather(stream, return_exceptions=True)


@pytest.mark.asyncio
async def test_newer_success_supersedes_retry_even_with_equal_creation_time(
    manager, tmp_path, monkeypatch
):
    older = await ready_job(manager.store, manager)
    newer = await ready_job(manager.store, manager)
    assert older["devices"]["D1"]["enqueue_seq"] < newer["devices"]["D1"]["enqueue_seq"]
    manager.store._call_sync(lambda conn: conn.execute(
        "UPDATE push_jobs SET created_at=100 WHERE job_id IN (?, ?)",
        (older["job_id"], newer["job_id"]),
    ))

    await manager.store.enable_dispatch(older["job_id"])
    assert (await manager.claim_next(["D1"]))["job"]["job_id"] == older["job_id"]
    await manager.prepare_dispatch(older["job_id"], "D1", accept_deadline=now_ms() + 60_000)
    await manager.transition_device(
        older["job_id"], "D1", expected={DeviceState.DISPATCHING},
        target=DeviceState.DOWNLOADING,
    )
    assert (await manager.settle_result(
        older["job_id"], "D1", 1, "fail", failure_code="download_failed",
    )).accepted

    await manager.store.enable_dispatch(newer["job_id"])
    assert (await manager.claim_next(["D1"]))["job"]["job_id"] == newer["job_id"]
    await manager.prepare_dispatch(newer["job_id"], "D1", accept_deadline=now_ms() + 60_000)
    await manager.transition_device(
        newer["job_id"], "D1", expected={DeviceState.DISPATCHING},
        target=DeviceState.DOWNLOADING,
    )
    assert (await manager.settle_result(newer["job_id"], "D1", 1, "success")).accepted

    artifact = await manager.store.artifact_record(older["artifact"]["artifact_id"])
    older_path = tmp_path / artifact["storage_name"]
    older_path.write_bytes(b"x")
    original = await manager.get_snapshot(older["job_id"])
    assert original["devices"]["D1"]["retryable"] is False

    with pytest.raises(StoreConflict, match="no unsuccessful targets to retry"):
        await manager.retry_failed(
            older["job_id"], str(uuid.uuid4()), artifact_root=tmp_path,
            online_devices={"D1"},
        )

    def unexpected_per_job_lookup(*_args, **_kwargs):
        pytest.fail("bulk snapshots repeated a per-job supersession query")

    monkeypatch.setattr(PushJobStore, "_superseded_devices", staticmethod(unexpected_per_job_lookup))
    listed = await manager.list_snapshots(100, 0)
    bulk_older = next(snapshot for snapshot in listed if snapshot["job_id"] == older["job_id"])
    assert bulk_older["devices"]["D1"]["retryable"] == original["devices"]["D1"]["retryable"]


@pytest.mark.asyncio
async def test_slow_artifact_gc_does_not_delay_reconciliation_deadline(
    manager, tmp_path, monkeypatch
):
    active = await downloading_job(manager.store, manager)
    await manager.mark_reconciling(
        active["job_id"], "D1", expected={DeviceState.DOWNLOADING},
        reason="device_disconnect", deadline=now_ms() - 1,
    )
    runtime = object.__new__(PushRuntime)
    runtime.manager = manager
    runtime.leases = PushTransferLeases()
    runtime.transfers = TransferRegistry()
    runtime.sessions = {}
    runtime.scheduler = None
    runtime.publish = lambda _snapshot: asyncio.sleep(0)
    runtime.artifacts = types.SimpleNamespace(artifact_root=tmp_path)
    runtime.artifact_gc_interval = 0.01
    runtime.artifact_retry_window = 60

    gc_started = threading.Event()
    release_gc = threading.Event()

    def block_gc(*_args, **_kwargs):
        gc_started.set()
        assert release_gc.wait(timeout=2)
        return []

    manager.gc_artifacts_sync = block_gc
    expired = asyncio.Event()
    original_unconfirmed = manager.mark_unconfirmed

    async def mark_and_signal(*args, **kwargs):
        result = await original_unconfirmed(*args, **kwargs)
        expired.set()
        return result

    manager.mark_unconfirmed = mark_and_signal
    monkeypatch.setattr(push_runtime, "_RECONCILIATION_POLL_INTERVAL", 0.01)
    gc_task = asyncio.create_task(runtime._artifact_gc_loop())
    housekeeping_task = asyncio.create_task(runtime._reconciliation_housekeeping())
    try:
        assert await asyncio.to_thread(gc_started.wait, 1)
        await asyncio.wait_for(expired.wait(), timeout=1)
        current = await manager.assignment(active["job_id"], "D1")
        assert current["state"] == DeviceState.UNCONFIRMED.value
    finally:
        release_gc.set()
        gc_task.cancel()
        housekeeping_task.cancel()
        await asyncio.gather(gc_task, housekeeping_task, return_exceptions=True)


@pytest.mark.asyncio
async def test_shutdown_waits_for_artifact_gc_thread_before_closing_store(
    tmp_path, monkeypatch
):
    runtime = PushRuntime(types.SimpleNamespace(ADMIN_SEND_TIMEOUT=1), tmp_path)
    runtime.artifact_gc_interval = 0
    gc_started = threading.Event()
    release_gc = threading.Event()
    gc_finished = threading.Event()

    def block_gc(*_args, **_kwargs):
        gc_started.set()
        try:
            if not release_gc.wait(timeout=30):
                raise AssertionError("test did not release the artifact GC worker")
            return []
        finally:
            gc_finished.set()

    runtime.manager.gc_artifacts_sync = block_gc
    store_closed_after_gc = []
    original_close = runtime.store.close
    original_shield = asyncio.shield
    cancellation_delivered = asyncio.Event()

    async def observe_gc_cancellation(work):
        try:
            return await original_shield(work)
        except asyncio.CancelledError:
            cancellation_delivered.set()
            raise

    def observe_close():
        store_closed_after_gc.append(gc_finished.is_set())
        original_close()

    monkeypatch.setattr(asyncio, "shield", observe_gc_cancellation)
    monkeypatch.setattr(runtime.store, "close", observe_close)
    runtime.artifact_gc_task = asyncio.create_task(runtime._artifact_gc_loop())

    cleanup_task = None
    try:
        assert await asyncio.to_thread(gc_started.wait, 5)

        cleanup_task = asyncio.create_task(runtime.on_cleanup(None))
        # Wait until cancellation reaches the GC loop while the worker is blocked.
        await asyncio.wait_for(cancellation_delivered.wait(), timeout=5)
        assert not runtime.artifact_gc_task.done()
        assert not cleanup_task.done()
        assert store_closed_after_gc == []
        assert not gc_finished.is_set()

        release_gc.set()
        await asyncio.wait_for(cleanup_task, timeout=5)

        assert gc_finished.is_set()
        assert store_closed_after_gc == [True]
    finally:
        release_gc.set()
        if cleanup_task is not None and not cleanup_task.done():
            await asyncio.gather(cleanup_task, return_exceptions=True)
        elif not runtime.artifact_gc_task.done():
            runtime.artifact_gc_task.cancel()
            await asyncio.gather(runtime.artifact_gc_task, return_exceptions=True)
