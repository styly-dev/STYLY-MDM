"""Persistence, migration and atomic membership for configuration packages."""

import asyncio
import copy
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
    assert server._configuration_packages == packages


def test_group_rename_and_delete_persist_references_and_keep_package(tmp_path, monkeypatch):
    async def body():
        _write_registry(
            tmp_path, groups={"Alpha": ["device-a"], "Beta": []},
            group_settings={"version": 1, "profiles": {"Alpha": _legacy_profile()}},
        )
        server.load_registry()
        package_id = server._group_assignments["Alpha"]
        packages = copy.deepcopy(server._configuration_packages)
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
        assert server._configuration_packages == packages
        assert server._group_assignments == {"Gamma": package_id}
        assert server.device_groups == {"Gamma": ["device-a"], "Beta": []}

        replace_calls.clear()
        await server.handle_delete_group(admin, {"name": "Gamma"})
        assert admin.messages[-1]["type"] == "GROUP_DELETED"
        assert len(replace_calls) == 1
        server.load_registry()
        assert server._configuration_packages == packages
        assert server._group_assignments == {}
        assert server.device_groups == {"Beta": []}

    asyncio.run(body())


def test_failed_writes_restore_membership_references_and_package(tmp_path, monkeypatch):
    async def body():
        _write_registry(
            tmp_path, groups={"Old": ["device-a"], "New": []},
            group_settings={"version": 1, "profiles": {"Old": _legacy_profile()}},
        )
        server.load_registry()
        package_id = server._group_assignments["Old"]
        packages = copy.deepcopy(server._configuration_packages)
        before_bytes = server.REGISTRY_PATH.read_bytes()
        previous_groups = copy.deepcopy(server.device_groups)
        monkeypatch.setattr(server, "save_registry", lambda: False)
        admin = _AdminSocket()

        with pytest.raises(configuration_packages.PackageError):
            server._save_package(package_id, {"name": "Replacement"})
        await server.handle_rename_group(admin, {"name": "Old", "new_name": "Renamed"})
        await server.handle_delete_group(admin, {"name": "Old"})
        await server.handle_set_group_members(admin, {"name": "New", "members": ["device-a"]}, allow_move=True)
        assert [message["type"] for message in admin.messages] == ["ERROR"] * 3
        assert server.device_groups == previous_groups
        assert server._group_assignments == {"Old": package_id}
        assert server._configuration_packages == packages
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
    assert server._configuration_packages is None
    assert server.device_groups == {"Alpha": ["same"]}
    server.device_registry["another"] = {"label": "Example"}
    assert server.save_registry()
    saved = json.loads(server.REGISTRY_PATH.read_text())
    assert saved[field] == opaque
    assert saved["groups"] == groups


def test_failed_migration_does_not_expose_unpersisted_ids(tmp_path, monkeypatch):
    legacy = {"version": 1, "profiles": {"Alpha": _legacy_profile()}}
    _write_registry(tmp_path, groups={"Alpha": ["device-a"]}, group_settings=legacy)
    before_bytes = server.REGISTRY_PATH.read_bytes()
    with monkeypatch.context() as patch:
        patch.setattr(server, "save_registry", lambda: False)
        server.load_registry()
        assert server._configuration_packages is None
        assert server._group_assignments == {}
        assert json.loads(server.build_group_list_msg())["packages"] == []
        assert server.REGISTRY_PATH.read_bytes() == before_bytes

    server.device_registry["another"] = {"label": "Example"}
    assert server.save_registry()
    assert json.loads(server.REGISTRY_PATH.read_text())["group_settings"] == legacy
    server.load_registry()
    assert server._group_assignments["Alpha"] in server._configuration_packages


def test_unreadable_registry_cannot_be_overwritten(tmp_path):
    _write_registry(tmp_path)
    server.REGISTRY_PATH.write_text('{"devices":')
    server.load_registry()
    server.device_registry["another"] = {"label": "Example"}
    assert not server.save_registry()
    assert server.REGISTRY_PATH.read_text() == '{"devices":'
