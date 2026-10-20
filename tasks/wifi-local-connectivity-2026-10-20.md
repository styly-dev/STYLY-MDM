# Wi-Fi local connectivity revision (2026-10-20)

This user-confirmed revision supersedes MDM-reconnection verification and server
application-result tracking in the supplied v1.1 document. Branch remains
`codex/group-wifi-settings`; local commits only, never push. Checkpoint `eb43feb`
contains the integrated UI and managed-LAN HTTP follow-up.

## Accepted behavior

- A manual deployment succeeds locally when one distributed SSID is connected
  and that Wi-Fi connection has an IP address within 60 seconds of changing
  settings. Internet, MDM reachability, and a return to the original LAN are not
  required or probed.
- First success commits the candidate as the device's successful baseline.
  Later disconnections do not trigger automatic rollback.
- If no qualifying connection is observed in the budget, restore the complete
  pre-change snapshot locally. Keep durable restoration information before the
  first mutation and an elapsed-time alarm/watchdog independent of MDM sockets.
- Process-death recovery restores unfinished journals before normal boot work;
  failed restoration blocks further configuration changes. Service restart must
  not renew a pending operation's 60-second budget.
- Once per actual OS boot, reapply the last locally successful profile. Existing
  v1.1 boot boundaries remain: no boot connectivity test or 60-second rollback;
  no repeated correction on service restart, screen-on or MDM reconnect.
- Server work ends after sending to the frozen current online group members.
  No result ACK, reconnect confirmation, observations, deferred delivery or
  device-operation fences remain on the server. Client-local busy/recovery
  protection remains necessary.
- Console displays send status only. A completed WebSocket send is not proof
  of receipt, connection, application or rollback. Offline devices retain their
  prior send status and receive no queued configuration on reconnection.

## Protocol and persistence boundary

- Advertise `wifi_settings_v2`; server must not dispatch this behavior to v1.
- Keep `EXECUTE_WIFI_APPLY` operation_id/revision/networks shape; operation IDs
  continue to deduplicate client execution.
- Redacted device state is `delivery` with operation_id/revision/status/reason.
  Public statuses are sent/failed/unknown; internal dispatching attempts become
  unknown after restart, without replay. Fixed reasons include unsupported,
  socket_send_failed, server_restarted and legacy_result_untracked.
- Preserve encrypted group profiles and HTTP/TLS opt-in, administrator/device
  tokens, Origin checks, and manual endpoint enrollment. HTTP is explicitly
  allowed on this managed LAN; encrypted persistence remains enabled.
- Migrate older encrypted application results to unknown legacy delivery state;
  never reinterpret application success as evidence of a v2 send. Preserve
  group transaction recovery and client restoration snapshots/baselines.

## Implementation and staged review

- [x] Android: replace server verification with local SSID/IP monitoring; remove
      network result replay and preserve durable rollback/boot lifecycle.
- [x] Server: delivery-only persistence/dispatch, no result waiting or fences,
      explicit v2 capability and migration coverage.
- [x] Console: delivery labels/reasons and no success/boot/mismatch claims.
- [x] Review asynchronous deadline, restoration, process-death and migration
      boundaries before final verification.
- [x] Run relevant/full tests, APK build, diagram checks and real-page flows;
      restart the same HTTP server with imported registry preserved.
- [x] Commit the revision locally; no physical-device claim and no push.

## Design review notes

- Official Android callbacks must correlate WifiInfo and LinkProperties for the
  same TRANSPORT_WIFI Network; no default-network/internet validation predicate.
- A reconnect API accepting a request is not proof of a fresh connection. For
  existing Wi-Fi, disconnect under the persisted local deadline, wait for that
  exact Network to be lost, reconnect and require fresh per-Network SSID/IP data.
  Missing freshness evidence times out safely; PICO callback delivery is untested.
- Location-sensitive callback data requires the API-31 location inclusion flag,
  permissions and Location services. Preflight before any Wi-Fi mutation.
- Server delivery records use operation IDs so an old concurrent send completion
  cannot replace the latest displayed attempt. Tests must block the actual send
  while editing profile/membership/replacing sockets, not only mutate afterward.

## Unmanaged policy extension (user request)

- Delete saves a new unmanaged revision without sending, altering device Wi-Fi,
  or clearing existing delivery records. Warn about retained networks and the
  required explicit Deploy.
- A later explicit unmanaged deployment durably disables boot reapplication,
  retains OS Wi-Fi and historical baseline, and has no local connection test.
- An older active attempt may finish/restore safely but cannot override the
  newer unmanaged policy. Later managed local success can re-enable management.
- Profile mode is managed/unmanaged, command mode is explicit; managed lists are
  nonempty and unmanaged lists are empty. GET/state expose mode; DELETE requires
  optimistic expected_revision just like PUT/deploy. Missing profile is mode null.
- [x] Verify warning/cancel, preserved history/no auto-send, explicit unmanaged
      deployment, reboot suppression, and successful managed re-enablement.

Review extensions: expected_revision is mandatory for PUT/DELETE/deploy to stop
TOCTOU sends/overwrites before polling notices another administrator's edit.
Per-device send ordering prevents a slow older managed deployment arriving after
a newer unmanaged command. The local policy epoch separately prevents an older
in-flight attempt from restoring boot management after unmanaged is received.

## Final verification and review

- Server full suite: 461 passed. Console Wi-Fi/Push suites: 28 passed.
- Android full JVM suite: 150 passed, zero failures/errors/skips; DevDebug APK
  assembly succeeded. Physical-device verification was not performed.
- Real complete page verified with the imported 290-device registry and an
  isolated actual server with v2/v1/offline simulated clients. Checked HTTP login
  from the console, group switching, profile saves, password retention/redaction,
  separate member drafts, preserved focus during a real DEVICE_LIST update,
  send-only labels, no reconnect replay, and 375/820/1280px layouts.
- Unmanaged DELETE on the isolated server preserved delivery state and sent no
  command. Explicit deploy then sent unmanaged to v2 only; v1 was unsupported
  and the offline client stayed excluded. The UI showed the retained previous
  delivery and required explicit deployment. Warning/cancel behavior is also
  covered by console tests. No physical device received a command.
- Independent lifecycle reviews found and corrected readiness/watchdog ordering,
  new-network disconnect progress, Android-29 callback construction, old-network
  seed resurrection, local observer cleanup and unmanaged baseline ordering.
  Server review/parent checks added true in-flight snapshot tests, optimistic
  revision checks and ordered per-device sends.
- Both SVG files and embedded editable XML parse; git diff --check passes.
- Main server runs HTTP on port7086 with managed-LAN opt-in, current registry and
  existing two-member test group preserved; original Documents registry unchanged.
  TLS trust/keychain settings were not changed. Secrets remain in mode-600 temp
  config files outside the repository. No push.

## Managed-LAN authentication correction

The user explicitly requires no authentication. The earlier token/session and
manual-enrollment requirements were implementation additions and are superseded.
Remove browser login, admin/device tokens, session endpoints and client enrollment
gates. Retain encrypted profile storage, optional transport configuration, local
Wi-Fi recovery and explicit unmanaged deployment. No push or real-device changes.

Validation: server461, console28, Android149 unit tests passed; DevDebug APK
assembled. Staged and integrated review repaired an eager token-cleanup write
that could suppress interrupted Wi-Fi recovery on storage failure. Decode now
ignores the old token without writing, and normal later persistence omits it.
A regression test preserves the TLS setting and unfinished rollback journal.
Re-review confirmed the finding is closed.

The actual HTTP console loaded all290 registry entries and the saved group,
opened Wi-Fi settings without login, and reconnected automatically across a
server restart without login. Local runtime admin/device token configuration
was removed; the existing registry/profile key and data remain. No physical
device validation, device Wi-Fi command or push was performed.
