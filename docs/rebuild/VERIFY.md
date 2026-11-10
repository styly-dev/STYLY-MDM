# Group Settings verification and adoption

**Decision.** Retain the published setup model and selectively adopt the three ownership changes described in the design. The isolated candidate replaces the station's configuration commit path, persists the device-to-Wi-Fi handoff, and separates console acknowledgment from deployment status. The [candidate patch](../../tasks/group-settings-rebuild-20261109/full-design-candidate.patch) is relative to `22d0bf1` plus the previously applied membership patch. It covers 18 files including nine test files and four feature documentation or diagram files.

## Baselines and build checks

| Check | 94-resumable `b005de1` | Working `22d0bf1` with membership patch | Isolated design candidate |
| --- | ---: | ---: | ---: |
| Python server, `python -m pytest -q -p no:cacheprovider` | 862 passed | 883 passed | 886 passed |
| Node console, `node --test tests/*.js` | 106 passed, 1 skip | 122 passed, 1 skip | 123 passed, 1 skip |
| Android, `:app:testDevDebugUnitTest` | 233 passed | 286 passed | 292 passed |
| Android, `:app:assembleDevDebug :app:assembleProdDebug` | Both built | Both built | Both built |

Python used the same Python 3.12 environment on all trees with `PYTHONPATH` set to the isolated server source and loopback enabled. Android used the repository Gradle wrapper with JDK 17, local SDK, `--offline --no-daemon --max-workers=4`, and counts from test XML. Node used the same local runtime for all trees. The common suites exercise existing identity, Push/Sync, installation, updates, retirement, group targeting, and console behavior. The 94-resumable base predates Configuration Packages.

## Matched behavior and boundary tests

| Scenario | Working tree | Candidate |
| --- | --- | --- |
| Shared setup with a same-size member exchange, followed by a restart before notification | Both groups counted, including offline members. Stale impact rejected. Save preserved the unmanaged draft value. After suppressing fan-out and restarting, the moved offline member received the latest revision and the removed member received release. | Same results through the real REST and WebSocket routes. |
| Storage replace and temporary-file cleanup both fail | Registry bytes remained unchanged at the failed request. The next canonical connection received the proposed brightness 80 instead of the saved 30. | The next canonical connection received 30. A separate regression covers the next unrelated label save and reconnect. |
| Wi-Fi terminal commit followed by simulated process loss before the package finish | The ordinary reconnect reported `interrupted` with empty setting results. With an exact Retry the scalar and Wi-Fi adapters were invoked twice each in total. | Matching terminal evidence recovered `complete` and the scalar readback. The same Retry sequence left each adapter at one invocation total. |
| Wi-Fi mid-switch process loss and boot rules | The existing suites cover the individual journal and Wi-Fi recovery paths. | The composed JVM cases restarted both controllers from serialized stores, restored auto-join once without replay, and kept Retry exact. Proven success in an earlier boot allowed one new-boot application. Missing, unknown, or mismatched receipts stayed interrupted. |
| Browser workflow with 256 synthetic records | Save, Manage off, Publish, assignment and release worked. When the HTTP assignment response arrived before its group snapshot, the display temporarily said the published package had no revision. | Same editor behavior, confirmation counts and release. The intermediate state showed that a group update was pending. The current revision appeared after WebSocket update. Candidate feedback was visible at 1440 and 375 px. |

The baseline Android boundary probe added only test injection seams in a separate temporary copy. Both controllers retained their original runtime and recovery logic. Process loss means stopping the old executor queues and rebuilding the controllers from their saved state. Adapter counts measure host calls. The candidate's six composition tests also cover journal-save failure at the handoff and retaining known terminal proof while a new policy waits.

The synthetic storage experiment used the same 256 device records, eight groups and four shared packages. One report update rewrote 266,741 bytes in a JSON temporary file. SQLite 3.50.4 with WAL/FULL appended 4,152 bytes before checkpoint. The SQLite stable group ID survived rename and reopen, and its expected-owner and uniqueness constraints passed. This compares data shape and write scope under a synthetic workload. Production latency, power-loss durability and operational migration cost remain separate questions.

## Compatibility and limits

Registry JSON, capability, package REST and device/console WebSocket contracts are compatible with the captured working implementation. The candidate adds an optional operation ID to the private package journal. The older reader ignores it and conservatively treats an unfinished attempt as interrupted. A Wi-Fi failure receipt overwritten by an unrelated startup rollback before the package can read it also stays interrupted. An exact known receipt can finish only the attempt it belongs to.

Physical PICO, actual power loss, hardware-dependent callback order and cold alarm wakeup were outside this run. The candidate preserves the existing firmware adapter and alarm/rollback behavior. Existing evidence for selected PUI 5.15.7 operations is scoped to DEV-005. The current documentation changes include synchronized visible and editable draw.io labels.

The patch passes forward checks against the working source and reverse checks against the isolated candidate. Applying or reverting it pairs the server and console, and uses the same station registry format. A rollback to the pre-package 94-resumable line still needs its compatible registry backup.

## Application from 22d0bf1

At the user's request, the first membership patch was reversed before applying the [transplanted design candidate](../../tasks/group-settings-rebuild-20261109/full-design-candidate-from-22d0bf1.patch). The transplant keeps the three design changes and uses the checkpoint's `MOVE_GROUP_MEMBERS` full-list contract. In a synthetic two-console test, saving from an old view could again move an untouched device back to its earlier group. The scoped membership change and its tests belonged to the first patch.

The transplanted source passed 885 Python tests, 122 Node tests with one existing skip, and 292 Android tests. Both Debug flavors built. The shared-publication workflow, offline reconnect, and Chrome assignment display also passed. The applied worktree diff matches the isolated transplant byte-for-byte.
