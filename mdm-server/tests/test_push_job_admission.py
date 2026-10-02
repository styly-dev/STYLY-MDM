"""Push job creation requires resumable job-v1 clients."""

from __future__ import annotations

import asyncio
import json
import uuid

import aiohttp
import pytest
from aiohttp.test_utils import TestServer

from styly_mdm import server


@pytest.fixture(autouse=True)
def reset_server_state(tmp_path):
    saved_paths = (server.DATA_DIR, server.APK_DIR, server.BUNDLE_DIR, server.REGISTRY_PATH)
    collections = (
        server.devices,
        server.admin_connections,
        server.pending_transfers,
        server._transfer_tasks,
        server.pending_self_updates,
        server._apk_hash_cache,
        server.device_registry,
    )
    for collection in collections:
        collection.clear()
    server.reset_transfer_slots()
    server._apply_data_dir(str(tmp_path))
    yield
    for collection in collections:
        collection.clear()
    server.reset_transfer_slots()
    server.DATA_DIR, server.APK_DIR, server.BUNDLE_DIR, server.REGISTRY_PATH = saved_paths


async def _registered(session: aiohttp.ClientSession, base: str, capabilities: list[str]):
    device_id = str(uuid.uuid4())
    device = await session.ws_connect(base + "/ws/device")
    await device.send_json({
        "type": "REGISTER",
        "identity_scheme": server.IDENTITY_SCHEME,
        "device_id": device_id,
        "model": "M",
        "ip": "1.1.1.2",
        "version_code": 10,
        "version_name": "guid",
        "capabilities": capabilities,
        "process_instance_id": str(uuid.uuid4()),
    })

    async def registered() -> None:
        while True:
            message = await device.receive()
            if message.type is aiohttp.WSMsgType.TEXT and json.loads(message.data).get(
                "type"
            ) == "REGISTERED":
                return

    await asyncio.wait_for(registered(), timeout=2)
    return device_id, device


def _request(device_id: str) -> dict:
    return {
        "client_request_id": str(uuid.uuid4()),
        "target_devices": [device_id],
        "mode": "push",
        "dest_path": "/sdcard/STYLY/admission",
        # Small content: there is no size threshold below which resume is optional.
        "source": {"display_name": "small", "declared_file_count": 1, "declared_total_bytes": 1},
    }


@pytest.mark.asyncio
@pytest.mark.parametrize("capabilities,accepted", [
    (["push_job_id_v1", "push_resume_v1"], True),
    (["push_job_id_v1"], False),
])
async def test_push_job_targets_must_support_resume(capabilities, accepted):
    test_server = TestServer(server.create_app())
    await test_server.start_server()
    base = f"http://{test_server.host}:{test_server.port}"
    try:
        async with aiohttp.ClientSession() as session:
            device_id, device = await _registered(session, base, capabilities)
            async with session.post(base + "/api/push-jobs", json=_request(device_id)) as response:
                body = await response.json()
                if accepted:
                    assert response.status == 201
                else:
                    assert response.status == 422
                    assert "push_resume_v1" in body["error"]
            await device.close()
    finally:
        await test_server.close()
