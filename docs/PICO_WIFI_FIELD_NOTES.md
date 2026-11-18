# PICO Wi-Fi field notes

Device findings from the Wi-Fi work between 2026-10-20 and 2026-11-17. Most
runs used DEV-005, a PICO 4 Ultra Enterprise on Android 14 / PUI 5.15.7 with
TobService 4.3.30, over USB ADB. Results for one device, firmware, and set of
access points are not proof for every PICO build or network. Raw logs are kept
outside the repository, and this file contains no credentials.

Runs before 2026-11-16 used Device Owner builds and a retired rollback flow.
Their platform observations remain valid; their product results do not
describe the current behavior. Unenroll development headsets provisioned as
Device Owner before installing a current build, and check with
`adb shell dpm list-owners`. Product behavior is described in
[Wi-Fi settings](WIFI_SETTINGS.md).

## Connections and scans

- A successful scan request does not mean the results are new. One request
  succeeded while the results were over three minutes old. In app tests, the
  update broadcast arrived after 4–6 seconds. Check it and the timestamps.
  A background service is not guaranteed to scan on schedule during sleep.
- `enableNetwork(id, true)` returns success even when the network does not
  authenticate or get an IP. Confirm the SSID, security, and IP instead.
- A connectivity callback can report the IP up to 250 ms before the SSID and
  security. Combine them only for the same network.
- With auto-join on, Android reconnects to a saved network without the app.
  With auto-join off, `enableNetwork(id, true)` still rejoins it and leaves
  auto-join off.
- The MDM WebSocket closes whenever Wi-Fi drops, even over a USB reverse
  tunnel. Policies published during a switch arrive after reconnection.
- Saving a profile for the connected network kept the link and IP for both
  `WifiManager` and TobService updates, with the same or a changed key.
- In two boots on 2026-11-17, MDM started about 1 second after
  `BOOT_COMPLETED`, TobService was ready about 1 second later, and Wi-Fi
  associated about 6 seconds after that. The boot reapply therefore usually
  starts before Wi-Fi is connected.
- For a package-driven network change, correlate the connection time with the
  package revision and its reported Wi-Fi result.

## Saved networks and credentials

- One saved network can appear as several configuration views with the same
  network ID, for example `WPA_PSK` and `SAE`.
- Network IDs can change across reboots. Re-resolve by SSID and security, and
  stop if the match is missing or ambiguous.
- Updating with a newly built partial `WifiConfiguration` returned `-1`.
  Updating a copy of the saved configuration worked.
- `getConfiguredNetworks()` reports `macRandomizationSetting = 0` for every
  network, while `dumpsys wifi` shows the stored value. Sending the copy back
  is treated as a MAC policy change and rejected for networks the app did not
  create. MDM records the policy of networks it creates and reads `dumpsys`
  for others, which needs `DUMP`.
- The configured-networks dump masks the first `length / 4 + 1` characters of
  each SSID on PUI 5.15.7.
- A WPA2/WPA3 transition AP accepted a PSK configuration and connected with
  SAE. WPA3-only APs and 1–7 character SAE passwords were not tested.
- Android does not expose saved passwords. After a password change, a
  same-boot reconnection can succeed with a wrong key, likely through cached
  authentication; PMKSA could not be flushed from the shell. An in-place write
  reset `hasEverConnected`, and a radio off/on cycle still rejoined. SSID and
  IP do not prove that a new password works.
- After an authentication failure, Android permanently disabled the network
  for selection until a corrected password was deployed.

## PICO-specific behavior

- `getConfiguredNetworks()` after disconnection needed
  `ACCESS_BACKGROUND_LOCATION` in addition to precise location and Location on.
- Auto-join changes use TobService `allow_wifi_auto_join`. Read back
  `WifiConfiguration.allowAutojoin` to confirm the result.
- `pbsControlSetAutoConnectWIFI` keeps a single default network, stores its
  password in plaintext in `/sdcard/pre_resource/config/config.txt`, and
  recreates that profile at boot. It cannot provide ordered fallback.
- ADB reports model `A9210`, while apps see `A94U0`, on the same headset.
- A BDM-enrolled headset reapplied its sleep policy after Wi-Fi association at
  boot and on reconnection. Unenroll headsets from BDM before managing them
  with MDM.

## Recent product results

The 2026-11-16 builds still left auto-join on for every listed network.

- 2026-11-16, local package injection: an out-of-range first network led to
  `FALLBACK` in about 16 seconds. Two out-of-range networks reported
  `UNREACHABLE` in about 26 seconds.
- 2026-11-16, LAN server: a wrong password on the active 6 GHz network led to
  `FALLBACK` on 5 GHz, reported over that connection. A corrected deployment
  switched back to 6 GHz, which reconnected after reboot.
- 2026-11-17: with a cached profile whose first network was unsaved, the
  controller selected the fallback and reported `FALLBACK` in 13.4 seconds,
  leaving its auto-join off.
- 2026-11-17: networks created by MDM updated through `WifiManager` without
  `DUMP`. Others updated through TobService with `DUMP`. Without `DUMP`, an
  external network reported `PERMISSION_DENIED` while the other network still
  updated.
- 2026-11-17: the package applied notice appeared once after deployment and
  once after reboot.

Not yet verified on a headset: Android selection mode, recovery when the access
point comes up after boot, and MDM-delivered multi-network fallback, automatic
return, and return to the previous network with the current APK.

## Test method

- Test through the package path as well as with a small probe. Record SSID,
  security, IP, network order, result reason, and whether MDM reconnected.
- Use an isolated loopback server and private registry. A USB reverse tunnel
  proves delivery through the tunnel, not MDM reachability over Wi-Fi.
- Record the original APK, app state, saved networks, and auto-join flags
  before changes. Remove only test-created networks and verify the original
  state afterward.
- Keep ADB on USB while switching Wi-Fi. Identify the headset by its Device ID,
  not a Wi-Fi ADB address or remembered serial.
- Keep credentials out of logs and evidence. Registry backups contain
  plaintext Wi-Fi passwords.
