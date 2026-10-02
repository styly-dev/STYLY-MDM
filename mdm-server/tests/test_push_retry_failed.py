"""Retry only unsuccessful targets while preserving bytes, history, and fences."""
import uuid
import pytest
from styly_mdm.push_job_manager import PushJobManager
from styly_mdm.push_job_store import PushJobStore, StoreConflict, now_ms
from styly_mdm.push_jobs import ProtocolMode, canonicalize_create_request

# Retry targets only online devices; these unit tests treat every target as online.
ONLINE = {'success', 'failed', 'interrupted', 'unknown', 'cancelled', 'active'}


@pytest.fixture
def manager(tmp_path):
    store = PushJobStore(tmp_path / 'jobs.sqlite3')
    yield PushJobManager(store)
    store.close()


async def completed(manager, root):
    states = {'success': ('succeeded', None), 'failed': ('failed', 'download_failed'),
              'interrupted': ('interrupted', 'restart'), 'unknown': ('unconfirmed', 'timeout'),
              'cancelled': ('failed', 'cancelled'), 'active': ('downloading', None)}
    request = canonicalize_create_request({
        'client_request_id': str(uuid.uuid4()), 'target_devices': list(states),
        'mode': 'sync', 'dest_path': '/sdcard/STYLY/content',
        'source': {'display_name': 'content', 'declared_file_count': 1, 'declared_total_bytes': 4},
    })
    _, job = await manager.create_job(request, {
        device: (ProtocolMode.JOB_V1, ['push_job_id_v1']) for device in states}, 600_000)
    job_id = job['job_id']
    await manager.start_upload(job_id)
    await manager.mark_packaging(job_id, 1, 4)
    artifact_id = str(uuid.uuid4())
    (root / (artifact_id + '.zip')).write_bytes(b'data')
    await manager.publish_artifact(job_id, {
        'artifact_id': artifact_id, 'storage_name': artifact_id + '.zip',
        'display_filename': 'content.zip', 'byte_size': 4, 'sha256': 'a' * 64, 'entry_count': 1,
    })
    def settle(conn):
        conn.execute("UPDATE push_jobs SET state='completed_with_errors', terminal_at=1 WHERE job_id=?", (job_id,))
        for device, (state, code) in states.items():
            conn.execute('UPDATE push_job_devices SET state=?, failure_code=? WHERE job_id=? AND device_id=?', (state, code, job_id, device))
        conn.execute("INSERT INTO push_device_fences(device_id,blocking_job_id,blocking_attempt,protocol_mode,reason,created_at,updated_at) VALUES ('unknown',?,1,'job_v1','timeout',1,1)", (job_id,))
    await manager.store._call(settle)
    return await manager.get_snapshot(job_id)


@pytest.mark.asyncio
async def test_retry_selects_unsuccessful_targets_preserving_history_and_fence(manager, tmp_path):
    original = await completed(manager, tmp_path)
    created, retry = await manager.retry_failed(original['job_id'], str(uuid.uuid4()), artifact_root=tmp_path, online_devices=ONLINE)
    assert created and retry['job_id'] != original['job_id']
    assert retry['state'] == 'ready' and retry['dispatch_enabled'] is False
    assert retry['mode'] == 'sync' and retry['dest_path'] == original['dest_path']
    assert retry['artifact'] == original['artifact']
    assert set(retry['devices']) == {'failed', 'interrupted', 'unknown'}
    for device in retry['devices'].values():
        assert device['state'] == 'queued' and device['attempt'] == 1
        assert device['failure'] is None
    previous = await manager.get_snapshot(original['job_id'])
    for device in original['devices']:
        assert previous['devices'][device]['state'] == original['devices'][device]['state']
        assert previous['devices'][device]['retry_job_id'] == (retry['job_id'] if device in retry['devices'] else None)
    fences = await manager.store._call(lambda conn: [dict(row) for row in conn.execute('SELECT * FROM push_device_fences')])
    assert len(fences) == 1 and fences[0]['blocking_job_id'] == original['job_id']


@pytest.mark.asyncio
async def test_retry_idempotency_survives_original_result_change(manager, tmp_path):
    original = await completed(manager, tmp_path)
    request_id = str(uuid.uuid4())
    _, first = await manager.retry_failed(original['job_id'], request_id, artifact_root=tmp_path, online_devices=ONLINE)
    await manager.store._call(lambda conn: conn.execute("UPDATE push_job_devices SET state='succeeded' WHERE job_id=?", (original['job_id'],)))
    created, replay = await manager.retry_failed(original['job_id'], request_id, artifact_root=tmp_path, online_devices=ONLINE)
    assert not created and replay == first
    with pytest.raises(StoreConflict, match='different request'):
        await manager.retry_failed(str(uuid.uuid4()), request_id, artifact_root=tmp_path, online_devices=ONLINE)


@pytest.mark.asyncio
@pytest.mark.parametrize('unavailable', ['deleted', 'missing', 'truncated'])
async def test_retry_rejects_unavailable_artifact_atomically(manager, tmp_path, unavailable):
    original = await completed(manager, tmp_path)
    artifact_id = original['artifact']['artifact_id']
    if unavailable == 'deleted':
        await manager.store._call(lambda conn: conn.execute("UPDATE push_artifacts SET retention_state='deleted' WHERE artifact_id=?", (artifact_id,)))
    elif unavailable == 'missing':
        (tmp_path / (artifact_id + '.zip')).unlink()
    else:
        (tmp_path / (artifact_id + '.zip')).write_bytes(b'')
    with pytest.raises(StoreConflict, match='artifact'):
        await manager.retry_failed(original['job_id'], str(uuid.uuid4()), artifact_root=tmp_path, online_devices=ONLINE)
    assert await manager.store._call(lambda conn: conn.execute('SELECT COUNT(*) FROM push_jobs').fetchone()[0]) == 1


@pytest.mark.asyncio
async def test_retry_ready_job_prevents_gc_past_original_deadline(manager, tmp_path):
    original = await completed(manager, tmp_path)
    await manager.store._call(lambda conn: conn.execute("UPDATE push_job_devices SET state='failed' WHERE device_id='active'"))
    _, retry = await manager.retry_failed(original['job_id'], str(uuid.uuid4()), artifact_root=tmp_path, online_devices=ONLINE)
    assert manager.gc_artifacts_sync(tmp_path, retry_window_ms=0, timestamp=now_ms() + 1) == []
    assert (tmp_path / (retry['artifact']['artifact_id'] + '.zip')).is_file()


@pytest.mark.asyncio
async def test_retry_rejects_only_success_or_cancelled_targets(manager, tmp_path):
    original = await completed(manager, tmp_path)
    await manager.store._call(lambda conn: conn.execute("UPDATE push_job_devices SET state='succeeded' WHERE failure_code IS NULL OR failure_code <> 'cancelled'"))
    with pytest.raises(StoreConflict, match='no unsuccessful'):
        await manager.retry_failed(original['job_id'], str(uuid.uuid4()), artifact_root=tmp_path, online_devices=ONLINE)


@pytest.mark.asyncio
@pytest.mark.parametrize('terminal_at', [200, None])
async def test_shared_artifact_gc_uses_latest_reference_end(manager, tmp_path, terminal_at):
    original = await completed(manager, tmp_path)
    _, retry = await manager.retry_failed(original['job_id'], str(uuid.uuid4()), artifact_root=tmp_path, online_devices=ONLINE)
    artifact_id = original['artifact']['artifact_id']

    def finish(conn):
        conn.execute("UPDATE push_job_devices SET state='succeeded'")
        conn.execute("UPDATE push_jobs SET state='succeeded', terminal_at=1")
        conn.execute('UPDATE push_jobs SET terminal_at=?, updated_at=200 WHERE job_id=?',
                     (terminal_at, retry['job_id']))
    await manager.store._call(finish)
    assert (await manager.artifact_record(artifact_id))['retention_state'] == 'retained'
    assert manager.gc_artifacts_sync(tmp_path, retry_window_ms=100, timestamp=299) == []
    assert (tmp_path / (artifact_id + '.zip')).is_file()
    assert manager.gc_artifacts_sync(tmp_path, retry_window_ms=100, timestamp=300) == [artifact_id]
    assert not (tmp_path / (artifact_id + '.zip')).exists()


@pytest.mark.asyncio
async def test_old_failure_cannot_be_retried_twice_but_new_failure_can(manager, tmp_path):
    original = await completed(manager, tmp_path)
    _, retry = await manager.retry_failed(original['job_id'], str(uuid.uuid4()), artifact_root=tmp_path, online_devices=ONLINE)
    with pytest.raises(StoreConflict, match='no unsuccessful'):
        await manager.retry_failed(original['job_id'], str(uuid.uuid4()), artifact_root=tmp_path, online_devices=ONLINE)
    await manager.store._call(lambda conn: conn.execute("UPDATE push_job_devices SET state='failed' WHERE job_id=?", (retry['job_id'],)))
    _, again = await manager.retry_failed(retry['job_id'], str(uuid.uuid4()), artifact_root=tmp_path, online_devices=ONLINE)
    assert set(again['devices']) == set(retry['devices'])
    assert (await manager.get_snapshot(retry['job_id']))['devices']['failed']['retry_job_id'] == again['job_id']


@pytest.mark.asyncio
async def test_retry_skips_targets_superseded_by_later_work_for_same_destination(manager, tmp_path):
    original = await completed(manager, tmp_path)
    assert {d for d, a in original['devices'].items() if a['retryable']} == {
        'failed', 'interrupted', 'unknown'}
    request = canonicalize_create_request({
        'client_request_id': str(uuid.uuid4()), 'target_devices': ['failed', 'interrupted'],
        'mode': 'sync', 'dest_path': original['dest_path'],
        'source': {'display_name': 'newer', 'declared_file_count': 1, 'declared_total_bytes': 4},
    })
    _, newer = await manager.create_job(request, {
        device: (ProtocolMode.JOB_V1, ['push_job_id_v1']) for device in ('failed', 'interrupted')
    }, 600_000)
    await manager.store._call(lambda conn: conn.execute(
        'UPDATE push_jobs SET created_at=? WHERE job_id=?',
        (original['created_at'] + 1000, newer['job_id'])))
    # Pending newer work supersedes the old targets: retrying them would overwrite it.
    current = await manager.get_snapshot(original['job_id'])
    assert {d for d, a in current['devices'].items() if a['retryable']} == {'unknown'}
    _, retry = await manager.retry_failed(
        original['job_id'], str(uuid.uuid4()), artifact_root=tmp_path, online_devices=ONLINE)
    assert set(retry['devices']) == {'unknown'}


@pytest.mark.asyncio
async def test_never_dispatched_later_failure_does_not_supersede_retry(manager, tmp_path):
    original = await completed(manager, tmp_path)
    request = canonicalize_create_request({
        'client_request_id': str(uuid.uuid4()), 'target_devices': ['failed'],
        'mode': 'sync', 'dest_path': original['dest_path'],
        'source': {'display_name': 'newer', 'declared_file_count': 1, 'declared_total_bytes': 4},
    })
    _, newer = await manager.create_job(
        request, {'failed': (ProtocolMode.JOB_V1, ['push_job_id_v1'])}, 600_000)

    def fail_before_dispatch(conn):
        conn.execute('UPDATE push_jobs SET created_at=? WHERE job_id=?',
                     (original['created_at'] + 1000, newer['job_id']))
        conn.execute("UPDATE push_job_devices SET state='failed', failure_code='upload_failed' "
                     "WHERE job_id=?", (newer['job_id'],))

    await manager.store._call(fail_before_dispatch)
    current = await manager.get_snapshot(original['job_id'])
    assert current['devices']['failed']['retryable'] is True
