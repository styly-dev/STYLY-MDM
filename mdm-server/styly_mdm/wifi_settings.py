"""Encrypted durable state and validation for the opt-in Wi-Fi settings feature.

This module contains no HTTP or WebSocket code. Password-bearing profiles and
operation snapshots are encrypted as one Fernet payload before they reach disk.
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

RESULT_REASONS = frozenset({
    "NONE",
    "RESTORE_INFO_MISSING",
    "PERMISSION_DENIED",
    "UNSUPPORTED",
    "CONFIG_READ_FAILED",
    "ADD_FAILED",
    "UPDATE_FAILED",
    "DELETE_FAILED",
    "VERIFY_FAILED",
    "TIMEOUT_ROLLED_BACK",
    "RESTORE_FAILED",
    "BUSY",
    "UNREACHABLE",
    "UNKNOWN",
})


class WiFiSettingsError(ValueError):
    """A validation or state transition error safe to return to an API client."""

    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.status = status


class WiFiStoreError(RuntimeError):
    """The encrypted state could not be safely read or persisted."""


def _new_state() -> dict[str, Any]:
    return {"version": 1, "profiles": {}, "operations": {}, "devices": {}}


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
    """A small encrypted JSON store with atomic replacement and fixed result enums."""

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
        if not isinstance(parsed, dict) or parsed.get("version") != 1:
            raise WiFiStoreError("encrypted Wi-Fi state has an unsupported format")
        for key in ("profiles", "operations", "devices"):
            if not isinstance(parsed.get(key), dict):
                raise WiFiStoreError("encrypted Wi-Fi state failed validation")
        self.state = parsed

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
        if any(
            operation.get("unresolved") is True
            and (operation.get("status") != "unknown" or not operation.get("server_restarted"))
            for operation in self.state["operations"].values()
        ):
            with self._transaction():
                for operation in self.state["operations"].values():
                    if operation.get("unresolved") is True:
                        if operation.get("status") == "unknown" and operation.get("server_restarted"):
                            continue
                        operation["status"] = "unknown"
                        operation["reason"] = "UNKNOWN"
                        operation["server_restarted"] = True
                        operation["updated_at"] = time.time()
                        device_id = operation.get("device_id")
                        if isinstance(device_id, str):
                            record = self.state["devices"].setdefault(device_id, {})
                            record["manual"] = {
                                "operation_id": operation.get("operation_id"),
                                "revision": operation.get("revision"),
                                "status": "unknown",
                                "reason": "UNKNOWN",
                                "rolled_back": False,
                            }

    def profile(self, group_name: str) -> dict[str, Any] | None:
        profile = self.state["profiles"].get(group_name)
        return copy.deepcopy(profile) if isinstance(profile, dict) else None

    def put_profile(self, group_name: str, networks: list[dict[str, Any]]) -> dict[str, Any]:
        revision = str(uuid.uuid4())
        profile = {"revision": revision, "networks": copy.deepcopy(networks)}
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

    def unresolved_for_device(self, device_id: str) -> bool:
        return any(
            item.get("device_id") == device_id and item.get("unresolved") is True
            for item in self.state["operations"].values()
        )

    def unresolved_for_group(self, group_name: str) -> bool:
        return any(
            item.get("group") == group_name and item.get("unresolved") is True
            for item in self.state["operations"].values()
        )

    def create_operations(
        self,
        group_name: str,
        revision: str,
        networks: list[dict[str, Any]],
        device_ids: list[str],
    ) -> list[dict[str, Any]]:
        busy = [device_id for device_id in device_ids if self.unresolved_for_device(device_id)]
        if busy:
            raise WiFiSettingsError("one or more devices already have an unresolved Wi-Fi operation", 409)

        operations: list[dict[str, Any]] = []
        with self._transaction():
            for device_id in device_ids:
                operation_id = str(uuid.uuid4())
                operation = {
                    "operation_id": operation_id,
                    "group": group_name,
                    "device_id": device_id,
                    "revision": revision,
                    "networks": copy.deepcopy(networks),
                    "status": "running",
                    "reason": "UNKNOWN",
                    "rolled_back": False,
                    "accepted": False,
                    "verify_challenge": None,
                    "verify_acknowledged": False,
                    "unresolved": True,
                    "server_restarted": False,
                    "created_at": time.time(),
                    "updated_at": time.time(),
                }
                self.state["operations"][operation_id] = operation
                record = self.state["devices"].setdefault(device_id, {})
                record["manual"] = {
                    "operation_id": operation_id,
                    "revision": revision,
                    "status": "running",
                    "reason": "UNKNOWN",
                    "rolled_back": False,
                }
                operations.append(copy.deepcopy(operation))
        return operations

    def mark_accepted(self, operation_id: str, device_id: str) -> bool:
        operation = self.state["operations"].get(operation_id)
        if not self._matches(operation, device_id) or operation.get("unresolved") is not True:
            return False
        if operation.get("accepted") is True:
            return True
        with self._transaction():
            operation = self.state["operations"][operation_id]
            operation["accepted"] = True
            operation["updated_at"] = time.time()
        return True

    def set_verify_challenge(self, operation_id: str, device_id: str, challenge: str) -> bool:
        operation = self.state["operations"].get(operation_id)
        if not self._matches(operation, device_id) or operation.get("unresolved") is not True:
            return False
        with self._transaction():
            operation = self.state["operations"][operation_id]
            operation["verify_challenge"] = challenge
            operation["verify_acknowledged"] = True
            operation["updated_at"] = time.time()
        return True

    @staticmethod
    def _matches(operation: Any, device_id: str) -> bool:
        return isinstance(operation, dict) and operation.get("device_id") == device_id

    def settle_manual(
        self,
        operation_id: str,
        device_id: str,
        revision: str,
        status: str,
        reason: str,
        rolled_back: bool,
    ) -> bool:
        operation = self.state["operations"].get(operation_id)
        if (
            not self._matches(operation, device_id)
            or operation.get("revision") != revision
            or status not in {"success", "failed", "unknown"}
            or reason not in RESULT_REASONS
        ):
            return False

        # A previously settled operation is idempotent only for the exact same
        # durable result. Conflicting resends must not be acknowledged.
        if operation.get("unresolved") is not True:
            return (
                operation.get("status") == status
                and operation.get("reason") == reason
                and operation.get("rolled_back") is rolled_back
            )
        if status == "success" and operation.get("verify_acknowledged") is not True:
            return False

        with self._transaction():
            operation = self.state["operations"][operation_id]
            operation["status"] = status
            operation["reason"] = reason
            operation["rolled_back"] = rolled_back
            operation["updated_at"] = time.time()
            # A confirmed failure to restore the previous Wi-Fi configuration
            # still leaves the device in an unresolved state. Keep its operation
            # fence until a later result proves restoration completed.
            restoration_unresolved = status == "failed" and reason == "RESTORE_FAILED" and not rolled_back
            if status != "unknown" and not restoration_unresolved:
                operation["unresolved"] = False
            record = self.state["devices"].setdefault(device_id, {})
            record["manual"] = {
                "operation_id": operation_id,
                "revision": revision,
                "status": status,
                "reason": reason,
                "rolled_back": rolled_back,
            }
            if status == "success":
                record["applied_revision"] = revision
        return True

    def settle_boot(
        self,
        device_id: str,
        boot_id: str,
        revision: str,
        status: str,
        reason: str,
    ) -> bool:
        if (
            not valid_uuid(boot_id)
            or not valid_uuid(revision)
            or status not in {"success", "failed", "unknown"}
            or reason not in RESULT_REASONS
        ):
            return False
        record = self.state["devices"].get(device_id, {})
        history = record.get("boot_history", {}) if isinstance(record, dict) else {}
        previous = history.get(boot_id) if isinstance(history, dict) else None
        if not isinstance(previous, dict):
            latest = record.get("boot") if isinstance(record, dict) else None
            previous = latest if isinstance(latest, dict) and latest.get("boot_id") == boot_id else None
        if previous is not None:
            return (
                previous.get("revision") == revision
                and previous.get("status") == status
                and previous.get("reason") == reason
            )
        with self._transaction():
            record = self.state["devices"].setdefault(device_id, {})
            result = {
                "boot_id": boot_id,
                "revision": revision,
                "status": status,
                "reason": reason,
            }
            history = record.setdefault("boot_history", {})
            if not isinstance(history, dict):
                history = {}
                record["boot_history"] = history
            history[boot_id] = result
            record["boot"] = result
        return True

    def observe(self, device_id: str, revision: str, status: str) -> bool:
        if not valid_uuid(revision) or status not in {"match", "mismatch", "unknown"}:
            return False
        with self._transaction():
            self.state["devices"].setdefault(device_id, {})["observation"] = {
                "revision": revision,
                "status": status,
            }
        return True

    def state_view(self, device_ids: set[str], online_ids: set[str]) -> dict[str, Any]:
        rows: dict[str, Any] = {}
        # A forgotten device's encrypted history remains available for audit, but
        # it no longer appears in the admin's currently known-device view.
        for device_id in sorted(device_ids):
            record = self.state["devices"].get(device_id, {})
            rows[device_id] = {
                "manual": copy.deepcopy(record.get("manual")),
                "boot": copy.deepcopy(record.get("boot")),
                "applied_revision": record.get("applied_revision"),
                "observation": copy.deepcopy(record.get("observation")),
                "online": device_id in online_ids,
            }
        return rows

    def mark_operation_timeout(self, operation_id: str) -> bool:
        operation = self.state["operations"].get(operation_id)
        if not isinstance(operation, dict) or operation.get("unresolved") is not True:
            return False
        if operation.get("status") == "unknown":
            return True
        with self._transaction():
            operation = self.state["operations"][operation_id]
            operation["status"] = "unknown"
            operation["reason"] = "UNKNOWN"
            operation["updated_at"] = time.time()
            operation["server_restarted"] = False
            record = self.state["devices"].setdefault(operation["device_id"], {})
            record["manual"] = {
                "operation_id": operation_id,
                "revision": operation["revision"],
                "status": "unknown",
                "reason": "UNKNOWN",
                "rolled_back": False,
            }
        return True
