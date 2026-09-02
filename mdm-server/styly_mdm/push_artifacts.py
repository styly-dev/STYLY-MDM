"""Immutable artifact publication and job-owned upload workspace helpers."""

from __future__ import annotations

import hashlib
import logging
import os
import shutil
import uuid
from pathlib import Path

log = logging.getLogger("stylymdm.push.artifacts")


def strong_etag(sha256: str) -> str:
    """Return the wire representation of the artifact's strong validator."""

    # The digest is calculated from the published bytes and therefore is a strong
    # validator.  Keep the quotes here (rather than at each HTTP call site) so
    # snapshots and commands cannot accidentally expose a weak/bare validator.
    return f'"{sha256}"'


class ArtifactStore:
    def __init__(self, data_dir: Path) -> None:
        self.data_dir = Path(data_dir)
        self.work_root = self.data_dir / "push-work"
        self.artifact_root = self.data_dir / "push-artifacts"
        self.work_root.mkdir(parents=True, exist_ok=True)
        self.artifact_root.mkdir(parents=True, exist_ok=True)

    @staticmethod
    def _uuid_key(value: str) -> str:
        parsed = uuid.UUID(value)
        if parsed.version != 4:
            raise ValueError("path identity must be UUIDv4")
        return str(parsed)

    def work_dir(self, job_id: str) -> Path:
        path = self.work_root / self._uuid_key(job_id)
        path.mkdir(parents=True, exist_ok=True)
        return path

    def upload_dir(self, job_id: str) -> Path:
        path = self.work_dir(job_id) / "upload"
        path.mkdir(parents=True, exist_ok=True)
        return path

    def cleanup_work(self, job_id: str) -> None:
        path = self.work_root / self._uuid_key(job_id)
        shutil.rmtree(path, ignore_errors=False)

    def cleanup_work_best_effort(self, job_id: str) -> None:
        try:
            self.cleanup_work(job_id)
        except FileNotFoundError:
            return
        except (OSError, ValueError):
            # The lifecycle result is already committed. Cleanup is observable but
            # never allowed to rewrite that canonical outcome.
            log.exception("Could not clean owned Push/Sync work directory for %s", job_id)

    def publish(
        self,
        job_id: str,
        part_path: Path,
        display_filename: str,
        entry_count: int,
    ) -> dict[str, object]:
        self._uuid_key(job_id)
        if not part_path.is_file():
            raise FileNotFoundError(part_path)
        artifact_id = str(uuid.uuid4())
        storage_name = f"{artifact_id}.zip"
        destination = self.artifact_root / storage_name
        if destination.exists():
            raise FileExistsError(destination)

        byte_size, sha256 = self._identity(part_path)
        # Flush the exact bytes before making the immutable name visible.
        with part_path.open("r+b") as handle:
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(part_path, destination)
        self._fsync_dir(self.artifact_root)
        return {
            "artifact_id": artifact_id,
            "storage_name": storage_name,
            "display_filename": display_filename,
            "byte_size": byte_size,
            "sha256": sha256,
            "etag": strong_etag(sha256),
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

    def remove_orphan(self, storage_name: str) -> None:
        if Path(storage_name).name != storage_name:
            raise ValueError("invalid artifact storage name")
        path = self.artifact_root / storage_name
        if path.parent.resolve() != self.artifact_root.resolve():
            raise ValueError("artifact path escapes the artifact root")
        path.unlink(missing_ok=True)

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
        # CPython does not expose a portable directory handle on Windows. The file
        # itself is fsynced above; POSIX additionally persists the directory rename.
        if os.name == "nt":
            return
        flags = os.O_RDONLY
        if hasattr(os, "O_DIRECTORY"):
            flags |= os.O_DIRECTORY
        fd = os.open(path, flags)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
