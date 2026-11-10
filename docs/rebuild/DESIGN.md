# Group Settings design from first principles

## What to build from scratch

A setup publishes a reusable recipe that takes effect at specific lifecycle events. Its managed values apply on first contact in a boot and on a new applicable publication. Release ends future writes. The setup says which values the station wants at those events. A headset's attempt history says what the SDK verified.

| Whole-system model | Where it fits | Fit for this feature |
| --- | --- | --- |
| A fleet rollout creates a job per target and tracks the requested revision through completion. | A campaign must finish a fixed cohort with explicit scheduling and delivery history. | Offline jobs must be superseded when membership or publication changes. The device still needs its own boot claim and Wi-Fi recovery. |
| A declarative agent continuously compares and corrects device state. | An enforced kiosk must recover drift and retry automatically. | Correcting drift between lifecycle events conflicts with local edits. Automatic retries of uncertain effects conflict with the Retry rule. |
| Resolve a published setup at contact and when its applicability changes. The device applies it under a boot and revision claim. | Reusable setup with offline members and a limited set of application events. | Directly represents the operator's draft, publication and assignment. Local recovery completes without connectivity to the station. **Selected.** |

For the selected model, I would build the following boundaries.

| Boundary | Greenfield choice | Concrete alternative and tradeoff |
| --- | --- | --- |
| Configuration data | One local transactional registry. Immutable IDs for groups and packages, a device-keyed membership relation, and separate draft and publication. Group names are labels. From scratch, SQLite can enforce uniqueness and references and update observations without rewriting configuration. | A single atomically replaced snapshot also enforces the domain model in one process. Per-device materialized policy duplicates the publication and makes group moves or republish update many stored copies. |
| Station and device connection | A live channel sends the effective immutable publication to a supported, identified device. Resolve from committed state at the serialized send boundary and on reconnect. Explicit Retry is an exact-version action. | Periodic polling also resolves latest, with delay and recurring requests. A durable outbox fits rollout-job semantics and needs supersession logic for the latest-only rule. |
| Device execution | One local worker owns a durable version, boot and Retry claim. The SDK adapter applies and verifies each supported setting. Store the stage evidence before delegating Wi-Fi. | An application-wide OS transaction would imply that SDK effects can be undone together. Per-item readback represents partial effects and keeps hardware knowledge in the adapter. |
| Network recovery | An independent local operation records baseline, deadline and outcome. Correlate its evidence to the package attempt with an operation ID and version. | A unified journal could commit both outcomes together, while making Wi-Fi recovery depend on the package store. Using the management connection as success would confuse station reachability with a working local association and IP. |
| Operator console | A single editor owns the editable draft. The server owns publication, assignments and deployment status. A write acknowledgment and a device observation are separate evidence. Show results for the exact publication. | A multi-screen release wizard is useful for campaigns with approvals and waves. A central browser store could simplify many views, but still needs to keep an uncommitted draft, an acknowledgment and a server projection distinct. |
| Startup and hardware | Content launch has its own deadline. The firmware adapter owns readiness, per-item reads/writes and recurring timer arbitration. Adding a scalar changes the setting definition and adapter, not delivery or Wi-Fi recovery. | A global startup barrier would make optional configuration or SDK work a dependency of launching content. A generated shared catalog would reduce duplicated definitions when supported device families multiply. |

The greenfield brief contained operator behavior and physical constraints. It omitted prior code, bugs, protocols and storage. A separate Sol session confirmed it had seen project instructions and a historical summary but used the brief and did not inspect the implementation. Its independently proposed local DB, device ownership and rollout alternative informed this comparison. Its polling, result history, and broader security and enforcement choices would require additional product decisions. The parent had prior exposure to the working implementation.

## Reconcile the target with this repository

| Area | Working design compared with the target | Migration choice |
| --- | --- | --- |
| Product and delivery | Shared packages, active revisions, live snapshot delivery and boot-local application already match the selected model. The socket gate resolves latest and rejects stale exact Retry. | Keep these contracts and the scoped membership edit from the prior patch. |
| Registry | Group membership, group assignment and packages live in separate mutable maps. Writers install changes and roll back on storage failure. | Replace the write path with a proposed group/package snapshot, persist it and then publish one reference. Store members and package reference together. Preserve name-keyed APIs, device identity and on-disk JSON. |
| Device and Wi-Fi | Wi-Fi already owns rollback independently. The package worker links an in-flight Wi-Fi operation through memory while the two stores persist separate facts. | Keep the platform state machine and separate ledgers. Make the handoff durable and complete only from exact settled evidence. Use the original boot count when recovering a proven result. |
| Console | Draft and published views already have useful separation. A group HTTP response can fill part of the group snapshot locally. | Keep the editor and derive deployment status from the group snapshot. Keep the assignment acknowledgment separate while waiting. |
| Hardware, startup and packaging | The feature already has a firmware adapter, bounded startup, an alarm recovery path and ordinary build outputs. | Preserve them. Existing tests and PICO evidence define the compatibility baseline. |

For storage, the synthetic 256-device comparison rewrote 266,741 bytes for one JSON report update. A SQLite WAL/FULL model appended one 4 KiB page plus headers, 4,152 bytes total, before checkpoint. Its stable group ID survived rename and rejected a stale expected-owner change. This measures write scope and identity behavior. The current workload has no measured production storage bottleneck that warrants a format and group-identity migration in this candidate. The JSON candidate adopts the transaction boundary while retaining the established data and control contracts.

## Chosen migration route

Adopt the existing product model and selectively replace the ownership boundaries. The candidate contains the authoritative server snapshot, correlated device/Wi-Fi handoff, and separate console acknowledgment. A full replacement would also require new API/data migrations and physical PICO parity checks for behavior the current implementation already supports. A small local fix at any one error branch would leave the same ownership boundaries in place.

The device record gains optional private `wifi_operation_id` evidence. Old records restore as before. A settled, exact terminal receipt can complete the recorded attempt after restart. Unmatched, missing or unknown evidence retains interruption. A known result is held until the parent journal can save it. The Wi-Fi journal keeps one latest result, so an unrelated old rollback that replaces a receipt before it is read can still leave an interrupted record. These cases preserve the no-implicit-retry rule.
