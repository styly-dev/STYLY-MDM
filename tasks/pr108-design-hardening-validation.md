# PR 108 design hardening validation

Reviewed base: `9b7b0709f97bf3562d5aa3def2369d042e3a94da`.
Changes are isolated from the concurrent Wi-Fi checkout. Do not push.

## Change boundary

- Server lifecycle decisions validate evidence and commit related fields atomically.
- Disconnect DB failures retain a retry owner; replacement registration flushes it.
- Dispatch authority is decoded in one domain boundary; persisted reasons remain compatible.
- Retry supersession uses enqueue order. Initial snapshots batch eligibility and fence queries.
- Artifact GC runs independently of deadline maintenance and joins before store shutdown.
- Android wire and stored command validation have independent compatibility contracts.
- Worker completion and interruption have separate outcome types and centralized failure policy.
- Destination apply waits for an actor-owned durable checkpoint; execution fingerprint fields are explicit.
- Console submission is an explicit Promise. Selection and pending actions survive asynchronous redraws and failures.
- Wire messages and SQLite schema 3 are unchanged. Android writes state version 1 and reads unversioned state; unsupported versions and invalid active records remain unavailable without discarding receipts.

## Automated validation

- Python server suite: 718 passed after the final console self-review correction.
- Android unit tests: 212 passed; DevDebug APK build passed.
- Console: 64 passed, 1 existing skip.
- SVG outer and embedded draw.io XML parsed successfully. `git diff --check` passed.
- The no-ten-minute-limit regression exercises the real scheduler/watchdog. A temporary 600-second dispatcher cap makes it fail under a virtual clock.
- Same local benchmark, 100 jobs x 300 targets, 14,616,394-byte JSON output: base snapshot reads 689.8/685.8/688.7 ms; changed reads 158.0/159.9/165.2 ms. These are local DB snapshot timings, not network or browser render timings.

## Self-review

Server lifecycle, Android compatibility/concurrency, console integration, and resource usage were reviewed separately. One additional console bug was reproduced: a retained ready submission ignored a terminal snapshot and kept dispatching the same ended job. The cache now rejects ended, undispatched jobs with a fresh-request-ID retry. Terminal snapshots that prove dispatch succeeded still resolve the submission successfully. Both cases have regression tests.

## Hardware validation

Target: DEV-005, identified afresh through its Device ID PNG. The installed test APK SHA-256 matched the built APK (versionCode 11, versionName 0.7.0-pr108-check-dev).
Testing used only a loopback test server via USB reverse and the owned destination
`/sdcard/STYLY/pr108-review-20261021`. The test preserved and restored the installed APK,
preferences, and original Push journal. No Wi-Fi setting changes.


| Scenario | Observed evidence |
| --- | --- |
| Push | Two files applied; exact source/destination SHA-256 and file-set match. |
| Sync | Nested paths and exact final two-file set matched; owned extra files removed. |
| Client process death | SIGKILL removed the original PID. Restart preserved a 4 MiB partial and reported client_restarted; manual Resume used HTTP 206 with Range bytes=4194304-, completed, and matched SHA-256. |
| Server crash | SIGKILL of the loopback test server retained a 3 MiB partial. Client reported server_lease_revoked; server paused dispatch. Manual Resume issued a fresh lease, used HTTP 206 from byte 3145728, completed, and matched SHA-256. |
| Offline Cancel | Interrupted client was stopped again; Cancel was recorded offline. Reconnection settled failed/cancelled with confirmed absence and removed the exact partial directory. |
| Device reboot | boot_id changed; 4 MiB partial and interrupted ownership survived. USB reverse had to be restored and the expired connection window reopened. Manual Resume used HTTP 206 from byte 4194304, completed, and matched SHA-256. |
| Checkpoint write failure | After acceptance, only the Push journal directory/file were made read-only. AtomicFile errors occurred in validation, awaitApplyingCheckpoint and terminal persistence. Push state became unavailable, while the entire destination file set and hashes stayed unchanged. Permissions were restored; the automatic 60-second recovery saved and sent failed/client_persistence_unavailable, then released active ownership. |

Test-harness corrections: aiohttp multipart initially percent-encoded nested filenames; quote_fields=False restored browser-equivalent paths before the nested-path assertion. PICO protected-package policy ignored am force-stop and rejected disable-user; those attempts were not counted as process-death tests. Actual process-death tests verified SIGKILL and PID disappearance. No package-disable state change succeeded.

Unknown-version and invalid-active tests also passed: state_version=2 and a malformed active command (with ten valid receipts) each remained byte-for-byte unchanged, reported unavailable, and rejected new Push admission with HTTP 422. Restoring the valid test journal retained all ten receipts and no active owner.

## Final device state

The original APK SHA-256 matches the pre-test backup (versionCode 11, versionName 0.7.0-dev). Original configuration values match; only the app's diagnostic update_journal gained its normal launch entry. Original Push state is restored with no active work and no pending/completed test receipts. Client and guard processes are running, and the client reconnected to its original server. Reboot cleared USB reverse mappings; the original localhost:7086 route was restored and the test port 17908 removed. The owned test destination and device-local backup directory were removed. All ten test jobs are terminal and the isolated server is stopped. No Wi-Fi settings were changed.

Physical power loss, a ten-minute real transfer, and multiple physical devices remain untested; the long-transfer deadline regression uses a virtual clock.

## Follow-up simplification after 8e3fb1b

The supported headset for this change is PICO 4 Ultra Enterprise. Connectivity-only
refreshes no longer rotate submission identity: the first send captures the target
set, and uncertain upload/dispatch retries reuse that request and job. Dispatch
settlement is shared, and completed or abandoned submission metadata is released.

The old standalone Push upload/fanout/download/UI paths were removed. `POST /api/bundles` now returns HTTP 410 without reading the body; `/bundles/*` is no
longer served, and identity-less `PUSH_FILES` requests receive a WebSocket error.
Existing bundle files are retained. Current job-v1 commands and SQLite schema are
unchanged. Historical durable active records, cleanup paths, and receipts remain
readable; removing the old live protocol does not discard execution evidence.
The wire/stored readers share value validation while preserving their distinct
required fields. `beforeApply` is required and the obsolete `onApplying` callback
is gone.

Independent console, Android lifecycle/testing, and server architecture reviews
found one removal regression: historical active recovery still needed the legacy
attempt-directory resolver. That path is retained only for durable cleanup. A
regression now proves first-load availability, physical cleanup, receipt retention,
and no worker execution. Parent review also caught and corrected accidentally
relaxed stored artifact invariants during decoder consolidation.

Final automated checks: **645 Python tests passed**, **66 console tests passed / 1
existing skip**, **212 Android tests passed**, APK build succeeded, diagram XML
parsed, and `git diff --check` passed. Obsolete standalone tests were removed;
packaging safety and install/Push shared-budget coverage now exercise job-v1 and
the production transfer adapter. The added GC shutdown test blocks the real GC
thread, delivers cancellation, and proves store close waits for thread completion;
a temporary no-join mutation makes it fail.

DEV-005 was re-identified through the Device ID PNG and tested through isolated
loopback port 17908 and the owned destination above. The installed APK hash matched
`0.7.0-pr108-cleanup-dev` (versionCode 11).

| Follow-up scenario | Observed evidence |
| --- | --- |
| Push and Sync | Both succeeded; exact file sets and SHA-256 matched. Sync removed the owned extra file. |
| Historical identity-less active | First restart remained available, removed `jobs/legacy/1`, cleared active ownership, retained a `client_restarted` receipt, and did not download or apply the historical command. |
| Required apply checkpoint | After durable acceptance, journal writes were denied. Logs show failure in `awaitApplyingCheckpoint`; the device reported unavailable and the entire destination still matched the preceding Sync. After permissions were restored, automatic recovery settled failed/`client_persistence_unavailable`, with no active owner and no pending result. Destination hashes still matched after settlement. |

The final source-only cleanup removed one unused legacy timeout constant after
hardware testing; the complete Android tests and APK build were rerun successfully.
No executable behavior was changed by that last deletion. Multiple physical devices
and physical power loss were not retested; the two-target connectivity-flap/lost
upload-response regression is covered by the console harness.

Restoration was verified: original APK SHA-256 and version `0.7.0-dev`, original
configuration values (excluding the normal diagnostic launch journal), and empty
original Push state all match. Client and guard are running; the client reconnected
to localhost:7086. Only that original USB reverse remains. The test destination and
device-local backup were removed; all 13 isolated-server jobs are terminal and the
test server is stopped. No Wi-Fi settings were changed. No push was performed.
