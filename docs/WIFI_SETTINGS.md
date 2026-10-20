# Group Wi-Fi profiles and local recovery

This implementation incorporates the operator's 2026-10-20 revision of the
supplied v1.1 specification and the later approved MDM-owned recovery contract.
MDM-server reconnection is **not** a success condition. The server sends settings;
each client checks and recovers locally using credentials already held by MDM.

## Operator workflow

Use **Manage Groups**, select a group, and open **Wi-Fi**. Save one or more
networks, then choose **Send to whole group**. Saving alone changes no device.
Before a device's first managed deployment, save known recovery credentials and
choose **Register recovery baseline** explicitly. This sends a local initial
baseline without changing Wi-Fi, enabling startup correction or claiming the
credentials connect. Until a successful MDM deployment exists, the initial
baseline can be explicitly registered again to correct it. After success, the
successful profile always takes precedence and registration cannot replace it.
Then save the candidate settings and send them normally. Replacement may remove
unknown existing networks; only the MDM-known recovery profile is restored on
failure, not the complete previous OS configuration.
The **Devices** tab edits membership separately. Target counts and delivery
status always use saved membership, even while membership edits are unsaved.
A device may belong to at most one group; unassigned devices are permitted.

Sending freezes the saved revision and the currently online group members.
Only compatible connected clients receive Wi-Fi settings. Unsupported online
clients get a send failure; offline members are excluded, retain their previous
send status, and do not receive queued settings when they reconnect. Sending
again is a new operation, with a new online snapshot.

The console reports **Not sent**, **Sent**, **Send failed** or **Send unconfirmed**,
with text/icons and color. An older successful send is **Sent (previous profile)**.
Online/offline is separate. **Sent** means the server completed a WebSocket send;
it does not prove receipt, successful Wi-Fi connection, application or rollback.
The server does not collect local manual, boot, mismatch or restoration results.
No last-checked timestamp is shown.

## Do not manage Wi-Fi

**Delete Wi-Fi settings** saves a new **Do not manage Wi-Fi** revision for the
group. The confirmation explicitly states that existing device Wi-Fi networks
remain, and that a separate explicit deployment is required. Deletion sends no
command, clears no device settings, and preserves prior delivery records.

Choose **Send to whole group** to send this unmanaged state. Receiving clients
persistently disable startup reapplication while retaining their existing Android
Wi-Fi networks and historical successful profile. Offline clients keep their old
policy until a later explicit deployment while connected; nothing is queued.
The server records sending this policy without claiming that a device applied it.

If a local connection attempt is already running, it can finish or restore for
safety. A newer unmanaged policy prevents that older attempt from re-enabling
startup reapplication or promoting an old managed baseline. A later managed
profile re-enables boot correction only after its own local connection succeeds.
Failed attempts do not re-enable management. Adding and saving a network after
an unmanaged revision returns the group's desired profile to managed mode, but
still requires an explicit deployment to change any device.

## Manual change on the client

1. Validate the command, recovery scope and management permissions. Select the
   last locally successful MDM profile, or the explicitly registered initial
   recovery profile before first success. Without either, stop before changing
   Wi-Fi. Freeze those known credentials in the durable operation journal.
2. At the first settings mutation, persist a fixed elapsed-time deadline 60
   seconds ahead and arm local rollback. An MDM service/socket restart never
   renews this budget.
3. Apply the candidate list and verify supported configuration API results.
   Observe the actual Wi-Fi network's SSID and assigned IP address locally.
   A default mobile/VPN network and internet validation do not qualify.
   Android 12+ uses per-network Wi-Fi identity. Android 10/11 correlates legacy
   Wi-Fi identity with the sole Wi-Fi network's IPv4 address; IPv6-only or
   ambiguous legacy observations fail closed instead of claiming success.
4. If a distributed SSID obtains an IP within the budget, atomically retain the
   candidate as the successful baseline and finish the journal. This is a
   one-time success: later disconnects do not trigger automatic rollback.
5. If no qualifying connection is observed before the deadline, or applying
   fails after changes, restore the operation's MDM-known recovery profile locally. MDM-server
   availability plays no role. Restoration failure remains a local fence against
   further changes; it is not reported as restored.

No HTTP request, ping, DNS lookup or MDM handshake is used as a connectivity
probe. The server may legitimately be unreachable from the new Wi-Fi network.
Normal client reconnect behavior remains separate from the Wi-Fi transaction.
Duplicate operation identities cannot execute settings mutations twice.

An interrupted durable manual journal is recovered before ordinary startup
work. A process restart conservatively restores unfinished changes rather than
waiting for a server response. Successful local state is committed before the
journal is cleared, so a late alarm cannot undo an already committed operation.

## Once-per-OS-boot correction

After recovering unfinished manual work, reapply the device's last locally
successful profile once per actual OS boot generation while management is enabled. Persist the claimed
boot generation to avoid duplicates from BOOT_COMPLETED and PICO service-boot
notifications. Service restart, screen-on, and reconnect do not trigger another
boot correction. Failed manual candidates never become the boot baseline.

Normal boot correction has no connectivity test, 60-second rollback, continuous
enforcement or unlimited retry loop. An old AP may have disappeared; boot
correction does not guarantee connectivity. Content auto-launch ordering still
requires physical-device validation; no strict before-content-start guarantee
is claimed.

## Setup and secrets

Configure `MDM_WIFI_ENCRYPTION_KEY` for encrypted profile storage. No administrator
login, session cookie or device token is required on the managed LAN.
Choose either TLS or explicit managed-LAN HTTP:

| Variable | Meaning |
|---|---|
| `MDM_WIFI_ENCRYPTION_KEY` | Fernet key stored separately from server data/backups |
| `MDM_TLS_CERT` / `MDM_TLS_KEY` | Both PEM files when using TLS |
| `MDM_WIFI_ALLOW_HTTP` | `1` permits managed-LAN HTTP only when TLS is absent |

Generate the encryption key with the installed environment:

```bash
python -c 'from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())'
```

For managed-LAN HTTP, leave TLS files unset, set `MDM_WIFI_ALLOW_HTTP=1`, open
`http://` and disable TLS in Android Settings. Wi-Fi credentials then travel
without transport encryption. For TLS, configure both files, open
`https://` and enable TLS on Android. The server advertises the matching WS/WSS
scheme. TLS certificates must be trusted; no certificate bypass is implemented.

Open the console directly. Clients receive Wi-Fi commands over their normal MDM
WebSocket connection, whether the server was configured manually or discovered.
The existing TLS setting selects WS or WSS. No enrollment step is required.
Same-origin checks still apply to browser Wi-Fi mutations; native tools can omit
Origin. These checks do not identify or authenticate an operator or device.

The server encrypts `wifi_settings.enc`, including password-bearing profiles.
Keep the key separate; losing or replacing it makes the store unreadable and
must fail startup. Group rename/delete transaction recovery uses
`wifi_group_mutation.txn`; keep that journal with the data directory. Logs and
redacted APIs do not contain Wi-Fi passwords. Android private
internal storage retains recovery credentials, legacy snapshots and successful profiles; shared
storage, normal backups and diagnostic logs do not contain those secrets.

## Supported devices and networks

The initial form supports Open and WPA/WPA2 Personal (PSK). Empty managed network lists,
duplicate SSID/security pairs and unsupported modes are rejected. SSIDs are 1–32 UTF-8 bytes;
this adapter rejects control characters, quotes and backslashes. PSKs are
8–63 printable ASCII characters or 64 hexadecimal digits. A blank password
field retains an existing password only for the same saved SSID/security entry.
The key editor disables autocomplete and uses a CSS-masked text input to avoid
login-password generation/saving prompts. Browsers without CSS masking support
fall back to a password input to keep the key hidden; those browsers or password
manager extensions may still show their own prompts.
Requested profiles do not support WPA3/SAE, EAP certificates, WEP, proxy or
static-IP configuration. Existing networks using those features may be removed
by replacement; they are not restored unless represented by a supported known
MDM profile. Multiple PSK/SAE views for one saved network ID are counted once;
verification selects the requested security view and removes unneeded IDs once.

The client needs device-owner/system authority for configured-network management,
precise location access for SSID identity, and exact-alarm access for local
rollback. Ordinary runtime permission alone does not grant management authority.
Masked old PSKs are not reconstructed from SSID matches. Known candidate and
recovery credentials are explicitly written, and fresh SSID/IP acquisition is
required for manual success. New operations do not preserve unknown passwords,
priority, proxy/static-IP or other OS-only configuration details. Previously
persisted legacy recovery journals retain their parcel restoration path.

### Explicit Device Owner provisioning

The client declares `MdmDeviceAdminReceiver`, protected by `BIND_DEVICE_ADMIN`,
with empty policy metadata. It does not request admin activation, provision
ownership automatically or apply extra restrictions. Receiver identity must
remain stable in future updates. Existing ordinary installations remain ordinary
until an operator explicitly provisions them.

For an explicitly authorized test device with no existing owner or accounts:

```sh
adb -s DEVICE_SERIAL shell dpm set-device-owner --user 0 com.styly.mdmclient/.MdmDeviceAdminReceiver
adb -s DEVICE_SERIAL shell dpm list-owners
```

This is not sufficient to guarantee readable old credentials. Do not remove
accounts or reset a device automatically when provisioning is refused. For a
removable debug test installation, build with `-Pandroid.injected.testOnly=true`,
install using `adb install -r -t`, and verify `TEST_ONLY` in the installed package
flags before provisioning. Such a test owner can be removed explicitly with:

```sh
adb -s DEVICE_SERIAL shell dpm remove-active-admin --user 0 com.styly.mdmclient/.MdmDeviceAdminReceiver
```

Do not assume this removal command works for a normal release installation.
Before switching signing keys or restoring a differently signed APK, remove the
test owner first. Wi-Fi unmanaged policy and Android Device Owner are separate
states: unmanaged deployment does not remove Device Owner.

## Protocol and migration

`REGISTER.capabilities` remains a string array and advertises
`wifi_settings_v3`. V1/V2 clients cannot run this recovery contract;
the server marks them unsupported without sending credentials.
Update client and server together. Other MDM capabilities remain compatible.
The login endpoint and admin/device-token settings have been removed. Old token
environment variables and old session cookies are ignored. Existing Android
private state remains readable; the obsolete device token is ignored and omitted on the
next normal state write. Update earlier token-gated clients to enable ordinary
manually configured and discovered Wi-Fi delivery without enrollment.

`EXECUTE_WIFI_APPLY` contains operation_id, revision, mode and networks. Mode is
managed, unmanaged or recovery. Managed/recovery lists are nonempty and require
`recovery_scope: mdm_profiles`, so a new client rejects an old server's destructive
managed command. Recovery registers the initial baseline without changing Wi-Fi.
Unmanaged commands have an empty list and change only the persistent
startup-management policy. There is no
Wi-Fi verification request/ACK, result ACK, observation or reconnect-time send.

| Route | Operation |
|---|---|
| `GET /api/wifi/state` | Read redacted revisions and per-device delivery status |
| `GET /api/wifi/groups/{name}` | Read redacted saved form values |
| `PUT /api/wifi/groups/{name}` | Validate/save a new managed profile revision |
| `DELETE /api/wifi/groups/{name}` | Save an unmanaged revision; preserve device settings/delivery |
| `POST /api/wifi/groups/{name}/deploy` | Send the frozen profile to current online members |
| `POST /api/wifi/groups/{name}/recovery` | Send the saved managed profile as an initial recovery baseline |

PUT, DELETE and both POST routes require `expected_revision`, matching the profile that the
operator reviewed. A first PUT or DELETE explicitly uses null. Missing/malformed
values return 400; a concurrent revision change returns 409 before saving or
sending. The console preserves the draft and requires explicit reload. This
Wi-Fi API change requires the accompanying updated console.

Local schema4 preserves schema1–3 successful profiles and unfinished journals,
adding a separate initial recovery profile. Existing legacy parcel bodies remain
readable through the platform Parcelable creator; new recovery entries use known
credentials without OS parcels. Do not downgrade after writing schema4 state.

State includes `allow_http`, group revision/mode and `devices[id].delivery`. Delivery status
is sent/failed/unknown, with fixed reasons. An internal unfinished send becomes
unknown on restart and is never replayed. Socket writes are serialized briefly per device so an older managed send cannot
arrive after a newer unmanaged send. Superseded attempts that have not started
are skipped; this is not a reconnect queue or a device-result wait.
The deploy response includes
`online_target_count` and per-device `operations`; it makes no application claim.
A socket send has a bounded five-second timeout, separate from the client’s
60-second connection budget. The old `MDM_WIFI_OPERATION_TIMEOUT` result-wait
setting is no longer used. No server-side device-operation fence waits for a
client that has changed LAN.

Older encrypted profiles/revisions survive migration. Earlier application results
are not reinterpreted as proof of a v2 send. Legacy unfinished delivery becomes
unconfirmed; stale result messages cannot change the new delivery status.
Older saved profiles without mode remain managed. Older Android state defaults
to management enabled; receiving an unmanaged command is what disables it.
Client recovery preserves existing baselines and interrupted restoration journals;
old server-verification journals recover locally instead of waiting for an ACK.
Downgrading after a server-store format migration is unsupported; retain a backup
and its matching encryption key when switching builds.

Legacy overlapping membership migrates to the first group in sorted name order;
unassigned devices remain unassigned. Downgrading cannot reconstruct the removed
overlaps. Group rename preserves the saved revision; editing creates a new one.

## Verification boundary

### PICO hardware result: 2026-10-20

On DEV-005, PICO 4 Ultra Enterprise / PUI 5.15.7 / Android 14, the ordinary
client failed management preflight. Explicitly authorized Device Owner
provisioning enabled configured-network writes but did not reveal old PSKs.
The operator approved the v3 MDM-known recovery contract rather than full OS
restoration. Unknown original configurations were removed during these tests.

With the available `s-5ghz` test AP, v3 physically verified explicit baseline
registration without Wi-Fi changes, fresh SSID/IP success without an MDM route,
same-SSID wrong-password rollback after approximately 60 seconds, recovery after
process termination, and once-per-boot recreation of an externally removed
successful configuration. Starting only the foreground service, without opening
an Activity, also restored a pending operation and the actual connection.
A subsequent unmanaged boot did not recreate an externally removed network.
Explicit managed re-enable succeeded after restoring basic connectivity; final
unmanaged deployment retained the connected Wi-Fi and successful baseline.
Office/guest APs became unavailable; no successful connection to those APs or
switching between two available APs is claimed.

Boot testing exposed an Android visibility failure: configured-network writes
succeeded but readback returned an empty list and location AppOps recorded a
rejection. The service now declares a location foreground-service type, selects
it only after precise-location permission is granted, and starts durable Wi-Fi
recovery only after foreground promotion. Later in-app location grants refresh
the running service type. Cold rollback alarms start the same service so startup
recovery can own the journal. No background-location permission is requested.

The server records sending only. Actual results were checked through private
client state and native Wi-Fi status, not inferred from delivery status or APK
build success. See the [hardware report](../tasks/wifi-hardware-validation-2026-10-20.md)
for individual outcomes, the final device state and untested cases. Other PICO
firmware, WPA3/EAP/static-IP restoration, two-AP switching, hardware duplicate
commands, exact-alarm-only recovery, active-operation reboot and content-start
ordering remain outside this hardware evidence.

References: [WifiManager](https://developer.android.com/reference/android/net/wifi/WifiManager),
[NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback),
[Android 12 Wi-Fi identity changes](https://developer.android.com/about/versions/12/behavior-changes-12),
[PICO Enterprise API](https://business.picoxr.com/us/doc/EnterpriseAPI-Intro).
