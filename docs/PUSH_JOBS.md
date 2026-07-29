# Push / Sync jobs

Issue #91 introduces a server-owned job model for **Push Files** and **Sync Folder**. APK install remains on the existing path.

## Identity and persistence

A browser creates a job before uploading bytes:

1. `POST /api/push-jobs` allocates an opaque UUID `job_id` and atomically records the target snapshot in `DATA_DIR/push_jobs.sqlite3`.
2. `POST /api/push-jobs/{job_id}/upload` writes into `push-work/{job_id}`, packages the upload, fsyncs it, and publishes an immutable `push-artifacts/{artifact_id}.zip`.
3. Admin WebSocket `PUSH_FILES {job_id}` enables dispatch. All destination, mode, target, and artifact data are read from SQLite rather than trusted from a second browser message.

`client_request_id` makes job creation idempotent. The canonical fingerprint sorts targets, normalizes the shared-storage destination, and hashes stable JSON. Reusing an ID with different canonical data returns `409 Conflict`.

## Ownership model

Push/Sync uses three independent ownership mechanisms:

- **Device execution ownership** remains held through validation and apply until a terminal result.
- **Global transfer slot** is released only by the matching `PUSH_TRANSFER_COMPLETE` (or bounded resource-recovery fallback).
- **Persistent device fence** blocks later jobs after an `unconfirmed` outcome until exact evidence proves the old worker is gone.

The server transfer registry uses typed keys. A job-v1 push slot is addressed by `(job_id, device_id, attempt=1)`; a stale result cannot release a different job's slot.

## Client capability and durability

New clients register:

```json
{
  "capabilities": ["push_job_id_v1"],
  "process_instance_id": "<process UUID>",
  "push_runtime": {"active": null}
}
```

`PushJobCoordinator` is owned by `MdmClientApplication`, not by a Service instance. It serializes commands on one actor, permits one active Push/Sync worker, persists active state before `PUSH_JOB_ACCEPTED`, and stores terminal results in an outbox until a matching `PUSH_RESULT_ACK` arrives. Completed receipts retain the original command metadata so an exact duplicate is replayed without applying twice, while the same identity with changed artifact or destination is rejected.

Legacy commands remain supported through the same client execution gate. A legacy command has no exact identity, so overlapping legacy work is reported as busy instead of being guessed to be a duplicate.

## State and revision

Each job has a monotonic 64-bit `revision`. Canonical mutations are committed and snapshotted in the same serialized SQLite operation. Admins receive:

- `PUSH_JOBS_SNAPSHOT` after connection,
- `PUSH_JOB_UPDATED` with a full job snapshot,
- compatibility-derived `PUSH_PROGRESS` and `PUSH_DEVICE_STATE` events carrying the same `job_id` and revision.

The console stores snapshots in a Map keyed by `job_id` and ignores events whose revision is not newer.

## Restart and reconciliation

On server restart:

- `uploading` and `packaging` become `interrupted`;
- `waiting_transfer` returns to `queued`;
- dispatched phases become `reconciling`;
- dispatch is paused and no command is automatically resent;
- the operator resumes queued assignments by sending `PUSH_FILES {job_id}` again.

A client process restart converts a persisted active record into an interrupted terminal result and replays it from the durable outbox. Reconciliation accepts only exact identity evidence. `absent` or `interrupted` may clear a matching fence; an unrelated or malformed report cannot.

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

`MDM_MAX_CONCURRENT_TRANSFERS` and `MDM_TRANSFER_TIMEOUT` continue to control the shared network resource. A transfer timeout recovers only the slot; it does not by itself mark a job failed.

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
    S->>DB: dispatch_enabled=1, queued -> waiting_transfer -> dispatching
    S->>D: EXECUTE_PUSH_FILES(job_id, attempt, artifact)
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
    S-->>D: PUSH_RESULT_ACK
    D->>D: remove matching outbox entry
```
