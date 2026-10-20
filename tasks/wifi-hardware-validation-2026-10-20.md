# Wi-Fi hardware validation and review — 2026-10-20

## Scope and final device

- Branch `codex/group-wifi-settings`, based on pre-feature `7df0492`. Local
  commits only; no push. The current behavior is defined in
  [WIFI_SETTINGS.md](../docs/WIFI_SETTINGS.md).
- DEV-005: Device ID `70a1c963-e76e-4afb-9439-aeb21784298b`, PICO 4 Ultra
  Enterprise / A9210, PUI 5.15.7, Android 14. The ADB skill rechecks the Device ID
  PNG before operations; USB serial/IP identify transport only.
- The operator authorized replacing APKs, changing all saved Wi-Fi settings,
  grants for location/storage/alarms, and explicit Device Owner provisioning.
  No root, factory reset, account removal, firmware or overlay change occurred.
- Test-only 0.7.0-dev client and embedded guard remain installed as Device Owner.
  Original APK backups and password-bearing evidence are private under
  `/tmp/styly-mdm-wifi-device-test-20261020/private/`, excluded from Git.
- HTTP test server remains on 7086; client uses `ws://127.0.0.1:7086/ws/device`
  through USB reverse. The Documents registry file was unchanged. Runtime
  canonical registration increased 290 to291 records; target membership moved
  into its dedicated one-device group, leaving the old `test` group with one.
- At the completed pre-review checkpoint, s-5ghz had an IP, MDM was online and
  server/client policy was explicitly unmanaged with a retained successful
  baseline and no pending operation. Temporary restoration Activities/probes
  and credential input/output were removed.

## Physical outcomes

| Case | Observed result |
|---|---|
| Initial recovery registration | No Wi-Fi/policy/boot change; separate seed retained. Re-registration replaced it only before first managed success. |
| SSID/IP success without MDM route | Removed USB reverse while the operation was active. s-5ghz committed locally at 3.3s. |
| Same SSID, wrong password | Timeout at 60.5s; known successful credentials and actual connection restored. Baseline unchanged. |
| No available candidate AP | Timeout at 60.6s; registered fallback configuration restored. Fallback AP was also unavailable, so connectivity was not claimed. |
| Process death | Pending journal restored conservatively at 1.6s; UNKNOWN/rolled_back=true, no renewed candidate budget. |
| Background-only restart | Starting only the service restored the baseline and actual connection at 3.5s. |
| Managed boot without MDM | Removed the successful OS network, rebooted109 to 110. Network recreated, boot result successful, IP acquired without USB route. Service restart did not repeat correction. |
| Delete without send | Client state and previous delivery remained unchanged. |
| Explicit unmanaged, populated baseline | Policy disabled; current Wi-Fi and successful baseline retained. |
| Unmanaged boot | Removed OS network, rebooted110 to 111. After USB reconnection, network remained absent, policy false, claimed generation110 and boot results unchanged. |
| Managed re-enable | Separately restored connectivity, then explicit MDM deployment succeeded at 3.8s and re-enabled management. |
| Final unmanaged deployment | Delete again preserved state/delivery; explicit send aligned server/client revision and retained s-5ghz/IP. No pending work. |
| Offline targets | Excluded from the send; later reconnect did not replay it. |

The ordinary app initially failed PERMISSION_DENIED. Device Owner enabled
writes but did not reveal old PSKs, so the operator approved MDM-known recovery
instead of full OS restoration. Original unknown networks were removed and were
not restored. Office/guest APs became unavailable; the operator supplied s-5ghz.
Two available-AP switching and dual-STA/MLO are not established by these results.

Boot testing found empty configured-network readback despite successful writes,
with location AppOps rejection. Location foreground-service promotion before
recovery fixed the actual boot test; no background-location grant was added.
An instrumentation probe's empty list differed from normal app execution and
was not treated as firmware evidence. PICO exposes multiple security views per
network ID; adapter readback counts IDs and checks the requested view.

With no OS network, the existing client waits for network availability before
initiating its loopback USB connection. A temporary normal-app Activity restored
basic connectivity before the managed re-enable test. This was setup, not proof
of MDM delivery to a networkless device. The clean product APK was restored.

## Whole-feature review and simplification

Read-only `review_arch_build`, `review_testing` and `review_code_health` reviewed
`7df0492...b063e28`; the primary agent verified and fixed accepted findings.

- P2, `WifiSettingsController.finishManualFailure`: older rollback completion
  could cancel the next operation's global alarm.
  Operation-specific alarm identity/cancellation preserves the newer deadline.
- P2, `appendWifiOperationReceipt`: receipt retention alternated between
  unbounded growth and500 entries.
  One bounded policy now owns deduplication across every command path.
- P2, `_recover_wifi_group_mutation`: group recovery removed its journal before
  restored directory entries were
  durable. Destination directories now sync before journal removal; failure
  retains the recovery marker.
- Removed unused server-ACK fields/state machine, OS snapshot/Parcel/priority
  restoration, duplicate client restoration execution and repeated API readback.
  Recovery represents known profiles only. Current client schema 4 is read into 5;
  earlier unpublished prototype formats are rejected.
- Server delivery has one latest durable record per device. Removed duplicate
  operation history, pruning and synchronization. Current server format 2 reads
  into 3, preserving profiles/latest deliveries and interrupted-send uncertainty.
- P3, `buildProfilePayload`: console duplicate checks now use SSID/security
  identity, matching the server and client instead of rejecting valid shared SSIDs.
- Consolidated the operating contract into one guide and this validation report;
  removed three superseded task specifications. Crash-safe paired group updates,
  mutation fences and fresh-network checks remain because they protect verified
  behavior, not speculative compatibility.

Automated post-review checks: full server 464 passed, Wi-Fi/Push console 32 passed,
Android 168 passed and test-only DevDebug APK assembled. SVG and embedded editable
XML are valid; unchanged diagram elements retain their original formatting.
Actual server format 2 to 3 migration preserved profiles/latest delivery. Actual
client schema 4 to 5 migration preserved profiles, policy and boot history, then
normal managed SSID/IP success completed at 3.9s. Actual boot 111 to 112 recreated the removed successful configuration, acquired
an IP without MDM and did not repeat on service restart. A timed wrong-password
attempt rolled back at 60.8s and recovered the actual SSID/IP connection.
After reconnection, profile deletion preserved client/delivery state; explicit
unmanaged deployment again retained the connected Wi-Fi and successful baseline.
Compared with the pre-feature base, changed lines decreased from 9056 to 8028;
production code decreased by 396 net lines, and the whole feature by 896 net lines.
These counts include retained meaningful regression tests, not removal of checks.
Existing aiohttp application-key warnings remain. No external AI was used.

## Evidence and limits

Private evidence includes v3 records 01–14 (initial registration, local success,
timeout/process/boot recovery, resumed unmanaged readback and final cleanup).
Review evidence under `private/review/` includes actual server/client format
migration, managed success, the active exact-alarm registration and final state.
Boot112 and timed-repeat records are under `private/v3/`. The attempted
concurrent unmanaged test could not send once Wi-Fi closed the MDM socket;
this path is covered by JVM policy-epoch tests, not claimed as a physical pass.
The first unmanaged reboot script timed out while USB was absent; its result is
supported by the resumed boot 111 readback. Temporary files are not a permanent
committed audit archive. No passwords appear in this report or Git.

The actual page was checked with 291 runtime records at 1280px and in a 375px frame
of the complete live console. Existing 38px action controls and stacked narrow
layout fit; no desktop horizontal overflow. Unsaved layout drafts were discarded.

Unverified hardware cases: concurrent unmanaged delivery during an active
connection attempt (its MDM socket closes with Wi-Fi), two available APs, duplicate-command replay,
active-operation OS reboot, exact-alarm-only cold recovery, later location-grant
permission dialog, and strict before-content-start ordering. Group recovery
fsync ordering is covered by injected-failure tests, not physical power cuts.
