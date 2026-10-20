"""Encrypted profile storage and delivery-only Wi-Fi state tests."""

from __future__ import annotations

import copy
import json
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


def test_profile_and_delivery_state_are_encrypted_without_snapshot_password_duplication(tmp_path):
    path = tmp_path / "wifi_settings.enc"
    store, key = _store(path)
    profile = store.put_profile("Studio", validate_profile_networks(_profile()))
    attempt = store.create_delivery_attempts("Studio", profile["revision"], ["device-1"])[0]

    changed = store.put_profile(
        "Studio",
        validate_profile_networks(_profile(None), profile["networks"]),
    )
    assert changed["revision"] != profile["revision"]
    assert changed["mode"] == "managed"
    assert changed["networks"][0]["password"] == "correct-horse"
    assert "networks" not in attempt
    assert set(attempt) == {
        "operation_id", "group", "device_id", "revision", "status", "reason",
        "attempted_at", "updated_at",
    }
    assert store.state_view({"device-1"}, set())["device-1"]["delivery"]["status"] == "unknown"
    assert public_networks(changed["networks"]) == [{
        "ssid": "Studio Wi-Fi",
        "security": "WPA_PSK",
        "password_present": True,
        "hidden": False,
    }]

    restored = WiFiSettingsStore(path, key.decode("ascii"))
    assert restored.profile("Studio") == changed
    operation = restored.state["devices"]["device-1"]["delivery"]
    assert operation["status"] == "unknown"
    assert operation["reason"] == "server_restarted"
    assert "networks" not in operation
    assert b"correct-horse" not in path.read_bytes()


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


def test_restart_marks_interrupted_send_unknown_and_does_not_replay(tmp_path):
    path = tmp_path / "wifi_settings.enc"
    store, key = _store(path)
    profile = store.put_profile("Studio", validate_profile_networks(_profile()))
    attempt = store.create_delivery_attempts("Studio", profile["revision"], ["device-1"])[0]

    restored = WiFiSettingsStore(path, key.decode("ascii"))
    operation = restored.state["devices"]["device-1"]["delivery"]
    delivery = restored.state["devices"]["device-1"]["delivery"]
    assert operation["status"] == "unknown"
    assert operation["reason"] == "server_restarted"
    assert delivery["status"] == "unknown"
    assert delivery["reason"] == "server_restarted"
    assert delivery["operation_id"] == attempt["operation_id"]


@pytest.mark.parametrize("transition", ["create", "settle"])
def test_failed_persistence_rolls_back_delivery_state(tmp_path, transition):
    path = tmp_path / "wifi_settings.enc"
    store, _key = _store(path)
    profile = store.put_profile("Studio", validate_profile_networks(_profile()))
    attempt = None
    if transition == "settle":
        attempt = store.create_delivery_attempts("Studio", profile["revision"], ["device-1"])[0]
    before = copy.deepcopy(store.state)

    def fail_save():
        raise WiFiStoreError("injected persistence failure")

    store.save = fail_save
    with pytest.raises(WiFiStoreError):
        if transition == "create":
            store.create_delivery_attempts("Studio", profile["revision"], ["device-1"])
        else:
            store.settle_delivery(attempt["device_id"], attempt["operation_id"], "sent", None)
    assert store.state == before


@pytest.mark.parametrize("interrupted", [False, True])
def test_current_v2_migration_preserves_latest_delivery_and_discards_duplicate_history(tmp_path, interrupted):
    path = tmp_path / "wifi_settings.enc"
    store, key = _store(path)
    profile = store.put_profile("Studio", validate_profile_networks(_profile()))
    attempt = store.create_delivery_attempts("Studio", profile["revision"], ["device-1"])[0]
    operation = dict(attempt, status="dispatching" if interrupted else "sent")
    delivery = {k: v for k, v in attempt.items() if k not in {"group", "device_id"}}
    delivery.update(status="unknown" if interrupted else "sent",
                    reason="dispatch_not_confirmed" if interrupted else None)
    old_id = str(uuid.uuid4())
    old_state = {
        "version": 2,
        "profiles": copy.deepcopy(store.state["profiles"]),
        "devices": {"device-1": {"delivery": delivery}},
        "operations": {attempt["operation_id"]: operation, old_id: dict(operation, operation_id=old_id)},
    }
    path.write_bytes(Fernet(key).encrypt(json.dumps(old_state).encode()))

    restored = WiFiSettingsStore(path, key)

    assert restored.state["version"] == 3
    assert "operations" not in restored.state
    assert restored.profile("Studio") == profile
    latest = restored.state["devices"]["device-1"]["delivery"]
    assert latest["operation_id"] == attempt["operation_id"]
    assert latest["revision"] == profile["revision"]
    assert latest["status"] == ("unknown" if interrupted else "sent")
    assert latest["reason"] == ("server_restarted" if interrupted else None)
    assert WiFiSettingsStore(path, key).state == restored.state


def test_unsupported_local_prototype_format_is_not_silently_reinterpreted(tmp_path):
    path = tmp_path / "wifi_settings.enc"
    key = Fernet.generate_key()
    path.write_bytes(Fernet(key).encrypt(json.dumps({
        "version": 1, "profiles": {}, "devices": {}, "operations": {},
    }).encode()))
    original = path.read_bytes()
    with pytest.raises(WiFiStoreError, match="unsupported format"):
        WiFiSettingsStore(path, key)
    assert path.read_bytes() == original


def test_unmanaged_profile_has_empty_networks_and_preserves_delivery(tmp_path):
    store, _key = _store(tmp_path / "wifi_settings.enc")
    managed = store.put_profile("Studio", validate_profile_networks(_profile()))
    attempt = store.create_delivery_attempts("Studio", managed["revision"], ["device-1"])[0]
    assert store.settle_delivery(attempt["device_id"], attempt["operation_id"], "sent", None)
    previous_delivery = copy.deepcopy(store.state["devices"]["device-1"]["delivery"])

    unmanaged = store.put_profile("Studio", [], mode="unmanaged")

    assert unmanaged["mode"] == "unmanaged"
    assert unmanaged["networks"] == []
    assert unmanaged["revision"] != managed["revision"]
    assert store.state["devices"]["device-1"]["delivery"] == previous_delivery
    with pytest.raises(WiFiSettingsError):
        store.put_profile("Studio", validate_profile_networks(_profile()), mode="unmanaged")


def test_superseded_send_settlement_does_not_replace_newer_device_delivery(tmp_path):
    store, _key = _store(tmp_path / "wifi_settings.enc")
    revision_a = str(uuid.uuid4())
    revision_b = str(uuid.uuid4())
    first = store.create_delivery_attempts("Studio", revision_a, ["device-1"])[0]
    second = store.create_delivery_attempts("Studio", revision_b, ["device-1"])[0]

    assert store.settle_delivery(first["device_id"], first["operation_id"], "sent", None)
    current = store.state["devices"]["device-1"]["delivery"]
    assert current["operation_id"] == second["operation_id"]
    assert current["revision"] == revision_b
    assert store.state_view({"device-1"}, set())["device-1"]["delivery"]["status"] == "unknown"
    assert store.settle_delivery(second["device_id"], second["operation_id"], "sent", None)
    assert store.state["devices"]["device-1"]["delivery"]["status"] == "sent"


def test_state_view_exposes_only_latest_delivery(tmp_path):
    store, _key = _store(tmp_path / "wifi_settings.enc")
    profile = store.put_profile("Studio", validate_profile_networks(_profile()))
    attempt = store.create_delivery_attempts("Studio", profile["revision"], ["device-1"])[0]
    assert store.settle_delivery(attempt["device_id"], attempt["operation_id"], "sent", None)
    assert store.state_view({"device-1", "device-2"}, {"device-1"}) == {
        "device-1": {
            "delivery": {
                "operation_id": attempt["operation_id"],
                "revision": profile["revision"],
                "status": "sent",
                "reason": None,
                "attempted_at": attempt["attempted_at"],
                "updated_at": store.state["devices"]["device-1"]["delivery"]["updated_at"],
            },
            "online": True,
        },
        "device-2": {"delivery": None, "online": False},
    }
