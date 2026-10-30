"""Retry only unsuccessful targets while preserving bytes, history, and fences."""
import asyncio
import sqlite3
import threading
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
    _, job = await manager.store.create_job(request, {
        device: (ProtocolMode.JOB_V1, ['push_job_id_v1']) for device in states}, 600_000)
    job_id = job['job_id']
    await manager.store.start_upload(job_id)
    await manager.store.mark_packaging(job_id, 1, 4)
    artifact_id = str(uuid.uuid4())
    (root / (artifact_id + '.zip')).write_bytes(b'data')
    await manager.store.publish_artifact(job_id, {
        'artifact_id': artifact_id, 'storage_name': artifact_id + '.zip',
        'display_filename': 'content.zip', 'byte_size': 4, 'sha256': 'a' * 64, 'entry_count': 1,
    })
    def settle(conn):
        conn.execute("UPDATE push_jobs SET state='completed_with_errors', terminal_at=1 WHERE job_id=?", (job_id,))
        for device, (state, code) in states.items():
            conn.execute('UPDATE push_job_devices SET state=?, failure_code=? WHERE job_id=? AND device_id=?', (state, code, job_id, device))
        conn.execute("INSERT INTO push_device_fences(device_id,blocking_job_id,blocking_attempt,protocol_mode,reason,created_at,updated_at) VALUES ('unknown',?,1,'job_v1','timeout',1,1)", (job_id,))
    await manager.store._call(settle)
    return await manager.store.get_snapshot(job_id)


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
    previous = await manager.store.get_snapshot(original['job_id'])
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
    assert manager.store.gc_artifacts_sync(tmp_path, retry_window_ms=0, timestamp=now_ms() + 1) == []
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
    assert (await manager.store.artifact_record(artifact_id))['retention_state'] == 'retained'
    assert manager.store.gc_artifacts_sync(tmp_path, retry_window_ms=100, timestamp=299) == []
    assert (tmp_path / (artifact_id + '.zip')).is_file()
    assert manager.store.gc_artifacts_sync(tmp_path, retry_window_ms=100, timestamp=300) == [artifact_id]
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
    assert (await manager.store.get_snapshot(retry['job_id']))['devices']['failed']['retry_job_id'] == again['job_id']


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
    _, newer = await manager.store.create_job(request, {
        device: (ProtocolMode.JOB_V1, ['push_job_id_v1']) for device in ('failed', 'interrupted')
    }, 600_000)
    await manager.store._call(lambda conn: conn.execute(
        'UPDATE push_jobs SET created_at=? WHERE job_id=?',
        (original['created_at'] + 1000, newer['job_id'])))
    # Pending newer work supersedes the old targets: retrying them would overwrite it.
    current = await manager.store.get_snapshot(original['job_id'])
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
    _, newer = await manager.store.create_job(
        request, {'failed': (ProtocolMode.JOB_V1, ['push_job_id_v1'])}, 600_000)

    def fail_before_dispatch(conn):
        conn.execute('UPDATE push_jobs SET created_at=? WHERE job_id=?',
                     (original['created_at'] + 1000, newer['job_id']))
        conn.execute("UPDATE push_job_devices SET state='failed', failure_code='upload_failed' "
                     "WHERE job_id=?", (newer['job_id'],))

    await manager.store._call(fail_before_dispatch)
    current = await manager.store.get_snapshot(original['job_id'])
    assert current['devices']['failed']['retryable'] is True


@pytest.mark.asyncio
@pytest.mark.parametrize('ending', ['failure', 'expiry', 'cancel', 'offline', 'startup'])
async def test_newer_work_publishes_retry_eligibility_revisions(manager, tmp_path, ending):
    from styly_mdm.push_jobs import JobState
    from test_push_cancellation import runtime_for
    original = await completed(manager, tmp_path)
    # This fixture is terminal; remove its synthetic active row before startup recovery.
    await manager.store._call(lambda conn: conn.execute(
        "UPDATE push_job_devices SET state='succeeded' WHERE job_id=? AND device_id='active'",
        (original['job_id'],)))
    original = await manager.store.get_snapshot(original['job_id'])
    runtime, _ = runtime_for(manager)
    del runtime.publish
    await runtime.publish(original)
    request = canonicalize_create_request({
        'client_request_id': str(uuid.uuid4()), 'target_devices': ['failed'],
        'mode': 'sync', 'dest_path': original['dest_path'],
        'source': {'display_name': 'newer', 'declared_file_count': 1, 'declared_total_bytes': 4},
    })
    _, newer = await manager.store.create_job(
        request, {'failed': (ProtocolMode.JOB_V1, ['push_job_id_v1', 'push_resume_v1'])},
        0 if ending == 'expiry' else 60000)
    await runtime.publish(newer)
    suppressed = runtime.pending_publications[original['job_id']]
    assert not suppressed['devices']['failed']['retryable']
    assert suppressed['revision'] > original['revision']
    if ending == 'expiry':
        settled = await manager.store.expire_created(newer['job_id'])
    elif ending == 'failure':
        settled = await manager.store.fail_pre_dispatch(newer['job_id'], JobState.FAILED, 'upload_failed', 'failed upload')
    else:
        await manager.store.start_upload(newer['job_id'])
        if ending == 'startup':
            manager.store.recover_startup_sync(accept_reconciliation_timeout_ms=1000, reconciliation_timeout_ms=1000)
            settled = await manager.store.get_snapshot(newer['job_id'])
        else:
            await manager.store.mark_packaging(newer['job_id'], 1, 4)
            await manager.store.publish_artifact(newer['job_id'], {
                'artifact_id': str(uuid.uuid4()), 'storage_name': 'newer.zip',
                'display_filename': 'newer.zip', 'byte_size': 4, 'sha256': 'b'*64, 'entry_count': 1,
            })
            if ending == 'cancel':
                settled = await manager.cancel_interrupted(newer['job_id'], 'failed')
            else:
                await manager.store.enable_dispatch(newer['job_id'])
                [settled] = await manager.settle_offline_before_dispatch('failed')
    await runtime.publish(settled)
    restored = runtime.pending_publications[original['job_id']]
    assert restored['devices']['failed']['retryable']
    assert restored['revision'] > suppressed['revision']
    # Publishing is read-only and cannot restart the retention clock.
    assert restored['terminal_at'] == original['terminal_at']
    assert restored['updated_at'] == original['updated_at']
    await runtime.publish(settled)
    assert await manager.store.get_snapshot(original['job_id']) == restored


@pytest.mark.asyncio
async def test_retry_dependency_revision_failure_rolls_back_new_job(manager, tmp_path):
    import sqlite3
    original = await completed(manager, tmp_path)
    request = canonicalize_create_request({
        'client_request_id': str(uuid.uuid4()), 'target_devices': ['failed'],
        'mode': 'sync', 'dest_path': original['dest_path'],
        'source': {'display_name': 'newer', 'declared_file_count': 1, 'declared_total_bytes': 4},
    })
    def deny_revision(action, table, column, _database, _trigger):
        return (sqlite3.SQLITE_DENY if action == sqlite3.SQLITE_UPDATE
                and table == 'push_jobs' and column == 'revision' else sqlite3.SQLITE_OK)
    await manager.store._call(lambda conn: conn.set_authorizer(deny_revision))
    try:
        with pytest.raises(sqlite3.DatabaseError):
            await manager.store.create_job(request, {'failed': (ProtocolMode.JOB_V1, [])}, 60000)
    finally:
        await manager.store._call(lambda conn: conn.set_authorizer(lambda *_args: sqlite3.SQLITE_OK))
    assert await manager.store.get_snapshot(original['job_id']) == original
    assert await manager.store._call(lambda conn: conn.execute('SELECT COUNT(*) FROM push_jobs').fetchone()[0]) == 1


def newer_request(original, targets=None):
    return canonicalize_create_request({
        'client_request_id': str(uuid.uuid4()), 'target_devices': targets or ['failed'],
        'mode': 'sync', 'dest_path': original['dest_path'],
        'source': {'display_name': 'newer', 'declared_file_count': 1, 'declared_total_bytes': 4},
    })


async def create_newer(manager, original):
    return await manager.store.create_job(
        newer_request(original), {'failed': (ProtocolMode.JOB_V1, [])}, 60000)


@pytest.mark.asyncio
async def test_publish_uses_committed_retry_snapshots_without_db_access(manager, tmp_path, monkeypatch):
    from test_push_cancellation import runtime_for
    original = await completed(manager, tmp_path)
    _, newer = await create_newer(manager, original)
    expected = await manager.store.get_snapshot(original['job_id'])
    runtime, _ = runtime_for(manager)
    del runtime.publish

    def reject_db_access(_op):
        pytest.fail('publication must not submit database work')

    monkeypatch.setattr(manager.store._worker, 'submit', reject_db_access)
    await runtime.publish(newer)
    assert runtime.pending_publications[original['job_id']] == expected
    assert not expected['devices']['failed']['retryable']
    # No-op publications also stay entirely in memory after the notices are drained.
    await runtime.publish(newer)
    await runtime.publish(original)
    assert runtime.pending_publications[original['job_id']] == expected


@pytest.mark.asyncio
@pytest.mark.parametrize('failure', ['source_snapshot', 'dependent_snapshot', 'commit'])
async def test_retry_publications_roll_back_with_transaction(manager, tmp_path, monkeypatch, failure):
    original = await completed(manager, tmp_path)
    assert manager.store.take_retry_publications() == []
    notifications = []
    manager.store.set_retry_publication_wakeup(lambda: notifications.append(True))
    snapshot = manager.store._snapshot

    def fail_snapshot(conn, job_id):
        if (job_id == original['job_id']) == (failure == 'dependent_snapshot'):
            raise RuntimeError('snapshot failed after invalidation')
        return snapshot(conn, job_id)

    def deny_commit(action, operation, *_args):
        return (sqlite3.SQLITE_DENY if action == sqlite3.SQLITE_TRANSACTION
                and operation == 'COMMIT' else sqlite3.SQLITE_OK)

    with monkeypatch.context() as patch:
        if failure == 'commit':
            await manager.store._call(lambda conn: conn.set_authorizer(deny_commit))
        else:
            patch.setattr(manager.store, '_snapshot', fail_snapshot)
        try:
            with pytest.raises((RuntimeError, sqlite3.DatabaseError)):
                await create_newer(manager, original)
        finally:
            await manager.store._call(lambda conn: conn.set_authorizer(lambda *_args: sqlite3.SQLITE_OK))
    assert await manager.store.get_snapshot(original['job_id']) == original
    assert manager.store.take_retry_publications() == []
    assert notifications == []
    # A later successful transaction must not pick up rolled-back invalidations.
    await manager.store.create_job(
        newer_request(original, ['other']), {'other': (ProtocolMode.JOB_V1, [])}, 60000)
    assert manager.store.take_retry_publications() == []


@pytest.mark.asyncio
async def test_cancelled_mutation_keeps_committed_retry_publications(manager, tmp_path, monkeypatch):
    from test_push_cancellation import runtime_for
    original = await completed(manager, tmp_path)
    started, release = threading.Event(), threading.Event()
    commit = manager.store._commit

    def blocked_commit(conn):
        started.set()
        assert release.wait(5)
        commit(conn)

    monkeypatch.setattr(manager.store, '_commit', blocked_commit)
    mutation = asyncio.create_task(create_newer(manager, original))
    try:
        assert await asyncio.to_thread(started.wait, 5)
        mutation.cancel()
        with pytest.raises(asyncio.CancelledError):
            await mutation
    finally:
        release.set()
        await asyncio.gather(mutation, return_exceptions=True)
    expected = await manager.store.get_snapshot(original['job_id'])
    runtime, _ = runtime_for(manager)
    del runtime.publish
    # Even an unrelated publication recovers the notice of the committed mutation.
    await runtime.publish({'job_id': 'unrelated', 'revision': 1})
    assert runtime.pending_publications[original['job_id']] == expected
    assert not expected['devices']['failed']['retryable']


@pytest.mark.asyncio
async def test_retry_publications_coalesce_to_final_committed_revision(manager, tmp_path):
    from styly_mdm.push_jobs import JobState
    from test_push_cancellation import runtime_for
    original = await completed(manager, tmp_path)
    _, newer = await create_newer(manager, original)
    await manager.store.fail_pre_dispatch(newer['job_id'], JobState.FAILED, 'upload_failed', 'failed upload')
    expected = await manager.store.get_snapshot(original['job_id'])
    runtime, _ = runtime_for(manager)
    del runtime.publish
    await runtime.publish(newer)
    await runtime.publish(original)
    assert runtime.pending_publications[original['job_id']] == expected
    assert expected['devices']['failed']['retryable']


@pytest.mark.asyncio
async def test_retry_dependency_lookup_skips_collected_history(manager, tmp_path):
    original = await completed(manager, tmp_path)
    _, newer = await create_newer(manager, original)

    def measure(conn, history):
        # Reuse the real schema and old job shape; only one artifact stays retained.
        template = dict(conn.execute('SELECT * FROM push_jobs WHERE job_id=?', (original['job_id'],)).fetchone())
        conn.execute('BEGIN')
        conn.execute('UPDATE push_job_devices SET enqueue_seq=? WHERE job_id=?',
                     (1000 + history * 255 + 1, newer['job_id']))
        for index in range(history):
            artifact_id = f'deleted-artifact-{index}'
            conn.execute("INSERT INTO push_artifacts (artifact_id,storage_name,display_filename,byte_size,sha256,entry_count,created_at,retention_state) VALUES (?,?,?,4,?,1,1,'deleted')",
                         (artifact_id, artifact_id, artifact_id, 'a' * 64))
            job = {**template, 'job_id': f'history-{index}', 'client_request_id': f'history-{index}', 'artifact_id': artifact_id}
            conn.execute(f"INSERT INTO push_jobs ({','.join(job)}) VALUES ({','.join('?' for _ in job)})", tuple(job.values()))
            conn.executemany("INSERT INTO push_job_devices (job_id,device_id,enqueue_seq,target_ordinal,protocol_mode,create_capability_snapshot_json,state,updated_at) VALUES (?,?,?,?,'job_v1','[]','failed',1)",
                             [(job['job_id'], 'failed' if device == 0 else f'history-device-{device}',
                               1000 + index * 255 + device, device) for device in range(255)])
        conn.execute('COMMIT')
        steps = 0

        def progress():
            nonlocal steps
            steps += 1
            return 0

        conn.set_progress_handler(progress, 1)
        try:
            assert manager.store._retry_dependent_job_ids(conn, newer['job_id']) == [original['job_id']]
        finally:
            conn.set_progress_handler(None, 0)
        return steps

    baseline = await manager.store._call(lambda conn: measure(conn, 0))
    with_history = await manager.store._call(lambda conn: measure(conn, 300))
    # Bound executed SQLite instructions, not machine-dependent wall time.
    assert with_history <= baseline * 2
