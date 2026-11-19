# Wi-Fi in configuration packages

Wi-Fi settings support PICO 4 Ultra Enterprise on Android 13 or later on the
managed LAN. Package deployment and the client implementation are described in
[configuration packages](DEVELOPMENT.md#configuration-packages).

## Operator workflow

Open **Manage Groups → Configuration Packages**, choose a package, then expand
**Network & connectivity**. Select **Manage** on **Wi-Fi networks** and add
Open or WPA2/WPA3 Personal networks. A blank password field keeps the saved key
for the same SSID and security. Choose **Save & deploy**. The confirmation
includes all assigned groups and members. Offline devices receive the latest
revision when they connect. Group **Settings** shows Wi-Fi readiness and the
last package result.
Turning off **Manage** keeps the previously saved list and keys. Draft network
rows are ignored while deploying Wi-Fi as unmanaged. Returning **Manage** to
its saved off state leaves no Wi-Fi change to save.

## Modes

**Prioritize package Wi-Fi networks** is on by default.

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

The Wi-Fi line under **Results at apply time** shows one of these outcomes.

| Console | Meaning | Action |
|---|---|---|
| Applied | Connected to the first listed network. | None. |
| Applied · fallback | Connected to a lower listed network after earlier ones failed. | Check the earlier networks. |
| Saved. Android selects the network. | Android selection: profiles and auto-join flags were read back. | None. |
| Saved. Verify after reboot. | A new or previously unknown password was saved for the connected network. | Restart the headset to confirm it. |
| Failed · unreachable | No listed network connected within about a minute. | Reconnect the headset if needed, then Retry or deploy corrected settings. |
| Failed · update failed, verify failed | A profile or auto-join flag could not be written or read back. Shown even if another network connected. | Check provisioning, then Retry. |
| Failed · permission denied | A required grant is missing. | See [Provisioning](#provisioning). |

Android does not let MDM read a saved password, and a reconnection during the
same boot can reuse earlier authentication. A changed password is therefore
confirmed only after the next reboot. Removing and re-adding that network,
retrying a partial save from a fallback network, or restarting the MDM client
does not confirm the password.

After a reboot, the headset reapplies the package. If the access point comes up
later than the headset and all networks were saved, an `unreachable` result
changes to applied once the headset connects to a listed network.

## Reconnection and automatic return

These apply to priority mode.

- After a disconnection, Android reconnects to the first listed network. With
  more than one listed network, the client waits about 10 seconds and then
  tries the networks in order. It keeps retrying with a growing delay of up to
  about a minute. While Wi-Fi is off, it waits for the radio to become available.
- With automatic return on, a headset on a lower network checks Wi-Fi scans for
  a higher one, first within about 30 seconds and then about every five
  minutes. Any visible higher network triggers a switch, regardless of signal
  strength. A switch briefly interrupts communication. If it fails, the client
  reconnects to the previous network. After each attempt the client waits five
  minutes, doubling up to 30 minutes after consecutive failures. Scanning pauses
  during this wait.
- Detection depends on Android scan results. Sleep, throttling, and radio
  conditions can delay it. The console shows the monitor state separately from
  the package result. If the client cannot register to receive scan results, it
  tries again on the next Wi-Fi readiness or connectivity check.
- Automatic return does not support hidden SSIDs. Turn it off to use hidden
  networks.

## Reapply and release

A same-boot deployment whose Wi-Fi setting matches a successful result reuses
it. To write managed Wi-Fi again, use **Reapply settings** on the headset or reboot it.

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
Editor responses, device reports, and console logs omit them. Device test
results are in the [PICO Wi-Fi field notes](PICO_WIFI_FIELD_NOTES.md).
