# Push/Sync validation

Scope: resumable job-based Push/Sync on PICO 4 Ultra Enterprise. Deploy the server
and matching client together. See [Push jobs](PUSH_JOBS.md) for the protocol,
operating policy, and breaking changes to the old standalone API.

## Automated verification

Earlier checks on macOS, 2026-10-21:

- Python 3.11.15 / aiohttp 3.14.5: 653 server tests passed in ten consecutive runs.
- Python 3.12.13 / aiohttp 3.14.3: 653 server tests passed in ten consecutive runs.
- Registration-sensitive tests: 45 passed in ten consecutive runs.
- Console: 66 passed, one existing skip.
- Android: 214 unit tests passed; development APK build succeeded.

The powered-on retention follow-up uses durable state version 2. Its current server
suite passes 656 tests on Python 3.11, and console checks pass 66 with the same one
skip. Android verification uses `testProdDebugUnitTest assembleProdDebug`; the
226-test suite passed in five consecutive runs and covers boot changes, process
downtime, five-minute checkpoints, bounded shutdown saves, Resume, delayed
entry-point expiry, invalid state, and persistence failure. The earlier repeated server results above predate the explicit
manager API and schema-statement cleanup.

The server suite runs with `python -m pytest -q` in `mdm-server`. The registration
subset is `tests/test_self_update.py tests/test_retire.py
tests/test_duplicate_connection.py`. Earlier Android checks used
`testDevDebugUnitTest assembleDevDebug`; console checks use
`node --test tests/test_push_jobs_v1_console.js`.

A controlled post-acknowledgement registration delay exposes an intermediate
`registering` device list. Tests wait for expected snapshot contents using one
overall deadline, including version and battery values where relevant. The
controlled delay proves this behavior without depending on an intermittent
failure appearing in an ordinary run.

Other regressions cover stale dispatch claims without terminal-state mutation,
rejection of duplicate dispatch preparation, non-target Resume reasons and update
timestamps, durable apply checkpoints, and payload-free client diagnostics.
Linux release CI was not run as part of these local checks.

## Hardware evidence

One PICO 4 Ultra Enterprise headset was tested with isolated server data and an
owned destination. Installed test APKs were verified by SHA-256. The original APK,
configuration, Push journal, and management connection were restored afterward.

The preceding review follow-up passed a normal Push against the Python 3.11 server, with
an exact file-set and SHA-256 match. With WebSocket DEBUG logging enabled,
registration and Push message types were present but lease queries and message
payloads were absent. The diagnostic logging setting was restored after the test.


The powered-on retention follow-up passed a normal Push with matching SHA-256,
then used owned interrupted-state fixtures without starting a replacement worker:

| Retention check | Observed result |
| --- | --- |
| Actual five-minute checkpoint | The saved anchor advanced by 300,011 ms and the remaining budget decreased by the same amount. |
| Framework normal reboot (`svc power reboot`) | The shutdown checkpoint saved another 70,791 ms after the last checkpoint; the new boot retained that saved budget. |
| Direct ADB reboot | The saved budget survived without resetting to 24 hours; the uncheckpointed interval was not counted. This path did not exercise the normal shutdown broadcast. |
| Client process replacement within one boot | Recovery deducted the elapsed interval, including time outside the previous process, without resetting the budget. |
| Short remaining budget | A four-second fixture durably produced `resume_expired`, then removed its exact temporary directory. |
| Version-1 migration | Old wall-clock state became version 2 with one fresh budget; another process restart consumed that budget instead of renewing it. |
| Invalid version-2 retention | The journal hash and owned temporary file remained unchanged; the server reported Push state unavailable. |

The original APK hash, configuration, empty Push journal, USB route, running client
and guard, and online management connection were verified after restoration. All
owned fixture directories were removed. The physical three-second power-button
operation, deep-sleep timing, and 24 hours of real elapsed operation were not tested;
the normal shutdown receiver was exercised through the framework reboot above.

The following broader recovery scenarios were exercised on the preceding
implementation. They were not all repeated for this follow-up.

| Scenario | Observed result |
| --- | --- |
| Push and Sync | Exact file sets and SHA-256 matched; Sync removed extra files. |
| Client process death | Partial bytes survived; manual Resume requested HTTP 206 from the retained offset and completed with matching SHA-256. |
| Server restart | The old lease was rejected; manual Resume issued a fresh lease and completed from the retained offset. |
| Device reboot | Interrupted ownership and partial bytes survived; manual Resume completed successfully. |
| Offline Cancel | Reconnection confirmed cancellation and removed the exact partial directory. |
| Apply checkpoint write failure | Journal write failure prevented destination changes. Automatic recovery later persisted the failure and released ownership. |
| Unsupported or invalid durable state | Original state and valid receipts were retained; new Push admission was rejected while storage was unavailable. |
| Historical active state | Recovery removed owned temporary files, retained the receipt, and did not start another worker. |

Physical power loss, multiple physical devices, and a ten-minute real transfer
were not tested. Long-transfer timing and multi-target races use automated tests.
