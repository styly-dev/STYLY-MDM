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

Detailed observations are in the [November device log](../tasks/wifi-priority-2026-11-10/README.md),
[November verification record](../tasks/wifi-priority-2026-11-10/VERIFY.md),
and [October hardware record](../tasks/wifi-hardware-validation-2026-10-20.md).

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
- The transition AP advertised PSK and SAE. A PSK configuration connected to
  it with Android security type `4` (SAE) and a usable IP. The WPA2-only AP was
  also observed with PSK and an IP. These runs do not prove behavior on a
  WPA3-only AP, on a non-SAE client, or with a 1–7-character SAE password.
- Android did not expose the saved Wi-Fi password to the app. Recovery can
  restore credentials that MDM already knows, but cannot reconstruct an unknown
  password after it has been overwritten. The chosen policy permits that
  overwrite; the operator may need to re-enter the old password after failure.
- A first wrong-password trial unexpectedly connected after a changed password
  was published. Authentication caching is a plausible explanation, but was
  not established. The shell user could not flush PMKSA. After removing only a
  network confirmed to have been created for the test, the device reported a
  wrong-password rejection and the client connected to the lower candidate.
  Do not use a successful SSID/IP check as proof that the newly supplied
  credential was freshly authenticated.
- Wi-Fi credentials are stored in plaintext in the private server registry and
  backups, and are sent to the device when needed. Keep credentials out of
  logs and evidence, and protect registry backups and test files accordingly.

## Permissions and PICO-specific operations

- In the October recovery test, precise location, Location enabled, and a
  location foreground service were insufficient for `getConfiguredNetworks()`
  after Wi-Fi disconnected. Granting `ACCESS_BACKGROUND_LOCATION` allowed
  recovery on that PUI build. Check the granted permissions and device state,
  not just the manifest. The current managed flow also checks Device Owner and
  exact-alarm access.
- PICO auto-join changes use the TobService `allow_wifi_auto_join` message.
  Read back the actual `WifiConfiguration.allowAutojoin` flag; do not infer the
  final saved state only from a successful request.
- On the tested device, ADB reported model `A9210` while application processes
  reported `A94U0`. Model checks and diagnostic output can therefore differ
  depending on where they are read; do not treat these two values as evidence
  of two different devices.
- Keep ADB on USB during Wi-Fi switching. Resolve the target using its on-device
  Device ID and recheck it before an operation; a Wi-Fi ADB IP or a remembered
  serial alone is not a stable identity. If the transport drops, inspect the
  actual device state before deciding whether to retry a mutation.

## Implementation decisions made from these findings

These are current product choices, not guarantees supplied by Android or PICO.
The operator-facing details are in [Wi-Fi settings](WIFI_SETTINGS.md).

- One package setting contains an ordered list of up to four networks and a
  package-wide automatic-return toggle. Open and WPA2/WPA3 Personal are the
  supported choices. Passwords are 1–63 printable ASCII characters; 1–7
  characters are WPA3-only, while 8–63 permit WPA2 fallback.
- Before a Wi-Fi mutation, the client records saved network identities,
  auto-join flags and MDM-known credentials. It waits up to two seconds for a
  usable current connection to become identifiable and match one saved
  network. If a usable connection remains ambiguous, it fails before mutation;
  if there is no usable connection after the observation window, it treats the
  device as disconnected.
- The initial mutation starts a 60-second overall budget; an individual
  candidate gets up to 12 seconds within it. A normal disconnect has an
  eight-second grace period before ordered reselection. Repeated disconnected
  reselection failures back off from five minutes to at most 30 minutes.
- With automatic return on, the app looks for higher candidates while connected
  to a lower one. The first scan may happen immediately, later requests target
  about five minutes, and fresh passive results may also trigger a switch.
  Failed scan requests back off to as much as 30 minutes. Hidden SSIDs are not
  supported with automatic return on. With it off, a working lower connection is
  retained; ordered reselection still applies after a disconnect.
- A failed automatic return was found in a host test to restart scanning and
  immediately attempt another switch after recovery. The final implementation
  waits five minutes after any automatic-return operation settles before
  another one, even after a successful move. This in-memory wait resets on app
  process restart; a new package revision is not delayed by the old wait. The
  delay change was host-tested and was **not** installed on the physical device.
- A failed switch restores saved auto-join flags and known credentials. If a
  usable connection existed before the attempt, restoration is confirmed only
  after the prior SSID and a usable IP are observed. If not confirmed within
  60 seconds, the result is recorded as unconfirmed and the saved connection
  is retried about every five minutes. A later recovery does not turn the
  original failed package application into a successful one.
- SSID and IP constitute local Wi-Fi success only. They do not prove the MDM
  server is reachable, and a pending report may remain local while offline.
  The latest Wi-Fi recovery status is separate from the historical package
  application result.

## Testing and operational lessons

- Test switching and recovery on the actual app execution path as well as with
  a small diagnostic probe. The first complete package test failed with
  `UPDATE_FAILED` and `RESTORE_FAILED` even though earlier saved-network
  switching had worked. The corrected package flow was subsequently tested.
- The first all-candidates-unreachable trial did **not** prove recovery: the
  client reported `RESTORE_UNCONFIRMED` and lost the retry journal when the
  prior SSID was not yet visible. A read-only probe exposed the observation
  race. After the fix, the same kind of trial returned to the prior SSID with
  IP and reported the failed operation with `rolled_back=true`.
- Use an isolated loopback server and private registry for device trials.
  A USB reverse tunnel proves policy delivery through that tunnel; it does not
  prove MDM server reachability over Wi-Fi. Record the baseline, original APK,
  app state, saved network identities and auto-join flags before mutation.
  Remove only test-created networks, then verify the original APK, connection,
  settings, Device Owner and service. A running service alone does not prove
  registration to the original server; that registration was not checked in
  the final November cleanup. The user's correction of an initially invalid
  saved 5 GHz credential was intentionally retained; later recovery checks
  used that corrected baseline.
- November verification covered the package flow, SAE and PSK connections,
  wrong-password fallback, short ON/OFF automatic-return observations, and
  recovery to the prior SSID/IP. The final host checks passed 926 server tests,
  145 console tests with one existing skip, and 341 Android tests; the Android
  development APK also built. These are recorded results, not a claim that the
  tests were rerun when this note was written.
- Hardware gaps remain: a five-minute background cadence, prolonged sleep,
  retry when the original AP is unavailable, restart during pending recovery,
  a short-password WPA3-only AP, and a non-SAE client. Relevant timer and
  recovery cases have host coverage, but that is not physical proof.
- The new Wi-Fi format deliberately drops the development-only old format and
  requires updated clients. An existing registry containing old Wi-Fi entries
  cannot load the configuration-package catalog until those entries are
  removed and recreated; the registry itself is retained. Finish old Wi-Fi
  operations first, protect a registry backup, and follow the revision-reset
  procedure in the [operator guide](WIFI_SETTINGS.md#operator-workflow).
- During this work the shared, ignored `tasks/todo.md` was overwritten. Only
  its last 80 recorded lines and the new task plan could be recovered. Ignored
  files can still contain user work: inspect and preserve them before writing,
  and coordinate a single writer for shared task notes.
