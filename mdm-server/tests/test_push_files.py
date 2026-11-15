"""Tests for Push job packaging safety and the retired pre-job API boundary."""

import json
import uuid
import zipfile

import aiohttp
from aiohttp.test_utils import TestClient, TestServer
import pytest

import styly_mdm
from styly_mdm import server


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("a.txt", "a.txt"),
        ("folder/sub/a.txt", "folder/sub/a.txt"),
        ("folder\\sub\\a.txt", "folder/sub/a.txt"),
        ("./a/./b.txt", "a/b.txt"),
        ("/abs/path.txt", "abs/path.txt"),
        ("../secret", None),
        ("a/../../b", None),
        ("", None),
        ("   ", None),
        (None, None),
    ],
)
def test_sanitize_relpath(raw, expected):
    assert server.sanitize_relpath(raw) == expected


def test_strip_common_root():
    assert server.strip_common_root(["myfolder/a.txt", "myfolder/sub/b.txt"]) == (
        "myfolder", ["a.txt", "sub/b.txt"]
    )
    assert server.strip_common_root(["a.txt", "b.txt"]) == (None, ["a.txt", "b.txt"])
    assert server.strip_common_root(["config.json"]) == (None, ["config.json"])
    assert server.strip_common_root(["x/a.txt", "y/b.txt"]) == (None, ["x/a.txt", "y/b.txt"])


def test_zip_tree_stores_forward_slash_relative_names(tmp_path):
    root = tmp_path / "src"
    (root / "sub").mkdir(parents=True)
    (root / "a.txt").write_bytes(b"AAA")
    (root / "sub" / "b.txt").write_bytes(b"BBB")
    dest_zip = tmp_path / "out.zip"

    server.zip_tree(root, dest_zip)

    with zipfile.ZipFile(dest_zip) as archive:
        assert set(archive.namelist()) == {"a.txt", "sub/b.txt"}
        assert archive.read("sub/b.txt") == b"BBB"


@pytest.mark.asyncio
async def test_retired_bundle_api_fails_fast_and_does_not_serve_existing_files(tmp_path):
    server._apply_data_dir(str(tmp_path))
    old_bundle_dir = server.BUNDLE_DIR
    old_bundle_dir.mkdir(parents=True)
    old_bundle = old_bundle_dir / "existing.zip"
    old_bundle.write_bytes(b"preserve")
    app = styly_mdm.create_app()

    methods = {(route.method, route.resource.canonical) for route in app.router.routes()}
    assert ("POST", "/api/bundles") in methods
    assert ("POST", "/api/push-jobs/{job_id}/upload") in methods
    assert not any(resource.canonical.startswith("/bundles") for resource in app.router.resources())

    async with TestClient(TestServer(app)) as client:
        form = aiohttp.FormData()
        form.add_field("files", b"payload", filename="payload.txt")
        response = await client.post("/api/bundles", data=form)
        assert response.status == 410
        body = await response.json()
        assert "/api/push-jobs" in body["error"]

        response = await client.get("/bundles/existing.zip")
        assert response.status == 404

    assert old_bundle.read_bytes() == b"preserve"


async def _registered_push_device(session, base):
    device_id = str(uuid.uuid4())
    device = await session.ws_connect(base + "/ws/device")
    await device.send_json({
        "type": "REGISTER",
        "identity_scheme": server.IDENTITY_SCHEME,
        "device_id": device_id,
        "model": "PICO 4 Ultra Enterprise",
        "ip": "127.0.0.1",
        "version_code": 11,
        "version_name": "test",
        "capabilities": ["push_job_id_v1", "push_resume_v1"],
        "process_instance_id": str(uuid.uuid4()),
        "push_state": {"status": "available"},
        "push_runtime": {"active": None},
    })
    while True:
        message = await device.receive()
        if message.type == aiohttp.WSMsgType.TEXT and json.loads(message.data).get(
            "type"
        ) == "REGISTERED":
            return device_id, device


async def _create_job(session, base, device_id):
    response = await session.post(base + "/api/push-jobs", json={
        "client_request_id": str(uuid.uuid4()),
        "target_devices": [device_id],
        "mode": "push",
        "dest_path": "/sdcard/STYLY/content",
        "source": {
            "display_name": "myfolder",
            "declared_file_count": 4,
            "declared_total_bytes": 64,
        },
    })
    assert response.status == 201
    return await response.json()


@pytest.mark.asyncio
async def test_job_upload_packages_folder_without_os_metadata(tmp_path):
    server._apply_data_dir(str(tmp_path))
    async with TestClient(TestServer(styly_mdm.create_app())) as client:
        base = str(client.make_url("/"))[:-1]
        device_id, device = await _registered_push_device(client.session, base)
        try:
            job = await _create_job(client.session, base, device_id)
            # Browser FormData sends the filename path literally. Disabling aiohttp's
            # default quote_fields behavior keeps this test transport-equivalent.
            form = aiohttp.FormData(quote_fields=False)
            form.add_field("files", b"AAA", filename="myfolder/a.txt")
            form.add_field("files", b"junk", filename="myfolder/.DS_Store")
            form.add_field("files", b"literal", filename="myfolder/percent%2Fname.txt")
            form.add_field("files", b"BBBBB", filename="myfolder/sub/b.txt")
            response = await client.session.post(base + job["upload_url"], data=form)

            assert response.status == 200
            snapshot = await response.json()
            assert snapshot["state"] == "ready"
            assert snapshot["actual_file_count"] == 3
            assert snapshot["actual_total_bytes"] == 15
            runtime = client.server.app["push_runtime"]
            artifact = await runtime.store.artifact_record(snapshot["artifact"]["artifact_id"])
            archive_path = runtime.artifacts.path_for_record(artifact)
            with zipfile.ZipFile(archive_path) as archive:
                assert archive.namelist() == ["a.txt", "percent%2Fname.txt", "sub/b.txt"]
                assert archive.read("percent%2Fname.txt") == b"literal"
        finally:
            await device.close()


@pytest.mark.asyncio
async def test_job_upload_rejects_traversal_path(tmp_path):
    server._apply_data_dir(str(tmp_path))
    async with TestClient(TestServer(styly_mdm.create_app())) as client:
        base = str(client.make_url("/"))[:-1]
        device_id, device = await _registered_push_device(client.session, base)
        try:
            job = await _create_job(client.session, base, device_id)
            form = aiohttp.FormData(quote_fields=False)
            form.add_field("files", b"bad", filename="../outside.txt")
            response = await client.session.post(base + job["upload_url"], data=form)
            assert response.status == 422
            assert "unsafe uploaded path" in (await response.json())["error"]
            assert not (tmp_path / "outside.txt").exists()
        finally:
            await device.close()


@pytest.mark.asyncio
async def test_identityless_push_command_returns_job_migration_error():
    class Admin:
        def __init__(self):
            self.sent = []

        async def send_str(self, message):
            self.sent.append(json.loads(message))

    admin = Admin()
    await server.handle_push_files(admin, {
        "target_devices": ["D1"],
        "bundle_url": "http://old-server/bundles/content.zip",
        "dest_path": "/sdcard/STYLY/content",
    })

    assert admin.sent == [{
        "type": "ERROR",
        "message": (
            "Push/Sync requires a job_id. Create a job through /api/push-jobs, "
            "upload to its upload_url, then send PUSH_FILES with that job_id."
        ),
    }]
