"""Validation and public telemetry for group Wi-Fi settings."""

from __future__ import annotations

import uuid
from typing import Any

MAX_WIFI_NETWORKS = 4
MAX_SSID_BYTES = 32
PRIORITY_MONITOR_STATES = frozenset({
    "off", "monitoring", "degraded", "switching", "unsupported",
})
WIFI_UNAVAILABLE_REASONS = frozenset({
    "INITIALIZING", "PERMISSION_DENIED", "STORAGE_ERROR", "UNKNOWN", "UNSUPPORTED",
})


class WiFiSettingsError(ValueError):
    """A validation or state transition error safe to return to an API client."""


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
    "UPDATE_FAILED", "VERIFY_FAILED", "SAVED",
    "UNREACHABLE", "UNKNOWN", "UNSUPPORTED_HIDDEN_NETWORK",
    "UNSUPPORTED_PRIORITY_NETWORK_LIMIT",
}


def public_wifi_state(raw: Any) -> dict | None:
    """Accept only secret-free device telemetry; never forward arbitrary fields."""
    if not isinstance(raw, dict) or type(raw.get("busy")) is not bool or type(raw.get("available")) is not bool:
        return None
    reason = raw.get("unavailable_reason")
    if reason is not None and (not isinstance(reason, str) or reason not in WIFI_UNAVAILABLE_REASONS):
        return None
    priority_monitor = raw.get("priority_monitor")
    if not isinstance(priority_monitor, str) or priority_monitor not in PRIORITY_MONITOR_STATES:
        return None
    return {
        "busy": raw["busy"], "available": raw["available"], "unavailable_reason": reason,
        "priority_monitor": priority_monitor,
    }


def validate_profile_networks(
    raw_networks: Any,
    *,
    allow_empty: bool = False,
) -> list[dict[str, Any]]:
    """Validate ordered Wi-Fi networks and their credentials."""
    if not isinstance(raw_networks, list) or (not raw_networks and not allow_empty):
        raise WiFiSettingsError("networks must be a non-empty list")
    if len(raw_networks) > MAX_WIFI_NETWORKS:
        raise WiFiSettingsError(f"networks may contain at most {MAX_WIFI_NETWORKS} entries")

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
        if not isinstance(security, str) or security not in {"OPEN", "WPA2_WPA3"}:
            raise WiFiSettingsError("security must be OPEN or WPA2_WPA3")
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
        elif isinstance(supplied_password, str):
            try:
                encoded = supplied_password.encode("ascii")
            except UnicodeEncodeError:
                raise WiFiSettingsError("WPA2_WPA3 password must be printable ASCII") from None
            if not 1 <= len(encoded) <= 63 or any(byte < 0x20 or byte > 0x7e for byte in encoded):
                raise WiFiSettingsError("WPA2_WPA3 password must be 1 to 63 printable ASCII characters")
            password = supplied_password
        else:
            raise WiFiSettingsError("WPA2_WPA3 networks require a password")

        result.append({"ssid": ssid, "security": security, "password": password, "hidden": hidden})
    return result
