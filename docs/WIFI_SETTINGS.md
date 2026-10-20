# Group Wi-Fi settings (v1)

This feature implements the Wi-Fi portion of the supplied 2026-10-20 v1.1
settings-deployment specification. Saving a profile and applying it are separate
operations. A device belongs to at most one group, or remains unassigned.
Brightness, volume and LBE settings are outside this implementation.

## Ownership and lifecycle

The server owns the latest group profile, its immutable revision, the deployment
snapshot and each device's reported results. The Android application owns actual
Wi-Fi changes, rollback, the last successfully committed profile and boot claims.
The server never infers rollback success from a disconnected socket.

A manual deployment freezes group membership, profile revision and online socket
identities before dispatch. Every online member is included, even if a previous
attempt succeeded. Offline members retain their existing state. No command queue
is retained for reconnecting devices. A command already received by the client can
recover locally and resend its result, without starting the settings operation
again.

Before making any manual changes, the client reads all existing configurations
and obtains complete supported restoration information. Unknown credentials,
unsupported security or insufficient privileges stop the operation before its
first mutation. Merely knowing an SSID does not establish recoverability.
The restoration snapshot and operation journal are persisted in private,
backup-excluded storage before the first mutation. A local monotonic 60-second
deadline covers changes, final readback and an operation-specific MDM challenge.
The deadline initiates restoration; it does not promise restoration completes
within 60 seconds. The successful profile is durably committed before publishing
success. An unconfirmed candidate never replaces the boot baseline.

On an OS boot, a durable boot-generation claim permits one local correction to
the last successful profile. Service restarts, reopening Settings, screen-on and
server reconnects do not initiate correction. PICO's `SERVICE_AUTO_BOOT` is not
accepted as proof of an OS boot. Recovery of an unfinished manual operation takes
precedence over ordinary correction and blocks overlapping changes if restoration
fails. Boot correction checks API results/readable values, but does not test
connectivity, wait for MDM or schedule manual-deployment rollback.

The MDM client's own content autostart is ordered after local initialization and
recovery/correction. Other launchers or OS content autostart are independent; this
feature cannot guarantee it finishes before externally launched content starts.
No continuous settings-enforcement loop is installed.

## Security and supported devices

Wi-Fi credentials use administrator/device access control and encrypted server
storage. HTTPS is supported. A server restricted to a managed LAN can explicitly
allow HTTP with `MDM_WIFI_ALLOW_HTTP=1` when TLS is not configured; tokens and
Wi-Fi passwords then travel without transport encryption. The browser session is
HttpOnly and SameSite=Strict, with Secure set on HTTPS. Tokens must not be put in
URLs or shared-storage client configuration. When TLS is used, certificates must
be trusted by Android; no permissive trust manager or hostname bypass is provided.

Precise location permission and exact-alarm access are requested from the existing
Android Settings screen. The client checks both before a manual mutation, along
with device-owner/system authority; granting ordinary runtime permissions alone
does not grant Wi-Fi management authority.

The initial form supports open networks and WPA/WPA2 personal PSK networks. It
rejects empty lists, duplicate SSID/security pairs and unsupported authentication
methods. SSIDs contain 1–32 UTF-8 bytes; control characters, double quotes and
backslashes are rejected by this adapter. PSKs contain 8–63 ASCII characters or
64 hexadecimal digits.
WPA3/SAE, EAP/enterprise certificates, WEP and custom proxy/static-IP profiles are
not silently converted to supported profiles. API availability and permission
checks are required: a successful compile does not grant Android device-owner or
privileged Wi-Fi access.

`MDM_DEVICE_TOKEN` is a shared fleet credential. Every holder is trusted for the
entire fleet, including any group's Wi-Fi secrets. A self-reported Device ID is
not cryptographic device authentication: a token holder can impersonate another
fleet member. Do not use this mode for mutually untrusted devices or tenants.
Per-device enrollment credentials/certificates and authorization bound to the
registered Device ID are required before claiming isolation between groups.

Passwords live only in the encrypted server state and Android private
`noBackupFilesDir` storage. Neither raw WebSocket frames nor credential values
belong in logs, diagnostics, shared storage or backup. Read APIs redact passwords;
retaining an existing password uses an explicit unchanged-password representation
rather than returning it to the form.

PICO's documented default-auto-connect API alone does not establish saved-network
listing, deletion, restoration or password-readback support. The Android adapter
must fail closed when a complete, authoritative configuration list and restoration
snapshot are unavailable. Do not assume a previously successful SSID still has
the same password after another application or operator changes it.

## Server and client setup

The Wi-Fi feature is opt-in. Configure the encryption key and both tokens, then
choose TLS or explicit managed-LAN HTTP:

| Variable | Value |
|---|---|
| `MDM_TLS_CERT` | PEM certificate chain file when using TLS |
| `MDM_TLS_KEY` | PEM private-key file when using TLS |
| `MDM_WIFI_ALLOW_HTTP` | `1` enables managed-LAN HTTP only when TLS is absent |
| `MDM_WIFI_ENCRYPTION_KEY` | Fernet key, kept outside the data directory/backups |
| `MDM_ADMIN_TOKEN` | Secret administrator access token |
| `MDM_DEVICE_TOKEN` | Secret token provisioned into managed clients |

Generate a Fernet key with the installed Python environment:

```bash
python -c 'from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())'
```

The encrypted store is `MDM_DATA_DIR/wifi_settings.enc`. Group rename/delete
uses `wifi_group_mutation.txn`, recovered before either group or Wi-Fi state is
loaded. Keep that journal with the data directory when recovering an interrupted
server. An unrecoverable metadata transaction blocks Wi-Fi work rather than
letting inconsistent state deploy credentials.

`MDM_WIFI_OPERATION_TIMEOUT` optionally controls when the server marks missing
results unknown (default 180 seconds). It does not change the device's local
60-second manual rollback deadline or imply a completed restoration.

Retain the encryption key separately: losing it makes saved profiles and deployment
history unreadable. Restarting with another key must fail rather than silently
resetting the store. Back up the encrypted data together with the normal device
registry, and keep the token/key material out of source control. `cryptography`
is the additional server runtime dependency for authenticated encryption.

The console and device connections use the configured MDM HTTP/WebSocket port.
For managed-LAN HTTP, set `MDM_WIFI_ALLOW_HTTP=1` and leave TLS certificate/key
unset; use `http://` in the browser and disable TLS in Android Settings. For TLS,
configure both certificate files, use `https://` and enable TLS in Settings.
Discovery advertises the matching `ws://` or `wss://` scheme. Authenticate in the
console access form. On each client, set an explicit manual server address and
provision the device token. The token and Wi-Fi capability are bound to that
manually selected endpoint; UDP discovery/cache alone never authorizes credential
transmission, even when the discovered URL matches the saved address.

The shared-storage `styly-mdm/config.json` remains limited to non-secret
connection tunables.

## Console behavior

Use **Manage Groups**, choose a group, open its **Wi-Fi** tab, edit the network
rows, then save. The **Devices** tab edits membership separately; Wi-Fi target
counts and results always use saved membership. Saving does
not send settings to devices. **Apply to whole group** takes a new online snapshot;
there is no individual-device apply action. Saving again creates a new revision.
Moving membership during an unresolved operation is rejected.

The console keeps online/offline status separate from settings status. It shows
text and icons as well as colors: not applied (gray), applying (blue), success
(green), mismatch (yellow), failed/unknown (red). A successful old revision is
shown as **Not applied (previous settings applied)** after the group changes.
Switching the active AP within the specified Wi-Fi list is not a mismatch.
Unreadable credentials do not prove a complete match.

Manual and boot results are stored separately. Successful correction to an older
baseline does not turn a failed manual deployment into success. A boot-operation
failure is shown explicitly while keeping manual history. No result is fabricated
before the client reports it, and no last-checked timestamp is displayed.

## Wire and HTTP contract

`REGISTER.capabilities` keeps the existing string-array format and adds
`wifi_settings_v1`. A dictionary would break Push/Sync capability negotiation.
Only canonical identities on authorized enrolled connections qualify for Wi-Fi
commands; provisional identities never receive settings credentials.

| Route | Operation |
|---|---|
| `POST /api/wifi/session` | Authenticate a browser using its bearer token |
| `GET /api/wifi/state` | Read redacted group revisions and separate device result states |
| `GET /api/wifi/groups/{name}` | Read redacted saved Wi-Fi form values |
| `PUT /api/wifi/groups/{name}` | Validate and save a new profile revision |
| `POST /api/wifi/groups/{name}/deploy` | Freeze online membership and apply the saved revision |

Profiles contain nonempty `networks` lists. Each entry has `ssid`, `security`
(`OPEN` or `WPA_PSK`), `password` and `hidden`. Read responses replace the password
with `password_present`; a null/omitted password on update retains the saved PSK
for the same SSID/security identity. Unsupported values are rejected before save.
Group names in routes are URL-encoded.

| Message | Purpose |
|---|---|
| `EXECUTE_WIFI_APPLY` | New operation UUID, frozen revision UUID and network list |
| `WIFI_APPLY_ACCEPTED` | Device has durably received this operation |
| `WIFI_VERIFY_REQUEST` / `WIFI_VERIFY_ACK` | Exact-operation challenge after final settings verification |
| `WIFI_APPLY_RESULT` | Durable manual result with fixed reason and rollback indication |
| `WIFI_BOOT_RESULT` | Separate boot result, keyed by boot-result UUID and baseline revision |
| `WIFI_OBSERVATION` | Readable-value observation: match, mismatch or unknown |
| `WIFI_RESULT_ACK` | Server has durably stored the matching manual or boot result |

A result ACK echoes its exact revision, status and reason (plus `rolled_back`
for manual results). A delayed ACK for an intermediate unknown/restore-failed
result must not acknowledge a later, different result for the same operation.
A conclusive restoration failure remains fenced until verified recovery settles
the same operation.

A reconnected canonical socket can finish a received operation and resend its
result; this does not authorize redelivery of a new application command. A server
restart marks unresolved operations unknown. It cannot declare success or a
completed restoration without an exact client result. Unknown ownership remains
fenced against overlapping deployments and membership changes.

## Compatibility and migration

This is an additive Wi-Fi wire protocol. Older clients retain existing MDM
commands but cannot receive Wi-Fi credentials without the new capability and
authenticated enrolled connection. Enable the feature after provisioning its
server credentials and device token, with either TLS or explicit managed-LAN HTTP.
The state API adds `allow_http`; clients that do not recognize it continue to
require HTTPS. Existing TLS configurations retain their transport requirements.

Legacy registry files may contain overlapping group membership. Migration assigns
a device to the first group in sorted name order and removes later duplicates;
unassigned devices remain unassigned. Back up the registry before upgrading if
an existing workflow relies on overlapping groups. The new single-membership rule
is intentional; downgrading does not reconstruct discarded overlaps. A profile's
revision follows group rename; edits create a new revision. Device deployment
history must retain its original operation/revision identity.

## Verification boundary

Host-side tests cover lifecycle boundaries with fake Wi-Fi adapters and transports.
They do not validate PICO firmware privileges, real saved-network/password
visibility, broadcast delivery, API blocking behavior, connectivity loss,
restoration on physical reboot, or ordering relative to external content launch.
No physical device was exercised for this implementation. The request's assumption
that physical checks succeed is an implementation premise, not recorded evidence
of a completed acceptance test.

Reference documentation:

- [PICO Enterprise API introduction](https://business.picoxr.com/us/doc/EnterpriseAPI-Intro)
- [Android WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager)
- [Android WifiConfiguration](https://developer.android.com/reference/android/net/wifi/WifiConfiguration)
- [Android broadcast exceptions](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions)
