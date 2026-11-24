"""Wi-Fi validation and public telemetry tests."""

from __future__ import annotations

import pytest

from styly_mdm.wifi_settings import (
    WiFiSettingsError,
    public_wifi_state,
    validate_profile_networks,
)


def _networks(password: str | None = "correct-horse") -> list[dict]:
    return [{
        "ssid": "Studio Wi-Fi",
        "security": "WPA2_WPA3",
        "password": password,
        "hidden": False,
    }]


def test_profile_validation_requires_a_password_on_each_secured_network():
    assert validate_profile_networks(_networks()) == _networks()
    with pytest.raises(WiFiSettingsError, match="require a password"):
        validate_profile_networks(_networks(None))

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


def test_wifi_state_exposes_only_valid_readiness_and_monitor_state():
    base = {
        "busy": True, "available": False, "unavailable_reason": "INITIALIZING", "priority_monitor": "off",
    }
    assert public_wifi_state({**base, "password": "secret"}) == base
    for reason in ("INITIALIZING", "PERMISSION_DENIED", "STORAGE_ERROR", "UNKNOWN", "UNSUPPORTED"):
        state = {**base, "unavailable_reason": reason}
        assert public_wifi_state(state) == state
    for monitor in ("off", "monitoring", "degraded", "switching", "unsupported"):
        state = {**base, "available": True, "unavailable_reason": None, "priority_monitor": monitor}
        assert public_wifi_state(state) == state
    for raw in (
        [], {}, {**base, "busy": "yes"}, {**base, "available": 1},
        {**base, "unavailable_reason": ["secret"]}, {**base, "unavailable_reason": "secret"},
        {**base, "priority_monitor": None}, {**base, "priority_monitor": "secret"},
    ):
        assert public_wifi_state(raw) is None


def test_configuration_result_preserves_saved_reason():
    from styly_mdm.configuration_packages import public_configuration_state

    revision = "11111111-1111-4111-8111-111111111111"
    saved_report = public_configuration_state({
        "package_id": "22222222-2222-4222-8222-222222222222",
        "revision": revision,
        "status": "complete",
        "results": {"wifi": {"status": "applied", "reason": "SAVED"}},
    })
    assert saved_report["results"]["wifi"] == {"status": "applied", "reason": "SAVED"}
