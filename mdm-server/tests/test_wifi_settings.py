"""Wi-Fi profile validation and encrypted persistence tests."""

from __future__ import annotations

import json

import pytest
from cryptography.fernet import Fernet

from styly_mdm.wifi_settings import (
    WiFiSettingsError,
    WiFiSettingsStore,
    WiFiStoreError,
    public_networks,
    validate_profile_networks,
)


def _networks(password: str | None = "correct-horse") -> list[dict]:
    return [{
        "ssid": "Studio Wi-Fi",
        "security": "WPA_PSK",
        "password": password,
        "hidden": False,
    }]


def test_profile_state_is_encrypted_and_round_trips_without_delivery_history():
    key = Fernet.generate_key()
    store = WiFiSettingsStore(key)
    profile = store.put_profile("Studio", validate_profile_networks(_networks()))
    persisted = store.encrypted_state()

    changed = store.put_profile("Studio", validate_profile_networks(_networks(None), profile["networks"]))
    assert changed["revision"] != profile["revision"]
    assert changed["networks"][0]["password"] == "correct-horse"
    assert b"correct-horse" not in persisted.encode("ascii")
    assert public_networks(changed["networks"]) == [{
        "ssid": "Studio Wi-Fi",
        "security": "WPA_PSK",
        "password_present": True,
        "hidden": False,
    }]

    restored = WiFiSettingsStore(key, store.encrypted_state())
    assert restored.profile("Studio") == changed
    assert restored.state == {"version": 1, "profiles": {"Studio": changed}}


def test_unmanaged_profile_is_empty_and_encrypted():
    key = Fernet.generate_key()
    store = WiFiSettingsStore(key)
    profile = store.put_profile("Studio", [], mode="unmanaged")
    assert profile["mode"] == "unmanaged"
    assert profile["networks"] == []
    assert WiFiSettingsStore(key, store.encrypted_state()).profile("Studio") == profile


def test_failed_registry_persistence_rolls_back_profile_state():
    def fail(_encrypted: str) -> None:
        raise WiFiStoreError("injected persistence failure")

    store = WiFiSettingsStore(Fernet.generate_key(), persist=fail)
    with pytest.raises(WiFiStoreError):
        store.put_profile("Studio", validate_profile_networks(_networks()))
    assert store.profile("Studio") is None


def test_profile_validation_preserves_saved_password_and_accepts_overwrite():
    previous = validate_profile_networks(_networks())
    assert validate_profile_networks(_networks(None), previous)[0]["password"] == "correct-horse"
    assert validate_profile_networks(_networks("new-password"), previous)[0]["password"] == "new-password"

    assert validate_profile_networks([{
        "ssid": "日本語", "security": "OPEN", "password": "", "hidden": True,
    }])[0]["ssid"] == "日本語"
    assert validate_profile_networks([{
        "ssid": "Office", "security": "WPA_PSK", "password": "a" * 64, "hidden": False,
    }])[0]["password"] == "a" * 64

    for ssid in ("", "a" * 33, "bad\nname", 'bad"name', "bad\\name", "\ud800"):
        with pytest.raises(WiFiSettingsError):
            validate_profile_networks([{
                "ssid": ssid, "security": "OPEN", "password": "", "hidden": False,
            }])
    for password in ("short", "пароль", "bad\npass"):
        with pytest.raises(WiFiSettingsError):
            validate_profile_networks([{
                "ssid": "Office", "security": "WPA_PSK", "password": password, "hidden": False,
            }])


def test_unreleased_previous_wifi_storage_formats_are_rejected():
    key = Fernet.generate_key()
    wrong = Fernet(key).encrypt(json.dumps({"version": 2, "profiles": {}, "devices": {}}).encode()).decode()
    with pytest.raises(WiFiStoreError, match="unsupported format"):
        WiFiSettingsStore(key, wrong)
