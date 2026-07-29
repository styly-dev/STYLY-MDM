"""Immutable artifact publication and job-owned upload workspace helpers."""

from __future__ import annotations

import hashlib
import os
import shutil
import uuid
from pathlib import Path
from typing import BinaryIO


class ArtifactStore:
    def __init__(self, data_dir: Path) -> None:
        self.data_dir = data_dir
        self.work_root = data_dir / "push-work"
        self.artifact_root = data_dir / "push-artifacts"
        self.work_root.mkdir(parents=True, exist_ok=True)
        self.artifact_root.mkdir(parents=True, exist_ok=True)

    def work_dir(self, job_id: str) -> Path:
        # job_id is server-generated UUID, but validate before using it as a path key.
        canonical = str(uuid.UUID(job_id))
        path = self.work_root / canonical
        path.mkdir(parents=True, exist_ok=True)
        return path

    def upload_dir(self, job_id: str) -> Path:
        path = self.work_dir(job_id) / "upload"
        path.mkdir(parents=True, exist_ok=True)
        return path

    def cleanup_work(self, job_id: str) -> None:
        try:
            path = self.work_root / str(uuid.UUID(job_id))
        except ValueError:
            return
        shutil.rmtree(path, ignore_errors=False)

    def cleanup_work_best_effort(self, job_id: str) -> None:
        try:
            self.cleanup_work(job_id)
        except FileNotFoundError:
            return
        except OSError:
            # Lifecycle outcome is already canonical in SQLite; cleanup failure must
            # not roll it back.  The runtime logs the exception around this helper.
            return

    def publish(self, job_id: str, part_path: Path, display_filename: str, entry_count: int) -> dict[str, object]:
        if not part_path.is_file():
            raise FileNotFoundError(part_path)
        artifact_id = str(uuid.uuid4())
        storage_name = f"{artifact_id}.zip"
        destination = self.artifact_root / storage_name

        byte_size, sha256 = self._identity(part_path)
        with part_path.open("rb") as handle:
            os.fsync(handle.fileno())
        os.replace(part_path, destination)
        self._fsync_dir(self.artifact_root)
        return {
            "artifact_id": artifact_id,
            "storage_name": storage_name,
            "display_filename": display_filename,
            "byte_size": byte_size,
            "sha256": sha256,
            "entry_count": entry_count,
            "path": destination,
        }

    def path_for_record(self, record: dict[str, object]) -> Path:
        storage_name = record.get("storage_name")
        if not isinstance(storage_name, str) or Path(storage_name).name != storage_name:
            raise ValueError("invalid artifact storage name")
        path = self.artifact_root / storage_name
        resolved = path.resolve()
        if resolved.parent != self.artifact_root.resolve():
            raise ValueError("artifact path escapes the artifact root")
        return resolved

    @staticmethod
    def _identity(path: Path) -> tuple[int, str]:
        digest = hashlib.sha256()
        size = 0
        with path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
                size += len(block)
        return size, digest.hexdigest()

    @staticmethod
    def _fsync_dir(path: Path) -> None:
        flags = os.O_RDONLY
        if hasattr(os, "O_DIRECTORY"):
            flags |= os.O_DIRECTORY
        fd = os.open(path, flags)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
