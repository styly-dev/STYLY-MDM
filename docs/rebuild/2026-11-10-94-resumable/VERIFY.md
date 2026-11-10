# Verification and adoption: Group Settings since 94-resumable

**Decision: partial adoption.** Keep the shared package, atomic deployment, current-connection delivery, on-device attempt journal, Wi-Fi recovery and offline Reapply designs. The scoped membership editor was verified in isolation and applied to the working tree after review. [Applied patch](../../../tasks/group-settings-rebuild-20261110/membership-candidate.patch).

### Captured baseline

| Check | 94-resumable `b005de1` | `22d0bf1` + pre-application edits | Applied membership patch |
| --- | ---: | ---: | ---: |
| Python server | 862 passed | 889 passed | 892 passed |
| Node console | 106 passed, 1 skip | 126 passed, 1 skip | 131 passed, 1 skip |
| Android DevDebug host tests | 233 passed on rerun | 295 passed | Same Android source as current |
| Android DevDebug and ProdDebug | Both built | Both built | Same Android source as current |
| Local Python wheel build | Built | Built | Built |

The [source inventory](../../../tasks/group-settings-rebuild-20261110/source-delta.txt) covers the 52 paths from `b005de1` to the pre-application checkout, including 24 locally modified tracked files. The Git binary diff from `22d0bf1` before application hashed to `d75e577895cf5c341de2d60440f19eae9e1487a1893330a223e159b9406f51f1`. The reviewed patch spans eight files and has SHA-256 `4eadaea885a1a4a342db9e34569cbdc21e37de2fb7f4db8fc505fce01f504fdb`. After application all 187 tracked paths matched the tested candidate byte for byte and the staging area matched its prior state. The patch passed forward and reverse application checks. The untracked `uv.lock` did not provide the comparison environment.

Both trees used Python 3.12.13, aiohttp 3.14.3 and `PYTHONPATH=. python -m pytest -q -p no:cacheprovider` with localhost access. Node 25.8.1 ran `node --test tests/*.js`. JDK 17 and the local Android SDK ran `./gradlew --offline --no-daemon --max-workers=4 :app:testDevDebugUnitTest :app:assembleDevDebug :app:assembleProdDebug`; test totals are from XML. The comparison base initially failed one Push recovery test in `PushJobCoordinatorIntegrationTest.kt:666` because the test's recovery task was null. The same source passed all 233 on rerun. That Push test and coordinator are byte-identical across both source snapshots; this run does not prove the timing cause or eliminate the flake. The candidate did not change them.

The wheel build used the existing offline setuptools/setuptools-scm cache and version `0.0.0`. The current and candidate wheels contained the configuration and Wi-Fi modules and the console assets. The reviewed wheel metadata lists aiohttp as a runtime dependency and pytest and pytest-asyncio in the dev extra. All 892 server tests also passed with `cryptography` imports blocked. No signed release, CI embedding, or upload was attempted. Current SVGs and their embedded editable diagrams parsed.

### Measured results

| Probe | Pre-application source | Applied candidate |
| --- | --- | --- |
| Two console windows: one snapshot delayed; move untouched X from North to South, then add a different member from the old North view | X moved back to North. A synthetic canonical device received brightness policies **30 → 75 → 30**. | X stayed in South and received **30 → 75**. The selected addition alone received North's policy. |
| Another console changes the source group of a staged addition | Full-list move has no source-group precondition. | Chrome showed a conflict and sent no write after seeing the new owner. A separately delayed/stale command was rejected by the server as a whole in a two-admin WebSocket test. Current owner and removal cases passed. |
| Toggle a selection back, then receive another console's addition; old server snapshot with no capability | Existing full-list command has no negotiated scope. | Reverted selection followed the remote addition. A simulated old `GROUP_LIST` showed an update message and sent no membership write. |
| Membership save while device delivery waits; console disconnect during the save acknowledgment | The first candidate waited for delivery before acknowledging the save. | The admin received the committed group list and save acknowledgment while device delivery was held. With a simulated disconnect on acknowledgment, a connected synthetic device still received the saved policy. |
| Shared and one-step deployment; exact membership token; failed persistence and offline rejoin | Real routes preserve committed revisions, reject stale scope, release only the requested group, and resolve latest policy on rejoin. | Same route/registry tests pass; the scoped membership storage failure test still verifies unchanged in-memory state and registry bytes. |
| Process loss during Wi-Fi, settled receipt, restart, manual Reapply and startup timeout | JVM coverage passes: interrupted attempts stay fenced, exact settled evidence completes, cached policy does not auto-apply, explicit offline Reapply works and clear removes its source. Bounded startup gate passes. | The Android sources are identical. No separate device or Android build is attributed to the candidate. |

The browser probe used the real console and station on loopback in Headless Chrome 157, with separate tabs at 1440 and 500 px. It was rerun on the reviewed patch before application. It delayed only the receipt of a group snapshot in one page and observed actual policy messages from a synthetic canonical client. [Reproduction and normalized output](../../../tasks/group-settings-rebuild-20261110/README.md). The 94-resumable base lacks packages; its role here is core regression comparison, not a feature baseline for the newer probes.

### Compatibility, rollback, and open checks

- Coordinate server restart/upgrade and console reload when applying or reverting the patch. The new editor requires the advertised scoped edit command. Reload old tabs so they use the new editor. Existing no-move `SET_GROUP_MEMBERS` callers retain their legacy list replacement semantics, including stale-list limits. Device wire messages, APKs and the JSON registry schema are unchanged by the candidate. A rollback all the way to the 94-resumable line needs a registry backup compatible with that line.
- Registry tests cover replacement failures and process restart. Power-loss durability, real PUI scheduling, alarm-only recovery, physical offline Reapply, and self-update with a configured recurring shutdown schedule were not verified in this run. Earlier DEV-005 records are limited to their reported firmware and operations.
- A disconnected headset cannot know whether the station later cleared or replaced a cached policy. Manual Reapply is explicitly the last received version; automatic writes still depend on live delivery. Firmware-dependent delay values and live authorization/credential retention policy were not changed.
- Stable group IDs would prevent delete/recreate ambiguity for the same name. A replacement storage engine, retained rollout history and wider firmware support remain separate changes with their own acceptance tests.
