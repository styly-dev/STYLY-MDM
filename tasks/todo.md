# PR 108 design hardening

Base: `9b7b0709f97bf3562d5aa3def2369d042e3a94da`.
Work is isolated from the concurrent Wi-Fi checkout. Do not push.

## Plan

- [x] Centralize server lifecycle decisions and atomic disconnect settlement.
- [x] Retain recovery ownership after database failures and recover orphaned active rows.
- [x] Centralize dispatch authority without changing the wire or database schema.
- [x] Use enqueue order for supersession and batch snapshot eligibility queries.
- [x] Separate GC from deadline maintenance and avoid redundant physical cleanup.
- [x] Separate Android wire and stored command readers with explicit compatibility.
- [x] Classify worker outcomes and partial retention in one place.
- [x] Require a durable checkpoint before destination apply.
- [x] Make execution fingerprint fields explicit.
- [x] Replace implicit console upload interception with an explicit asynchronous API.
- [x] Preserve pending actions through redraws and connection changes.
- [x] Add regression coverage for races, persistence failures, clock equality, and integration contracts.
- [x] Update development documentation and relevant diagrams.
- [x] Run server, console, Android tests and build.
- [x] Self-review the complete change; fix findings and rerun affected checks.
- [x] Validate on DEV-005 using an isolated test server and owned test destination.
- [x] Record hardware evidence, limitations, and final device state.
- [x] Commit completed changes locally; do not push.

## Review and validation

Implemented all requested design points and completed self-review. The console review found one additional terminal-cache bug, now fixed with regression tests.

Validation: Python 718 passed; console 64 passed / 1 existing skip; Android 212 passed and APK build passed. DEV-005 completed Push, Sync, process/server/device restart Resume, offline Cancel, checkpoint write-failure recovery, unknown-version and invalid-active retention. Original APK/configuration/Push state and original USB server route are restored; client and guard run. See `tasks/pr108-design-hardening-validation.md` for evidence and limits.

No push is authorized.


## Follow-up simplification

Base: `8e3fb1b`. Current supported headset: PICO 4 Ultra Enterprise. No push.

- [x] Keep submission identity/targets stable through connectivity-only changes.
- [x] Consolidate dispatch settlement and release completed/abandoned submission metadata.
- [x] Share command value validation, remove the obsolete apply hook, and require the checkpoint callback.
- [x] Remove unused live Push protocol fallback; preserve historical execution evidence.
- [x] Prove GC shutdown waits for its blocked thread before closing the store.
- [x] Update documentation and run server/console/Android verification.
- [x] Self-review changes, correct findings, and perform focused hardware validation if the device is free.
- [x] Record results and commit locally; preserve concurrent checkout and do not push.

Follow-up validation: 645 Python, 66 console (1 existing skip), and 212 Android tests passed; APK build succeeded. Independent review findings were corrected. DEV-005 passed Push/Sync, historical active cleanup and blocked apply checkpoint tests, then was restored and reconnected to its original server. See `tasks/pr108-design-hardening-validation.md`.
