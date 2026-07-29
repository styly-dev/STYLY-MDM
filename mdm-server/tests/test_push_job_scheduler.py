from types import SimpleNamespace

from styly_mdm.push_jobs import ProtocolMode
from styly_mdm.push_scheduler import LiveSession, PushScheduler


def test_command_uses_device_visible_absolute_artifact_url():
    session = LiveSession(
        device_id="D1", session_id="s", ws=object(),
        capabilities=frozenset({"push_job_id_v1"}),
        process_instance_id="p", send_lock=SimpleNamespace(),
        origin="http://192.0.2.10:7070",
    )
    snapshot = {
        "job_id": "00000000-0000-4000-8000-000000000001",
        "revision": 3,
        "dest_path": "/sdcard/STYLY/content",
        "mode": "push",
        "artifact": {
            "artifact_id": "00000000-0000-4000-8000-000000000002",
            "url": "/artifacts/00000000-0000-4000-8000-000000000002",
            "display_filename": "x.zip", "byte_size": 1, "sha256": "a" * 64,
        },
        "devices": {"D1": {"attempt": 1}},
    }
    command = PushScheduler._command(snapshot, "D1", ProtocolMode.JOB_V1, session)
    assert command["artifact_url"] == (
        "http://192.0.2.10:7070/artifacts/00000000-0000-4000-8000-000000000002"
    )
    assert command["bundle_url"] == command["artifact_url"]
