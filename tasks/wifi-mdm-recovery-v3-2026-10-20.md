# MDM-owned Wi-Fi recovery revision — 2026-10-20

## User-approved contract

This revision supersedes full restoration of all pre-existing OS Wi-Fi settings.
The user explicitly approved recovery using settings whose credentials MDM knows.
Restoration uses the last device-local successful MDM profile. Before the first
success, an administrator must explicitly register an initial recovery profile.
Unknown pre-existing networks may be removed and are not restored after failure.
Device Owner provisioning on DEV-005 is explicitly authorized and completed.

## State and operation boundaries

- Keep `lastApplied` exclusively for profiles that completed fresh SSID plus IP
  verification on the device. Never turn baseline registration into success.
- Persist `initialRecoveryProfile` separately. Registration writes no Wi-Fi and
  changes neither boot management nor `lastApplied`. A prior successful profile
  always takes precedence. Reject registration during active manual/boot work.
- Without either baseline, reject managed application before mutation. Freeze
  the selected known credentials in the durable operation before any write.
- Preserve the fixed 60-second deadline, one-time local success, local alarms,
  process/reboot recovery and once-per-boot successful-profile reapplication.
- Unmanaged deployment retains both historical baselines and OS Wi-Fi. An older
  attempt must not re-enable management after a newer unmanaged policy.
- Initial recovery registration is not proof those credentials connect. Its UI
  confirmation must say so and explain the reduced restoration scope.

## Compatibility

- Advertise `wifi_settings_v3`. Send only to compatible online targets.
- Managed/recovery commands require `recovery_scope: mdm_profiles`; a capability
  advertisement alone does not prevent an old server sending old semantics.
- Use `EXECUTE_WIFI_APPLY` mode `recovery` for initial registration; managed and
  unmanaged retain their names. Group profile storage remains managed/unmanaged.
- Add explicit POST `/api/wifi/groups/{name}/recovery` with expected revision.
  Reuse frozen online targets, delivery persistence and per-device send ordering.
  The server finishes after dispatch, without application-result tracking.
- Local schema4 retains schema1–3 state and pending recovery journals. New
  operation recovery entries contain known credentials without OS parcels;
  legacy recovery entries retain their old parcels and restoration path.

## PICO adapter boundary

- Normal application execution after DO provisioning read 24 API entries while
  dumpsys contained 12 saved network IDs. Do not count security views as networks.
- Select the requested OPEN/PSK view for verification, retain returned IDs after
  successful writes, and delete each unneeded network ID at most once.
- Write known credentials explicitly when PSKs are masked. Construct new managed
  configurations rather than inheriting SAE/security data from an unrelated view.
- Preserve legacy parcel handling only for already persisted old journals.

## Required verification

- Client codec migration, registration persistence/busy/no-policy-change, missing
  baseline rejection, last-success precedence and frozen operation recovery.
- Server revision conflicts, online snapshot, v2 exclusion, per-device ordering,
  delivery-only behavior and unchanged save/delete semantics.
- Real page controls/confirmation/layout with existing styles and registry size.
- Real DEV-005 registration, office/guest connection, invalid credential timeout
  and known-baseline restoration, actual reboot, unmanaged policy, and recovery
  across process/service restart where practical. Record blocked cases honestly.

## Validation checkpoint

V3 implementation and automated checks completed. Hardware passed single-AP
SSID/IP success, MDM-independent settlement, 60-second wrong-password recovery,
process/foreground-service recovery and boot recreation (109 to 110). Resumed
USB readback verified unmanaged boot110 to111 did not recreate externally
removed Wi-Fi. Separately restored basic connectivity, then verified explicit
managed re-enable and final unmanaged deployment. Final s-5ghz/IP connection,
retained baseline and matching server/client unmanaged policy were confirmed.
See the hardware report for exact outcomes and exclusions. No push.
