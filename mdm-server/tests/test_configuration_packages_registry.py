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
    revision = str(uuid.uuid4())
    return {
        "name": name,
        "settings": {"brightness": {"managed": True, "value": 45}},
        "edit_revision": revision,
        "published_revision": revision,
        "deployed_at_ms": None,
    }


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
                    packages={"version": configuration_packages.VERSION, "packages": packages})


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
    assert registry["configuration_revision"] == 0
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


def test_configuration_revision_is_durable_and_only_advances_for_configuration_changes(tmp_path):
    _write_package_registry(tmp_path, {"Studio": ([], None)})
    registry = json.loads(server.REGISTRY_PATH.read_text())
    registry["operator_metadata"] = {"preserve": True}
    server.REGISTRY_PATH.write_text(json.dumps(registry))
    server.load_registry()

    before = json.loads(server.build_group_list_msg())
    assert before["revision"] == 0
    candidate = server.replace(server._configuration, groups={
        **server._configuration.groups,
        "Gallery": server._DeviceGroup(),
    })
    assert server._commit_configuration(candidate)
    committed = json.loads(server.build_group_list_msg())
    assert committed["revision"] == before["revision"] + 1
    assert json.loads(server.REGISTRY_PATH.read_text())["configuration_revision"] == committed["revision"]

    # Device telemetry persistence and idempotent config writes do not advance order.
    assert server.save_registry()
    assert server._commit_configuration(server._configuration)
    server.load_registry()
    reloaded = json.loads(server.build_group_list_msg())
    assert reloaded["revision"] == committed["revision"]
    persisted = json.loads(server.REGISTRY_PATH.read_text())
    assert persisted["operator_metadata"] == {"preserve": True}


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


@pytest.mark.parametrize(("version", "package_id"), [(2, None), (99, "invalid")])
def test_unsupported_catalog_survives_load_and_unrelated_write(tmp_path, version, package_id):
    catalog = {"version": version, "packages": {}}
    groups = {"Alpha": {"members": ["same"], "package_id": package_id}}
    _write_registry(tmp_path, groups=groups, packages=catalog)
    original = server.REGISTRY_PATH.read_bytes()
    server.load_registry()
    assert server._configuration.packages is None
    assert server.REGISTRY_PATH.read_bytes() == original
    assert _backups(tmp_path) == []
    assert json.loads(server.build_group_list_msg())["groups"] == {"Alpha": ["same"]}
    server.device_registry["another"] = {"label": "Example"}
    assert server.save_registry()
    saved = json.loads(server.REGISTRY_PATH.read_text())
    assert saved["packages"] == catalog
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
        revision = str(uuid.uuid4())
        profile = {
            "name": "Office",
            "settings": {"brightness": {"managed": True, "value": 45}},
            "edit_revision": revision,
            "published_revision": revision,
            "deployed_at_ms": None,
        }
        _write_registry(
            tmp_path,
            groups={legacy_name: {"members": ["device-a"], "package_id": package_id}},
            packages={"version": configuration_packages.VERSION, "packages": {package_id: profile}},
        )
        server.load_registry()
        saved = json.loads(server.REGISTRY_PATH.read_text())
        assert saved["groups"] == {group: {"members": ["device-a"], "package_id": package_id}}
        assert server._desired_configuration("device-a") == {
            "package_id": package_id, "revision": revision, "settings": {"brightness": 45},
        }

        admin = _AdminSocket()
        server.admin_connections.add(admin)
        other = "b" * 63
        await server.handle_create_group(admin, {"name": other + " z"})
        assert admin.messages[0] == {"type": "GROUP_CREATED", "name": other}
        assert admin.messages[1]["type"] == "GROUP_LIST"
        server.load_registry()
        assert server._configuration.packages == {package_id: profile}
        assert json.loads(server.build_group_list_msg())["groups"] == {group: ["device-a"], other: []}

    asyncio.run(body())


def test_partial_configuration_registry_without_device_map_is_preserved(tmp_path):
    server._apply_data_dir(str(tmp_path))
    raw = {"groups": {"Studio": ["device-a"]}, "operator_metadata": {"keep": True}}
    server.REGISTRY_PATH.write_text(json.dumps(raw))
    before = server.REGISTRY_PATH.read_bytes()

    server.load_registry()

    assert server._registry_readable is False
    assert server._configuration.packages is None
    assert server._configuration.groups["Studio"].members == ("device-a",)
    assert server.device_registry == {}
    assert not server.save_registry()
    assert server.REGISTRY_PATH.read_bytes() == before


def test_reserved_legacy_device_id_with_string_label_is_not_a_registry_wrapper(tmp_path):
    server._apply_data_dir(str(tmp_path))
    server.REGISTRY_PATH.write_text(json.dumps({"groups": "lab"}))

    server.load_registry()

    assert server._registry_readable is True
    assert server.device_registry["groups"]["label"] == "lab"


def test_legacy_wrapped_device_map_without_configuration_metadata_is_migrated(tmp_path):
    server._apply_data_dir(str(tmp_path))
    server.REGISTRY_PATH.write_text(json.dumps({"devices": {"device-a": {"label": "Lab"}}}))

    server.load_registry()

    assert server._registry_readable is True
    assert server.device_registry["device-a"]["label"] == "Lab"
    assert "devices" not in server.device_registry


@pytest.mark.parametrize("ack_type", ["GROUP_CREATED", "DEVICE_LABEL_SET", "DEVICE_FORGOTTEN"])
def test_committed_change_broadcast_survives_requester_disconnect(tmp_path, monkeypatch, ack_type):
    class RecordingAdmin(_AdminSocket):
        async def close(self, **_kwargs):
            self.closed = True

    class DisconnectingAdmin(RecordingAdmin):
        def __init__(self):
            super().__init__()
            self.closed = False

        async def send_str(self, message):
            parsed = json.loads(message)
            if parsed["type"] == ack_type:
                raise ConnectionResetError("requesting console disconnected")
            await super().send_str(message)

    async def body():
        _write_registry(tmp_path, devices={"device-a": {"label": "Old"}}, groups={"Studio": ["device-a"]})
        server.load_registry()
        monkeypatch.setattr(server, "_admin_send_locks", {})
        requester = DisconnectingAdmin()
        peer = RecordingAdmin()
        server.admin_connections.update((requester, peer))

        if ack_type == "GROUP_CREATED":
            await server.handle_create_group(requester, {"name": "Gallery"})
            assert peer.messages[0]["groups"]["Gallery"] == []
        elif ack_type == "DEVICE_LABEL_SET":
            await server.handle_set_device_label(requester, {"device_id": "device-a", "label": "Lab"})
            assert peer.messages[0]["devices"][0]["label"] == "Lab"
        else:
            await server.handle_forget_device(requester, {"device_id": "device-a"})
            assert [message["type"] for message in peer.messages] == ["DEVICE_LIST", "GROUP_LIST"]
            assert peer.messages[0]["devices"] == []
            assert peer.messages[1]["groups"]["Studio"] == []
        assert requester.closed

    asyncio.run(body())


def test_conflicting_normalized_group_names_are_preserved(tmp_path):
    groups = {
        "Studio": {"members": ["device-a"], "package_id": None},
        "Studio ": {"members": ["device-b"], "package_id": None},
    }
    _write_registry(tmp_path, groups=groups, packages={"version": configuration_packages.VERSION, "packages": {}})
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
    revision = str(uuid.uuid4())
    with pytest.raises(configuration_packages.PackageError):
        configuration_packages.load_packages({"version": configuration_packages.VERSION, "packages": {
            package_id: {
                "name": "Corrupt",
                "settings": {key: {"managed": True, "value": value}},
                "edit_revision": revision,
                "published_revision": revision,
                "deployed_at_ms": None,
            },
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
    validated = configuration_packages.validate_settings(settings)
    assert validated == settings
    assert list(configuration_packages.managed_settings(validated)) == ["wifi"]

    # Missing and explicit true both preserve the original wire shape.
    prioritized = configuration_packages.validate_settings({
        "wifi": {"managed": True, "value": {
            "networks": settings["wifi"]["value"]["networks"],
            "auto_return": False,
            "prioritize_networks": True,
        }},
    })
    assert prioritized["wifi"]["value"] == settings["wifi"]["value"]

    android_selected = configuration_packages.validate_settings({
        "wifi": {"managed": True, "value": {
            "networks": settings["wifi"]["value"]["networks"],
            "auto_return": False,
            "prioritize_networks": False,
        }},
    })
    assert android_selected["wifi"]["value"] == {
        **settings["wifi"]["value"], "prioritize_networks": False,
    }

    with pytest.raises(configuration_packages.PackageError, match="auto_return must be a boolean"):
        configuration_packages.validate_settings({
            "wifi": {"managed": True, "value": {"networks": [], "auto_return": 1}},
        })
    with pytest.raises(configuration_packages.PackageError, match="prioritize_networks must be a boolean"):
        configuration_packages.validate_settings({
            "wifi": {"managed": True, "value": {
                "networks": [], "auto_return": False, "prioritize_networks": 0,
            }},
        })
    with pytest.raises(configuration_packages.PackageError, match="auto_return must be false"):
        configuration_packages.validate_settings({
            "wifi": {"managed": True, "value": {
                "networks": [], "auto_return": True, "prioritize_networks": False,
            }},
        })
    with pytest.raises(configuration_packages.PackageError, match="not supported with hidden networks"):
        configuration_packages.validate_settings({
            "wifi": {"managed": True, "value": {
                "networks": settings["wifi"]["value"]["networks"], "auto_return": True,
            }},
        })
    with pytest.raises(configuration_packages.PackageError, match="unsupported item"):
        configuration_packages.validate_settings({
            **settings, "wifiPriority": {"managed": True, "value": False},
        })
    with pytest.raises(configuration_packages.PackageError, match="non-empty list"):
        configuration_packages.validate_settings({
            "wifi": {"managed": True, "value": {"networks": [], "auto_return": False}},
        })
    assert configuration_packages.validate_settings({
        "wifi": {"managed": False, "value": {"networks": [], "auto_return": False}},
    })["wifi"]["value"] == {"networks": [], "auto_return": False}
    unmanaged_hidden = configuration_packages.validate_settings({
        "wifi": {"managed": False, "value": {
            "networks": settings["wifi"]["value"]["networks"], "auto_return": True,
        }},
    })
    assert unmanaged_hidden["wifi"]["value"]["auto_return"] is True

    package_id = str(uuid.uuid4())
    revision = str(uuid.uuid4())
    published = {
        "name": "Legacy",
        "settings": {"brightness": {"managed": True, "value": 45}},
        "edit_revision": revision,
        "published_revision": revision,
        "deployed_at_ms": None,
    }
    assert configuration_packages.load_packages({"version": configuration_packages.VERSION, "packages": {
        package_id: published,
    }}) == {package_id: published}

    draft_only = {
        "name": "Unpublished",
        "settings": {"brightness": {"managed": True, "value": 55}},
        "edit_revision": str(uuid.uuid4()),
        "published_revision": None,
        "deployed_at_ms": None,
    }
    assert configuration_packages.load_packages({"version": configuration_packages.VERSION, "packages": {
        package_id: draft_only,
    }}) == {package_id: draft_only}

    old_wifi = {
        "name": "Old Wi-Fi shape",
        "settings": {"wifi": {"managed": True, "value": [{
            "ssid": "Old Office", "security": "WPA_PSK",
            "password": "test-password", "hidden": False,
        }]}, "wifiPriority": {"managed": True, "value": False}},
        "edit_revision": str(uuid.uuid4()),
        "published_revision": None,
        "deployed_at_ms": None,
    }
    with pytest.raises(configuration_packages.PackageError):
        configuration_packages.load_packages({"version": configuration_packages.VERSION, "packages": {
            package_id: old_wifi,
        }})


def test_registry_recovers_after_retired_wifi_entries_are_removed_without_losing_assignment(tmp_path):
    package_id = str(uuid.uuid4())
    revision = str(uuid.uuid4())
    profile = {
        "name": "Office",
        "settings": {
            "brightness": {"managed": True, "value": 45},
            "wifi": {"managed": True, "value": [{
                "ssid": "Old Office", "security": "WPA_PSK", "password": "test-password", "hidden": False,
            }]},
            "wifiPriority": {"managed": True, "value": False},
        },
        "edit_revision": revision,
        "published_revision": revision,
        "deployed_at_ms": None,
    }
    groups = {"Studio": {"members": ["device-a"], "package_id": package_id}}
    catalog = {"version": configuration_packages.VERSION, "packages": {package_id: profile}}
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
    saved_profile["settings"].pop("wifi")
    saved_profile["settings"].pop("wifiPriority")
    server.REGISTRY_PATH.write_text(json.dumps(cleaned))
    server.load_registry()

    assert server._configuration.packages == {package_id: saved_profile}
    assert json.loads(server.build_group_list_msg())["assignments"] == {"Studio": package_id}
    assert server._desired_configuration("device-a") == {
        "package_id": package_id, "revision": saved_profile["published_revision"],
        "settings": {"brightness": 45},
    }

def test_wifi_public_draft_redacts_password_and_keeps_nested_policy():
    settings = configuration_packages.validate_settings({
        "wifi": {"managed": True, "value": {
            "networks": [
                {"ssid": "Hidden Office", "security": "WPA2_WPA3", "password": "test-password", "hidden": True},
            ],
            "auto_return": False,
        }},
    })
    profile = {
        "name": "Office", "settings": settings,
        "edit_revision": str(uuid.uuid4()), "published_revision": None, "deployed_at_ms": None,
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

    settings["wifi"]["value"]["prioritize_networks"] = False
    public = configuration_packages.public_profile(str(uuid.uuid4()), {
        **profile, "settings": settings,
    }, {})
    assert public["settings"]["wifi"]["value"]["prioritize_networks"] is False


def test_wifi_caps_networks_at_four_in_managed_drafts_and_snapshots():
    networks = [
        {"ssid": f"Network {index}", "security": "OPEN", "password": "", "hidden": False}
        for index in range(1, 6)
    ]
    for auto_return in (False, True):
        with pytest.raises(configuration_packages.PackageError, match="at most 4"):
            configuration_packages.validate_settings({
                "wifi": {"managed": True, "value": {"networks": networks, "auto_return": auto_return}},
            })

    package_id = str(uuid.uuid4())
    for auto_return in (False, True):
        revision = str(uuid.uuid4())
        with pytest.raises(configuration_packages.PackageError, match="at most 4"):
            configuration_packages.load_packages({"version": configuration_packages.VERSION, "packages": {
                package_id: {
                    "name": "Too many networks",
                    "settings": {"wifi": {"managed": True, "value": {
                        "networks": networks, "auto_return": auto_return,
                    }}},
                    "edit_revision": revision,
                    "published_revision": revision,
                    "deployed_at_ms": None,
                },
            }})
