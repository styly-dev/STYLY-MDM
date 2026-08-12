# Push / Sync jobs

Issue #91 introduces a server-owned job model for **Push Files** and **Sync Folder**. APK install remains on the existing path.

## Identity and persistence

A browser creates a job before uploading bytes:

1. `POST /api/push-jobs` allocates an opaque UUIDv4 `job_id` and atomically records the target snapshot in `DATA_DIR/push_jobs.sqlite3`.
2. `POST /api/push-jobs/{job_id}/upload` writes into `push-work/{job_id}`, packages the upload, fsyncs it, and publishes an immutable `push-artifacts/{artifact_id}.zip`.
3. Admin WebSocket `PUSH_FILES {job_id}` enables dispatch. Destination, mode, targets, and artifact data are read from SQLite rather than trusted from a second browser message.

`client_request_id` makes job creation idempotent. The server looks up an existing request **before** live-target preflight, so loss of the first response followed by a device disconnect still returns the original job. The canonical fingerprint sorts targets, normalizes the shared-storage destination, and hashes stable JSON. Reusing an ID with different canonical data returns `409 Conflict`.

## Ownership model

Push/Sync uses three independent ownership mechanisms:

- **Device execution ownership** remains held through validation and apply until a terminal result.
- **Global transfer slot** is released only by the matching `PUSH_TRANSFER_COMPLETE` or a bounded resource-recovery path.
- **Persistent device fence** blocks later jobs after an `unconfirmed` outcome until exact evidence proves the old worker is gone.

The transfer registry uses typed keys. A job-v1 slot is addressed by `(job_id, device_id, attempt=1)`; a stale result cannot release a different job's slot. Install and migration-only legacy Push use the same typed registry and the existing server-wide transfer semaphore.

The device queue is ordered by the server-wide monotonic `enqueue_seq`. A later enabled job cannot jump over an older non-terminal queued assignment merely because the older job is still uploading or has its restart dispatch gate paused.

## Client capability and durability

New clients register:

```json
{
  "capabilities": ["push_job_id_v1"],
  "process_instance_id": "<process UUIDv4>",
  "push_runtime": {"active": null}
}
```

`PushJobCoordinator` is owned by `MdmClientApplication`, not by a Service instance. It serializes commands on one actor, permits one active Push/Sync worker, persists active state before `PUSH_JOB_ACCEPTED`, and stores terminal results in an outbox until a matching `PUSH_RESULT_ACK` either accepts the result or marks its rejection as permanent with `retryable: false`. An older server that omits `retryable` is treated as retryable. Completed receipts retain the original command metadata for at most 256 entries and seven days.

The worker validates destination paths on the device as well as the server. It accepts only a shared-storage subdirectory, rejects protected top-level media/app directories, does not traverse destination symlinks, bounds ZIP entry count and expanded bytes, rejects duplicate or conflicting archive paths, and validates the exact job-v1 artifact size and SHA-256 before publishing the downloaded ZIP or touching the destination. Destination validation completes before the client reports `applying`.

Legacy commands remain supported through the same client execution gate. Legacy downloads may lack `artifact_size`; they remain bounded by the server/client size limit but cannot receive job-v1 exact-size guarantees.

## State and revision

Each job has a monotonic 64-bit `revision`. Canonical mutations are committed and snapshotted in the same serialized SQLite operation. The current console receives:

- `PUSH_JOBS_SNAPSHOT` after connection,
- `PUSH_JOB_UPDATED` with a full job snapshot.

The server coalesces pending publication by `job_id` and sends only the newest queued revision from a single best-effort publication worker. Admin WebSocket sends are concurrent across connections and individually bounded by `MDM_ADMIN_SEND_TIMEOUT`, so a stalled browser cannot hold a device owner lock or delay another browser. The console treats `PUSH_JOBS_SNAPSHOT` as a full replacement, buffers updates that arrive before that initial snapshot, and ignores revisions that are not newer. Fence create/clear, including an opaque fence, revises every non-terminal job whose snapshot displays that device fence plus the terminal blocking job.

## Scheduling and connection ownership

The exact transfer waiter is registered before `waiting_transfer -> dispatching` commits and before the command is sent. After slot acquisition, the scheduler rechecks the live connection and capability. It then holds the same per-device owner lock used by REGISTER replacement and disconnect while performing the final session check and bounded `send_str`.

Job-v1 messages from a device are also checked and settled under that owner lock. Once a replacement REGISTER owns the device, the superseded socket cannot settle an ACK, phase, transfer completion, result, or reconciliation report. Committed admin snapshots are queued for publication and sent after this correctness path returns; browser backpressure is not part of device ownership.

Artifact URLs in device commands are absolute HTTP(S) URLs derived from the accepted device connection's server authority. The canonical snapshot retains the relative `/artifacts/{artifact_id}` route for console/API use.

## Restart and reconciliation

On server restart:

- `uploading` and `packaging` become `interrupted` and their owned work trees are cleaned;
- expired `created` jobs become `interrupted`;
- `waiting_transfer` returns to `queued`;
- dispatched phases become `reconciling`;
- dispatch is paused and no command is automatically resent;
- the operator resumes queued assignments by sending `PUSH_FILES {job_id}` again.

A missing DB-referenced immutable artifact is never substituted. Startup fails the work that still needs that artifact. Canonical UUID-named artifact files with no owning DB row are treated as publication-crash orphans and removed; unknown files are left for the general stale-file policy.

A client process restart converts a persisted active record into an interrupted terminal result, commits it to the outbox, then removes the attempt work directory after the worker's handles are closed. Reconciliation accepts only exact identity evidence:

- a matching active report restores the reported phase;
- an explicit pre-accept `absent` report requeues the same attempt at most once;
- restart-origin `absent` becomes `interrupted` and is never automatically requeued;
- an elapsed deadline becomes `unconfirmed` only if the callback's stored deadline still matches;
- a matching late result or exact `absent|interrupted` clears the fence without rewriting the old terminal job;
- a different job-v1 process UUID proves process replacement; a new job-v1 process also safely replaces a legacy fence that had no process UUID.

## Configuration

| Environment variable | Default | Meaning |
|---|---:|---|
| `MDM_PUSH_CREATE_TIMEOUT` | `600` | Seconds allowed before upload starts |
| `MDM_PUSH_COMMAND_SEND_TIMEOUT` | `5` | Bounded WebSocket send time |
| `MDM_PUSH_COMMAND_ACCEPT_TIMEOUT` | `15` | Wait for accept/reject before probing |
| `MDM_PUSH_ACCEPT_RECONCILIATION_TIMEOUT` | `60` | Pre-accept reconciliation window |
| `MDM_PUSH_RECONCILIATION_TIMEOUT` | `1800` | Accepted-work reconciliation window |
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
