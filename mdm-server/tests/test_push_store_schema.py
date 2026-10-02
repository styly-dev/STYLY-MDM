"""Schema upgrade backup, retry-job linkage, artifact GC, and action flags."""

import sqlite3
import threading
import uuid
from pathlib import Path

import pytest

from styly_mdm.push_job_manager import PushJobManager
from styly_mdm.push_job_store import PushJobStore, StoreConflict
from styly_mdm.push_jobs import ProtocolMode, assignment_actions, canonicalize_create_request


async def _ready_job(manager: PushJobManager, root: Path, states: dict[str, str]) -> dict:
    request = canonicalize_create_request({
        'client_request_id': str(uuid.uuid4()), 'target_devices': list(states),
        'mode': 'push', 'dest_path': '/sdcard/STYLY/content',
        'source': {'display_name': 'content', 'declared_file_count': 1, 'declared_total_bytes': 4},
    })
    capabilities = ['push_job_id_v1', 'push_resume_v1']
    _, job = await manager.create_job(
        request, {device: (ProtocolMode.JOB_V1, capabilities) for device in states}, 600_000,
    )
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
        conn.execute(
            "UPDATE push_jobs SET state='completed_with_errors', terminal_at=1 WHERE job_id=?",
            (job_id,),
        )
        for device, state in states.items():
            conn.execute(
                "UPDATE push_job_devices SET state=?, failure_code=?, dispatch_revision=1, "
                "dispatch_capability_snapshot_json=? WHERE job_id=? AND device_id=?",
                (state, None if state == 'succeeded' else 'download_failed',
                 '["push_job_id_v1","push_resume_v1"]', job_id, device),
            )
    await manager.store._call(settle)
    return await manager.get_snapshot(job_id)


def _downgrade_to_schema_one(path: Path) -> None:
    with sqlite3.connect(path) as conn:
        conn.execute('DROP INDEX ix_push_jobs_retry_of')
        conn.execute('ALTER TABLE push_jobs DROP COLUMN retry_of_job_id')
        conn.execute('ALTER TABLE push_job_devices DROP COLUMN dispatch_revision')
        conn.execute('ALTER TABLE push_job_devices DROP COLUMN cancel_requested_at')
        conn.execute("UPDATE server_metadata SET value='1' WHERE key='schema_version'")


@pytest.mark.asyncio
async def test_schema_one_upgrade_writes_restorable_backup_first(tmp_path):
    path = tmp_path / 'push_jobs.sqlite3'
    store = PushJobStore(path)
    manager = PushJobManager(store)
    job = await _ready_job(manager, tmp_path, {'D1': 'failed'})
    store.close()
    _downgrade_to_schema_one(path)

    upgraded = PushJobStore(path)
    try:
        assert (await upgraded.get_snapshot(job['job_id']))['devices'].keys() == {'D1'}
    finally:
        upgraded.close()

    backup = tmp_path / 'push_jobs.sqlite3.v1.bak'
    assert backup.is_file()
    assert not (tmp_path / 'push_jobs.sqlite3.v1.bak.tmp').exists()
    with sqlite3.connect(backup) as conn:
        assert conn.execute(
            "SELECT value FROM server_metadata WHERE key='schema_version'"
        ).fetchone()[0] == '1'
        columns = {row[1] for row in conn.execute('PRAGMA table_info(push_job_devices)')}
        assert 'cancel_requested_at' not in columns
        assert conn.execute(
            'SELECT job_id FROM push_jobs'
        ).fetchall() == [(job['job_id'],)]
    with sqlite3.connect(path) as conn:
        assert conn.execute(
            "SELECT value FROM server_metadata WHERE key='schema_version'"
        ).fetchone()[0] == '3'


def test_new_or_current_database_writes_no_backup(tmp_path):
    path = tmp_path / 'push_jobs.sqlite3'
    PushJobStore(path).close()
    PushJobStore(path).close()
    assert sorted(p.name for p in tmp_path.glob('*.bak')) == []


@pytest.mark.asyncio
async def test_retry_job_is_linked_by_indexed_column(tmp_path):
    store = PushJobStore(tmp_path / 'jobs.sqlite3')
    manager = PushJobManager(store)
    try:
        original = await _ready_job(manager, tmp_path, {'D1': 'failed', 'D2': 'succeeded'})
        _, retry = await manager.retry_failed(
            original['job_id'], str(uuid.uuid4()), artifact_root=tmp_path,
            online_devices={'D1', 'D2'},
        )
        linked = await store._call(lambda conn: conn.execute(
            'SELECT retry_of_job_id FROM push_jobs WHERE job_id=?', (retry['job_id'],),
        ).fetchone()[0])
        assert linked == original['job_id']
        snapshot = await manager.get_snapshot(original['job_id'])
        assert snapshot['devices']['D1']['retry_job_id'] == retry['job_id']
        assert snapshot['devices']['D2']['retry_job_id'] is None
        plan = await store._call(lambda conn: ' '.join(
            str(row[3]) for row in conn.execute(
                'EXPLAIN QUERY PLAN SELECT job_id FROM push_jobs WHERE retry_of_job_id=?',
                (original['job_id'],),
            )
        ))
        assert 'ix_push_jobs_retry_of' in plan
    finally:
        store.close()


@pytest.mark.asyncio
async def test_gc_tombstones_in_db_then_unlinks_outside_the_db_worker(tmp_path, monkeypatch):
    store = PushJobStore(tmp_path / 'jobs.sqlite3')
    manager = PushJobManager(store)
    try:
        job = await _ready_job(manager, tmp_path, {'D1': 'succeeded'})
        artifact_id = job['artifact']['artifact_id']
        unlink_threads = []
        original_unlink = Path.unlink

        def recording_unlink(self, missing_ok=False):
            unlink_threads.append(threading.current_thread().name)
            return original_unlink(self, missing_ok=missing_ok)

        monkeypatch.setattr(Path, 'unlink', recording_unlink)
        assert store.gc_artifacts_sync(tmp_path, retry_window_ms=0, timestamp=10) == [artifact_id]
        assert unlink_threads and 'push-job-db' not in unlink_threads
        assert not (tmp_path / (artifact_id + '.zip')).exists()
        record = await store.artifact_record(artifact_id)
        assert record['retention_state'] == 'deleted'
    finally:
        store.close()


@pytest.mark.asyncio
async def test_gc_retries_tombstoned_file_after_reopen_and_reports_removal_once(
    tmp_path, monkeypatch
):
    store = PushJobStore(tmp_path / 'jobs.sqlite3')
    manager = PushJobManager(store)
    reopened = None
    try:
        job = await _ready_job(manager, tmp_path, {'D1': 'succeeded'})
        artifact_id = job['artifact']['artifact_id']
        artifact_path = tmp_path / f'{artifact_id}.zip'
        original_unlink = Path.unlink
        attempts = 0

        def failing_unlink(self, missing_ok=False):
            nonlocal attempts
            if self == artifact_path and attempts == 0:
                attempts += 1
                raise PermissionError('temporary filesystem failure')
            return original_unlink(self, missing_ok=missing_ok)

        monkeypatch.setattr(Path, 'unlink', failing_unlink)
        assert store.gc_artifacts_sync(tmp_path, retry_window_ms=0, timestamp=10) == []
        assert (await store.artifact_record(artifact_id))['retention_state'] == 'deleted'
        assert artifact_path.is_file()

        # Reopening after the tombstone commit models a process exit before unlink.
        store.close()
        reopened = PushJobStore(tmp_path / 'jobs.sqlite3')
        reopened_manager = PushJobManager(reopened)
        assert (await reopened.artifact_record(artifact_id))['retention_state'] == 'deleted'
        assert reopened_manager.gc_artifacts_sync(
            tmp_path, retry_window_ms=0, timestamp=11
        ) == [artifact_id]
        assert not artifact_path.exists()
        assert (await reopened.artifact_record(artifact_id))['retention_state'] == 'deleted'
        assert reopened_manager.gc_artifacts_sync(
            tmp_path, retry_window_ms=0, timestamp=12
        ) == []
    finally:
        store.close()
        if reopened is not None:
            reopened.close()


def _actions(**overrides):
    values = dict(
        job_state='running', dispatch_enabled=True, state='queued',
        queue_reason='download_retry_exhausted', cancel_requested=False, retried=False,
        resume_supported=True, blocking_fence=False,
    )
    values.update(overrides)
    return assignment_actions(**values)


def test_assignment_actions_rules():
    waiting = _actions()
    assert (waiting.manual_wait, waiting.resume_required, waiting.cancellable) == (True, True, True)
    queued = _actions(queue_reason='awaiting_dispatch')
    assert (queued.manual_wait, queued.resume_required, queued.cancellable) == (False, False, False)
    paused_job = _actions(queue_reason=None, dispatch_enabled=False)
    assert paused_job.resume_required and not paused_job.cancellable
    assert not _actions(resume_supported=False).cancellable
    assert _actions(state='reconciling', queue_reason=None).cancellable
    assert not _actions(state='unconfirmed', queue_reason=None).cancellable
    assert _actions(state='unconfirmed', queue_reason=None, blocking_fence=True).cancellable
    for closed in ({'cancel_requested': True}, {'retried': True}):
        flags = _actions(**closed)
        assert not flags.resume_required and not flags.cancellable


@pytest.mark.asyncio
async def test_retried_target_is_not_cancellable_in_snapshot_or_store(tmp_path):
    store = PushJobStore(tmp_path / 'jobs.sqlite3')
    manager = PushJobManager(store)
    try:
        original = await _ready_job(manager, tmp_path, {'D1': 'unconfirmed'})
        job_id = original['job_id']
        await store._call(lambda conn: conn.execute(
            "INSERT INTO push_device_fences(device_id,blocking_job_id,blocking_attempt,"
            "protocol_mode,reason,created_at,updated_at) VALUES ('D1',?,1,'job_v1','timeout',1,1)",
            (job_id,),
        ))
        assert (await manager.get_snapshot(job_id))['devices']['D1']['cancellable'] is True
        await manager.retry_failed(
            job_id, str(uuid.uuid4()), artifact_root=tmp_path, online_devices={'D1'},
        )
        assert (await manager.get_snapshot(job_id))['devices']['D1']['cancellable'] is False
        with pytest.raises(StoreConflict):
            await manager.cancel_interrupted(job_id, 'D1')
    finally:
        store.close()
