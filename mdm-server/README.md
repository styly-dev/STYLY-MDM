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

| Option | Env var | Flag | Default |
|--------|---------|------|---------|
| HTTP/WebSocket port | `MDM_WS_PORT` | `--port` | `7070` |
| UDP discovery port | `MDM_DISCOVERY_PORT` | — | `7071` |
| Data directory (uploaded APKs + device registry) | `MDM_DATA_DIR` | `--data-dir` | current directory |

Uploaded APKs are written to `<data-dir>/apks/` and the persistent device
registry to `<data-dir>/device_registry.json`.

You can also start it as a module: `python -m styly_mdm`.

## Documentation

Full documentation, protocol references, and the Android MDM client live in the
project repository: <https://github.com/styly-dev/STYLY-MDM>.
