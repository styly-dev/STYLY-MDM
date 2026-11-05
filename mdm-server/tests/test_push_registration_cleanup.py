"""Real WebSocket coverage for rejecting stale interrupted Push ownership."""

from __future__ import annotations

import asyncio
import uuid

import aiohttp
import pytest
from aiohttp.test_utils import TestServer

from server_test_helpers import reset_server_state
from styly_mdm import server
from ws_test_helpers import receive_type


async def recv_type(ws, expected: str, timeout: float = 2.0) -> dict:
    payload = await receive_type(ws, expected, timeout=timeout)
    assert payload is not None, f"timed out waiting for {expected}"
    return payload


def push_request(device_id: str) -> dict:
    return {
        "client_request_id": str(uuid.uuid4()),
        "target_devices": [device_id],
        "mode": "push",
        "dest_path": "/sdcard/STYLY/recovery",
        "source": {
            "display_name": "recovery-test",
            "declared_file_count": 1,
            "declared_total_bytes": 7,
        },
    }


@pytest.mark.asyncio
async def test_unknown_interrupted_registration_is_rejected_then_push_can_dispatch():
    device_id = str(uuid.uuid4())
    unknown_job_id = str(uuid.uuid4())
    artifact_id = str(uuid.uuid4())
    process_instance_id = str(uuid.uuid4())
    async with TestServer(server.create_app()) as test_server, aiohttp.ClientSession() as client:
        base = f"http://{test_server.host}:{test_server.port}"
        runtime = test_server.app["push_runtime"]
        admin = await client.ws_connect(base + "/ws/admin")
        device = await client.ws_connect(base + "/ws/device")
        registration = {
            "type": "REGISTER",
            "identity_scheme": server.IDENTITY_SCHEME,
            "device_id": device_id,
            "model": "test",
            "ip": "127.0.0.1",
            "version_code": 1,
            "version_name": "test",
            "capabilities": ["push_job_id_v1", "push_resume_v1"],
            "process_instance_id": process_instance_id,
            "push_state": {"status": "available"},
            "push_runtime": {"active": {
                "job_id": unknown_job_id,
                "attempt": 1,
                "artifact_id": artifact_id,
                "revision": 7,
                "status": "interrupted",
                "phase": "downloading",
                "validated_offset": 3,
            }},
        }
        await device.send_json(registration)

        # The rejection must be sent only after canonical registration succeeds.
        await recv_type(device, "REGISTERED")
        rejection = await recv_type(device, "PUSH_RESUME_REJECTED")
        assert rejection["job_id"] == unknown_job_id
        assert rejection["artifact_id"] == artifact_id
        assert rejection["revision"] == 7
        assert await runtime.manager.opaque_reconcile_target(device_id) is not None

        # A manual reconcile of the still-interrupted state retries rejection and
        # must not clear ownership until the client confirms exact absence.
        await admin.send_json({"type": "RECONCILE_PUSH_DEVICE", "device_id": device_id})
        probe = await recv_type(device, "PUSH_RECONCILE_REQUEST")
        assert probe["jobs"] == [{
            "job_id": unknown_job_id,
            "attempt": 1,
            "artifact_id": artifact_id,
        }]
        await device.send_json({
            "type": "PUSH_RECONCILE_REPORT",
            "job_id": unknown_job_id,
            "attempt": 1,
            "artifact_id": artifact_id,
            "revision": 7,
            "status": "interrupted",
            "phase": "downloading",
        })
        retried_rejection = await recv_type(device, "PUSH_RESUME_REJECTED")
        assert retried_rejection["job_id"] == unknown_job_id
        assert await runtime.manager.opaque_reconcile_target(device_id) is not None

        await device.send_json({
            "type": "PUSH_RECONCILE_REPORT",
            "job_id": unknown_job_id,
            "attempt": 1,
            "artifact_id": str(uuid.uuid4()),
            "status": "absent",
        })
        # A same-socket REGISTER acknowledgement orders after the preceding report.
        refresh = {
            **registration,
            "push_runtime": {"active": None},
        }
        await device.send_json(refresh)
        await recv_type(device, "REGISTERED")
        assert await runtime.manager.opaque_reconcile_target(device_id) is not None

        await admin.send_json({"type": "RECONCILE_PUSH_DEVICE", "device_id": device_id})
        probe = await recv_type(device, "PUSH_RECONCILE_REQUEST")
        assert probe["jobs"][0]["job_id"] == unknown_job_id

        await device.send_json({
            "type": "PUSH_RECONCILE_REPORT",
            "job_id": unknown_job_id,
            "attempt": 1,
            "artifact_id": artifact_id,
            "status": "absent",
        })
        await device.send_json(refresh)
        await recv_type(device, "REGISTERED")
        assert await runtime.manager.opaque_reconcile_target(device_id) is None

        async with client.post(base + "/api/push-jobs", json=push_request(device_id)) as response:
            assert response.status == 201
            job = await response.json()
        form = aiohttp.FormData()
        form.add_field("files", b"payload", filename="payload.txt")
        async with client.post(base + job["upload_url"], data=form) as response:
            assert response.status == 200

        await admin.send_json({"type": "PUSH_FILES", "job_id": job["job_id"]})
        await recv_type(admin, "PUSH_FILES_SENT")
        command = await recv_type(device, "EXECUTE_PUSH_FILES")
        assert command["job_id"] == job["job_id"]
        await device.send_json({
            "type": "PUSH_JOB_ACCEPTED",
            "job_id": job["job_id"],
            "attempt": 1,
            "artifact_id": command["artifact_id"],
            "phase": "downloading",
        })

        async def accepted() -> dict:
            while True:
                assignment = await runtime.manager.assignment(job["job_id"], device_id)
                if assignment is not None and assignment["state"] == "downloading":
                    return assignment
                await asyncio.sleep(0)

        assert await asyncio.wait_for(accepted(), timeout=2)

        await device.close()
        await admin.close()


@pytest.mark.asyncio
async def test_terminal_assignment_rejection_waits_for_registration_ack():
    device_id = str(uuid.uuid4())
    process_instance_id = str(uuid.uuid4())
    async with TestServer(server.create_app()) as test_server, aiohttp.ClientSession() as client:
        base = f"http://{test_server.host}:{test_server.port}"
        runtime = test_server.app["push_runtime"]
        first_device = await client.ws_connect(base + "/ws/device")
        registration = {
            "type": "REGISTER",
            "identity_scheme": server.IDENTITY_SCHEME,
            "device_id": device_id,
            "model": "test",
            "ip": "127.0.0.1",
            "version_code": 1,
            "version_name": "test",
            "capabilities": ["push_job_id_v1", "push_resume_v1"],
            "process_instance_id": process_instance_id,
            "push_state": {"status": "available"},
            "push_runtime": {"active": None},
        }
        await first_device.send_json(registration)
        await recv_type(first_device, "REGISTERED")

        async with client.post(base + "/api/push-jobs", json=push_request(device_id)) as response:
            assert response.status == 201
            job = await response.json()
        form = aiohttp.FormData()
        form.add_field("files", b"payload", filename="payload.txt")
        async with client.post(base + job["upload_url"], data=form) as response:
            assert response.status == 200
        ready_job = await runtime.store.get_snapshot(job["job_id"])
        await runtime.store._call(lambda conn: conn.execute(
            "UPDATE push_job_devices SET state='failed', dispatch_revision=1, "
            "failure_code='device_rejected', terminal_at=1 WHERE job_id=?",
            (job["job_id"],),
        ))

        stale_report = {
            "job_id": job["job_id"],
            "attempt": 1,
            "artifact_id": ready_job["artifact"]["artifact_id"],
            "revision": 1,
            "status": "interrupted",
            "phase": "downloading",
            "validated_offset": 0,
        }
        replacement = await client.ws_connect(base + "/ws/device")
        await replacement.send_json({
            **registration,
            "process_instance_id": str(uuid.uuid4()),
            "push_runtime": {"active": stale_report},
        })
        await recv_type(replacement, "REGISTERED")
        rejection = await recv_type(replacement, "PUSH_RESUME_REJECTED")
        assert rejection["job_id"] == job["job_id"]
        assert rejection["artifact_id"] == ready_job["artifact"]["artifact_id"]

        await replacement.close()
        await first_device.close()
