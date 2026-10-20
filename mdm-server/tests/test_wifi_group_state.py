"""Wi-Fi profile and group state share one atomic registry file."""

from __future__ import annotations

import asyncio
import copy
import json

import pytest
from cryptography.fernet import Fernet

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
    key = Fernet.generate_key()
    return WiFiSettingsStore(key, persist=server._persist_wifi_profiles), key


def _networks():
    return validate_profile_networks([{
        "ssid": "Office", "security": "WPA_PSK", "password": "office-secret", "hidden": False,
    }])


def test_profiles_are_encrypted_inside_registry_and_reload(tmp_path, monkeypatch):
    store, key = _store(tmp_path)
    server.device_groups["Alpha"] = ["device-a"]
    profile = store.put_profile("Alpha", _networks())

    raw = server.REGISTRY_PATH.read_bytes()
    registry = json.loads(raw)
    assert registry["groups"] == {"Alpha": ["device-a"]}
    assert "wifi_profiles_encrypted" in registry
    assert b"office-secret" not in raw
    assert not (tmp_path / "wifi_settings.enc").exists()

    monkeypatch.setenv("MDM_WIFI_ENCRYPTION_KEY", key.decode("ascii"))
    server.load_registry()
    enabled, restored = server._load_wifi_store()
    assert enabled
    assert restored.profile("Alpha") == profile


def test_group_rename_and_delete_persist_profile_with_one_registry_replace(tmp_path, monkeypatch):
    async def body():
        store, _key = _store(tmp_path)
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
        store, _key = _store(tmp_path)
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
        assert server._wifi_profiles_encrypted is not None
        assert server.REGISTRY_PATH.read_bytes() == before_bytes

    asyncio.run(body())


def test_cross_group_membership_is_rejected(tmp_path):
    async def body():
        store, _key = _store(tmp_path)
        server.device_groups.update({"Alpha": ["device-a"], "Beta": []})
        admin = _AdminSocket()

        await server.handle_set_group_members(
            admin, {"name": "Beta", "members": ["device-a"]}, app={"wifi_store": store},
        )
        assert admin.messages[-1]["type"] == "ERROR"
        assert server.device_groups == {"Alpha": ["device-a"], "Beta": []}

    asyncio.run(body())
