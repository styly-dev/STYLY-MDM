"""Push job admission and client durable-state reporting."""

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


async def _registered(
    session: aiohttp.ClientSession,
    base: str,
    capabilities: list[str],
    push_state: dict | None = None,
):
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
        "push_state": push_state or {"status": "available"},
        "push_runtime": {"active": None},
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


@pytest.mark.asyncio
async def test_unavailable_push_state_is_live_only_and_explained_on_create():
    test_server = TestServer(server.create_app())
    await test_server.start_server()
    base = f"http://{test_server.host}:{test_server.port}"
    try:
        async with aiohttp.ClientSession() as session:
            device_id, device = await _registered(
                session, base, [], push_state={"status": "unavailable"},
            )
            assert server.devices[device_id]["push_state_status"] == "unavailable"
            # Connection-scoped state is never persisted in the device registry.
            assert "push_state_status" not in server.device_registry[device_id]
            async with session.post(base + "/api/push-jobs", json=_request(device_id)) as response:
                assert response.status == 422
                assert "cannot store Push state" in (await response.json())["error"]
            await device.close()
    finally:
        await test_server.close()


@pytest.mark.asyncio
async def test_push_state_reset_notice_reaches_admins():
    test_server = TestServer(server.create_app())
    await test_server.start_server()
    base = f"http://{test_server.host}:{test_server.port}"
    try:
        async with aiohttp.ClientSession() as session:
            admin = await session.ws_connect(base + "/ws/admin")
            device_id, device = await _registered(
                session, base, ["push_job_id_v1", "push_resume_v1"],
                push_state={
                    "status": "available",
                    "reset": {"reason": "corrupt_state_discarded", "detail": "bad\njson"},
                },
            )

            async def reset_notice() -> dict:
                while True:
                    message = await admin.receive()
                    if message.type is aiohttp.WSMsgType.TEXT:
                        payload = json.loads(message.data)
                        if payload.get("type") == "PUSH_STATE_RESET":
                            return payload

            notice = await asyncio.wait_for(reset_notice(), timeout=2)
            assert notice == {
                "type": "PUSH_STATE_RESET",
                "device_id": device_id,
                "reason": "corrupt_state_discarded",
                "detail": "bad json",
            }
            await device.close()
            await admin.close()
    finally:
        await test_server.close()


@pytest.mark.asyncio
async def test_register_on_same_socket_refreshes_push_state_without_disconnect():
    test_server = TestServer(server.create_app())
    await test_server.start_server()
    base = f"http://{test_server.host}:{test_server.port}"
    try:
        async with aiohttp.ClientSession() as session:
            device_id, device = await _registered(
                session, base, [], push_state={"status": "unavailable"},
            )
            runtime = test_server.app["push_runtime"]
            live = runtime.sessions[device_id]
            assert live.push_state_available is False
            await device.send_json({
                "type": "REGISTER",
                "identity_scheme": server.IDENTITY_SCHEME,
                "device_id": device_id,
                "model": "M",
                "ip": "1.1.1.2",
                "version_code": 10,
                "version_name": "guid",
                "capabilities": ["push_job_id_v1", "push_resume_v1"],
                "process_instance_id": live.process_instance_id,
                "push_state": {"status": "available"},
                "push_runtime": {"active": None},
            })

            async def registered() -> dict:
                while True:
                    message = await device.receive()
                    if message.type is aiohttp.WSMsgType.TEXT:
                        payload = json.loads(message.data)
                        if payload.get("type") == "REGISTERED":
                            return payload

            reply = await asyncio.wait_for(registered(), timeout=2)
            # The same live session is refreshed in place; no disconnect settlement.
            assert reply["session_id"] == live.session_id
            assert runtime.sessions[device_id] is live
            assert live.push_state_available is True
            assert server.devices[device_id]["push_state_status"] == "available"
            async with session.post(base + "/api/push-jobs", json=_request(device_id)) as response:
                assert response.status == 201
            await device.close()
    finally:
        await test_server.close()
