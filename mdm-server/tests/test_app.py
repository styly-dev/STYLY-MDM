"""Smoke tests for the styly_mdm control server.

No network is used: these exercise the app factory, verify the web console is
bundled with the package, and cover the pure filename helpers.
"""

import json
from pathlib import Path

import pytest

import styly_mdm
from styly_mdm import server


@pytest.fixture
def app(tmp_path):
    """A fresh app whose writable data directory is an isolated tmp path."""
    server._apply_data_dir(str(tmp_path))
    return styly_mdm.create_app()


def test_create_app_registers_expected_routes(app):
    canonicals = {resource.canonical for resource in app.router.resources()}
    method_paths = {(route.method, route.resource.canonical) for route in app.router.routes()}

    assert ("GET", "/") in method_paths
    assert ("GET", "/ws/device") in method_paths
    assert ("GET", "/ws/admin") in method_paths
    assert ("POST", "/api/apks") in method_paths
    # Static mounts are prefix resources.
    assert any(c.startswith("/static") for c in canonicals)
    assert any(c.startswith("/apks") for c in canonicals)


def test_create_app_creates_data_dir(tmp_path):
    data_dir = tmp_path / "nested" / "data"
    server._apply_data_dir(str(data_dir))
    styly_mdm.create_app()
    assert data_dir.is_dir()
    assert (data_dir / "apks").is_dir()


def test_web_console_is_bundled():
    static_index = Path(server.__file__).parent / "static" / "index.html"
    assert static_index.is_file(), "web console index.html must ship inside the package"


def test_web_console_renders_provisional_identity_as_escaped_status_only():
    html = (Path(server.__file__).parent / "static" / "index.html").read_text()
    assert "PROVISIONAL_CONNECTION_LIST" in html
    assert "Device ID unavailable" in html
    assert "esc(entry.diagnostic" in html
    assert "provisionalConnections.map" in html


@pytest.mark.parametrize(
    "raw, expected",
    [
        ("app.apk", "app.apk"),
        ("../../etc/passwd.apk", "passwd.apk"),  # path components stripped
        ("my app (v2).apk", "my_app__v2_.apk"),  # unsafe chars replaced
        ("notanapk.txt", None),
        ("", None),
        (None, None),
        (".apk", None),
    ],
)
def test_sanitize_apk_filename(raw, expected):
    assert server.sanitize_apk_filename(raw) == expected


def test_unique_apk_path_avoids_collision(tmp_path):
    server._apply_data_dir(str(tmp_path))
    (tmp_path / "apks").mkdir(parents=True, exist_ok=True)

    first = server.unique_apk_path("app.apk")
    assert first == server.APK_DIR / "app.apk"

    first.write_bytes(b"x")  # occupy the name
    second = server.unique_apk_path("app.apk")
    assert second != first
    assert second.name.startswith("app-")
    assert second.suffix == ".apk"


def test_coerce_battery_accepts_valid_optional_state():
    assert server._coerce_battery(None) is None
    assert server._coerce_battery({"level": 51, "charging": False, "last_seen": 123}) == {
        "level": 51,
        "charging": False,
        "last_seen": 123,
    }
    assert server._coerce_battery({"level": 100.0, "charging": True, "timestamp": 456}) == {
        "level": 100,
        "charging": True,
        "last_seen": 456,
    }


@pytest.mark.parametrize(
    "value",
    [
        {"level": -1, "charging": False},
        {"level": 101, "charging": False},
        {"level": True, "charging": False},
        {"level": 50, "charging": "yes"},
        "50%",
    ],
)
def test_coerce_battery_rejects_invalid_state(value):
    assert server._coerce_battery(value) is None


def test_build_device_list_includes_online_and_offline_battery(tmp_path):
    server._apply_data_dir(str(tmp_path))
    server.devices.clear()
    server.device_registry.clear()

    server.device_registry["online-1"] = {
        "label": "DEV-001",
        "model": "PICO 4E",
        "ip": "192.168.1.11",
        "last_seen": 100,
        "startup_app": None,
        "battery": {"level": 87, "charging": True, "last_seen": 101},
    }
    server.device_registry["offline-1"] = {
        "label": "DEV-002",
        "model": "PICO 4E",
        "ip": "192.168.1.12",
        "last_seen": 90,
        "startup_app": None,
        "battery": {"level": 18, "charging": False, "last_seen": 91},
    }
    server.devices["online-1"] = {
        "ws": object(),
        "device_id": "online-1",
        "model": "PICO 4E",
        "ip": "192.168.1.11",
        "status": "online",
        "startup_app": None,
        "battery": {"level": 87, "charging": True, "last_seen": 101},
    }

    payload = json.loads(server.build_device_list_msg())
    rows = {row["device_id"]: row for row in payload["devices"]}

    assert rows["online-1"]["status"] == "online"
    assert rows["online-1"]["battery"] == {"level": 87, "charging": True, "last_seen": 101}
    assert rows["offline-1"]["status"] == "offline"
    assert rows["offline-1"]["battery"] == {"level": 18, "charging": False, "last_seen": 91}
