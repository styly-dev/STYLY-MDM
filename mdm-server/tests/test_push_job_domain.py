import itertools
import subprocess
import sys
import uuid
from pathlib import Path

import pytest

import styly_mdm.push_jobs as push_jobs
from styly_mdm.push_jobs import (
    CAP_PUSH_JOB_ID_V1,
    DeviceState,
    JobState,
    PushJobError,
    aggregate_device_states,
    canonical_destination,
    canonicalize_create_request,
    derive_dispatched_job_state,
    parse_capabilities,
    validate_device_transition,
)


def test_strenum_members_keep_string_semantics():
    state = JobState.CREATED
    assert isinstance(state, str)
    assert str(state) == "created"
    assert format(state) == "created"
    assert f"{state}" == "created"
    assert repr(state) == "<JobState.CREATED: 'created'>"


def test_push_jobs_imports_without_stdlib_strenum():
    script = """
import enum
import importlib.util
import sys

if hasattr(enum, "StrEnum"):
    del enum.StrEnum
spec = importlib.util.spec_from_file_location("_push_jobs_compat_test", sys.argv[1])
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)
state = module.JobState.CREATED
assert isinstance(state, str)
assert str(state) == "created"
assert format(state) == "created"
assert repr(state) == "<JobState.CREATED: 'created'>"
"""
    subprocess.run(
        [sys.executable, "-c", script, str(Path(push_jobs.__file__))],
        check=True,
    )


def request(**overrides):
    raw = {
        "client_request_id": str(uuid.uuid4()),
        "target_devices": ["B", "A"],
        "mode": "push",
        "dest_path": "/storage/emulated/0/STYLY/content/",
        "source": {
            "display_name": "content",
            "declared_file_count": 2,
            "declared_total_bytes": 10,
        },
    }
    raw.update(overrides)
    return raw


def test_create_request_is_canonical_and_fingerprint_is_order_independent():
    first = canonicalize_create_request(request())
    second_raw = request(
        client_request_id=first.client_request_id,
        target_devices=["A", "B"],
        dest_path=" /sdcard/STYLY/content ",
    )
    second = canonicalize_create_request(second_raw)
    assert first.target_devices == ("A", "B")
    assert first.dest_path == "/sdcard/STYLY/content"
    assert first.fingerprint == second.fingerprint


def test_duplicate_targets_are_rejected_before_fingerprinting():
    with pytest.raises(PushJobError, match="duplicates"):
        canonicalize_create_request(request(target_devices=["A", "A"]))


@pytest.mark.parametrize("path", ["/sdcard", "/sdcard/Download/x", "relative", "/tmp/x", "/sdcard/a/../b"])
def test_unsafe_destinations_are_rejected(path):
    with pytest.raises(PushJobError):
        canonical_destination(path)


def test_capability_field_is_all_or_nothing():
    assert parse_capabilities([CAP_PUSH_JOB_ID_V1]) == {CAP_PUSH_JOB_ID_V1}
    assert parse_capabilities(None) == set()
    assert parse_capabilities("push_job_id_v1") == set()
    assert parse_capabilities([CAP_PUSH_JOB_ID_V1, 1]) == set()


def test_download_complete_is_not_terminal():
    assert derive_dispatched_job_state([DeviceState.VALIDATING]) == JobState.RUNNING
    assert derive_dispatched_job_state([DeviceState.APPLYING]) == JobState.RUNNING


def test_aggregate_and_job_derivation_cover_terminal_combinations():
    terminal = [
        DeviceState.SUCCEEDED,
        DeviceState.FAILED,
        DeviceState.INTERRUPTED,
        DeviceState.UNCONFIRMED,
    ]
    for rows in itertools.product(terminal, repeat=3):
        aggregate = aggregate_device_states(rows)
        assert aggregate["total"] == 3
        assert sum(aggregate[state.value] for state in terminal) == 3
        derived = derive_dispatched_job_state(rows)
        if all(state is DeviceState.SUCCEEDED for state in rows):
            assert derived is JobState.SUCCEEDED
        elif any(state is DeviceState.UNCONFIRMED for state in rows):
            assert derived is JobState.COMPLETED_WITH_ERRORS
        elif any(state is DeviceState.SUCCEEDED for state in rows):
            assert derived is JobState.COMPLETED_WITH_ERRORS
        else:
            assert derived is JobState.FAILED


def test_monotonic_transition_rejects_apply_to_download():
    with pytest.raises(Exception):
        validate_device_transition(DeviceState.APPLYING, DeviceState.DOWNLOADING)
