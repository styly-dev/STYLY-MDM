# PICO Wi-Fi hardware validation — 2026-10-20

## Outcome

**Managed Wi-Fi application failed on the real device.** The current Android
adapter requires Device Owner or `NETWORK_SETTINGS` authority, neither of which
the ordinary test installation has. Both managed deployment attempts that reached
the client terminated with `PERMISSION_DENIED` before changing Wi-Fi. No actual
connection-success or rollback claim can be made from the automated test suite
or a server delivery status of `sent`.

Branch: `codex/group-wifi-settings`. Product code was not changed during these
tests. This report and the documentation correction are committed locally only;
no push is authorized.

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
  Device ID. The existing two-member `test` group and original Documents
  registry file were not edited. Canonical registration added one runtime
  registry entry (290 to 291); the older legacy serial entry remains separate.
- Candidate profile contained the user-provided office and guest networks.
  Credentials and raw diagnostics remain in private temporary files and are
  deliberately excluded from this report and Git.

## Results

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
their persistence across another reboot was not tested. Location remained
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
contract. No Device Owner provisioning, root, firmware or overlay change was
performed. These require a separate design decision; Device Owner is not yet
proven sufficient for credential restoration.

## Final state and evidence

- Device remains connected to STYLY-office with an IP address; existing saved
  network identity/security entries were preserved.
- The test debug client and guard remain installed. Local manual endpoint and
  USB forwarding remain configured; disconnecting USB removes that server path.
- Server desired profile and client policy are both unmanaged after explicit
  deployment. The local HTTP server remains running on port 7086.
- Private evidence and APK backups:
  `/tmp/styly-mdm-wifi-device-test-20261020/private/`.
  Important records: `state-after-first-deploy.json`, `unmanaged-results.json`,
  `offline-result.json`, boot-count/state readbacks, `final-results.json`,
  `wifi-status-final.txt`, `wifi-final.txt`, `appops-final.txt`.
- Some exploratory script snapshot filenames were overwritten by later reads;
  they are not treated as immutable initial-state evidence. Raw files are
  temporary and are not a committed audit archive.

## Review and checks

- Compared local persistent state with server delivery records for each completed
  case; final network/security entries matched the pre-reboot diagnostics.
- No product code changed, so automated suites were not rerun to imply hardware
  coverage. Documentation links and `git diff --check` are checked separately.
- Independent read-only review found no discrepancy with the SDK investigation
  or the four primary state/result records, and no overstated hardware coverage.
