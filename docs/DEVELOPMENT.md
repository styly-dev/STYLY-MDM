# Developer Guide

Developer-oriented documentation for STYLY-MDM: build instructions, project layout, and protocol references.

For end-user setup and usage, see the [README](../README.md).

## Architecture

![Architecture](architecture.drawio.svg)

| Component | Location | Technology |
|---|---|---|
| Control Server | `mdm-server/` | Python 3.10+, aiohttp |
| MDM Client | `mdm-client/` | Kotlin, OkHttp, PICO Business SDK |
| Web Console | `mdm-server/static/` | Vanilla HTML / CSS / JS |

## Message Flow

![Message Flow](message-flow.drawio.svg)

## Building the MDM Client

### Build from CLI

```bash
cd mdm-client
./build.sh                # prod debug (default)
./build.sh release        # prod release
./build.sh debug dev      # dev debug   — separate discovery port (see below)
./build.sh release dev    # dev release
```

Usage: `./build.sh [debug|release] [prod|dev]` (defaults: `debug prod`).

The generated APK is output to `mdm-client/app/build/outputs/apk/<flavor>/<type>/`,
e.g. `apk/prod/debug/app-prod-debug.apk` or `apk/dev/debug/app-dev-debug.apk`.

### Build from Android Studio

1. Open the `mdm-client/` directory in Android Studio.
2. Run `Build → Build Bundle(s) / APK(s) → Build APK(s)`.

### Install the APK

```bash
adb install mdm-client/app/build/outputs/apk/prod/debug/app-prod-debug.apk
```

> **Dev Container:** A ready-to-use build environment is provided via [Dev Containers](https://containers.dev/). Open this repository in VS Code with the Dev Containers extension (or GitHub Codespaces) and all prerequisites (JDK 17, Android SDK 33, build-tools, Gradle) are set up automatically — no local installation needed.

## Running a Dev Environment Alongside Production

Only one production server may exist per LAN (clients connect to the first server
that answers discovery). During development you often want a separate dev server and
dev client on the **same** network without disturbing production. The `dev` build
flavor and a pair of server environment variables isolate the two by using different
ports, so neither environment discovers the other.

| Environment | Discovery port | WebSocket port | Client applicationId |
|---|---|---|---|
| Production (default) | 7071 | 7070 | `com.styly.mdmclient` |
| Development (`dev` flavor) | 7081 | 7080 | `com.styly.mdmclient.dev` |

**Dev server** — start with the `dev` argument, which sets the dev ports for you:

```bash
cd mdm-server
./run.sh dev      # dev ports (7081 / 7080); ./run.sh (or "prod") uses production ports
```

`run.sh dev` simply exports `MDM_DISCOVERY_PORT=7081` / `MDM_WS_PORT=7080` before
launching `server.py`. You can still set those variables yourself if you prefer.
When unset, `server.py` falls back to the production ports, so production startup is
unchanged.

**Dev client** — build the `dev` flavor:

```bash
cd mdm-client
./build.sh debug dev      # or: ./build.sh release dev
adb install app/build/outputs/apk/dev/debug/app-dev-debug.apk
```

The dev APK uses applicationId `com.styly.mdmclient.dev` and label **STYLY-MDM Dev**,
so it installs side-by-side with the production build on the same headset. It only
broadcasts/listens on the dev discovery port (7081), so it never discovers the
production server. Its fallback URL also targets the dev WebSocket port (7080), so
even if discovery times out it cannot accidentally connect to a production server.

The ports come from `BuildConfig` fields defined per flavor in
`mdm-client/app/build.gradle` (`DISCOVERY_PORT`, `DEFAULT_WS_PORT`).

> **Device owner:** Android allows only one device owner per device. If you provision
> the dev build as device owner for testing, the production build cannot hold
> device-owner powers at the same time.

## Project Structure

```
STYLY-MDM/
├── mdm-server/
│   ├── server.py           # WebSocket control server (aiohttp)
│   ├── requirements.txt    # Python dependencies
│   └── static/
│       └── index.html      # Web management console
└── mdm-client/
    └── app/src/main/
        ├── AndroidManifest.xml
        └── java/com/styly/mdmclient/
            ├── MdmClientApplication.kt   # Application entry point
            ├── MdmClientService.kt       # Foreground service; executes launch commands
            ├── WebSocketManager.kt       # WebSocket connection with auto-reconnect
            ├── SettingsActivity.kt       # UI to configure server URL
            ├── ServerDiscovery.kt        # UDP broadcast server discovery
            └── BootReceiver.kt           # Auto-start on device boot
```

## WebSocket Protocol Reference

### Device → Server

| Message type | Description |
|---|---|
| `REGISTER` | Sent on connect. Fields: `device_id`, `model`, `ip` |
| `LAUNCH_RESULT` | Result of an app launch. Fields: `status` (`success`/`fail`), `package_name`, `error` (optional) |
| `INSTALL_RESULT` | Result of an APK install. Fields: `status` (`success`/`fail`), `apk_filename`, `result_code` (optional), `error` (optional) |

### Server → Device

| Message type | Description |
|---|---|
| `EXECUTE_LAUNCH` | Launch an app. Fields: `package_name`, `extra` |
| `EXECUTE_INSTALL` | Download and install an APK. Fields: `apk_url`, `apk_filename` |

### Admin → Server

| Message type | Description |
|---|---|
| `LAUNCH_APP` | Launch an app on target devices. Fields: `target_devices` (list of device IDs or `["*"]`), `package_name`, `extra_data` |
| `INSTALL_APK` | Install an uploaded APK on target devices. Fields: `target_devices` (list of device IDs or `["*"]`), `apk_url`, `apk_filename` |
| `GET_DEVICE_LIST` | Request the current device list |

### Admin HTTP API

| Endpoint | Description |
|---|---|
| `POST /api/apks` | Multipart upload with field `apk`. Returns `apk_url`, `apk_filename`, and `size`. |
| `GET /apks/{filename}` | Serves uploaded APK files to devices on the LAN. |

### Server → Admin

| Message type | Description |
|---|---|
| `DEVICE_LIST` | Current list of connected devices. Fields: `devices` (array) |
| `LAUNCH_SENT` | Confirmation that commands were dispatched. Fields: `package_name`, `sent_count`, `target_count` |
| `INSTALL_SENT` | Confirmation that install commands were dispatched. Fields: `apk_filename`, `apk_url`, `sent_count`, `target_count` |
| `LAUNCH_RESULT` | Forwarded result from a device |
| `INSTALL_RESULT` | Forwarded install result from a device |
| `ERROR` | Error message. Fields: `message` |

## Server Discovery Protocol

STYLY-MDM supports automatic server discovery via UDP broadcast on the LAN.

| Step | Direction | Detail |
|---|---|---|
| 1 | Client → Broadcast | Send `STYLYMDM_DISCOVER` as UTF-8 to `255.255.255.255:7071` (UDP) |
| 2 | Server → Client | Respond with JSON: `{"service": "stylymdm", "ws_url": "ws://<ip>:7070/ws/device", "version": "1.0"}` |

The client waits up to 3 seconds for a response. If no server replies, discovery fails silently and the client falls back to the default or saved URL.

> The ports above are the production defaults. A development server/client uses
> port 7081 (discovery) and 7080 (WebSocket) instead — see
> [Running a Dev Environment Alongside Production](#running-a-dev-environment-alongside-production).

## MDM Client Permissions

The MDM client requires the following Android permissions:

| Permission | Purpose |
|---|---|
| `INTERNET` | WebSocket connection to the control server |
| `FOREGROUND_SERVICE` | Run as a persistent background service |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on device boot |
| `ACCESS_NETWORK_STATE` | Monitor network connectivity |
| `ACCESS_WIFI_STATE` | Retrieve the device IP address |
| `MANAGE_EXTERNAL_STORAGE` | Write downloaded APKs to shared storage so the PICO ToBService can read them for silent install |

## Requirements

| Component | Minimum version |
|---|---|
| Python | 3.10 |
| aiohttp | 3.9 |
| Android (MDM client) | API 29 (Android 10) |
| OkHttp | 4.x |
| PICO OS | Business Mode enabled |
