import asyncio
import json

from styly_mdm.push_jobs import ProtocolMode
from styly_mdm.push_scheduler import PushScheduler


def test_job_v1_command_uses_absolute_artifact_url():
    snapshot = {
        'job_id': 'job',
        'revision': 8,
        'mode': 'sync',
        'dest_path': '/sdcard/STYLY/content',
        'artifact': {
            'artifact_id': 'artifact',
            'url': '/artifacts/artifact',
            'display_filename': 'content.zip',
            'byte_size': 123,
            'sha256': 'a' * 64,
        },
        'devices': {'D1': {'attempt': 1}},
    }
    command = PushScheduler._command(
        snapshot, 'D1', ProtocolMode.JOB_V1, 'http://10.0.0.2:7070',
    )
    assert command['artifact_url'] == 'http://10.0.0.2:7070/artifacts/artifact'
    assert command['bundle_url'] == command['artifact_url']
    assert command['delete_extras'] is True


class _Ws:
    def __init__(self):
        self.messages = []

    async def send_str(self, value):
        self.messages.append(json.loads(value))


def test_exact_reconcile_can_reuse_held_owner_lock():
    async def scenario():
        lock = asyncio.Lock()
        ws = _Ws()
        session = __import__(
            'styly_mdm.push_scheduler', fromlist=['LiveSession']
        ).LiveSession(
            device_id='D1',
            session_id='session',
            ws=ws,
            capabilities=frozenset({'push_job_id_v1'}),
            process_instance_id='process',
            owner_lock=lock,
            http_base='http://server',
        )
        scheduler = object.__new__(PushScheduler)
        scheduler.send_timeout = 0.5
        scheduler.sessions = lambda: {'D1': session}
        async with lock:
            await asyncio.wait_for(
                scheduler.send_exact_reconcile(
                    session, 'job', 1, 'artifact', owner_lock_held=True
                ),
                timeout=0.5,
            )
        assert ws.messages[0]['type'] == 'PUSH_RECONCILE_REQUEST'

    asyncio.run(scenario())
