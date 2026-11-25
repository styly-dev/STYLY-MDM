"""Tests for the APK upload endpoint (POST /api/apks)."""

import asyncio
from pathlib import Path

import aiohttp
from aiohttp.test_utils import TestServer

import styly_mdm
from styly_mdm import server
from server_test_helpers import reset_server_state  # noqa: F401  (autouse fixture)


def _emulate_windows_open_file_lock(monkeypatch):
    """Make Path.unlink fail on a still-open file, as Windows does (WinError 32)."""
    handles = {}
    real_open = Path.open
    real_unlink = Path.unlink

    def tracking_open(self, *args, **kwargs):
        handle = real_open(self, *args, **kwargs)
        handles[str(self)] = handle
        return handle

    def locking_unlink(self, missing_ok=False):
        handle = handles.get(str(self))
        if handle is not None and not handle.closed:
            raise PermissionError(32, "The process cannot access the file because "
                                      "it is being used by another process", str(self))
        return real_unlink(self, missing_ok=missing_ok)

    monkeypatch.setattr(Path, "open", tracking_open)
    monkeypatch.setattr(Path, "unlink", locking_unlink)


async def _upload(payload: bytes, filename: str = "app.apk"):
    test_server = TestServer(styly_mdm.create_app())
    await test_server.start_server()
    try:
        form = aiohttp.FormData()
        form.add_field("apk", payload, filename=filename,
                       content_type="application/vnd.android.package-archive")
        async with aiohttp.ClientSession() as session:
            async with session.post(test_server.make_url("/api/apks"), data=form) as resp:
                return resp.status, await resp.json()
    finally:
        await test_server.close()


def test_upload_stores_apk():
    status, body = asyncio.run(_upload(b"x" * 1024))

    assert status == 200
    assert body["apk_filename"] == "app.apk"
    assert body["size"] == 1024
    assert (server.APK_DIR / "app.apk").read_bytes() == b"x" * 1024


def test_oversized_upload_is_rejected_with_413_while_file_is_locked(monkeypatch):
    """Regression: on Windows the partial file was unlinked while still open,
    turning the size-limit rejection into a 500 PermissionError."""
    monkeypatch.setattr(server, "MAX_APK_SIZE", 1024)
    _emulate_windows_open_file_lock(monkeypatch)

    status, body = asyncio.run(_upload(b"x" * 4096))

    assert status == 413
    assert body["error"] == "APK exceeds 2 GiB limit"
    assert list(server.APK_DIR.glob("*.apk")) == []
