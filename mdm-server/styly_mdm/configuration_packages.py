"""PICO 4 Ultra Enterprise configuration packages, validation, and public projections."""

import copy
import uuid

from .wifi_settings import (
    WIFI_REASONS,
    WiFiSettingsError,
    normalize_revision,
    public_networks,
    validate_profile_networks,
)

CAPABILITY = "configuration_packages_v1"
SUMMARY_CAPABILITY = "configuration_summary_v1"
VERSION = 1
MAX_PACKAGE_NAME_LEN = 64
_REASONS = WIFI_REASONS | {
    "READ_FAILED", "WRITE_FAILED", "READBACK_MISMATCH", "INVALID_VALUE",
    "DELAY_CONFLICT", "UPDATE_TIMER_ACTIVE", "CALLBACK_TIMEOUT", "STORAGE_ERROR",
    "UNSUPPORTED_HIDDEN_NETWORK",
    "UNSUPPORTED_PRIORITY_NETWORK_LIMIT",
}


class PackageError(ValueError):
    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.status = status


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
    {"id": "basic", "title": "Basic settings", "description": "Display, sleep and power", "items": [
        _item("brightness", "Screen brightness", "Screen brightness level.", "range", 70, min=1, max=100),
        _item("autoSleep", "Auto sleep", "Enable automatic sleep.", "bool", False),
        _item("sleepDelay", "Sleep delay", "Time before the headset sleeps.", "delay", -1,
              options=_delays(-1, 300, 600, 1800)),
        _item("screenOff", "Screen off delay", "Time before the screen turns off.", "delay", -1,
              options=_delays(-1, 30, 60, 300, 600)),
        _item("shutdown", "Scheduled shutdown", "Set the local time and days for shutdown.",
              "schedule", {"enabled": False, "hour": 0, "minute": 0, "repeat": 127}),
    ]},
    {"id": "network", "title": "Network & connectivity", "description": "Wi-Fi and Bluetooth", "items": [
        _item("wifi", "Wi-Fi networks", "Prefer networks in the listed order and optionally return to a higher-priority network.",
              "wifi", {"networks": [], "auto_return": False}),
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
    {"id": "advanced", "title": "USB & advanced", "description": "USB, file copying and developer options", "items": [
        _item("usb", "USB debugging", "Enable USB debugging.", "bool", False),
        _item("usbCopy", "Auto-copy from USB", "Automatically import files from a connected USB device.",
              "bool", False),
        _item("wirelessAdb", "Wireless USB ADB", "Enable wireless USB ADB.", "bool", False),
        _item("developerMenu", "Developer options menu",
              "Show the Developer tab in PICO Settings. Requires WRITE_SECURE_SETTINGS.", "bool", False),
    ]},
]

_ITEMS = {
    item["id"]: item for section in SECTIONS for item in section["items"]
    if item["kind"] != "unavailable"
}
_LEGACY_SCALAR_VALUES = {
    "brightness": frozenset({0}),
    "sleepDelay": frozenset({15, 30, 60}),
    "screenOff": frozenset({3, 10}),
}


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
    if kind == "bool":
        return type(value) is bool
    if kind == "range":
        return _bounded_int(value, item["min"], item["max"])
    if kind == "delay":
        return type(value) is int and any(value == option["value"] for option in item["options"])
    return kind == "schedule" and _valid_schedule(value, require_repeat=require_repeat)


def _validate_delays(settings):
    screen = settings.get("screenOff")
    sleep = settings.get("sleepDelay")
    if screen is not None and sleep is not None and sleep != -1 and (screen == -1 or screen > sleep):
        raise PackageError("Screen off delay must not exceed sleep delay")


def _validate_wifi(value, *, managed, previous=None):
    if not isinstance(value, dict) or set(value) != {"networks", "auto_return"}:
        raise PackageError("Wi-Fi value must contain networks and auto_return")
    auto_return = value["auto_return"]
    if type(auto_return) is not bool:
        raise PackageError("auto_return must be a boolean")
    previous_networks = previous.get("networks") if isinstance(previous, dict) else None
    try:
        networks = validate_profile_networks(
            value["networks"], previous_networks, allow_empty=not managed,
        )
    except WiFiSettingsError as exc:
        raise PackageError(str(exc)) from None
    if managed and auto_return and any(network["hidden"] for network in networks):
        raise PackageError("Automatic Wi-Fi return is not supported with hidden networks")
    return {"networks": networks, "auto_return": auto_return}


def _validate_value(key, value, *, managed=True, previous=None):
    item = _ITEMS.get(key)
    if item is None:
        raise PackageError("Settings contain an unsupported item")
    if key == "wifi":
        return _validate_wifi(value, managed=managed, previous=previous)
    if not _valid_value(item, value):
        raise PackageError(f"Invalid value for {item['label']}")
    return value


def _is_legacy_scalar_value(key, value):
    return type(value) is int and value in _LEGACY_SCALAR_VALUES.get(key, ())


def has_legacy_values(settings):
    """Whether a flat published settings snapshot contains retired scalar values."""
    return isinstance(settings, dict) and any(
        _is_legacy_scalar_value(key, value) for key, value in settings.items()
    )


def _validate_draft(settings, previous=None, *, allow_legacy=False):
    if not isinstance(settings, dict):
        raise PackageError("settings must be an object")
    previous = previous or {}
    result = {}
    for key, entry in settings.items():
        if (not isinstance(entry, dict) or set(entry) != {"managed", "value"}
                or type(entry["managed"]) is not bool):
            raise PackageError("Each setting must have managed and value fields")
        managed = entry["managed"]
        raw_value = entry["value"]
        value = (raw_value if allow_legacy and _is_legacy_scalar_value(key, raw_value)
                 else _validate_value(key, raw_value, managed=managed,
                                      previous=previous.get(key, {}).get("value")))
        result[key] = {"managed": managed, "value": value}
    managed = managed_settings(result)
    _validate_delays(managed)
    return result


def validate_draft(settings, previous=None):
    """Validate new draft input using only the current setting catalog."""
    return _validate_draft(settings, previous)


def managed_settings(settings):
    return {key: item["value"] for key, item in settings.items() if item["managed"]}


def validate_name(value):
    if not isinstance(value, str) or not value.strip() or len(value.strip()) > MAX_PACKAGE_NAME_LEN:
        raise PackageError(f"Package name must contain 1 to {MAX_PACKAGE_NAME_LEN} characters")
    return value.strip()


def public_summary(package_id, profile):
    published = profile.get("published")
    summary = {
        "package_id": package_id,
        "name": profile["name"],
        "has_draft": "draft" in profile,
        "published_revision": published["revision"] if published else None,
    }
    if published and has_legacy_values(published["settings"]):
        summary["publication_requires_review"] = True
    return summary


def public_profile(package_id, profile, impact):
    draft = profile.get("draft")
    published = profile.get("published")
    settings = copy.deepcopy(draft["settings"]) if draft else {}
    if "wifi" in settings:
        settings["wifi"]["value"]["networks"] = public_networks(settings["wifi"]["value"]["networks"])
    result = {
        "package_id": package_id,
        "name": profile["name"],
        "draft_revision": draft["revision"] if draft else None,
        "published_revision": published["revision"] if published else None,
        "published_keys": list(published["settings"]) if published else [],
        "settings": settings,
        "sections": SECTIONS,
        "impact": impact,
    }

    if published and has_legacy_values(published["settings"]):
        result["publication_requires_review"] = True
    return result


def check_expected_revision(body, draft):
    if "expected_revision" not in body:
        raise PackageError("expected_revision is required")
    expected = body["expected_revision"]
    if expected is not None and normalize_revision(expected) is None:
        raise PackageError("expected_revision must be a canonical revision UUID")
    if expected != (draft["revision"] if draft is not None else None):
        raise PackageError("The saved package draft changed or is missing. Reload the package.", 409)


def new_snapshot(settings):
    return {"revision": str(uuid.uuid4()), "settings": copy.deepcopy(settings)}


def _load_catalog(raw, key):
    if (not isinstance(raw, dict) or type(raw.get("version")) is not int
            or raw["version"] != VERSION or not isinstance(raw.get(key), dict)):
        raise PackageError("Unsupported configuration format")
    return raw[key]


def _validate_snapshots(snapshots, published_phase):
    for phase, snapshot in snapshots.items():
        if (phase not in ("draft", published_phase) or not isinstance(snapshot, dict)
                or normalize_revision(snapshot.get("revision")) is None
                or not isinstance(snapshot.get("settings"), dict)):
            raise PackageError("Invalid saved configuration snapshot")
        if phase == "draft":
            _validate_draft(snapshot["settings"], allow_legacy=True)
        else:
            if "deployed_at_ms" in snapshot and (
                not _bounded_int(snapshot["deployed_at_ms"], 1, 253402300799999)
            ):
                raise PackageError("Invalid configuration deployment time")
            values = {
                key: value if _is_legacy_scalar_value(key, value) else _validate_value(key, value)
                for key, value in snapshot["settings"].items()
            }
            _validate_delays(values)


def load_packages(raw):
    packages = _load_catalog(raw, "packages")
    for package_id, profile in packages.items():
        if normalize_revision(package_id) is None or not isinstance(profile, dict):
            raise PackageError("Invalid saved package")
        if validate_name(profile.get("name")) != profile["name"]:
            raise PackageError("Invalid saved package name")
        _validate_snapshots({key: value for key, value in profile.items() if key != "name"}, "published")
    return packages


def migrate_group_settings(raw):
    packages = {}
    assignments = {}
    for name, profile in _load_catalog(raw, "profiles").items():
        if validate_name(name) != name or not isinstance(profile, dict):
            raise PackageError("Invalid saved group settings")
        _validate_snapshots(profile, "deployed")
        package_id = str(uuid.uuid4())
        packages[package_id] = {
            "name": name,
            **{"published" if phase == "deployed" else phase: snapshot for phase, snapshot in profile.items()},
        }
        assignments[name] = package_id
    return packages, assignments


def public_configuration_state(raw):
    """Allow only bounded, non-credential status fields from device reports."""
    if (not isinstance(raw, dict) or normalize_revision(raw.get("package_id")) is None
            or normalize_revision(raw.get("revision")) is None
            or raw.get("status") not in ("applying", "complete", "interrupted", "already_attempted")
            or not isinstance(raw.get("results"), dict)):
        return None
    state = {"package_id": raw["package_id"], "revision": raw["revision"], "status": raw["status"]}
    results = {}
    if raw["status"] == "already_attempted":
        return {**state, "results": results}
    for key, item in _ITEMS.items():
        result = raw["results"].get(key)
        if not isinstance(result, dict) or result.get("status") not in ("applied", "failed", "unsupported"):
            continue
        clean = {"status": result["status"]}
        value = result.get("value")
        if _valid_value(item, value, require_repeat=False):
            clean["value"] = value
        reason = result.get("reason")
        if isinstance(reason, str) and reason in _REASONS:
            clean["reason"] = reason
        if type(result.get("rolled_back")) is bool:
            clean["rolled_back"] = result["rolled_back"]
        results[key] = clean
    return {**state, "results": results}
