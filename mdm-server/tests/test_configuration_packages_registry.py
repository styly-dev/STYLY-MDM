"""Persistence, migration and atomic membership for configuration packages."""

import asyncio
import json

import pytest

from styly_mdm import configuration_packages, server


class _AdminSocket:
    def __init__(self):
        self.messages = []

    async def send_str(self, message):
        self.messages.append(json.loads(message))


def _write_registry(tmp_path, **fields):
    server._apply_data_dir(str(tmp_path))
    server.devices.clear()
    server.REGISTRY_PATH.write_text(json.dumps({"devices": {}, "groups": {}, **fields}))


def _legacy_profile():
    return {
        "draft": configuration_packages.new_snapshot({"brightness": {"managed": True, "value": 70}}),
        "deployed": configuration_packages.new_snapshot({"brightness": 45}),
    }


def test_migration_preserves_revisions_and_resolves_legacy_overlaps(tmp_path, caplog):
    profile = _legacy_profile()
    orphan = _legacy_profile()
    _write_registry(
        tmp_path,
        devices={"device-a": {"label": "Example", "other_field": {"preserve": True}}},
        groups={"Beta": ["device-a", "device-b"], "Alpha": ["device-a", "device-a"], "Gamma": []},
        group_settings={"version": 1, "profiles": {"Alpha": profile, "Deleted": orphan}},
        wifi_profiles={"discard": "legacy"}, wifi_profiles_encrypted="discard-legacy",
        another_section={"preserve": True},
    )
    server.load_registry()

    registry = json.loads(server.REGISTRY_PATH.read_text())
    alpha_id = registry["groups"]["Alpha"]["package_id"]
    packages = registry["packages"]["packages"]
    assert registry["groups"] == {
        "Alpha": {"members": ["device-a"], "package_id": alpha_id},
        "Beta": {"members": ["device-b"], "package_id": None},
        "Gamma": {"members": [], "package_id": None},
    }
    assert packages[alpha_id] == {"name": "Alpha", "draft": profile["draft"], "published": profile["deployed"]}
    assert [value for key, value in packages.items() if key != alpha_id] == [
        {"name": "Deleted", "draft": orphan["draft"], "published": orphan["deployed"]},
    ]
    assert server._desired_configuration("device-a") == {"package_id": alpha_id, **profile["deployed"]}
    assert "kept 'Alpha', removed from 'Beta'" in caplog.text
    assert not {"group_settings", "wifi_profiles", "wifi_profiles_encrypted"} & registry.keys()
    assert registry["another_section"] == {"preserve": True}
    assert registry["devices"]["device-a"]["other_field"] == {"preserve": True}
    assert server.REGISTRY_PATH.stat().st_mode & 0o777 == 0o600

    before_bytes = server.REGISTRY_PATH.read_bytes()
    server.load_registry()
    assert server.REGISTRY_PATH.read_bytes() == before_bytes
    assert server._configuration.packages == packages


def test_group_rename_forget_and_delete_persist_references_and_keep_package(tmp_path, monkeypatch):
    async def body():
        _write_registry(
            tmp_path, devices={"device-a": {"label": "Example"}}, groups={"Alpha": ["device-a"], "Beta": []},
            group_settings={"version": 1, "profiles": {"Alpha": _legacy_profile()}},
        )
        server.load_registry()
        package_id = json.loads(server.build_group_list_msg())["assignments"]["Alpha"]
        packages = server._configuration.packages
        admin = _AdminSocket()
        replace_calls = []
        original_replace = server.Path.replace

        def count_registry_replace(path, target):
            if target == server.REGISTRY_PATH:
                replace_calls.append(target)
            return original_replace(path, target)

        monkeypatch.setattr(server.Path, "replace", count_registry_replace)
        await server.handle_rename_group(admin, {"name": "Alpha", "new_name": "Gamma"})
        assert admin.messages[-1]["type"] == "GROUP_RENAMED"
        assert len(replace_calls) == 1
        server.load_registry()
        assert server._configuration.packages == packages
        assert json.loads(server.build_group_list_msg())["assignments"] == {"Gamma": package_id}
        assert json.loads(server.build_group_list_msg())["groups"] == {"Gamma": ["device-a"], "Beta": []}

        replace_calls.clear()
        await server.handle_forget_device(admin, {"device_id": "device-a"})
        assert admin.messages[-1]["type"] == "DEVICE_FORGOTTEN"
        assert len(replace_calls) == 1
        server.load_registry()
        assert "device-a" not in server.device_registry
        assert json.loads(server.build_group_list_msg())["groups"]["Gamma"] == []
        assert json.loads(server.build_group_list_msg())["assignments"]["Gamma"] == package_id

        replace_calls.clear()
        await server.handle_delete_group(admin, {"name": "Gamma"})
        assert admin.messages[-1]["type"] == "GROUP_DELETED"
        assert len(replace_calls) == 1
        server.load_registry()
        assert server._configuration.packages == packages
        assert json.loads(server.build_group_list_msg())["assignments"] == {}
        assert json.loads(server.build_group_list_msg())["groups"] == {"Beta": []}

    asyncio.run(body())


def test_failed_writes_keep_committed_device_and_configuration_state(tmp_path, monkeypatch):
    async def body():
        _write_registry(
            tmp_path, devices={"device-a": {"label": "Example"}}, groups={"Old": ["device-a"], "New": []},
            group_settings={"version": 1, "profiles": {"Old": _legacy_profile()}},
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
    ("group_settings", {"version": 99, "profiles": {}}, {"Alpha": ["same"]}),
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


def test_failed_migration_does_not_expose_unpersisted_ids(tmp_path, monkeypatch):
    legacy = {"version": 1, "profiles": {"Alpha": _legacy_profile()}}
    _write_registry(tmp_path, groups={"Alpha": ["device-a"]}, group_settings=legacy,
                    wifi_profiles={"prototype": "retained until migration succeeds"})
    before_bytes = server.REGISTRY_PATH.read_bytes()
    with monkeypatch.context() as patch:
        patch.setattr(server, "_write_registry", lambda *_: False)
        server.load_registry()
        assert server._configuration.packages is None
        assert json.loads(server.build_group_list_msg())["assignments"] == {}
        assert json.loads(server.build_group_list_msg())["packages"] == []
        assert server.REGISTRY_PATH.read_bytes() == before_bytes

    server.device_registry["another"] = {"label": "Example"}
    assert server.save_registry()
    saved = json.loads(server.REGISTRY_PATH.read_text())
    assert saved["group_settings"] == legacy
    assert saved["wifi_profiles"] == {"prototype": "retained until migration succeeds"}
    server.load_registry()
    assert "wifi_profiles" not in json.loads(server.REGISTRY_PATH.read_text())
    assert json.loads(server.build_group_list_msg())["assignments"]["Alpha"] in server._configuration.packages


def test_unreadable_registry_cannot_be_overwritten(tmp_path):
    _write_registry(tmp_path)
    server.REGISTRY_PATH.write_text('{"devices":')
    server.load_registry()
    server.device_registry["another"] = {"label": "Example"}
    assert not server.save_registry()
    assert server.REGISTRY_PATH.read_text() == '{"devices":'


def test_group_name_round_trip_and_upgrade_keep_membership_and_assignment(tmp_path):
    async def body():
        group = "a" * 63
        legacy_name = group + " "
        package_id = configuration_packages.new_snapshot({})["revision"]
        profile = {"name": "Office", "published": configuration_packages.new_snapshot({"brightness": 45})}
        _write_registry(
            tmp_path,
            groups={legacy_name: {"members": ["device-a"], "package_id": package_id}},
            packages={"version": 1, "packages": {package_id: profile}},
        )
        server.load_registry()
        saved = json.loads(server.REGISTRY_PATH.read_text())
        assert saved["groups"] == {group: {"members": ["device-a"], "package_id": package_id}}
        assert server._desired_configuration("device-a") == {"package_id": package_id, **profile["published"]}

        admin = _AdminSocket()
        other = "b" * 63
        await server.handle_create_group(admin, {"name": other + " z"})
        assert admin.messages[-1] == {"type": "GROUP_CREATED", "name": other}
        server.load_registry()
        assert server._configuration.packages == {package_id: profile}
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
    ("brightness", 0),
    ("sleepDelay", 15),
    ("sleepDelay", 30),
    ("sleepDelay", 60),
    ("screenOff", 3),
    ("screenOff", 10),
])
def test_inactive_legacy_draft_loads_beside_valid_package_without_weakening_new_writes(
    tmp_path, key, value,
):
    legacy_id = configuration_packages.new_snapshot({})["revision"]
    valid_id = configuration_packages.new_snapshot({})["revision"]
    legacy_draft = configuration_packages.new_snapshot({
        key: {"managed": False, "value": value},
    })
    valid_draft = configuration_packages.new_snapshot(configuration_packages.validate_draft({
        "brightness": {"managed": True, "value": 45},
    }))
    valid_published = configuration_packages.new_snapshot({"brightness": 45})
    packages = {
        "version": 1,
        "packages": {
            legacy_id: {"name": "Legacy", "draft": legacy_draft},
            valid_id: {"name": "Current", "draft": valid_draft, "published": valid_published},
        },
    }
    _write_registry(tmp_path, packages=packages)

    server.load_registry()

    assert server._configuration.packages == packages["packages"]
    assert server._configuration.packages[legacy_id]["draft"] == legacy_draft
    assert configuration_packages.has_legacy_values(legacy_draft["settings"]) is False
    assert "publication_requires_review" not in configuration_packages.public_summary(
        legacy_id, server._configuration.packages[legacy_id],
    )
    assert "publication_requires_review" not in configuration_packages.public_summary(
        valid_id, server._configuration.packages[valid_id],
    )
    with pytest.raises(configuration_packages.PackageError, match="Invalid value"):
        configuration_packages.validate_draft({key: {"managed": True, "value": value}})


@pytest.mark.parametrize(("key", "value"), [
    ("brightness", 0),
    ("sleepDelay", 15),
    ("sleepDelay", 30),
    ("sleepDelay", 60),
    ("screenOff", 3),
    ("screenOff", 10),
])
def test_migration_and_package_load_preserve_legacy_publication_for_review(key, value):
    draft = configuration_packages.new_snapshot({
        key: {"managed": False, "value": value},
    })
    deployed = configuration_packages.new_snapshot({key: value})
    legacy = {"version": 1, "profiles": {"Alpha": {"draft": draft, "deployed": deployed}}}

    migrated, assignments = configuration_packages.migrate_group_settings(legacy)
    assert list(assignments) == ["Alpha"]
    assigned_id = assignments["Alpha"]
    assert migrated[assigned_id] == {
        "name": "Alpha", "draft": draft, "published": deployed,
    }
    loaded = configuration_packages.load_packages({"version": 1, "packages": migrated})
    assert loaded[assigned_id]["draft"] == draft
    assert loaded[assigned_id]["draft"]["revision"] == draft["revision"]
    assert loaded[assigned_id]["published"] == deployed
    assert loaded[assigned_id]["published"]["revision"] == deployed["revision"]
    assert configuration_packages.has_legacy_values(deployed["settings"])
    assert configuration_packages.public_summary(
        assigned_id, loaded[assigned_id],
    )["publication_requires_review"] is True
    assert configuration_packages.has_legacy_values({"brightness": False}) is False
    assert configuration_packages.has_legacy_values({"sleepDelay": 15.0}) is False
    with pytest.raises(configuration_packages.PackageError, match="Invalid value"):
        configuration_packages.validate_draft({key: {"managed": True, "value": value}})


@pytest.mark.parametrize(("key", "value"), [
    ("brightness", -1),
    ("brightness", False),
    ("sleepDelay", 15.0),
    ("screenOff", 31),
    ("unknown", 0),
])
def test_package_load_still_rejects_corrupt_or_unrecognized_saved_scalars(key, value):
    package_id = configuration_packages.new_snapshot({})["revision"]
    published = configuration_packages.new_snapshot({key: value})
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

    old_snapshot = configuration_packages.new_snapshot({"brightness": 45})
    package_id = configuration_packages.new_snapshot({})["revision"]
    assert configuration_packages.load_packages({"version": 1, "packages": {
        package_id: {"name": "Legacy", "published": old_snapshot},
    }}) == {package_id: {"name": "Legacy", "published": old_snapshot}}

    old_wifi_snapshot = configuration_packages.new_snapshot({
        "wifi": [{"ssid": "Old Office", "security": "WPA_PSK",
                  "password": "test-password", "hidden": False}],
        "wifiPriority": False,
    })
    with pytest.raises(configuration_packages.PackageError):
        configuration_packages.load_packages({"version": 1, "packages": {
            package_id: {"name": "Old Wi-Fi shape", "published": old_wifi_snapshot},
        }})


def test_registry_recovers_after_retired_wifi_entries_are_removed_without_losing_assignment(tmp_path):
    package_id = configuration_packages.new_snapshot({})["revision"]
    old_networks = [{
        "ssid": "Old Office", "security": "WPA_PSK", "password": "test-password", "hidden": False,
    }]
    profile = {
        "name": "Office",
        "draft": configuration_packages.new_snapshot({
            "brightness": {"managed": True, "value": 45},
            "wifi": {"managed": True, "value": old_networks},
            "wifiPriority": {"managed": True, "value": False},
        }),
        "published": configuration_packages.new_snapshot({
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

    assert server._configuration.packages == {package_id: saved_profile}
    assert json.loads(server.build_group_list_msg())["assignments"] == {"Studio": package_id}
    assert server._desired_configuration("device-a") == {
        "package_id": package_id, **saved_profile["published"],
    }
    assert server._desired_configuration("device-a")["settings"] == {"brightness": 45}

def test_wifi_public_draft_redacts_password_and_keeps_nested_policy():
    draft = configuration_packages.new_snapshot(configuration_packages.validate_draft({
        "wifi": {"managed": True, "value": {
            "networks": [
                {"ssid": "Hidden Office", "security": "WPA2_WPA3", "password": "test-password", "hidden": True},
            ],
            "auto_return": False,
        }},
    }))
    profile = {"name": "Office", "draft": draft}

    public = configuration_packages.public_profile(
        configuration_packages.new_snapshot({})["revision"], profile, {},
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

    package_id = configuration_packages.new_snapshot({})["revision"]
    for auto_return in (False, True):
        priority_published = configuration_packages.new_snapshot({"wifi": {
            "networks": networks, "auto_return": auto_return,
        }})
        with pytest.raises(configuration_packages.PackageError, match="at most 4"):
            configuration_packages.load_packages({"version": 1, "packages": {
                package_id: {"name": "Too many networks", "published": priority_published},
            }})
