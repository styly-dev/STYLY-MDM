# Wi-Fi priority hardware investigation — 2026-11-10

## Preflight

- Target: DEV-005, PICO 4 Ultra Enterprise, Android 14 / PUI 5.15.7, connected by USB ADB and identified from its on-device Device ID.
- The installed development client is the Device Owner. Precise and background location are granted, Location is enabled, and the exact-alarm app operation is allowed.
- The device initially used a saved 6 GHz WPA3-SAE network with a usable IP. A saved 5 GHz WPA2 network and the 6 GHz network were both present in scan results. No password was read or changed.
- The first shell scan request returned successfully, but the immediately read scan results were more than three minutes old. A later read showed newer observations; the request's return value alone does not prove the results were refreshed.
- The current package supports Open and WPA/WPA2 Personal inputs. The 6 GHz WPA3 connection can exercise saved-network platform behavior, but does not by itself validate package deployment of that network.

## App-context scan

With a temporary, test-only build signed by the same key as the installed app,
the foreground probe reported the MDM package and operation package, its own
UID, and Device Owner status. It read the three saved networks and their
auto-join flags without reading or rewriting credentials. Both 5 GHz and 6 GHz
networks were initially auto-join enabled; the original 6 GHz connection had a
usable IP.

An app-requested scan returned `true` and produced an updated-results broadcast
5.9 seconds later. Both target networks were present in that result, with
reported ages of 2.843 and 0.101 seconds. This demonstrates foreground
detection for this device and run; it does not establish a background or
screen-off detection latency.

## First saved-network switch attempt

The app requested a switch from the original 6 GHz network to the saved 5 GHz
network. `enableNetwork(id, true)` returned `true`, but the target never
obtained an IP. Device Wi-Fi diagnostics reported authentication failure with
`ERROR_AUTH_FAILURE_WRONG_PSWD`; the OS then marked the 5 GHz candidate
`NETWORK_SELECTION_DISABLED_BY_WRONG_PASSWORD`. The device reconnected to
the original 6 GHz network and the explicit restore request also observed a
usable IP there. All three auto-join flags matched the pre-test readback.

This is a verified failed target connection and a verified return to the
original SSID, not proof that switching to a working second network succeeds.
After the user supplied the current credential, it was entered through the
device's masked Wi-Fi settings field without recording the value. The device
connected to the 5 GHz network and obtained a usable IP.

## App-context detection and switch

While connected to 5 GHz, the app requested a scan and received an updated
results broadcast after 4.2 seconds. The higher 6 GHz candidate was present
with an age of 0.201 seconds. The Device Owner app then requested 5 GHz →
6 GHz → 5 GHz using existing saved-network IDs. Both requested transitions
were observed on the target SSID with a usable IP; all saved-network
auto-join flags matched the pre-transition readback.

This establishes foreground detection and the two-direction saved-network
switch for this device and run. It does not yet establish automatic monitoring,
hidden-network detection, or a full package deployment.

## One screen-off scan

The headset reported `mWakefulness=Asleep` both before and immediately after
the scan request. With the probe activity still the top activity, one requested
scan returned `true` and an updated-results broadcast arrived after 4.9 seconds.
Both candidate SSIDs were present with result ages under one second. The device
was then woken and reported `mWakefulness=Awake`.

This is one screen-off observation with the diagnostic activity at the top;
it does not prove a background service can scan at a given interval, overcome
throttling, detect hidden SSIDs, or recover after process death.

## Cleanup after the preliminary probe

After the probe, the device was returned to the original 6 GHz SSID with a
usable IP. The original application APK was reinstalled without uninstalling
the Device Owner. Its device-side SHA-256 matched the pre-probe backup,
Device Owner status remained present, and the test-only activity was absent.
The test's UI dump was removed. The user-requested correction to the saved
5 GHz credential remains on the device. The package replacement stopped the
app process, so the original Settings activity was started again; the original
MDM process and its foreground service were then observed running. Server
registration was not established by this process/service check.

For later isolated package testing, a read-only check found no pending Wi-Fi
operation and no active Push/Sync job. The headset does have a previously
applied managed Wi-Fi state, so any test must preserve and restore that state
instead of clearing the app's private data.

The user supplied a second WPA2 network for the package test. Its credential
is not recorded in this report. The isolated test server must bind only to
loopback, use a private temporary registry, and serve only DEV-005 through a
temporary USB reverse tunnel. Such a tunnel is evidence for policy delivery,
not for Wi-Fi discovery or reachability. Restore the original application,
device-side policy state, connection and original auto-join flags after tests.

## Outstanding

- Verify long-running background detection and hidden SSID behavior, if the available test networks permit it.
- Test app-level policy behavior and record the remaining device and compatibility limits.

## Package-test preflight and PICO security views

The first attempt to capture the original system Wi-Fi configuration stopped
before policy delivery or Wi-Fi mutation. PUI returned multiple configured
network views with the same network ID: each of the three saved networks had
both `SAE` and `WPA_PSK` views. The diagnostic's original one-view-per-ID
check rejected this as `duplicate_network_id`. The 2.4 GHz test network had
not been saved at this point.

The isolated server was stopped, the three application files were restored
and byte-verified, and the owned USB reverse mapping was removed. The original
APK was reinstalled and its checksum, the original 6 GHz Wi-Fi connection,
Device Owner status, and the MDM foreground service were checked. No test
credential file or diagnostic recovery snapshot remained on the device.
Server registration was not verified by these checks. A subsequent attempt
must group compatible security views by network ID and capture a fresh app
baseline before delivering the test package.

## Package failure and WPA2/WPA3 probe

The next isolated package test registered DEV-005 and delivered the two-network
policy. The client reported `UPDATE_FAILED` followed by `RESTORE_FAILED` and
left a fenced recovery operation. The original 6 GHz connection remained usable.
The test assignment was released, then the diagnostic restored the saved
5 GHz configuration and verified an actual connection to it. It returned to the
original 6 GHz SSID, verified all three original auto-join flags and security
views, and removed the newly created 2.4 GHz test network. The original app
files and APK were restored; Device Owner and the MDM service were observed.
The app wrote its normal update-journal entry on restart. No server registration
was verified for the restored original app.

A separate, bounded app-context probe reproduced the failing update directly.
The device reported WPA3 SAE support. The 2.4 GHz AP advertised both PSK and
SAE, and adding a PSK configuration produced both views under one network ID.
The diagnostic connected to it with a usable IP and reported Android security
type `4` (`SECURITY_TYPE_SAE`). A newly constructed, partial PSK configuration
targeting the existing 5 GHz network then returned `-1` from `updateNetwork`.
Restoring a copy of the original 5 GHz configuration with its known credential
succeeded, including a verified 5 GHz connection, followed by return to 6 GHz
and the original auto-join flags. The test network, private diagnostic files,
test server registry and temporary application-state backup were removed after
verification.

This confirms the partial-update failure and one successful transition-mode SAE
connection on the tested build. It does not yet prove the product's corrected
package flow, automatic return in the background, short-password SAE behavior,
or WPA2 fallback on a non-SAE device.

## First corrected package test

After the Android host suite passed, DEV-005's app state was backed up and the
same-signature, same-version diagnostic APK was installed. The server was bound
only to loopback, with a private registry and a USB reverse tunnel; the test
package contained the visible 2.4 GHz and 5 GHz networks in that order.
The published Wi-Fi result was `applied`, and Android's connection history
showed the first candidate connected. Later observations showed the second
candidate connected; device history showed the system moving from the first to
the second network. These observations do not establish why the system moved.

With auto-return enabled, another published revision was `applied`. A direct
read observed the first candidate, a usable IPv4 address, and Android security
type `4` (SAE). The diagnostic manually selected the second candidate; after
the probe activity was moved to the background, the device returned to the
first candidate with SAE and a usable IP. With auto-return disabled, a manual
selection of the second candidate remained connected for the observed period,
and no new app operation appeared. This is a short background observation,
not proof of a five-minute interval or operation throughout device sleep.

The first wrong-password test unexpectedly connected to the preferred network;
the published password had been changed. Authentication caching is a possible
explanation, not a confirmed cause. The shell user could not flush the PMKSA
cache. The test-created preferred network was removed while connected to the
second candidate, then the wrong-password policy was published again. The
device reported the preferred network disabled for wrong password, connected to
the second candidate using PSK and a usable IP, and reported the package as
applied. No original saved network was deleted.

For an all-candidates-unreachable trial, two uniquely named, previously absent
test SSIDs were published. The device reconnected to another saved network but
reported `RESTORE_UNCONFIRMED`; the previous SSID could not be confirmed and no
retry journal remained. This is a **failed product recovery test**, not a
verified return to the original SSID. The USB tunnel proves test policy
delivery, not reachability of the MDM server over Wi-Fi.

The test assignment was released, only the test-created SSIDs were removed,
and the diagnostic restored and verified the original 5 GHz credential, the
original 6 GHz connection with IP, and all three original auto-join states.
The original APK, private app state, Device Owner and MDM service were checked;
the temporary registry, credentials, USB reverse and backups were removed.
Server registration of the restored original app was not verified.

## Read-only investigation of the recovery failure

On DEV-005, a diagnostic created the same local connectivity observation used
by the app without changing networks. In five repeated samples, the immediate
snapshot had a usable IP but no SSID; by 250 ms all five had the SSID and
security. A prior single run already had the information immediately. This
confirms a timing race in treating the first snapshot as definitive. The app
must wait for a same-network observation before mutating Wi-Fi, or stop safely
before mutation if the previous connection cannot be identified. The diagnostic
was removed, and the original APK, Wi-Fi, Device Owner, service and private
state were checked again.

## Recovery retest after the observation fix

The client was changed to wait up to two seconds for a single usable Wi-Fi
observation with an identifiable, uniquely saved SSID and security before
changing settings. On the corrected diagnostic APK, the isolated two-network
package was `applied`. Immediately before the failure trial, the device was on
the first candidate with a usable IP. After publishing two previously absent
SSIDs, it disconnected and then returned to that same first candidate with SAE
and a usable IP. The package result was `failed`, reason `UNREACHABLE`, with
`rolled_back=true`; the device's latest Wi-Fi result agreed.

The temporary assignment and both test-only SSIDs were removed. The diagnostic
verified the 5 GHz saved credential by connecting, restored the original
6 GHz connection and IP, checked all three original auto-join states, and
removed the newly added 2.4 GHz network. The original APK, private app state,
Device Owner and MDM service were verified, and the test registry, credential
file, USB reverse and app-state backup were removed. Registration to the
original server was not verified.

## Automatic-return retest after the observation fix

The post-observation-fix diagnostic build applied the ordered two-network package with
automatic return enabled. With the probe activity in the background, the second
candidate was selected manually and observed with PSK and a usable IP. The
device then returned to the first candidate with SAE and a usable IP. A new,
successful Wi-Fi operation was recorded by the app. This demonstrates the
switch after the observation fix, but not the five-minute periodic schedule.

The isolated package was released and Wi-Fi management was observed as
disabled. The diagnostic restored the original 5 GHz credential and verified a
connection, restored the original 6 GHz connection and IP, verified all three
saved auto-join states, and removed the test-created 2.4 GHz network. The
diagnostic snapshots and credential files were deleted. The original APK,
private app files, Device Owner, MDM service, Wi-Fi state and absence of test
networks were verified; the private test registry, backup and USB reverse were
removed. Registration to the original server was not verified.

These device tests do not prove a five-minute background schedule, retry after
the original AP becomes unavailable, or connection to a WPA3-only AP with a
1–7-character password. Those paths have host test coverage where applicable.

## Retry delay after automatic return

Review after device cleanup found that a failed automatic-return attempt could
restart scanning immediately after recovery and repeatedly interrupt the
restored connection. A host test reproduced this before the fix. The controller
now waits five minutes after an automatic-return operation settles before
starting another; the wait also prevents immediate retries of a failed higher
candidate after a partial return. Monitoring continues, a new package revision
is independent of the wait, and an app process restart clears it. Host tests
cover the failure, partial-return, observation-failure, telemetry, deadline and
new-revision cases. This final retry-delay change was not installed on DEV-005.
