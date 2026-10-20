# Group Wi-Fi settings

The server saves profiles and sends them explicitly to online group members.
Clients decide success and recover locally. Internet access, MDM reachability,
server ACKs and reconnection are not success conditions. The managed LAN uses
no login, session cookie or device token.

## Operator workflow

Open **Manage Groups**, select a group and choose **Wi-Fi**.

1. Before a device's first managed deployment, save known working recovery
   credentials and explicitly choose **Register recovery baseline**. This stores
   an initial baseline on the client without changing Wi-Fi, enabling boot
   correction or proving connectivity. It can be replaced until first success.
2. Save the desired candidate networks, then choose **Send to whole group**.
   Saving alone changes no device. Replacement can delete existing OS networks;
   failure restores only credentials held by MDM, not unknown previous settings.
3. After a successful managed deployment, its profile becomes the recovery and
   boot baseline. Initial registration cannot replace that successful baseline.

Each send freezes the saved revision and online membership. Offline members
are excluded, retain previous status and receive nothing automatically when they
reconnect. Unsupported online clients receive no credentials and are marked
send-failed. Each new explicit send creates a new operation.

**Sent** means the WebSocket send completed. It does not prove receipt,
application or connection. Other states are **Not sent**, **Send failed** and
**Send unconfirmed**. **Sent (previous profile)** refers to an older revision.
The server stores only the latest delivery per device and collects no client
application/rollback/boot results. Online/offline is displayed separately.

Membership edits use the separate **Devices** tab. Wi-Fi sends and counts use
saved membership, even while membership edits are unsaved. Each device belongs
to at most one group; unassigned devices are allowed.

## Stop managing Wi-Fi

**Delete Wi-Fi settings** saves a **Do not manage Wi-Fi** revision and preserves
the previous delivery record. Its warning states that existing device networks
remain. Deletion sends nothing: choose **Send to whole group** separately.

Receiving this unmanaged command persistently disables boot reapplication while
retaining Android Wi-Fi networks and historical successful/initial profiles.
Offline devices keep their policy until a later explicit send while online.
An already active attempt may finish or recover, but cannot override the newer
unmanaged policy or promote its old candidate as the successful baseline.
Only a later managed deployment's local success re-enables management.

## Client lifecycle

- Validate authority, observation access and a known recovery profile before
  mutation. Freeze the last successful profile, or the explicit initial profile,
  in the durable operation journal. Without either, reject the command.
- At the first mutation, persist a deadline 60 seconds ahead and arm an exact
  elapsed-time alarm plus an in-process watchdog. Service/socket restarts do not
  renew the deadline. Alarm identity belongs to that operation, so finishing an
  older operation cannot cancel the next operation's alarm.
- Apply the candidate list, read back supported fields and require a fresh
  candidate SSID plus an assigned Wi-Fi IP before the deadline. Mobile/VPN and
  internet-validation status do not qualify. Android 10/11 requires an unambiguous
  IPv4-correlated Wi-Fi observation; Android 12+ uses per-network Wi-Fi identity.
- First success atomically commits the candidate and clears the journal. Later
  disconnects cause no rollback. Failure or interrupted manual work restores
  the frozen MDM-known profile. Failed/unknown restoration keeps a durable fence
  against another mutation; it is never reported as restored.
- After manual recovery, reapply the last successful profile once per actual OS
  boot while managed. Persist the claimed generation to avoid duplicate boot
  broadcasts/service starts. Boot correction has no connectivity test, rollback
  budget, continuous enforcement or retry loop.

The process owns one controller across service recreation. Startup recovery
begins after foreground-service promotion, before WebSocket commands. When
precise location is granted, the service includes the `location` type so saved
network/SSID reads stay visible; later grants refresh the type. Cold rollback
alarms start the same service. No background-location permission is requested.
Content auto-launch waits for startup recovery completion; physical ordering
against independently launched PICO content is not established.

The latest 500 operation IDs suppress repeated commands. This is bounded local
deduplication, not a permanent replay ledger. Local diagnostic results retain
100 recent entries and are never sent to the server.

## Setup and storage

| Setting | Purpose |
|---|---|
| `MDM_WIFI_ENCRYPTION_KEY` | Fernet key kept separately from data/backups |
| `MDM_WIFI_ALLOW_HTTP=1` | Allow managed-LAN HTTP when TLS is absent |
| `MDM_TLS_CERT` / `MDM_TLS_KEY` | Both PEM files when serving TLS |

Generate a key using `python -c 'from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())'`.
For managed-LAN HTTP, leave TLS files unset, enable HTTP and disable TLS in
Android Settings. Credentials then travel without transport encryption. For TLS,
configure both files, use HTTPS and enable TLS on Android; certificates must be
trusted. Clients use their existing manual/discovered MDM connection.

`wifi_settings.enc` encrypts profiles and latest deliveries. A missing/wrong key
for existing state fails startup. Group rename/delete uses
`wifi_group_mutation.txn` to recover the registry and encrypted profile together;
keep it with the data directory. Browser mutations enforce same origin; native
requests may omit Origin. These checks provide no operator authentication.
Passwords are redacted from APIs/logs. Android private no-backup storage retains
known recovery credentials; shared storage is not used for them.

## Device requirements and supported inputs

Configured-network management requires Device Owner or system authority,
precise location with location services enabled, and exact-alarm special access.
Runtime location permission alone does not grant management authority.

Profiles support Open and WPA/WPA2 Personal (PSK), at most 32 networks, with no
SSID/security duplicates. SSIDs contain 1–32 UTF-8 bytes; control characters,
quotes and backslashes are rejected. PSKs contain 8–63 printable ASCII characters
or 64 hexadecimal digits. A blank editor field retains a saved key only for the
same SSID/security. CSS masking suppresses account-password UI where supported;
the password-input fallback or browser extensions may still show prompts.

WPA3/SAE, EAP, WEP, proxy, static IP and configurable priority are unsupported.
Unknown OS configurations may be removed and are not restored. PICO security
views sharing a saved network ID are counted once; supported readback uses the
requested security view. Masked old PSKs are never guessed from SSID matches.

### Explicit Device Owner provisioning

`MdmDeviceAdminReceiver` has empty policy metadata and requires `BIND_DEVICE_ADMIN`.
It requests no admin prompt or extra policy and does not provision itself.
On an explicitly authorized device without an owner/accounts:

```sh
adb -s DEVICE_SERIAL shell dpm set-device-owner --user 0 com.styly.mdmclient/.MdmDeviceAdminReceiver
adb -s DEVICE_SERIAL shell dpm list-owners
```

Do not remove accounts or factory-reset automatically if provisioning is refused.
For removable tests, build with `-Pandroid.injected.testOnly=true`, install with
`adb install -r -t` and verify installed `TEST_ONLY` flags before provisioning.
Remove that test owner before a signing-key change using:

```sh
adb -s DEVICE_SERIAL shell dpm remove-active-admin --user 0 com.styly.mdmclient/.MdmDeviceAdminReceiver
```

Normal release owners may not support this removal. Unmanaged Wi-Fi and Device
Owner are separate: deploying unmanaged does not remove ownership.

## Protocol and migration

`REGISTER.capabilities` includes `wifi_settings_v3`. `EXECUTE_WIFI_APPLY` carries
operation_id, revision, mode and networks. Mode is managed, unmanaged or recovery.
Managed/recovery lists are nonempty and require `recovery_scope: mdm_profiles`;
unmanaged has an empty list. Incompatible clients do not receive settings.

| Route | Action |
|---|---|
| `GET /api/wifi/state` | Redacted group revisions/latest deliveries |
| `GET /api/wifi/groups/{name}` | Redacted saved form |
| `PUT /api/wifi/groups/{name}` | Save managed profile |
| `DELETE /api/wifi/groups/{name}` | Save unmanaged revision, preserve delivery |
| `POST /api/wifi/groups/{name}/deploy` | Send to frozen online targets |
| `POST /api/wifi/groups/{name}/recovery` | Send saved managed profile as initial baseline |

Every mutation requires `expected_revision` (null only for an initial save).
A revision conflict returns 409 before saving/sending and requires explicit
reload. Each socket send has a five-second timeout; this is separate from the
client's 60-second budget. Per-device send locks prevent reversed send order;
superseded requests cannot replace newer delivery records. Interrupted sending
becomes unconfirmed on server restart and is never replayed.

Server format 3 reads the current format 2 profiles/latest deliveries and discards
duplicate historical operations. Android schema 5 reads current schema 4 baselines,
policy/results and known-profile recovery journals, replacing OS snapshot wrappers
with recovery networks. Earlier unreleased local prototypes are unsupported;
unknown legacy OS journals are rejected rather than partially restored. Preserve
backups and the matching encryption key; downgrade after these writes is unsupported.
Existing registry overlaps migrate deterministically to one group per device.

Physical results and exclusions are recorded in the
[hardware report](../tasks/wifi-hardware-validation-2026-10-20.md).
References: [WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager),
[network callbacks](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback),
[PICO Enterprise API](https://business.picoxr.com/us/doc/EnterpriseAPI-Intro).
