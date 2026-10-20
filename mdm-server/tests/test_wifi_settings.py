"""Durable storage and server-side policy tests for Wi-Fi settings."""

from __future__ import annotations

import copy
import uuid

import pytest
from cryptography.fernet import Fernet

from styly_mdm.wifi_settings import (
    WiFiSettingsError,
    WiFiSettingsStore,
    WiFiStoreError,
    public_networks,
    validate_profile_networks,
)


def _profile(password: str | None = "correct-horse") -> list[dict]:
    return [{
        "ssid": "Studio Wi-Fi",
        "security": "WPA_PSK",
        "password": password,
        "hidden": False,
    }]


def _store(path):
    key = Fernet.generate_key()
    return WiFiSettingsStore(path, key.decode("ascii")), key


def test_profile_password_is_encrypted_and_preserved_by_identity(tmp_path):
    path = tmp_path / "wifi_settings.enc"
    store, key = _store(path)
    original = store.put_profile("Studio", validate_profile_networks(_profile()))
    operation = store.create_operations("Studio", original["revision"], original["networks"], ["device-1"])[0]

    updated_networks = validate_profile_networks(_profile(None), original["networks"])
    updated = store.put_profile("Studio", updated_networks)

    assert updated["revision"] != original["revision"]
    assert updated["networks"][0]["password"] == "correct-horse"
    assert operation["networks"][0]["password"] == "correct-horse"
    assert public_networks(updated["networks"]) == [{
        "ssid": "Studio Wi-Fi",
        "security": "WPA_PSK",
        "password_present": True,
        "hidden": False,
    }]

    encrypted = path.read_bytes()
    assert b"correct-horse" not in encrypted
    assert b"Studio Wi-Fi" not in encrypted
    restored = WiFiSettingsStore(path, key.decode("ascii"))
    assert restored.profile("Studio") == updated
    assert restored.state["operations"][operation["operation_id"]]["networks"] == operation["networks"]


def test_profile_validation_matches_client_supported_inputs():
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


def test_restart_marks_running_operation_unknown_but_keeps_ownership_until_result(tmp_path):
    path = tmp_path / "wifi_settings.enc"
    store, key = _store(path)
    profile = store.put_profile("Studio", validate_profile_networks(_profile()))
    operation = store.create_operations("Studio", profile["revision"], profile["networks"], ["device-1"])[0]
    assert store.mark_accepted(operation["operation_id"], "device-1")
    assert store.set_verify_challenge(operation["operation_id"], "device-1", "challenge-1")

    restored = WiFiSettingsStore(path, key.decode("ascii"))
    current = restored.state["operations"][operation["operation_id"]]
    assert current["status"] == "unknown"
    assert current["unresolved"] is True
    assert current["server_restarted"] is True
    assert restored.unresolved_for_device("device-1")
    assert restored.settle_manual(
        operation["operation_id"], "device-1", profile["revision"],
        "success", "NONE", False,
    )
    assert not restored.unresolved_for_device("device-1")
    assert restored.state["devices"]["device-1"]["applied_revision"] == profile["revision"]


@pytest.mark.parametrize("transition", ["manual", "boot", "create"])
def test_failed_persistence_rolls_back_all_authoritative_state(tmp_path, transition):
    path = tmp_path / "wifi_settings.enc"
    store, _key = _store(path)
    profile = store.put_profile("Studio", validate_profile_networks(_profile()))
    prior = store.create_operations("Studio", profile["revision"], profile["networks"], ["device-1"])[0]
    store.settle_manual(
        prior["operation_id"], "device-1", profile["revision"], "failed", "ADD_FAILED", False,
    )
    before = copy.deepcopy(store.state)

    def fail_save():
        raise WiFiStoreError("injected persistence failure")

    if transition == "manual":
        replacement = store.create_operations(
            "Studio", profile["revision"], profile["networks"], ["device-2"],
        )[0]
        before = copy.deepcopy(store.state)
        store.save = fail_save
    with pytest.raises(WiFiStoreError):
        if transition == "manual":
            store.settle_manual(
                replacement["operation_id"], "device-2", profile["revision"], "failed", "ADD_FAILED", False,
            )
        elif transition == "boot":
            store.save = fail_save
            store.settle_boot("device-1", str(uuid.uuid4()), profile["revision"], "failed", "ADD_FAILED")
        else:
            store.save = fail_save
            store.create_operations("Studio", profile["revision"], profile["networks"], ["device-1"])
    assert store.state == before


def test_conflicting_duplicate_results_are_not_acknowledgeable(tmp_path):
    store, _key = _store(tmp_path / "wifi_settings.enc")
    profile = store.put_profile("Studio", validate_profile_networks(_profile()))
    operation = store.create_operations("Studio", profile["revision"], profile["networks"], ["device-1"])[0]
    assert store.settle_manual(
        operation["operation_id"], "device-1", profile["revision"], "failed", "ADD_FAILED", False,
    )
    assert store.settle_manual(
        operation["operation_id"], "device-1", profile["revision"], "failed", "ADD_FAILED", False,
    )
    assert not store.settle_manual(
        operation["operation_id"], "device-1", profile["revision"], "failed", "DELETE_FAILED", False,
    )


def test_restore_failure_keeps_device_fenced_until_later_verified_result(tmp_path):
    store, _key = _store(tmp_path / "wifi_settings.enc")
    profile = store.put_profile("Studio", validate_profile_networks(_profile()))
    operation = store.create_operations("Studio", profile["revision"], profile["networks"], ["device-1"])[0]

    assert store.settle_manual(
        operation["operation_id"], "device-1", profile["revision"],
        "failed", "RESTORE_FAILED", False,
    )
    assert store.unresolved_for_device("device-1")
    with pytest.raises(WiFiSettingsError, match="unresolved"):
        store.create_operations("Studio", profile["revision"], profile["networks"], ["device-1"])

    # A later report for this same operation proves the rollback completed.
    assert store.settle_manual(
        operation["operation_id"], "device-1", profile["revision"],
        "failed", "UPDATE_FAILED", True,
    )
    assert not store.unresolved_for_device("device-1")
    assert store.state["devices"]["device-1"]["manual"] == {
        "operation_id": operation["operation_id"],
        "revision": profile["revision"],
        "status": "failed",
        "reason": "UPDATE_FAILED",
        "rolled_back": True,
    }


def test_boot_result_duplicate_must_match_exactly(tmp_path):
    store, _key = _store(tmp_path / "wifi_settings.enc")
    boot_id = str(uuid.uuid4())
    revision = str(uuid.uuid4())
    assert store.settle_boot("device-1", boot_id, revision, "success", "NONE")
    assert store.settle_boot("device-1", boot_id, revision, "success", "NONE")
    assert not store.settle_boot("device-1", boot_id, revision, "failed", "ADD_FAILED")
