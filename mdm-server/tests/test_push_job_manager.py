import uuid

import pytest

from styly_mdm.push_job_manager import PushJobManager
from styly_mdm.push_job_store import PushJobStore, StoreConflict, now_ms
from styly_mdm.push_jobs import DeviceState, JobState, ProtocolMode, canonicalize_create_request


def request(device='D1'):
    return canonicalize_create_request({
        'client_request_id': str(uuid.uuid4()),
        'target_devices': [device],
        'mode': 'push',
        'dest_path': '/sdcard/STYLY/content',
        'source': {
            'display_name': 'content',
            'declared_file_count': 1,
            'declared_total_bytes': 1,
        },
    })


async def ready(store, req, label):
    _, snapshot = await store.create_job(
        req,
        {req.target_devices[0]: (ProtocolMode.JOB_V1, {'push_job_id_v1'})},
        600_000,
    )
    job_id = snapshot['job_id']
    await store.start_upload(job_id)
    await store.mark_packaging(job_id, 1, 1)
    return await store.publish_artifact(job_id, {
        'artifact_id': str(uuid.uuid4()),
        'storage_name': f'{label}-{uuid.uuid4()}.zip',
        'display_filename': f'{label}.zip',
        'byte_size': 1,
        'sha256': 'a' * 64,
        'entry_count': 1,
    })


@pytest.fixture
def manager(tmp_path):
    store = PushJobStore(tmp_path / 'push_jobs.sqlite3')
    value = PushJobManager(store)
    yield value
    store.close()


def test_policy_mutations_have_one_owner():
    policy_methods = {
        'settle_late_fenced_result',
        'reconcile_report',
        'mark_unconfirmed',
        'add_opaque_fence',
        'clear_matching_fence',
        'clear_fence_on_process_replacement',
    }

    assert policy_methods <= PushJobManager.__dict__.keys()
    assert policy_methods.isdisjoint(PushJobStore.__dict__.keys())


@pytest.mark.asyncio
async def test_idempotent_lookup_does_not_depend_on_current_connection(manager):
    req = request()
    created, snapshot = await manager.create_job(
        req, {'D1': (ProtocolMode.JOB_V1, {'push_job_id_v1'})}, 600_000,
    )
    assert created
    replay = await manager.find_idempotent_job(req.client_request_id, req.fingerprint)
    assert replay['job_id'] == snapshot['job_id']


@pytest.mark.asyncio
async def test_older_nonterminal_queue_row_blocks_later_enabled_job(manager):
    # enqueue_seq is the device queue's total order. A later ready job cannot jump
    # over a job created first merely because that first job's dispatch gate is off.
    first = request()
    _, first_snapshot = await manager.create_job(
        first, {'D1': (ProtocolMode.JOB_V1, {'push_job_id_v1'})}, 600_000,
    )
    second_snapshot = await ready(manager.store, request(), 'second')
    await manager.enable_dispatch(second_snapshot['job_id'])

    assert first_snapshot['devices']['D1']['enqueue_seq'] < \
        second_snapshot['devices']['D1']['enqueue_seq']
    assert await manager.claim_next(['D1']) is None


@pytest.mark.asyncio
async def test_fence_mutation_revisions_every_visible_job(manager):
    first = await ready(manager.store, request(), 'first')
    _, second = await manager.create_job(
        request(), {'D1': (ProtocolMode.JOB_V1, {'push_job_id_v1'})}, 600_000,
    )
    await manager.enable_dispatch(first['job_id'])
    await manager.claim_next(['D1'])
    await manager.prepare_dispatch(
        first['job_id'], 'D1', protocol_mode=ProtocolMode.JOB_V1,
        live_capabilities={'push_job_id_v1'}, accept_deadline=now_ms() + 1_000,
    )
    await manager.mark_reconciling(
        first['job_id'], 'D1', expected={DeviceState.DISPATCHING},
        reason='test', deadline=now_ms(),
    )
    before_second = await manager.get_snapshot(second['job_id'])
    snapshots = await manager.mark_unconfirmed(
        first['job_id'], 'D1', 'process-a', 'test timeout',
    )
    by_id = {item['job_id']: item for item in snapshots}
    assert first['job_id'] in by_id
    assert second['job_id'] in by_id
    assert by_id[second['job_id']]['revision'] == before_second['revision'] + 1
    assert by_id[first['job_id']]['devices']['D1']['device_fence'] is not None
    assert by_id[second['job_id']]['devices']['D1']['device_fence'] is None


@pytest.mark.asyncio
async def test_opaque_fence_is_attributed_only_to_latest_enqueued_job(manager):
    first = await ready(manager.store, request(), 'opaque-first')
    second = await ready(manager.store, request(), 'opaque-second')
    before = {
        item['job_id']: item['revision']
        for item in (first, second)
    }

    snapshots = await manager.add_opaque_fence(
        'D1', '{"token":"unknown-active-job"}', ProtocolMode.JOB_V1,
        'process-a', 'unknown active job',
    )
    by_id = {item['job_id']: item for item in snapshots}

    assert set(by_id) == set(before)
    assert all(by_id[job_id]['revision'] == revision + 1
               for job_id, revision in before.items())
    assert by_id[first['job_id']]['devices']['D1']['device_fence'] is None
    assert by_id[second['job_id']]['devices']['D1']['device_fence'] is not None


@pytest.mark.asyncio
async def test_preaccept_absent_requeues_same_attempt_only_once(manager):
    snapshot = await ready(manager.store, request(), 'replay')
    job_id = snapshot['job_id']
    await manager.enable_dispatch(job_id)
    await manager.claim_next(['D1'])
    await manager.prepare_dispatch(
        job_id, 'D1', protocol_mode=ProtocolMode.JOB_V1,
        live_capabilities={'push_job_id_v1'}, accept_deadline=now_ms() + 1_000,
    )
    await manager.mark_reconciling(
        job_id, 'D1', expected={DeviceState.DISPATCHING},
        reason='command_accept_timeout', deadline=now_ms() + 60_000,
    )
    outcome, snapshots = await manager.reconcile_report(
        job_id, 'D1', 1, 'absent', None, None,
    )
    assert outcome == 'requeued'
    assert snapshots[0]['devices']['D1']['attempt'] == 1
    assert snapshots[0]['devices']['D1']['queue_reason'] == 'command_accept_replay'

    await manager.claim_next(['D1'])
    await manager.prepare_dispatch(
        job_id, 'D1', protocol_mode=ProtocolMode.JOB_V1,
        live_capabilities={'push_job_id_v1'}, accept_deadline=now_ms() + 1_000,
    )
    await manager.mark_reconciling(
        job_id, 'D1', expected={DeviceState.DISPATCHING},
        reason='command_accept_timeout', deadline=now_ms() + 60_000,
    )
    outcome, snapshots = await manager.reconcile_report(
        job_id, 'D1', 1, 'absent', None, None,
    )
    assert outcome == 'interrupted'
    assert snapshots[0]['devices']['D1']['state'] == DeviceState.INTERRUPTED.value
    assert snapshots[0]['devices']['D1']['attempt'] == 1


@pytest.mark.asyncio
async def test_exact_absent_clears_fence_when_reconciliation_deadline_wins(manager):
    snapshot = await ready(manager.store, request(), 'deadline-race')
    job_id = snapshot['job_id']
    await manager.enable_dispatch(job_id)
    await manager.claim_next(['D1'])
    await manager.prepare_dispatch(
        job_id, 'D1', protocol_mode=ProtocolMode.JOB_V1,
        live_capabilities={'push_job_id_v1'}, accept_deadline=now_ms() + 1_000,
    )
    await manager.mark_reconciling(
        job_id, 'D1', expected={DeviceState.DISPATCHING},
        reason='command_accept_timeout', deadline=now_ms(),
    )

    # The runtime may have observed reconciling immediately before housekeeping
    # commits unconfirmed. The exact report must still clear the matching fence.
    await manager.mark_unconfirmed(job_id, 'D1', 'process-a', 'deadline elapsed')
    outcome, snapshots = await manager.reconcile_report(
        job_id, 'D1', 1, 'absent', None, None,
    )

    assert outcome == 'fence_cleared'
    current = next(item for item in snapshots if item['job_id'] == job_id)
    assert current['devices']['D1']['state'] == DeviceState.UNCONFIRMED.value
    assert current['devices']['D1']['device_fence'] is None


@pytest.mark.asyncio
async def test_expired_acceptance_query_tracks_only_unaccepted_dispatches(manager):
    snapshot = await ready(manager.store, request(), 'accept-deadline')
    job_id = snapshot['job_id']
    await manager.enable_dispatch(job_id)
    await manager.claim_next(['D1'])
    deadline = now_ms() - 1
    await manager.prepare_dispatch(
        job_id, 'D1', protocol_mode=ProtocolMode.JOB_V1,
        live_capabilities={'push_job_id_v1'}, accept_deadline=deadline,
    )

    expired = await manager.expired_acceptances(now_ms())
    assert expired == [{
        'job_id': job_id,
        'device_id': 'D1',
        'attempt': 1,
        'accept_deadline': deadline,
    }]

    await manager.transition_device(
        job_id,
        'D1',
        expected={DeviceState.DISPATCHING},
        target=DeviceState.DOWNLOADING,
        fields={'accepted_at': now_ms(), 'accept_deadline': None},
    )
    assert await manager.expired_acceptances(now_ms()) == []


@pytest.mark.asyncio
async def test_stale_expired_acceptance_cannot_reconcile_replayed_dispatch(manager):
    snapshot = await ready(manager.store, request(), 'stale-accept-deadline')
    job_id = snapshot['job_id']
    await manager.enable_dispatch(job_id)
    await manager.claim_next(['D1'])
    first_deadline = now_ms() - 1
    await manager.prepare_dispatch(
        job_id, 'D1', protocol_mode=ProtocolMode.JOB_V1,
        live_capabilities={'push_job_id_v1'}, accept_deadline=first_deadline,
    )

    changed, reconciling = await manager.mark_acceptance_reconciling(
        job_id,
        'D1',
        expected_accept_deadline=first_deadline,
        reconciliation_deadline=now_ms() + 60_000,
    )
    assert changed is True
    current_row = await manager.assignment(job_id, 'D1')
    assert current_row['accept_deadline'] == first_deadline
    changed, duplicate = await manager.mark_acceptance_reconciling(
        job_id,
        'D1',
        expected_accept_deadline=first_deadline,
        reconciliation_deadline=now_ms() + 60_000,
    )
    assert changed is False
    assert duplicate['revision'] == reconciling['revision']

    outcome, _ = await manager.reconcile_report(
        job_id, 'D1', 1, 'absent', None, None,
    )
    assert outcome == 'requeued'
    await manager.claim_next(['D1'])
    second_deadline = first_deadline + 120_000
    await manager.prepare_dispatch(
        job_id, 'D1', protocol_mode=ProtocolMode.JOB_V1,
        live_capabilities={'push_job_id_v1'}, accept_deadline=second_deadline,
    )

    with pytest.raises(StoreConflict, match='accept deadline no longer owns dispatch'):
        await manager.mark_acceptance_reconciling(
            job_id,
            'D1',
            expected_accept_deadline=first_deadline,
            reconciliation_deadline=now_ms() + 60_000,
        )
    current = await manager.get_snapshot(job_id)
    assert current['devices']['D1']['state'] == DeviceState.DISPATCHING.value
    current_row = await manager.assignment(job_id, 'D1')
    assert current_row['accept_deadline'] == second_deadline


@pytest.mark.asyncio
async def test_new_job_v1_process_safely_clears_legacy_fence(manager):
    _, queued = await manager.create_job(
        request(), {'D1': (ProtocolMode.JOB_V1, {'push_job_id_v1'})}, 600_000,
    )
    snapshots = await manager.add_opaque_fence(
        'D1', 'legacy-active', ProtocolMode.LEGACY, None,
        'legacy worker state is ambiguous',
    )
    assert snapshots
    cleared = await manager.clear_fence_on_process_replacement(
        'D1', str(uuid.uuid4()), True,
    )
    assert cleared
    current = await manager.get_snapshot(queued['job_id'])
    assert current['devices']['D1']['device_fence'] is None


@pytest.mark.asyncio
async def test_new_job_v1_process_clears_offline_job_v1_fence(manager):
    first = await ready(manager.store, request(), 'offline-owner')
    second = await ready(manager.store, request(), 'next-job')
    await manager.enable_dispatch(first['job_id'])
    await manager.enable_dispatch(second['job_id'])
    await manager.claim_next(['D1'])
    await manager.prepare_dispatch(
        first['job_id'], 'D1', protocol_mode=ProtocolMode.JOB_V1,
        live_capabilities={'push_job_id_v1'}, accept_deadline=now_ms() + 1_000,
    )
    await manager.mark_reconciling(
        first['job_id'], 'D1', expected={DeviceState.DISPATCHING},
        reason='device_disconnect', deadline=now_ms(),
    )
    await manager.mark_unconfirmed(
        first['job_id'], 'D1', None, 'device stayed offline past deadline',
    )

    assert await manager.claim_next(['D1']) is None
    cleared = await manager.clear_fence_on_process_replacement(
        'D1', str(uuid.uuid4()), True,
    )
    assert cleared
    claimed = await manager.claim_next(['D1'])
    assert claimed['job']['job_id'] == second['job_id']

@pytest.mark.asyncio
async def test_stale_reconciliation_deadline_cannot_mark_recovered_assignment(manager):
    snapshot = await ready(manager.store, request(), 'deadline')
    job_id = snapshot['job_id']
    await manager.enable_dispatch(job_id)
    await manager.claim_next(['D1'])
    await manager.prepare_dispatch(
        job_id, 'D1', protocol_mode=ProtocolMode.JOB_V1,
        live_capabilities={'push_job_id_v1'}, accept_deadline=now_ms() + 1_000,
    )
    first_deadline = now_ms() + 10
    await manager.mark_reconciling(
        job_id, 'D1', expected={DeviceState.DISPATCHING},
        reason='command_accept_timeout', deadline=first_deadline,
    )
    # A matching active report recovers the assignment, then a later disconnect
    # installs a different deadline. The old timeout callback must be a no-op.
    await manager.reconcile_report(job_id, 'D1', 1, 'active', 'downloading', None)
    second_deadline = first_deadline + 60_000
    await manager.mark_reconciling(
        job_id, 'D1', expected={DeviceState.DOWNLOADING},
        reason='device_disconnect', deadline=second_deadline,
    )
    with pytest.raises(StoreConflict):
        await manager.mark_unconfirmed(
            job_id,
            'D1',
            'process-a',
            'stale callback',
            expected_deadline=first_deadline,
            observed_now=first_deadline + 1,
        )
    current = await manager.get_snapshot(job_id)
    assert current['devices']['D1']['state'] == DeviceState.RECONCILING.value
    assert current['devices']['D1']['reconciliation_deadline'] == second_deadline


@pytest.mark.asyncio
async def test_active_assignment_blocks_a_second_claim(manager):
    first = await ready(manager.store, request(), 'active-first')
    second = await ready(manager.store, request(), 'active-second')
    await manager.enable_dispatch(first['job_id'])
    await manager.enable_dispatch(second['job_id'])
    claimed = await manager.claim_next(['D1'])
    assert claimed['job']['job_id'] == first['job_id']
    # The second row remains queued; unique-index contention is a normal no-op, not
    # an exception that terminates the scheduler loop.
    assert await manager.claim_next(['D1']) is None
    current = await manager.get_snapshot(second['job_id'])
    assert current['devices']['D1']['state'] == DeviceState.QUEUED.value



@pytest.mark.asyncio
async def test_exact_late_result_is_acked_after_process_replacement_cleared_fence(manager):
    snapshot = await ready(manager.store, request(), 'late-after-replacement')
    job_id = snapshot['job_id']
    await manager.enable_dispatch(job_id)
    await manager.claim_next(['D1'])
    await manager.prepare_dispatch(
        job_id,
        'D1',
        protocol_mode=ProtocolMode.JOB_V1,
        live_capabilities={'push_job_id_v1'},
        accept_deadline=now_ms() + 1_000,
    )
    await manager.mark_reconciling(
        job_id,
        'D1',
        expected={DeviceState.DISPATCHING},
        reason='test',
        deadline=now_ms(),
    )
    await manager.mark_unconfirmed(
        job_id, 'D1', 'process-a', 'test timeout'
    )
    await manager.clear_fence_on_process_replacement(
        'D1', 'process-b', True
    )
    before = await manager.get_snapshot(job_id)
    accepted, snapshots = await manager.settle_late_fenced_result(
        job_id, 'D1', 1
    )
    assert accepted
    current = next(item for item in snapshots if item['job_id'] == job_id)
    assert current['state'] == JobState.COMPLETED_WITH_ERRORS.value
    assert current['devices']['D1']['state'] == DeviceState.UNCONFIRMED.value
    assert current['devices']['D1']['device_fence'] is None
    assert current['revision'] == before['revision']
