# Issue #91 design conformance review

Reviewed against `STYLY-MDM-issue-91-push-job-design(3).md` on 2026-07-30.

## Result

The implementation follows the design's canonical identity, state, persistence,
scheduling, client ownership, reconciliation, restart, console reconstruction, and
legacy migration model after the fixes recorded in this branch.

## Conformance matrix

| Design area | Status | Implementation / verification |
|---|---|---|
| Browser creates `job_id` before upload | Conformant | `POST /api/push-jobs` then job-owned upload route; console no longer sends bytes to `/api/bundles` for the new flow |
| Canonical UUID identities and attempt=1 | Conformant | UUIDv4 job/artifact/client validation and exact `(job_id, device_id, attempt)` protocol |
| Canonical request fingerprint / idempotency | Conformant | normalized destination, sorted targets, stable JSON SHA-256; replay lookup occurs before live preflight |
| SQLite schema / WAL / FULL / FK / busy timeout | Conformant | dedicated single-thread store and schema-version check |
| Job/device state machine and ordered aggregate | Conformant | domain transition tables and transaction-time derivation |
| Exact-revision full snapshots | Conformant | canonical transaction builds the snapshot before commit returns; duplicate no-op is not revised |
| Immutable artifact publication | Conformant | fsync file, atomic replace, POSIX directory fsync, DB link only after publication |
| Owned work cleanup | Conformant | ready/failure/interruption/expiry/startup cleanup occurs off the event loop and failures are logged |
| Per-device durable queue | Conformant | monotonic `enqueue_seq`; globally oldest non-terminal queued row cannot be overtaken |
| Shared global transfer pool | Conformant | Install, legacy Push, and job-v1 Push use one semaphore and typed registry |
| Waiter-before-send ordering | Conformant | exact waiter exists before `dispatching` commit and WebSocket send |
| Live capability/session recheck | Conformant | rechecked after slot acquisition; final owner check and bounded send share device lock |
| Transfer completion != job completion | Conformant | slot released at exact transfer completion; execution lease retained through terminal result |
| Application-scoped single active worker | Conformant | coordinator actor and execution gate owned by `MdmClientApplication` |
| Client active/result durability | Conformant | active persisted before ACK; terminal outbox persisted before cleanup/send; ACK-only deletion |
| Client process restart | Conformant | active becomes `client_restarted` result, then attempt directory cleanup; no automatic reapply |
| Command acceptance reconciliation | Conformant | 15s acceptance, 60s exact probe, one same-attempt replay on explicit absence only |
| Long-running reconciliation | Conformant | 1800s default; deadline callback validates exact stored deadline before `unconfirmed` |
| Persistent device fence | Conformant | local and opaque job-v1 fences require matching terminal/absence or process replacement; visible jobs receive revisions |
| Server restart gate | Conformant | no automatic redispatch; queued rows survive and require operator dispatch of the same job |
| Superseded WebSocket guard | Conformant | inbound job-v1 settlement and outbound command send share the per-device owner lock |
| Console reload reconstruction | Conformant | full snapshot + revisioned Map merge; concurrent jobs and safe fence reconcile action |
| Restricted legacy fallback | Conformant with documented limitations | no identity guessing; one active legacy execution and typed opaque transfer/fence identity |
| Non-goals (#85/#89/#94) | Conformant | no byte progress, cancellation, upload resume, Range resume, or Install job integration |

## Deviations found and corrected during review

The first implementation did not intentionally override the design in the following
areas; they were defects or incomplete pieces and were corrected:

1. device commands used a relative artifact URL;
2. the exact transfer waiter was registered after the `dispatching` transaction;
3. later enabled jobs could overtake older paused/not-ready queue entries;
4. explicit pre-accept absence did not perform the one allowed same-attempt replay;
5. late acceptance after the first timeout could be dropped;
6. fence mutations did not revise every snapshot that displayed the fence;
7. legacy fences could not be cleared by a confirmed new job-v1 process;
8. stale reconciliation timers did not compare the stored deadline;
9. inbound job-v1 messages could race a replacement WebSocket owner;
10. the client result lacked a stable `failure_code` / `detail` pair;
11. completed receipts had a count bound but not the specified seven-day bound;
12. attempt work cleanup happened before terminal outbox persistence;
13. client destination and ZIP validation were weaker than the server/design invariants;
14. `BundleSync` could follow destination symbolic links;
15. the console could retain only one pending pre-job upload;
16. startup did not reconcile missing referenced artifacts or canonical publication orphans;
17. restart could preserve a stale long deadline for an existing pre-accept reconciliation;
18. an exact late result was not ACKed after independent matching evidence had already cleared its fence;
19. the Android client did not discard callbacks from a superseded OkHttp WebSocket;
20. the client execution gate was released before terminal outbox fsync completed;
21. opaque job-v1 fences could not be exact-reconciled or cleared by matching foreign result/absence evidence;
22. an upload request reaching an already-expired create deadline committed `interrupted` without publishing its snapshot;
23. safety-critical Android command fields used permissive `org.json` coercion instead of strict JSON types;
24. rearming the created-job deadline cancelled the owner task and could lose publication after a concurrent DB commit;
25. server create identity validation accepted non-v4 UUID values.
26. destination canonicalization erased symbolic-link components before client validation;
27. pre-dispatch terminal transitions did not wake a queue blocked by the older job.
28. process replacement cleared an opaque fence before collecting every snapshot that exposed it;
29. malformed foreign success counts could be treated as terminal evidence before validation.
30. duplicate REGISTER interleaving could restore a superseded runtime WebSocket owner;
31. unknown exact phase/transfer events could raise `StoreNotFound` and terminate the device receive loop;
32. result ACK and reconciliation identity parsing still used permissive Android JSON coercion;
33. coordinator transport callbacks could route through a newer mutable socket before registration;
34. REGISTER active state did not validate the reported artifact against the canonical assignment;
35. canonical legacy results were also forwarded by the old identity-less handler;
36. job-aware derived device/result events could overwrite the revisioned Console row through the legacy handler;
37. duplicate terminal results rebroadcast unchanged canonical snapshots and terminal logs.

Targeted tests cover queue ordering, fence revisions, one-time exact replay, stale
deadline callbacks, process replacement, opaque-fence matching, committed upload expiry,
strict JSON types, absolute artifact URLs, archive path conflicts, and destination
symlink escape.

## Intentional implementation-layout differences

These are organizational differences, not lifecycle or protocol differences:

- `PushJobManager` lives in `push_job_manager.py` so the dependency-free domain module
  does not import the SQLite store and create a circular dependency.
- `push_runtime.py` is a narrow compatibility adapter around the established monolithic
  `server.py`; unrelated server commands and old clients retain their existing path.
- `static/push-jobs-v1.js` bridges the current inline console controls to the new API
  rather than rewriting unrelated console UI.
- Directory fsync is performed on POSIX. Windows lacks a portable Python directory
  handle, so Windows fsyncs the artifact file and performs atomic `os.replace`, but
  cannot perform the POSIX directory-handle fsync.
