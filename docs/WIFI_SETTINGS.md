# Wi-Fi in configuration packages

Wi-Fi settings support PICO 4 Ultra Enterprise on Android 13 or later on the
managed LAN. Package deployment and the client implementation are described in
[configuration packages](DEVELOPMENT.md#configuration-packages).

## Operator workflow

Open **Manage Groups → Configuration Packages**, choose a package, then expand
**Network & connectivity**. Select **Manage** on **Wi-Fi networks** and add
Open or WPA2/WPA3 Personal networks. The editor displays saved and newly typed
passwords in plain text. Each secured network needs a password. Edit the
displayed value to replace it, then choose **Save & deploy**. The confirmation
includes all assigned groups and members. Offline devices receive the latest
revision when they connect. Group **Settings** shows Wi-Fi readiness and the
last package result.
Turning off **Manage** keeps the previously saved list and keys. Draft network
rows are ignored while deploying Wi-Fi as unmanaged. Returning **Manage** to
its saved off state leaves no Wi-Fi change to save.

## Modes

**Prioritize Wi-Fi by list order** is on by default.

- **Priority mode**: only the first listed network has Android auto-join on.
  Other saved networks have it off. When the headset needs a connection, the
  client tries the listed networks in order. A headset already connected to the
  first listed network keeps that connection. Each candidate gets up to 12
  seconds, including time to disconnect and obtain an IP address, within the
  overall connection attempt of up to one minute.
- **Android selection** (priority off): every listed network has auto-join on
  and other saved networks have it off. Android chooses and reconnects.
  Automatic return is not available.

In both modes the headset keeps its saved profiles. If no listed network can be
saved, the client reports a failure and leaves auto-join unchanged.

If none of the listed networks connects, the headset can lose contact with MDM.
Include a backup network with a known password before changing the active
network's password or deploying uncertain credentials.

## Results

Select the device card in **Device status** to see the Wi-Fi result and the
other verified settings. An applied setting shows a green check. A failed or
unverified setting shows a red cross next to its name and the reason, when reported.

| Console | Meaning | Action |
|---|---|---|
| Saved | The headset saved the listed profiles and read them back with their auto-join flags. | None. |
| Saved. Android selects the network. | Android selection: profiles and auto-join flags were read back. | None. |
| Update failed or Verify failed | A profile or auto-join flag could not be written or read back. Shown even if another network connected. | Check provisioning, then Retry. |
| Permission denied | A required grant is missing. | See [Provisioning](#provisioning). |

Android masks saved passwords. The readback checks network profiles and
auto-join flags. A successful readback completes the Wi-Fi setting. The
headset handles association and fallback independently and reapplies the saved
package after a reboot.

## Reconnection and automatic return

These apply to priority mode.

The device card shows a live notice when auto-return is degraded, unavailable,
or switching to a higher-priority network.

- After a disconnection, Android reconnects to the first listed network. With
  more than one listed network, the client waits about 10 seconds and then
  tries the networks in order. It keeps retrying with a growing delay of up to
  about a minute. While Wi-Fi is off, it waits for the radio to become available.
- With automatic return on, a headset on a lower network requests its first
  scan within 30 seconds. It requests five more scans about one minute apart,
  then scans about every five minutes. A failed scan ends the one-minute checks
  for that monitoring session. Any visible higher network triggers a switch,
  regardless of signal strength. A switch briefly interrupts communication.
  If it fails, the client reconnects to the previous network. After each
  attempt the client waits five minutes, doubling up to 30 minutes after
  consecutive failures. Scanning pauses during this wait.
- Detection depends on Android scan results. Sleep, throttling, and radio
  conditions can delay it. The console shows the monitor state separately from
  the package result. If the client cannot register to receive scan results, it
  tries again on the next Wi-Fi readiness or connectivity check.
- Automatic return does not support hidden SSIDs. Turn it off to use hidden
  networks.

## Reapply and release

Redelivery of the same revision during the same boot reuses its result. A new
deployment checks the saved profiles and auto-join flags again. The client
reuses a secured profile only when the requested profile and its Android network
ID match the saved evidence. Existing profiles are updated in place. Use
**Reapply settings** on the headset or reboot it to write managed Wi-Fi again.

**No managed settings**, or turning off **Manage** for Wi-Fi, stops Wi-Fi management.
Once the headset saves the release or the updated assignment, it also honors
the change when it reboots offline. Headset Settings shows an error if the
client cannot save the release.
Saved profiles and their auto-join flags stay as they are. To let a network
with auto-join off reconnect automatically, turn auto-connect on for it on the
headset.

## Provisioning

Provision headsets without a Device Owner, and grant these permissions when
installing the client.

```sh
adb shell pm grant com.styly.mdmclient android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.styly.mdmclient android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.styly.mdmclient android.permission.ACCESS_BACKGROUND_LOCATION
adb shell appops set com.styly.mdmclient SYSTEM_ALERT_WINDOW allow
```

Also turn on **Location** in the headset's system settings. On the headset,
**Permissions → Wi-Fi configuration** shows each grant and the Location switch.
**Review Wi-Fi permissions** opens the next setting that needs attention.

Grant `DUMP` as well when MDM must update networks it did not create, such as
the network added by the ADBK Wi-Fi step or in Android Settings. Without it,
updating those networks fails.

```sh
adb shell pm grant com.styly.mdmclient android.permission.DUMP
```

## Supported inputs and credential storage

A package lists 1–4 networks, unique by SSID and security. SSIDs contain 1–32
UTF-8 bytes without control characters, quotes, or backslashes. WPA2/WPA3
Personal prefers WPA3 and allows WPA2 fallback. Passwords contain 1–63
printable ASCII characters; use at least eight to allow WPA2.

The server stores Wi-Fi passwords in plaintext in its registry and backups.
The package editor receives saved passwords to fill in the form. Restrict
server access to trusted devices and operators. Device status reports and
console logs omit passwords.
