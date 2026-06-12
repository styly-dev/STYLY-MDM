#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# --- helpers ----------------------------------------------------------------
info()  { printf '\033[1;34m[INFO]\033[0m  %s\n' "$1"; }
ok()    { printf '\033[1;32m[OK]\033[0m    %s\n' "$1"; }
error() { printf '\033[1;31m[ERROR]\033[0m %s\n' "$1" >&2; exit 1; }

# --- prerequisite checks ----------------------------------------------------
command -v java >/dev/null 2>&1 || error "Java not found. Please install JDK 17."

if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    error "ANDROID_HOME or ANDROID_SDK_ROOT is not set."
fi

# --- build -------------------------------------------------------------------
BUILD_TYPE="${1:-debug}"

case "$BUILD_TYPE" in
    debug)   TASK="assembleDebug"   ;;
    release) TASK="assembleRelease" ;;
    *)       error "Unknown build type: $BUILD_TYPE (specify debug or release)" ;;
esac

info "Building MDM Client APK (${BUILD_TYPE}) ..."
# Use the Gradle wrapper, not a system-wide `gradle`. The wrapper pins the Gradle
# version (gradle/wrapper/gradle-wrapper.properties) that the configured AGP requires;
# a mismatched system Gradle fails the AGP version check.
./gradlew "$TASK"

# --- output ------------------------------------------------------------------
APK=$(find app/build/outputs/apk/"$BUILD_TYPE" -name "*.apk" 2>/dev/null | head -1)

if [ -z "$APK" ]; then
    error "APK not found."
fi

ok "Build complete: $APK ($(du -h "$APK" | cut -f1))"
