"""Encrypted durable state and validation for the opt-in Wi-Fi settings feature.

This module contains no HTTP or WebSocket code. Password-bearing profiles and
delivery metadata are encrypted as one Fernet payload before they reach disk.
"""

from __future__ import annotations

import copy
import json
import os
import tempfile
import time
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any

from cryptography.fernet import Fernet, InvalidToken


MAX_WIFI_NETWORKS = 32
MAX_SSID_BYTES = 32

DELIVERY_STATUSES = frozenset({"sent", "failed", "unknown"})
DELIVERY_REASONS = frozenset({
    "unsupported",
    "permission_denied",
    "socket_unavailable",
    "socket_replaced",
    "superseded",
    "socket_send_failed",
    "server_restarted",
    "legacy_result_untracked",
    "dispatch_not_confirmed",
    "state_persist_failed",
})
MAX_OLD_DELIVERY_OPERATIONS = 256


class WiFiSettingsError(ValueError):
    """A validation or state transition error safe to return to an API client."""

    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.status = status


class WiFiStoreError(RuntimeError):
    """The encrypted state could not be safely read or persisted."""


def _new_state() -> dict[str, Any]:
    return {"version": 2, "profiles": {}, "operations": {}, "devices": {}}


def normalize_revision(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = uuid.UUID(value)
        return value if parsed.version == 4 and str(parsed) == value else None
    except (ValueError, AttributeError):
        return None


def validate_profile_networks(
    raw_networks: Any,
    previous: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    """Validate form-shaped networks and preserve omitted saved PSKs by identity."""
    if not isinstance(raw_networks, list) or not raw_networks:
        raise WiFiSettingsError("networks must be a non-empty list")
    if len(raw_networks) > MAX_WIFI_NETWORKS:
        raise WiFiSettingsError(f"networks may contain at most {MAX_WIFI_NETWORKS} entries")

    previous_by_identity = {
        (item.get("ssid"), item.get("security")): item
        for item in previous or []
        if isinstance(item, dict)
    }
    result: list[dict[str, Any]] = []
    identities: set[tuple[str, str]] = set()
    for raw in raw_networks:
        if not isinstance(raw, dict):
            raise WiFiSettingsError("each network must be an object")
        if set(raw) - {"ssid", "security", "password", "hidden"}:
            raise WiFiSettingsError("network contains unsupported fields")

        ssid = raw.get("ssid")
        if not isinstance(ssid, str) or not ssid:
            raise WiFiSettingsError("ssid must contain 1 to 32 UTF-8 bytes")
        try:
            ssid_bytes = ssid.encode("utf-8")
        except UnicodeEncodeError:
            raise WiFiSettingsError("ssid must contain valid UTF-8 text") from None
        if len(ssid_bytes) > MAX_SSID_BYTES:
            raise WiFiSettingsError("ssid must contain 1 to 32 UTF-8 bytes")
        if any(ord(char) < 0x20 or 0x7f <= ord(char) <= 0x9f or char in {'"', "\\"} for char in ssid):
            raise WiFiSettingsError("ssid contains unsupported characters")
        security = raw.get("security")
        if not isinstance(security, str) or security not in {"OPEN", "WPA_PSK"}:
            raise WiFiSettingsError("security must be OPEN or WPA_PSK")
        hidden = raw.get("hidden")
        if not isinstance(hidden, bool):
            raise WiFiSettingsError("hidden must be a boolean")

        identity = (ssid, security)
        if identity in identities:
            raise WiFiSettingsError("duplicate SSID and security combination")
        identities.add(identity)

        supplied_password = raw.get("password")
        if security == "OPEN":
            if supplied_password not in (None, ""):
                raise WiFiSettingsError("OPEN networks must have an empty password")
            password = ""
        elif supplied_password is None:
            old = previous_by_identity.get(identity)
            if old is None or not isinstance(old.get("password"), str) or not old["password"]:
                raise WiFiSettingsError("a password is required for a new WPA_PSK network")
            password = old["password"]
        elif isinstance(supplied_password, str):
            try:
                encoded = supplied_password.encode("ascii")
            except UnicodeEncodeError:
                raise WiFiSettingsError("WPA_PSK password must be ASCII") from None
            is_hex_psk = len(encoded) == 64 and all(ch in b"0123456789abcdefABCDEF" for ch in encoded)
            has_control = any(ord(char) < 0x20 or 0x7f <= ord(char) <= 0x9f for char in supplied_password)
            if has_control or (not is_hex_psk and not 8 <= len(encoded) <= 63):
                raise WiFiSettingsError("WPA_PSK password must be 8 to 63 ASCII characters or 64 hex digits")
            password = supplied_password
        else:
            raise WiFiSettingsError("password must be a string or null")

        result.append({
            "ssid": ssid,
            "security": security,
            "password": password,
            "hidden": hidden,
        })

    return result


def public_networks(networks: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Build the non-secret network shape returned by profile APIs."""
    return [
        {
            "ssid": network["ssid"],
            "security": network["security"],
            "password_present": network["security"] == "WPA_PSK" and bool(network.get("password")),
            "hidden": network["hidden"],
        }
        for network in networks
    ]


def valid_uuid(value: Any) -> bool:
    return normalize_revision(value) is not None


class WiFiSettingsStore:
    """Encrypted profiles and bounded, delivery-only Wi-Fi command state."""

    def __init__(self, path: Path, encryption_key: str | bytes):
        self.path = Path(path)
        if isinstance(encryption_key, str):
            try:
                encryption_key = encryption_key.encode("ascii", errors="strict")
            except UnicodeEncodeError:
                raise WiFiStoreError("invalid encryption key") from None
        try:
            self._fernet = Fernet(encryption_key)
        except (TypeError, ValueError):
            raise WiFiStoreError("invalid encryption key") from None
        self.state = _new_state()
        self._load()
        self.mark_restart_unknown()

    def _load(self) -> None:
        try:
            encrypted = self.path.read_bytes()
        except FileNotFoundError:
            return
        except OSError:
            raise WiFiStoreError("encrypted Wi-Fi state is unreadable") from None
        try:
            payload = self._fernet.decrypt(encrypted)
            parsed = json.loads(payload)
        except (InvalidToken, json.JSONDecodeError, UnicodeDecodeError):
            raise WiFiStoreError("encrypted Wi-Fi state failed validation") from None
        if not isinstance(parsed, dict) or type(parsed.get("version")) is not int:
            raise WiFiStoreError("encrypted Wi-Fi state has an unsupported format")
        for key in ("profiles", "operations", "devices"):
            if not isinstance(parsed.get(key), dict):
                raise WiFiStoreError("encrypted Wi-Fi state failed validation")
        if parsed["version"] == 1:
            self.state = self._migrate_v1(parsed)
            try:
                self.save()
            except WiFiStoreError:
                raise WiFiStoreError("legacy Wi-Fi delivery state could not be migrated") from None
        elif parsed["version"] == 2:
            self.state = parsed
            if self._normalize_profiles(self.state["profiles"]):
                try:
                    self.save()
                except WiFiStoreError:
                    raise WiFiStoreError("legacy Wi-Fi profile mode could not be migrated") from None
        else:
            raise WiFiStoreError("encrypted Wi-Fi state has an unsupported format")

    @staticmethod
    def _normalize_profiles(profiles: dict[str, Any]) -> bool:
        """Upgrade pre-mode profiles as managed and reject invalid persisted shapes."""
        changed = False
        for profile in profiles.values():
            if not isinstance(profile, dict):
                raise WiFiStoreError("encrypted Wi-Fi profile failed validation")
            if "mode" not in profile:
                profile["mode"] = "managed"
                changed = True
            mode = profile.get("mode")
            networks = profile.get("networks")
            if (
                not isinstance(mode, str)
                or mode not in {"managed", "unmanaged"}
                or not isinstance(networks, list)
            ):
                raise WiFiStoreError("encrypted Wi-Fi profile failed validation")
            if (mode == "managed" and not networks) or (mode == "unmanaged" and networks):
                raise WiFiStoreError("encrypted Wi-Fi profile failed validation")
        return changed

    @staticmethod
    def _migrate_v1(parsed: dict[str, Any]) -> dict[str, Any]:
        """Keep profiles; represent old result-tracked applies as unknown delivery."""
        migrated = _new_state()
        migrated["profiles"] = copy.deepcopy(parsed["profiles"])
        WiFiSettingsStore._normalize_profiles(migrated["profiles"])
        latest: dict[str, tuple[float, int, dict[str, Any]]] = {}
        for index, (operation_id, old) in enumerate(parsed["operations"].items()):
            if not isinstance(old, dict):
                continue
            device_id = old.get("device_id")
            revision = old.get("revision")
            if not isinstance(device_id, str) or not valid_uuid(operation_id) or not valid_uuid(revision):
                continue
            attempted_at = old.get("created_at")
            if isinstance(attempted_at, bool) or not isinstance(attempted_at, (int, float)):
                attempted_at = 0.0
            record = {
                "operation_id": operation_id,
                "device_id": device_id,
                "revision": revision,
                "status": "unknown",
                "reason": "legacy_result_untracked",
                "attempted_at": float(attempted_at),
                "updated_at": float(attempted_at),
            }
            candidate = (float(attempted_at), index, record)
            if device_id not in latest or candidate[:2] >= latest[device_id][:2]:
                latest[device_id] = candidate

        all_device_ids = set(parsed["devices"]) | set(latest)
        for device_id in all_device_ids:
            if not isinstance(device_id, str):
                continue
            old_record = parsed["devices"].get(device_id)
            migrated_devices: dict[str, Any] = {}
            if device_id in latest:
                migrated_devices["delivery"] = copy.deepcopy(latest[device_id][2])
            else:
                # A legacy manual result may outlive its detailed operation.
                # Never treat its status as proof that a command was delivered.
                manual = old_record.get("manual") if isinstance(old_record, dict) else None
                if isinstance(manual, dict):
                    operation_id = manual.get("operation_id")
                    revision = manual.get("revision")
                    if valid_uuid(operation_id) and valid_uuid(revision):
                        migrated_devices["delivery"] = {
                            "operation_id": operation_id,
                            "revision": revision,
                            "status": "unknown",
                            "reason": "legacy_result_untracked",
                            "attempted_at": None,
                            "updated_at": 0.0,
                        }
            migrated["devices"][device_id] = migrated_devices

        migrated["operations"] = {
            item[2]["operation_id"]: copy.deepcopy(item[2])
            for item in latest.values()
        }
        return migrated

    def encrypted_bytes(self) -> bytes:
        payload = json.dumps(
            self.state,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        return self._fernet.encrypt(payload)

    @contextmanager
    def _transaction(self):
        """Copy-on-write state so failed disk writes can never become live state."""
        previous = self.state
        self.state = copy.deepcopy(previous)
        try:
            yield
            self.save()
        except Exception:
            self.state = previous
            raise

    def save(self) -> None:
        """Write an encrypted snapshot through a mode-0600 temporary file."""
        self.path.parent.mkdir(parents=True, exist_ok=True)
        encrypted = self.encrypted_bytes()
        fd, temporary_name = tempfile.mkstemp(prefix=f".{self.path.name}.", dir=self.path.parent)
        temporary = Path(temporary_name)
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "wb") as handle:
                handle.write(encrypted)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, self.path)
            try:
                self.path.chmod(0o600)
            except OSError:
                pass
            try:
                directory_fd = os.open(self.path.parent, os.O_RDONLY)
            except OSError:
                directory_fd = None
            if directory_fd is not None:
                try:
                    os.fsync(directory_fd)
                finally:
                    os.close(directory_fd)
        except OSError:
            temporary.unlink(missing_ok=True)
            raise WiFiStoreError("encrypted Wi-Fi state could not be persisted") from None

    def mark_restart_unknown(self) -> None:
        interrupted = [
            operation_id
            for operation_id, operation in self.state["operations"].items()
            if isinstance(operation, dict) and operation.get("status") == "dispatching"
        ]
        if not interrupted:
            return
        with self._transaction():
            for operation_id in interrupted:
                operation = self.state["operations"].get(operation_id)
                if not isinstance(operation, dict) or operation.get("status") != "dispatching":
                    continue
                operation["status"] = "unknown"
                operation["reason"] = "server_restarted"
                operation["updated_at"] = time.time()
                self._update_latest_delivery(operation)
            self._prune_operations()

    def profile(self, group_name: str) -> dict[str, Any] | None:
        profile = self.state["profiles"].get(group_name)
        if not isinstance(profile, dict):
            return None
        result = copy.deepcopy(profile)
        result.setdefault("mode", "managed")
        return result

    def put_profile(
        self,
        group_name: str,
        networks: list[dict[str, Any]],
        *,
        mode: str = "managed",
    ) -> dict[str, Any]:
        if (
            not isinstance(mode, str)
            or mode not in {"managed", "unmanaged"}
            or not isinstance(networks, list)
        ):
            raise WiFiSettingsError("invalid Wi-Fi profile mode or networks")
        if (mode == "managed" and not networks) or (mode == "unmanaged" and networks):
            raise WiFiSettingsError("Wi-Fi networks do not match the profile mode")
        revision = str(uuid.uuid4())
        profile = {"revision": revision, "mode": mode, "networks": copy.deepcopy(networks)}
        with self._transaction():
            self.state["profiles"][group_name] = profile
        return copy.deepcopy(profile)

    def rename_profile(self, old_name: str, new_name: str, *, save: bool = True) -> None:
        if old_name not in self.state["profiles"]:
            return
        if save:
            with self._transaction():
                self.state["profiles"][new_name] = self.state["profiles"].pop(old_name)
        else:
            self.state["profiles"][new_name] = self.state["profiles"].pop(old_name)

    def delete_profile(self, group_name: str, *, save: bool = True) -> None:
        if group_name not in self.state["profiles"]:
            return
        if save:
            with self._transaction():
                self.state["profiles"].pop(group_name)
        else:
            self.state["profiles"].pop(group_name)

    def create_delivery_attempts(
        self,
        group_name: str,
        revision: str,
        device_ids: list[str],
    ) -> list[dict[str, Any]]:
        if not valid_uuid(revision):
            raise WiFiSettingsError("invalid Wi-Fi revision")
        attempts: list[dict[str, Any]] = []
        with self._transaction():
            for device_id in device_ids:
                operation_id = str(uuid.uuid4())
                now = time.time()
                operation = {
                    "operation_id": operation_id,
                    "group": group_name,
                    "device_id": device_id,
                    "revision": revision,
                    "status": "dispatching",
                    "reason": None,
                    "attempted_at": now,
                    "updated_at": now,
                }
                # Persist this small record before any socket write. A restart
                # makes it unknown and never replays the command.
                self.state["operations"][operation_id] = operation
                self.state["devices"].setdefault(device_id, {})["delivery"] = {
                    "operation_id": operation_id,
                    "revision": revision,
                    "status": "unknown",
                    "reason": "dispatch_not_confirmed",
                    "attempted_at": now,
                    "updated_at": now,
                }
                attempts.append(copy.deepcopy(operation))
            self._prune_operations()
        return attempts

    def settle_delivery(self, operation_id: str, status: str, reason: str | None) -> bool:
        operation = self.state["operations"].get(operation_id)
        if not isinstance(operation, dict):
            return False
        if status not in DELIVERY_STATUSES or not self._valid_delivery_reason(status, reason):
            return False
        if operation.get("status") != "dispatching":
            return operation.get("status") == status and operation.get("reason") == reason

        with self._transaction():
            operation = self.state["operations"][operation_id]
            operation["status"] = status
            operation["reason"] = reason
            operation["updated_at"] = time.time()
            self._update_latest_delivery(operation)
            self._prune_operations()
        return True

    def is_latest_delivery(self, device_id: str, operation_id: str) -> bool:
        record = self.state["devices"].get(device_id)
        delivery = record.get("delivery") if isinstance(record, dict) else None
        return isinstance(delivery, dict) and delivery.get("operation_id") == operation_id

    @staticmethod
    def _valid_delivery_reason(status: str, reason: str | None) -> bool:
        if status == "sent":
            return reason is None
        if status == "failed":
            return reason in {
                "unsupported", "permission_denied", "socket_unavailable", "socket_replaced",
            }
        return reason in {
            "socket_send_failed", "server_restarted", "legacy_result_untracked",
            "dispatch_not_confirmed", "state_persist_failed", "superseded",
        }

    def _update_latest_delivery(self, operation: dict[str, Any]) -> None:
        record = self.state["devices"].get(operation.get("device_id"))
        delivery = record.get("delivery") if isinstance(record, dict) else None
        if not isinstance(delivery, dict) or delivery.get("operation_id") != operation.get("operation_id"):
            return
        for key in ("revision", "status", "reason", "attempted_at", "updated_at"):
            delivery[key] = operation.get(key)

    def _prune_operations(self) -> None:
        latest_ids = {
            record.get("delivery", {}).get("operation_id")
            for record in self.state["devices"].values()
            if isinstance(record, dict) and isinstance(record.get("delivery"), dict)
        }
        def updated_at(item: tuple[str, dict[str, Any]]) -> float:
            value = item[1].get("updated_at", 0.0)
            return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) else 0.0

        old_terminal = sorted(
            (
                (operation_id, operation)
                for operation_id, operation in self.state["operations"].items()
                if isinstance(operation, dict)
                and operation_id not in latest_ids
                and operation.get("status") != "dispatching"
            ),
            key=lambda item: (updated_at(item), item[0]),
        )
        for operation_id, _operation in old_terminal[:-MAX_OLD_DELIVERY_OPERATIONS]:
            self.state["operations"].pop(operation_id, None)

    def state_view(self, device_ids: set[str], online_ids: set[str]) -> dict[str, Any]:
        rows: dict[str, Any] = {}
        for device_id in sorted(device_ids):
            record = self.state["devices"].get(device_id, {})
            rows[device_id] = {
                "delivery": copy.deepcopy(record.get("delivery")),
                "online": device_id in online_ids,
            }
        return rows
