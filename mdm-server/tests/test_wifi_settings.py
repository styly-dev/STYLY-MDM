"""Wi-Fi profile validation and registry persistence tests."""

from __future__ import annotations

import json

import pytest

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


def test_profile_state_round_trips_without_delivery_history():
    store = WiFiSettingsStore()
    profile = store.put_profile("Studio", validate_profile_networks(_networks()))

    changed = store.put_profile("Studio", validate_profile_networks(_networks(None), profile["networks"]))
    assert changed["revision"] != profile["revision"]
    assert changed["networks"][0]["password"] == "correct-horse"
    assert public_networks(changed["networks"]) == [{
        "ssid": "Studio Wi-Fi",
        "security": "WPA_PSK",
        "password_present": True,
        "hidden": False,
    }]

    restored = WiFiSettingsStore(store.state)
    assert restored.profile("Studio") == changed
    assert restored.state == {"version": 1, "profiles": {"Studio": changed}}


def test_unmanaged_profile_is_empty_and_persisted():
    store = WiFiSettingsStore()
    profile = store.put_profile("Studio", [], mode="unmanaged")
    assert profile["mode"] == "unmanaged"
    assert profile["networks"] == []
    assert WiFiSettingsStore(store.state).profile("Studio") == profile


def test_failed_registry_persistence_rolls_back_profile_state():
    def fail(_state: dict) -> None:
        raise WiFiStoreError("injected persistence failure")

    store = WiFiSettingsStore(persist=fail)
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
    wrong = {"version": 2, "profiles": {}, "devices": {}}
    with pytest.raises(WiFiStoreError, match="unsupported format"):
        WiFiSettingsStore(wrong)


def test_wifi_state_never_forwards_credentials_or_unvalidated_results():
    from styly_mdm.wifi_settings import public_wifi_state
    raw = {"managed": True, "policy_revision": "11111111-1111-4111-8111-111111111111",
           "busy": False, "available": True, "unavailable_reason": None,
           "password": "secret", "last_result": {
               "operation_id": "22222222-2222-4222-8222-222222222222",
               "revision": "11111111-1111-4111-8111-111111111111", "status": "failed",
               "reason": "PERMISSION_DENIED", "rolled_back": False, "password": "secret"}}
    state = public_wifi_state(raw)
    assert "secret" not in json.dumps(state)
    for value in ([], {}, {**raw, "busy": "yes"}, {**raw, "last_result": {"status": "success"}},
                  {**raw, "unavailable_reason": ["secret"]}, {**raw, "policy_revision": "invalid"}):
        assert public_wifi_state(value) is None
