import asyncio
import errno
import hashlib
import io
import os
import shutil
import threading
import time
import zipfile
import zlib
from pathlib import Path

from aiohttp import ClientSession, FormData, web
from aiohttp.test_utils import TestServer
import pytest

from styly_mdm import lbe_maps
from styly_mdm.lbe_maps import LbeMapError, LbeMapStore


def make_zip(files):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in files.items():
            archive.writestr(name, content)
    return output.getvalue()


async def upload(store, *, filename="venue-map.zip", data=None, fields=None, quote_fields=True, raw_body=None):
    app = web.Application(client_max_size=lbe_maps.MAX_LBE_MAP_BYTES + 1024 * 1024)

    async def handler(request):
        try:
            return web.json_response(await store.save_upload(request))
        except LbeMapError as exc:
            return web.json_response({"error": str(exc)}, status=exc.status)

    app.router.add_post("/upload", handler)
    headers = None
    if raw_body is None:
        form = FormData(quote_fields=quote_fields)
        if fields is not None:
            for name, value in fields:
                form.add_field(name, value, filename=f"{name}.zip", content_type="application/zip")
        else:
            form.add_field("map", data if data is not None else make_zip({"mapinfo.json": b"{}"}),
                           filename=filename, content_type="application/zip")
    else:
        form = raw_body
        headers = {"Content-Type": "multipart/form-data; boundary=outer"}

    async with TestServer(app) as server:
        async with ClientSession() as session:
            async with session.post(server.make_url("/upload"), data=form, headers=headers) as response:
                return response.status, await response.json()


def test_valid_map_reference_requires_exact_bounded_fields():
    reference = {
        "sha256": "a" * 64,
        "name": "venue-map.zip",
        "size": 10,
    }
    assert lbe_maps.valid_map_reference(reference)
    assert not lbe_maps.valid_map_reference({**reference, "extra": True})
    assert not lbe_maps.valid_map_reference({**reference, "name": "../map.zip"})
    assert not lbe_maps.valid_map_reference({**reference, "name": None})
    assert not lbe_maps.valid_map_reference({**reference, "size": True})
    assert not lbe_maps.valid_map_reference({**reference, "sha256": "A" * 64})


def test_list_maps_reports_root_storage_failure(tmp_path, monkeypatch):
    store = LbeMapStore(tmp_path / "maps")
    original_iterdir = Path.iterdir

    def fail_listing(path):
        if path == store.root:
            raise PermissionError("injected storage error")
        return original_iterdir(path)

    monkeypatch.setattr(Path, "iterdir", fail_listing)
    with pytest.raises(LbeMapError, match="Could not read map library") as error:
        store.list_maps()
    assert error.value.status == 500


def test_upload_is_registered_deduplicated_and_survives_store_restart(tmp_path):
    store = LbeMapStore(tmp_path / "maps")
    data = make_zip({"0.finchz": b"map-data", "mapinfo.json": b"{}"})

    status, reference = asyncio.run(upload(store, filename="hall.zip", data=data))
    assert status == 200
    assert reference == {
        "sha256": hashlib.sha256(data).hexdigest(),
        "name": "hall.zip",
        "size": len(data),
    }
    archive = store.path_for(reference)
    assert archive is not None
    assert archive.read_bytes() == data
    assert store.list_maps() == [reference]

    duplicate_status, duplicate = asyncio.run(upload(store, filename="renamed.zip", data=data))
    restarted = LbeMapStore(tmp_path / "maps")
    assert duplicate_status == 200
    assert duplicate == reference
    assert restarted.list_maps() == [reference]
    assert restarted.path_for(reference) == archive


def test_path_lookup_uses_content_identity_when_a_restored_library_renames_the_map(tmp_path):
    store = LbeMapStore(tmp_path / "maps")
    data = make_zip({"mapinfo.json": b"{}"})
    _status, reference = asyncio.run(upload(store, filename="before-restore.zip", data=data))

    restored_reference = {**reference, "name": "after-restore.zip"}

    assert store.path_for(restored_reference) == store.path_for(reference)


def test_valid_duplicate_skips_revalidating_the_registered_archive(tmp_path, monkeypatch):
    store = LbeMapStore(tmp_path / "maps")
    data = make_zip({"mapinfo.json": b"{}"})
    status, reference = asyncio.run(upload(store, data=data))
    assert status == 200

    def unexpected_validation(_archive):
        raise AssertionError("A valid registered ZIP does not need decompression again")

    monkeypatch.setattr(store, "_validate_zip", unexpected_validation)
    duplicate_status, duplicate = asyncio.run(upload(store, filename="renamed.zip", data=data))

    assert duplicate_status == 200
    assert duplicate == reference


def test_upload_repairs_same_size_corruption_from_valid_duplicate(tmp_path):
    store = LbeMapStore(tmp_path / "maps")
    data = make_zip({"mapinfo.json": b"{}", "map-data": b"venue"})
    status, reference = asyncio.run(upload(store, data=data))
    assert status == 200

    archive = store.path_for(reference)
    assert archive is not None
    damaged = bytearray(data)
    damaged[-1] ^= 0x01
    os.chmod(archive, 0o600)
    archive.write_bytes(damaged)
    os.chmod(archive.parent, 0o500)

    repaired_status, repaired_reference = asyncio.run(upload(store, data=data))

    assert repaired_status == 200
    assert repaired_reference == reference
    assert archive.read_bytes() == data
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == reference["sha256"]


def test_valid_duplicate_with_corrupt_bytes_fails_storage_verification(tmp_path, monkeypatch):
    store = LbeMapStore(tmp_path / "maps")
    data = make_zip({"mapinfo.json": b"{}"})
    status, reference = asyncio.run(upload(store, data=data))
    assert status == 200
    archive = store.path_for(reference)
    assert archive is not None

    def fail_hash(_archive, _reference):
        raise OSError("injected storage read failure")

    monkeypatch.setattr(store, "_archive_matches_reference", fail_hash)
    duplicate_status, error = asyncio.run(upload(store, data=data))

    assert duplicate_status == 500
    assert "publish" in error["error"].lower()


def test_upload_repairs_a_missing_archive_from_valid_bytes(tmp_path):
    store = LbeMapStore(tmp_path / "maps")
    data = make_zip({"mapinfo.json": b"{}"})
    status, reference = asyncio.run(upload(store, data=data))
    assert status == 200
    destination = store.root / reference["sha256"]
    archive = destination / "map.zip"

    os.chmod(destination, 0o700)
    archive.unlink()
    os.chmod(destination, 0o500)
    repaired_status, repaired_reference = asyncio.run(upload(store, data=data))

    assert repaired_status == 200
    assert repaired_reference == reference
    assert archive.read_bytes() == data


def test_upload_repairs_a_truncated_archive_atomically(tmp_path):
    store = LbeMapStore(tmp_path / "maps")
    data = make_zip({"mapinfo.json": b"{}"})
    status, reference = asyncio.run(upload(store, data=data))
    assert status == 200
    destination = store.root / reference["sha256"]
    archive = destination / "map.zip"

    os.chmod(destination, 0o700)
    os.chmod(archive, 0o600)
    archive.write_bytes(data[:8])
    os.chmod(destination, 0o500)
    repaired_status, repaired_reference = asyncio.run(upload(store, data=data))

    assert repaired_status == 200
    assert repaired_reference == reference
    assert archive.read_bytes() == data


def test_startup_restores_legacy_read_only_map_permissions(tmp_path):
    store = LbeMapStore(tmp_path / "maps")
    data = make_zip({"mapinfo.json": b"{}"})
    status, reference = asyncio.run(upload(store, data=data))
    assert status == 200
    destination = store.root / reference["sha256"]
    archive = destination / "map.zip"
    metadata = destination / "metadata.json"

    os.chmod(archive, 0o400)
    os.chmod(metadata, 0o400)
    os.chmod(destination, 0o500)

    restarted = LbeMapStore(store.root)
    restarted.restore_legacy_permissions()

    assert destination.stat().st_mode & 0o777 == 0o700
    assert archive.stat().st_mode & 0o777 == 0o600
    assert metadata.stat().st_mode & 0o777 == 0o600
    archive.write_bytes(b"manual backup restore")
    assert archive.read_bytes() == b"manual backup restore"


def test_invalid_stored_metadata_returns_an_actionable_repair_message(tmp_path):
    store = LbeMapStore(tmp_path / "maps")
    data = make_zip({"mapinfo.json": b"{}"})
    status, reference = asyncio.run(upload(store, data=data))
    assert status == 200
    destination = store.root / reference["sha256"]
    metadata = destination / "metadata.json"

    os.chmod(destination, 0o700)
    os.chmod(metadata, 0o600)
    metadata.write_text("{}", encoding="utf-8")
    os.chmod(metadata, 0o400)
    os.chmod(destination, 0o500)
    retry_status, error = asyncio.run(upload(store, data=data))

    assert retry_status == 500
    assert "server administrator" in error["error"]
    assert reference["sha256"] in error["error"]
    assert "remove map entry" in error["error"]


def test_upload_rejects_archive_traversal_and_duplicate_paths(tmp_path):
    store = LbeMapStore(tmp_path / "maps")

    traversal_status, _ = asyncio.run(upload(store, data=make_zip({"../outside.txt": b"x"})))
    duplicate_status, _ = asyncio.run(
        upload(store, data=make_zip({"Map.json": b"one", "map.JSON": b"two"}))
    )

    assert traversal_status == 400
    assert duplicate_status == 400
    assert not (tmp_path / "outside.txt").exists()
    assert store.list_maps() == []


def test_upload_rejects_bad_zip_and_raw_path_looking_filename(tmp_path):
    store = LbeMapStore(tmp_path / "maps")

    bad_zip_status, _ = asyncio.run(upload(store, data=b"not a zip"))
    unsafe_name_status, _ = asyncio.run(
        upload(store, filename="../venue-map.zip", quote_fields=False)
    )

    assert bad_zip_status == 400
    assert unsafe_name_status == 400
    assert store.list_maps() == []


def test_upload_keeps_literal_percent_encoded_filename_as_a_safe_display_name(tmp_path):
    store = LbeMapStore(tmp_path / "maps")

    status, reference = asyncio.run(upload(store, filename="map%2fzone.zip", quote_fields=False))

    assert status == 200
    assert reference["name"] == "map%2fzone.zip"
    assert store.path_for(reference) is not None


def test_upload_rejects_file_directory_conflict(tmp_path):
    store = LbeMapStore(tmp_path / "maps")
    data = make_zip({"folder": b"file", "folder/child.bin": b"child"})

    status, _ = asyncio.run(upload(store, data=data))

    assert status == 400
    assert store.list_maps() == []


def test_upload_rejects_empty_and_oversized_file(tmp_path, monkeypatch):
    store = LbeMapStore(tmp_path / "maps")
    monkeypatch.setattr(lbe_maps, "MAX_LBE_MAP_BYTES", 16)

    empty_status, _ = asyncio.run(upload(store, data=b""))
    oversized_status, _ = asyncio.run(upload(store, data=b"x" * 17))

    assert empty_status == 400
    assert oversized_status == 413
    assert store.list_maps() == []


def test_upload_rejects_expanded_archive_over_limit(tmp_path, monkeypatch):
    store = LbeMapStore(tmp_path / "maps")
    monkeypatch.setattr(lbe_maps, "MAX_LBE_MAP_EXPANDED_BYTES", 4)
    data = make_zip({"mapinfo.json": b"12345"})

    status, _ = asyncio.run(upload(store, data=data))

    assert status == 400
    assert store.list_maps() == []


def test_upload_rejects_large_directory_before_opening_the_zip(tmp_path, monkeypatch):
    store = LbeMapStore(tmp_path / "maps")
    data = make_zip({"mapinfo.json": b"{}"})
    monkeypatch.setattr(lbe_maps, "MAX_LBE_MAP_DIRECTORY_BYTES", 46)

    def unexpected_zip_open(*_args, **_kwargs):
        raise AssertionError("ZipFile must not open an oversized directory")

    monkeypatch.setattr(lbe_maps.zipfile, "ZipFile", unexpected_zip_open)
    status, error = asyncio.run(upload(store, data=data))
    assert status == 400
    assert "central directory" in error["error"]
    assert store.list_maps() == []


def test_failed_atomic_promotion_cleans_partial_upload(tmp_path, monkeypatch):
    store = LbeMapStore(tmp_path / "maps")

    def fail_promotion(_staging, _destination):
        raise OSError("injected promotion failure")

    monkeypatch.setattr(store, "_promote", fail_promotion)
    status, _ = asyncio.run(upload(store))

    assert status == 500
    assert store.list_maps() == []
    assert not list(store.root.glob(".lbe-map-upload-*"))


def test_same_map_published_concurrently_is_deduplicated(tmp_path, monkeypatch):
    for collision_errno in (errno.EEXIST, errno.ENOTEMPTY):
        store = LbeMapStore(tmp_path / str(collision_errno))
        original_promote = LbeMapStore._promote

        def publish_winner_then_report_platform_collision(staging, destination):
            winner = store.root / ".lbe-map-upload-winner"
            winner.mkdir()
            for filename in ("map.zip", "metadata.json"):
                shutil.copyfile(staging / filename, winner / filename)
            original_promote(winner, destination)
            raise OSError(collision_errno, "destination directory already exists")

        monkeypatch.setattr(store, "_promote", publish_winner_then_report_platform_collision)
        status, reference = asyncio.run(upload(store))

        assert status == 200
        assert store.path_for(reference) is not None
        assert not list(store.root.glob(".lbe-map-upload-*"))


def test_zip_decompression_error_is_a_client_error(tmp_path, monkeypatch):
    store = LbeMapStore(tmp_path / "maps")

    def fail_decompression(_archive):
        raise zlib.error("injected corrupt deflate stream")

    monkeypatch.setattr(zipfile.ZipFile, "testzip", fail_decompression)
    status, error = asyncio.run(upload(store))

    assert status == 400
    assert "valid ZIP" in error["error"]
    assert store.list_maps() == []


def test_storage_error_during_zip_validation_remains_server_error(tmp_path, monkeypatch):
    store = LbeMapStore(tmp_path / "maps")

    def fail_read(_archive):
        raise OSError("injected storage read failure")

    monkeypatch.setattr(LbeMapStore, "_check_directory", staticmethod(fail_read))
    status, error = asyncio.run(upload(store))

    assert status == 500
    assert error["error"] == "Could not publish the map ZIP"
    assert store.list_maps() == []


def test_upload_rejects_nested_multipart_without_server_error(tmp_path):
    store = LbeMapStore(tmp_path / "maps")
    nested = make_zip({"mapinfo.json": b"{}"})
    body = (
        b"--outer\r\n"
        b'Content-Disposition: form-data; name="map"; filename="venue-map.zip"\r\n'
        b"Content-Type: multipart/mixed; boundary=inner\r\n\r\n"
        b"--inner\r\n"
        b'Content-Disposition: attachment; filename="nested.zip"\r\n'
        b"Content-Type: application/zip\r\n\r\n"
        + nested
        + b"\r\n--inner--\r\n--outer--\r\n"
    )

    status, error = asyncio.run(upload(store, raw_body=body))

    assert status == 400
    assert "ZIP filename" in error["error"]
    assert store.list_maps() == []


def test_stale_read_only_staging_is_recovered(tmp_path):
    store = LbeMapStore(tmp_path / "maps")
    stale = store.root / ".lbe-map-upload-stale"
    stale.mkdir()
    (stale / "map.zip").write_bytes(b"partial")
    os.chmod(stale / "map.zip", 0o400)
    os.chmod(stale, 0o500)
    now = time.time()
    old_time = now - lbe_maps.LBE_MAP_STAGING_MAX_AGE_SECONDS - 1
    os.utime(stale / "map.zip", (old_time, old_time))
    os.utime(stale, (old_time, old_time))

    candidates = store.staging_directories()
    assert store.remove_stale_staging(candidates, now=now) == 1
    assert not stale.exists()


def test_staging_cleanup_only_removes_discovered_candidates(tmp_path):
    store = LbeMapStore(tmp_path / "maps")
    discovered = store.root / ".lbe-map-upload-discovered"
    discovered.mkdir()
    now = time.time()
    old_time = now - lbe_maps.LBE_MAP_STAGING_MAX_AGE_SECONDS - 1
    os.utime(discovered, (old_time, old_time))
    candidates = store.staging_directories()
    later = store.root / ".lbe-map-upload-later"
    later.mkdir()
    os.utime(later, (old_time, old_time))

    assert store.remove_stale_staging(candidates, now=now) == 1
    assert not discovered.exists()
    assert later.exists()


def test_active_staging_heartbeat_defers_cleanup(tmp_path):
    store = LbeMapStore(tmp_path / "maps")
    staging = store.root / ".lbe-map-upload-active"
    staging.mkdir()
    archive = staging / "map.zip"
    archive.write_bytes(b"partial")
    candidates = store.staging_directories()
    now = time.time()
    recent = now - lbe_maps.LBE_MAP_STAGING_MAX_AGE_SECONDS + 10
    os.utime(archive, (recent, recent))

    assert store.remove_stale_staging(candidates, now=now) == 0
    assert staging.exists()


def test_upload_fsync_does_not_block_the_event_loop(tmp_path, monkeypatch):
    store = LbeMapStore(tmp_path / "maps")
    fsync_started = threading.Event()
    allow_fsync = threading.Event()
    original_flush_fsync = LbeMapStore._flush_fsync

    def slow_flush_fsync(handle):
        fsync_started.set()
        if not allow_fsync.wait(2):
            raise TimeoutError("test did not release fsync")
        original_flush_fsync(handle)

    monkeypatch.setattr(LbeMapStore, "_flush_fsync", staticmethod(slow_flush_fsync))

    async def body():
        task = asyncio.create_task(upload(store))
        release_timer = threading.Timer(0.2, allow_fsync.set)
        release_timer.start()
        try:
            while not fsync_started.is_set():
                await asyncio.sleep(0.001)
            await asyncio.wait_for(asyncio.sleep(0.01), timeout=0.05)
            assert not task.done()
            status, _ = await task
            assert status == 200
        finally:
            allow_fsync.set()
            release_timer.cancel()
            if not task.done():
                await task

    asyncio.run(body())


def test_upload_requires_one_map_multipart_field(tmp_path):
    store = LbeMapStore(tmp_path / "maps")

    status, error = asyncio.run(upload(store, fields=[("other", b"ignored")]))

    assert status == 400
    assert error["error"] == "Multipart field 'map' with a ZIP filename is required"
    assert store.list_maps() == []
