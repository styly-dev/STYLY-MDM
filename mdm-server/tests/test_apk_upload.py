"""Regression coverage for device download URLs returned by APK uploads."""

import asyncio
from unittest.mock import Mock

import aiohttp
from aiohttp import web
from aiohttp.test_utils import TestServer
import pytest

from styly_mdm import server


@pytest.fixture
def app(tmp_path, monkeypatch):
    """Keep upload storage and client-APK broadcasts isolated from other tests."""
    monkeypatch.setattr(server, "DATA_DIR", tmp_path)
    monkeypatch.setattr(server, "APK_DIR", tmp_path / "apks")
    monkeypatch.setattr(server, "BUNDLE_DIR", tmp_path / "bundles")
    monkeypatch.setattr(server, "REGISTRY_PATH", tmp_path / "device_registry.json")
    monkeypatch.setattr(server, "BUNDLED_CLIENT_DIR", tmp_path / "no-bundle")
    monkeypatch.setattr(server, "device_registry", {})
    monkeypatch.setattr(server, "device_groups", {})
    monkeypatch.setattr(server, "admin_connections", set())
    return server.create_app()


@pytest.mark.parametrize(
    "scheme, authority, addresses, expected_authority",
    [
        ("http", "localhost:7070", ["192.0.2.10"], "192.0.2.10:7070"),
        ("http", "LOCALHOST:7080", ["192.0.2.10"], "192.0.2.10:7080"),
        ("http", "localhost.:7070", ["192.0.2.10"], "192.0.2.10:7070"),
        ("http", "127.0.0.1:7070", ["192.0.2.10"], "192.0.2.10:7070"),
        ("http", "127.0.0.2:7070", ["192.0.2.10"], "192.0.2.10:7070"),
        ("http", "[::1]:7070", ["192.0.2.10"], "192.0.2.10:7070"),
        ("https", "localhost:7443", ["192.0.2.10"], "192.0.2.10:7443"),
        ("http", "localhost", ["192.0.2.10"], "192.0.2.10"),
        ("https", "localhost", ["192.0.2.10"], "192.0.2.10"),
        ("http", "192.0.2.20:7070", ["192.0.2.10"], "192.0.2.20:7070"),
        ("https", "mdm.example:7443", ["192.0.2.10"], "mdm.example:7443"),
        ("http", "[2001:db8::20]:7070", ["192.0.2.10"], "[2001:db8::20]:7070"),
        ("http", "localhost:7070", [], "localhost:7070"),
        ("http", "[::1]:7070", [], "[::1]:7070"),
        ("http", "localhost:7070", ["192.0.2.10", "198.51.100.10"], "localhost:7070"),
    ],
)
def test_upload_apk_download_url(app, monkeypatch, scheme, authority, addresses, expected_authority):
    """Only an unambiguous LAN address replaces a loopback upload authority."""
    lookup = Mock(return_value=addresses)
    monkeypatch.setattr(server, "get_local_ip_addresses", lookup)

    # Exercise HTTPS URL construction without requiring a TLS test certificate.
    @web.middleware
    async def effective_scheme(request, handler):
        return await handler(request.clone(scheme=scheme))

    app.middlewares.append(effective_scheme)

    async def body():
        form = aiohttp.FormData()
        form.add_field("apk", b"protocol-fixture", filename="test.apk")
        async with TestServer(app) as ts, aiohttp.ClientSession() as session:
            async with session.post(
                ts.make_url("/api/apks"), data=form, headers={"Host": authority}
            ) as response:
                assert response.status == 200
                result = await response.json()
            assert result == {
                "apk_filename": "test.apk",
                "apk_url": f"{scheme}://{expected_authority}/apks/test.apk",
                "size": len(b"protocol-fixture"),
            }
        if authority in {"192.0.2.20:7070", "mdm.example:7443", "[2001:db8::20]:7070"}:
            lookup.assert_not_called()

    asyncio.run(body())


def test_uploaded_apk_remains_downloadable(app, monkeypatch):
    """Changing the advertised host preserves the actual static download path."""
    monkeypatch.setattr(server, "get_local_ip_addresses", lambda: ["192.0.2.10"])

    async def body():
        # Protocol fixture only; this payload is not an installable APK.
        payload = b"uploaded-apk-fixture"
        form = aiohttp.FormData()
        form.add_field("apk", payload, filename="test.apk")
        async with TestServer(app) as ts, aiohttp.ClientSession() as session:
            async with session.post(ts.make_url("/api/apks"), data=form) as response:
                assert response.status == 200
                result = await response.json()
            download = ts.make_url("/apks/" + result["apk_filename"])
            assert result["apk_url"] == str(download.with_host("192.0.2.10"))
            # The advertised address is intentionally a documentation-only IP.
            async with session.get(download) as response:
                assert response.status == 200
                assert await response.read() == payload

    asyncio.run(body())
