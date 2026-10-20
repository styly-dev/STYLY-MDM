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
queued command on reconnect. The response reports sent/failed/unconfirmed counts
only. A completed socket send does not prove application or connection success.
There is no persistent per-device Wi-Fi delivery history or periodic status poll.
Concurrent edits return 409 and require an explicit reload before saving/sending.

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
and arm an operation-specific exact alarm plus an in-process watchdog. Require
a fresh connection to a distributed SSID with an assigned Wi-Fi IP. An old
connection, mobile/VPN, internet access or MDM reachability is not success proof.
First success commits the candidate as the last successful profile; later
connection loss does not roll it back.

On failure/timeout, restore the prior automatic-connection flags and any
MDM-known credentials overwritten by this attempt. Newly added candidates remain
saved with automatic connection disabled. **Unknown overwritten passwords cannot
be restored**. Restoring flags cannot guarantee connectivity if an AP is absent
or its credentials have changed. Unknown existing SAE/other configurations can
remain saved and have their flags restored; deploying new SAE/EAP configurations
is outside the supported input formats.

Interrupted manual operations recover before another mutation or content
startup. Re-resolve changed network IDs by saved identity; ambiguous or missing
original settings fail recovery rather than guessing. Failed/unknown restoration
retains the durable operation fence. Never restore concurrently with a still
running Wi-Fi mutation. Duplicate operation IDs are bounded to the latest 500.
Only the latest manual diagnostic result is stored locally, not reported to MDM.

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

Set `MDM_WIFI_ENCRYPTION_KEY` to a Fernet key. The registry's optional
`wifi_profiles_encrypted` field contains encrypted profiles, so a policy and its
group rename/delete share one registry replacement. Keep the key with protected
backups separately. APIs/logs do not return password values. Android stores its
journal and known credentials in private no-backup `wifi-settings.json`.

| Route | Action |
|---|---|
| `GET /api/wifi/state` | Feature availability and saved group revisions |
| `GET /api/wifi/groups/{name}` | Saved form with password-presence flags |
| `PUT /api/wifi/groups/{name}` | Save a managed policy |
| `DELETE /api/wifi/groups/{name}` | Save unmanaged without sending |
| `POST /api/wifi/groups/{name}/deploy` | Send to frozen online targets |

Mutations require `expected_revision`, initially null when no policy exists.
The command is `EXECUTE_WIFI_APPLY` with operation_id, revision, mode
(managed/unmanaged) and networks. Only `wifi_settings_v4` capable canonical
clients receive it. Per-device send locks preserve managed/unmanaged order and
socket writes have a five-second timeout. There are no client result ACKs or
server reconciliation/replay. Browser mutations require the same origin.

Inputs: 1–32 networks; Open or WPA/WPA2 PSK; unique SSID/security pairs; SSID
1–32 UTF-8 bytes without control characters, quotes or backslashes; PSK 8–63
printable ASCII characters or 64 hex digits. Hidden SSIDs are supported. No
priority, static IP, proxy or simultaneous dual-SSID connection is provided.

This is unpublished prototype work: only the current client/server formats are
supported. Earlier `wifi_settings.enc`, client state schemas, TLS options and
initial-recovery commands have no runtime migration paths. Preserve any needed
test data separately before switching builds. See the
[hardware report](../tasks/wifi-hardware-validation-2026-10-20.md) for evidence
and actual limits; it distinguishes older replacement tests from auto-join tests.
