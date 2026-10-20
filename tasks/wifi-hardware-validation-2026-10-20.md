# PICO Wi-Fi hardware validation — 2026-10-20

## Outcome

**V3 managed Wi-Fi passed the available single-AP tests on DEV-005.** Explicit
Device Owner provisioning and the approved MDM-known recovery contract resolved
the earlier V2 blockers. Real tests confirmed local SSID/IP settlement without an
MDM route, wrong-password timeout recovery, process recovery and boot
reapplication. Initial V2 failures are preserved below as historical evidence;
they do not describe the final V3 adapter.

Branch: `codex/group-wifi-settings`. The initial V2 report was committed as
`d21dee6`. This follow-up adds explicit Device Owner provisioning, the V3 recovery
contract and corrections found by physical tests. Local commits only, no push.

## Target and preparation

- Device: DEV-005, Device ID `70a1c963-e76e-4afb-9439-aeb21784298b`.
  The ADB skill revalidated the Device ID PNG before device operations.
- PICO 4 Ultra Enterprise / A9210, Android 14 (API 34), PUI 5.15.7.
- The user authorized replacing APKs and adding/deleting Wi-Fi configurations.
  Existing client/guard APKs were backed up privately before replacement.
- Replaced release 0.7.0 with the current 0.7.0-dev debug client (same package
  `com.styly.mdmclient`; different signing key required uninstall). The client
  installed its embedded guard. The original release was not restored.
- After explicit user approval, granted location, all-files and exact-alarm
  access; saved a manual local test endpoint; installed USB reverse forwarding;
  launched the client. These grants do not provide Wi-Fi management authority.
- Local test server: HTTP port 7086, endpoint
  `ws://127.0.0.1:7086/ws/device`, reached through USB reverse forwarding.
- Dedicated group `Wi-Fi hardware test DEV-005` contained only the canonical
  Device ID. The original Documents registry file was not edited. Runtime membership was
  changed for the dedicated test group; the existing `test` group now has one
  member (initially two). Canonical registration added one runtime
  registry entry (290 to 291); the older legacy serial entry remains separate.
- Candidate profile contained the user-provided office and guest networks.
  Credentials and raw diagnostics remain in private temporary files and are
  deliberately excluded from this report and Git.

## Initial ordinary-app results

| Case | Result | Evidence and limits |
|---|---|---|
| Online managed deployment | Failed | Two real commands reached the client. Each was persisted as failed / `PERMISSION_DENIED`, with no active mutation journal or successful baseline. |
| Delete saved profile without Deploy | Passed | Client state and previous delivery record were unchanged. A new unmanaged revision was saved. |
| Explicit unmanaged deployment | Passed | One target received it; `managed_wifi=false`, policy epoch incremented, historical baseline retained (null in this test). |
| Real reboot while unmanaged | Partial | Boot count advanced 106 to 107; unmanaged policy/revision persisted, no manual/boot operation appeared. No successful baseline existed, so suppression of reapplication of a populated baseline was not exercised. |
| Offline deployment | Passed | After reboot and before restoring the USB route, deploy had zero online targets/operations and preserved previous delivery. Reconnection did not create the corresponding managed operation. |
| Failed managed deploy after unmanaged | Passed | After canonical reconnection, another managed command failed preflight. `managed_wifi` stayed false, policy epoch and historical baseline were preserved. |
| Process restart persistence | Passed within scope | Force-stop/start retained unmanaged state and no pending operation. This does not verify interruption recovery during an active Wi-Fi mutation. |
| Final explicit unmanaged deployment | Passed | Saved and explicitly sent unmanaged again; client policy revision matched, no pending manual/boot operation. |
| Current network preserved | Passed within scope | Final diagnostics show office Wi-Fi connected, supplicant completed, and an IPv4 address. The 12 saved-network identity/security entries matched the pre-reboot diagnostics. Dynamic timestamps/reboot counters were not treated as configuration changes. |
| Office/guest switching and SSID/IP success | Blocked | Management preflight prevented configuration changes. |
| Invalid password/no-network 60-second rollback | Blocked | No mutation began, so a timeout/restore did not occur. |
| Same-SSID credential replacement | Blocked | No mutation began. Existing office configuration uses SAE, also outside the current OPEN/WPA_PSK adapter support. |
| Successful managed re-enable and baseline boot correction | Blocked | No successfully applied managed baseline. |
| Active-operation death/reboot, alarm deadline, MDM-independent success, duplicate execution | Not exercised | The management precondition failed; state-machine tests are not physical evidence for these cases. |

## Reboot and test-route observations

USB reverse is host-managed and disappeared across reboot. The client exhausted
its existing bounded connection window before the route was restored. Opening
an already running Settings activity did not renew the window; restarting the
test client was required. This was a test-route issue, not evidence that Wi-Fi
rollback depends on MDM reachability.

Package-scoped all-files/exact-alarm AppOps were reported as `default` after
reboot; Device ID became unavailable and registration was provisional. Restored
the already approved grants at UID scope, restarted the client, and confirmed
canonical online registration. Final UID-level grants read back as `allow`;
later V3 reboots retained canonical identity and alarm access. Location remained
granted. Do not claim that the initial permission setup survived reboot.

## Feasibility review

The provided `pico-settings-summary.md` reports a different prior hardware test
(PUI 5.15.5 / TobService 4.3.28). It confirms PICO APIs can add/connect networks,
but ordinary apps could not delete them; detailed configured-network readback
was unavailable even in its Device Owner test. That reference is supporting
evidence, not a reproduction on this device.

A separate read-only review of the current SDK 4.3.16 API surface found
`pbsConfigWifi` / `connectWifi`, SSID-only `pbsGetConfiguredWifi`, and PICO
auto-connect configuration APIs. It found no demonstrated ordinary-app path to
read all existing credentials, replace every saved configuration, and restore
them completely. Cached service implementation analysis was supplementary;
its identity with this device's firmware was not established.

Owning code: `WifiManagerAdapter.requireManagementAuthority()` enforces the
authority requirement; `restorableSnapshot()` additionally rejects unsupported
or unreadable restoration data. Bypassing the permission check or substituting
a single-network connect call would not satisfy the accepted complete-rollback
contract. No Device Owner provisioning was performed in that initial phase. The
subsequent explicitly authorized Device Owner test is recorded below. No root,
firmware or overlay change was performed.

## V2 Device Owner follow-up

The user subsequently authorized Device Owner provisioning. Added a minimal
`MdmDeviceAdminReceiver` protected by `BIND_DEVICE_ADMIN`, with empty policy
metadata. The installed package was verified `TEST_ONLY` before
`dpm set-device-owner`; both `dpm list-owners` and normal application execution
confirmed ownership. No accounts, prior owner, root or factory reset were needed.

An instrumentation diagnostic returned zero configurations and initially led to
an incorrect inference about normal application access. That inference was
withdrawn. A temporary debug Activity using the normal application context
reported package/op-package `com.styly.mdmclient`, Device Owner true,
24 adapter entries, 11 unreadable PSKs, 12 unsupported-security entries, and
`RESTORE_INFO_MISSING`. These 24 API security views must not be equated to the
12 saved network IDs in dumpsys. The different instrumentation result is not
used as firmware evidence; its exact cause was not established.

Two managed deployments after provisioning failed before mutation with
`RESTORE_INFO_MISSING`, including one after removal of all temporary diagnostic
code. The ordinary permission blocker was resolved; credential restoration was
not. An experimental unconditional empty-list rejection was withdrawn because
normal application evidence did not support it and it would reject genuinely
empty devices. Only the provisioning receiver was retained at this checkpoint.

The user then explicitly approved the MDM-known recovery contract described in
`wifi-mdm-recovery-v3-2026-10-20.md`; completed V3 results are recorded below.
These V2 failures remain evidence, not V3 test outcomes.

## V2 checkpoint state and evidence

- The final V2 readback showed an existing TP5 connection with an IP address.
  The 12 saved network IDs and SSIDs matched the original inventory. Connection
  status and dynamic security-view details changed, so full configuration
  equality is not claimed. Earlier ordinary-app checks ended on STYLY-office.
- The test debug client and guard remain installed. Local manual endpoint and
  USB forwarding remain configured; disconnecting USB removes that server path.
- Server desired profile and client policy are both unmanaged after explicit
  deployment. The local HTTP server remains running on port 7086.
- Device Owner remains registered, separately from unmanaged Wi-Fi policy.
  The final debug APK is test-only and can be deprovisioned before a signing-key
  change. Temporary instrumentation APK/Activity and private probe output were
  removed from the device; no diagnostic code was retained in the source.
- Private evidence and APK backups:
  `/tmp/styly-mdm-wifi-device-test-20261020/private/`.
  Important records: `state-after-first-deploy.json`, `unmanaged-results.json`,
  `offline-result.json`, boot-count/state readbacks, `final-results.json`,
  `wifi-status-final.txt`, `wifi-final.txt`, `appops-final.txt`.
  DO follow-up evidence: `set-owner-result.txt`, `owners-final.txt`,
  `probe-normal-owner-result.txt`, `owner-after-attempt-state.json`,
  `owner-final-results.json`, `wifi-status-owner-final.txt` and
  `wifi-owner-final.txt`.
- Some exploratory script snapshot filenames were overwritten by later reads;
  they are not treated as immutable initial-state evidence. Raw files are
  temporary and are not a committed audit archive.

## Review and checks

- Compared local persistent state with server delivery records for each completed
  case; final network/security entries matched the pre-reboot diagnostics.
- After the receiver addition, Android JVM tests (149) and test-only DevDebug
  APK assembly passed. Tests were not treated as proof of hardware rollback.
- Independent read-only review found no discrepancy with the SDK investigation
  or the four primary state/result records, and no overstated hardware coverage.

## V3 MDM-known recovery results

The operator approved limiting rollback to MDM-held credentials. Registered an
initial recovery profile separately; its registration made no Wi-Fi change and
was not recorded as a successful connection. Unknown original OS configurations
were removed during candidate replacement, as authorized. The office/guest APs
became unavailable; the operator supplied `s-5ghz` instead. Passwords remain
excluded from this report and Git.

| Case | Result | Physical evidence and limits |
|---|---|---|
| Initial recovery registration | Passed | Initial baseline persisted separately; no Wi-Fi, successful baseline, boot history or management-policy change. Explicit re-registration replaced the seed before first managed success. |
| Guest candidate with no AP available | Passed for timeout/settings only | Timed out at 60.6s and restored the registered known office configuration. Neither AP was available, so actual guest connection or fallback connectivity is not claimed. |
| Available SSID + IP, MDM route removed | Passed | Removed USB reverse while the same operation journal remained active. `s-5ghz` obtained an IP and committed success at 3.3s without the MDM server. |
| Same SSID, wrong password | Passed | Failed with `TIMEOUT_ROLLED_BACK` at 60.5s; restored the known successful credentials and actual SSID/IP connection. The successful baseline remained unchanged. |
| Process death during a changed candidate | Passed | Terminated the app process. Opening the client recovered the journal and actual connection at 1.6s; result `UNKNOWN`, rolled back true. The 60-second candidate budget was not renewed. |
| Background-only process recovery | Passed | Terminated an active attempt and started only the foreground service, with no Activity. Restored the known baseline and actual connection at 3.5s. |
| Boot correction without MDM | Passed after repair | Externally removed the saved successful Wi-Fi, then rebooted 109 to 110. Client recreated it, recorded successful boot application and obtained an IP while USB reverse was absent. A subsequent service restart added no boot result. |
| Delete then explicit unmanaged, populated baseline | Passed before reboot | Deletion preserved the client's state and previous server delivery. Explicit send persisted unmanaged policy while retaining the successful baseline and current connection. |
| Unmanaged boot with externally removed Wi-Fi | Passed after USB reconnection | Boot count advanced 110 to 111. Management stayed disabled, successful baseline and boot results were unchanged, claimed generation stayed 110, and the removed Wi-Fi was not recreated. |
| Managed re-enable after unmanaged | Passed | After separately restoring basic connectivity, explicit valid MDM deployment completed fresh SSID/IP settlement at 3.8s and re-enabled management. This case does not demonstrate delivery to a device with no network. |
| Final delete/explicit unmanaged deployment | Passed | Saving unmanaged again preserved client state and previous delivery. Explicit deployment disabled management while retaining the successful baseline and actual s-5ghz/IP connection; server and client policy revision matched. No pending operation remained. |

### Repairs established by hardware and review

- PICO returned multiple security views per saved network ID. Readback now counts
  IDs once, selects the requested security view and removes each unneeded ID once.
- New known-credential profiles use fresh configurations; rollback uses the
  frozen successful/initial MDM profile rather than unreadable old OS keys.
- Independent review found legacy parcel bodies were read as if they had a class
  header. Corrected that format and verified a real-device round trip of the
  adapter's existing marshal/unmarshal path. The temporary probe was removed.
- Reboots 108 and 109 physically recreated Wi-Fi but incorrectly recorded
  `VERIFY_FAILED`: configured-network readback was empty and FINE_LOCATION
  AppOps recorded a rejection. The service had only `connectedDevice` type.
  Added conditional location foreground-service promotion before startup
  recovery; reboot 110 passed without background-location permission. The
  controller remains process-owned, starts once and precedes WebSocket commands.
- Review found the later location-grant callback also needed to refresh service
  type. Added that start request without stopping/recreating the owner. This
  permission-dialog path was code-reviewed, not separately exercised on hardware.

### UI and automated checks

- Actual running page with 291 runtime registry records: desktop 1280px had no
  horizontal overflow. Existing button classes were reused; save, add, recovery
  registration and send controls were 38px high.
- A temporary 375px iframe displayed the complete live console. Navigation,
  form controls and full-width stacked actions fit visually. Its unsaved draft
  was discarded; no browser save or deploy occurred. The temporary page and tab
  were removed. Screenshots are local task artifacts outside Git.
- Full server suite: 461 passed. Wi-Fi/Push console tests: 31 passed. Android
  suite: 162 passed, including deferred startup, duplicate starts and interrupted
  journal recovery. DevDebug test-only APK assembly passed.
- Read-only staged reviews covered the adapter/recovery contract and later
  foreground-service lifecycle. Confirmed findings were fixed before finalizing.

### Current V3 state and remaining limits

The HTTP test server remains on port 7086 with the Documents-derived runtime
registry. Desired Wi-Fi is unmanaged. The test-only Device Owner client/guard
remain installed; their local server path requires USB reverse. After USB
reconnection, actual boot111 readback confirmed unmanaged policy with the
successful baseline retained and no recreation of the externally removed Wi-Fi.

With no OS network, the existing client waits for a default-network callback and
does not initiate the loopback USB server connection. A temporary normal-app
debug Activity restored the supplied s-5ghz connection through the authorized
Device Owner APIs. Its credential input/output and Activity were removed, and
the clean product APK was rebuilt and reinstalled before normal MDM testing.
This restoration was test setup, not proof of MDM delivery without a network.

The subsequent explicit managed deployment succeeded with SSID/IP at 3.8s.
Finally saved and explicitly deployed unmanaged again. Final native readback
confirmed s-5ghz, supplicant completed and an assigned IP. Client policy revision
matched the server unmanaged revision, the successful baseline remained and no
manual/boot operation was pending. USB reverse and canonical online registration
were restored. Original unknown OS networks were not restored.

Private V3 evidence is under the existing `private/v3/` directory: `01-*`,
`02-guest.json`, `03-success.json`, `03-without-server.json`,
`04-wrong-password.json`, `05-process-recovery.json`,
`06-boot-correction-110.json`, `06-service-restart-110.json`,
`07-background-recovery.json`, `log-boot-diagnostic.txt`,
`08-resumed-readback.json`, `08-unmanaged-reboot-resumed.json`,
`09-managed-reenable.json`, `10-final-unmanaged.json` and
`12-rescue-connect.json` and `13-final-readback.json`. The first unmanaged reboot script timed out while USB
was absent; its result is established by the separately recorded resumed
readback, not an invented completed original script. These temporary files are
not a durable committed audit archive.

Two available-AP switching, hardware duplicate-command replay, active-operation
OS reboot, exact-alarm-only cold recovery and content-start ordering remain
unverified. Single-AP success does not prove dual-STA or MLO support. Existing
unknown credentials and unsupported OS configuration details were not restored.
