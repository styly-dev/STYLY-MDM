"""Admin WebSocket transport tests."""

import asyncio
import json

import aiohttp
from aiohttp.test_utils import TestServer

from styly_mdm import server


def test_admin_websocket_does_not_negotiate_compression(tmp_path):
    """The browser control channel stays uncompressed while device behavior is unchanged."""

    async def body():
        server._apply_data_dir(str(tmp_path))
        server.admin_connections.clear()
        test_server = TestServer(server.create_app())
        await test_server.start_server()
        base = f"http://{test_server.host}:{test_server.port}"

        try:
            async with aiohttp.ClientSession() as session:
                # Compression remains available on the device channel; the
                # workaround is deliberately scoped to browser admin traffic.
                device = await session.ws_connect(base + "/ws/device", compress=15)
                assert device.compress == 15
                await device.send_json({
                    "type": "REGISTER",
                    "device_id": "test-device",
                    "model": "test",
                    "ip": "127.0.0.1",
                })

                async def wait_registered():
                    while "test-device" not in server.devices:
                        await asyncio.sleep(0)

                await asyncio.wait_for(wait_registered(), timeout=2)

                admin = await session.ws_connect(base + "/ws/admin", compress=15)
                assert admin.compress == 0

                # Drain the four snapshots sent when an admin connects.
                for _ in range(4):
                    msg = await admin.receive(timeout=2)
                    assert msg.type == aiohttp.WSMsgType.TEXT

                # Exercise the same browser-to-server command that followed a
                # multi-gigabyte upload in the reported failure.
                await admin.send_json({
                    "type": "PUSH_FILES",
                    "target_devices": ["test-device"],
                    "bundle_url": base + "/bundles/test.zip",
                    "bundle_filename": "test.zip",
                    "dest_path": "/sdcard/test",
                    "delete_extras": False,
                })
                ack = await admin.receive(timeout=2)
                assert ack.type == aiohttp.WSMsgType.TEXT
                assert json.loads(ack.data)["type"] == "PUSH_FILES_SENT"

                command = await device.receive(timeout=2)
                assert command.type == aiohttp.WSMsgType.TEXT
                assert json.loads(command.data)["type"] == "EXECUTE_PUSH_FILES"
                await device.send_json({
                    "type": "DOWNLOAD_COMPLETE",
                    "task": "push",
                    "dest_path": "/sdcard/test",
                })

                while True:
                    progress = await admin.receive(timeout=2)
                    assert progress.type == aiohttp.WSMsgType.TEXT
                    payload = json.loads(progress.data)
                    if payload.get("type") == "PUSH_PROGRESS" and payload.get("done"):
                        break

                await device.close()
                await admin.close()
        finally:
            await test_server.close()
            server.admin_connections.clear()
            server.devices.clear()

    asyncio.run(body())
