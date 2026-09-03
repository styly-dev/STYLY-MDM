# styly-mdm

The control server for **STYLY-MDM** — a lightweight Mobile Device Management
system for Location Based Experience (LBE) VR headsets. It bridges PICO VR HMDs
and a browser-based admin console, letting you launch apps and install APKs on
many headsets at once over the LAN.

> STYLY-MDM is designed for use within a local area network (LAN). It has no
> authentication. **Do not expose the server to the public internet.**

## Install & run

Run without cloning the repository:

```bash
# One-off, no install (recommended):
uvx styly-mdm

# Or install, then run:
pip install styly-mdm
styly-mdm
```

The server starts on port **7070** and prints its LAN IP addresses. Open
`http://<server-ip>:7070` in a browser for the web console. Devices on the LAN
discover the server automatically via UDP broadcast (port **7071**).

## Configuration

A command-line flag overrides the corresponding environment variable.

| Option | Env var | Flag | Default |
|--------|---------|------|---------|
| HTTP/WebSocket port | `MDM_WS_PORT` | `--port` | `7070` |
| UDP discovery port | `MDM_DISCOVERY_PORT` | — | `7071` |
| Data directory (uploaded APKs, pushed bundles, device registry) | `MDM_DATA_DIR` | `--data-dir` | current directory |
| Simultaneous device downloads, server-wide | `MDM_MAX_CONCURRENT_TRANSFERS` | `--max-concurrent-transfers` | `5` |
| Seconds a device may hold a transfer slot | `MDM_TRANSFER_TIMEOUT` | — | `600` |
| Device registration policy | `MDM_DEVICE_IDENTITY_MODE` | — | `legacy-compatible` |

The data directory holds everything the server persists: uploaded APKs
(`<data-dir>/apks/`), pushed file bundles (`<data-dir>/bundles/`), and the device
registry (`<data-dir>/device_registry.json`). It defaults to the directory the
server is started from, so `uvx styly-mdm` in a fresh directory comes up with no
devices or groups.

Transfer throttling bounds how many devices download at once — one server-wide
pool shared by every install and push/sync job; the rest queue until a slot
frees.

`MDM_DEVICE_IDENTITY_MODE=legacy-compatible` accepts both existing scheme-less
serial clients and `styly_device_id_v1` clients during the rollout. After every
client has been updated and identity-keyed state has been backed up and reset,
restart with `MDM_DEVICE_IDENTITY_MODE=cutover-strict`; scheme-less registrations
are then rejected before they can modify persistent state. Strict startup also
fails without rewriting the registry if a non-canonical device record or group
member ID remains.

A client that succeeds in crossing from the legacy build to the GUID build registers
as a new device. The server does not correlate or migrate the old serial record. If
the old build reconnects under its serial, it remains targetable for another update;
if it does not return, the old update is reported as an untracked identity handoff.

Only one server may answer discovery on a given port: the server probes on
startup and exits if another STYLY-MDM server already responds on its discovery
port. Run a second server alongside the first by giving it a different
`MDM_DISCOVERY_PORT` and `MDM_WS_PORT`.

You can also start it as a module: `python -m styly_mdm`.

## Documentation

Full documentation, protocol references, and the Android MDM client live in the
project repository: <https://github.com/styly-dev/STYLY-MDM>.
