# Wi-Fi in configuration packages

Wi-Fi settings support PICO 4 Ultra Enterprise on Android 13 or later on the
managed LAN. See
[configuration packages](DEVELOPMENT.md#configuration-packages) for saving,
deployment, assignment, and delivery behavior.

## Operator workflow

Open **Manage Groups → Configuration Packages**, choose a package, then expand
**Network & connectivity**. Select **Manage** on **Wi-Fi networks** and add
Open or WPA2/WPA3 Personal networks. A blank password field keeps the saved key
for the same SSID and security. Choose **Save & deploy**. The confirmation
includes all assigned groups and members. Offline devices receive the latest
revision when they connect. Group **Settings** shows Wi-Fi readiness and the
last package result.

In priority mode, a headset connected to the first listed network keeps that
link when it is still usable after the save. When the deployment includes new
or previously unknown credentials for that network, the console can show
**Saved. Verify after reboot.** The headset checks the saved credentials on the
next Android boot.

**Prioritize package Wi-Fi networks** is on by default. In priority mode, only
the first listed network is eligible for Android auto-join. The client selects
saved candidates in list order when it needs a connection. A successful save
of a lower-priority candidate still leaves auto-join reserved for the first.

Turn priority off to let Android choose the connection. The device enables
auto-join for every listed SSID and security match and turns it off for other
saved networks. Android handles connection and reconnection. The result
confirms saved settings and auto-join flags by readback. Install the current
client APK on affected headsets before deploying Android selection.

The headset keeps its saved profiles in both modes. When no candidate can be
saved and read back, the client reports a failure before changing auto-join
flags. If none of the listed networks connects, the device may lose contact
with MDM. Include a backup candidate with a known password before changing the
active network's password or deploying uncertain credentials.

A package can contain up to four networks. In priority mode, the client tries
the next candidate after an unsuccessful connection attempt. When all profiles
and flags are saved and a verified lower-priority candidate connects, the
result is applied with reason `FALLBACK`. A profile write or readback failure
remains a failed Wi-Fi result even if another candidate connects. Typical
failure reasons include `UNREACHABLE`, `UPDATE_FAILED`, and `VERIFY_FAILED`.
Reconnect the headset manually if needed before retrying or deploying corrected
settings.

If the boot attempt saves and verifies the Wi-Fi settings before the access
point becomes available, the console can initially show `UNREACHABLE`.
The client marks Wi-Fi applied when it later observes an eligible listed
network with a usable IP address during the same boot. It confirms the
connection without rewriting settings or disconnecting the headset.

Android does not let the client read a saved password back. In priority mode,
a connected protected network's changed or previously unknown password stays
unverified until the next OS boot. A usable connection to that candidate can
report `VERIFY_AFTER_REBOOT` once profile and flag checks pass. Reconnection
and automatic return still try that candidate in order and keep a usable
connection. Automatic selection and app restarts preserve the pending result.
Retry for another setting also carries it forward during the same boot.
SSID and IP observations establish local connectivity. Credential
verification and MDM reachability require their own checks.

In priority mode, the client tries candidates in order when applying policy.
When automatic return is on, it also checks for higher-priority networks while
connected to a lower candidate, including after manual selection or fallback.
This is useful when a backup network has no route to the MDM server. The first
check runs within about 30 seconds, with later requests about five minutes
apart plus a small random delay. A fresh system scan can trigger a switch
earlier. No priority scan is needed while connected to the first candidate. A
switch can temporarily interrupt communication. If a higher-priority attempt
fails, the client explicitly tries the previous lower candidate when still
disconnected, because its Android auto-join is off. After an automatic-return
attempt settles, the client waits about five minutes before another attempt.
Consecutive failures double the wait up to 30 minutes. A success resets it. A
changed Wi-Fi policy can apply during this wait. An app process restart clears
it.

In priority mode, Android can reconnect to the first listed network after a
disconnection. For a package with multiple candidates, or a cached fallback profile with
auto-join off, the client waits about eight seconds plus up to three random
seconds. If Android has already restored a usable connection, the client keeps
it. Otherwise it explicitly selects saved candidates in package order without
an app-initiated scan. If every candidate fails, it retries with an increasing
delay capped at about one minute plus a small random delay, stopping when a
candidate has a usable IP address. A single successfully saved primary network
uses Android auto-join for reconnection.
Automatic return does not support hidden SSIDs; turn it off to use hidden
networks.

A matching Wi-Fi setting with a successful result is reused on same-boot
deployments. To apply Wi-Fi again, use **Reapply settings** on the headset or
reboot it. Releasing the package stops management. The auto-join flags already
written still apply until they are changed on the headset or by a later policy.
The **No package** confirmation explains this for either Wi-Fi mode.

WPA2/WPA3 Personal prefers WPA3 when supported and allows WPA2 fallback.
Passwords contain 1–63 printable ASCII characters; passwords shorter than
eight characters can connect only with WPA3. Update the client APK before
deploying the ordered Wi-Fi format. The client stores Wi-Fi policy state in
`wifi-policy.json` and does not migrate the previous Wi-Fi state file. Reapply
the package policy after updating the APK.

The console shows the connected client's auto-return monitor state separately
from the package-application result. Successful Wi-Fi application includes
readback of the saved profiles and the requested auto-join flags. Priority mode
also confirms a local connection to a candidate. The `FALLBACK` reason indicates
a connection to a lower-priority candidate after an earlier candidate failed.
The monitor depends on fresh Android scan results. Scan throttling, sleep,
permissions, and radio conditions can delay detection or make monitoring
degraded; no detection interval is guaranteed. When scan requests fail, the
client increases its request interval from ten minutes up to 30 minutes; fresh
passive scans can still trigger detection.

## Provisioning

Provision PICO 4 Ultra Enterprise headsets without a Device Owner. Unenroll
headsets provisioned as Device Owner with an earlier release before replacing
the APK. Verify the owner list with `adb shell dpm list-owners`.

Grant these permissions when installing the client for managed Wi-Fi.

```sh
adb shell pm grant com.styly.mdmclient android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.styly.mdmclient android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.styly.mdmclient android.permission.ACCESS_BACKGROUND_LOCATION
adb shell appops set com.styly.mdmclient SYSTEM_ALERT_WINDOW allow
```

Also enable **Location** in the headset's system settings. The overlay app-op
allows the client to read saved network IDs and auto-join flags on PUI. On the
headset, **Permissions → Wi-Fi configuration** shows the individual grants and
whether Location services are on. **Review Wi-Fi permissions** opens the next
location or overlay setting that needs attention.
Background location keeps saved-network reads available after disconnection.

When provisioning with the ADBK Wi-Fi step, grant `DUMP` at the same time so
MDM can update the initial saved network remotely. The grant is also needed for
updates to profiles created in Android Settings and profiles whose MAC policy
MDM has no record of. New profiles and auto-join changes use the basic grants.

```sh
adb shell pm grant com.styly.mdmclient android.permission.DUMP
```

The client uses TobService's `allowWifiAutoJoin(null, networkId, allowed)`
to set the flag by saved network ID. It reads `WifiConfiguration.allowAutojoin`
through reflection to verify the saved value. When MDM creates a profile, it
records the MAC policy it supplied for the SSID and security type. For an
update, it resolves the current saved ID and passes that policy to
`WifiManager.updateNetwork()`, which checks the app's update authority.

For an update that needs TobService, the client reads the current MAC policy
for the target ID from `dumpsys wifi`. It rechecks the saved identity and uses
the firmware's update-by-ID path. Missing `DUMP` or a diagnostic read failure
fails that candidate. The device continues applying any other candidates in the
package and reports the Wi-Fi failure.

## Device evidence

On DEV-005 (PUI 5.15.7, TobService 4.3.30), an out-of-range first
candidate led to a `FALLBACK` result and an iPhone hotspot connection with
IPv4. Earlier tests kept both profiles enabled for auto-join. Priority mode
enables it only for the first listed network.
In a second run an incorrect password on the active 6 GHz network led to a
5 GHz fallback. Over that connection the headset reported `FALLBACK` to the
test server, received a corrected package, and rejoined 6 GHz after reboot. The
[PICO Wi-Fi field notes](PICO_WIFI_FIELD_NOTES.md) detail the test setup and
its limits.

## Supported inputs and credential storage

Policies accept 1–4 networks, unique by SSID and security. SSIDs must contain
1–32 UTF-8 bytes without control characters, quotes, or backslashes. WPA2/WPA3
Personal passwords contain 1–63 printable ASCII characters; use at least eight
characters to allow WPA2 fallback. Hidden networks are supported when
automatic return is off.

The server keeps plaintext Wi-Fi credentials in the private package registry
and backups. HTTP editor responses, device reports, and console logs omit
passwords. The policy sent to an eligible member includes the credentials
required to configure its networks.
