"""Smoke tests for the styly_mdm control server.

No network is used: these exercise the app factory, verify the web console is
bundled with the package, and cover the pure filename helpers.
"""

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
