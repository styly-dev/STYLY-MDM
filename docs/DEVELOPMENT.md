# Developer Guide

Developer-oriented documentation for STYLY-MDM: build instructions, project layout, and protocol references.

For end-user setup and usage, see the [README](../README.md).

## Architecture

![Architecture](architecture.drawio.svg)

| Component | Location | Technology |
|---|---|---|
| Control Server | `mdm-server/styly_mdm/` | Python 3.10+, aiohttp |
| MDM Client | `mdm-client/` | Kotlin, OkHttp, PICO Business SDK |
| Web Console | `mdm-server/styly_mdm/static/` | Vanilla HTML / CSS / JS |

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

| Environment | Discovery port | WebSocket port | Client build (label) |
|---|---|---|---|
| Production (default) | 7071 | 7070 | `prod` flavor — "STYLY-MDM Client" |
| Development (`dev` flavor) | 7081 | 7080 | `dev` flavor — "STYLY-MDM Dev" |

Both flavors share the applicationId `com.styly.mdmclient`, so they **cannot be
installed at the same time** — installing the dev build replaces the production build
on a device. Run the dev client on a dedicated test device, or accept that it replaces
production while you test. Isolation between the two environments is purely by port, so
a dev client/server never discovers a production one even on the same LAN.

**Dev server** — start with the `dev` argument, which sets the dev ports for you:

```bash
cd mdm-server
./run.sh dev      # dev ports (7081 / 7080); ./run.sh (or "prod") uses production ports
```

`run.sh dev` simply exports `MDM_DISCOVERY_PORT=7081` / `MDM_WS_PORT=7080` before
launching the server (`python -m styly_mdm`). You can still set those variables
yourself if you prefer. When unset, the server falls back to the production ports,
so production startup is unchanged.

**Dev client** — build the `dev` flavor:

```bash
cd mdm-client
./build.sh debug dev      # or: ./build.sh release dev
adb install app/build/outputs/apk/dev/debug/app-dev-debug.apk
```

The dev APK keeps the production applicationId `com.styly.mdmclient` and only changes
its label to **STYLY-MDM Dev**, so installing it replaces the production build on a
device (they share a package name and cannot coexist). It only broadcasts/listens on
the dev discovery port (7081), so it never discovers the production server. Its
fallback URL also targets the dev WebSocket port (7080), so even if discovery times
out it cannot accidentally connect to a production server.

The ports come from `BuildConfig` fields defined per flavor in
`mdm-client/app/build.gradle` (`DISCOVERY_PORT`, `DEFAULT_WS_PORT`).

> **Device owner:** because dev and prod share an applicationId, a device only ever has
> one STYLY-MDM install, so there is never a dev-vs-prod device-owner conflict on a
> single device. Updating a device-owner production install in place with a dev build
> of the **same signing key** preserves device-owner status; a differently signed dev
> build (e.g. debug over a release install) must be uninstalled first, which clears
> device-owner status, so you would need to re-provision.

### Seeding dummy devices for UI testing

`mdm-server/scripts/seed_dummy_devices.py` populates a **running** server with fake
devices, so you can exercise console UI that only matters at scale (e.g. the Manage
Groups device list scrolling). It is a dev-only helper and is not shipped in the wheel.

It registers through the live `/ws/device` WebSocket rather than editing
`device_registry.json`, because a running server owns that file: any register/battery/
group change triggers `save_registry()`, which rewrites the file from in-memory state
and would clobber a hand-edit. Going through the socket makes the server itself persist
the dummies.

```bash
cd mdm-server
python scripts/seed_dummy_devices.py 20            # add 20 offline dummy devices
python scripts/seed_dummy_devices.py 20 --online   # keep them connected (online) until Ctrl-C
python scripts/seed_dummy_devices.py --remove      # forget every dummy this tool created
```

The port is found by UDP discovery (so it works against a `run.sh dev` server on
7080/7081 as well as a default 7070/7071 one); pass `--port` to target it explicitly.
Repeated runs continue numbering, so counts add up; dummies use the serial prefix
`DUMMY-TEST-` (override with `--prefix`) and are removed by that prefix. Only offline
devices can be forgotten, so stop an `--online` run before `--remove`.

## Project Structure

```
STYLY-MDM/
├── mdm-server/
│   ├── pyproject.toml       # Packaging metadata (published to PyPI as styly-mdm)
│   ├── run.sh               # Dev/prod launcher (python -m styly_mdm)
│   ├── scripts/             # Dev-only helpers (not packaged)
│   │   └── seed_dummy_devices.py  # Add/remove fake devices on a running server
│   ├── styly_mdm/           # Installable package
│   │   ├── __init__.py      # Exports create_app / main
│   │   ├── __main__.py      # `python -m styly_mdm` entrypoint
│   │   ├── server.py        # WebSocket control server (aiohttp) + `styly-mdm hash` CLI
│   │   ├── integrity.py     # APK CD-digest + directory tree-hash (integrity reference)
│   │   └── static/
│   │       └── index.html   # Web management console (bundled package data)
│   └── tests/
│       └── test_app.py      # Smoke tests
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
| `REGISTER` | Sent on connect. Fields: `device_id`, `model`, `ip`, `startup_app` (optional) |
| `BATTERY_UPDATE` | Battery telemetry. Fields: `device_id`, `level` (integer 0-100), `charging` (boolean), `timestamp` (epoch seconds) |
| `LAUNCH_RESULT` | Result of an app launch. Fields: `status` (`success`/`fail`), `package_name`, `error` (optional) |
| `INSTALL_RESULT` | Result of an APK install. Fields: `status` (`success`/`fail`), `apk_filename`, `result_code` (optional), `error` (optional) |
| `DOWNLOAD_COMPLETE` | Sent right after the APK download finishes (before the local install). Frees the server's transfer slot so the next queued device can start downloading. Fields: `apk_filename`. Optional — see the install-throttling note below. |
| `PUSH_FILES_RESULT` | Result of a push or sync. Fields: `status` (`success`/`fail`), `dest_path`, `added`, `updated`, `deleted` (counts; `deleted` is always 0 for a push), `error` (optional) |
| `VERIFY_APK_RESULT` | Result of an APK integrity check. Fields: `package_name`, `found` (boolean), `size`, `cd_sha256`, `full_sha256`, `version_code`, `version_name`, `signer_sha256`, `error` (optional). Absent hash/version fields when `found` is false. |
| `VERIFY_DIR_RESULT` | Result of a directory integrity check. Fields: `path`, `found` (boolean), `tree_hash`, `file_count`, `total_size`, `manifest` (optional array of `{relative_path, size, sha256}`, omitted above a cap), `error` (optional) |

### Server → Device

| Message type | Description |
|---|---|
| `EXECUTE_LAUNCH` | Launch an app. Fields: `package_name`, `extra` |
| `EXECUTE_INSTALL` | Download and install an APK. Fields: `apk_url`, `apk_filename` |
| `EXECUTE_PUSH_FILES` | Download a bundle and apply it to a directory. Fields: `bundle_url`, `bundle_filename`, `dest_path`, `delete_extras` (boolean; `false` = copy/overwrite only, `true` = full mirror. Read with a `false` default — a missing field must never delete) |
| `EXECUTE_VERIFY_APK` | Compute `size` + Central-Directory digest (plus diagnostics) for an installed package. Fields: `package_name` |
| `EXECUTE_VERIFY_DIR` | Compute a manifest + tree hash for a device directory (shared storage only). Fields: `path` |

### Admin → Server

| Message type | Description |
|---|---|
| `LAUNCH_APP` | Launch an app on target devices. Fields: `target_devices` (list of device IDs or `["*"]`), `package_name`, `extra_data` |
| `INSTALL_APK` | Install an uploaded APK on target devices. Fields: `target_devices` (list of device IDs or `["*"]`), `apk_url`, `apk_filename` |
| `PUSH_FILES` | Apply a bundle to a directory on target devices. Fields: `target_devices` (list of device IDs or `["*"]`), `bundle_url`, `bundle_filename`, `dest_path`, `delete_extras` (boolean, optional; only a literal `true` requests a full mirror) |
| `VERIFY_APK` | Verify an installed package against a local reference on target devices. Fields: `target_devices`, `package_name`. The reference (`size` + CD digest) is computed and compared in the browser and is **never** sent to the server. |
| `VERIFY_DIR` | Verify a device directory against a local reference on target devices. Fields: `target_devices`, `path` (absolute, within shared storage). |
| `GET_DEVICE_LIST` | Request the current device list |
| `CREATE_GROUP` | Create a new, empty device group. Fields: `name` |
| `RENAME_GROUP` | Rename a group, preserving its members. Fields: `name`, `new_name` |
| `DELETE_GROUP` | Delete a group (member devices are not affected). Fields: `name` |
| `SET_DEVICE_GROUPS` | Set the exact set of groups a device belongs to. Fields: `device_id`, `groups` (list of existing group names) |
| `SET_GROUP_MEMBERS` | Set the exact member list of an existing group (group-centric). Fields: `name`, `members` (list of serials; offline/unknown serials allowed) |

### Admin HTTP API

| Endpoint | Description |
|---|---|
| `POST /api/apks` | Multipart upload with field `apk`. Returns `apk_url`, `apk_filename`, and `size`. |
| `GET /apks/{filename}` | Serves uploaded APK files to devices on the LAN. |
| `POST /api/bundles` | Multipart upload with repeated field `files`; each part's filename carries its folder-relative path. The server zips the reconstructed tree into a bundle, excluding OS metadata (`.DS_Store`, `._*`, `Thumbs.db`, …). Returns `bundle_url`, `bundle_filename`, `size`, `entry_count` (files in the bundle), and `skipped_count` (files excluded). 400 if every uploaded file was excluded. |
| `GET /bundles/{filename}` | Serves generated file/folder bundles (zip) to devices on the LAN. |

### Server → Admin

| Message type | Description |
|---|---|
| `DEVICE_LIST` | Current list of connected devices. Fields: `devices` (array; each device may include optional `battery`: `{level, charging, last_seen}`) |
| `LAUNCH_SENT` | Confirmation that commands were dispatched. Fields: `package_name`, `sent_count`, `target_count` |
| `INSTALL_SENT` | Confirmation that an install job was accepted (dispatch is throttled and runs in the background). Fields: `apk_filename`, `apk_url`, `target_count`, `max_concurrent` |
| `INSTALL_PROGRESS` | Live progress of a throttled install job, broadcast on each transfer-slot transition. Fields: `apk_filename`, `apk_url`, `total`, `queued`, `transferring`, `transferred`, `failed`, `done` (boolean, `true` on the final update) |
| `INSTALL_DEVICE_STATE` | Per-device companion to `INSTALL_PROGRESS`: names the devices that just entered a state, so the console can label each row instead of showing the whole target set as installing. Fields: `device_ids` (array), `state` (`queued` / `transferring` / `installing` / `fail`), `apk_filename`, `detail` (failure reason, may be empty) |
| `PUSH_FILES_SENT` | Confirmation that push-files commands were dispatched. Fields: `bundle_filename`, `dest_path`, `delete_extras`, `sent_count`, `target_count` |
| `PUSH_FILES_RESULT` | Forwarded file/folder result from a device (adds `device_id`) |
| `LAUNCH_RESULT` | Forwarded result from a device |
| `INSTALL_RESULT` | Forwarded install result from a device |
| `VERIFY_SENT` | Confirmation that verify-APK commands were dispatched. Fields: `package_name`, `sent_count`, `target_count` |
| `VERIFY_DIR_SENT` | Confirmation that verify-directory commands were dispatched. Fields: `path`, `sent_count`, `target_count` |
| `VERIFY_APK_RESULT` / `VERIFY_DIR_RESULT` | Forwarded integrity result from a device (stamped with `device_id`). The console compares it against the local reference. |
| `GROUP_LIST` | Current device groups. Fields: `groups` (object mapping group name → array of member serials). The console derives each device's group membership from this; sent on connect and after any group change. |
| `GROUP_CREATED` / `GROUP_RENAMED` / `GROUP_DELETED` | Acknowledgements for group create / rename / delete. |
| `DEVICE_GROUPS_SET` | Acknowledgement of a device's group membership change. Fields: `device_id`, `groups` |
| `GROUP_MEMBERS_SET` | Acknowledgement of a group's member list change. Fields: `name`, `members` |
| `ERROR` | Error message. Fields: `message` |

> **Device groups** are a many-to-many grouping keyed by device serial, persisted
> server-side in `device_registry.json` (under a `groups` key). Selecting a group
> in the console is a client-side convenience: it sets the device selection to that
> group's members (devices not in the group are deselected), so commands still
> dispatch via the normal `target_devices` path (online members only). Group
> membership can include offline devices.

> **Battery telemetry** is optional for backwards compatibility. Older clients
> that never send `BATTERY_UPDATE` remain valid; their device rows simply omit
> `battery`. New clients send one update immediately after WebSocket connect and
> then every 5 minutes while the foreground service is running. The server stores
> the latest battery state in `device_registry.json`, so offline devices retain
> their last-known battery percentage and charging state.

> **Install transfer throttling.** An `INSTALL_APK` targeting a large group would
> otherwise make every device pull the APK from the server at the same instant (an
> APK can be up to 2 GiB), spiking LAN/server bandwidth. Instead the server gates
> the `EXECUTE_INSTALL` fan-out so at most **N** transfers are in flight per job
> (`MDM_MAX_CONCURRENT_TRANSFERS` env var / `--max-concurrent-transfers` flag,
> default **5**). Remaining targets are queued; each slot frees as soon as its
> device signals the download finished, and the next queued device is dispatched.
> Slot-release triggers, in order of preference:
>
> 1. `DOWNLOAD_COMPLETE` from the client (primary — releases the moment the
>    network-heavy download ends, so the local install proceeds off the critical
>    path).
> 2. `INSTALL_RESULT` (fallback — covers older clients that never emit
>    `DOWNLOAD_COMPLETE`, and clients whose download failed outright).
> 3. Device disconnect (frees the slot immediately).
> 4. A per-device timeout (`MDM_TRANSFER_TIMEOUT` seconds, default **600**) so a
>    silent/stuck device cannot block the queue. Lowering it recovers stuck slots
>    sooner but risks releasing a slow-but-healthy transfer early, which only
>    relaxes throttling and never drops the install itself.
>
> This is fully backward compatible: an older client that ignores the new signal
> still frees its slot via `INSTALL_RESULT` or the timeout, and an older server
> that predates the feature simply logs `DOWNLOAD_COMPLETE` as an unknown message.
> Admins see aggregate progress via `INSTALL_PROGRESS` (queued / transferring /
> transferred / failed counts).

> **Per-device install state.** `INSTALL_PROGRESS` carries only aggregate counts,
> which cannot be mapped back to rows, so the server also broadcasts
> `INSTALL_DEVICE_STATE` as each device moves. The console's PROGRESS column shows
> `Waiting…` → `Transferring…` → `Installing…` → `✓ installed` / `✗ failed`, which
> is what distinguishes a device queued behind a transfer slot from one that is
> genuinely installing.
>
> Which side emits which state is deliberate:
>
> | State | Emitted by |
> |---|---|
> | `queued` | the install job, once for the whole target list |
> | `transferring` | the dispatcher, right after `EXECUTE_INSTALL` is sent |
> | `installing` | the `DOWNLOAD_COMPLETE` **message handler** |
> | `fail` | the dispatcher (offline before its turn, failed dispatch, timeout) |
> | `success` / `fail` | the forwarded terminal `INSTALL_RESULT` |
>
> `installing` must come from the message handler rather than the dispatcher
> coroutine resuming from its released future: the coroutine's resumption would
> race the receive loop processing the client's subsequent `INSTALL_RESULT`, and if
> `installing` landed last the row would spin forever. Handling it in the receive
> loop makes the order structural, since a WebSocket preserves per-connection
> order. `release_transfer_slot()` returns whether it actually freed a live slot,
> so a `DOWNLOAD_COMPLETE` arriving after a transfer already timed out cannot
> resurrect `installing` on a device the job has written off.

> **Push files: two actions, one transport.** The console uploads a file or a whole
> folder to `POST /api/bundles`; the server reconstructs the tree and zips it into a
> single bundle served from `/bundles/`, dropping OS-generated metadata on the way in
> (see below). `PUSH_FILES` carries the bundle URL, a
> destination directory, and `delete_extras`. The client downloads the bundle, unzips
> it (with a zip-slip guard), and applies it to the destination — how it applies is
> the whole distinction:
>
> | Console action | `delete_extras` | Semantics |
> |---|---|---|
> | **Push Files** (file or folder) | `false` | Copy and overwrite. Nothing at the destination is removed; `deleted` is always 0. |
> | **Sync Folder** (folder only) | `true` | Full mirror: extras at the destination — including now-empty directories — are deleted, so it ends up identical to the bundle (`rsync --delete` semantics). |
>
> These are two console panels rather than one panel with a delete toggle so the
> destructive operation has to be chosen **by name**. Only Sync Folder shows the
> warning and requires the "extras will be deleted" confirmation.
>
> The delete lives in exactly one place: `BundleSync.apply` in the client, gated on
> `deleteExtras`. Everything else — the server's `handle_push_files`, the bundle
> build — is identical for both actions and merely passes the flag through. That flag
> is decoded defensively at both boundaries: the server treats only a literal `true`
> as a delete request (`data.get("delete_extras") is True`), and the client reads it
> with `optBoolean(..., false)`. **A missing or malformed field can only ever copy.**
> Since the delete is client-side, the non-destructive Push only exists on devices
> running an APK that has this change — an older client mirrors whatever it is sent.
> `BundleSync` is deliberately Android-free so `app/src/test/.../BundleSyncTest.kt`
> can prove both branches on the host JVM (`./gradlew :app:testProdDebugUnitTest`).
>
> **Excluded from every bundle.** A folder pick drags along whatever the OS left in it —
> a `.DS_Store` per directory, an `._name` AppleDouble sidecar per file once the folder has
> been through a USB stick, `Thumbs.db`, `desktop.ini`. `is_excluded_bundle_entry` drops
> these in `upload_bundle_handler`, before anything is written to staging, so they never
> reach the zip, never count against `MAX_BUNDLE_ENTRIES`, and never land on a device.
> Matching is case-insensitive and applies to *any* path segment, so an excluded directory
> (`.Spotlight-V100`, `__MACOSX`, `$RECYCLE.BIN`, `.Trash-1000`) takes its contents with it.
>
> The list is deliberately confined to files an OS creates on its own. `.git/`, Unity
> `.meta` files, and dotfiles at large are uploaded as-is: silently dropping content a user
> authored would be a worse failure than shipping an unwanted `.DS_Store`. An upload that is
> *entirely* metadata is rejected with 400 rather than producing an empty bundle — a Sync
> Folder given an empty bundle would wipe the destination. Since the console's pre-upload
> file count comes from the browser and the post-upload count comes from the server, the
> upload response returns `skipped_count` and the console logs what was excluded rather than
> letting the two numbers silently disagree.
>
> Because a mirror deletes, and because the PICO ToBService exposes **no privileged
> file-copy API**, two constraints apply to both actions and are enforced on the
> server (syntactic) and the client (canonical): the destination must live under
> **shared/primary external storage** (`/sdcard` · `/storage/emulated/0`) — the
> client can only reach shared storage with `java.io.File` I/O, so app-scoped
> `Android/data/<pkg>/` directories are *not* targetable — and it must be neither
> the storage root nor a protected top-level directory (`Android`, `Download(s)`,
> `DCIM`, `Pictures`, `Movies`, `Music`, `Documents`, `Alarms`, `Notifications`,
> `Podcasts`, `Ringtones`) so a mistyped path cannot wipe unrelated user/media data.
>
> Both actions reuse the per-device PROGRESS column, showing `Pushing…` / `Syncing…`
> → `✓ pushed` / `✓ synced` (with the `+added ~updated -deleted` summary) / `✗ failed`.
> Unlike install, these transitions are **not** server-driven: neither action is
> throttled, so the server holds no per-device state to broadcast. The console paints
> the in-flight state optimistically on dispatch and resolves it on
> `PUSH_FILES_RESULT`; a device that drops offline mid-job clears its cell on the next
> `DEVICE_LIST`. `PUSH_FILES_RESULT` does not name the mode, so the console carries the
> verb over from what it dispatched (a page reloaded mid-job falls back to "pushed").
> The column holds one state per device, so a push and an install targeting the same
> device overwrite each other's cell — the last job dispatched wins.

## Integrity Verification

On-demand check that a package (or a directory) on a managed device matches a
reference the operator holds locally. The **reference is computed client-side**
(in the browser, or with the `styly-mdm hash` CLI) and the **comparison happens
client-side**; the server only relays the `VERIFY_*` messages (it never hashes).
This keeps two hard constraints: a 1 GB+ APK is **never uploaded** just to be
checked, and no HTTPS/secure-context is required — the browser hashing is pure JS
(`crypto.subtle` is unavailable over plain `http://<LAN-IP>`).

The reference, browser, and device implementations must agree byte-for-byte, so
the algorithms below are a fixed spec (the canonical Python implementation is
`mdm-server/styly_mdm/integrity.py`).

**APK (`VERIFY_APK`) — `size` + Central-Directory digest.** An APK is a ZIP; its
tail holds the Central Directory (every entry's CRC-32 + sizes). `cd_sha256` is the
SHA-256 of `file[CD_offset .. EOF]`, where `CD_offset` is the little-endian uint32 at
offset 16 of the End-Of-Central-Directory record (the last `PK\x05\x06` whose
`position + 22 + comment_length == file_length`). This covers every entry while
reading only a few hundred KB regardless of APK size. A device match requires
`found && size == reference.size && cd_sha256 == reference.cd_sha256`. The device also
returns `full_sha256` (whole-file, hardware-accelerated), `version_code`,
`version_name`, and `signer_sha256` (SHA-256 of the current signing certificate) as
diagnostics shown on a mismatch. ZIP64 archives (CD offset sentinel `0xFFFFFFFF`) are
not supported and return a clear error rather than a wrong range.

Picking a reference APK also **auto-fills the package name** to verify. The console
reads `AndroidManifest.xml` out of the picked file (Central Directory → Local File
Header → stored as-is or inflated with `DecompressionStream('deflate-raw')`, which,
unlike `crypto.subtle`, works on a non-secure origin) and parses the binary AXML for
the `<manifest>` element's `package` and `versionName`. Both storage forms occur in
practice: Unity release APKs store the manifest, Gradle debug APKs deflate it, and the
AXML string pool may be UTF-8 or UTF-16. This is a progressive enhancement — on any
parse failure the console logs a warning and the field falls back to manual entry.
A name the operator typed is never overwritten by a failed parse, but one the console
filled in from a *previous* APK is cleared, so a reference can never be verified
against a stale package name.

*Known limitation (accepted):* `size` + CD digest does not detect in-place data
corruption that preserves the stored CRC-32 (ZIP CRCs are build-time constants). This
is rare — Android verifies the APK signature at install. `full_sha256` is already
returned for a future byte-exact "strict" mode. Phase 1 targets the single `base.apk`
(`sourceDir`); split-APK combination is a possible future extension.

**Directory (`VERIFY_DIR`) — manifest + tree hash.** For each regular file the device
records `{relative_path, size, sha256}` (paths forward-slashed, relative to the target
directory), sorts entries by the **UTF-8 byte order** of `relative_path`, and hashes
`relative_path + "\n" + size + "\n" + sha256 + "\n"` per entry into a single `tree_hash`.
The console compares `tree_hash` for same/different and, when both manifests are present,
diffs them to list missing / added / changed files. Policy (identical across all
implementations): symlinks are not followed and are excluded, empty directories are not
represented, and an unreadable file makes the whole result an error. Directory checks are
bounded to **shared external storage** (`/sdcard`), which is what `MANAGE_EXTERNAL_STORAGE`
grants; the device canonicalizes the path and refuses anything outside that root. Large
trees can omit the per-file `manifest` (a cap on entry count) and still compare by
`tree_hash`. Note that OS-generated hidden files in a reference folder (e.g. macOS
`.DS_Store`, `Thumbs.db`) are hashed as content and will show up as spurious "added"
entries against a device that does not have them — prune them from the reference first.

**Generating a reference.** In the console, pick a local `.apk` (Verify APK) or a folder
(Verify Directory) — it is hashed in the browser, never uploaded. For large trees the
`styly-mdm hash <path>` CLI is faster and deterministic; it prints the same JSON the
console consumes and runs on the machine that holds the tree (which may differ from the
server host):

```bash
styly-mdm hash ./content            # directory -> {tree_hash, file_count, total_size, manifest}
styly-mdm hash ./app-release.apk    # APK       -> {size, cd_sha256}
```

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

### Startup duplicate-server guard

Because the client takes the **first** discovery response it receives, two
servers sharing a discovery port on the same LAN would split devices between
them nondeterministically. To prevent this, the server probes for an existing
server before it starts: on startup it broadcasts one `STYLYMDM_DISCOVER` and
waits ~1 s. If another STYLY-MDM server answers, the server logs an error naming
the responder's IP and the discovery port, then exits with a non-zero status:

```
[ERROR] Another STYLY-MDM server is already running on this network and
responding on discovery port 7071 (from 192.168.1.42). Refusing to start so
devices cannot connect to the wrong server. Stop the other server, or set
MDM_DISCOVERY_PORT to a different value to run alongside it.
```

Running a dev server alongside production is unaffected: it probes its own
discovery port (7081), does not see production on 7071, and starts normally. The
probe is best-effort — two servers started at the same instant may both probe
before either is answering and miss each other.

## MDM Client Permissions

The MDM client requires the following Android permissions:

| Permission | Purpose |
|---|---|
| `INTERNET` | WebSocket connection to the control server |
| `FOREGROUND_SERVICE` | Run as a persistent background service |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on device boot |
| `ACCESS_NETWORK_STATE` | Monitor network connectivity |
| `ACCESS_WIFI_STATE` | Retrieve the device IP address |
| `MANAGE_EXTERNAL_STORAGE` | Write downloaded APKs to shared storage so the PICO ToBService can read them for silent install; read/write shared-storage directories for file/folder push (sync); and read shared storage for directory integrity checks (`VERIFY_DIR`) |
| `QUERY_ALL_PACKAGES` | Resolve an arbitrary installed package via `PackageManager` for APK integrity checks (`VERIFY_APK`). On API 30+ package visibility is filtered; an operator may verify any package, so a `<queries>` allowlist cannot cover it. This client is privately distributed (not on Google Play), so the Play-policy restriction on this permission does not apply. |

Battery percentage and charging state are read with Android's standard battery
status APIs (`ACTION_BATTERY_CHANGED` / `BatteryManager`), which do not require
an additional manifest permission.

## Requirements

| Component | Minimum version |
|---|---|
| Python | 3.10 |
| aiohttp | 3.9 |
| Android (MDM client) | API 29 (Android 10) |
| OkHttp | 4.x |
| PICO OS | Business Mode enabled |

## Server Packaging & PyPI Release

The control server is packaged as the `styly-mdm` PyPI distribution (import name
`styly_mdm`). Runtime dependencies and metadata live in `mdm-server/pyproject.toml`;
the web console (`styly_mdm/static/`) ships as bundled package data.

**Runtime data location.** Uploaded APKs (`apks/`) and the device registry
(`device_registry.json`) are written to a data directory, not next to the code (the
installed package lives in read-only `site-packages`). It defaults to the current
working directory and is overridable via `MDM_DATA_DIR` / `--data-dir`. `run.sh`
does `cd mdm-server` first, so the from-source dev workflow keeps writing under
`mdm-server/` as before.

**Not ASGI.** The server is aiohttp and starts a UDP discovery responder in the same
asyncio loop as the HTTP server (`server.run_server`). It must be launched via its own
process (the `styly-mdm` console script, `python -m styly_mdm`, or `uvx styly-mdm`) —
running it under an ASGI server such as uvicorn (`module:app`) would never start LAN
discovery.

**Build & test locally:**

```bash
cd mdm-server
pip install -e '.[dev]'
python -m pytest        # smoke tests
python -m build         # sdist + wheel into dist/  (pip install build first)
```

**Versioning.** The package version is derived from the `vX.Y.Z` git tag via
`setuptools-scm` — the same tag the APK release flow uses, so server and APK share one
version. There is no hardcoded version to bump.

**Release automation.** `.github/workflows/publish-pypi.yml` runs on the same
`release: published` event as the APK build (`release.yml`): a human publishes the
draft created by `release-version-bump.yml`, which fires both builds. It builds,
tests, then publishes in two stages using **Trusted Publishing (OIDC)** — no tokens:

1. **Test PyPI** — automatic after build.
2. **PyPI** — gated by the protected `pypi` GitHub environment (manual approval = the
   "verify on Test PyPI first" step).

A `workflow_dispatch` run does a Test PyPI-only dry run.

**One-time maintainer setup (required before the first publish):**

- On **Test PyPI** and **PyPI**, add a Trusted Publisher for this repo: workflow
  `publish-pypi.yml`, environment `testpypi` / `pypi` respectively (use a "pending
  publisher" since the project doesn't exist yet).
- In the repo's **Settings → Environments**, create `testpypi` (no protection) and
  `pypi` (required reviewers → manual approval gate).

**Verifying a Test PyPI release.** After a `workflow_dispatch` dry run (or the
automatic Test PyPI step of a real release), install the package into a throwaway
environment and actually start it before approving the `pypi` promotion:

```bash
# Install from Test PyPI. Two flags matter:
#   --extra-index-url : runtime deps (aiohttp, ...) live on real PyPI, not Test PyPI,
#                       so --index-url alone fails to resolve them.
#   --pre             : Test PyPI only ever has dev builds (e.g. 0.2.1.devN).
python -m venv /tmp/styly-test && source /tmp/styly-test/bin/activate
pip install --index-url https://test.pypi.org/simple/ \
            --extra-index-url https://pypi.org/simple/ --pre styly-mdm

# Start it on non-default ports with a throwaway data dir (so it never clashes with a
# real 7070/7071 server), then smoke-test HTTP and UDP discovery:
MDM_WS_PORT=17070 MDM_DISCOVERY_PORT=17071 styly-mdm --data-dir /tmp/styly-data &
curl -s -o /dev/null -w 'HTTP %{http_code}\n' http://127.0.0.1:17070/   # -> HTTP 200
python - <<'PY'
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(3)
s.sendto(b'STYLYMDM_DISCOVER', ('127.0.0.1', 17071))
print('discovery:', s.recvfrom(1024)[0].decode())   # -> {"service": "stylymdm", ...}
PY
```

A clean run — `HTTP 200`, a discovery JSON reply advertising the WS port, and a
freshly created `/tmp/styly-data/apks/` — confirms the published artifact installs and
runs. Only then approve the `pypi` environment to promote the release to PyPI.
