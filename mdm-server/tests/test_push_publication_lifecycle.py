"""Committed Retry updates outlive cancelled callers and runtime shutdown."""
import asyncio
import threading

import pytest

from styly_mdm.push_runtime import PushRuntime
from test_push_retry_failed import completed, create_newer, manager


class Legacy:
    ADMIN_SEND_TIMEOUT = 1
    devices = {}

    def __init__(self):
        self.events = asyncio.Queue()

    def transfer_slots(self):
        return asyncio.Semaphore(1)

    async def forward_to_admins(self, event):
        self.events.put_nowait(event)


async def update_for(legacy, job_id, revision):
    async def receive():
        while True:
            event = await legacy.events.get()
            if event['job_id'] == job_id and event['revision'] == revision:
                return event['job']
    return await asyncio.wait_for(receive(), 2)


@pytest.mark.asyncio
async def test_startup_drains_retry_updates_committed_before_wake_attachment(tmp_path):
    legacy = Legacy()
    runtime = PushRuntime(legacy, tmp_path)
    try:
        original = await completed(runtime.manager, runtime.artifacts.artifact_root)
        await create_newer(runtime.manager, original)
        expected = await runtime.manager.get_snapshot(original['job_id'])
        await runtime.on_startup(None)
        assert await update_for(legacy, original['job_id'], expected['revision']) == expected
    finally:
        await runtime.on_cleanup(None)
    assert runtime.store._retry_publication_wakeup is None
    assert runtime.store.take_retry_publications() == []


@pytest.mark.asyncio
@pytest.mark.parametrize('shutdown', [False, True])
async def test_cancelled_caller_publishes_committed_retry_without_other_publish(
    tmp_path, monkeypatch, shutdown,
):
    legacy = Legacy()
    runtime = PushRuntime(legacy, tmp_path)
    started, release = threading.Event(), threading.Event()
    committed = []
    cleanup = None
    mutation = None
    try:
        original = await completed(runtime.manager, runtime.artifacts.artifact_root)
        await runtime.on_startup(None)
        commit = runtime.store._commit

        def blocked_commit(conn):
            started.set()
            assert release.wait(5)
            commit(conn)
            committed.append(True)

        monkeypatch.setattr(runtime.store, '_commit', blocked_commit)
        mutation = asyncio.create_task(create_newer(runtime.manager, original))
        assert await asyncio.to_thread(started.wait, 2)
        mutation.cancel()
        with pytest.raises(asyncio.CancelledError):
            await mutation
        if shutdown:
            drain_started = asyncio.Event()
            drain = runtime.store.drain

            async def observe_drain():
                drain_started.set()
                await drain()

            monkeypatch.setattr(runtime.store, 'drain', observe_drain)
            cleanup = asyncio.create_task(runtime.on_cleanup(None))
            await asyncio.wait_for(drain_started.wait(), 2)
            assert not cleanup.done()
            assert not committed
        release.set()
        if cleanup is not None:
            await asyncio.wait_for(cleanup, 2)
            assert runtime.store._retry_publication_wakeup is None
        update = await update_for(legacy, original['job_id'], original['revision'] + 1)
        assert not update['devices']['failed']['retryable']
        assert committed
        assert runtime.store.take_retry_publications() == []
    finally:
        release.set()
        if mutation is not None:
            await asyncio.gather(mutation, return_exceptions=True)
        if cleanup is None:
            await runtime.on_cleanup(None)
        else:
            await asyncio.gather(cleanup, return_exceptions=True)


@pytest.mark.asyncio
async def test_shutdown_drains_commit_queued_during_admin_send(tmp_path, monkeypatch):
    legacy = Legacy()
    runtime = PushRuntime(legacy, tmp_path)
    sending, release, closed = asyncio.Event(), asyncio.Event(), asyncio.Event()
    cleanup = None
    try:
        original = await completed(runtime.manager, runtime.artifacts.artifact_root)
        await runtime.on_startup(None)
        forward = legacy.forward_to_admins

        async def blocked_send(event):
            if event['job_id'] == 'earlier':
                sending.set()
                await release.wait()
            await forward(event)

        monkeypatch.setattr(legacy, 'forward_to_admins', blocked_send)
        await runtime.publish({'job_id': 'earlier', 'revision': 1})
        await asyncio.wait_for(sending.wait(), 2)
        await create_newer(runtime.manager, original)
        expected = await runtime.manager.get_snapshot(original['job_id'])
        close = runtime.store.close

        def observe_close():
            close()
            closed.set()

        monkeypatch.setattr(runtime.store, 'close', observe_close)
        cleanup = asyncio.create_task(runtime.on_cleanup(None))
        await asyncio.wait_for(closed.wait(), 2)
        assert runtime.publication_stopping
        release.set()
        await asyncio.wait_for(cleanup, 2)
        assert await update_for(legacy, original['job_id'], expected['revision']) == expected
        assert runtime.store.take_retry_publications() == []
    finally:
        release.set()
        if cleanup is None:
            await runtime.on_cleanup(None)
        else:
            await asyncio.gather(cleanup, return_exceptions=True)


@pytest.mark.asyncio
async def test_notification_failure_keeps_commit_successful_and_snapshot_queued(
    manager, tmp_path, caplog,
):
    original = await completed(manager, tmp_path)

    def failed_notification():
        raise RuntimeError('event loop unavailable')

    manager.store.set_retry_publication_wakeup(failed_notification)
    created, _ = await create_newer(manager, original)
    assert created
    expected = await manager.get_snapshot(original['job_id'])
    assert not expected['devices']['failed']['retryable']
    assert manager.store.take_retry_publications() == [expected]
    assert 'Could not wake Push Retry publication' in caplog.text
