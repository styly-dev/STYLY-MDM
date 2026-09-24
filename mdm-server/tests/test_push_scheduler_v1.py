import asyncio
import json
from dataclasses import replace
from unittest.mock import AsyncMock

import pytest

from styly_mdm.push_jobs import ProtocolMode
from styly_mdm.push_scheduler import LiveSession, PushScheduler
from styly_mdm.transfer_registry import TransferKey, TransferRegistry


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
            'etag': '"' + 'a' * 64 + '"',
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


def test_protocol_admission_uses_the_configured_resume_threshold():
    scheduler = object.__new__(PushScheduler)
    scheduler.resume_threshold_bytes = 10
    session = LiveSession(
        device_id='D1',
        session_id='session',
        ws=_Ws(),
        capabilities=frozenset({'push_job_id_v1'}),
        process_instance_id='process',
        owner_lock=asyncio.Lock(),
        http_base='http://server',
    )

    assert scheduler._protocol_for(session, 10) is ProtocolMode.JOB_V1
    assert scheduler._protocol_for(session, 11) is None
    resumable = replace(
        session, capabilities=frozenset({'push_job_id_v1', 'push_resume_v1'})
    )
    assert scheduler._protocol_for(resumable, 11) is ProtocolMode.JOB_V1


@pytest.mark.parametrize("artifact_size,capabilities,expected_code", [
    (11, {"push_job_id_v1"}, "artifact_requires_push_resume_v1"),
    (10, set(), "capability_changed_before_dispatch"),
])
def test_dispatch_reports_artifact_requirement_separately_from_capability_change(
    artifact_size, capabilities, expected_code
):
    async def scenario():
        session = LiveSession(
            device_id="D1", session_id="session", ws=_Ws(),
            capabilities=frozenset(capabilities), process_instance_id="process",
            owner_lock=asyncio.Lock(), http_base="http://server",
        )
        scheduler = object.__new__(PushScheduler)
        scheduler.resume_threshold_bytes = 10
        scheduler.sessions = lambda: {"D1": session}
        scheduler.transfer_slots = lambda: asyncio.Semaphore(1)
        scheduler._fail_current = AsyncMock()
        await scheduler._dispatch_assignment_inner({
            "job": {
                "job_id": "job", "declared_total_bytes": 10,
                "artifact": {"byte_size": artifact_size},
            },
            "device_id": "D1", "attempt": 1,
        })
        scheduler._fail_current.assert_awaited_once()
        assert scheduler._fail_current.await_args.args[3] == expected_code
        assert session.ws.messages == []

    asyncio.run(scenario())


def test_active_reconnect_keeps_the_existing_transfer_slot():
    async def scenario():
        scheduler = object.__new__(PushScheduler)
        scheduler.transfer_registry = TransferRegistry()
        scheduler.transfer_slots = lambda: asyncio.Semaphore(0)
        accept = asyncio.get_running_loop().create_future()
        scheduler._accept_waiters = {("job", "D1", 1): accept}
        key = TransferKey("push", "D1", "job", 1)
        future = asyncio.get_running_loop().create_future()
        scheduler.transfer_registry.register(key, future)

        await asyncio.wait_for(
            scheduler.ensure_active_transfer_slot("job", "D1", 1), timeout=0.5
        )

        assert scheduler.transfer_registry.get(key) is future
        assert not future.done()
        assert accept.result()[0] == "accepted"

    asyncio.run(scenario())


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
