"""Transient failures and deadlines preserve exact, manually resumable ownership."""
import asyncio
import json
import uuid

import pytest

from styly_mdm.push_job_store import now_ms
from styly_mdm.push_jobs import DeviceState
from styly_mdm.push_scheduler import LiveSession
from styly_mdm.push_transfer_leases import PushTransferLeases
from styly_mdm.transfer_registry import TransferKey, TransferRegistry
from test_push_acceptance_transport_failure import _ready, _scheduler
from test_push_cancellation import manager, runtime_for
from test_push_review_connection import downloading_job, ready_job, Ws


async def expired_assignment(manager):
    job = await downloading_job(manager.store, manager)
    await manager.mark_reconciling(job['job_id'], 'D1', expected={DeviceState.DOWNLOADING},
                                   reason='device_disconnect', deadline=1)
    # An older Resume authorization must not survive the unknown-outcome deadline.
    await manager.store.enable_dispatch(job['job_id'], ['D1'])
    await manager.mark_unconfirmed(job['job_id'], 'D1', None, 'offline timeout',
                                   expected_deadline=1, observed_now=2)
    return job


def evidence(job):
    return dict(attempt=1, artifact_id=job['artifact']['artifact_id'],
                dispatch_revision=job['devices']['D1']['dispatch_revision'],
                validated_offset=1, reason='client_restarted', phase='downloading')


@pytest.mark.asyncio
@pytest.mark.parametrize('restart', [False, True])
async def test_timeout_retains_bytes_and_recovers_only_after_exact_interruption(manager, tmp_path, restart):
    job = await expired_assignment(manager)
    job_id = job['job_id']
    path = tmp_path / (await manager.store.artifact_record(job['artifact']['artifact_id']))['storage_name']
    path.write_bytes(b'x')
    other = await ready_job(manager.store, manager)
    # A different destination is still affected by the device-wide fence.
    await manager.store._call(lambda conn: conn.execute(
        "UPDATE push_jobs SET dest_path='/sdcard/STYLY/other' WHERE job_id=?", (other['job_id'],)))
    if restart:
        manager.store.recover_startup_sync(accept_reconciliation_timeout_ms=1000,
                                          reconciliation_timeout_ms=1000)
    terminal_at = (await manager.get_snapshot(job['job_id']))['terminal_at']
    assert manager.gc_artifacts_sync(tmp_path, retry_window_ms=7*86_400_000, timestamp=terminal_at+7*86_400_000-1) == []
    assert path.exists()
    assert await manager.claim_next(['D1']) is None
    before = await manager.get_snapshot(other['job_id'])
    outcome, snapshots = await manager.resume_interrupted(job_id, 'D1', **evidence(job))
    assert outcome == 'requeued'
    assert {snapshot['job_id'] for snapshot in snapshots} == {job_id, other['job_id']}
    current = await manager.get_snapshot(job_id)
    row = current['devices']['D1']
    assert row['state'] == 'queued' and row['resume_required']
    assert row['dispatch_revision'] == evidence(job)['dispatch_revision']
    assert row['validated_offset'] == 1
    assert current['terminal_at'] is None
    assert await manager.fenced_assignment_for_device('D1') is None
    assert (await manager.get_snapshot(other['job_id']))['revision'] > before['revision']
    # Recovered queued ownership protects the bytes even after the terminal window.
    assert manager.gc_artifacts_sync(tmp_path, retry_window_ms=0, timestamp=now_ms()+1) == []
    assert path.exists()
    assert await manager.claim_next(['D1']) is None
    await manager.store.enable_dispatch(job_id, ['D1'])
    claimed = await manager.claim_next(['D1'])
    assert claimed['job']['job_id'] == job_id


@pytest.mark.asyncio
@pytest.mark.parametrize('invalid', ['cancel', 'retry', 'newer_work', 'cleared_fence',
                                    'other_fence', 'deleted_artifact', 'revision', 'applying', 'missing_phase'])
async def test_timeout_does_not_resurrect_invalid_or_superseded_work(manager, tmp_path, invalid):
    job = await expired_assignment(manager)
    job_id = job['job_id']
    fields = evidence(job)
    if invalid == 'cancel':
        await manager.cancel_interrupted(job_id, 'D1')
    elif invalid == 'retry':
        (tmp_path / (await manager.store.artifact_record(job['artifact']['artifact_id']))['storage_name']).write_bytes(b'x')
        await manager.retry_failed(job_id, str(uuid.uuid4()), artifact_root=tmp_path, online_devices=['D1'])
    elif invalid == 'newer_work':
        await ready_job(manager.store, manager)
    elif invalid == 'cleared_fence':
        await manager.clear_matching_fence(job_id, 'D1', 1)
    elif invalid == 'other_fence':
        other = await ready_job(manager.store, manager)
        await manager.store._call(lambda conn: conn.execute(
            "UPDATE push_device_fences SET blocking_job_id=?", (other['job_id'],)))
    elif invalid == 'deleted_artifact':
        await manager.store._call(lambda conn: conn.execute(
            "UPDATE push_artifacts SET retention_state='deleted'"))
    elif invalid == 'revision':
        fields['dispatch_revision'] += 1
    else:
        fields['phase'] = 'applying' if invalid == 'applying' else None
    before = await manager.get_snapshot(job_id)
    outcome, _ = await manager.resume_interrupted(job_id, 'D1', **fields)
    assert outcome == 'rejected'
    assert await manager.get_snapshot(job_id) == before


@pytest.mark.asyncio
async def test_timeout_artifact_expiry_rejects_resume_without_clearing_fence(manager, tmp_path):
    job = await expired_assignment(manager)
    path = tmp_path / (await manager.store.artifact_record(job['artifact']['artifact_id']))['storage_name']
    path.write_bytes(b'x')
    terminal_at = (await manager.get_snapshot(job['job_id']))['terminal_at']
    assert manager.gc_artifacts_sync(tmp_path, retry_window_ms=7*86_400_000, timestamp=terminal_at+7*86_400_000-1) == []
    before = await manager.get_snapshot(job['job_id'])
    fence = await manager.fenced_assignment_for_device('D1')
    assert fence is not None
    assert manager.gc_artifacts_sync(tmp_path, retry_window_ms=7*86_400_000, timestamp=terminal_at+7*86_400_000) == [job['artifact']['artifact_id']]
    assert not path.exists()
    outcome, _ = await manager.resume_interrupted(job['job_id'], 'D1', **evidence(job))
    assert outcome == 'rejected'
    current = await manager.get_snapshot(job['job_id'])
    assert current['devices']['D1']['state'] == before['devices']['D1']['state'] == 'unconfirmed'
    assert current['devices']['D1']['dispatch_revision'] == before['devices']['D1']['dispatch_revision']
    assert await manager.fenced_assignment_for_device('D1') == fence
    assert await manager.claim_next(['D1']) is None


@pytest.mark.asyncio
@pytest.mark.parametrize('failure', ['reject', 'late_reject', 'send_reset', 'send_timeout', 'delivered_then_reset'])
async def test_transient_dispatch_failures_preserve_resume_and_release_resources(tmp_path, failure):
    async with _ready(tmp_path) as (manager, caps, job, _claimed):
        job_id = job['job_id']
        prior = await manager.prepare_dispatch(job_id, 'D1', accept_deadline=now_ms()+1000)
        await manager.transition_device(job_id, 'D1', expected={DeviceState.DISPATCHING},
                                       target=DeviceState.DOWNLOADING, fields={'accepted_at': now_ms()})
        fields = evidence(prior)
        fields['reason'] = 'download_interrupted'
        await manager.resume_interrupted(job_id, 'D1', **fields)
        await manager.store.enable_dispatch(job_id, ['D1'])
        claimed = await manager.claim_next(['D1'])
        sent = asyncio.Event()
        ws = Ws()
        original_send = ws.send_str
        async def send(text):
            payload = json.loads(text)
            if payload['type'] == 'EXECUTE_PUSH_FILES':
                sent.set()
                if failure == 'delivered_then_reset':
                    await original_send(text)
                if failure in {'send_reset', 'delivered_then_reset'}:
                    raise ConnectionResetError('transport reset')
                if failure == 'send_timeout':
                    raise asyncio.TimeoutError('transport timeout')
            await original_send(text)
        ws.send_str = send
        live = LiveSession('D1', 'session', ws, caps, 'process', asyncio.Lock(), 'http://server')
        sessions = {'D1': live}
        leases = PushTransferLeases()
        registry = TransferRegistry(leases.revoke_now)
        slots = asyncio.Semaphore(1)
        scheduler = _scheduler(manager, registry, leases, sessions, slots)
        runtime, _ = runtime_for(manager)
        runtime.sessions, runtime.leases, runtime.transfers, runtime.scheduler = sessions, leases, registry, scheduler
        dispatch = asyncio.create_task(scheduler._dispatch_assignment_inner(claimed))
        try:
            await asyncio.wait_for(sent.wait(), 2)
            rejection = {'reason': 'client_persistence_unavailable', 'retryable': True}
            if failure == 'reject':
                assert scheduler.command_response(job_id=job_id, device_id='D1', attempt=1,
                                                  outcome='rejected', payload=rejection)
            elif failure == 'late_reject':
                async def wait_reconciling():
                    while (await manager.assignment(job_id, 'D1'))['state'] != 'reconciling':
                        await asyncio.sleep(0.001)
                await asyncio.wait_for(wait_reconciling(), 2)
                await scheduler.handle_late_command_response(job_id=job_id, device_id='D1',
                                                              outcome='rejected', payload=rejection)
            await asyncio.wait_for(dispatch, 2)
            row = await manager.assignment(job_id, 'D1')
            if failure.startswith('send_') or failure == 'delivered_then_reset':
                assert row['state'] == 'reconciling'
                assert await manager.claim_next(['D1']) is None
                assert any(m['type'] == 'PUSH_RECONCILE_REQUEST' for m in ws.messages)
            else:
                assert row['state'] == 'queued' and row['queue_reason'] == 'dispatch_paused'
            report = {'job_id': job_id, 'attempt': 1, 'artifact_id': fields['artifact_id'],
                      'revision': fields['dispatch_revision'], 'validated_offset': 1,
                      'status': 'interrupted', 'phase': 'downloading', 'reason': 'server_lease_revoked'}
            await runtime._handle_reconcile_report(live, 'D1', report)
            assert not any(m['type'] == 'PUSH_RESUME_REJECTED' for m in ws.messages)
            current = await manager.get_snapshot(job_id)
            assert current['devices']['D1']['resume_required']
            assert current['devices']['D1']['validated_offset'] == 1
            assert await manager.claim_next(['D1']) is None
            key = TransferKey('push', 'D1', job_id, 1)
            assert registry.get(key) is None and leases.token(key) is None
            assert not scheduler._accept_waiters and not scheduler._dispatch_waiters
            assert slots._value == 1
            await manager.store.enable_dispatch(job_id, ['D1'])
            assert (await manager.claim_next(['D1']))['job']['job_id'] == job_id
        finally:
            dispatch.cancel()
            await asyncio.gather(dispatch, return_exceptions=True)
            await scheduler.stop()


@pytest.mark.asyncio
@pytest.mark.parametrize('late', [False, True])
async def test_permanent_identity_rejection_still_fails(manager, late):
    job = await downloading_job(manager.store, manager)
    fields = evidence(job)
    fields['reason'] = 'download_interrupted'
    await manager.resume_interrupted(job['job_id'], 'D1', **fields)
    await manager.store.enable_dispatch(job['job_id'], ['D1'])
    await manager.claim_next(['D1'])
    await manager.prepare_dispatch(job['job_id'], 'D1', accept_deadline=now_ms()+1000)
    if late:
        await manager.mark_reconciling(job['job_id'], 'D1', expected={DeviceState.DISPATCHING},
                                       reason='accept_timeout', deadline=now_ms()+1000)
    runtime, session = runtime_for(manager)
    scheduler = _scheduler(manager, runtime.transfers, runtime.leases, runtime.sessions, asyncio.Semaphore(1))
    try:
        payload = {'reason': 'artifact_identity_mismatch', 'retryable': False}
        if late:
            await scheduler.handle_late_command_response(job_id=job['job_id'], device_id='D1',
                                                          outcome='rejected', payload=payload)
        else:
            await scheduler._settle_dispatch_failure(job['job_id'], 'D1', payload['reason'], 'mismatch')
        row = await manager.assignment(job['job_id'], 'D1')
        assert row['state'] == 'failed' and row['failure_code'] == 'artifact_identity_mismatch'
    finally:
        await scheduler.stop()


@pytest.mark.asyncio
@pytest.mark.parametrize('case', ['unknown', 'artifact', 'cancel', 'valid', 'timeout'])
@pytest.mark.parametrize('route', ['register', 'reconcile'])
async def test_interrupted_decision_is_shared(manager, route, case):
    from test_push_runtime_recovery import interrupted_report
    job = await (expired_assignment(manager) if case == 'timeout'
                 else downloading_job(manager.store, manager))
    runtime, session = runtime_for(manager)
    report = interrupted_report(job, 'D1')
    if case in {'valid', 'cancel'}:
        report['reason'] = 'download_interrupted'
    if case == 'unknown':
        report['job_id'] = str(uuid.uuid4())
    elif case == 'artifact':
        report['artifact_id'] = str(uuid.uuid4())
    elif case == 'cancel':
        await manager.resume_interrupted(job['job_id'], 'D1', **{**evidence(job), 'reason': 'download_interrupted'})
        await manager.cancel_interrupted(job['job_id'], 'D1')
    if route == 'register':
        deferred = []
        await runtime._registration_active_snapshots('D1', session, report, resume_rejections=deferred)
        assert session.ws.messages == []
        replies = deferred
    else:
        await runtime._handle_reconcile_report(session, 'D1', report)
        replies = session.ws.messages
    if case in {'unknown', 'artifact', 'cancel'}:
        assert len(replies) == 1
        for field in ('job_id', 'artifact_id', 'revision'):
            assert replies[0][field] == report[field]
    else:
        assert not replies
        row = await manager.assignment(job['job_id'], 'D1')
        assert row['state'] == 'queued'
        assert (await manager.get_snapshot(job['job_id']))['devices']['D1']['resume_required']
    if case in {'unknown', 'artifact'}:
        assert await manager.fenced_assignment_for_device('D1') is not None


@pytest.mark.asyncio
async def test_failed_unknown_rejection_replays_without_releasing_fence(manager):
    runtime, session = runtime_for(manager)
    report = dict(job_id=str(uuid.uuid4()), attempt=1, artifact_id=str(uuid.uuid4()),
                  revision=7, status='interrupted', phase='downloading')
    async def fail_send(_text):
        raise ConnectionResetError('rejection lost')
    session.ws.send_str = fail_send
    await runtime._registration_active_snapshots('D1', session, report)
    waiting = await ready_job(manager.store, manager)
    await manager.store.enable_dispatch(waiting['job_id'])
    await runtime._handle_reconcile_report(session, 'D1', report)
    assert await manager.fenced_assignment_for_device('D1') is not None
    assert await manager.claim_next(['D1']) is None
    received = []
    async def send(text):
        received.append(json.loads(text))
    session.ws.send_str = send
    await runtime._handle_reconcile_report(session, 'D1', report)
    assert received[0]['type'] == 'PUSH_RESUME_REJECTED'
    assert received[0]['artifact_id'] == report['artifact_id']
    assert await manager.fenced_assignment_for_device('D1') is not None
    await runtime._handle_reconcile_report(session, 'D1', {**report, 'status': 'absent'})
    assert await manager.fenced_assignment_for_device('D1') is None
    assert (await manager.claim_next(['D1']))['job']['job_id'] == waiting['job_id']


@pytest.mark.asyncio
@pytest.mark.parametrize('lease_present', [False, True])
@pytest.mark.parametrize('phase', [None, 'downloading', 'validating'])
async def test_late_ack_requires_download_lease(manager, lease_present, phase):
    job = await downloading_job(manager.store, manager)
    await manager.mark_reconciling(job['job_id'], 'D1', expected={DeviceState.DOWNLOADING},
                                   reason='command_send_failed', deadline=now_ms()+10000)
    runtime, session = runtime_for(manager)
    runtime.scheduler = None
    if lease_present:
        runtime.leases.issue(TransferKey('push', 'D1', job['job_id'], 1), job['artifact']['artifact_id'])
    before = await manager.assignment(job['job_id'], 'D1')
    await runtime._handle_owned_job_v1_message(session, 'D1', dict(
        type='PUSH_JOB_ACCEPTED', job_id=job['job_id'], attempt=1, phase=phase))
    row = await manager.assignment(job['job_id'], 'D1')
    if phase != 'validating' and not lease_present:
        assert row == before
    else:
        assert row['state'] == (phase or 'downloading')
        assert row['reconciliation_deadline'] is None


@pytest.mark.asyncio
async def test_prewrite_guard_pauses_without_uncertain_delivery(tmp_path):
    from styly_mdm.device_policy import CommandNotAllowedError
    async with _ready(tmp_path) as (manager, caps, job, claimed):
        class GuardedWs:
            async def send_str(self, _text):
                raise CommandNotAllowedError('owner no longer eligible')
        live = LiveSession('D1', 'session', GuardedWs(), caps, 'process', asyncio.Lock(), 'http://server')
        leases = PushTransferLeases()
        registry = TransferRegistry(leases.revoke_now)
        slots = asyncio.Semaphore(1)
        scheduler = _scheduler(manager, registry, leases, {'D1': live}, slots)
        await scheduler._dispatch_assignment_inner(claimed)
        row = await manager.assignment(job['job_id'], 'D1')
        assert row['state'] == 'queued' and row['queue_reason'] == 'dispatch_paused'
        assert row['reconciliation_deadline'] is None
        key = TransferKey('push', 'D1', job['job_id'], 1)
        assert registry.get(key) is None and leases.token(key) is None
        assert not scheduler._accept_waiters and slots._value == 1
        assert await manager.claim_next(['D1']) is None


@pytest.mark.parametrize('status', [[], {}, ['available'], 1, True, None])
def test_malformed_push_state_status_is_not_hashable_input(status):
    from styly_mdm.server import parse_push_state_status
    assert parse_push_state_status({'status': status}) is None


@pytest.mark.asyncio
@pytest.mark.parametrize('reason', ['command_accept_timeout', 'disconnect_before_accept'])
async def test_stall_preserves_current_db_deadline_and_expires_on_time(manager, reason):
    job = await downloading_job(manager.store, manager)
    await manager.mark_reconciling(job['job_id'], 'D1', expected={DeviceState.DOWNLOADING},
                                   reason=reason, deadline=10)
    # The watchdog must read the authoritative deadline inside its transaction,
    # even when the dispatch task has older timeout information.
    await manager.mark_transfer_stalled(job['job_id'], 'D1', expected={DeviceState.RECONCILING},
                                        acceptance_deadline=1000, reconciliation_deadline=1800000)
    row = await manager.assignment(job['job_id'], 'D1')
    assert row['reconciliation_reason'] == reason and row['reconciliation_deadline'] == 10
    await manager.mark_unconfirmed(job['job_id'], 'D1', None, 'timeout', expected_deadline=10, observed_now=11)
    assert (await manager.assignment(job['job_id'], 'D1'))['state'] == 'unconfirmed'
