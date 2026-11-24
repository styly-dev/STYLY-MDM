import asyncio
import hashlib
import io
import json
import time
from types import SimpleNamespace
import zipfile

import aiohttp
import pytest
from aiohttp import web
from aiohttp.test_utils import TestServer

from styly_mdm import file_transfer, server
from styly_mdm.lbe_maps import LbeMapStore
from styly_mdm.push_transfer_leases import TRANSFER_IDLE_SECONDS


def _download_fixture(tmp_path, monkeypatch):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("mapinfo.json", b"{}")
    payload = buffer.getvalue()
    digest = hashlib.sha256(payload).hexdigest()
    reference = {"sha256": digest, "name": "map.zip", "size": len(payload)}
    store = LbeMapStore(tmp_path / "lbe-maps")
    directory = store.root / digest
    directory.mkdir()
    (directory / "map.zip").write_bytes(payload)
    (directory / "metadata.json").write_text(json.dumps(reference), encoding="utf-8")
    policy = {"revision": "revision-1", "settings": {"lbeMap": reference}}
    monkeypatch.setattr(server, "_lbe_map_store", lambda: store)
    monkeypatch.setattr(server, "_desired_configuration", lambda _device_id: policy)
    monkeypatch.setattr(server, "LBE_MAP_TRANSFER_QUEUE_TIMEOUT_SECONDS", 0.02)
    token = server._lbe_map_token("device-1", "revision-1", digest, int(time.time()) + 60)
    url = f"/api/lbe-maps/{digest}/download?device_id=device-1&revision=revision-1"
    return store, policy, reference, payload, token, url


async def _serve_lbe(handler=server.lbe_map_download_handler):
    app = web.Application()
    app.router.add_get(r"/api/lbe-maps/{sha256:[0-9a-f]{64}}/download", handler)
    test_server = TestServer(app)
    await test_server.start_server()
    return test_server


@pytest.mark.asyncio
async def test_lbe_get_rechecks_current_assignment_after_waiting_for_a_slot(tmp_path, monkeypatch):
    store, policy, _reference, _payload, token, path = _download_fixture(tmp_path, monkeypatch)
    path_for_calls = []
    original_path_for = store.path_for

    def count_path_for(reference):
        path_for_calls.append(reference)
        return original_path_for(reference)

    monkeypatch.setattr(store, "path_for", count_path_for)

    class WaitingSemaphore:
        def __init__(self):
            self.inner = asyncio.Semaphore(0)
            self.waiting = asyncio.Event()

        async def acquire(self):
            self.waiting.set()
            return await self.inner.acquire()

        def release(self):
            self.inner.release()

    slots = WaitingSemaphore()
    monkeypatch.setattr(server, "transfer_slots", lambda: slots)
    test_server = await _serve_lbe()
    async with test_server:
        async with aiohttp.ClientSession() as client:
            request = asyncio.create_task(client.get(
                f"http://{test_server.host}:{test_server.port}{path}",
                headers={"Authorization": f"Bearer {token}"},
            ))
            await asyncio.wait_for(slots.waiting.wait(), timeout=1)
            assert path_for_calls == []
            policy["revision"] = "revision-2"
            slots.release()
            response = await request
            assert response.status == 403
            await response.read()
            assert path_for_calls == []
    assert slots.inner._value == 1


@pytest.mark.asyncio
async def test_lbe_queue_timeout_returns_retryable_503_without_consuming_a_slot(tmp_path, monkeypatch):
    _store, _policy, _reference, _payload, token, path = _download_fixture(tmp_path, monkeypatch)
    slots = asyncio.Semaphore(0)
    monkeypatch.setattr(server, "transfer_slots", lambda: slots)

    test_server = await _serve_lbe()
    async with test_server:
        async with aiohttp.ClientSession() as client:
            async with client.get(
                f"http://{test_server.host}:{test_server.port}{path}",
                headers={"Authorization": f"Bearer {token}"},
            ) as response:
                assert response.status == 503
                assert response.headers["X-MDM-Transfer-Status"] == "queued"
                assert response.headers["Retry-After"] == "1"
                await response.read()

    assert slots._value == 0
    slots.release()
    assert await asyncio.wait_for(slots.acquire(), timeout=0.1)


@pytest.mark.asyncio
async def test_lbe_head_uses_the_shared_authorized_transfer_response(tmp_path, monkeypatch):
    _store, _policy, reference, payload, token, path = _download_fixture(tmp_path, monkeypatch)
    slots = asyncio.Semaphore(1)
    monkeypatch.setattr(server, "transfer_slots", lambda: slots)

    test_server = await _serve_lbe()
    async with test_server:
        async with aiohttp.ClientSession() as client:
            async with client.head(
                f"http://{test_server.host}:{test_server.port}{path}",
                headers={"Authorization": f"Bearer {token}"},
            ) as response:
                assert response.status == 200
                assert response.headers["ETag"] == f'"{reference["sha256"]}"'
                assert response.headers["Content-Length"] == str(len(payload))
                assert await response.read() == b""

    assert slots._value == 1


@pytest.mark.asyncio
async def test_lbe_get_holds_its_slot_until_streaming_finishes(tmp_path, monkeypatch):
    _store, _policy, reference, _payload, token, _path = _download_fixture(tmp_path, monkeypatch)
    slots = asyncio.Semaphore(1)
    monkeypatch.setattr(server, "transfer_slots", lambda: slots)
    streaming = asyncio.Event()
    finish_stream = asyncio.Event()

    async def blocked_stream(*_args, **_kwargs):
        streaming.set()
        await finish_stream.wait()
        return web.Response(body=b"done")

    monkeypatch.setattr(server, "stream_file", blocked_stream)
    request = SimpleNamespace(
        query={"device_id": "device-1", "revision": "revision-1"},
        match_info={"sha256": reference["sha256"]},
        headers={"Authorization": f"Bearer {token}"},
        method="GET",
    )
    transfer = asyncio.create_task(server.lbe_map_download_handler(request))
    await asyncio.wait_for(streaming.wait(), timeout=0.1)

    assert slots.locked()
    finish_stream.set()
    await transfer
    assert slots._value == 1


@pytest.mark.asyncio
async def test_lbe_releases_slot_if_stream_fails(tmp_path, monkeypatch):
    _store, _policy, reference, _payload, token, path = _download_fixture(tmp_path, monkeypatch)
    slots = asyncio.Semaphore(1)
    monkeypatch.setattr(server, "transfer_slots", lambda: slots)
    seen_timeout = []

    async def fail_stream(*_args, **kwargs):
        seen_timeout.append(kwargs["write_timeout_seconds"])
        raise asyncio.TimeoutError()

    monkeypatch.setattr(server, "stream_file", fail_stream)
    request = SimpleNamespace(
        query={"device_id": "device-1", "revision": "revision-1"},
        match_info={"sha256": reference["sha256"]},
        headers={"Authorization": f"Bearer {token}"},
        method="GET",
    )

    with pytest.raises(asyncio.TimeoutError):
        await server.lbe_map_download_handler(request)

    assert seen_timeout == [TRANSFER_IDLE_SECONDS]
    assert await asyncio.wait_for(slots.acquire(), timeout=0.1)


@pytest.mark.asyncio
async def test_shared_file_stream_times_out_stalled_writes_and_closes_transport(tmp_path, monkeypatch):
    path = tmp_path / "file.zip"
    path.write_bytes(b"stream")
    closed = []

    class SlowResponse:
        def __init__(self, **_kwargs):
            pass

        async def prepare(self, _request):
            pass

        async def write(self, _chunk):
            await asyncio.sleep(1)

        async def write_eof(self):
            pass

    class Transport:
        def close(self):
            closed.append(True)

    monkeypatch.setattr(file_transfer.web, "StreamResponse", SlowResponse)
    request = SimpleNamespace(headers={}, method="GET", transport=Transport())

    with pytest.raises(asyncio.TimeoutError):
        await file_transfer.stream_file(
            request,
            path,
            6,
            hashlib.sha256(b"stream").hexdigest(),
            cache_control="private, no-store",
            write_timeout_seconds=0.01,
        )

    assert closed == [True]
