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


class WiFiSettingsError(ValueError):
    """A validation or state transition error safe to return to an API client."""

    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.status = status


class WiFiStoreError(RuntimeError):
    """The encrypted state could not be safely read or persisted."""


def _new_state() -> dict[str, Any]:
    return {"version": 3, "profiles": {}, "devices": {}}


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
        for key in ("profiles", "devices"):
            if not isinstance(parsed.get(key), dict):
                raise WiFiStoreError("encrypted Wi-Fi state failed validation")
        if parsed["version"] == 2:
            operations = parsed.pop("operations", None)
            if not isinstance(operations, dict):
                raise WiFiStoreError("encrypted Wi-Fi state failed validation")
            # Preserve only the latest delivery, including an interrupted socket write.
            for record in parsed["devices"].values():
                delivery = record.get("delivery") if isinstance(record, dict) else None
                if isinstance(delivery, dict):
                    operation = operations.get(delivery.get("operation_id"))
                    if isinstance(operation, dict) and operation.get("status") == "dispatching":
                        delivery["status"] = "dispatching"
                        delivery["reason"] = None
            parsed["version"] = 3
            self.state = parsed
            self._validate_profiles()
            self.save()
        elif parsed["version"] == 3:
            self.state = parsed
            self._validate_profiles()
        else:
            raise WiFiStoreError("encrypted Wi-Fi state has an unsupported format")

    def _validate_profiles(self) -> None:
        for profile in self.state["profiles"].values():
            if not isinstance(profile, dict):
                raise WiFiStoreError("encrypted Wi-Fi profile failed validation")
            mode = profile.get("mode")
            networks = profile.get("networks")
            if mode not in ("managed", "unmanaged") or not isinstance(networks, list):
                raise WiFiStoreError("encrypted Wi-Fi profile failed validation")
            if (mode == "managed" and not networks) or (mode == "unmanaged" and networks):
                raise WiFiStoreError("encrypted Wi-Fi profile failed validation")

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
        if not any(record.get("delivery", {}).get("status") == "dispatching"
                   for record in self.state["devices"].values()):
            return
        with self._transaction():
            for record in self.state["devices"].values():
                delivery = record.get("delivery", {})
                if delivery.get("status") == "dispatching":
                    delivery.update(status="unknown", reason="server_restarted", updated_at=time.time())

    def profile(self, group_name: str) -> dict[str, Any] | None:
        profile = self.state["profiles"].get(group_name)
        if not isinstance(profile, dict):
            return None
        result = copy.deepcopy(profile)
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
        if normalize_revision(revision) is None:
            raise WiFiSettingsError("invalid Wi-Fi revision")
        if not device_ids:
            return []
        attempts: list[dict[str, Any]] = []
        with self._transaction():
            for device_id in device_ids:
                now = time.time()
                delivery = {
                    "operation_id": str(uuid.uuid4()),
                    "revision": revision,
                    "status": "dispatching",
                    "reason": None,
                    "attempted_at": now,
                    "updated_at": now,
                }
                # Persist before sending. Requests retain their own frozen attempt;
                # the store owns only the latest delivery for each device.
                self.state["devices"][device_id] = {"delivery": delivery}
                attempts.append(dict(delivery, group=group_name, device_id=device_id))
        return attempts

    def settle_delivery(self, device_id: str, operation_id: str, status: str, reason: str | None) -> bool:
        if status not in DELIVERY_STATUSES or not self._valid_delivery_reason(status, reason):
            return False
        if not self.is_latest_delivery(device_id, operation_id):
            # A later request owns the durable row. The older request still returns
            # its actual send result without overwriting the latest delivery.
            return True
        delivery = self.state["devices"][device_id]["delivery"]
        if delivery["status"] != "dispatching":
            return delivery["status"] == status and delivery["reason"] == reason
        with self._transaction():
            self.state["devices"][device_id]["delivery"].update(
                status=status, reason=reason, updated_at=time.time(),
            )
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

    def state_view(self, device_ids: set[str], online_ids: set[str]) -> dict[str, Any]:
        rows: dict[str, Any] = {}
        for device_id in sorted(device_ids):
            record = self.state["devices"].get(device_id, {})
            delivery = copy.deepcopy(record.get("delivery"))
            if delivery is not None and delivery["status"] == "dispatching":
                delivery.update(status="unknown", reason="dispatch_not_confirmed")
            rows[device_id] = {
                "delivery": delivery,
                "online": device_id in online_ids,
            }
        return rows
