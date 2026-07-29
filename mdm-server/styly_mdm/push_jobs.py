"""Canonical push/sync job domain model for issue #91.

This module deliberately has no aiohttp or SQLite dependency.  It owns the
protocol vocabulary, canonical request fingerprint, capability parsing, state
transition validation, and aggregate/job-state derivation used by both the
persistent store and tests.
"""

from __future__ import annotations

import hashlib
import json
import posixpath
import uuid
from dataclasses import dataclass
from enum import Enum
from typing import Any, Iterable, Mapping


CAP_PUSH_JOB_ID_V1 = "push_job_id_v1"
CAP_PUSH_PROGRESS_V1 = "push_progress_v1"
CAP_PUSH_RESUME_V1 = "push_resume_v1"
CAP_PUSH_CANCEL_V1 = "push_cancel_v1"


class StringEnum(str, Enum):
    """Python 3.10-compatible equivalent of enum.StrEnum."""

    def __str__(self) -> str:
        return self.value


class PushMode(StringEnum):
    PUSH = "push"
    SYNC = "sync"


class ProtocolMode(StringEnum):
    JOB_V1 = "job_v1"
    LEGACY = "legacy"


class JobState(StringEnum):
    CREATED = "created"
    UPLOADING = "uploading"
    PACKAGING = "packaging"
    READY = "ready"
    RUNNING = "running"
    RECONCILING = "reconciling"
    SUCCEEDED = "succeeded"
    COMPLETED_WITH_ERRORS = "completed_with_errors"
    FAILED = "failed"
    INTERRUPTED = "interrupted"
    CANCELLED = "cancelled"  # reserved for #89


class DeviceState(StringEnum):
    QUEUED = "queued"
    WAITING_TRANSFER = "waiting_transfer"
    DISPATCHING = "dispatching"
    DOWNLOADING = "downloading"
    VALIDATING = "validating"
    APPLYING = "applying"
    RECONCILING = "reconciling"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    INTERRUPTED = "interrupted"
    UNCONFIRMED = "unconfirmed"
    CANCELLED = "cancelled"  # reserved for #89


TERMINAL_JOB_STATES = frozenset(
    {
        JobState.SUCCEEDED,
        JobState.COMPLETED_WITH_ERRORS,
        JobState.FAILED,
        JobState.INTERRUPTED,
        JobState.CANCELLED,
    }
)
TERMINAL_DEVICE_STATES = frozenset(
    {
        DeviceState.SUCCEEDED,
        DeviceState.FAILED,
        DeviceState.INTERRUPTED,
        DeviceState.UNCONFIRMED,
        DeviceState.CANCELLED,
    }
)
ACTIVE_DEVICE_STATES = frozenset(
    {
        DeviceState.WAITING_TRANSFER,
        DeviceState.DISPATCHING,
        DeviceState.DOWNLOADING,
        DeviceState.VALIDATING,
        DeviceState.APPLYING,
        DeviceState.RECONCILING,
    }
)


JOB_TRANSITIONS: Mapping[JobState, frozenset[JobState]] = {
    JobState.CREATED: frozenset({JobState.UPLOADING, JobState.INTERRUPTED, JobState.FAILED}),
    JobState.UPLOADING: frozenset({JobState.PACKAGING, JobState.INTERRUPTED, JobState.FAILED}),
    JobState.PACKAGING: frozenset({JobState.READY, JobState.INTERRUPTED, JobState.FAILED}),
    JobState.READY: frozenset({JobState.RUNNING, JobState.FAILED}),
    JobState.RUNNING: frozenset(
        {
            JobState.RECONCILING,
            JobState.SUCCEEDED,
            JobState.COMPLETED_WITH_ERRORS,
            JobState.FAILED,
        }
    ),
    JobState.RECONCILING: frozenset(
        {
            JobState.RUNNING,
            JobState.SUCCEEDED,
            JobState.COMPLETED_WITH_ERRORS,
            JobState.FAILED,
        }
    ),
}

DEVICE_TRANSITIONS: Mapping[DeviceState, frozenset[DeviceState]] = {
    DeviceState.QUEUED: frozenset(
        {DeviceState.WAITING_TRANSFER, DeviceState.FAILED, DeviceState.INTERRUPTED}
    ),
    DeviceState.WAITING_TRANSFER: frozenset(
        {
            DeviceState.QUEUED,
            DeviceState.DISPATCHING,
            DeviceState.FAILED,
            DeviceState.INTERRUPTED,
        }
    ),
    DeviceState.DISPATCHING: frozenset(
        {
            DeviceState.QUEUED,
            DeviceState.DOWNLOADING,
            DeviceState.RECONCILING,
            DeviceState.FAILED,
        }
    ),
    DeviceState.DOWNLOADING: frozenset(
        {DeviceState.VALIDATING, DeviceState.RECONCILING, DeviceState.FAILED}
    ),
    DeviceState.VALIDATING: frozenset(
        {DeviceState.APPLYING, DeviceState.RECONCILING, DeviceState.FAILED}
    ),
    DeviceState.APPLYING: frozenset(
        {DeviceState.SUCCEEDED, DeviceState.RECONCILING, DeviceState.FAILED}
    ),
    DeviceState.RECONCILING: frozenset(
        {
            DeviceState.DOWNLOADING,
            DeviceState.VALIDATING,
            DeviceState.APPLYING,
            DeviceState.QUEUED,
            DeviceState.SUCCEEDED,
            DeviceState.FAILED,
            DeviceState.INTERRUPTED,
            DeviceState.UNCONFIRMED,
        }
    ),
}


class PushJobError(ValueError):
    """Base class for canonical request/state errors."""


class InvalidTransition(PushJobError):
    pass


class RequestConflict(PushJobError):
    pass


@dataclass(frozen=True)
class CanonicalSource:
    display_name: str
    declared_file_count: int
    declared_total_bytes: int

    def as_dict(self) -> dict[str, Any]:
        return {
            "display_name": self.display_name,
            "declared_file_count": self.declared_file_count,
            "declared_total_bytes": self.declared_total_bytes,
        }


@dataclass(frozen=True)
class CanonicalCreateRequest:
    client_request_id: str
    target_devices: tuple[str, ...]
    mode: PushMode
    dest_path: str
    source: CanonicalSource
    fingerprint: str

    def fingerprint_payload(self) -> dict[str, Any]:
        return {
            "target_devices": list(self.target_devices),
            "mode": self.mode.value,
            "dest_path": self.dest_path,
            "source": self.source.as_dict(),
        }


def require_uuid(value: Any, field: str) -> str:
    if not isinstance(value, str):
        raise PushJobError(f"{field} must be a UUID string")
    try:
        parsed = uuid.UUID(value)
    except (ValueError, AttributeError) as exc:
        raise PushJobError(f"{field} must be a valid UUID") from exc
    return str(parsed)


def canonical_destination(value: Any) -> str:
    if not isinstance(value, str) or not value.strip():
        raise PushJobError("dest_path is required")
    text = value.strip().replace("\\", "/")
    if "\x00" in text:
        raise PushJobError("dest_path contains an invalid character")
    if not text.startswith("/"):
        raise PushJobError("dest_path must be absolute")
    if ".." in text.split("/"):
        raise PushJobError("dest_path must not contain '..'")
    normalized = posixpath.normpath(text)
    aliases = ("/sdcard", "/storage/emulated/0")
    root = next((prefix for prefix in aliases if normalized == prefix or normalized.startswith(prefix + "/")), None)
    if root is None:
        raise PushJobError("dest_path must be under shared storage")
    remainder = normalized[len(root) :].strip("/")
    if not remainder:
        raise PushJobError("dest_path must be a shared-storage subdirectory")
    protected = {
        "android",
        "download",
        "downloads",
        "dcim",
        "pictures",
        "movies",
        "music",
        "documents",
        "alarms",
        "notifications",
        "podcasts",
        "ringtones",
    }
    if remainder.split("/", 1)[0].lower() in protected:
        raise PushJobError("dest_path points inside a protected top-level directory")
    # Keep one protocol spelling so fingerprinting is stable across /storage/emulated/0 aliases.
    return "/sdcard/" + remainder


def canonicalize_create_request(data: Mapping[str, Any]) -> CanonicalCreateRequest:
    client_request_id = require_uuid(data.get("client_request_id"), "client_request_id")

    raw_targets = data.get("target_devices")
    if not isinstance(raw_targets, list) or not raw_targets:
        raise PushJobError("target_devices must be a non-empty array")
    if any(not isinstance(item, str) or not item.strip() for item in raw_targets):
        raise PushJobError("target_devices must contain non-empty device IDs")
    cleaned_targets = [item.strip() for item in raw_targets]
    if len(set(cleaned_targets)) != len(cleaned_targets):
        raise PushJobError("target_devices must not contain duplicates")
    targets = tuple(sorted(cleaned_targets))

    try:
        mode = PushMode(str(data.get("mode", "")).lower())
    except ValueError as exc:
        raise PushJobError("mode must be 'push' or 'sync'") from exc

    dest_path = canonical_destination(data.get("dest_path"))
    raw_source = data.get("source")
    if not isinstance(raw_source, Mapping):
        raise PushJobError("source must be an object")
    display_name = raw_source.get("display_name")
    if not isinstance(display_name, str) or not display_name.strip():
        raise PushJobError("source.display_name is required")
    display_name = display_name.strip()[:255]
    declared_file_count = raw_source.get("declared_file_count")
    declared_total_bytes = raw_source.get("declared_total_bytes")
    if isinstance(declared_file_count, bool) or not isinstance(declared_file_count, int) or declared_file_count < 1:
        raise PushJobError("source.declared_file_count must be a positive integer")
    if isinstance(declared_total_bytes, bool) or not isinstance(declared_total_bytes, int) or declared_total_bytes < 0:
        raise PushJobError("source.declared_total_bytes must be a non-negative integer")

    source = CanonicalSource(display_name, declared_file_count, declared_total_bytes)
    payload = {
        "target_devices": list(targets),
        "mode": mode.value,
        "dest_path": dest_path,
        "source": source.as_dict(),
    }
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    fingerprint = hashlib.sha256(encoded).hexdigest()
    return CanonicalCreateRequest(
        client_request_id=client_request_id,
        target_devices=targets,
        mode=mode,
        dest_path=dest_path,
        source=source,
        fingerprint=fingerprint,
    )


def parse_capabilities(value: Any) -> frozenset[str]:
    """Parse REGISTER capabilities with the issue #91 all-or-nothing malformed rule."""
    if value is None:
        return frozenset()
    if not isinstance(value, list):
        return frozenset()
    parsed: list[str] = []
    for item in value:
        if not isinstance(item, str) or not item or len(item) > 128:
            return frozenset()
        parsed.append(item)
    return frozenset(parsed)


def validate_job_transition(current: str | JobState, target: str | JobState) -> None:
    src, dst = JobState(current), JobState(target)
    if src == dst:
        return
    if src in TERMINAL_JOB_STATES or dst not in JOB_TRANSITIONS.get(src, frozenset()):
        raise InvalidTransition(f"invalid job transition: {src.value} -> {dst.value}")


def validate_device_transition(current: str | DeviceState, target: str | DeviceState) -> None:
    src, dst = DeviceState(current), DeviceState(target)
    if src == dst:
        return
    if src in TERMINAL_DEVICE_STATES or dst not in DEVICE_TRANSITIONS.get(src, frozenset()):
        raise InvalidTransition(f"invalid device transition: {src.value} -> {dst.value}")


def aggregate_device_states(states: Iterable[str | DeviceState]) -> dict[str, int]:
    counts = {state.value: 0 for state in DeviceState if state is not DeviceState.CANCELLED}
    total = 0
    for raw in states:
        state = DeviceState(raw)
        if state is DeviceState.CANCELLED:
            # Reserved state is kept out of #91 aggregate fields until #89 owns it.
            counts.setdefault("cancelled", 0)
            counts["cancelled"] += 1
        else:
            counts[state.value] += 1
        total += 1
    counts["total"] = total
    return counts


def derive_dispatched_job_state(states: Iterable[str | DeviceState]) -> JobState:
    rows = tuple(DeviceState(state) for state in states)
    if not rows:
        raise PushJobError("cannot derive a dispatched job state without device rows")
    if any(state is DeviceState.RECONCILING for state in rows):
        return JobState.RECONCILING
    if any(
        state
        in {
            DeviceState.QUEUED,
            DeviceState.WAITING_TRANSFER,
            DeviceState.DISPATCHING,
            DeviceState.DOWNLOADING,
            DeviceState.VALIDATING,
            DeviceState.APPLYING,
        }
        for state in rows
    ):
        return JobState.RUNNING
    if all(state is DeviceState.SUCCEEDED for state in rows):
        return JobState.SUCCEEDED
    if any(state is DeviceState.UNCONFIRMED for state in rows):
        return JobState.COMPLETED_WITH_ERRORS
    if any(state is DeviceState.SUCCEEDED for state in rows):
        return JobState.COMPLETED_WITH_ERRORS
    return JobState.FAILED


def is_terminal_device_state(value: str | DeviceState) -> bool:
    return DeviceState(value) in TERMINAL_DEVICE_STATES


def is_terminal_job_state(value: str | JobState) -> bool:
    return JobState(value) in TERMINAL_JOB_STATES
