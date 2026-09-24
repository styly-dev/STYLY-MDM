"""Cancel only timed-out transfers that are awaiting operator Resume."""
import asyncio
import uuid

import pytest

from styly_mdm.push_job_manager import PushJobManager
from styly_mdm.push_job_store import PushJobStore, StoreConflict
from styly_mdm.push_jobs import DeviceState
from styly_mdm.push_runtime import PushRuntime
from styly_mdm.push_scheduler import LiveSession
from styly_mdm.transfer_registry import TransferRegistry
from styly_mdm.transfer_registry import TransferKey
from styly_mdm.push_transfer_leases import PushTransferLeases
from test_push_review_connection import ready_job, downloading_job, Ws, Scheduler, LegacyEvents

CAPS = frozenset({'push_job_id_v1', 'push_resume_v1'})


@pytest.fixture
def manager(tmp_path):
    store = PushJobStore(tmp_path / 'push_jobs.sqlite3')
    yield PushJobManager(store)
    store.close()


def runtime_for(manager):
    runtime = object.__new__(PushRuntime)
    runtime.store = manager.store
    runtime.manager = manager
    runtime.scheduler = Scheduler()
    runtime.transfers = TransferRegistry()
    runtime.send_timeout = 1
    runtime.legacy = LegacyEvents()
    runtime.pending_publications = {}
    runtime.publication_revisions = {}
    runtime.publication_wake = asyncio.Event()
    runtime.publish = lambda snapshot: asyncio.sleep(0)
    session = LiveSession('D1', 'session', Ws(), CAPS, str(uuid.uuid4()), asyncio.Lock(), 'http://server')
    runtime.sessions = {'D1': session}
    return runtime, session


async def timed_out_job(manager):
    active = await downloading_job(manager.store, manager, CAPS)
    await manager.resume_interrupted(
        active['job_id'], 'D1', attempt=1, reason='download_retry_exhausted',
        artifact_id=active['artifact']['artifact_id'],
        dispatch_revision=active['devices']['D1']['dispatch_revision'], validated_offset=1)
    return await manager.get_snapshot(active['job_id'])


@pytest.mark.asyncio
async def test_timeout_cancel_is_terminal_idempotent_and_unblocks_next_job(manager):
    first = await timed_out_job(manager)
    second = await ready_job(manager.store, manager)
    await manager.enable_dispatch(second['job_id'])
    assert await manager.claim_next(['D1']) is None
    cancelled = await manager.cancel_interrupted(first['job_id'], 'D1')
    assert cancelled['devices']['D1']['state'] == 'queued'
    assert cancelled['devices']['D1']['cancel_requested']
    assert await manager.claim_next(['D1']) is None
    assert await manager.cancel_interrupted(first['job_id'], 'D1') == cancelled
    await manager.enable_dispatch(first['job_id'])
    assert await manager.claim_next(['D1']) is None
    await manager.confirm_cancel(first['job_id'], 'D1')
    assert (await manager.claim_next(['D1']))['job']['job_id'] == second['job_id']


@pytest.mark.asyncio
@pytest.mark.parametrize('capabilities, expected', [
    (CAPS, True),
    (frozenset({'push_job_id_v1'}), False),
])
async def test_snapshot_resume_supported_mirrors_cancel_identity(manager, capabilities, expected):
    active = await downloading_job(manager.store, manager, capabilities)
    assert active['devices']['D1']['dispatch_revision'] is not None
    assert active['devices']['D1']['resume_supported'] is expected
    await manager.mark_reconciling(active['job_id'], 'D1', expected={DeviceState.DOWNLOADING},
                                   reason='device_disconnect', deadline=1)
    if expected:
        await manager.cancel_interrupted(active['job_id'], 'D1')
    else:
        with pytest.raises(StoreConflict, match='Only interrupted'):
            await manager.cancel_interrupted(active['job_id'], 'D1')


@pytest.mark.asyncio
async def test_undispatched_snapshot_is_not_resume_supported(manager):
    assert (await ready_job(manager.store, manager))['devices']['D1']['resume_supported'] is False


@pytest.mark.asyncio
@pytest.mark.parametrize('phase', ['ready', 'downloading', 'resumed'])
async def test_cancel_rejects_work_not_awaiting_timeout_resume(manager, phase):
    if phase == 'ready':
        original = await ready_job(manager.store, manager)
    elif phase == 'downloading':
        original = await downloading_job(manager.store, manager, CAPS)
    else:
        original = await timed_out_job(manager)
        _, original = await manager.enable_dispatch(original['job_id'])
    with pytest.raises(StoreConflict, match='Only interrupted'):
        await manager.cancel_interrupted(original['job_id'], 'D1')
    assert await manager.get_snapshot(original['job_id']) == original


@pytest.mark.asyncio
async def test_offline_cancel_rejects_recovered_partial_on_reconnect(manager):
    original = await timed_out_job(manager)
    cancelled = await manager.cancel_interrupted(original['job_id'], 'D1')
    runtime, session = runtime_for(manager)
    active_report = {
        'job_id': original['job_id'], 'attempt': 1, 'status': 'interrupted',
        'phase': 'downloading', 'reason': 'download_retry_exhausted',
        'artifact_id': original['artifact']['artifact_id'],
        'revision': original['devices']['D1']['dispatch_revision'], 'validated_offset': 1,
    }
    assert await runtime._registration_active_snapshots('D1', session, active_report) == []
    assert session.ws.messages[0]['type'] == 'PUSH_RESUME_REJECTED'
    for field in ['job_id', 'attempt', 'artifact_id', 'revision']:
        assert session.ws.messages[0][field] == active_report[field]
    assert await manager.get_snapshot(original['job_id']) == cancelled
    assert await manager.claim_next(['D1']) is None


@pytest.mark.asyncio
async def test_offline_cancel_preserves_fence_and_artifact_until_exact_cleanup(manager, tmp_path):
    from styly_mdm.push_jobs import DeviceState
    from styly_mdm.push_job_store import now_ms
    active = await downloading_job(manager.store, manager, CAPS)
    await manager.mark_reconciling(active['job_id'], 'D1', expected={DeviceState.DOWNLOADING},
                                   reason='device_disconnect', deadline=1)
    await manager.cancel_interrupted(active['job_id'], 'D1')
    await manager.mark_unconfirmed(active['job_id'], 'D1', None, 'timeout', observed_now=2)
    assert (await manager.artifact_record(active['artifact']['artifact_id']))['retention_state'] == 'retained'
    assert manager.gc_artifacts_sync(tmp_path, retry_window_ms=0, timestamp=now_ms()+1000) == []
    next_job = await ready_job(manager.store, manager)
    await manager.enable_dispatch(next_job['job_id'])
    assert await manager.claim_next(['D1']) is None
    runtime, session = runtime_for(manager)
    identity = {'job_id': active['job_id'], 'attempt': 1, 'artifact_id': active['artifact']['artifact_id'],
                'revision': active['devices']['D1']['dispatch_revision']}
    await runtime._handle_reconcile_report(session, 'D1', {**identity, 'status': 'active', 'phase': 'downloading'})
    assert session.ws.messages == []
    await runtime._handle_reconcile_report(session, 'D1', {**identity, 'status': 'interrupted'})
    assert session.ws.messages[-1]['type'] == 'PUSH_RESUME_REJECTED'
    assert await manager.claim_next(['D1']) is None
    await runtime._handle_reconcile_report(session, 'D1', {**identity, 'status': 'absent', 'artifact_id': str(uuid.uuid4())})
    assert await manager.claim_next(['D1']) is None
    await runtime._handle_reconcile_report(session, 'D1', {**identity, 'status': 'absent'})
    assert (await manager.get_snapshot(active['job_id']))['devices']['D1']['failure']['code'] == 'cancelled'
    assert (await manager.claim_next(['D1']))['job']['job_id'] == next_job['job_id']


@pytest.mark.asyncio
@pytest.mark.parametrize('resume_first', [False, True])
async def test_client_restart_requires_explicit_resume_even_when_job_enabled(manager, resume_first):
    from styly_mdm.push_jobs import DeviceState
    active = await downloading_job(manager.store, manager, CAPS)
    await manager.mark_reconciling(active['job_id'], 'D1', expected={DeviceState.DOWNLOADING},
                                   reason='device_disconnect', deadline=9999999999999)
    if resume_first:
        await manager.enable_dispatch(active['job_id'])
    outcome, current = await manager.resume_interrupted(active['job_id'], 'D1', attempt=1,
        artifact_id=active['artifact']['artifact_id'], dispatch_revision=active['devices']['D1']['dispatch_revision'],
        validated_offset=1, reason='client_restarted')
    assert outcome == 'requeued'
    assert current['devices']['D1']['queue_reason'] == ('resumable_replay' if resume_first else 'client_restarted')
    if not resume_first:
        assert await manager.claim_next(['D1']) is None
        await manager.enable_dispatch(active['job_id'])
    assert await manager.claim_next(['D1']) is not None


@pytest.mark.asyncio
async def test_storage_failure_retains_exact_partial_for_manual_resume(manager):
    active = await downloading_job(manager.store, manager, CAPS)
    outcome, snapshot = await manager.resume_interrupted(
        active['job_id'], 'D1', attempt=1,
        artifact_id=active['artifact']['artifact_id'],
        dispatch_revision=active['devices']['D1']['dispatch_revision'],
        validated_offset=1, reason='storage_write_failed',
    )
    assert outcome == 'requeued'
    assert snapshot['devices']['D1']['queue_reason'] == 'download_retry_exhausted'
    assert snapshot['devices']['D1']['validated_offset'] == 1
    assert await manager.claim_next(['D1']) is None


@pytest.mark.asyncio
async def test_late_absent_cannot_rewrite_completed_terminal_result(manager):
    original = await timed_out_job(manager)
    await manager.cancel_interrupted(original['job_id'], 'D1')
    await manager.store.settle_result(original['job_id'], 'D1', 1, 'fail', failure_code='resume_expired')
    terminal = await manager.get_snapshot(original['job_id'])
    assert await manager.confirm_cancel(original['job_id'], 'D1') == []
    assert await manager.get_snapshot(original['job_id']) == terminal


@pytest.mark.asyncio
async def test_cancelled_reconciling_assignment_ignores_late_phase_and_completion(manager):
    from styly_mdm.push_jobs import DeviceState
    active = await downloading_job(manager.store, manager, CAPS)
    await manager.mark_reconciling(active['job_id'], 'D1', expected={DeviceState.DOWNLOADING},
                                   reason='device_disconnect', deadline=9999999999999)
    await manager.cancel_interrupted(active['job_id'], 'D1')
    runtime, _session = runtime_for(manager)
    identity = {'job_id': active['job_id'], 'attempt': 1,
                'artifact_id': active['artifact']['artifact_id']}
    await runtime._handle_phase('D1', {**identity, 'phase': 'validating'})
    await runtime._handle_transfer_complete('D1', {**identity, 'received_size': 1})
    await runtime._handle_phase('D1', {**identity, 'phase': 'applying'})
    current = await manager.get_snapshot(active['job_id'])
    assert current['devices']['D1']['state'] == 'reconciling'
    assert current['devices']['D1']['cancel_requested']


@pytest.mark.asyncio
async def test_validation_phase_releases_slot_before_verified_completion(manager):
    active = await downloading_job(manager.store, manager, CAPS)
    runtime, _session = runtime_for(manager)
    runtime.leases = PushTransferLeases()
    runtime.transfers = TransferRegistry(runtime.leases.revoke_now)
    key = TransferKey('push', 'D1', active['job_id'], 1)
    runtime.leases.issue(key, active['artifact']['artifact_id'])
    future = asyncio.get_running_loop().create_future()
    runtime.transfers.register(key, future)
    identity = {'job_id': active['job_id'], 'attempt': 1,
                'artifact_id': active['artifact']['artifact_id']}
    await runtime._handle_phase('D1', {**identity, 'phase': 'validating'})
    assert future.result() == 'validation_started'
    assert runtime.leases.token(key) is None
    assert (await manager.get_snapshot(active['job_id']))['devices']['D1']['state'] == 'validating'
    await runtime._handle_transfer_complete('D1', {**identity, 'received_size': 1})
    assert (await manager.get_snapshot(active['job_id']))['devices']['D1']['state'] == 'validating'


@pytest.mark.asyncio
async def test_exact_verified_completion_recovers_reconciling_state(manager):
    from styly_mdm.push_jobs import DeviceState
    active = await downloading_job(manager.store, manager, CAPS)
    await manager.mark_reconciling(active['job_id'], 'D1', expected={DeviceState.DOWNLOADING},
                                   reason='transfer_stalled', deadline=9999999999999)
    runtime, _session = runtime_for(manager)
    await runtime._handle_transfer_complete('D1', {
        'job_id': active['job_id'], 'attempt': 1,
        'artifact_id': active['artifact']['artifact_id'], 'received_size': 1,
    })
    assert (await manager.get_snapshot(active['job_id']))['devices']['D1']['state'] == 'validating'


@pytest.mark.asyncio
async def test_success_after_cancelled_absence_was_missed_settles_exact_result(manager):
    from styly_mdm.push_jobs import DeviceState
    active = await downloading_job(manager.store, manager, CAPS)
    await manager.mark_reconciling(active['job_id'], 'D1', expected={DeviceState.DOWNLOADING},
                                   reason='device_disconnect', deadline=1)
    await manager.cancel_interrupted(active['job_id'], 'D1')
    await manager.mark_unconfirmed(active['job_id'], 'D1', None, 'timeout', observed_now=2)
    runtime, session = runtime_for(manager)
    await runtime._handle_result('D1', {
        'job_id': active['job_id'], 'attempt': 1, 'status': 'success',
        'added': 1, 'updated': 0, 'deleted': 0,
    }, session)
    settled = await manager.get_snapshot(active['job_id'])
    assert settled['devices']['D1']['state'] == 'succeeded'
    assert settled['devices']['D1']['result']['added'] == 1
    assert session.ws.messages[-1]['accepted'] is True


@pytest.mark.asyncio
async def test_schema_two_migrates_existing_dispatch_identity(tmp_path):
    import sqlite3
    path = tmp_path / 'migration.sqlite3'
    store = PushJobStore(path)
    original = await downloading_job(store, PushJobManager(store), CAPS)
    store.close()
    with sqlite3.connect(path) as conn:
        conn.execute('ALTER TABLE push_job_devices DROP COLUMN cancel_requested_at')
        conn.execute("UPDATE server_metadata SET value='2' WHERE key='schema_version'")
    migrated = PushJobStore(path)
    try:
        assert await migrated.get_snapshot(original['job_id']) == original
        assert await migrated._call(lambda conn: conn.execute("SELECT value FROM server_metadata WHERE key='schema_version'").fetchone()[0]) == '3'
    finally:
        migrated.close()


def test_future_schema_is_rejected_before_alter(tmp_path):
    import sqlite3
    path = tmp_path / 'future.sqlite3'
    store = PushJobStore(path)
    store.close()
    with sqlite3.connect(path) as conn:
        conn.execute('ALTER TABLE push_job_devices DROP COLUMN cancel_requested_at')
        conn.execute("UPDATE server_metadata SET value='99' WHERE key='schema_version'")
    with pytest.raises(RuntimeError, match='unsupported push job schema version 99'):
        PushJobStore(path)
    with sqlite3.connect(path) as conn:
        columns = {row[1] for row in conn.execute('PRAGMA table_info(push_job_devices)')}
        assert 'cancel_requested_at' not in columns
