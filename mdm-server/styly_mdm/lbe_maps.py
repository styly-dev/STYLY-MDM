"""Immutable storage for PICO LBE map ZIP files."""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import re
import shutil
import stat
import struct
import tempfile
import time
import zipfile
from pathlib import Path
from typing import Any

from aiohttp import web
from aiohttp.multipart import BodyPartReader, MultipartReader

from .integrity import EOCD_MIN_SIZE, EOCD_SIGNATURE, MAX_EOCD_COMMENT

MAX_LBE_MAP_BYTES = 512 * 1024 * 1024
MAX_LBE_MAP_EXPANDED_BYTES = 2 * 1024 * 1024 * 1024
MAX_LBE_MAP_ENTRIES = 10_000
MAX_LBE_MAP_DIRECTORY_BYTES = 16 * 1024 * 1024
MAX_LBE_MAP_NAME_LENGTH = 128
MAX_LBE_MAP_PATH_LENGTH = 1024
LBE_MAP_CHUNK_SIZE = 1024 * 1024
LBE_MAP_STAGING_PREFIX = ".lbe-map-upload-"
LBE_MAP_STAGING_MAX_AGE_SECONDS = 24 * 60 * 60
_SHA256_PATTERN = re.compile(r"[a-f0-9]{64}\Z")


class LbeMapError(ValueError):
    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.status = status


def _safe_map_name(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    name = value.strip()
    if (
        not name
        or len(name) > MAX_LBE_MAP_NAME_LENGTH
        or name in (".", "..")
        or "/" in name
        or "\\" in name
        or any(ord(character) < 32 or ord(character) == 127 for character in name)
        or not name.lower().endswith(".zip")
    ):
        return None
    return name


def valid_map_reference(value: Any) -> bool:
    """Return whether a value has the exact public LBE map reference shape."""
    return (
        isinstance(value, dict)
        and set(value) == {"sha256", "name", "size"}
        and isinstance(value.get("sha256"), str)
        and _SHA256_PATTERN.fullmatch(value["sha256"]) is not None
        and isinstance(value.get("name"), str)
        and _safe_map_name(value.get("name")) == value.get("name")
        and type(value.get("size")) is int
        and 0 < value["size"] <= MAX_LBE_MAP_BYTES
    )


class LbeMapStore:
    """Keep map ZIP bytes and their metadata together under a content hash."""

    def __init__(self, path: Path):
        self.root = Path(path)
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.root = self.root.resolve()

    def cleanup_stale_staging(self, *, now: float | None = None) -> int:
        """Remove abandoned upload directories left by a prior server process."""
        current_time = time.time() if now is None else now
        removed = 0
        try:
            candidates = tuple(self.root.glob(f"{LBE_MAP_STAGING_PREFIX}*"))
        except OSError:
            return removed
        for candidate in candidates:
            try:
                if candidate.is_symlink() or not candidate.is_dir():
                    continue
                if current_time - candidate.stat().st_mtime < LBE_MAP_STAGING_MAX_AGE_SECONDS:
                    continue
                # Older releases chmodded staging directories to 0500 before
                # promotion. Restore owner write access before removing one.
                os.chmod(candidate, 0o700)
                shutil.rmtree(candidate)
                removed += 1
            except OSError:
                continue
        return removed

    def list_maps(self) -> list[dict[str, Any]]:
        references = []
        try:
            directories = tuple(self.root.iterdir())
        except OSError:
            return references
        for directory in directories:
            if _SHA256_PATTERN.fullmatch(directory.name) is None:
                continue
            reference = self._read_reference(directory)
            if reference is not None:
                references.append(reference)
        return sorted(references, key=lambda item: (item["name"].casefold(), item["sha256"]))

    def path_for(self, reference: Any) -> Path | None:
        if not valid_map_reference(reference):
            return None
        directory = self.root / reference["sha256"]
        registered = self._read_reference(directory)
        if registered != reference:
            return None
        archive = directory / "map.zip"
        try:
            if archive.stat().st_size != reference["size"]:
                return None
        except OSError:
            return None
        return archive

    async def save_upload(self, request: web.Request) -> dict[str, Any]:
        try:
            reader = await request.multipart()
        except web.HTTPRequestEntityTooLarge as exc:
            raise LbeMapError("Map upload exceeds the request size limit", 413) from exc
        except (AssertionError, ValueError, web.HTTPException) as exc:
            raise LbeMapError("Expected a multipart upload with a map ZIP", 400) from exc

        try:
            staging = Path(tempfile.mkdtemp(prefix=LBE_MAP_STAGING_PREFIX, dir=self.root))
        except OSError as exc:
            raise LbeMapError("Could not prepare map ZIP storage", 500) from exc
        archive = staging / "map.zip"
        try:
            part = await self._next_part(reader)
            if (not isinstance(part, BodyPartReader) or part.name != "map"
                    or not part.filename):
                raise LbeMapError("Multipart field 'map' with a ZIP filename is required", 400)
            name = _safe_map_name(part.filename)
            if name is None:
                raise LbeMapError("Map filename must be a safe .zip name", 400)

            digest = hashlib.sha256()
            size = 0
            output = archive.open("xb")
            try:
                while True:
                    chunk = await self._read_chunk(part)
                    if not chunk:
                        break
                    size += len(chunk)
                    if size > MAX_LBE_MAP_BYTES:
                        raise LbeMapError("Map ZIP exceeds the 512 MiB limit", 413)
                    digest.update(chunk)
                    await self._run_blocking(output.write, chunk)
                await self._run_blocking(self._flush_fsync, output)
            except OSError as exc:
                raise LbeMapError("Could not store the map ZIP upload", 500) from exc
            finally:
                await self._run_blocking(output.close)

            if await self._next_part(reader) is not None:
                raise LbeMapError("Map upload must contain only the 'map' field", 400)

            if size == 0:
                raise LbeMapError("Map ZIP is empty", 400)

            try:
                await self._run_blocking(self._validate_zip, archive)
            except (OSError, zipfile.BadZipFile, zipfile.LargeZipFile, RuntimeError,
                    EOFError, UnicodeError) as exc:
                raise LbeMapError("Map upload is not a valid ZIP archive", 400) from exc

            reference = {"sha256": digest.hexdigest(), "name": name, "size": size}
            destination = self.root / reference["sha256"]
            if destination.exists():
                registered = self._read_reference(destination)
                if registered is None:
                    raise LbeMapError("Stored map metadata is unavailable", 500)
                return registered

            try:
                await self._run_blocking(self._publish_staging, staging, destination, reference)
            except FileExistsError:
                # Another request may have published the same bytes after the first
                # existence check. Treat that exact hash as a deduplicated success.
                registered = self._read_reference(destination)
                if registered is None:
                    raise LbeMapError("Stored map metadata is unavailable", 500)
                return registered
            return reference
        except OSError as exc:
            raise LbeMapError("Could not publish the map ZIP", 500) from exc
        finally:
            if staging.exists():
                await self._run_blocking(self._remove_staging, staging)

    @staticmethod
    async def _next_part(reader: MultipartReader) -> BodyPartReader | MultipartReader | None:
        try:
            return await reader.next()
        except web.HTTPRequestEntityTooLarge as exc:
            raise LbeMapError("Map upload exceeds the request size limit", 413) from exc
        except (AssertionError, TypeError, ValueError, web.HTTPException) as exc:
            raise LbeMapError("Invalid map upload form", 400) from exc

    @staticmethod
    async def _read_chunk(part: BodyPartReader) -> bytes:
        try:
            return await part.read_chunk(size=LBE_MAP_CHUNK_SIZE)
        except web.HTTPRequestEntityTooLarge as exc:
            raise LbeMapError("Map upload exceeds the request size limit", 413) from exc
        except (AssertionError, TypeError, ValueError, web.HTTPException) as exc:
            raise LbeMapError("Could not read the map ZIP upload", 400) from exc

    @staticmethod
    async def _run_blocking(function, *args):
        """Run file work off-loop and wait for it before cancellation cleanup."""
        work = asyncio.create_task(asyncio.to_thread(function, *args))
        try:
            return await asyncio.shield(work)
        except asyncio.CancelledError:
            # A cancelled to_thread await does not stop its worker thread. Wait for
            # that thread before closing or deleting the file it may still use.
            await asyncio.gather(work, return_exceptions=True)
            raise

    @staticmethod
    def _flush_fsync(handle) -> None:
        handle.flush()
        os.fsync(handle.fileno())

    def _publish_staging(self, staging: Path, destination: Path, reference: dict[str, Any]) -> None:
        metadata = staging / "metadata.json"
        with metadata.open("x", encoding="utf-8") as output:
            json.dump(reference, output, ensure_ascii=False, separators=(",", ":"))
            output.flush()
            os.fsync(output.fileno())
        os.chmod(staging / "map.zip", 0o400)
        os.chmod(metadata, 0o400)
        self._fsync_directory(staging)
        # Keep the staging directory writable until after rename. A crash before
        # promotion then leaves a removable directory instead of a 0500 orphan.
        self._promote(staging, destination)
        os.chmod(destination, 0o500)
        self._fsync_directory(destination.parent)

    @staticmethod
    def _remove_staging(staging: Path) -> None:
        try:
            os.chmod(staging, 0o700)
        except OSError:
            pass
        shutil.rmtree(staging, ignore_errors=True)

    @staticmethod
    def _check_directory(archive: Path) -> None:
        # Check bounds before ZipFile allocates its directory and entry list.
        with archive.open("rb") as handle:
            size = handle.seek(0, os.SEEK_END)
            tail_start = max(0, size - EOCD_MIN_SIZE - MAX_EOCD_COMMENT - 20)
            handle.seek(tail_start)
            tail = handle.read()
            pos = tail.rfind(EOCD_SIGNATURE)
            if pos < 0 or len(tail) - pos < EOCD_MIN_SIZE:
                raise zipfile.BadZipFile("Missing end of central directory")
            disk, start_disk, local_count, count, directory_size, start, comment_size = struct.unpack_from(
                "<4H2IH", tail, pos + len(EOCD_SIGNATURE),
            )
            if (disk or start_disk or local_count != count
                    or pos + EOCD_MIN_SIZE + comment_size != len(tail)
                    or start + directory_size != tail_start + pos
                    or (pos >= 20 and tail[pos - 20:pos - 16] == b"PK\x06\x07")):
                raise zipfile.BadZipFile("Invalid or unsupported central directory")
            if count > MAX_LBE_MAP_ENTRIES or directory_size > MAX_LBE_MAP_DIRECTORY_BYTES:
                raise LbeMapError("Map ZIP central directory exceeds its limits", 400)
            handle.seek(start)
            for _ in range(count):
                header = handle.read(46)
                if len(header) != 46 or header[:4] != b"PK\x01\x02":
                    raise zipfile.BadZipFile("Invalid central directory entry")
                payload_size = sum(struct.unpack_from("<3H", header, 28))
                if handle.seek(payload_size, os.SEEK_CUR) > start + directory_size:
                    raise zipfile.BadZipFile("Central directory entry exceeds its bounds")
            if handle.tell() != start + directory_size:
                raise zipfile.BadZipFile("Central directory entry count does not match")

    @staticmethod
    def _validate_zip(archive: Path) -> None:
        try:
            LbeMapStore._check_directory(archive)
            with zipfile.ZipFile(archive, "r") as zipped:
                entries = zipped.infolist()
                if not entries:
                    raise LbeMapError("Map ZIP contains no files", 400)

                expanded_size = 0
                file_count = 0
                seen_paths: dict[tuple[str, ...], bool] = {}
                for entry in entries:
                    path = entry.orig_filename
                    if (
                        not path
                        or "\\" in path
                        or "\x00" in path
                        or len(path.encode("utf-8")) > MAX_LBE_MAP_PATH_LENGTH
                        or path.startswith("/")
                        or re.match(r"[A-Za-z]:", path) is not None
                    ):
                        raise LbeMapError("Map ZIP contains an unsafe path", 400)

                    segments = path.split("/")
                    if entry.is_dir() and segments[-1] == "":
                        segments.pop()
                    if not segments or any(segment in ("", ".", "..") for segment in segments):
                        raise LbeMapError("Map ZIP contains an unsafe path", 400)
                    normalized_path = tuple(segment.casefold() for segment in segments)
                    if normalized_path in seen_paths:
                        raise LbeMapError("Map ZIP contains duplicate paths", 400)
                    seen_paths[normalized_path] = entry.is_dir()

                    mode = entry.external_attr >> 16
                    file_type = stat.S_IFMT(mode)
                    allowed_types = (0, stat.S_IFREG, stat.S_IFDIR)
                    if (
                        file_type not in allowed_types
                        or (entry.is_dir() and file_type == stat.S_IFREG)
                        or (not entry.is_dir() and file_type == stat.S_IFDIR)
                    ):
                        raise LbeMapError("Map ZIP contains a non-regular file", 400)
                    if entry.flag_bits & 0x1:
                        raise LbeMapError("Encrypted ZIP entries are not supported", 400)
                    if entry.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                        raise LbeMapError("Map ZIP uses an unsupported compression method", 400)

                    if entry.is_dir():
                        if entry.file_size != 0:
                            raise LbeMapError("Map ZIP contains a non-empty directory entry", 400)
                        continue
                    file_count += 1
                    expanded_size += entry.file_size
                    if expanded_size > MAX_LBE_MAP_EXPANDED_BYTES:
                        raise LbeMapError("Map ZIP expands beyond the 2 GiB limit", 400)

                if file_count == 0:
                    raise LbeMapError("Map ZIP contains no files", 400)
                ordered_paths = sorted(seen_paths)
                if any(not seen_paths[parent] and child[:len(parent)] == parent
                       for parent, child in zip(ordered_paths, ordered_paths[1:])):
                    raise LbeMapError("Map ZIP contains conflicting file paths", 400)
                bad_entry = zipped.testzip()
                if bad_entry is not None:
                    raise LbeMapError("Map ZIP contains a corrupt file", 400)
        except LbeMapError:
            raise
        except (zipfile.BadZipFile, zipfile.LargeZipFile, OSError, RuntimeError, EOFError, UnicodeError) as exc:
            raise LbeMapError("Map upload is not a valid ZIP archive", 400) from exc

    def _read_reference(self, directory: Path) -> dict[str, Any] | None:
        try:
            if directory.is_symlink() or not directory.is_dir():
                return None
            if directory.resolve().parent != self.root:
                return None
            if _SHA256_PATTERN.fullmatch(directory.name) is None:
                return None
            metadata_path = directory / "metadata.json"
            archive = directory / "map.zip"
            if metadata_path.is_symlink() or archive.is_symlink():
                return None
            raw = json.loads(metadata_path.read_text(encoding="utf-8"))
            if not valid_map_reference(raw) or raw["sha256"] != directory.name:
                return None
            if not archive.is_file() or archive.stat().st_size != raw["size"]:
                return None
            return raw
        except (OSError, ValueError, TypeError, RecursionError):
            return None

    @staticmethod
    def _promote(staging: Path, destination: Path) -> None:
        os.rename(staging, destination)

    @staticmethod
    def _fsync_directory(path: Path) -> None:
        if os.name == "nt":
            return
        flags = os.O_RDONLY
        if hasattr(os, "O_DIRECTORY"):
            flags |= os.O_DIRECTORY
        descriptor = os.open(path, flags)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
