"""Persistence, migration and atomic membership for configuration packages."""

import asyncio
import json
import os
import uuid

import pytest

from styly_mdm import configuration_packages, server
from server_test_helpers import reset_server_state


class _AdminSocket:
    def __init__(self):
        self.messages = []

    async def send_str(self, message):
        self.messages.append(json.loads(message))


def _write_registry(tmp_path, **fields):
    server._apply_data_dir(str(tmp_path))
    server.devices.clear()
    server.REGISTRY_PATH.write_text(json.dumps({"devices": {}, "groups": {}, **fields}))


def _profile(name):
    return {
        "name": name,
        "draft": _legacy_snapshot({"brightness": {"managed": True, "value": 45}}),
        "published": _legacy_snapshot({"brightness": 45}),
    }


def _legacy_snapshot(settings, *, deployed_at_ms=None):
    snapshot = {"revision": str(uuid.uuid4()), "settings": settings}
    if deployed_at_ms is not None:
        snapshot["deployed_at_ms"] = deployed_at_ms
    return snapshot


def _write_package_registry(tmp_path, groups, *, devices=None):
    """Write the package format; groups map a name to (members, package name or None)."""
    packages = {}
    entries = {}
    for name, (members, package_name) in groups.items():
        package_id = None
        if package_name is not None:
            package_id = str(uuid.uuid4())
            packages[package_id] = _profile(package_name)
        entries[name] = {"members": members, "package_id": package_id}
    _write_registry(tmp_path, devices=devices or {}, groups=entries,
                    packages={"version": 1, "packages": packages})


def _backups(tmp_path):
    return sorted(tmp_path.glob(server.REGISTRY_PATH.name + ".*.bak"))


def test_released_groups_migrate_once_with_backup_and_one_group_per_device(tmp_path, caplog):
    _write_registry(
        tmp_path,
        devices={"device-a": {"label": "Example", "other_field": {"preserve": True}}},
        groups={"Beta": ["device-a", "device-b"], "Alpha": ["device-a", "device-a"], "Gamma": []},
        another_section={"preserve": True},
    )
    original = server.REGISTRY_PATH.read_bytes()
    server.load_registry()

    registry = json.loads(server.REGISTRY_PATH.read_text())
    assert registry["groups"] == {
        "Alpha": {"members": ["device-a"], "package_id": None},
        "Beta": {"members": ["device-b"], "package_id": None},
        "Gamma": {"members": [], "package_id": None},
    }
    assert registry["packages"] == {"version": configuration_packages.VERSION, "packages": {}}
    assert "kept 'Alpha', removed from 'Beta'" in caplog.text
    assert registry["another_section"] == {"preserve": True}
    assert registry["devices"]["device-a"]["other_field"] == {"preserve": True}

    [backup] = _backups(tmp_path)
    assert backup.read_bytes() == original
    assert str(backup) in caplog.text
    if os.name != "nt":
        assert server.REGISTRY_PATH.stat().st_mode & 0o777 == 0o600
        assert backup.stat().st_mode & 0o777 == 0o600

    before_bytes = server.REGISTRY_PATH.read_bytes()
    server.load_registry()
    assert server.REGISTRY_PATH.read_bytes() == before_bytes
    assert _backups(tmp_path) == [backup]


def test_v1_package_migration_preserves_editor_and_publication_without_duplicate_values(tmp_path):
    package_id = str(uuid.uuid4())
    deployed_at_ms = 1_800_000_000_000
    draft_revision = str(uuid.uuid4())
    published_revision = str(uuid.uuid4())
    settings = {
        "brightness": {"managed": True, "value": 45},
        "screenOff": {"managed": False, "value": 300},
    }
    profile = {
        "name": "Office",
        "draft": {"revision": draft_revision, "settings": settings},
        "published": {
            "revision": published_revision,
            "settings": {"brightness": 45},
            "deployed_at_ms": deployed_at_ms,
        },
    }
    groups = {"Studio": {"members": ["device-a"], "package_id": package_id}}
    _write_registry(
        tmp_path,
        groups=groups,
        packages={"version": 1, "packages": {package_id: profile}},
    )
    original = server.REGISTRY_PATH.read_bytes()

    server.load_registry()

    expected_profile = {
        "name": "Office",
        "settings": settings,
        "draft_revision": draft_revision,
        "published_revision": published_revision,
        "deployed_at_ms": deployed_at_ms,
    }
    saved = json.loads(server.REGISTRY_PATH.read_text())
    assert saved["packages"] == {"version": 2, "packages": {package_id: expected_profile}}
    assert server._configuration.packages == {package_id: expected_profile}
    assert server._desired_configuration("device-a") == {
        "package_id": package_id,
        "revision": published_revision,
        "settings": {"brightness": 45},
    }
    [backup] = _backups(tmp_path)
    assert backup.read_bytes() == original

    migrated = server.REGISTRY_PATH.read_bytes()
    server.load_registry()
    assert server.REGISTRY_PATH.read_bytes() == migrated
    assert _backups(tmp_path) == [backup]


def test_v1_divergent_draft_fails_closed_and_preserves_original_registry(tmp_path):
    package_id = str(uuid.uuid4())
    profile = {
        "name": "Office",
        "draft": _legacy_snapshot({"brightness": {"managed": True, "value": 70}}),
        "published": _legacy_snapshot({"brightness": 45}),
    }
    _write_registry(
        tmp_path,
        groups={"Studio": {"members": ["device-a"], "package_id": package_id}},
        packages={"version": 1, "packages": {package_id: profile}},
    )
    original = server.REGISTRY_PATH.read_bytes()

    server.load_registry()

    assert server._configuration.packages is None
    assert server.REGISTRY_PATH.read_bytes() == original
    assert json.loads(server.REGISTRY_PATH.read_text())["packages"]["version"] == 1
    assert _backups(tmp_path) == []


def test_migration_is_not_written_when_the_backup_fails(tmp_path, monkeypatch):
    _write_registry(tmp_path, groups={"Alpha": ["device-a"], "Beta": ["device-a"]})
    before_bytes = server.REGISTRY_PATH.read_bytes()

    def fail_backup():
        raise configuration_packages.PackageError("Registry backup could not be saved")

    monkeypatch.setattr(server, "_backup_registry", fail_backup)
    server.load_registry()
    assert server._configuration.packages is None
    assert server.REGISTRY_PATH.read_bytes() == before_bytes


def test_group_rename_forget_and_delete_persist_references_and_keep_package(tmp_path, monkeypatch):
    async def body():
        _write_package_registry(
            tmp_path, {"Alpha": (["device-a"], "Alpha"), "Beta": ([], None)},
            devices={"device-a": {"label": "Example"}},
        )
        server.load_registry()
        package_id = json.loads(server.build_group_list_msg())["assignments"]["Alpha"]
        packages = server._configuration.packages
        admin = _AdminSocket()
        monkeypatch.setattr(server, "_admin_send_locks", {})
        server.admin_connections.add(admin)
        replace_calls = []
        original_replace = server.Path.replace

        def count_registry_replace(path, target):
            if target == server.REGISTRY_PATH:
                replace_calls.append(target)
            return original_replace(path, target)

        monkeypatch.setattr(server.Path, "replace", count_registry_replace)
        await server.handle_rename_group(admin, {"name": "Alpha", "new_name": "Gamma"})
        assert [message["type"] for message in admin.messages] == ["GROUP_RENAMED", "GROUP_LIST"]
        assert len(replace_calls) == 1
        server.load_registry()
        assert server._configuration.packages == packages
        assert json.loads(server.build_group_list_msg())["assignments"] == {"Gamma": package_id}
        assert json.loads(server.build_group_list_msg())["groups"] == {"Gamma": ["device-a"], "Beta": []}

        replace_calls.clear()
        admin.messages.clear()
        await server.handle_forget_device(admin, {"device_id": "device-a"})
        assert admin.messages[0]["type"] == "DEVICE_FORGOTTEN"
        assert len(replace_calls) == 1
        server.load_registry()
        assert "device-a" not in server.device_registry
        assert json.loads(server.build_group_list_msg())["groups"]["Gamma"] == []
        assert json.loads(server.build_group_list_msg())["assignments"]["Gamma"] == package_id

        replace_calls.clear()
        admin.messages.clear()
        await server.handle_delete_group(admin, {"name": "Gamma"})
        assert [message["type"] for message in admin.messages] == ["GROUP_DELETED", "GROUP_LIST"]
        assert len(replace_calls) == 1
        server.load_registry()
        assert server._configuration.packages == packages
        assert json.loads(server.build_group_list_msg())["assignments"] == {}
        assert json.loads(server.build_group_list_msg())["groups"] == {"Beta": []}

    asyncio.run(body())


def test_failed_writes_keep_committed_device_and_configuration_state(tmp_path, monkeypatch):
    async def body():
        _write_package_registry(
            tmp_path, {"Old": (["device-a"], "Old"), "New": ([], None)},
            devices={"device-a": {"label": "Example"}},
        )
        server.load_registry()
        package_id = json.loads(server.build_group_list_msg())["assignments"]["Old"]
        committed = server._configuration
        before_bytes = server.REGISTRY_PATH.read_bytes()
        monkeypatch.setattr(server, "_write_registry", lambda *_: False)
        admin = _AdminSocket()

        with pytest.raises(configuration_packages.PackageError):
            server._save_package(package_id, {"name": "Replacement"})
        await server.handle_rename_group(admin, {"name": "Old", "new_name": "Renamed"})
        await server.handle_delete_group(admin, {"name": "Old"})
        await server.handle_update_group_members(
            admin, {"name": "New", "add": {"device-a": "Old"}, "remove": []},
        )
        await server.handle_forget_device(admin, {"device_id": "device-a"})
        assert [message["type"] for message in admin.messages] == ["ERROR"] * 4
        assert admin.messages[2]["operation"] == "UPDATE_GROUP_MEMBERS"
        assert server._configuration is committed
        assert server.device_registry["device-a"]["label"] == "Example"
        assert server.REGISTRY_PATH.read_bytes() == before_bytes

    asyncio.run(body())


@pytest.mark.parametrize(("field", "opaque", "groups"), [
    ("packages", {"version": 99, "packages": {}}, {"Alpha": {"members": ["same"], "package_id": None}}),
    ("packages", {"version": 1, "packages": {}}, {"Alpha": {"members": ["same"], "package_id": "invalid"}}),
])
def test_unreadable_configuration_survives_unrelated_write(tmp_path, field, opaque, groups):
    _write_registry(tmp_path, groups=groups, **{field: opaque})
    server.load_registry()
    assert server._configuration.packages is None
    assert json.loads(server.build_group_list_msg())["groups"] == {"Alpha": ["same"]}
    server.device_registry["another"] = {"label": "Example"}
    assert server.save_registry()
    saved = json.loads(server.REGISTRY_PATH.read_text())
    assert saved[field] == opaque
    assert saved["groups"] == groups


def test_failed_migration_keeps_released_groups_until_it_can_be_saved(tmp_path, monkeypatch):
    _write_registry(tmp_path, groups={"Alpha": ["device-a"]})
    before_bytes = server.REGISTRY_PATH.read_bytes()
    with monkeypatch.context() as patch:
        patch.setattr(server, "_write_registry", lambda *_: False)
        server.load_registry()
        assert server._configuration.packages is None
        assert json.loads(server.build_group_list_msg())["groups"] == {"Alpha": ["device-a"]}
        assert json.loads(server.build_group_list_msg())["packages"] == []
        assert server.REGISTRY_PATH.read_bytes() == before_bytes

    server.device_registry["another"] = {"label": "Example"}
    assert server.save_registry()
    assert json.loads(server.REGISTRY_PATH.read_text())["groups"] == {"Alpha": ["device-a"]}
    server.load_registry()
    assert json.loads(server.REGISTRY_PATH.read_text())["groups"] == {
        "Alpha": {"members": ["device-a"], "package_id": None},
    }
    assert server._configuration.packages == {}


def test_unreadable_registry_cannot_be_overwritten_and_is_reported_to_consoles(tmp_path):
    _write_registry(tmp_path)
    assert json.loads(server.build_group_list_msg())["registry_writable"] is True
    server.REGISTRY_PATH.write_text('{"devices":')
    server.load_registry()
    server.device_registry["another"] = {"label": "Example"}
    assert not server.save_registry()
    assert server.REGISTRY_PATH.read_text() == '{"devices":'
    assert json.loads(server.build_group_list_msg())["registry_writable"] is False


def test_group_name_round_trip_and_upgrade_keep_membership_and_assignment(tmp_path):
    async def body():
        group = "a" * 63
        legacy_name = group + " "
        package_id = str(uuid.uuid4())
        published = _legacy_snapshot({"brightness": 45})
        profile = {"name": "Office", "published": published}
        _write_registry(
            tmp_path,
            groups={legacy_name: {"members": ["device-a"], "package_id": package_id}},
            packages={"version": 1, "packages": {package_id: profile}},
        )
        server.load_registry()
        saved = json.loads(server.REGISTRY_PATH.read_text())
        assert saved["groups"] == {group: {"members": ["device-a"], "package_id": package_id}}
        assert server._desired_configuration("device-a") == {
            "package_id": package_id, "revision": published["revision"], "settings": {"brightness": 45},
        }

        admin = _AdminSocket()
        other = "b" * 63
        await server.handle_create_group(admin, {"name": other + " z"})
        assert admin.messages[-1] == {"type": "GROUP_CREATED", "name": other}
        server.load_registry()
        assert server._configuration.packages == {package_id: {
            "name": "Office",
            "settings": {"brightness": {"managed": True, "value": 45}},
            "draft_revision": published["revision"],
            "published_revision": published["revision"],
            "deployed_at_ms": None,
        }}
        assert json.loads(server.build_group_list_msg())["groups"] == {group: ["device-a"], other: []}

    asyncio.run(body())


def test_conflicting_normalized_group_names_are_preserved(tmp_path):
    groups = {
        "Studio": {"members": ["device-a"], "package_id": None},
        "Studio ": {"members": ["device-b"], "package_id": None},
    }
    _write_registry(tmp_path, groups=groups, packages={"version": 1, "packages": {}})
    before_bytes = server.REGISTRY_PATH.read_bytes()
    server.load_registry()
    assert server._configuration.packages is None
    assert server.REGISTRY_PATH.read_bytes() == before_bytes
    server.device_registry["another"] = {"label": "Example"}
    assert server.save_registry()
    assert json.loads(server.REGISTRY_PATH.read_text())["groups"] == groups


@pytest.mark.parametrize(("key", "value"), [
    ("brightness", -1),
    ("brightness", 0),
    ("brightness", False),
    ("sleepDelay", 15),
    ("sleepDelay", 15.0),
    ("screenOff", 10),
    ("screenOff", 31),
    ("unknown", 0),
])
def test_package_load_still_rejects_corrupt_or_unrecognized_saved_scalars(key, value):
    package_id = str(uuid.uuid4())
    published = _legacy_snapshot({key: value})
    with pytest.raises(configuration_packages.PackageError):
        configuration_packages.load_packages({"version": 1, "packages": {
            package_id: {"name": "Corrupt", "published": published},
        }})


def test_wifi_uses_one_value_with_order_auto_return_and_managed_empty_rules():
    settings = {
        "wifi": {"managed": True, "value": {
            "networks": [
                {"ssid": "Hidden Office", "security": "WPA2_WPA3", "password": "test-password", "hidden": True},
                {"ssid": "Guest", "security": "OPEN", "password": "", "hidden": False},
            ],
            "auto_return": False,
        }},
    }
    validated = configuration_packages.validate_draft(settings)
    assert validated == settings
    assert list(configuration_packages.managed_settings(validated)) == ["wifi"]

    with pytest.raises(configuration_packages.PackageError, match="auto_return must be a boolean"):
        configuration_packages.validate_draft({
            "wifi": {"managed": True, "value": {"networks": [], "auto_return": 1}},
        })
    with pytest.raises(configuration_packages.PackageError, match="not supported with hidden networks"):
        configuration_packages.validate_draft({
            "wifi": {"managed": True, "value": {
                "networks": settings["wifi"]["value"]["networks"], "auto_return": True,
            }},
        })
    with pytest.raises(configuration_packages.PackageError, match="unsupported item"):
        configuration_packages.validate_draft({
            **settings, "wifiPriority": {"managed": True, "value": False},
        })
    with pytest.raises(configuration_packages.PackageError, match="non-empty list"):
        configuration_packages.validate_draft({
            "wifi": {"managed": True, "value": {"networks": [], "auto_return": False}},
        })
    assert configuration_packages.validate_draft({
        "wifi": {"managed": False, "value": {"networks": [], "auto_return": False}},
    })["wifi"]["value"] == {"networks": [], "auto_return": False}
    unmanaged_hidden = configuration_packages.validate_draft({
        "wifi": {"managed": False, "value": {
            "networks": settings["wifi"]["value"]["networks"], "auto_return": True,
        }},
    })
    assert unmanaged_hidden["wifi"]["value"]["auto_return"] is True

    old_snapshot = _legacy_snapshot({"brightness": 45})
    package_id = str(uuid.uuid4())
    migrated = {
        "name": "Legacy",
        "settings": {"brightness": {"managed": True, "value": 45}},
        "draft_revision": old_snapshot["revision"],
        "published_revision": old_snapshot["revision"],
        "deployed_at_ms": None,
    }
    assert configuration_packages.load_packages({"version": 1, "packages": {
        package_id: {"name": "Legacy", "published": old_snapshot},
    }}) == {package_id: migrated}

    draft_only = _legacy_snapshot({"brightness": {"managed": True, "value": 55}})
    assert configuration_packages.load_packages({"version": 1, "packages": {
        package_id: {"name": "Unpublished", "draft": draft_only},
    }}) == {package_id: {
        "name": "Unpublished",
        "settings": draft_only["settings"],
        "draft_revision": draft_only["revision"],
        "published_revision": None,
        "deployed_at_ms": None,
    }}

    old_wifi_snapshot = _legacy_snapshot({
        "wifi": [{"ssid": "Old Office", "security": "WPA_PSK",
                  "password": "test-password", "hidden": False}],
        "wifiPriority": False,
    })
    with pytest.raises(configuration_packages.PackageError):
        configuration_packages.load_packages({"version": 1, "packages": {
            package_id: {"name": "Old Wi-Fi shape", "published": old_wifi_snapshot},
        }})


def test_registry_recovers_after_retired_wifi_entries_are_removed_without_losing_assignment(tmp_path):
    package_id = str(uuid.uuid4())
    old_networks = [{
        "ssid": "Old Office", "security": "WPA_PSK", "password": "test-password", "hidden": False,
    }]
    profile = {
        "name": "Office",
        "draft": _legacy_snapshot({
            "brightness": {"managed": True, "value": 45},
            "wifi": {"managed": True, "value": old_networks},
            "wifiPriority": {"managed": True, "value": False},
        }),
        "published": _legacy_snapshot({
            "brightness": 45, "wifi": old_networks, "wifiPriority": False,
        }),
    }
    groups = {"Studio": {"members": ["device-a"], "package_id": package_id}}
    catalog = {"version": 1, "packages": {package_id: profile}}
    _write_registry(
        tmp_path, devices={"device-a": {"label": "Example"}},
        groups=groups, packages=catalog,
    )
    before = server.REGISTRY_PATH.read_bytes()

    server.load_registry()
    assert server._configuration.packages is None
    assert server.REGISTRY_PATH.read_bytes() == before
    assert json.loads(server.REGISTRY_PATH.read_text())["groups"] == groups

    cleaned = json.loads(before)
    saved_profile = cleaned["packages"]["packages"][package_id]
    for phase in ("draft", "published"):
        settings = saved_profile[phase]["settings"]
        settings.pop("wifi", None)
        settings.pop("wifiPriority", None)
    server.REGISTRY_PATH.write_text(json.dumps(cleaned))
    server.load_registry()

    migrated_profile = {
        "name": saved_profile["name"],
        "settings": saved_profile["draft"]["settings"],
        "draft_revision": saved_profile["draft"]["revision"],
        "published_revision": saved_profile["published"]["revision"],
        "deployed_at_ms": None,
    }
    assert server._configuration.packages == {package_id: migrated_profile}
    assert json.loads(server.build_group_list_msg())["assignments"] == {"Studio": package_id}
    assert server._desired_configuration("device-a") == {
        "package_id": package_id, "revision": saved_profile["published"]["revision"],
        "settings": {"brightness": 45},
    }
    assert server._desired_configuration("device-a")["settings"] == {"brightness": 45}

def test_wifi_public_draft_redacts_password_and_keeps_nested_policy():
    draft = _legacy_snapshot(configuration_packages.validate_draft({
        "wifi": {"managed": True, "value": {
            "networks": [
                {"ssid": "Hidden Office", "security": "WPA2_WPA3", "password": "test-password", "hidden": True},
            ],
            "auto_return": False,
        }},
    }))
    profile = {
        "name": "Office", "settings": draft["settings"],
        "draft_revision": draft["revision"], "published_revision": None, "deployed_at_ms": None,
    }

    public = configuration_packages.public_profile(
        str(uuid.uuid4()), profile, {},
    )
    assert public["settings"]["wifi"]["value"] == {
        "networks": [{
            "ssid": "Hidden Office", "security": "WPA2_WPA3", "password_present": True, "hidden": True,
        }],
        "auto_return": False,
    }
    assert "test-password" not in json.dumps(public)


def test_wifi_caps_networks_at_four_in_managed_drafts_and_snapshots():
    networks = [
        {"ssid": f"Network {index}", "security": "OPEN", "password": "", "hidden": False}
        for index in range(1, 6)
    ]
    for auto_return in (False, True):
        with pytest.raises(configuration_packages.PackageError, match="at most 4"):
            configuration_packages.validate_draft({
                "wifi": {"managed": True, "value": {"networks": networks, "auto_return": auto_return}},
            })

    package_id = str(uuid.uuid4())
    for auto_return in (False, True):
        priority_published = _legacy_snapshot({"wifi": {
            "networks": networks, "auto_return": auto_return,
        }})
        with pytest.raises(configuration_packages.PackageError, match="at most 4"):
            configuration_packages.load_packages({"version": 1, "packages": {
                package_id: {"name": "Too many networks", "published": priority_published},
            }})
