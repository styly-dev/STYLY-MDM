"""Single-membership migration and paired registry/profile persistence tests."""

from __future__ import annotations

import asyncio
import base64
import copy
import json
from pathlib import Path

import pytest

from cryptography.fernet import Fernet

from styly_mdm import server
from styly_mdm.wifi_settings import WiFiSettingsStore, validate_profile_networks


class _AdminSocket:
    def __init__(self):
        self.messages = []

    async def send_str(self, message):
        self.messages.append(json.loads(message))


def _networks():
    return validate_profile_networks([{
        "ssid": "Office", "security": "WPA_PSK", "password": "office-secret", "hidden": False,
    }])


def test_legacy_many_to_many_migration_is_deterministic():
    assert server._normalize_single_membership({
        "Zulu": ["device-a", "device-b"],
        "Alpha": ["device-a", "device-c"],
        "Beta": ["device-b", "device-d"],
    }) == {
        "Alpha": ["device-a", "device-c"],
        "Beta": ["device-b", "device-d"],
        "Zulu": [],
    }


def test_group_members_reject_cross_group_assignments_but_allow_changes_after_dispatch(tmp_path, monkeypatch):
    async def body():
        server._apply_data_dir(str(tmp_path))
        server._WIFI_GROUP_RECOVERY_REQUIRED = False
        server.device_groups.clear()
        server.device_groups.update({"Alpha": ["device-a"], "Beta": []})
        server.device_registry.clear()
        store = WiFiSettingsStore(tmp_path / "wifi_settings.enc", Fernet.generate_key())
        fake_app = {"wifi_store": store}
        admin = _AdminSocket()

        await server.handle_set_group_members(
            admin, {"name": "Beta", "members": ["device-a"]}, app=fake_app,
        )
        assert admin.messages[-1]["type"] == "ERROR"
        assert server.device_groups == {"Alpha": ["device-a"], "Beta": []}

        profile = store.put_profile("Alpha", _networks())
        operation = store.create_delivery_attempts("Alpha", profile["revision"], ["device-a"])[0]
        assert store.settle_delivery(operation["device_id"], operation["operation_id"], "sent", None)
        await server.handle_set_group_members(
            admin, {"name": "Alpha", "members": []}, app=fake_app,
        )
        assert admin.messages[-1]["type"] == "GROUP_MEMBERS_SET"
        assert server.device_groups["Alpha"] == []
        assert store.state["devices"]["device-a"]["delivery"]["status"] == "sent"
        assert operation["device_id"] == "device-a"

        await server.handle_rename_group(
            admin, {"name": "Alpha", "new_name": "Gamma"}, app=fake_app,
        )
        assert admin.messages[-1]["type"] == "GROUP_RENAMED"
        assert store.profile("Gamma") is not None

        await server.handle_delete_group(admin, {"name": "Gamma"}, app=fake_app)
        assert admin.messages[-1]["type"] == "GROUP_DELETED"
        assert store.profile("Gamma") is None

    asyncio.run(body())


def test_group_rename_failure_rolls_back_both_files_and_memory(tmp_path, monkeypatch):
    server._apply_data_dir(str(tmp_path))
    server._WIFI_GROUP_RECOVERY_REQUIRED = False
    server.device_registry.clear()
    server.device_groups.clear()
    server.device_groups["Old"] = ["device-a"]
    assert server.save_registry()
    store = WiFiSettingsStore(tmp_path / "wifi_settings.enc", Fernet.generate_key())
    store.put_profile("Old", _networks())
    old_registry = server.REGISTRY_PATH.read_bytes()
    old_wifi = store.path.read_bytes()
    previous_groups = copy.deepcopy(server.device_groups)
    previous_state = copy.deepcopy(store.state)

    server.device_groups["New"] = server.device_groups.pop("Old")
    store.rename_profile("Old", "New", save=False)
    original_replace = server.os.replace
    failed = False

    def fail_first_store_replace(source, destination):
        nonlocal failed
        if Path(destination) == store.path and not failed:
            failed = True
            raise OSError("injected second-file replacement failure")
        return original_replace(source, destination)

    monkeypatch.setattr(server.os, "replace", fail_first_store_replace)
    assert not server._persist_group_registry_mutation(store, previous_groups, previous_state)
    assert failed
    assert server.REGISTRY_PATH.read_bytes() == old_registry
    assert store.path.read_bytes() == old_wifi
    assert server.device_groups == previous_groups
    assert store.state == previous_state
    assert not server._wifi_group_journal_path().exists()


def test_group_mutation_latch_refuses_new_pair_writes(tmp_path):
    server._apply_data_dir(str(tmp_path))
    server.device_groups.clear()
    server.device_groups["Old"] = ["device-a"]
    store = WiFiSettingsStore(tmp_path / "wifi_settings.enc", Fernet.generate_key())
    store.put_profile("Old", _networks())
    previous_groups = copy.deepcopy(server.device_groups)
    previous_state = copy.deepcopy(store.state)
    registry_bytes = server.REGISTRY_PATH.read_bytes() if server.REGISTRY_PATH.exists() else None

    server.device_groups["New"] = server.device_groups.pop("Old")
    store.rename_profile("Old", "New", save=False)
    server._WIFI_GROUP_RECOVERY_REQUIRED = True
    try:
        assert not server._persist_group_registry_mutation(store, previous_groups, previous_state)
        assert server.device_groups == previous_groups
        assert store.state == previous_state
        assert not server._wifi_group_journal_path().exists()
        if registry_bytes is not None:
            assert server.REGISTRY_PATH.read_bytes() == registry_bytes
    finally:
        server._WIFI_GROUP_RECOVERY_REQUIRED = False


def test_startup_recovers_crash_between_registry_and_profile_replacements(tmp_path, monkeypatch):
    server._apply_data_dir(str(tmp_path))
    server._WIFI_GROUP_RECOVERY_REQUIRED = False
    server.device_registry.clear()
    server.device_groups.clear()
    server.device_groups["Old"] = ["device-a"]
    assert server.save_registry()
    key = Fernet.generate_key()
    old_store = WiFiSettingsStore(tmp_path / "wifi_settings.enc", key)
    old_store.put_profile("Old", _networks())

    old_registry = server.REGISTRY_PATH.read_bytes()
    old_wifi = old_store.path.read_bytes()
    new_state = copy.deepcopy(old_store.state)
    new_state["profiles"]["New"] = new_state["profiles"].pop("Old")
    next_store = WiFiSettingsStore(tmp_path / "next.enc", key)
    next_store.state = new_state
    next_wifi = next_store.encrypted_bytes()
    next_registry = json.dumps({
        "devices": {}, "groups": {"New": ["device-a"]},
    }, ensure_ascii=False, indent=2).encode("utf-8")
    journal = {
        "version": 1,
        "phase": "rollback",
        "previous_registry": base64.b64encode(old_registry).decode("ascii"),
        "previous_wifi": base64.b64encode(old_wifi).decode("ascii"),
        "next_registry": base64.b64encode(next_registry).decode("ascii"),
        "next_wifi": base64.b64encode(next_wifi).decode("ascii"),
    }
    server._wifi_group_journal_path().write_text(json.dumps(journal), encoding="ascii")
    server.REGISTRY_PATH.write_bytes(next_registry)  # crash after the first replace

    monkeypatch.setenv("MDM_WIFI_ENCRYPTION_KEY", key.decode("ascii"))
    monkeypatch.setattr(server, "_load_tls_context", lambda: object())
    app = server.create_app()

    assert server.device_groups == {"Old": ["device-a"]}
    assert app["wifi_store"].profile("Old") is not None
    assert app["wifi_store"].profile("New") is None
    assert not server._wifi_group_journal_path().exists()


@pytest.mark.parametrize("sync_fails", [False, True])
def test_recovery_syncs_restored_directories_before_removing_journal(tmp_path, monkeypatch, sync_fails):
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    registry_dir = tmp_path / "registry"
    registry_dir.mkdir()
    registry_path = registry_dir / "devices.json"
    journal_path = data_dir / "wifi_group_mutation.txn"
    journal_path.write_text(json.dumps({
        "version": 1, "phase": "rollback", "previous_registry": None, "previous_wifi": None,
    }))
    events = []
    monkeypatch.setattr(server, "_restore_file", lambda path, *_args, **_kwargs: events.append(("restore", path)))

    def sync(directory):
        events.append(("sync", directory))
        if sync_fails:
            raise OSError("injected recovery sync failure")

    original_unlink = Path.unlink
    def unlink(path, **kwargs):
        if path == journal_path:
            events.append(("unlink", path))
        return original_unlink(path, **kwargs)

    monkeypatch.setattr(server, "_fsync_directory", sync)
    monkeypatch.setattr(Path, "unlink", unlink)

    assert server._recover_wifi_group_mutation(data_dir, registry_path) is not sync_fails
    if sync_fails:
        assert journal_path.exists()
        assert not any(kind == "unlink" for kind, _ in events)
    else:
        removed_at = events.index(("unlink", journal_path))
        assert ("sync", registry_dir) in events[:removed_at]
        assert ("sync", data_dir) in events[:removed_at]
        assert events[-1] == ("sync", data_dir)
        assert not journal_path.exists()
