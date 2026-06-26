#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# --- helpers ----------------------------------------------------------------
info()  { printf '\033[1;34m[INFO]\033[0m  %s\n' "$1"; }
error() { printf '\033[1;31m[ERROR]\033[0m %s\n' "$1" >&2; exit 1; }

# --- prerequisite checks ----------------------------------------------------
command -v python >/dev/null 2>&1 || error "python not found. Please install Python 3.10+."

# --- environment ------------------------------------------------------------
# Usage: ./run.sh [prod|dev]   (default: prod)
# Dev uses separate discovery/WebSocket ports so a dev server can run on the same
# LAN as production without the two stealing each other's clients. Keep these in
# sync with the client's dev flavor in mdm-client/app/build.gradle (DISCOVERY_PORT,
# DEFAULT_WS_PORT).
ENVIRONMENT="${1:-prod}"

case "$ENVIRONMENT" in
    prod)
        # server.py falls back to the production ports (7071 / 7070) when unset.
        ;;
    dev)
        export MDM_DISCOVERY_PORT=7081
        export MDM_WS_PORT=7080
        ;;
    *)
        error "Unknown environment: $ENVIRONMENT (specify prod or dev)"
        ;;
esac

# --- run --------------------------------------------------------------------
info "Starting STYLY-MDM server (${ENVIRONMENT}) ..."
exec python server.py
