"""Wi-Fi profile and group state share one atomic registry file."""

from __future__ import annotations

import asyncio
import copy
import json

import pytest

from styly_mdm import server
from styly_mdm.wifi_settings import WiFiSettingsStore, validate_profile_networks


class _AdminSocket:
    def __init__(self):
        self.messages = []

    async def send_str(self, message):
        self.messages.append(json.loads(message))


def _store(tmp_path):
    server._apply_data_dir(str(tmp_path))
    server.device_registry.clear()
    server.device_groups.clear()
    server._wifi_profiles_encrypted = None
    server._wifi_profiles = None
    return WiFiSettingsStore(persist=server._persist_wifi_profiles)


def _networks():
    return validate_profile_networks([{
        "ssid": "Office", "security": "WPA_PSK", "password": "office-secret", "hidden": False,
    }])


def test_profiles_are_plaintext_inside_private_registry_and_reload(tmp_path, monkeypatch):
    store = _store(tmp_path)
    server.device_groups["Alpha"] = ["device-a"]
    profile = store.put_profile("Alpha", _networks())

    raw = server.REGISTRY_PATH.read_bytes()
    registry = json.loads(raw)
    assert registry["groups"] == {"Alpha": ["device-a"]}
    assert "wifi_profiles" in registry
    assert "wifi_profiles_encrypted" not in registry
    assert b"office-secret" in raw
    assert server.REGISTRY_PATH.stat().st_mode & 0o777 == 0o600
    assert not (tmp_path / "wifi_settings.enc").exists()

    monkeypatch.delenv("MDM_WIFI_ENCRYPTION_KEY", raising=False)
    server.load_registry()
    enabled, restored = server._load_wifi_store()
    assert enabled
    assert restored.profile("Alpha") == profile


def test_group_rename_and_delete_persist_profile_with_one_registry_replace(tmp_path, monkeypatch):
    async def body():
        store = _store(tmp_path)
        server.device_groups.update({"Alpha": ["device-a"], "Beta": []})
        profile = store.put_profile("Alpha", _networks())
        admin = _AdminSocket()
        replace_calls = []
        original_replace = server.Path.replace

        def count_registry_replace(path, target):
            if path == server.REGISTRY_PATH.with_suffix(".json.tmp"):
                replace_calls.append(target)
            return original_replace(path, target)

        monkeypatch.setattr(server.Path, "replace", count_registry_replace)
        await server.handle_rename_group(
            admin, {"name": "Alpha", "new_name": "Gamma"}, app={"wifi_store": store},
        )
        assert admin.messages[-1]["type"] == "GROUP_RENAMED"
        assert store.profile("Gamma") == profile
        assert "Alpha" not in store.state["profiles"]
        assert len(replace_calls) == 1

        replace_calls.clear()
        await server.handle_delete_group(admin, {"name": "Gamma"}, app={"wifi_store": store})
        assert admin.messages[-1]["type"] == "GROUP_DELETED"
        assert store.profile("Gamma") is None
        assert len(replace_calls) == 1

    asyncio.run(body())


def test_group_rename_write_failure_restores_memory_and_registry(tmp_path, monkeypatch):
    async def body():
        store = _store(tmp_path)
        server.device_groups["Old"] = ["device-a"]
        profile = store.put_profile("Old", _networks())
        before_bytes = server.REGISTRY_PATH.read_bytes()
        previous_groups = copy.deepcopy(server.device_groups)
        previous_state = copy.deepcopy(store.state)
        monkeypatch.setattr(server, "save_registry", lambda: False)
        admin = _AdminSocket()

        await server.handle_rename_group(
            admin, {"name": "Old", "new_name": "New"}, app={"wifi_store": store},
        )
        assert admin.messages[-1]["type"] == "ERROR"
        assert server.device_groups == previous_groups
        assert store.state == previous_state
        assert store.profile("Old") == profile
        assert server._wifi_profiles is not None
        assert server.REGISTRY_PATH.read_bytes() == before_bytes

    asyncio.run(body())


def test_cross_group_membership_is_preserved(tmp_path):
    async def body():
        store = _store(tmp_path)
        server.device_groups.update({"Alpha": ["device-a"], "Beta": []})
        admin = _AdminSocket()

        await server.handle_set_group_members(
            admin, {"name": "Beta", "members": ["device-a"]},
        )
        assert admin.messages[-1]["type"] == "GROUP_MEMBERS_SET"
        assert server.device_groups == {"Alpha": ["device-a"], "Beta": ["device-a"]}

    asyncio.run(body())


def test_loading_registry_keeps_overlapping_memberships_without_writing(tmp_path):
    server._apply_data_dir(str(tmp_path))
    original = json.dumps({"devices": {}, "groups": {"Beta": ["same"], "Alpha": ["same"]}})
    server.REGISTRY_PATH.write_text(original)
    server.load_registry()
    assert server.device_groups == {"Beta": ["same"], "Alpha": ["same"]}
    assert server.REGISTRY_PATH.read_text() == original


def test_set_device_groups_allows_multiple_groups(tmp_path):
    async def body():
        _store(tmp_path)
        server.device_groups.update({"Alpha": [], "Beta": []})
        admin = _AdminSocket()
        await server.handle_set_device_groups(admin, {"device_id": "same", "groups": ["Alpha", "Beta"]})
        assert admin.messages[-1]["type"] == "DEVICE_GROUPS_SET"
        assert server.device_groups == {"Alpha": ["same"], "Beta": ["same"]}
    asyncio.run(body())


@pytest.mark.parametrize("key", [None, "invalid-key"])
def test_unreadable_legacy_profiles_disable_only_wifi_and_survive_registry_writes(tmp_path, monkeypatch, key):
    server._apply_data_dir(str(tmp_path))
    original = {"devices": {}, "groups": {"Alpha": ["same"], "Beta": ["same"]},
                "wifi_profiles_encrypted": "unreadable-legacy-token"}
    server.REGISTRY_PATH.write_text(json.dumps(original))
    if key is None:
        monkeypatch.delenv("MDM_WIFI_ENCRYPTION_KEY", raising=False)
    else:
        monkeypatch.setenv("MDM_WIFI_ENCRYPTION_KEY", key)
    app = server.create_app()
    assert not app["wifi_enabled"]
    assert app["wifi_store"] is None
    server.device_registry["another"] = {"label": "Example"}
    assert server.save_registry()
    saved = json.loads(server.REGISTRY_PATH.read_text())
    assert saved["wifi_profiles_encrypted"] == original["wifi_profiles_encrypted"]
    assert saved["groups"] == original["groups"]
    assert "wifi_profiles" not in saved


def test_legacy_encryption_migrates_once_and_plaintext_needs_no_key(tmp_path, monkeypatch):
    from cryptography import fernet
    server._apply_data_dir(str(tmp_path))
    key = fernet.Fernet.generate_key()
    state = {"version": 1, "profiles": {"Alpha": {
        "revision": "11111111-1111-4111-8111-111111111111", "mode": "managed", "networks": _networks(),
    }}}
    token = fernet.Fernet(key).encrypt(json.dumps(state).encode()).decode()
    server.REGISTRY_PATH.write_text(json.dumps({"devices": {}, "groups": {"Alpha": []},
                                                "wifi_profiles_encrypted": token}))
    monkeypatch.setenv("MDM_WIFI_ENCRYPTION_KEY", key.decode())
    app = server.create_app()
    assert app["wifi_store"].state == state
    saved = json.loads(server.REGISTRY_PATH.read_text())
    assert saved["wifi_profiles"] == state
    assert "wifi_profiles_encrypted" not in saved
    monkeypatch.delenv("MDM_WIFI_ENCRYPTION_KEY")
    assert server.create_app()["wifi_store"].state == state


def test_legacy_migration_write_failure_retains_encrypted_data(tmp_path, monkeypatch):
    from cryptography import fernet
    server._apply_data_dir(str(tmp_path))
    key = fernet.Fernet.generate_key()
    token = fernet.Fernet(key).encrypt(b'{"version":1,"profiles":{}}').decode()
    original = json.dumps({"devices": {}, "groups": {}, "wifi_profiles_encrypted": token})
    server.REGISTRY_PATH.write_text(original)
    monkeypatch.setenv("MDM_WIFI_ENCRYPTION_KEY", key.decode())
    monkeypatch.setattr(server, "save_registry", lambda: False)
    assert not server.create_app()["wifi_enabled"]
    assert server._wifi_profiles_encrypted == token
    assert server._wifi_profiles is None
    assert server.REGISTRY_PATH.read_text() == original


def test_normal_startup_does_not_import_cryptography(tmp_path, monkeypatch):
    import builtins
    real_import = builtins.__import__
    def without_crypto(name, *args, **kwargs):
        if name.startswith("cryptography"):
            raise ImportError("not installed")
        return real_import(name, *args, **kwargs)
    monkeypatch.setattr(builtins, "__import__", without_crypto)
    server._apply_data_dir(str(tmp_path))
    assert server.create_app()["wifi_enabled"]
    server.REGISTRY_PATH.write_text(json.dumps({"devices": {}, "groups": {},
                                                "wifi_profiles_encrypted": "old-token"}))
    monkeypatch.setenv("MDM_WIFI_ENCRYPTION_KEY", "old-key")
    assert not server.create_app()["wifi_enabled"]


@pytest.mark.parametrize("state", [
    {"version": 99, "profiles": {}},
    {"version": 1, "profiles": {"Alpha": {"mode": "managed", "networks": []}}},
])
def test_unreadable_plaintext_profiles_do_not_stop_server_or_get_overwritten(tmp_path, state):
    server._apply_data_dir(str(tmp_path))
    server.REGISTRY_PATH.write_text(json.dumps({"devices": {}, "groups": {}, "wifi_profiles": state}))
    app = server.create_app()
    assert not app["wifi_enabled"]
    assert app["wifi_store"] is None
    assert server.save_registry()
    assert json.loads(server.REGISTRY_PATH.read_text())["wifi_profiles"] == state
