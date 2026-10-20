# Wi-Fi auto-join validation — 2026-10-20

## Scope

- Branch `codex/group-wifi-settings`; pre-feature baseline `7df0492`.
  Local commit only; no push. [WIFI_SETTINGS.md](../docs/WIFI_SETTINGS.md)
  defines the current behavior. This report supersedes earlier replacement,
  recovery-registration and prototype-migration tests.
- DEV-005, Device ID `70a1c963-e76e-4afb-9439-aeb21784298b`:
  PICO 4 Ultra Enterprise, PUI 5.15.7, Android 14; ADB model A9210,
  application model A94U0. TobService 4.3.30; client SDK 4.3.16.
  The ADB skill checks the Device ID PNG before each operation.
- The operator authorized APK replacement, Wi-Fi changes, Device Owner and
  test permissions. No root, factory reset or firmware modification occurred.
  The test-only DevDebug client and guard remain installed as Device Owner.
- HTTP server: port 7086, using a private runtime copy of the Documents TeNQ
  registry. The original Documents file is unchanged. The dedicated hardware
  group contains only DEV-005. USB reverse provides the test MDM route.
- Only s-5ghz was available. Existing settings were retained throughout these
  auto-join tests. Two deliberately unreachable test networks remain saved OFF.
  Credentials and detailed evidence are private under
  `/tmp/styly-wifi-autojoin-validation/private/`, excluded from Git.

## Current physical results

| Case | Observed result |
|---|---|
| Initial managed deployment | No recovery seed required. Local SSID/IP success at 3.6s; existing network IDs retained. |
| Unreachable new SSID | Timeout/recovery at 60.7s; original auto-join flags restored, new candidate retained OFF, actual original SSID/IP restored. |
| Same SSID with wrong password | Timeout/recovery at 60.3s; MDM-known previous password and actual SSID/IP restored. Successful baseline unchanged. |
| Process death during mutation | Restart recovered the durable snapshot at 3.1s, restored flags and actual SSID/IP; no renewed 60s candidate budget. |
| Managed OS boot without MDM route | Before boot, baseline auto-join OFF and test candidate ON. Boot re-enabled baseline, disabled candidates and obtained an IP. Network identities retained despite OS ID reassignment (boot 114). |
| Service restart in the same OS boot | A manually enabled test candidate remained enabled; boot policy was not applied twice. |
| Delete without Deploy | Client journal, management flag and OS Wi-Fi flags stayed unchanged. |
| Explicit unmanaged Deploy | Disabled boot management while preserving current flags and the successful baseline. |
| Unmanaged OS boot | A manually enabled test candidate stayed enabled (boot 115); no policy reapplication. Baseline retained, no pending operation. |

Final readback: s-5ghz connected with an IP, baseline auto-join ON, both test
candidates OFF, server/client unmanaged and no pending operation. Final APK
and server were restarted and MDM registration confirmed online. The temporary
ADB diagnostic JAR was removed.

The first timeout test exposed an actual permission failure: PUI rejected
`getConfiguredNetworks()` after disconnecting Wi-Fi despite precise location,
Location enabled and a location foreground service. Adding and granting
`ACCESS_BACKGROUND_LOCATION` allowed recovery with the app in the background;
the successful timeout, wrong-password and process-recovery tests followed.
The app now rejects managed mutation without this permission and directs the
operator to allow precise location all the time. A blocked recovery requires a
headset restart after fixing permissions; the UI states this explicitly.
Unknown old passwords remain unreadable and cannot be restored after overwrite.

## Review and automated verification

Read-only architecture/build, lifecycle/testing and code-health reviewers were
used at implementation checkpoints. The primary agent verified and fixed:

- Restored `membershipConflict`, still used by the existing Devices editor.
- Removed stale cached group-revision comparisons that blocked a fresh profile.
- Discarded the actual dirty model on editor exit, including Devices-tab changes.
- Removed unused independent profile-rename/delete save branches; group and
  encrypted profiles have one registry-save owner.
- Corrected the deletion warning: unmanaged Deploy also preserves OS flags.
- Removed leftover TLS server/discovery setup, unpublished-format migrations,
  initial baseline registration, per-device send history and detailed boot state.
- Kept mutation ownership, fixed deadlines, local fresh-network proof, durable
  rollback fences and operation-specific alarms. Production selection tests cover
  network-ID reassignment, duplicate security views, missing/ambiguous originals,
  and candidates added before an ID could be journalled.

Checks: full server 456 passed; Wi-Fi/Push console 24 passed; Android 166 passed,
DevDebug test-only APK assembled and installed. `git diff --check`, SVG and
embedded editable XML validation passed. Existing aiohttp AppKey warnings remain.

The actual 291-record console was checked at desktop and 375px widths. Existing
button styles were retained, fields stack without horizontal overflow, password
values are not rendered by GET, and an unsaved edit disappears after leaving and
returning. Browser warning/error log was empty during the check.

## Limits

No physical proof is claimed for two available APs, simultaneous dual-SSID
connection, concurrent unmanaged delivery after the MDM socket closes, an OS
reboot during an active mutation, exact-alarm-only cold recovery, permission-dialog
interaction, power cuts, or strict before-content-start ordering. Lifecycle and
policy-epoch races have JVM coverage. Unknown-credential overwrite is an accepted
restoration limit. Server registry replacement is atomic but was not tested
against physical power loss. Prototype compatibility is intentionally absent.


Compared with pre-feature `7df0492`, production additions decreased from 4530 to
3526 lines (about 22%). Including tests/docs and removed base lines, the feature
changed-line total decreased from 8028 to approximately 5860.
Required rollback/lifecycle ownership remains; this is not a 50% reduction.
