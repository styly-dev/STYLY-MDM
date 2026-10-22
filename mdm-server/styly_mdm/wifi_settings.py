"""Validation and registry persistence for Wi-Fi profiles."""

from __future__ import annotations

import copy
import json
import uuid
from contextlib import contextmanager
from typing import Any, Callable

MAX_WIFI_NETWORKS = 32
MAX_SSID_BYTES = 32


class WiFiSettingsError(ValueError):
    """A validation or state transition error safe to return to an API client."""

    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.status = status


class WiFiStoreError(RuntimeError):
    """The saved state could not be safely read or persisted."""


def normalize_revision(value: Any) -> str | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = uuid.UUID(value)
        return value if parsed.version == 4 and str(parsed) == value else None
    except (ValueError, AttributeError):
        return None


WIFI_REASONS = {
    "NONE", "PERMISSION_DENIED", "UNSUPPORTED", "CONFIG_READ_FAILED",
    "UPDATE_FAILED", "VERIFY_FAILED", "TIMEOUT_ROLLED_BACK", "RESTORE_FAILED",
    "BUSY", "UNREACHABLE", "UNKNOWN",
}


def public_wifi_state(raw: Any) -> dict | None:
    """Accept only secret-free device telemetry; never forward arbitrary fields."""
    if not isinstance(raw, dict):
        return None
    if (type(raw.get("busy")) is not bool or type(raw.get("available")) is not bool or
        (raw.get("managed") is not None and type(raw["managed"]) is not bool)):
        return None
    revision = raw.get("policy_revision")
    if revision is not None and normalize_revision(revision) is None:
        return None
    result = raw.get("last_result")
    if result is not None:
        if (not isinstance(result, dict) or
            normalize_revision(result.get("operation_id")) is None or
            normalize_revision(result.get("revision")) is None or
            result.get("status") not in ("success", "failed", "unknown") or
            result.get("reason") not in tuple(WIFI_REASONS) or
            type(result.get("rolled_back")) is not bool):
            return None
        result = {key: result[key] for key in (
            "operation_id", "revision", "status", "reason", "rolled_back",
        )}
    reason = raw.get("unavailable_reason")
    if reason is not None and reason not in tuple(WIFI_REASONS | {"INITIALIZING", "STORAGE_ERROR"}):
        return None
    return {
        "managed": raw.get("managed"), "policy_revision": revision, "last_result": result,
        "busy": raw["busy"], "available": raw["available"], "unavailable_reason": reason,
    }


def validate_profile_networks(
    raw_networks: Any,
    previous: list[dict[str, Any]] | None = None,
) -> list[dict[str, Any]]:
    """Validate submitted networks and preserve saved PSKs omitted by the form."""
    if not isinstance(raw_networks, list) or not raw_networks:
        raise WiFiSettingsError("networks must be a non-empty list")
    if len(raw_networks) > MAX_WIFI_NETWORKS:
        raise WiFiSettingsError(f"networks may contain at most {MAX_WIFI_NETWORKS} entries")

    previous_by_identity = {
        (item.get("ssid"), item.get("security")): item
        for item in previous or [] if isinstance(item, dict)
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

        result.append({"ssid": ssid, "security": security, "password": password, "hidden": hidden})
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


def decrypt_legacy_wifi_profiles(encrypted_state: str, key: str | None) -> dict:
    """Read the former prototype format only when its optional dependency is present."""
    if not key or not isinstance(encrypted_state, str):
        raise WiFiStoreError("saved encrypted Wi-Fi profiles require their original key")
    try:
        from cryptography.fernet import Fernet, InvalidToken
    except ImportError:
        raise WiFiStoreError("install cryptography once to migrate encrypted Wi-Fi profiles") from None
    try:
        return json.loads(Fernet(key.encode("ascii")).decrypt(encrypted_state.encode("ascii")))
    except (InvalidToken, ValueError, TypeError, UnicodeError):
        raise WiFiStoreError("saved encrypted Wi-Fi profiles could not be read") from None


class WiFiSettingsStore:
    """Profiles persisted with group metadata in one atomic registry replacement."""

    def __init__(
        self,
        state: dict | None = None,
        persist: Callable[[dict], None] | None = None,
    ):
        self._persist = persist or (lambda _state: None)
        if state is None:
            state = {"version": 1, "profiles": {}}
        if (
            not isinstance(state, dict)
            or type(state.get("version")) is not int
            or state["version"] != 1
            or not isinstance(state.get("profiles"), dict)
        ):
            raise WiFiStoreError("Wi-Fi state has an unsupported format")
        self.state = copy.deepcopy(state)
        for name, profile in self.state["profiles"].items():
            if not isinstance(name, str) or not name or not isinstance(profile, dict):
                raise WiFiStoreError("Wi-Fi profile failed validation")
            mode = profile.get("mode")
            networks = profile.get("networks")
            if (mode not in ("managed", "unmanaged") or
                not isinstance(networks, list) or
                normalize_revision(profile.get("revision")) is None or
                (mode == "managed" and not networks) or
                (mode == "unmanaged" and networks)):
                raise WiFiStoreError("Wi-Fi profile failed validation")
            try:
                if mode == "managed":
                    validate_profile_networks(networks)
            except WiFiSettingsError:
                raise WiFiStoreError("Wi-Fi profile failed validation") from None

    def persist(self) -> None:
        self._persist(copy.deepcopy(self.state))

    @contextmanager
    def _transaction(self):
        previous = self.state
        self.state = copy.deepcopy(previous)
        try:
            yield
            self.persist()
        except Exception:
            self.state = previous
            raise

    def profile(self, group_name: str) -> dict[str, Any] | None:
        profile = self.state["profiles"].get(group_name)
        return copy.deepcopy(profile) if isinstance(profile, dict) else None

    def put_profile(
        self,
        group_name: str,
        networks: list[dict[str, Any]],
        *,
        mode: str = "managed",
    ) -> dict[str, Any]:
        if not isinstance(mode, str) or mode not in {"managed", "unmanaged"} or not isinstance(networks, list):
            raise WiFiSettingsError("invalid Wi-Fi profile mode or networks")
        if (mode == "managed" and not networks) or (mode == "unmanaged" and networks):
            raise WiFiSettingsError("Wi-Fi networks do not match the profile mode")
        profile = {"revision": str(uuid.uuid4()), "mode": mode, "networks": copy.deepcopy(networks)}
        with self._transaction():
            self.state["profiles"][group_name] = profile
        return copy.deepcopy(profile)

    def rename_profile(self, old_name: str, new_name: str) -> None:
        """Stage the rename; the caller saves group metadata and profiles together."""
        if old_name in self.state["profiles"]:
            self.state["profiles"][new_name] = self.state["profiles"].pop(old_name)

    def delete_profile(self, group_name: str) -> None:
        """Stage deletion for the caller's combined registry save."""
        self.state["profiles"].pop(group_name, None)
