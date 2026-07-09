"""Tests for the push-files (file/folder full-mirror sync) feature.

Covers the pure path/bundle helpers, the destination-safety guard, the bundle upload
endpoint (multipart tree -> zip), and the PUSH_FILES command fan-out. No real network is
used: async handlers run via asyncio.run with in-memory fakes / the aiohttp test client.
"""

import asyncio
import json
import zipfile

import aiohttp
from aiohttp.test_utils import TestClient, TestServer
import pytest

import styly_mdm
from styly_mdm import server


# ---------------------------------------------------------------------------
# Pure helpers
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "raw, expected",
    [
        ("a.txt", "a.txt"),
        ("folder/sub/a.txt", "folder/sub/a.txt"),
        ("folder\\sub\\a.txt", "folder/sub/a.txt"),   # backslashes normalized
        ("./a/./b.txt", "a/b.txt"),                    # '.' segments dropped
        ("/abs/path.txt", "abs/path.txt"),             # leading slash -> relative
        ("../secret", None),                           # parent traversal rejected
        ("a/../../b", None),
        ("", None),
        ("   ", None),
        (None, None),
    ],
)
def test_sanitize_relpath(raw, expected):
    assert server.sanitize_relpath(raw) == expected


@pytest.mark.parametrize(
    "dest",
    [
        "/sdcard/STYLY/content",
        "/storage/emulated/0/STYLY/content",
        "/sdcard/a",
        "/sdcard/STYLY/content/",       # trailing slash tolerated
    ],
)
def test_validate_dest_path_accepts_safe(dest):
    assert server.validate_dest_path(dest) is None


@pytest.mark.parametrize(
    "dest",
    [
        "", "   ", None,
        "relative/path",                 # not absolute
        "/sdcard",                       # storage root, no subdir
        "/sdcard/",                      # storage root
        "/storage/emulated/0",           # storage root
        "/sdcard/Download",              # protected top-level dir
        "/sdcard/download/x",            # protected (case-insensitive)
        "/sdcard/Android/data/x",        # protected
        "/sdcard/DCIM/x",                # protected
        "/sdcard/STYLY/../content",      # contains '..'
        "/etc/passwd",                   # outside shared storage
    ],
)
def test_validate_dest_path_rejects_unsafe(dest):
    assert server.validate_dest_path(dest) is not None


def test_bundle_basename():
    assert server.bundle_basename(["config.txt"]) == "config"
    assert server.bundle_basename(["myfolder/a.txt", "myfolder/b.txt"]) == "myfolder"
    assert server.bundle_basename(["x/a.txt", "y/b.txt"]) == "bundle"
    assert server.bundle_basename([]) == "bundle"


def test_unique_bundle_path_avoids_collision(tmp_path):
    server._apply_data_dir(str(tmp_path))
    server.BUNDLE_DIR.mkdir(parents=True, exist_ok=True)

    first = server.unique_bundle_path("content.zip")
    assert first == server.BUNDLE_DIR / "content.zip"

    first.write_bytes(b"x")  # occupy the name
    second = server.unique_bundle_path("content.zip")
    assert second != first
    assert second.name.startswith("content-")
    assert second.suffix == ".zip"


def test_zip_tree_stores_forward_slash_relative_names(tmp_path):
    root = tmp_path / "src"
    (root / "sub").mkdir(parents=True)
    (root / "a.txt").write_bytes(b"AAA")
    (root / "sub" / "b.txt").write_bytes(b"BBB")
    dest_zip = tmp_path / "out.zip"

    server.zip_tree(root, dest_zip)

    with zipfile.ZipFile(dest_zip) as z:
        assert set(z.namelist()) == {"a.txt", "sub/b.txt"}
        assert z.read("sub/b.txt") == b"BBB"


# ---------------------------------------------------------------------------
# Route registration
# ---------------------------------------------------------------------------

def test_create_app_registers_bundle_routes(tmp_path):
    server._apply_data_dir(str(tmp_path))
    app = styly_mdm.create_app()
    method_paths = {(route.method, route.resource.canonical) for route in app.router.routes()}
    canonicals = {resource.canonical for resource in app.router.resources()}
    assert ("POST", "/api/bundles") in method_paths
    assert any(c.startswith("/bundles") for c in canonicals)
    assert (tmp_path / "bundles").is_dir()


# ---------------------------------------------------------------------------
# Bundle upload endpoint (multipart tree -> zip)
# ---------------------------------------------------------------------------

async def _upload(app, parts):
    """POST parts (list of (relpath, bytes)) to /api/bundles; return (status, json)."""
    async with TestClient(TestServer(app)) as client:
        data = aiohttp.FormData()
        for relpath, content in parts:
            data.add_field("files", content, filename=relpath,
                           content_type="application/octet-stream")
        resp = await client.post("/api/bundles", data=data)
        return resp.status, await resp.json()


def test_strip_common_root():
    # Folder pick: shared top dir stripped so the bundle mirrors its contents.
    assert server.strip_common_root(["myfolder/a.txt", "myfolder/sub/b.txt"]) == (
        "myfolder", ["a.txt", "sub/b.txt"])
    # A bare top-level file among the entries -> no shared root, unchanged.
    assert server.strip_common_root(["a.txt", "b.txt"]) == (None, ["a.txt", "b.txt"])
    assert server.strip_common_root(["config.json"]) == (None, ["config.json"])
    # Two distinct roots -> unchanged.
    assert server.strip_common_root(["x/a.txt", "y/b.txt"]) == (None, ["x/a.txt", "y/b.txt"])


def test_upload_bundle_folder_pick_mirrors_contents(tmp_path):
    # A folder pick nests files under the folder name; the bundle must contain the
    # folder's *contents* (root stripped) so it mirrors into the destination directly,
    # while the bundle is still named after the picked folder.
    server._apply_data_dir(str(tmp_path))
    app = styly_mdm.create_app()

    status, body = asyncio.run(_upload(app, [
        ("myfolder/a.txt", b"AAA"),
        ("myfolder/sub/b.txt", b"BBBBB"),
    ]))

    assert status == 200
    assert body["entry_count"] == 2
    assert body["bundle_filename"].startswith("myfolder")
    zip_path = server.BUNDLE_DIR / body["bundle_filename"]
    assert zip_path.is_file()
    with zipfile.ZipFile(zip_path) as z:
        assert set(z.namelist()) == {"a.txt", "sub/b.txt"}  # root stripped
    # The transient staging directory must be cleaned up, leaving only the zip.
    assert [p.name for p in server.BUNDLE_DIR.iterdir()] == [body["bundle_filename"]]


def test_upload_bundle_multiple_files_keep_flat_names(tmp_path):
    # Multiple individually-picked files share no folder root -> archived as-is.
    server._apply_data_dir(str(tmp_path))
    app = styly_mdm.create_app()
    status, body = asyncio.run(_upload(app, [("a.txt", b"A"), ("b.txt", b"B")]))
    assert status == 200
    assert body["entry_count"] == 2
    with zipfile.ZipFile(server.BUNDLE_DIR / body["bundle_filename"]) as z:
        assert set(z.namelist()) == {"a.txt", "b.txt"}


def test_upload_bundle_single_file(tmp_path):
    server._apply_data_dir(str(tmp_path))
    app = styly_mdm.create_app()
    status, body = asyncio.run(_upload(app, [("config.json", b"{}")]))
    assert status == 200
    assert body["entry_count"] == 1
    assert body["bundle_filename"].startswith("config")


def test_upload_bundle_rejects_traversal_filename(tmp_path):
    server._apply_data_dir(str(tmp_path))
    app = styly_mdm.create_app()
    status, _ = asyncio.run(_upload(app, [("../evil.txt", b"x")]))
    assert status == 400


def test_upload_bundle_requires_files(tmp_path):
    server._apply_data_dir(str(tmp_path))
    app = styly_mdm.create_app()

    async def _empty():
        async with TestClient(TestServer(app)) as client:
            resp = await client.post("/api/bundles", data=aiohttp.FormData())
            return resp.status

    assert asyncio.run(_empty()) == 400


# ---------------------------------------------------------------------------
# PUSH_FILES command fan-out
# ---------------------------------------------------------------------------

class FakeWS:
    """Captures messages sent to it as parsed dicts."""

    def __init__(self):
        self.sent = []

    async def send_str(self, text):
        self.sent.append(json.loads(text))


def _register_online_device(did):
    ws = FakeWS()
    server.devices[did] = {
        "ws": ws, "device_id": did, "model": "PICO 4E",
        "ip": "192.168.1.10", "status": "online", "startup_app": None, "battery": None,
    }
    return ws


def test_handle_push_files_fans_out_to_online_devices(tmp_path):
    server._apply_data_dir(str(tmp_path))
    server.devices.clear()
    dev_ws = _register_online_device("dev-1")
    admin = FakeWS()

    asyncio.run(server.handle_push_files(admin, {
        "target_devices": ["*"],
        "bundle_url": "http://host/bundles/b.zip",
        "bundle_filename": "b.zip",
        "dest_path": "/sdcard/STYLY/content",
    }))

    assert dev_ws.sent[0]["type"] == "EXECUTE_PUSH_FILES"
    assert dev_ws.sent[0]["bundle_url"] == "http://host/bundles/b.zip"
    assert dev_ws.sent[0]["dest_path"] == "/sdcard/STYLY/content"

    sent_ack = admin.sent[-1]
    assert sent_ack["type"] == "PUSH_FILES_SENT"
    assert sent_ack["sent_count"] == 1
    assert sent_ack["target_count"] == 1
    assert sent_ack["dest_path"] == "/sdcard/STYLY/content"


@pytest.mark.parametrize(
    "sent_value, expected",
    [
        # A push (copy) and an explicit sync (mirror) round-trip their flag verbatim.
        (False, False),
        (True, True),
        # Everything else must decode to "do not delete". An omitted field is the case that
        # matters most: a console that predates the flag must never trigger a mirror.
        (None, False),
        ("true", False),
        (1, False),
    ],
)
def test_handle_push_files_only_a_literal_true_requests_deletion(tmp_path, sent_value, expected):
    server._apply_data_dir(str(tmp_path))
    server.devices.clear()
    dev_ws = _register_online_device("dev-1")
    admin = FakeWS()

    data = {
        "target_devices": ["*"],
        "bundle_url": "http://host/bundles/b.zip",
        "bundle_filename": "b.zip",
        "dest_path": "/sdcard/STYLY/content",
    }
    if sent_value is not None:
        data["delete_extras"] = sent_value

    asyncio.run(server.handle_push_files(admin, data))

    assert dev_ws.sent[0]["delete_extras"] is expected
    assert admin.sent[-1]["delete_extras"] is expected


def test_handle_push_files_rejects_unsafe_dest(tmp_path):
    server._apply_data_dir(str(tmp_path))
    server.devices.clear()
    dev_ws = _register_online_device("dev-1")
    admin = FakeWS()

    asyncio.run(server.handle_push_files(admin, {
        "target_devices": ["*"],
        "bundle_url": "http://host/bundles/b.zip",
        "bundle_filename": "b.zip",
        "dest_path": "/sdcard/Download",   # protected -> must be rejected
    }))

    assert admin.sent[-1]["type"] == "ERROR"
    assert dev_ws.sent == []  # nothing dispatched to the device


def test_handle_push_files_requires_bundle_url(tmp_path):
    server._apply_data_dir(str(tmp_path))
    server.devices.clear()
    admin = FakeWS()
    asyncio.run(server.handle_push_files(admin, {
        "target_devices": ["*"], "dest_path": "/sdcard/STYLY/content",
    }))
    assert admin.sent[-1]["type"] == "ERROR"
