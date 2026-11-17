# PICO Wi-Fi field notes

This file collects the practical findings from the Wi-Fi priority work on
2026-11-10, including a few directly relevant observations from the earlier
2026-10-20 recovery test. It separates device observations from implementation
choices and test gaps. It contains no network credentials.

## Scope and evidence

The November hardware tests used one DEV-005 PICO 4 Ultra Enterprise, Android
14 / PUI 5.15.7. The app was Device Owner, Location was enabled, precise
location and background location were granted, and exact-alarm access was
allowed. The device was connected through USB ADB. A same-signature,
same-version, test-only diagnostic APK temporarily replaced the installed app.

The available networks included a WPA2/WPA3 transition AP, a WPA2-only AP, and
an existing WPA3-SAE connection. Findings for this device, firmware, radio
environment, and those APs are not proof for every PICO firmware or network.
The October test also used a PICO 4 Ultra Enterprise on Android 14 / PUI 5.15.7.
That earlier record identified TobService 4.3.30 and client SDK 4.3.16; it does
not establish the service version used in the November runs.

A follow-up run on 2026-11-11 used a PICO 4 Ultra Enterprise (PUI 5.15.7) with
the branch's development APK as Device Owner, an isolated loopback server over
a USB reverse tunnel, a WPA2/WPA3 office AP saved earlier in system Settings,
and a guest AP added by the app.

The raw device logs and verification records from these runs are kept outside
the repository; this file is the maintained summary.

The hardware lifecycle observations below include runs with an earlier
recovery implementation. Current priority mode keeps configured candidates,
enables Android auto-join for the first listed profile, and explicitly tries
candidates in priority order. The Android selection option enables auto-join
for every listed profile and delegates connections to Android. The API probe
and product deployment trials below cover the recorded builds and settings.

## Wi-Fi scans and connection observations

- A scan request returning success does **not** establish that the results are
  new. One shell request succeeded while the first results were over three
  minutes old. In app-context tests, updated-result broadcasts arrived after
  approximately 4.2 and 5.9 seconds, with fresh observations of the targets.
  Check the result-update signal and sample timestamps before using a scan to
  decide whether a higher-priority AP is available.
- One screen-off request produced fresh results while the diagnostic activity
  remained the top activity. This is not evidence that a background service
  can scan on a fixed schedule during prolonged sleep. Fresh scans can also be
  initiated by the system and observed by the app.
- `enableNetwork(..., true)` returned success even when the target did not
  authenticate or obtain an IP. Verify the intended SSID, compatible security,
  and usable IP on the observed connection; do not use the API return value
  alone as connection proof.
- On the November device, five immediate connectivity snapshots had a usable
  IP but no SSID. All five had the SSID and security within 250 ms; another
  run had them immediately. Treat the IP, SSID and security as observations
  for the same network, and allow for callback timing rather than mixing an IP
  from one connection with an SSID from another.
- In the 2026-11-11 recovery implementation, its reconnect to the prior network
  failed once with an authentication error during rapid back-to-back switches. About 2.4
  seconds later the OS auto-joined that network itself (Wi-Fi system UID), and
  in a process-kill test the OS and the restarting app requested the same
  network in the same millisecond. With auto-join enabled and the network
  selection status enabled, the OS reconnects without the app.
- In the 2026-11-11 recovery implementation, the client's MDM WebSocket closed
  whenever Wi-Fi dropped, even when the server
  is reached through a USB reverse tunnel. A release or policy published while
  a switch is in progress therefore reaches the device only after it
  reconnects, typically while the rollback is confirming the prior network.
- One device history showed a move between the test networks without proving
  the cause. Do not assume that every observed network change was made by the
  MDM app: correlate it with the app's operation ID and result where available.

## Saved networks, security, and credentials

- The device returned multiple saved-configuration views with the same numeric
  network ID, including `WPA_PSK` and `SAE` views. They are not necessarily
  separate saved networks. The adapter groups consistent views by network ID
  and checks their SSID, security and auto-join flags.
- Numeric network IDs are not durable identities. In the October reboot test,
  Android/PICO reassigned IDs while the saved network identities remained.
  Re-resolve against the current SSID and compatible security; if the match is
  missing or ambiguous, stop rather than modifying a guessed network.
- A newly constructed partial `WifiConfiguration` for an existing 5 GHz
  network caused `updateNetwork` to return `-1` on the November device. Updating
  a copy of the original configuration with the known credential succeeded, and
  connection was verified. The exact missing field or firmware cause was not
  established. The implementation copies an existing configuration before
  changing it, instead of replacing it with a partial object.
- On 2026-11-11 every managed apply failed with `UPDATE_FAILED` when a listed
  SSID had been saved earlier in system Settings (creator UID 1000). The app's
  copy from `getConfiguredNetworks()` reported `macRandomizationSetting = 0`
  (NONE) for every network, while `dumpsys wifi` showed the stored value 3
  (AUTO). Sending the copy back made the framework treat it as a MAC
  randomization change, which it rejects ("does not have permission to modify
  MAC randomization Settings") unless the caller holds `NETWORK_SETTINGS` or is
  a Device Owner updating a network it created. Networks the app added were
  unaffected. The tested build tried AUTO, NONE, PERSISTENT, and NON_PERSISTENT
  on its Device Owner path. With this change the apply succeeded and
  all saved networks kept setting 3. The NONE fallback (a network set to
  "device MAC" in Settings) is host-tested only. The earlier partial
  `WifiConfiguration` failure below may have had the same cause; that was not
  rechecked.
- On 2026-11-16 the configured-networks dump on DEV-005 masked the first
  `length / 4 + 1` UTF-16 code units of the displayed SSID. The client accepts
  that format or the complete SSID and checks the same saved network ID.
  The ownerless MDM package test confirmed an authentication failure on a
  saved WPA2 network, recovery to a WPA3 hotspot with IPv4, and successful
  redeployment of the corrected password to the original WPA2 network ID.
  Android's selection status for the failed network was permanently disabled
  with a wrong-password reason before the corrected deployment.
- The transition AP advertised PSK and SAE. A PSK configuration connected to
  it with Android security type `4` (SAE) and a usable IP. The WPA2-only AP was
  also observed with PSK and an IP. These runs do not prove behavior on a
  WPA3-only AP, on a non-SAE client, or with a 1–7-character SAE password.
- Android did not expose the saved Wi-Fi password to the app. The current
  priority policy keeps a usable first-candidate link after saving and records
  current-boot credential uncertainty for a new or previously unknown key.
  Ordered selection can use a saved candidate while verification is pending.
- A first wrong-password trial unexpectedly connected after a changed password
  was published. Authentication caching is a plausible explanation, but was
  not established. The shell user could not flush PMKSA. After removing only a
  network confirmed to have been created for the test, the device reported a
  wrong-password rejection and the client connected to the lower candidate.
  Do not use a successful SSID/IP check as proof that the newly supplied
  credential was freshly authenticated.
- On DEV-005, an in-place password write on an active SAE connection reset
  Android's `hasEverConnected` flag. Disconnecting before the write still
  allowed same-boot success with an incorrect password. The device also
  rejoined after a PICO radio-off/radio-on cycle that reached Android's
  fully disabled state. The exact cache or retained session is unconfirmed.
  SSID and IP alone must not be treated as proof that a changed credential was
  used successfully.
- On 2026-11-17 a diagnostic APK invoked the current adapter's save operation
  on a connected 5 GHz test profile. Both the app-owned `WifiManager` path
  and the TobService path for a profile owned by a separate diagnostic UID
  accepted a same-key and a changed-key update. All four writes kept the same
  network handle and usable IP through eight seconds of observation. The AP
  kept its original key throughout. The diagnostic profiles and APKs were
  removed and the original connection, saved flags and MDM APK were verified.
- Wi-Fi credentials are stored in plaintext in the private server registry and
  backups, and are sent to the device when needed. Keep credentials out of
  logs and evidence, and protect registry backups and test files accordingly.

## Permissions and PICO-specific operations

- In the October recovery test, precise location, Location enabled, and a
  location foreground service were insufficient for `getConfiguredNetworks()`
  after Wi-Fi disconnected. Granting `ACCESS_BACKGROUND_LOCATION` allowed
  recovery on that PUI build. Check the granted permissions and device state,
  not just the manifest. The tested build also required Device Owner.
  The 2026-11-11 development build used in these trials declared
  `USE_EXACT_ALARM`, and PUI 5.15.7 granted it at install. That permission was
  used by the retired Wi-Fi rollback flow and is not needed by the current
  ordered-candidate policy.
- On DEV-005 (Android 14, PUI 5.15.7, TobService 4.3.30), the Device Owner's
  `pbsControlSetAutoConnectWIFI` call with `ext=0` stored one SSID and its
  plaintext test password in `/sdcard/pre_resource/config/config.txt` before
  completing the connection attempt. The boolean variant returned `false`
  after about 100 seconds for an absent AP. The error-code variant returned
  `4` for a second absent AP. Both failures left their Android profiles saved.
  The second call replaced the single PICO default. After the test removed the
  second Android profile, reboot recreated it from the remaining PICO default.
  On that managed headset, a separate non-owner app received `2` from the
  error-code setter. The same app cleared the PICO default with
  `pbsControlClearAutoConnectWIFI`, leaving both Android profiles and auto-join
  flags intact. On a reachable WPA2/WPA3 iPhone hotspot, the boolean setter
  returned `true` after 3.4 seconds. Android reported WPA3-SAE and had a usable
  IPv4 address. After removing the temporary Android profile before reboot,
  the headset recreated it and reconnected with IPv4 during startup. Clearing
  the PICO default while connected left the Android profile, association, and
  IPv4 in place. The single default and its failure behavior do not provide
  ordered candidate fallback required by the MDM policy.
- PICO auto-join changes use the TobService `allow_wifi_auto_join` message.
  Read back the actual `WifiConfiguration.allowAutojoin` flag; do not infer the
  final saved state only from a successful request.
- On 2026-11-17, an ownerless diagnostic app on DEV-005 (PUI 5.15.7, target
  SDK 33) turned off auto-join for the connected saved network and disconnected
  it. After IPv4 was lost, `WifiManager.enableNetwork(savedId, true)` rejoined
  the same saved profile with IPv4 and left auto-join off. The test restored
  the original flag and connection and removed the diagnostic app.
- On the tested device, ADB reported model `A9210` while application processes
  reported `A94U0`. Model checks and diagnostic output can therefore differ
  depending on where they are read; do not treat these two values as evidence
  of two different devices.
- Keep ADB on USB during Wi-Fi switching. Resolve the target using its on-device
  Device ID and recheck it before an operation; a Wi-Fi ADB IP or a remembered
  serial alone is not a stable identity. If the transport drops, inspect the
  actual device state before deciding whether to retry a mutation.

## Current implementation decisions

These product choices are not guarantees supplied by Android or PICO. The
operator-facing details are in [Wi-Fi settings](WIFI_SETTINGS.md).

- One package setting contains an ordered list of up to four networks and a
  package-wide automatic-return toggle. Open and WPA2/WPA3 Personal are the
  supported choices. Passwords are 1–63 printable ASCII characters; 1–7
  characters are WPA3-only, while 8–63 permit WPA2 fallback.
- After confirming at least one candidate save, the client sets Android
  auto-join by saved ID. Priority mode enables the first listed network.
  Android selection mode enables every listed SSID and security match and
  delegates connections to Android. Auto-join is disabled for other saved IDs
  in either mode, and the profiles are retained.
- The client applies candidates in list order and keeps a usable primary link
  after an unverified credential save. Connection attempts include candidates
  with unverified credentials. A usable unverified connection reports
  `VERIFY_AFTER_REBOOT` after profile and flag checks pass. A verified lower
  candidate reports `FALLBACK`. Save and flag readback failures stay visible.
- After disconnection, Android can reconnect to the first listed network. For
  multiple candidates or a cached fallback profile, the client waits about
  8–11 seconds, keeps a usable connection already restored by Android, and
  otherwise explicitly tries saved candidates in order without an app scan.
  After all fail, it retries with an increasing wait capped at about one
  minute plus jitter, stopping when it obtains a usable IP. With automatic
  return on, it looks for higher candidates while connected to a lower one;
  if the higher attempt fails and disconnects the headset, it explicitly
  reselects the previous lower candidate. The first scan runs within about 30
  seconds, later requests target about five minutes plus up to 10% random
  delay, and fresh passive results may also trigger a switch. Any visible
  higher candidate triggers a switch regardless of signal strength. Failed
  scan requests back off to as much as 30 minutes. Hidden SSIDs are not
  supported with automatic return on.
- A settled automatic-return attempt waits five minutes before another one,
  even after a successful move. Consecutive failures double that wait up to 30
  minutes. The in-memory wait resets on app process restart. The timer changes
  were host-tested and were not installed on the physical device in the
  recorded November runs.
- On 2026-11-11 a brightness-only revision kept the active connection through
  the client's matching Wi-Fi shortcut. Same-boot updates carry a matching
  Wi-Fi profile into the new revision without rewriting or reconnecting.
- On 2026-11-12 the BDM-enrolled DEV-001 reapplied its sleep policy through
  `tobmdm` after Wi-Fi association at boot and after a Wi-Fi reconnect. DEV-005
  kept its 1800-second sleep delay across both checks after it was unenrolled.
  The fixed boot delay was removed based on this comparison.
- SSID and IP show a local connection only. They do not prove that Android
  authenticated with a newly supplied password or that the MDM server is
  reachable. A report can remain local until the device reconnects to MDM.

## Testing and operational lessons

- Test Wi-Fi through the configuration-package path as well as with a small
  diagnostic probe. A successful Android API return does not prove the target
  authenticated or obtained an IP. Record the observed SSID, security, IP,
  candidate order, result reason, and whether MDM reconnected.
- On 2026-11-17 a diagnostic loaded the built DevDebug Wi-Fi controller with a
  cached profile whose first candidate was unsaved. From a disconnected state,
  it selected the known-good saved fallback, obtained IPv4, and reported
  `FALLBACK` after 13.4 seconds. Auto-join remained off. The test restored the
  four saved flags and the original connection, then removed the diagnostic.
  It exercised offline candidate selection without changing credentials or
  using a server. Multi-AP package deployment and automatic return remain
  unverified in this build.
- On 2026-11-17, ownerless DEV-005 on PUI 5.15.7 ran the package controller
  and hybrid Wi-Fi adapter in a separate targetSdk 33 diagnostic APK. Auto-join
  writes were scoped to two temporary profiles. App-created updates used
  `WifiManager` without `DUMP`. A profile recreated by TobService under
  the same SSID used the privileged update path despite the earlier app record.
  Both paths kept the saved ID, MAC policy, and DHCP assignment and connected
  to the test access point with IPv4. Mixed policies used both update paths.
  With `DUMP` revoked and the external candidate first, the package reported
  `PERMISSION_DENIED`, the app-created candidate still updated, and the test
  device reconnected to the access point. Package payloads were injected locally.
  Cleanup verified the original four profiles, their auto-join flags, and
  the original Wi-Fi connection.
- The recorded November hardware trials used the earlier recovery flow. They
  covered authentication failure, recovery to a previous network, release,
  process restart, SAE and PSK connections, and short automatic-return
  observations. The old result `rolled_back=true` and recovery records do not
  prove the current candidate-retention and fallback behavior.
- The host checks recorded for that earlier implementation passed 926 server
  tests, 145 console tests with one existing skip, and 341 Android tests; the
  development APK also built. These are historical results, not a claim that
  they were rerun for the current branch.
- Use an isolated loopback server and private registry for device trials. A
  USB reverse tunnel proves policy delivery through that tunnel; it does not
  prove MDM server reachability over Wi-Fi. Record the baseline, original APK,
  app state, saved network identities, and auto-join flags before mutation.
  Remove only test-created networks, then verify the original APK, connection,
  settings, Device Owner, and service. A running service alone does not prove
  registration to the original server; that registration was not checked in
  the final November cleanup. The user's correction of an initially invalid
  saved 5 GHz credential was intentionally retained for the subsequent runs.
- On 2026-11-16 the candidate-retention APK was tested on DEV-005 through the
  device's configuration package controller. An out-of-range first candidate
  led to `FALLBACK` in about 16 seconds and the iPhone fallback obtained IPv4.
  Both saved profiles kept auto-join enabled. A subsequent revision selected
  the iPhone as its first choice, and Android rejoined it after reboot. A
  package with two out-of-range candidates reported `UNREACHABLE` in about 26
  seconds and kept both profiles enabled. The test injected packages locally
  through USB instrumentation, so it did not prove MDM transport over the
  iPhone connection. The original APK, app settings and Wi-Fi profiles were
  verified after cleanup.
- A second run used 6 GHz as the primary and 5 GHz as the fallback, with the
  running server bound to a Mac on the same LAN. A deployment changed the
  primary credential to an incorrect value while DEV-005 was using 6 GHz.
  The server received `FALLBACK` over the 5 GHz connection. Both profiles
  kept their saved IDs and auto-join flags. While connected to 5 GHz, the
  headset received the corrected deployment, switched back to 6 GHz, then
  connected to 6 GHz again and appeared online after a full reboot. The AP
  credentials were unchanged during this test. Cleanup verified the original
  client APK, app state, and 6 GHz connection and removed the isolated test
  registry.
- See the [configuration package registry notes](DEVELOPMENT.md#configuration-packages)
  for the supported catalog versions and backup behavior. The server retains
  data from unsupported development catalogs and marks the package catalog
  unavailable.
