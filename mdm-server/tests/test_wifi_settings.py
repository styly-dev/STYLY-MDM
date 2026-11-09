"""Wi-Fi validation and public telemetry tests."""

from __future__ import annotations

import json

import pytest

from styly_mdm.wifi_settings import (
    WiFiSettingsError,
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


def test_profile_validation_preserves_saved_password_and_accepts_overwrite():
    previous = validate_profile_networks(_networks())
    retained = validate_profile_networks(_networks(None), previous)
    assert retained[0]["password"] == "correct-horse"
    assert public_networks(retained) == [{
        "ssid": "Studio Wi-Fi", "security": "WPA_PSK", "password_present": True, "hidden": False,
    }]
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
