"""An ungraceful TCP disconnect must retire the Push session as well."""
import asyncio
import uuid

import aiohttp
import pytest
from aiohttp.test_utils import TestServer

from styly_mdm import server
from server_test_helpers import reset_server_state
from push_test_helpers import push_request
from ws_test_helpers import receive_type


async def wait_until(predicate):
    async def poll():
        while not await predicate():
            await asyncio.sleep(0)
    await asyncio.wait_for(poll(), 2)


@pytest.mark.asyncio
@pytest.mark.parametrize("graceful", [False, True])
async def test_disconnect_retires_session_and_allows_offline_cancel(graceful):
    async with TestServer(server.create_app()) as web, aiohttp.ClientSession() as client:
        runtime = web.app["push_runtime"]
        device_id = str(uuid.uuid4())
        device = await client.ws_connect(web.make_url("/ws/device"))
        await device.send_json({
            "type": "REGISTER", "identity_scheme": server.IDENTITY_SCHEME,
            "device_id": device_id, "model": "disconnect-test",
            "capabilities": ["push_job_id_v1", "push_resume_v1"],
            "process_instance_id": str(uuid.uuid4()),
            "push_state": {"status": "available"}, "push_runtime": {"active": None},
        })
        assert await receive_type(device, "REGISTERED") is not None
        admin = await client.ws_connect(web.make_url("/ws/admin"))
        async with client.post(web.make_url("/api/push-jobs"), json=push_request(device_id)) as response:
            assert response.status == 201
            job = await response.json()
        form = aiohttp.FormData()
        form.add_field("files", b"payload", filename="payload.txt")
        async with client.post(web.make_url(job["upload_url"]), data=form) as response:
            assert response.status == 200
        await admin.send_json({"type": "PUSH_FILES", "job_id": job["job_id"]})
        assert await receive_type(device, "EXECUTE_PUSH_FILES") is not None
        await device.send_json({"type": "PUSH_JOB_ACCEPTED", "job_id": job["job_id"], "attempt": 1})

        async def downloading():
            assignment = await runtime.manager.assignment(job["job_id"], device_id)
            return assignment["state"] == "downloading"
        await wait_until(downloading)
        session = runtime.sessions[device_id]
        if graceful:
            await device.close()
        else:
            device._writer.transport.abort()

        async def retired():
            return device_id not in server.devices and device_id not in runtime.sessions
        await wait_until(retired)
        assignment = await runtime.manager.assignment(job["job_id"], device_id)
        assert assignment["state"] == "reconciling"
        # Calling close after the handler's finally block must remain idempotent.
        await session.ws.close()
        await admin.send_json({"type": "CANCEL_PUSH_JOB", "job_id": job["job_id"]})
        assert await receive_type(admin, "PUSH_JOB_ACTION_SENT") is not None
        snapshot = await runtime.store.get_snapshot(job["job_id"])
        assert snapshot["devices"][device_id]["cancel_requested"]
        assert snapshot["devices"][device_id]["state"] == "reconciling"
        await admin.close()
        await device.close()
