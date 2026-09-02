# Push / Sync jobs

Issue #91 introduces a server-owned job model for **Push Files** and **Sync Folder**. APK install remains on the existing path.

## Identity and persistence

A browser creates a job before uploading bytes:

1. `POST /api/push-jobs` allocates an opaque UUIDv4 `job_id` and atomically records the target snapshot in `DATA_DIR/push_jobs.sqlite3`.
2. `POST /api/push-jobs/{job_id}/upload` writes into `push-work/{job_id}`, packages the upload, fsyncs it, and publishes an immutable `push-artifacts/{artifact_id}.zip`.
3. Admin WebSocket `PUSH_FILES {job_id}` enables dispatch. Destination, mode, targets, and artifact data are read from SQLite rather than trusted from a second browser message.

`client_request_id` makes job creation idempotent. The server looks up an existing request **before** live-target preflight, so loss of the first response followed by a device disconnect still returns the original job. The canonical fingerprint sorts targets, normalizes the shared-storage destination, and hashes stable JSON. Reusing an ID with different canonical data returns `409 Conflict`.

Issue #94 upgrades `push_jobs.sqlite3` to schema version 2 by adding the
per-device immutable dispatch revision. Existing version-1 rows are migrated in
place without discarding jobs. The Issue #91 server ignores this nullable column
when rolled back, but assignments created while it is running have no dispatch
revision and therefore remain intentionally non-resumable after a later
roll-forward. Keep a matching database backup for operational rollback;
restoring it is not required solely because the schema-2 column exists.

## Resumable artifact transfer

Published bytes are immutable for the lifetime of an opaque `artifact_id`. The
SQLite artifact row stores the unique storage name, exact byte size, SHA-256,
creation time, and retention state. The SHA-256 is also exposed as a quoted
strong ETag. Garbage collection leaves a tombstone row, so neither an artifact
ID nor its `/artifacts/{artifact_id}` URL can be reused after its bytes expire.

Job-v1 commands carry an immutable per-device `revision`, `artifact_id`, absolute
URL, exact size, SHA-256, and ETag. The aggregate job revision may continue to
advance; the dispatch revision remains fixed for one `(job_id, device_id,
attempt)` and is the restart-resume authorization token. Large artifacts require
both `push_job_id_v1` and `push_resume_v1`. The exact artifact size is checked
again at dispatch, so ZIP container overhead cannot bypass capability admission.
Legacy clients retain the existing small-push path.

The client keeps job-owned state below
`Downloads/styly-mdm/.push-tmp/jobs/{job_id}/{attempt}/`:

- `artifact.part` is the only authoritative byte offset;
- atomically replaced `metadata.json` binds that file to the job, attempt,
  dispatch revision, artifact ID, size, SHA-256, strong ETag, and retention
  timestamps. The URL is a replaceable locator refreshed by each exact server
  authorization, so a server authority change does not invalidate the artifact;
- `artifact.zip` appears only after exact-size and SHA-256 verification.

Unknown, malformed, expired, or mismatched metadata is never appended to. A
validated partial of `N > 0` bytes is requested with `Range: bytes=N-`,
`If-Match: <strong-etag>`, and `Accept-Encoding: identity`. Response handling is
fail-closed:

- `206` is appended only when ETag, `Content-Range`, total size, and
  `Content-Length` all describe exactly the requested suffix;
- `200` is never appended; with the same validator it replaces the partial from
  byte zero;
- `412` is an artifact-identity failure;
- `416` is accepted only when `Content-Range: bytes */T` proves the local file is
  already the exact complete artifact;
- `404` and `410` are explicit artifact-unavailable failures;
- transient I/O, `408`, `429`, and 5xx failures keep validated partial bytes and
  use six attempts with bounded 1/2/4/8/8-second backoff.

Extraction and destination apply start only after size and SHA-256 verification
and an atomic local rename. A hash mismatch removes the untrusted partial.

On process or device restart, the client retains a valid interrupted Issue #94 job-v1
record and reports its exact artifact, dispatch revision, attempt, and local
offset during registration/reconciliation. The server requeues it only when that
evidence matches the durable assignment and its recorded `push_resume_v1`
capability, then sends a fresh `EXECUTE_PUSH_FILES` authorization. The client
never resumes, extracts, or applies stale partial work merely because it exists.
If exact evidence cannot be authorized, the server sends
`PUSH_RESUME_REJECTED`; the client durably releases only that exact interrupted
identity, removes its owned work, and confirms `absent`. A verified
`artifact.zip` surviving a restart is hash-checked and reused without network
transfer. Issue #91 commands without a dispatch revision remain executable but
are never treated as resumable.

Partial metadata and interrupted client ownership use a local deadline whose
default is 24 hours. At expiry the client durably records `resume_expired`,
releases its local execution gate, removes the exact job-owned work, and replays
the terminal result when registered. The server remains authoritative for its
canonical assignment until that exact result or later reconciliation is received.
General startup/periodic removal of unreferenced files belongs to Issue #92.
Server restart rebuilds artifact leases from durable job/device rows before
cleanup, so queued, active, reconciling, and resumable assignments keep their
artifact bytes.

## Ownership model

Push/Sync uses three independent ownership mechanisms:

- **Device execution ownership** remains held through validation and apply until a terminal result.
- **Global transfer slot** is released only by the matching `PUSH_TRANSFER_COMPLETE` or a bounded resource-recovery path.
- **Persistent device fence** blocks later jobs after an `unconfirmed` outcome until exact evidence proves the old worker is gone.

The transfer registry uses typed keys. A job-v1 slot is addressed by `(job_id, device_id, attempt=1)`; a stale result cannot release a different job's slot. Install and migration-only legacy Push use the same typed registry and the existing server-wide transfer semaphore.

The device queue is ordered by the server-wide monotonic `enqueue_seq`. A later enabled job cannot jump over an older non-terminal queued assignment merely because the older job is still uploading or has its dispatch gate paused. The console therefore keeps every dispatchable `ready`, `running`, or `reconciling` job with a closed gate in a stable attention panel; an uploaded `ready` job can be dispatched with its existing `job_id` even if the original browser send was lost.

## Client capability and durability

New clients register:

```json
{
  "capabilities": ["push_state_retry_v1", "push_job_id_v1", "push_resume_v1"],
  "process_instance_id": "<process UUIDv4>",
  "push_state": {"status": "available"},
  "push_runtime": {"active": null}
}
```

`PushJobCoordinator` is owned by `MdmClientApplication`, not by a Service instance. It serializes commands on one actor, permits one active Push/Sync worker, persists active state before `PUSH_JOB_ACCEPTED`, and stores terminal results in an outbox until a matching `PUSH_RESULT_ACK` either accepts the result or marks its rejection as permanent with `retryable: false`. An older server that omits `retryable` is treated as retryable. Completed receipts retain the original command metadata for at most 256 entries and seven days. If durable state cannot be saved, the coordinator keeps the rest of the MDM client alive but rejects new work with `client_persistence_unavailable`; it never starts a worker, publishes a phase, releases an execution lease, or sends a terminal result whose required state was not persisted.

`push_state_retry_v1` is advertised even while durable Push state is unavailable. The
console then exposes `Retry Push state` on that online device and a bulk action for all
affected online devices. These actions send only `RETRY_PUSH_STATE`: the coordinator
rereads and republishes its durable state and answers with refreshed capability/state
metadata. An unavailable registration is not authoritative absence evidence. On a
successful retry, exact interrupted identity may be reconciled and requeued, but a
restart-paused job remains paused: the retry path never starts a worker, downloads an
artifact, or wakes the Push scheduler. A separate job `Dispatch` or `Resume` action
remains necessary. If that job was already explicitly resumed, the recovered device
continues under that existing operator authority.

The worker validates destination paths on the device as well as the server. It accepts only a shared-storage subdirectory, rejects protected top-level media/app directories, does not traverse destination symlinks, bounds ZIP entry count and expanded bytes, rejects duplicate or conflicting archive paths, and validates the exact job-v1 artifact size and SHA-256 before publishing the downloaded ZIP or touching the destination. Destination validation completes before the client reports `applying`.

Legacy commands remain supported through the same client execution gate. Legacy downloads may lack `artifact_size` and therefore cannot receive job-v1 exact-size guarantees. Consistent with pre-job-v1 behavior, the 2 GiB bundle limit applies to the uploaded source bytes and the extracted content, not to the ZIP artifact itself; ZIP container overhead may make the downloaded artifact slightly larger.

## State and revision

Each job has a monotonic 64-bit `revision`. Canonical mutations are committed and snapshotted in the same serialized SQLite operation. The current console receives:

- `PUSH_JOBS_SNAPSHOT` after connection,
- `PUSH_JOB_UPDATED` with a full job snapshot.

The server coalesces pending publication by `job_id` and sends only the newest queued revision from a single best-effort publication worker. Admin WebSocket sends are concurrent across connections and individually bounded by `MDM_ADMIN_SEND_TIMEOUT`, so a stalled browser cannot hold a device owner lock or delay another browser. A failed or timed-out admin socket, including one whose initial canonical snapshot cannot be sent, is closed; the current console's existing reconnect loop opens a new socket after three seconds and restores state from a fresh full snapshot. The console treats `PUSH_JOBS_SNAPSHOT` as a full replacement, buffers updates that arrive before that initial snapshot, and ignores revisions that are not newer. Fence create/clear, including an opaque fence, revises every non-terminal job whose snapshot displays that device fence plus the terminal blocking job.

## Scheduling and connection ownership

The exact transfer waiter is registered before `waiting_transfer -> dispatching` commits and before the command is sent. Waiter cleanup is identity-checked, so a superseded dispatch task cannot cancel a replacement task's waiter. The durable `accept_deadline` is swept into short reconciliation only when no live dispatch task still owns the exact in-memory acceptance waiter. The local waiter owns the normal timeout; the durable sweep recovers process/background-task loss. Its transition compares the exact stored deadline in the same transaction, so a stale sweep cannot capture a later replay of the same attempt. After slot acquisition, the scheduler rechecks the live connection and capability. It then holds the same per-device owner lock used by REGISTER replacement and disconnect while performing the final session check and bounded `send_str`.

Job-v1 messages from a device are also checked and settled under that owner lock. Disconnect removes the old owner and commits its canonical queue/reconciliation transition before a replacement REGISTER may acquire ownership. Once a replacement REGISTER owns the device, the superseded socket cannot settle an ACK, phase, transfer completion, result, or reconciliation report. Committed admin snapshots are queued for publication and sent after this correctness path returns; browser backpressure is not part of device ownership.

Artifact URLs in device commands are absolute HTTP(S) URLs derived from the accepted device connection's server authority. The canonical snapshot retains the relative `/artifacts/{artifact_id}` route for console/API use.

## Restart and reconciliation

On server restart:

- `uploading` and `packaging` become `interrupted` and their owned work trees are cleaned;
- expired `created` jobs become `interrupted`;
- `waiting_transfer` returns to `queued`;
- dispatched phases become `reconciling`;
- dispatch is paused and no command is automatically resent;
- the operator re-enables an existing job by using the console's **Dispatch** or **Resume** action, which sends `PUSH_FILES {job_id}` again; this also covers an uploaded `ready` job whose original dispatch send was lost;
- scheduler wake-up immediately follows the durable re-enable and does not wait for its direct admin acknowledgement.

A missing DB-referenced immutable artifact is never substituted. Startup fails the work that still needs that artifact. Canonical UUID-named artifact files with no owning DB row are treated as publication-crash orphans and removed; unknown files are left for the general stale-file policy.

A client process restart retains an Issue #94 job-v1 active record as
`interrupted` without starting work. It advertises the durable identity and
local offset, and waits for an exact server authorization before resuming.
Pre-Issue-94 job-v1 records without an immutable dispatch revision, and legacy
commands, keep the earlier `client_restarted` terminal/outbox cleanup behavior
because they cannot prove safe resume identity. Reconciliation accepts only
exact identity evidence:

- a matching active report restores the reported phase;
- an explicit pre-accept `absent` report requeues the same attempt at most once;
- restart-origin `absent` becomes `interrupted` and is never automatically requeued;
- an elapsed accept deadline enters the short exact reconciliation probe even if its in-memory waiter was lost;
- an elapsed reconciliation deadline becomes `unconfirmed` only if the callback's stored deadline still matches;
- a matching late result or exact `absent|interrupted` clears the fence without rewriting the old terminal job;
- a different recorded job-v1 process UUID proves process replacement; a new job-v1 process that explicitly reports no active execution also safely replaces a legacy or offline-timeout fence that had no process UUID.

An exact terminal result from the current owner settles any dispatched active phase even if an intermediate phase frame was lost during WebSocket replacement. Reconciliation can replay a matching completed receipt after its original result was already ACKed, allowing a server that missed that ACK boundary to converge without rerunning the worker.

## Configuration

| Environment variable | Default | Meaning |
|---|---:|---|
| `MDM_PUSH_CREATE_TIMEOUT` | `600` | Seconds allowed before upload starts |
| `MDM_PUSH_COMMAND_SEND_TIMEOUT` | `5` | Bounded WebSocket send time |
| `MDM_PUSH_COMMAND_ACCEPT_TIMEOUT` | `15` | Wait for accept/reject before probing |
| `MDM_PUSH_ACCEPT_RECONCILIATION_TIMEOUT` | `60` | Pre-accept reconciliation window |
| `MDM_PUSH_RECONCILIATION_TIMEOUT` | `1800` | Accepted-work reconciliation window |
| `MDM_PUSH_RESUME_THRESHOLD_BYTES` | `67108864` | Exact artifact bytes above which every target must advertise `push_resume_v1` |
| `MDM_PUSH_ARTIFACT_RETRY_WINDOW` | `604800` | Seconds terminal artifact bytes remain available for recoverable retry |
| `MDM_PUSH_ARTIFACT_GC_INTERVAL` | `60` | Seconds between lease-aware artifact GC scans |
| `MDM_PUSH_RECENT_JOB_LIMIT` | `100` | Recent terminal snapshots returned |
| `MDM_PUSH_RECENT_JOB_DAYS` | `30` | Recent terminal metadata window |
| `MDM_ALLOW_LEGACY_PUSH` | `1` | Permit restricted legacy fallback |
| `MDM_ADMIN_SEND_TIMEOUT` | `5` | Per-browser WebSocket send timeout in seconds |

`MDM_MAX_CONCURRENT_TRANSFERS` and `MDM_TRANSFER_TIMEOUT` continue to control the shared network resource. Transfer timeout recovers only the slot and moves uncertain work to reconciliation; it does not by itself make a job terminal.

## Deliberate file-layout differences from the proposal

The protocol and behavior follow the Issue #91 design. Three implementation-layout choices differ intentionally:

1. `PushJobManager` is in `push_job_manager.py` rather than `push_jobs.py`. `push_jobs.py` stays a dependency-free domain module used by `push_job_store.py`; importing the SQLite store back into it would create a circular dependency. The manager remains the sole policy layer above the serialized store.
2. `push_runtime.py` is the HTTP/WebSocket compatibility adapter rather than placing all Issue #91 routing into the already-large `server.py`. It installs explicit routes and consumes only job-v1 messages while leaving unrelated and old-client commands on the established server path.
3. The console bridge is `static/push-jobs-v1.js` rather than a large rewrite of inline `index.html`. It intercepts only the old upload/dispatch pair, reconstructs current Push/Sync rows from canonical full snapshots, and preserves the existing picker, target selection, and unrelated operation controls.

On POSIX, both the published file and artifact directory rename are fsynced. Python has no portable directory-fsync handle on Windows, so Windows fsyncs the file and uses atomic `os.replace` but skips only the directory-handle fsync. This platform limitation is explicit rather than allowing artifact publication to fail on every Windows server.

## Message flow

```mermaid
sequenceDiagram
    participant B as Browser
    participant S as Server
    participant DB as SQLite
    participant D as Device

    B->>S: POST /api/push-jobs
    S->>DB: create job + assignments
    S-->>B: job_id, upload_url
    B->>S: POST /api/push-jobs/{job_id}/upload
    S->>DB: uploading -> packaging -> ready
    B->>S: WS PUSH_FILES {job_id}
    S->>DB: dispatch_enabled=1
    S->>DB: queued -> waiting_transfer
    S->>S: acquire shared transfer slot; register exact waiter
    S->>DB: waiting_transfer -> dispatching
    S->>D: EXECUTE_PUSH_FILES(job_id, attempt, absolute artifact URL)
    D->>D: persist active command
    D-->>S: PUSH_JOB_ACCEPTED
    S->>DB: downloading
    D-->>S: PUSH_TRANSFER_COMPLETE
    S->>DB: validating; release exact transfer slot
    D-->>S: DOWNLOAD_COMPLETE
    D-->>S: PUSH_PHASE applying
    S->>DB: applying
    D->>D: persist terminal outbox
    D-->>S: PUSH_FILES_RESULT
    S->>DB: terminal assignment + aggregate job state
    S-->>D: PUSH_RESULT_ACK accepted or permanent/retryable rejection
    D->>D: remove accepted/permanently rejected result; retain retryable result
```
