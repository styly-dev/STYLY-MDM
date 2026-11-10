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
        "security": "WPA2_WPA3",
        "password": password,
        "hidden": False,
    }]


def test_profile_validation_preserves_saved_password_and_accepts_overwrite():
    previous = validate_profile_networks(_networks())
    retained = validate_profile_networks(_networks(None), previous)
    assert retained[0]["password"] == "correct-horse"
    assert public_networks(retained) == [{
        "ssid": "Studio Wi-Fi", "security": "WPA2_WPA3", "password_present": True, "hidden": False,
    }]
    assert validate_profile_networks(_networks("new-password"), previous)[0]["password"] == "new-password"

    assert validate_profile_networks([{
        "ssid": "日本語", "security": "OPEN", "password": "", "hidden": True,
    }])[0]["ssid"] == "日本語"
    assert validate_profile_networks([{
        "ssid": "Office", "security": "WPA2_WPA3", "password": "a", "hidden": False,
    }])[0]["password"] == "a"

    for ssid in ("", "a" * 33, "bad\nname", 'bad"name', "bad\\name", "\ud800"):
        with pytest.raises(WiFiSettingsError):
            validate_profile_networks([{
                "ssid": ssid, "security": "OPEN", "password": "", "hidden": False,
            }])
    for password in ("", "a" * 64, "пароль", "bad\npass", "bad\x7fpass"):
        with pytest.raises(WiFiSettingsError):
            validate_profile_networks([{
                "ssid": "Office", "security": "WPA2_WPA3", "password": password, "hidden": False,
            }])

    assert validate_profile_networks([{
        "ssid": "Office", "security": "WPA2_WPA3", "password": "a" * 63, "hidden": False,
    }])[0]["password"] == "a" * 63


def test_network_limit_empty_unmanaged_and_same_ssid_security_identity():
    assert validate_profile_networks([], allow_empty=True) == []
    with pytest.raises(WiFiSettingsError):
        validate_profile_networks([])

    same_ssid = [
        {"ssid": "Dual", "security": "OPEN", "password": "", "hidden": False},
        {"ssid": "Dual", "security": "WPA2_WPA3", "password": "secret", "hidden": False},
    ]
    assert len(validate_profile_networks(same_ssid)) == 2
    duplicate = same_ssid + [same_ssid[1]]
    with pytest.raises(WiFiSettingsError, match="duplicate SSID"):
        validate_profile_networks(duplicate)
    with pytest.raises(WiFiSettingsError, match="at most 4"):
        validate_profile_networks([
            {"ssid": f"Network {index}", "security": "OPEN", "password": "", "hidden": False}
            for index in range(5)
        ])


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
