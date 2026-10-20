"""Validation and encrypted persistence for Wi-Fi profiles."""

from __future__ import annotations

import copy
import json
import uuid
from contextlib import contextmanager
from typing import Any, Callable

from cryptography.fernet import Fernet, InvalidToken


MAX_WIFI_NETWORKS = 32
MAX_SSID_BYTES = 32


class WiFiSettingsError(ValueError):
    """A validation or state transition error safe to return to an API client."""

    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.status = status


class WiFiStoreError(RuntimeError):
    """The encrypted state could not be safely read or persisted."""


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


class WiFiSettingsStore:
    """Encrypted profiles persisted with the device registry in one atomic file."""

    def __init__(
        self,
        encryption_key: str | bytes,
        encrypted_state: str | None = None,
        persist: Callable[[str], None] | None = None,
    ):
        if isinstance(encryption_key, str):
            try:
                encryption_key = encryption_key.encode("ascii", errors="strict")
            except UnicodeEncodeError:
                raise WiFiStoreError("invalid encryption key") from None
        try:
            self._fernet = Fernet(encryption_key)
        except (TypeError, ValueError):
            raise WiFiStoreError("invalid encryption key") from None
        self._persist = persist or (lambda _encrypted: None)
        self.state: dict[str, Any] = {"version": 1, "profiles": {}}
        if encrypted_state is not None:
            self._load(encrypted_state)

    def _load(self, encrypted_state: str) -> None:
        try:
            parsed = json.loads(self._fernet.decrypt(encrypted_state.encode("ascii")))
        except (InvalidToken, UnicodeEncodeError, json.JSONDecodeError, UnicodeDecodeError):
            raise WiFiStoreError("encrypted Wi-Fi state failed validation") from None
        if (
            not isinstance(parsed, dict)
            or type(parsed.get("version")) is not int
            or parsed["version"] != 1
            or not isinstance(parsed.get("profiles"), dict)
        ):
            raise WiFiStoreError("encrypted Wi-Fi state has an unsupported format")
        self.state = parsed
        for profile in self.state["profiles"].values():
            if not isinstance(profile, dict):
                raise WiFiStoreError("encrypted Wi-Fi profile failed validation")
            mode = profile.get("mode")
            networks = profile.get("networks")
            if (
                not isinstance(mode, str)
                or mode not in {"managed", "unmanaged"}
                or not isinstance(networks, list)
                or (mode == "managed" and not networks)
                or (mode == "unmanaged" and networks)
            ):
                raise WiFiStoreError("encrypted Wi-Fi profile failed validation")

    def encrypted_state(self) -> str:
        payload = json.dumps(
            self.state, ensure_ascii=False, separators=(",", ":"), sort_keys=True,
        ).encode("utf-8")
        return self._fernet.encrypt(payload).decode("ascii")

    def persist(self) -> None:
        self._persist(self.encrypted_state())

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
