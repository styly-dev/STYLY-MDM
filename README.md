# STYLY-MDM for LBE

A lightweight Mobile Device Management (MDM) system purpose-built for Location Based Experience (LBE) — managing standalone VR headsets deployed at venues, events, and attractions. STYLY-MDM lets you launch applications on one or more HMDs simultaneously from a web browser — no physical access to each headset required.

STYLY-MDM currently supports PICO devices (PICO OS with Business Mode). Support for other device platforms is planned for the future.

> **Note:** STYLY-MDM is designed for use within a local area network (LAN). It does not include any authentication mechanism. Do not expose the server to the public internet.

<img width="898" height="913" alt="STYLY-MDM" src="docs/images/screenshot.png" />

## Features

- **Multi-device launch** — select devices with the checkboxes (use the header checkbox to select all) and send an app launch command to them at once; offline devices are skipped automatically
- **Persistent device list** — once an HMD has connected it stays in the list with an `online`/`offline` status instead of vanishing on disconnect; offline devices keep their last-known details, can be pre-labeled, and can be forgotten when decommissioned
- **Auto-reconnect** — both the MDM client and the web console reconnect automatically with exponential backoff
- **Boot persistence** — the MDM client starts automatically on HMD boot via `BOOT_COMPLETED` and PICO auto-boot intents
- **Activity log** — every launch command and result is shown in the web console with timestamps
- **APK deployment** — upload an APK from the web console and install it silently on selected online devices
- **Auto-terminate on switch** — the current foreground app is force-stopped before launching a new one, preventing resource conflicts
- **Server discovery** — devices can automatically find the MDM server on the LAN via UDP broadcast (port 7071), eliminating manual URL entry
- **IP address display** — the server logs its LAN IP addresses on startup so you know exactly where to connect

## Getting Started

### Prerequisites

- Python 3.10 or later
- PICO HMD running PICO OS with Business Mode enabled
- The MDM client APK installed on each HMD (see the [Developer Guide](docs/DEVELOPMENT.md) to build it)

### 1. Start the Control Server

```bash
cd mdm-server
pip install -r requirements.txt
python server.py
```

The server starts on port 7070 and displays its LAN IP addresses:

```
2026-02-24 [INFO] Starting STYLY-MDM server on port 7070
2026-02-24 [INFO]   Server running at http://192.168.1.5:7070
2026-02-24 [INFO] UDP discovery responder listening on port 7071
```

Open `http://<server-ip>:7070` in a browser to access the web console.

### 2. Configure the MDM Client

Install the MDM client APK on the HMD (see the [Developer Guide](docs/DEVELOPMENT.md) for build and install instructions), then:

1. Launch **STYLY-MDM Client** on the HMD.
2. Tap **Discover Server** to automatically find the server on the LAN.
   - If discovery succeeds, the URL field is populated automatically.
   - Alternatively, enter the server URL manually: `ws://<server-ip>:7070/ws/device`
3. Tap **Save & Connect**.

> **Note:** On first launch with no saved URL, the client automatically attempts server discovery before falling back to the default URL.

The MDM client connects to the server, registers the device (serial number, model, IP address), and runs as a foreground service in the background.

### 3. Launch Apps from the Web Console

1. Open `http://<server-ip>:7070` in a browser.
2. Connected HMDs appear in the **Devices** table.
3. Select target devices using the checkboxes (use the header checkbox to select all).
4. Enter the app's **Package Name** (e.g. `com.example.vrapp`) and optional **Extra Data** (JSON string passed to the activity).
5. Click **Launch to Selected**. Offline devices in the selection are skipped automatically.

### 4. Install APKs from the Web Console

1. Open the web console using the server's LAN address, for example `http://192.168.1.5:7070`.
2. Connected HMDs appear in the **Devices** table.
3. Select target devices using the checkboxes (use the header checkbox to select all).
4. In **Install APK**, choose an `.apk` file and click **Upload APK**.
5. After upload completes, click **Install to Selected**. Offline devices in the selection are skipped automatically.

Uploaded APKs are stored under `mdm-server/apks/` and served to devices over the LAN. The MDM client downloads the APK and calls PICO Business SDK's silent install API, so Business Mode and the high-risk API manifest tag are required.

> **All-files access is required.** The PICO ToBService runs as a separate system-user process, so it cannot read APKs from the app's scoped storage — the client must write them to shared storage (`/sdcard/Download/styly-mdm/`). The client therefore declares `MANAGE_EXTERNAL_STORAGE`, which must be granted once per device. Grant it during provisioning with:
>
> ```bash
> adb shell appops set com.styly.mdmclient MANAGE_EXTERNAL_STORAGE allow
> ```
>
> (or toggle **All files access** for the app in the headset's app settings). The client's own settings screen also shows the current grant status and a **Grant All Files Access** button that opens this settings page. Without it, installs fail with `pbsControlAPPManger returned 102: APK does not exist`.

## Documentation

- **[Developer Guide](docs/DEVELOPMENT.md)** — architecture, build instructions, project structure, WebSocket / discovery protocol references, client permissions, and version requirements.
