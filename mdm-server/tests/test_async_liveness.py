import asyncio
import json
from contextlib import asynccontextmanager

import aiohttp
import pytest
from aiohttp.test_utils import TestServer

from styly_mdm import push_runtime, push_scheduler, server
from styly_mdm.push_runtime import PushRuntime
from styly_mdm.push_scheduler import LiveSession, PushScheduler
from styly_mdm.transfer_registry import TransferKey, TransferRegistry


async def _publish(_snapshot):
    return None


class _ClaimManager:
    def __init__(self):
        self.calls = 0
        self.recovered = asyncio.Event()

    async def claim_next(self, _online_device_ids):
        self.calls += 1
        if self.calls == 1:
            raise RuntimeError("temporary database failure")
        self.recovered.set()
        return None


def _scheduler(manager):
    return PushScheduler(
        manager=manager,
        transfer_registry=object(),
        transfer_slots=lambda: None,
        sessions=lambda: {},
        publish=_publish,
        send_timeout=1,
        accept_timeout=1,
        accept_reconciliation_timeout=1,
        reconciliation_timeout=1,
        transfer_timeout=1,
        allow_legacy=False,
    )


@pytest.mark.asyncio
async def test_scheduler_retries_after_unexpected_claim_error(monkeypatch):
    monkeypatch.setattr(push_scheduler, "_RUN_RETRY_DELAY", 0)
    manager = _ClaimManager()
    scheduler = _scheduler(manager)
    scheduler.start()
    runner = scheduler._runner
    assert runner is not None
    try:
        await asyncio.wait_for(manager.recovered.wait(), timeout=1)
        assert not runner.done()
    finally:
        await scheduler.stop()
    assert runner.cancelled()


@pytest.mark.asyncio
async def test_wake_restarts_a_finished_scheduler_runner():
    class Manager:
        def __init__(self):
            self.claimed = asyncio.Event()

        async def claim_next(self, _online_device_ids):
            self.claimed.set()
            return None

    manager = Manager()
    scheduler = _scheduler(manager)
    finished = asyncio.create_task(asyncio.sleep(0))
    await finished
    scheduler._runner = finished

    scheduler.wake()
    restarted = scheduler._runner
    assert restarted is not None
    assert restarted is not finished
    try:
        await asyncio.wait_for(manager.claimed.wait(), timeout=1)
        assert not restarted.done()
    finally:
        await scheduler.stop()


@pytest.mark.asyncio
async def test_dispatch_exception_moves_uncertain_send_to_reconciliation(monkeypatch):
    job_id = "job-1"
    device_id = "D1"
    assignment = {
        "job": {"job_id": job_id},
        "device_id": device_id,
        "attempt": 1,
    }
    published = []

    class Manager:
        async def assignment(self, _job_id, _device_id):
            return {
                "state": "dispatching",
                "attempt": 1,
                "accepted_at": None,
                "protocol_mode": "job_v1",
            }

        async def mark_reconciling(self, _job_id, _device_id, **kwargs):
            assert kwargs["expected"] == {push_scheduler.DeviceState.DISPATCHING}
            assert kwargs["reason"] == "unexpected_dispatch_failure"
            return {
                "job_id": job_id,
                "artifact": None,
                "devices": {device_id: {"attempt": 1}},
            }

    registry = TransferRegistry()
    scheduler = PushScheduler(
        manager=Manager(),
        transfer_registry=registry,
        transfer_slots=lambda: None,
        sessions=lambda: {},
        publish=lambda snapshot: _record(published, snapshot),
        send_timeout=1,
        accept_timeout=1,
        accept_reconciliation_timeout=1,
        reconciliation_timeout=1,
        transfer_timeout=1,
        allow_legacy=False,
    )
    transfer_future = asyncio.get_running_loop().create_future()
    key = TransferKey("push", device_id, job_id, 1)
    registry.register(key, transfer_future)
    accept_future = asyncio.get_running_loop().create_future()
    scheduler._accept_waiters[(job_id, device_id, 1)] = accept_future
    scheduler._dispatch_waiters[asyncio.current_task()] = (
        key,
        transfer_future,
        accept_future,
    )

    async def fail(_assignment):
        raise RuntimeError("synthetic post-send failure")

    monkeypatch.setattr(scheduler, "_dispatch_assignment_inner", fail)
    await scheduler._dispatch_assignment(assignment)

    assert registry.get(key) is None
    assert transfer_future.cancelled()
    assert accept_future.cancelled()
    assert published and published[0]["job_id"] == job_id


@pytest.mark.asyncio
async def test_old_dispatch_cleanup_preserves_replacement_waiters():
    registry = TransferRegistry()
    scheduler = PushScheduler(
        manager=None,
        transfer_registry=registry,
        transfer_slots=lambda: asyncio.Semaphore(1),
        sessions=lambda: {},
        publish=lambda _snapshot: None,
        send_timeout=1,
        accept_timeout=1,
        accept_reconciliation_timeout=1,
        reconciliation_timeout=1,
        transfer_timeout=1,
        allow_legacy=False,
    )
    scheduler.wake = lambda: None
    key = TransferKey("push", "D1", "job-1", 1)
    loop = asyncio.get_running_loop()
    old_transfer = loop.create_future()
    old_accept = loop.create_future()
    registry.register(key, old_transfer)
    old_transfer.set_result("requeued")

    new_transfer = loop.create_future()
    new_accept = loop.create_future()
    registry.register(key, new_transfer)
    scheduler._accept_waiters[("job-1", "D1", 1)] = new_accept
    current_task = asyncio.current_task()
    assert current_task is not None
    scheduler._dispatch_waiters[current_task] = (key, new_transfer, new_accept)
    assert scheduler.has_live_acceptance_waiter("job-1", "D1", 1) is True

    scheduler._clear_dispatch_waiters(key, old_transfer, old_accept)

    assert registry.get(key) is new_transfer
    assert scheduler._accept_waiters[("job-1", "D1", 1)] is new_accept
    assert scheduler.has_live_acceptance_waiter("job-1", "D1", 1) is True
    assert not new_transfer.done()
    assert not new_accept.done()


@pytest.mark.asyncio
async def test_accept_timeout_already_reconciled_keeps_exact_transfer_slot(monkeypatch):
    deadline = 1234

    class Manager:
        async def mark_acceptance_reconciling(self, job_id, device_id, **kwargs):
            assert job_id == "job-1"
            assert device_id == "D1"
            assert kwargs["expected_accept_deadline"] == deadline
            return False, {
                "job_id": job_id,
                "devices": {device_id: {"attempt": 1, "accept_deadline": deadline}},
            }

    registry = TransferRegistry()
    scheduler = PushScheduler(
        manager=Manager(),
        transfer_registry=registry,
        transfer_slots=lambda: asyncio.Semaphore(1),
        sessions=lambda: {},
        publish=_publish,
        send_timeout=1,
        accept_timeout=0,
        accept_reconciliation_timeout=1,
        reconciliation_timeout=1,
        transfer_timeout=1,
        allow_legacy=False,
    )
    sent = []

    async def send_reconcile(_session, snapshot, device_id):
        sent.append((snapshot, device_id))

    monkeypatch.setattr(scheduler, "send_reconcile", send_reconcile)
    key = TransferKey("push", "D1", "job-1", 1)
    transfer_future = asyncio.get_running_loop().create_future()
    registry.register(key, transfer_future)
    accepted = await scheduler._await_acceptance(
        object(),
        {
            "job_id": "job-1",
            "devices": {"D1": {"attempt": 1, "accept_deadline": deadline}},
        },
        "D1",
        asyncio.get_running_loop().create_future(),
        key,
        deadline,
    )

    assert accepted is True
    assert registry.get(key) is transfer_future
    assert sent and sent[0][1] == "D1"


async def _record(target, value):
    target.append(value)


@asynccontextmanager
async def _transfer_slot():
    yield


class _DispatchManager:
    def __init__(self):
        self.claimed = False
        self.transitions = []
        self.transitioned = asyncio.Event()
        self.snapshot = {
            "job_id": "job-1",
            "revision": 1,
            "mode": "push",
            "dest_path": "/sdcard/STYLY/content",
            "artifact": {
                "artifact_id": "artifact-1",
                "url": "/artifacts/artifact-1",
                "display_filename": "content.zip",
                "byte_size": 1,
                "sha256": "a" * 64,
            },
            "devices": {"D1": {"attempt": 1}},
        }

    async def claim_next(self, _online_device_ids):
        if self.claimed:
            return None
        self.claimed = True
        return {
            "job": {"job_id": "job-1"},
            "device_id": "D1",
            "attempt": 1,
        }

    async def prepare_dispatch(self, *_args, **_kwargs):
        return self.snapshot

    async def transition_device(self, *_args, **kwargs):
        self.transitions.append(kwargs["target"])
        self.transitioned.set()
        return self.snapshot


class _BlockingWebSocket:
    def __init__(self, error=None):
        self.error = error
        self.started = asyncio.Event()

    async def send_str(self, _message):
        self.started.set()
        if self.error is not None:
            raise self.error
        await asyncio.Event().wait()


def _dispatch_scheduler(manager, websocket):
    session = LiveSession(
        device_id="D1",
        session_id="session-1",
        ws=websocket,
        capabilities=frozenset({"push_job_id_v1"}),
        process_instance_id="process-1",
        owner_lock=asyncio.Lock(),
        http_base="http://server",
    )
    registry = TransferRegistry()
    scheduler = PushScheduler(
        manager=manager,
        transfer_registry=registry,
        transfer_slots=_transfer_slot,
        sessions=lambda: {"D1": session},
        publish=_publish,
        send_timeout=10,
        accept_timeout=1,
        accept_reconciliation_timeout=1,
        reconciliation_timeout=1,
        transfer_timeout=1,
        allow_legacy=False,
    )
    return scheduler, registry


@pytest.mark.asyncio
async def test_dispatch_send_failure_cleans_all_registered_waiters():
    manager = _DispatchManager()
    websocket = _BlockingWebSocket(RuntimeError("send failed"))
    scheduler, registry = _dispatch_scheduler(manager, websocket)
    scheduler.start()
    try:
        await asyncio.wait_for(manager.transitioned.wait(), timeout=1)
        assert manager.transitions == [push_scheduler.DeviceState.FAILED]
        assert len(registry) == 0
        assert scheduler._accept_waiters == {}
    finally:
        await scheduler.stop()


@pytest.mark.asyncio
async def test_scheduler_stop_during_send_does_not_mark_assignment_failed():
    manager = _DispatchManager()
    websocket = _BlockingWebSocket()
    scheduler, registry = _dispatch_scheduler(manager, websocket)
    scheduler.start()
    await asyncio.wait_for(websocket.started.wait(), timeout=1)

    await asyncio.wait_for(scheduler.stop(), timeout=1)

    assert manager.transitions == []
    assert len(registry) == 0
    assert scheduler._accept_waiters == {}


@pytest.mark.asyncio
async def test_console_serves_push_job_adapter_without_file_response_patch(tmp_path):
    server._apply_data_dir(str(tmp_path))
    test_server = TestServer(server.create_app())
    await test_server.start_server()
    try:
        async with aiohttp.ClientSession() as session:
            async with session.get(f"http://{test_server.host}:{test_server.port}/") as response:
                assert response.status == 200
                html = await response.text()
            marker = '<script src="/static/push-jobs-v1.js"></script>'
            assert html.count(marker) == 1

            async with session.get(
                f"http://{test_server.host}:{test_server.port}/static/push-jobs-v1.js"
            ) as response:
                assert response.status == 200
                adapter = await response.text()
            assert "Issue #91 console integration" in adapter
    finally:
        await test_server.close()


class _AdminWebSocket:
    def __init__(
        self, *, error=None, mutate=False, block=None, delay=0, close_block=None,
        started=None,
    ):
        self.error = error
        self.mutate = mutate
        self.block = block
        self.delay = delay
        self.close_block = close_block
        self.started = started
        self.messages = []
        self.active = 0
        self.max_active = 0
        self.close_calls = []

    async def send_str(self, message):
        self.active += 1
        self.max_active = max(self.max_active, self.active)
        try:
            if self.started is not None:
                self.started.set()
            if self.block is not None:
                await self.block.wait()
            if self.delay:
                await asyncio.sleep(self.delay)
            if self.mutate:
                server.admin_connections.discard(self)
            if self.error is not None:
                raise self.error
            self.messages.append(json.loads(message))
        finally:
            self.active -= 1

    async def close(self, **kwargs):
        self.close_calls.append(kwargs)
        if self.close_block is not None:
            await self.close_block.wait()
        return True


@pytest.mark.asyncio
async def test_admin_broadcast_isolates_bad_connection_and_set_mutation():
    good = _AdminWebSocket()
    bad = _AdminWebSocket(error=RuntimeError("closing WebSocket"))
    mutating = _AdminWebSocket(mutate=True)
    server.admin_connections.clear()
    server.admin_connections.update({good, bad, mutating})
    try:
        await server.forward_to_admins({"type": "TEST"})
        assert good.messages == [{"type": "TEST"}]
        assert mutating.messages == [{"type": "TEST"}]
        assert bad not in server.admin_connections
        assert len(bad.close_calls) == 1
        assert bad.close_calls[0]["code"] == server.WSCloseCode.GOING_AWAY
        assert bad.close_calls[0]["drain"] is False
    finally:
        server.admin_connections.clear()


@pytest.mark.asyncio
async def test_admin_broadcast_does_not_block_healthy_peer(monkeypatch):
    monkeypatch.setattr(server, "ADMIN_SEND_TIMEOUT", 0.01)
    good = _AdminWebSocket()
    blocked = _AdminWebSocket(block=asyncio.Event(), close_block=asyncio.Event())
    server.admin_connections.clear()
    server.admin_connections.update({good, blocked})
    try:
        await asyncio.wait_for(server.forward_to_admins({"type": "TEST"}), timeout=0.2)
        assert good.messages == [{"type": "TEST"}]
        assert blocked not in server.admin_connections
        assert len(blocked.close_calls) == 1
        assert blocked.close_calls[0]["code"] == server.WSCloseCode.GOING_AWAY
        assert blocked.close_calls[0]["drain"] is False
    finally:
        server.admin_connections.clear()
        server._admin_send_locks.clear()


@pytest.mark.asyncio
async def test_concurrent_admin_broadcasts_serialize_each_connection():
    ws = _AdminWebSocket(delay=0.01)
    server.admin_connections.clear()
    server.admin_connections.add(ws)
    try:
        await asyncio.gather(
            server.forward_to_admins({"type": "FIRST"}),
            server.forward_to_admins({"type": "SECOND"}),
        )
        assert ws.max_active == 1
        assert {message["type"] for message in ws.messages} == {"FIRST", "SECOND"}
    finally:
        server.admin_connections.clear()
        server._admin_send_locks.clear()


@pytest.mark.asyncio
async def test_cancelled_admin_broadcast_finishes_bounded_send_before_reraising():
    started = asyncio.Event()
    release = asyncio.Event()
    ws = _AdminWebSocket(block=release, started=started)
    server.admin_connections.clear()
    server.admin_connections.add(ws)
    try:
        broadcast = asyncio.create_task(server.forward_to_admins({"type": "OFFLINE"}))
        await asyncio.wait_for(started.wait(), timeout=1)

        broadcast.cancel()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await broadcast

        assert ws.messages == [{"type": "OFFLINE"}]
    finally:
        server.admin_connections.clear()
        server._admin_send_locks.clear()


@pytest.mark.asyncio
async def test_created_deadline_loop_survives_unexpected_publish_error(monkeypatch):
    monkeypatch.setattr(push_runtime, "_BACKGROUND_LOOP_RETRY_DELAY", 0)
    published = asyncio.Event()
    cleaned_all = asyncio.Event()
    cleaned = []

    class Manager:
        def __init__(self):
            self.calls = 0

        async def next_created_deadline(self):
            self.calls += 1
            if self.calls <= 2:
                return (f"job-{self.calls}", 0)
            return None

    class Store:
        async def expire_created(self, job_id, _deadline):
            return {"job_id": job_id}

    class Artifacts:
        def cleanup_work_best_effort(self, job_id):
            cleaned.append(job_id)
            if job_id == "job-2":
                cleaned_all.set()

    class Scheduler:
        def __init__(self):
            self.wake_count = 0

        def wake(self):
            self.wake_count += 1

    runtime = object.__new__(PushRuntime)
    runtime.manager = Manager()
    runtime.store = Store()
    runtime.artifacts = Artifacts()
    runtime.scheduler = Scheduler()
    runtime.created_deadline_wake = asyncio.Event()
    runtime.created_deadline_task = None
    publish_calls = 0

    async def publish(_snapshot):
        nonlocal publish_calls
        publish_calls += 1
        if publish_calls == 1:
            raise RuntimeError("temporary publication failure")
        published.set()

    runtime.publish = publish
    runtime.arm_created_deadline()
    task = runtime.created_deadline_task
    assert task is not None
    try:
        await asyncio.wait_for(published.wait(), timeout=1)
        await asyncio.wait_for(cleaned_all.wait(), timeout=1)
        assert not task.done()
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
    assert task.cancelled()
    assert cleaned == ["job-1", "job-2"]
    assert runtime.scheduler.wake_count == 2


class _PublicationLegacy:
    def __init__(self, *, fail_first=False):
        self.fail_first = fail_first
        self.calls = 0
        self.active = 0
        self.max_active = 0
        self.events = []
        self.sent = asyncio.Event()

    async def forward_to_admins(self, payload):
        self.calls += 1
        self.active += 1
        self.max_active = max(self.max_active, self.active)
        try:
            await asyncio.sleep(0)
            if self.fail_first and self.calls == 1:
                raise RuntimeError("temporary admin publication failure")
            self.events.append(payload)
            self.sent.set()
        finally:
            self.active -= 1


def _publication_runtime(legacy):
    runtime = object.__new__(PushRuntime)
    runtime.legacy = legacy
    runtime.pending_publications = {}
    runtime.publication_revisions = {}
    runtime.publication_wake = asyncio.Event()
    runtime.publication_stopping = False
    runtime.publication_task = asyncio.create_task(runtime._publication_loop())
    return runtime


@pytest.mark.asyncio
async def test_publication_worker_coalesces_latest_revision_and_serializes_sends():
    legacy = _PublicationLegacy()
    runtime = _publication_runtime(legacy)
    await runtime.publish({"job_id": "job", "revision": 1})
    await runtime.publish({"job_id": "job", "revision": 3})
    await runtime.publish({"job_id": "job", "revision": 2})
    await asyncio.wait_for(legacy.sent.wait(), timeout=1)
    await runtime.publish({"job_id": "job", "revision": 1})
    await asyncio.sleep(0)
    runtime.publication_stopping = True
    runtime.publication_wake.set()
    await runtime.publication_task
    assert [event["revision"] for event in legacy.events] == [3]
    assert legacy.events[0]["type"] == "PUSH_JOB_UPDATED"
    assert legacy.max_active == 1


@pytest.mark.asyncio
async def test_publication_worker_continues_after_exception():
    legacy = _PublicationLegacy(fail_first=True)
    runtime = _publication_runtime(legacy)
    await runtime.publish({"job_id": "first", "revision": 1})
    await runtime.publish({"job_id": "second", "revision": 1})
    await asyncio.wait_for(legacy.sent.wait(), timeout=1)
    assert runtime.publication_task is not None and not runtime.publication_task.done()
    runtime.publication_stopping = True
    runtime.publication_wake.set()
    await runtime.publication_task
    assert legacy.calls == 2
    assert len(legacy.events) == 1


@pytest.mark.asyncio
async def test_runtime_cleanup_drains_publication_before_store_close(tmp_path):
    legacy = _PublicationLegacy()
    runtime = _publication_runtime(legacy)
    runtime.created_deadline_task = None
    runtime.housekeeping_task = None
    runtime.scheduler = None
    runtime.sessions = {}
    runtime.registration_candidates = {}
    runtime.transfers = TransferRegistry()
    runtime.data_dir = tmp_path

    class Store:
        def __init__(self):
            self.closed = False

        def close(self):
            assert legacy.events
            self.closed = True

    runtime.store = Store()
    await runtime.publish({"job_id": "job", "revision": 1})
    await runtime.on_cleanup(None)
    assert runtime.store.closed
    assert runtime.publication_task is None


@pytest.mark.asyncio
async def test_server_local_websocket_factory_preserves_aiohttp_and_handles_register(tmp_path):
    original = aiohttp.web.WebSocketResponse
    assert server._websocket_response_factory is push_runtime.RuntimeWebSocketResponse
    assert aiohttp.web.WebSocketResponse is original

    server._apply_data_dir(str(tmp_path))
    test_server = TestServer(server.create_app())
    await test_server.start_server()
    try:
        async with aiohttp.ClientSession() as session:
            ws = await session.ws_connect(
                f"http://{test_server.host}:{test_server.port}/ws/device"
            )
            await ws.send_json({
                "type": "REGISTER",
                "device_id": "factory-device",
                "model": "test",
                "process_instance_id": "0b1d4b9b-b80e-4f62-bde1-605111230dc1",
                "capabilities": ["push_job_id_v1"],
                "push_runtime": {"active": None},
            })
            message = await asyncio.wait_for(ws.receive_json(), timeout=1)
            assert message["type"] == "REGISTERED"
            runtime = test_server.app["push_runtime"]
            assert isinstance(
                runtime.sessions["factory-device"].ws,
                push_runtime.RuntimeWebSocketResponse,
            )
            await ws.close()
    finally:
        await test_server.close()


@pytest.mark.asyncio
async def test_housekeeping_recovers_expired_acceptance_waiter(monkeypatch):
    monkeypatch.setattr(push_runtime, "_RECONCILIATION_POLL_INTERVAL", 0)
    recovered = asyncio.Event()

    class Manager:
        def __init__(self):
            self.returned = False

        async def expired_acceptances(self, _timestamp):
            if self.returned:
                return []
            self.returned = True
            return [{
                "job_id": "job-1",
                "device_id": "D1",
                "attempt": 1,
                "accept_deadline": 0,
            }]

        async def expired_reconciliations(self, _timestamp):
            return []

        async def mark_acceptance_reconciling(self, job_id, device_id, **kwargs):
            assert job_id == "job-1"
            assert device_id == "D1"
            assert kwargs["expected_accept_deadline"] == 0
            return True, {"job_id": job_id}

    class Scheduler:
        def __init__(self):
            self.wake_count = 0

        def has_live_acceptance_waiter(self, _job_id, _device_id, _attempt):
            return False

        def wake(self):
            self.wake_count += 1

    runtime = object.__new__(PushRuntime)
    runtime.manager = Manager()
    runtime.scheduler = Scheduler()
    runtime.accept_reconciliation_timeout = 60
    runtime.sessions = {}

    async def publish(_snapshot):
        return None

    async def request_reconcile(device_id):
        assert device_id == "D1"
        recovered.set()

    runtime.publish = publish
    runtime.request_reconcile = request_reconcile
    task = asyncio.create_task(runtime._reconciliation_housekeeping())
    try:
        await asyncio.wait_for(recovered.wait(), timeout=1)
        assert runtime.scheduler.wake_count >= 1
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


@pytest.mark.asyncio
async def test_housekeeping_leaves_live_acceptance_waiter_to_dispatch_task(monkeypatch):
    monkeypatch.setattr(push_runtime, "_RECONCILIATION_POLL_INTERVAL", 0)
    checked = asyncio.Event()
    transitioned = False

    class Manager:
        def __init__(self):
            self.returned = False

        async def expired_acceptances(self, _timestamp):
            if self.returned:
                return []
            self.returned = True
            return [{
                "job_id": "job-1",
                "device_id": "D1",
                "attempt": 1,
                "accept_deadline": 0,
            }]

        async def expired_reconciliations(self, _timestamp):
            return []

        async def mark_acceptance_reconciling(self, *_args, **_kwargs):
            nonlocal transitioned
            transitioned = True
            raise AssertionError("a live acceptance waiter owns this deadline")

    class Scheduler:
        def has_live_acceptance_waiter(self, job_id, device_id, attempt):
            assert (job_id, device_id, attempt) == ("job-1", "D1", 1)
            checked.set()
            return True

        def wake(self):
            raise AssertionError("a skipped live waiter must not wake the scheduler")

    runtime = object.__new__(PushRuntime)
    runtime.manager = Manager()
    runtime.scheduler = Scheduler()
    runtime.accept_reconciliation_timeout = 60
    runtime.sessions = {}
    runtime.publish = lambda _snapshot: asyncio.sleep(0)
    runtime.request_reconcile = lambda _device_id: asyncio.sleep(0)

    task = asyncio.create_task(runtime._reconciliation_housekeeping())
    try:
        await asyncio.wait_for(checked.wait(), timeout=1)
        await asyncio.sleep(0)
        assert transitioned is False
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


@pytest.mark.asyncio
async def test_reconciliation_housekeeping_isolates_query_and_row_errors(monkeypatch):
    monkeypatch.setattr(push_runtime, "_RECONCILIATION_POLL_INTERVAL", 0)
    published = asyncio.Event()

    rows = [
        {
            "job_id": "bad-job",
            "device_id": "D1",
            "attempt": 1,
            "reconciliation_deadline": 0,
        },
        {
            "job_id": "publish-fail-job",
            "device_id": "D2",
            "attempt": 1,
            "reconciliation_deadline": 0,
        },
        {
            "job_id": "good-job",
            "device_id": "D3",
            "attempt": 1,
            "reconciliation_deadline": 0,
        },
    ]

    class Manager:
        def __init__(self):
            self.queries = 0

        async def expired_acceptances(self, _timestamp):
            return []

        async def expired_reconciliations(self, _timestamp):
            self.queries += 1
            if self.queries == 1:
                raise RuntimeError("temporary query failure")
            return rows if self.queries == 2 else []

        async def mark_unconfirmed(self, job_id, *_args, **_kwargs):
            if job_id == "bad-job":
                raise RuntimeError("one damaged row")
            return [{"job_id": job_id}]

    class Transfers:
        def __init__(self):
            self.released = []

        def release_exact(self, key, reason):
            self.released.append((key.job_id, reason))

    class Scheduler:
        def __init__(self):
            self.wake_count = 0

        def wake(self):
            self.wake_count += 1

    runtime = object.__new__(PushRuntime)
    runtime.manager = Manager()
    runtime.sessions = {}
    runtime.transfers = Transfers()
    runtime.scheduler = Scheduler()

    async def publish(snapshot):
        if snapshot["job_id"] == "publish-fail-job":
            raise RuntimeError("temporary publication failure")
        if snapshot["job_id"] == "good-job":
            published.set()

    runtime.publish = publish
    task = asyncio.create_task(runtime._reconciliation_housekeeping())
    try:
        await asyncio.wait_for(published.wait(), timeout=1)
        assert not task.done()
        assert runtime.transfers.released == [
            ("publish-fail-job", "reconciliation_timeout"),
            ("good-job", "reconciliation_timeout"),
        ]
        assert runtime.scheduler.wake_count == 2
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
    assert task.cancelled()
