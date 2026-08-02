"""Admin WebSocket transport tests."""

import asyncio

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

                admin = await session.ws_connect(base + "/ws/admin", compress=15)
                assert admin.compress == 0

                await admin.close()
                await device.close()
        finally:
            await test_server.close()
            server.admin_connections.clear()
            server.devices.clear()

    asyncio.run(body())
