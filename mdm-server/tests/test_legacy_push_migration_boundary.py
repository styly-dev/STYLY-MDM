"""Integration evidence for legacy Push jobs during GUID re-registration."""

from __future__ import annotations

import asyncio
import json
import uuid

import aiohttp
import pytest
from aiohttp.test_utils import TestServer

from styly_mdm import server
from styly_mdm.push_jobs import ProtocolMode, canonicalize_create_request


async def _recv_type(ws: aiohttp.ClientWebSocketResponse, expected: str) -> dict:
    async def receive() -> dict:
        while True:
            message = await ws.receive()
            if message.type is not aiohttp.WSMsgType.TEXT:
                continue
            payload = json.loads(message.data)
            if payload.get("type") == expected:
                return payload

    return await asyncio.wait_for(receive(), timeout=2)


def _request(target: str) -> object:
    return canonicalize_create_request(
        {
            "client_request_id": str(uuid.uuid4()),
            "target_devices": [target],
            "mode": "push",
            "dest_path": "/sdcard/STYLY/legacy-boundary",
            "source": {
                "display_name": "legacy-boundary",
                "declared_file_count": 1,
                "declared_total_bytes": 1,
            },
        }
    )


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


@pytest.mark.asyncio
async def test_unfinished_legacy_push_is_not_migrated_on_guid_registration(tmp_path):
    test_server = TestServer(server.create_app())
    await test_server.start_server()
    base = f"http://{test_server.host}:{test_server.port}"
    legacy_id = "SERIAL-BOUNDARY-1"
    guid_id = str(uuid.uuid4())
    try:
        runtime = test_server.app["push_runtime"]

        # Seed the state a pre-GUID server could have persisted: a ready, dispatch-
        # enabled Push job owned by the old serial identity.
        server.device_registry[legacy_id] = {
            "identity_kind": "legacy",
            "label": "old serial",
            "model": "M",
            "ip": "1.1.1.1",
        }
        _, legacy_snapshot = await runtime.manager.create_job(
            _request(legacy_id),
            {legacy_id: (ProtocolMode.LEGACY, set())},
            600_000,
        )
        legacy_job_id = legacy_snapshot["job_id"]
        await runtime.store.start_upload(legacy_job_id)
        await runtime.store.mark_packaging(legacy_job_id, 1, 1)
        await runtime.store.publish_artifact(
            legacy_job_id,
            {
                "artifact_id": str(uuid.uuid4()),
                "storage_name": "legacy-boundary.zip",
                "display_filename": "legacy-boundary.zip",
                "byte_size": 1,
                "sha256": "a" * 64,
                "entry_count": 1,
            },
        )
        await runtime.manager.enable_dispatch(legacy_job_id)
        legacy_before = await runtime.manager.get_snapshot(legacy_job_id)
        assert legacy_before["state"] == "running"
        assert legacy_before["devices"][legacy_id]["protocol_mode"] == "legacy"

        async with aiohttp.ClientSession() as session:
            device = await session.ws_connect(base + "/ws/device")
            await device.send_json(
                {
                    "type": "REGISTER",
                    "identity_scheme": server.IDENTITY_SCHEME,
                    "device_id": guid_id,
                    "model": "M",
                    "ip": "1.1.1.2",
                    "version_code": 10,
                    "version_name": "guid",
                    "capabilities": ["push_job_id_v1"],
                    "process_instance_id": str(uuid.uuid4()),
                }
            )
            await _recv_type(device, "REGISTERED")

            legacy_after_register = await runtime.manager.get_snapshot(legacy_job_id)
            assert legacy_after_register["state"] == legacy_before["state"]
            assert legacy_after_register["devices"] == legacy_before["devices"]
            assert legacy_after_register["job_id"] == legacy_job_id
            assert legacy_after_register["devices"].keys() == {legacy_id}

            # A new GUID-targeted job remains independently creatable through the
            # public API after the canonical registration.
            new_request = {
                "client_request_id": str(uuid.uuid4()),
                "target_devices": [guid_id],
                "mode": "push",
                "dest_path": "/sdcard/STYLY/guid-follow-up",
                "source": {
                    "display_name": "guid-follow-up",
                    "declared_file_count": 1,
                    "declared_total_bytes": 1,
                },
            }
            async with session.post(base + "/api/push-jobs", json=new_request) as response:
                assert response.status == 201
                created = await response.json()

            assert created["targets"] == [
                {"device_id": guid_id, "protocol_mode": "job_v1"}
            ]
            new_snapshot = await runtime.manager.get_snapshot(created["job_id"])
            assert new_snapshot["devices"].keys() == {guid_id}
            assert (await runtime.manager.get_snapshot(legacy_job_id))["state"] != "succeeded"
            await device.close()
    finally:
        await test_server.close()
