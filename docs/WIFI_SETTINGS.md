# Group Wi-Fi auto-connect policy

This feature supports PICO 4 Ultra Enterprise (A9210 / app model A94U0, Android 14). It uses the
existing managed-LAN HTTP/WebSocket connection, without login or device tokens.
The server saves policies and sends them explicitly to online group members;
clients decide connection success and recover independently of MDM or internet.

## Operator workflow

Open **Manage Groups**, select a group and choose **Wi-Fi**. Add Open or WPA/WPA2
Personal networks, then Save. An empty password field retains the saved key for
the same SSID/security. Unsaved edits are discarded when leaving the editor.
Choose **Send to whole group** separately to deploy the saved revision. Every
send freezes online membership and the saved policy; offline devices receive no
queued command on reconnect. The immediate response reports sent/failed/unconfirmed counts. A completed
socket send does not prove application or connection success. The group view
also shows each device’s last reported policy revision, management state,
readiness and latest result. Offline rows are last-known observations, not
proof of the current connection. There is no periodic status poll or delivery log.
Concurrent edits return 409 and require an explicit reload before saving/sending.

Devices may belong to multiple groups. The console warns about overlaps without
removing membership or blocking edits. Different group Wi-Fi policies can
compete: the last successful explicit deployment determines the device policy.
Moving a device between groups does not deploy Wi-Fi settings automatically.

Devices retain every saved OS network. Deployment adds/updates the listed
networks, enables their automatic connection and disables automatic connection
for other saved networks. Same-SSID/security credentials may be overwritten.
It does not delete saved networks or continually enforce the policy afterward.

**Delete Wi-Fi settings** saves a **Do not manage Wi-Fi** revision. The warning
explains that existing networks remain. Saving deletion sends nothing; deploy it
explicitly to stop boot reapplication. The unmanaged command leaves current
network settings and the last successful profile intact. It supersedes older
in-flight attempts without preventing their necessary local recovery. Only a
later successful managed deployment re-enables boot reapplication.

## Local connection and recovery

Before mutation, the client durably records existing network IDs, SSID/security
identities, automatic-connection flags and any previous credentials known to MDM.
A separately registered initial recovery profile is not required.

At the first mutation, persist a fixed elapsed-time deadline 60 seconds ahead
and arm an operation-specific exact alarm plus an in-process watchdog. For changed
or unverified settings, require a fresh connection to a distributed SSID with an assigned Wi-Fi IP. An old
connection, mobile/VPN, internet access or MDM reachability is not success proof.
First success commits the candidate as the last successful profile; later
connection loss does not roll it back. A repeated known, identical policy can
finish without disconnecting only after saved auto-join settings and current
candidate SSID/IP are verified. Changed credentials still require reconnection.

On failure/timeout, restore the prior automatic-connection flags and any
MDM-known credentials overwritten by this attempt. Newly added candidates remain
saved with automatic connection disabled. **Unknown overwritten passwords cannot
be restored**. Restoring flags cannot guarantee connectivity if an AP is absent
or its credentials have changed. Unknown existing SAE/other configurations can
remain saved and have their flags restored; deploying new SAE/EAP configurations
is outside the supported input formats.

Interrupted manual operations recover before another Wi-Fi mutation. Content
startup waits for recovery completion or at most 60 seconds, then launches even
if Wi-Fi recovery failed. Re-resolve changed network IDs by saved identity;
ambiguous or missing original settings fail recovery rather than guessing. Failed/unknown restoration
retains the durable operation fence. Never restore concurrently with a still
running Wi-Fi mutation. Duplicate operation IDs are bounded to the latest 500.
Only the latest manual result is retained. A terminal failed restoration can be
abandoned by explicit unmanaged deployment after all mutation/restore owners
finish. This stops retrying that journal; it does not claim successful restoration.
Unreadable local storage remains blocked rather than discarding recovery data.

Once per actual OS boot, while managed, persist the claimed boot generation and
reapply the last successful profile. This boot application has no connection
test, 60-second rollback, retry loop or detailed boot history. Service recreation
alone does not reapply it. The process owns one controller; recovery starts after
foreground-service location promotion. Later location grants refresh the service
foreground type. Precise location must be allowed all the time: on the tested
PUI build, a location foreground service alone lost configured-network access
after disconnecting Wi-Fi. If recovery was blocked by missing permissions,
fix the grant and restart the headset; the retained journal is retried in the
new process. Changing permissions alone does not retry a failed startup owner.

## APIs, authority and persistence

The normal app requires Device Owner, precise location allowed all the time with Location enabled,
and exact-alarm special access. Device Owner does not expose existing passwords.
Provisioning is explicit (`dpm set-device-owner`); the app neither provisions
itself nor requests unrelated restrictions. PICO auto-join uses TobService's
`allow_wifi_auto_join` message with `network_id` and `allow_auto_join`. Read the
actual `WifiConfiguration.allowAutojoin` flag; its status/enabled field is not
an equivalent. Never construct a partial configuration just to toggle auto-join.

Policies are stored in the optional `wifi_profiles` field of
`device_registry.json`. **Passwords are plaintext in this file and its backups**;
restrict access to both. Registry replacement uses a private file mode (0600 on
POSIX).
There is no configuration key or mandatory cryptography dependency. APIs and
logs never return passwords; GET returns only password-presence flags. A policy
and its group rename/delete share one registry replacement. Android stores its
journal and known credentials in private no-backup `wifi-settings.json`.

For an existing encrypted prototype registry, set its original
`MDM_WIFI_ENCRYPTION_KEY` for one startup and install `cryptography` only if needed
for that migration. Back up the registry before switching builds. Successful
validation and persistence replaces
`wifi_profiles_encrypted` with plaintext `wifi_profiles`. Then remove the key.
If the key/dependency is missing, decryption fails, or migration cannot be saved,
core MDM still starts with Wi-Fi management disabled and retains the old field.
Malformed plaintext Wi-Fi data is handled the same way. Resolve the stored data
and restart; no automatic discard/reset is performed.
Older builds do not preserve the new plaintext field; downgrade only with a
separate compatible registry backup. New device-report fields are optional:
clients without reports remain visible with unknown Wi-Fi results.

| Route | Action |
|---|---|
| `GET /api/wifi/state` | Feature availability |
| `GET /api/wifi/groups/{name}` | Saved form with password-presence flags |
| `PUT /api/wifi/groups/{name}` | Save a managed policy |
| `DELETE /api/wifi/groups/{name}` | Save unmanaged without sending |
| `POST /api/wifi/groups/{name}/deploy` | Send to frozen online targets |

Mutations require `expected_revision`, initially null when no policy exists.
The command is `EXECUTE_WIFI_APPLY` with operation_id, revision, mode
(managed/unmanaged) and networks. Only `wifi_settings_v4` capable canonical
clients receive it. The capability marks protocol/platform support; transient
permission/Device Owner/Location/alarm readiness is reported separately, so an
unmanaged deployment remains possible when managed mutation is unavailable.
Per-device send locks preserve managed/unmanaged order and socket writes have a
five-second timeout. Secret-free `wifi_state` is included in REGISTER and sent as
`WIFI_STATE` after registration and local state changes. The server validates and
stores its latest observation with server receipt time, then updates DEVICE_LIST
for the console. Results never depend on reconnection alone. There are no result
ACKs or automatic server reconciliation/replay. Browser mutations require the
same origin.

Inputs: 1–32 networks; Open or WPA/WPA2 PSK; unique SSID/security pairs; SSID
1–32 UTF-8 bytes without control characters, quotes or backslashes; PSK 8–63
printable ASCII characters or 64 hex digits. Hidden SSIDs are supported. No
priority, static IP, proxy or simultaneous dual-SSID connection is provided.

This is unpublished prototype work: only the current client/server formats are
supported. Earlier client state schemas, TLS options and initial-recovery
commands have no runtime migration paths. The encrypted registry migration above is the sole
server credential-storage exception. Preserve any needed
test data separately before switching builds. Hardware coverage remains limited to the tested PICO/PUI build; the current
review fixes require separate physical validation.
