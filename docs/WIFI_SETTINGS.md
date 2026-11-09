# Wi-Fi in configuration packages

Wi-Fi settings support PICO 4 Ultra Enterprise on the managed LAN. See
[configuration packages](DEVELOPMENT.md#configuration-packages) for draft,
publication, assignment, and delivery behavior.

## Operator workflow

Open **Manage Groups → Configuration Packages**, choose a package, then
expand **Network & connectivity**. Select **Manage** on **Wi-Fi networks**
and add the Open or WPA/WPA2 Personal networks. A blank password field
keeps the saved key for the same SSID and security. Save the draft and
choose **Publish**. The confirmation includes all assigned groups and
members. Offline devices receive the latest revision when they connect.
The group **Settings** tab shows Wi-Fi readiness and the last result.

Managed Wi-Fi adds or updates the listed networks and enables their automatic
connection. Other saved networks remain on the device with auto-join disabled.
Clearing **Manage** and publishing leaves the device's existing networks and
auto-join settings in place. Removing the package assignment has the same
effect. The draft retains the unchecked setting's values.

## Provisioning and recovery

Managed Wi-Fi requires Device Owner, precise location allowed all the time with
Location enabled, and exact-alarm special access. Provision Device Owner
explicitly with `dpm set-device-owner`. Foreground-service location by itself
did not preserve configured-network access after disconnection on the tested
PUI build. The Wi-Fi worker starts recovery after foreground location promotion.

Before mutation, the client records the saved network IDs, SSID and security
identities, auto-join flags, and any previous credentials known to MDM. The
first mutation starts a fixed 60-second elapsed-time budget with an exact alarm
and an in-process watchdog. The client must observe a fresh connection to a
candidate SSID with a Wi-Fi IP. This test can succeed on an isolated network
where the MDM server is unreachable. The report remains local until the client
can reconnect.

On failure, the client restores the captured auto-join flags and MDM-known
credentials. Added candidates remain saved with auto-join disabled. **Unknown
overwritten passwords cannot be restored.** A restored configuration may still
fail to connect if its AP or credentials changed. Reapplying a managed policy
writes its credentials because Android cannot read them back for comparison.

Interrupted Wi-Fi work recovers from the private `wifi-settings.json` journal
before another Wi-Fi mutation. Package application waits for live recovery
to settle. After a failed restoration, Retry or a new managed Wi-Fi revision
attempts to complete that restoration before writing new networks. Clearing
Wi-Fi management can release a settled recovery journal without changing
device values.
An unreadable journal stays blocked. Content startup waits for interrupted
Wi-Fi recovery for at most 60 seconds. The Wi-Fi journal preserves recovery
data and known credentials across upgrades. The client applies the assigned
published version after connecting in each boot. A failed or interrupted
version waits for Retry or a new publication.

The PICO auto-join API uses TobService's `allow_wifi_auto_join` message. Read
the actual `WifiConfiguration.allowAutojoin` flag. A partial Wi-Fi configuration
can overwrite a network's security settings.

## Supported inputs and credential storage

Policies accept 1–32 networks, unique by SSID and security. SSIDs must contain
1–32 UTF-8 bytes without control characters, quotes, or backslashes. WPA/WPA2
PSKs accept 8–63 printable ASCII characters or 64 hex digits. Hidden networks
are supported.

The server keeps plaintext Wi-Fi credentials in the private package
registry and backups. HTTP editor responses, device reports, and console logs
omit passwords. The policy sent to an eligible member includes the credentials
required to configure its networks.
