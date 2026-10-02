"""Offline devices never start queued Push work merely by reconnecting."""

import types
import uuid

import pytest

from styly_mdm.push_job_manager import PushJobManager
from styly_mdm.push_job_store import PushJobStore, StoreConflict
from styly_mdm.push_jobs import ProtocolMode, canonicalize_create_request
from styly_mdm.push_runtime import PushRuntime

CAPS = ['push_job_id_v1', 'push_resume_v1']


@pytest.fixture
def manager(tmp_path):
    store = PushJobStore(tmp_path / 'jobs.sqlite3')
    yield PushJobManager(store)
    store.close()


async def _ready_job(manager, devices, *, dispatch=True):
    request = canonicalize_create_request({
        'client_request_id': str(uuid.uuid4()), 'target_devices': list(devices),
        'mode': 'push', 'dest_path': '/sdcard/STYLY/content',
        'source': {'display_name': 'content', 'declared_file_count': 1, 'declared_total_bytes': 1},
    })
    _, job = await manager.create_job(
        request, {device: (ProtocolMode.JOB_V1, CAPS) for device in devices}, 600_000,
    )
    job_id = job['job_id']
    await manager.start_upload(job_id)
    await manager.mark_packaging(job_id, 1, 1)
    await manager.publish_artifact(job_id, {
        'artifact_id': str(uuid.uuid4()), 'storage_name': str(uuid.uuid4()) + '.zip',
        'display_filename': 'content.zip', 'byte_size': 1, 'sha256': 'a' * 64, 'entry_count': 1,
    })
    if dispatch:
        await manager.enable_dispatch(job_id)
    return job_id


async def _set(manager, job_id, device, **fields):
    assignments = ', '.join(f'{name}=?' for name in fields)
    await manager.store._call(lambda conn: conn.execute(
        f'UPDATE push_job_devices SET {assignments} WHERE job_id=? AND device_id=?',
        (*fields.values(), job_id, device),
    ))


@pytest.mark.asyncio
async def test_never_dispatched_work_fails_and_pending_resume_waits(manager):
    job_id = await _ready_job(manager, ['NEW', 'SLOT', 'RESUME', 'WAIT'])
    await _set(manager, job_id, 'SLOT', state='waiting_transfer', queue_reason=None)
    await _set(manager, job_id, 'RESUME', queue_reason='resumable_replay', dispatch_revision=3,
               dispatch_capability_snapshot_json='["push_job_id_v1","push_resume_v1"]')
    await _set(manager, job_id, 'WAIT', queue_reason='download_retry_exhausted',
               dispatch_revision=3)

    for device in ('NEW', 'SLOT', 'RESUME', 'WAIT'):
        await manager.settle_offline_before_dispatch(device)

    devices = (await manager.get_snapshot(job_id))['devices']
    for device in ('NEW', 'SLOT'):
        assert devices[device]['state'] == 'failed'
        assert devices[device]['failure']['code'] == 'device_offline_before_dispatch'
    resume = devices['RESUME']
    assert (resume['state'], resume['queue_reason']) == ('queued', 'device_offline')
    assert resume['dispatch_revision'] == 3
    assert resume['manual_wait'] and resume['resume_required'] and resume['cancellable']
    assert (devices['WAIT']['state'], devices['WAIT']['queue_reason']) == (
        'queued', 'download_retry_exhausted',
    )


@pytest.mark.asyncio
async def test_undispatched_job_is_left_for_its_dispatch_check(manager):
    job_id = await _ready_job(manager, ['D1'], dispatch=False)
    assert await manager.settle_offline_before_dispatch('D1') == []
    assert (await manager.get_snapshot(job_id))['devices']['D1']['state'] == 'queued'


@pytest.mark.asyncio
async def test_dispatch_skips_devices_that_are_offline(manager):
    job_id = await _ready_job(manager, ['ON', 'OFF'], dispatch=False)
    runtime = object.__new__(PushRuntime)
    runtime.store = manager.store
    runtime.manager = manager
    runtime.sessions = {'ON': object()}
    runtime.scheduler = None
    runtime.admin_send_timeout = 1
    runtime.legacy = types.SimpleNamespace(MAX_CONCURRENT_TRANSFERS=5, devices={})
    published = []

    async def publish(snapshot):
        published.append(snapshot)

    class Ws:
        def __init__(self):
            self.messages = []

        async def send_str(self, value):
            import json
            self.messages.append(json.loads(value))

    runtime.publish = publish
    assert await runtime.handle_admin_message(Ws(), {'type': 'PUSH_FILES', 'job_id': job_id})

    devices = (await manager.get_snapshot(job_id))['devices']
    assert devices['ON']['state'] == 'queued'
    assert devices['OFF']['state'] == 'failed'
    assert devices['OFF']['failure']['code'] == 'device_offline_before_dispatch'
    assert published[-1]['devices']['OFF']['state'] == 'failed'


@pytest.mark.asyncio
async def test_retry_failed_targets_only_online_devices(manager, tmp_path):
    job_id = await _ready_job(manager, ['ON', 'OFF'])
    artifact = (await manager.get_snapshot(job_id))['artifact']
    (tmp_path / (artifact['artifact_id'] + '.zip')).write_bytes(b'a')
    await manager.store._call(lambda conn: conn.execute(
        "UPDATE push_artifacts SET storage_name=? WHERE artifact_id=?",
        (artifact['artifact_id'] + '.zip', artifact['artifact_id']),
    ))
    for device in ('ON', 'OFF'):
        await _set(manager, job_id, device, state='failed', failure_code='download_failed')

    with pytest.raises(StoreConflict, match='online'):
        await manager.retry_failed(
            job_id, str(uuid.uuid4()), artifact_root=tmp_path, online_devices=set(),
        )
    _, retry = await manager.retry_failed(
        job_id, str(uuid.uuid4()), artifact_root=tmp_path, online_devices={'ON'},
    )
    assert set(retry['devices']) == {'ON'}
    original = (await manager.get_snapshot(job_id))['devices']
    assert original['OFF']['retry_job_id'] is None
    _, later = await manager.retry_failed(
        job_id, str(uuid.uuid4()), artifact_root=tmp_path, online_devices={'OFF'},
    )
    assert set(later['devices']) == {'OFF'}


@pytest.mark.asyncio
async def test_lost_acceptance_replays_only_while_the_device_stays_connected(manager):
    job_id = await _ready_job(manager, ['STAYED', 'LEFT'])
    for device in ('STAYED', 'LEFT'):
        await _set(manager, job_id, device, state='reconciling', dispatch_revision=1,
                   reconciliation_reason='command_accept_timeout')

    await manager.settle_offline_before_dispatch('LEFT')
    assert (await manager.reconcile_report(job_id, 'STAYED', 1, 'absent', None, None))[0] == 'requeued'
    outcome, _ = await manager.reconcile_report(job_id, 'LEFT', 1, 'absent', None, None)
    assert outcome == 'interrupted'
    devices = (await manager.get_snapshot(job_id))['devices']
    assert devices['STAYED']['state'] == 'queued'
    assert devices['LEFT']['state'] == 'interrupted'


@pytest.mark.asyncio
async def test_disconnect_withdraws_a_pending_operator_resume(manager):
    job_id = await _ready_job(manager, ['D1'])
    await _set(manager, job_id, 'D1', state='reconciling', dispatch_revision=1,
               queue_reason='resumable_replay', reconciliation_reason='device_disconnect',
               dispatch_capability_snapshot_json='["push_job_id_v1","push_resume_v1"]')
    await manager.settle_offline_before_dispatch('D1')
    device = (await manager.get_snapshot(job_id))['devices']['D1']
    assert (device['state'], device['queue_reason']) == ('reconciling', 'device_offline')
    assert device['manual_wait'] and device['resume_required']

    artifact_id = (await manager.get_snapshot(job_id))['artifact']['artifact_id']
    outcome, _ = await manager.resume_interrupted(
        job_id, 'D1', attempt=1, artifact_id=artifact_id, dispatch_revision=1,
        validated_offset=0, reason='client_restarted',
    )
    assert outcome == 'requeued'
    device = (await manager.get_snapshot(job_id))['devices']['D1']
    assert (device['state'], device['queue_reason']) == ('queued', 'device_offline')
    assert await manager.claim_next(['D1']) is None
