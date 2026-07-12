"""Tests for styly-mdm-client APK detection and startup seeding.

The console offers a per-device "Update" button that installs the server's own
client APK; the server finds the newest one in APK_DIR by the release naming
convention and seeds a wheel-bundled copy into APK_DIR on startup.
"""

import json

import pytest

from styly_mdm import server


@pytest.mark.parametrize(
    "name, expected",
    [
        ("styly-mdm-client_v0.3.0.apk", ("0.3.0", (0, 3, 0))),
        ("styly-mdm-client_0.9.0.apk", ("0.9.0", (0, 9, 0))),  # optional "v"
        # unique_apk_path() collision suffixes: -<timestamp> and -<timestamp>-<counter>.
        ("styly-mdm-client_v0.3.0-20260712-200000.apk", ("0.3.0", (0, 3, 0))),
        ("styly-mdm-client_v0.3.0-20260712-200000-2.apk", ("0.3.0", (0, 3, 0))),
        ("styly-mdm-client_v1.2.apk", ("1.2", (1, 2))),  # two-component version
        ("UserClient-20260710.apk", None),
        ("app-prod-release.apk", None),
        ("styly-mdm-client.apk", None),  # no version
        ("not-an-apk.txt", None),
    ],
)
def test_client_apk_version_parsing(name, expected):
    assert server._client_apk_version(name) == expected


def test_latest_client_apk_picks_highest_version_numerically(tmp_path):
    server._apply_data_dir(str(tmp_path))
    server.APK_DIR.mkdir(parents=True, exist_ok=True)
    for name in [
        "styly-mdm-client_v0.2.0.apk",
        "styly-mdm-client_v0.3.0.apk",
        "styly-mdm-client_v0.10.0.apk",  # 0.10 > 0.3 numerically, not lexically
        "UserClient-legacy.apk",  # ignored: not a client APK
    ]:
        (server.APK_DIR / name).write_bytes(b"x")

    info = server.latest_client_apk()
    assert info == {
        "filename": "styly-mdm-client_v0.10.0.apk",
        "url": "/apks/styly-mdm-client_v0.10.0.apk",
        "version": "0.10.0",
    }


def test_latest_client_apk_prefers_newest_mtime_on_same_version(tmp_path):
    # A re-upload of the same version lands under a collision name; the newer file
    # (by mtime) must win, so the operator's fresh upload supersedes the seeded one.
    server._apply_data_dir(str(tmp_path))
    server.APK_DIR.mkdir(parents=True, exist_ok=True)
    original = server.APK_DIR / "styly-mdm-client_v0.3.0.apk"
    reupload = server.APK_DIR / "styly-mdm-client_v0.3.0-20260712-200000.apk"
    original.write_bytes(b"old")
    reupload.write_bytes(b"new")
    import os

    os.utime(original, (1000, 1000))
    os.utime(reupload, (2000, 2000))  # newer

    info = server.latest_client_apk()
    assert info["filename"] == "styly-mdm-client_v0.3.0-20260712-200000.apk"
    assert info["version"] == "0.3.0"


def test_latest_client_apk_none_when_absent(tmp_path):
    server._apply_data_dir(str(tmp_path))
    server.APK_DIR.mkdir(parents=True, exist_ok=True)
    (server.APK_DIR / "app-prod-release.apk").write_bytes(b"x")  # not a client APK
    assert server.latest_client_apk() is None
    assert json.loads(server.build_client_apk_msg()) == {"type": "CLIENT_APK_INFO", "apk": None}


def test_seed_bundled_client_apk_copies_once(tmp_path, monkeypatch):
    bundled = tmp_path / "bundled"
    bundled.mkdir()
    (bundled / "styly-mdm-client_v0.3.0.apk").write_bytes(b"apk")
    (bundled / "README.txt").write_bytes(b"ignored")  # non-apk skipped
    monkeypatch.setattr(server, "BUNDLED_CLIENT_DIR", bundled)

    server._apply_data_dir(str(tmp_path / "data"))
    server.APK_DIR.mkdir(parents=True, exist_ok=True)

    server.seed_bundled_client_apk()
    seeded = server.APK_DIR / "styly-mdm-client_v0.3.0.apk"
    assert seeded.is_file()
    assert server.latest_client_apk()["version"] == "0.3.0"

    # Idempotent and non-clobbering: an operator's edit of the same name survives.
    seeded.write_bytes(b"operator-copy")
    server.seed_bundled_client_apk()
    assert seeded.read_bytes() == b"operator-copy"
