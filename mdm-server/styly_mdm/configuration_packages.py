"""PICO 4 Ultra Enterprise configuration packages, validation, and public projections."""

import re

from .lbe_maps import valid_map_reference
from .wifi_settings import (
    WIFI_REASONS,
    WiFiSettingsError,
    normalize_revision,
    validate_profile_networks,
)

CAPABILITY = "configuration_packages_v1"
SUMMARY_CAPABILITY = "configuration_summary_v1"
LBE_MAP_CAPABILITY = "configuration_lbe_map_v1"
VERSION = 3
MAX_PACKAGE_NAME_LEN = 64
MAX_ANDROID_PACKAGE_NAME_LEN = 255
_REASONS = WIFI_REASONS | {
    "READ_FAILED", "WRITE_FAILED", "READBACK_MISMATCH", "INVALID_VALUE",
    "DELAY_CONFLICT", "UPDATE_TIMER_ACTIVE", "CALLBACK_TIMEOUT", "STORAGE_ERROR",
}
_LBE_MAP_RESULT_STATUSES = {
    "MAP_ACTIVE_VERIFIED": {"applied"},
    "MAP_ACTIVE_UNVERIFIED": {"applied"},
    "MAP_ACTIVE_MISMATCH": {"failed"},
    "MAP_VERIFICATION_UNAVAILABLE": {"failed"},
    "MAP_MODE_NOT_APPLIED": {"failed"},
    "MAP_REQUIRED": {"failed"},
    "MAP_DOWNLOAD_FAILED": {"failed"},
    "MAP_IMPORT_FAILED": {"failed"},
}
_LBE_MODE_FAILURE_REASONS = {
    "MAP_REQUIRED", "MAP_NOT_LOADED", "MAP_ACTIVE_MISMATCH", "MAP_VERIFICATION_UNAVAILABLE",
}
_LBE_REPORT_REASONS = set(_LBE_MAP_RESULT_STATUSES) | _LBE_MODE_FAILURE_REASONS


class PackageError(ValueError):
    def __init__(self, message: str, status: int = 400, code: str | None = None):
        super().__init__(message)
        self.status = status
        self.code = code

    def payload(self) -> dict:
        return {"error": str(self), **({"code": self.code} if self.code else {})}


def _item(key, label, description, kind, default, **extra):
    return {"id": key, "label": label, "description": description,
            "kind": kind, "default": default, **extra}


def _delays(*seconds):
    return [
        {"value": value, "label": (
            "Never" if value == -1 else
            f"{value // 60} minute{'s' if value > 60 else ''}" if value >= 60 else
            f"{value} seconds"
        )}
        for value in seconds
    ]


SECTIONS = [
    {"id": "basic", "title": "Basic settings", "description": "Display, lenses, sleep, power and home app", "items": [
        _item("brightness", "Screen brightness", "Screen brightness level.", "range", 70, min=1, max=100),
        _item("ipd", "IPD", "Set lens spacing from 62 to 72 mm in 0.5 mm steps.", "range",
              63, min=62, max=72, step=0.5, unit="mm"),
        _item("autoSleep", "Auto sleep", "Enable automatic sleep.", "bool", False),
        _item("sleepDelay", "Sleep delay", "Time before the headset sleeps.", "delay", -1,
              options=_delays(-1, 300, 600, 1800)),
        _item("screenOff", "Screen off delay", "Time before the screen turns off.", "delay", -1,
              options=_delays(-1, 30, 60, 300, 600)),
        _item("shutdown", "Scheduled shutdown", "Set the local time and days for shutdown.",
              "schedule", {"enabled": False, "hour": 0, "minute": 0, "repeat": 127}),
        _item("startupApp", "PICO home app",
              "Open this app at startup and when you press Home. An empty package name restores the PICO default.",
              "package", ""),
    ]},
    {"id": "network", "title": "Network & connectivity", "description": "Wi-Fi and Bluetooth", "items": [
        _item("wifi", "Wi-Fi networks", "Manage saved Wi-Fi and how the headset connects.",
              "wifi", {"networks": [], "auto_return": False, "prioritize_networks": True}),
        _item("bluetooth", "Bluetooth", "Enable Bluetooth.", "bool", False),
        _item("wifiPower", "Wi-Fi power control", "Keep Wi-Fi connected during sleep.", "bool", True),
    ]},
    {"id": "input", "title": "Input & interaction", "description": "Controllers, gestures and UI", "items": [
        _item("controllerUI", "Controller connection prompt", "Show the controller connection prompt.", "bool", False),
        _item("gesture", "Gesture recognition", "Enable gesture recognition.", "bool", True),
        _item("gestureHome", "Home gesture", "Enable the Home gesture.", "bool", False),
        _item("gestureReset", "Reset gesture", "Enable the reset gesture.", "bool", False),
        _item("volumeUI", "Volume adjustment prompt", "Show the volume adjustment prompt.", "bool", False),
    ]},
    {"id": "safety", "title": "Safety & sensors", "description": "Safety zone and device sensors", "items": [
        _item("securityZone", "Safety zone", "Enable the permanent safety zone.", "bool", False),
        _item("proximity", "Proximity sensor", "Enable the proximity sensor.", "bool", False),
        _item("seeThrough", "Quick see-through mode", "Enable quick see-through mode.", "bool", False),
    ]},
    {"id": "lbe", "title": "LBE", "description": "LBE mode and venue map", "items": [
        _item("lbeMode", "LBE mode", "Turning on may bring the PICO LBE app to the foreground.", "bool", False),
        _item("lbeMap", "LBE map", "Choose a venue ZIP to import when deploying LBE mode on.", "lbe-map", None),
    ]},
    {"id": "advanced", "title": "USB & advanced", "description": "USB, file copying and developer options", "items": [
        _item("usbBoot", "Power on when USB is connected",
              "Automatically start a powered-off headset when USB power is connected.", "bool", False),
        _item("usb", "USB debugging", "Enable USB debugging.", "bool", False),
        _item("usbCopy", "Auto-copy from USB", "Automatically import files from a connected USB device.",
              "bool", False),
        _item("wirelessAdb", "Wireless USB ADB", "Enable wireless USB ADB.", "bool", False),
        _item("developerMenu", "Developer options menu",
              "Show the Developer tab in PICO Settings. Requires WRITE_SECURE_SETTINGS.", "bool", False),
    ]},
]

_ITEMS = {item["id"]: item for section in SECTIONS for item in section["items"]}


def _bounded_int(value, lower, upper):
    return type(value) is int and lower <= value <= upper


def _valid_schedule(value, *, require_repeat):
    return (
        isinstance(value, dict) and set(value) == {"enabled", "hour", "minute", "repeat"}
        and type(value["enabled"]) is bool
        and _bounded_int(value["hour"], 0, 23) and _bounded_int(value["minute"], 0, 59)
        and _bounded_int(value["repeat"], int(require_repeat and value["enabled"]), 127)
    )


def _valid_value(item, value, *, require_repeat=True):
    kind = item["kind"]
    if kind == "package":
        return (isinstance(value, str) and len(value) <= MAX_ANDROID_PACKAGE_NAME_LEN
                and (value == "" or re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+", value) is not None))
    if kind == "bool":
        return type(value) is bool
    if kind == "range":
        if "step" not in item:
            return _bounded_int(value, item["min"], item["max"])
        return (type(value) in (int, float) and item["min"] <= value <= item["max"]
                and (value - item["min"]) % item["step"] == 0)
    if kind == "lbe-map":
        return value is None or valid_map_reference(value)
    if kind == "delay":
        return type(value) is int and any(value == option["value"] for option in item["options"])
    return kind == "schedule" and _valid_schedule(value, require_repeat=require_repeat)


def _validate_delays(settings):
    screen = settings.get("screenOff")
    sleep = settings.get("sleepDelay")
    if screen is not None and sleep is not None and sleep != -1 and (screen == -1 or screen > sleep):
        raise PackageError("Screen off delay must not exceed sleep delay")


def _validate_wifi(value, *, managed):
    if (not isinstance(value, dict) or not {"networks", "auto_return"}.issubset(value)
            or set(value) - {"networks", "auto_return", "prioritize_networks"}):
        raise PackageError("Wi-Fi value must contain networks and auto_return, with optional prioritize_networks")
    auto_return = value["auto_return"]
    if type(auto_return) is not bool:
        raise PackageError("auto_return must be a boolean")
    prioritize_networks = value.get("prioritize_networks", True)
    if type(prioritize_networks) is not bool:
        raise PackageError("prioritize_networks must be a boolean")
    if not prioritize_networks and auto_return:
        raise PackageError("auto_return must be false when prioritize_networks is false")
    try:
        networks = validate_profile_networks(value["networks"], allow_empty=not managed)
    except WiFiSettingsError as exc:
        raise PackageError(str(exc)) from None
    if managed and prioritize_networks and auto_return and any(network["hidden"] for network in networks):
        raise PackageError("Automatic Wi-Fi return is not supported with hidden networks")
    result = {"networks": networks, "auto_return": auto_return}
    if not prioritize_networks:
        result["prioritize_networks"] = False
    return result


def _validate_value(key, value, *, managed):
    item = _ITEMS.get(key)
    if item is None:
        raise PackageError("Settings contain an unsupported item")
    if key == "wifi":
        return _validate_wifi(value, managed=managed)
    if key == "lbeMap" and managed and value is None:
        raise PackageError("Select an LBE map ZIP or turn off map management")
    if not _valid_value(item, value):
        raise PackageError(f"Invalid value for {item['label']}")
    return value


def validate_settings(settings):
    """Validate package settings against the current setting catalog."""
    if not isinstance(settings, dict):
        raise PackageError("settings must be an object")
    result = {}
    for key, entry in settings.items():
        if (not isinstance(entry, dict) or set(entry) != {"managed", "value"}
                or type(entry["managed"]) is not bool):
            raise PackageError("Each setting must have managed and value fields")
        managed = entry["managed"]
        value = _validate_value(key, entry["value"], managed=managed)
        result[key] = {"managed": managed, "value": value}
    managed = managed_settings(result)
    _validate_delays(managed)
    if managed.get("lbeMode") is True and "lbeMap" not in managed:
        raise PackageError("Select an LBE map ZIP before enabling LBE mode")
    return result


def managed_settings(settings):
    managed = {key: item["value"] for key, item in settings.items() if item["managed"]}
    if managed.get("lbeMode") is not True or managed.get("lbeMap") is None:
        managed.pop("lbeMap", None)
    return managed


def validate_name(value):
    if not isinstance(value, str) or not value.strip() or len(value.strip()) > MAX_PACKAGE_NAME_LEN:
        raise PackageError(f"Package name must contain 1 to {MAX_PACKAGE_NAME_LEN} characters")
    return value.strip()


def public_summary(package_id, profile):
    return {
        "package_id": package_id,
        "name": profile["name"],
        "edit_revision": profile["edit_revision"],
        "published_revision": profile["published_revision"],
    }


def editor_profile(package_id, profile, impact):
    return {
        **public_summary(package_id, profile),
        "settings": profile["settings"],
        "sections": SECTIONS,
        "impact": impact,
    }


def check_expected_revision(body, current_revision):
    if "expected_revision" not in body:
        raise PackageError("expected_revision is required")
    expected = body["expected_revision"]
    if expected is not None and normalize_revision(expected) is None:
        raise PackageError("expected_revision must be a canonical revision UUID")
    if expected != current_revision:
        raise PackageError("The package changed. Reload the package.", 409, "revision_conflict")


def load_packages(raw):
    if (not isinstance(raw, dict) or type(raw.get("version")) is not int
            or raw["version"] != VERSION or not isinstance(raw.get("packages"), dict)):
        raise PackageError("Unsupported configuration format")

    packages = raw["packages"]
    for package_id, profile in packages.items():
        if normalize_revision(package_id) is None or not isinstance(profile, dict):
            raise PackageError("Invalid saved package")
        if (set(profile) != {"name", "settings", "edit_revision", "published_revision", "deployed_at_ms"}
                or validate_name(profile.get("name")) != profile["name"]
                or not isinstance(profile["settings"], dict)):
            raise PackageError("Invalid saved package")
        edit_revision = profile["edit_revision"]
        published_revision = profile["published_revision"]
        deployed_at_ms = profile["deployed_at_ms"]
        if (edit_revision is not None and normalize_revision(edit_revision) is None
                or published_revision is not None and normalize_revision(published_revision) is None
                or deployed_at_ms is not None and not _bounded_int(deployed_at_ms, 1, 253402300799999)
                or (deployed_at_ms is not None and published_revision is None)
                or (edit_revision is None and (profile["settings"] or published_revision is not None))):
            raise PackageError("Invalid saved package revisions")
        validate_settings(profile["settings"])
    return packages


def published_settings(profile):
    if profile["published_revision"] is None:
        return {}
    return managed_settings(profile["settings"])


def public_configuration_state(raw):
    """Allow only bounded, non-credential status fields from device reports."""
    if (not isinstance(raw, dict) or normalize_revision(raw.get("package_id")) is None
            or normalize_revision(raw.get("revision")) is None
            or raw.get("status") not in ("applying", "complete", "interrupted")
            or not isinstance(raw.get("results"), dict)):
        return None
    state = {"package_id": raw["package_id"], "revision": raw["revision"], "status": raw["status"]}
    results = {}
    for key, item in _ITEMS.items():
        result = raw["results"].get(key)
        if not isinstance(result, dict):
            continue
        status = result.get("status")
        value = result.get("value")
        reason = result.get("reason")
        if status == "waiting":
            if key == "startupApp" and reason == "APP_NOT_INSTALLED" and value and _valid_value(item, value):
                results[key] = {"status": status, "value": value, "reason": reason}
            continue
        if status not in ("applied", "failed", "unsupported"):
            continue
        clean = {"status": status}
        if value is not None and _valid_value(item, value, require_repeat=False):
            clean["value"] = value
        if isinstance(reason, str) and reason in _LBE_REPORT_REASONS:
            if key == "lbeMap":
                if status not in _LBE_MAP_RESULT_STATUSES.get(reason, set()):
                    continue
            elif key == "lbeMode":
                if status != "failed" or reason not in _LBE_MODE_FAILURE_REASONS:
                    continue
            else:
                continue
            clean["reason"] = reason
        elif key == "startupApp" and status == "failed" and reason == "APP_DISABLED":
            clean["reason"] = reason
        elif isinstance(reason, str) and reason in _REASONS:
            clean["reason"] = reason
        elif key == "ipd" and status == "applied" and reason == "ACCEPTED_UNVERIFIED":
            clean["reason"] = reason
        results[key] = clean
    return {**state, "results": results}
