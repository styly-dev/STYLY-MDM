# Push / Sync jobs

Issue #91 introduces a server-owned job model for **Push Files** and **Sync Folder**. APK install remains on the existing path.

## Identity and persistence

A browser creates a job before uploading bytes:

1. `POST /api/push-jobs` allocates an opaque UUIDv4 `job_id` and atomically records the target snapshot in `DATA_DIR/push_jobs.sqlite3`.
2. `POST /api/push-jobs/{job_id}/upload` writes into `push-work/{job_id}`, packages the upload, fsyncs it, and publishes an immutable `push-artifacts/{artifact_id}.zip`.
3. Admin WebSocket `PUSH_FILES {job_id}` enables dispatch. Destination, mode, targets, and artifact data are read from SQLite rather than trusted from a second browser message.

`client_request_id` makes job creation idempotent. The server looks up an existing request **before** live-target preflight, so loss of the first response followed by a device disconnect still returns the original job. The canonical fingerprint sorts targets, normalizes the shared-storage destination, and hashes stable JSON. Reusing an ID with different canonical data returns `409 Conflict`.

Issue #94 upgrades the released Issue #91 `push_jobs.sqlite3` schema directly
from version 1 to version 3. It adds an immutable per-device dispatch revision
and nullable `cancel_requested_at` without discarding existing jobs. The Issue
#91 server rejects any schema version other than 1 at startup. Rollback to that
server therefore requires restoring a matching pre-upgrade database backup;
the schema-3 database cannot be used by the old server. Schema 2 was never
released.

## Resumable artifact transfer

Published bytes are immutable for the lifetime of an opaque `artifact_id`. The
SQLite artifact row stores the unique storage name, exact byte size, SHA-256,
creation time, and retention state. The SHA-256 is also exposed as a quoted
strong ETag. Garbage collection leaves a tombstone row, so neither an artifact
ID nor its `/artifacts/{artifact_id}` URL can be reused after its bytes expire.

Job-v1 commands carry an immutable per-device `revision`, `artifact_id`, absolute
URL, exact size, SHA-256, and ETag. The aggregate job revision may continue to
advance; the dispatch revision remains fixed for one `(job_id, device_id,
attempt)` and is the exact identity used when authorizing a resume. Large artifacts
require `push_resume_v1`; every Push job target must advertise `push_job_id_v1`.
The exact artifact size is checked after packaging and again at dispatch, so ZIP
container overhead cannot bypass capability admission. The legacy job fallback is
disabled and clients without `push_job_id_v1` are rejected as job targets. Standalone
Push through `/api/bundles` remains a separate legacy flow.

Each job-v1 artifact URL includes a random, in-memory `lease` token scoped to its
exact assignment. The server accepts the URL only while that assignment owns a
transfer slot. A missing, stale, or revoked token returns `409` with
`X-Push-Lease-Status: revoked`; the client keeps its validated partial and reports
interrupted work for manual Resume. Resume issues a new URL and token. The token is
not restored after a server restart, so the old URL is rejected and a partial
download must be manually resumed after the device reports the revoked lease.
If a device reconnects with the same live token while its earlier HTTP handler
is still open, the newer request aborts and replaces that handler. It can then
continue with a Range request under the same assignment and transfer slot.

Creation rejects a declared source size above the threshold for a target without
resume support. Packaging checks the final ZIP size against the stored target
capabilities before publishing the artifact; if it crosses the threshold, upload
fails with `artifact_requires_push_resume_v1`. Dispatch checks the final size
again against the live session, which may have changed since creation.

The client keeps job-owned state below
`Downloads/styly-mdm/.push-tmp/jobs/{job_id}/{attempt}/`:

- `artifact.part` is the only authoritative byte offset;
- atomically replaced `metadata.json` binds that file to the job, attempt,
  dispatch revision, artifact ID, size, SHA-256, strong ETag, and retention
  timestamps. The URL is a replaceable locator refreshed by each exact server
  authorization, so a server authority change does not invalidate the artifact;
- `artifact.zip` appears only after exact-size and SHA-256 verification.

Unknown, malformed, or mismatched metadata is never appended to. Expired
interrupted ownership is rejected by the coordinator before starting a worker. A
validated partial of `N > 0` bytes is requested with `Range: bytes=N-`,
`If-Match: <strong-etag>`, and `Accept-Encoding: identity`.

Registration and reconciliation report a nonzero `validated_offset` only when
the same worker metadata checks accept the partial identity, a strong ETag is
available, and its length is within the expected artifact size. This is a resume
offset, not a completed SHA-256 verification. The server sends unencoded bytes
without a `Content-Encoding` header; the client still accepts older servers that
explicitly send `Content-Encoding: identity`.

`validated_offset` is reconciliation state, not live console byte progress. Wiring
the worker's progress callback and maintaining monotonic displayed progress across
an ignored-Range full restart remain part of Issue #85.

Response handling is fail-closed:

- `206` is appended only when ETag, `Content-Range`, total size, and
  `Content-Length` describe a range starting at the requested offset, with its
  inclusive end strictly below the total size. A shorter valid range may be
  continued; a non-identity content encoding is rejected before writing;
- `200` is never appended; with the same validator it replaces the partial from
  byte zero;
- `412` is an artifact-identity failure;
- `416` is accepted only when `Content-Range: bytes */T` proves the local file is
  already the exact complete artifact;
- `404` and `410` are explicit artifact-unavailable failures;
- `409` with `X-Push-Lease-Status: revoked` ends this attempt as interrupted,
  preserving the validated partial for manual Resume;
- transient I/O, `408`, `429`, and 5xx failures keep validated partial bytes and
  retry with bounded 1/2/4/8-second backoff while the 60-second no-progress
  window remains open. Only received artifact bytes reset this monotonic deadline;
  connection establishment and response headers do not. Blocked HTTP operations
  are cancelled when the window expires. Management WebSocket loss alone does not
  stop a download that is still making progress. Local file write or sync failures
  terminate with `storage_write_failed` instead of retrying the network request.

The client deadline measures bytes received and saved on the device; the server
measures bytes successfully written to the HTTP response. These clocks can differ
because of buffering. The server renews the job-v1 lease only after successful HTTP
writes. After 60 seconds without such progress, it revokes the URL, aborts and awaits
the matching HTTP handler, then releases that assignment's slot. A healthy transfer
can continue beyond the previous 600-second timeout. Once revoked, the old URL cannot
reconnect without a new operator-authorized Resume, so it cannot restart outside the
slot limit. APK install and standalone Push through `/api/bundles` retain their
existing timeout behavior; neither is a job-v1 fallback.

If no artifact bytes arrive for 60 seconds, a resumable client persists interrupted
ownership with `reason: download_retry_exhausted` instead of creating a terminal
receipt or deleting the partial. It includes that reason in reconciliation and
registration reports. The server validates the exact assignment, atomically
requeues it with per-device `queue_reason: download_retry_exhausted`.
When the client reports `server_lease_revoked`, the server maps it to the same
per-device manual Resume gate.
The console exposes **Resume** while the job's dispatch gate remains enabled.
Only that assignment waits for authorization; other queued and running devices continue.
Reconnect and duplicate reports cannot clear this manual requirement.
Resume authorizes a fresh 60-second no-progress window with the same identity and offset.
Download completion ends this timer; validation and destination apply may continue
without the management connection. Application restart requires manual Resume
(`client_restarted`) instead of granting a fresh automatic recovery window.
Repeated reports while queued, waiting for a slot, or dispatching are no-ops:
they neither re-pause the job nor release the replacement transfer slot.

Deploy the server and matching client update together. This rollout assumes there is
no active Push/Sync transfer on the old APK; old unscoped job-v1 artifact URLs are
intentionally invalid after cutover. No compatibility machinery is kept for an
in-flight old job-v1 download. APK install and standalone `/api/bundles` Push are
unchanged. Cancellation intent requires schema 3 as described below; no new device
WebSocket message type is required.

Extraction and destination apply start only after size and SHA-256 verification
and an atomic local rename. A hash mismatch removes the untrusted partial.

On process or device restart, the client retains a valid interrupted Issue #94 job-v1
record and reports its exact artifact, dispatch revision, attempt, and local
offset during registration/reconciliation. The server requeues it only when that
evidence matches the durable assignment and its recorded `push_resume_v1`
capability. Restarted work also waits for manual Resume before the server sends
a fresh `EXECUTE_PUSH_FILES` authorization. The client
never resumes, extracts, or applies stale partial work merely because it exists.
If exact evidence cannot be authorized, the server sends
`PUSH_RESUME_REJECTED`; the client durably releases only that exact interrupted
identity, removes its owned work, and confirms `absent`. A verified
`artifact.zip` surviving a restart is hash-checked and reused without network
transfer. Issue #91 commands without a dispatch revision remain executable but
are never treated as resumable. The current server does not issue those legacy
commands as Push-job fallback; standalone `/api/bundles` Push remains a separate path.

Interrupted client ownership uses a local deadline whose default is 24 hours,
measured from the first interruption rather than the start of a long download.
The coordinator's `interruptedAt` is the only expiration authority; the older
metadata `retention_deadline` remains for file-format compatibility but cannot
discard a freshly authorized partial. Reauthorization does not reset the first interruption time.
At expiry the client durably records `resume_expired`,
releases its local execution gate, removes the exact job-owned work, and replays
the terminal result when registered. The server remains authoritative for its
canonical assignment until that exact result or later reconciliation is received.
An expired resumed assignment can settle while still queued; it does not require
another download dispatch to release server ownership.
General startup/periodic removal of unreferenced files belongs to Issue #92.
Server restart rebuilds artifact-retention references from durable job/device rows
before cleanup, so queued, active, reconciling, and resumable assignments keep their
artifact bytes. In-memory HTTP transfer tokens are deliberately not recovered:
restart invalidates old URLs, and the client retains its partial and waits for manual
Resume before receiving a new token.

## Operator controls

- **Resume** is available only for online devices. **Resume all** excludes offline
  devices, preserving their manual wait and Needs attention entry. Offline timed-out
  devices support **Cancel** only; reconnect alone never grants resume permission.
- **Resume** clears current per-device retry-exhaustion waits and reopens a
  restart-paused dispatch gate. It does not rerun terminal failures, reset the first
  interruption timestamp, or authorize new interruptions reported after the action.
  Each device's Progress cell exposes its own Resume/Cancel actions, which send
  `target_devices: [device_id]`. Omitting targets means all applicable assignments;
  empty, malformed or foreign targets are rejected. The attention panel retains
  **Resume all**. Resuming one device after a server restart keeps all other queued
  or interrupted devices paused until their own Resume or Resume all.
  A storage write failure with an exact retained partial uses the same manual
  Resume gate; the operator must free or repair device storage first.
- **Cancel** persists an operator cancellation request for interrupted or unresolved
  work, including offline status-confirmation waits. It revokes a still-active
  HTTP lease and stops its stream even when the management WebSocket is offline.
  An online active worker is not directly cancellable. Pending cancellation disappears from Needs attention because no
  further operator action is needed, but remains visible in Devices. Ownership and
  fences remain until exact absence or a terminal result confirms release; the next
  job for that device cannot start earlier. When the client reports interrupted work,
  the server sends the existing `PUSH_RESUME_REJECTED` with exact identity to discard
  it. Reconnection never authorizes a cancelled assignment. Already-applied files
  are not rolled back. Work already received and validating on an offline device
  may finish before cancellation is observed. Individual **Cancel** and **Cancel all**
  ask for confirmation; the latter shows the number of affected devices. Only
  previously dispatched `dispatch_paused` assignments can be cancelled.
- **Retry failed devices** creates and dispatches a new job for `failed`,
  `interrupted`, and `unconfirmed` targets, excluding `failure_code: cancelled`.
  It reuses the immutable artifact without upload and leaves success records and
  device fences intact. Offline targets wait in the new queue; fenced targets wait
  for evidence that the previous worker is gone. Expired/missing artifacts require
  a new upload. The request UUID makes replay idempotent. Shared artifact retention
  is measured from the latest referencing terminal job, with active leases preserved.
  A target already included in a retry job is excluded from the original job's
  attention and retry actions. History remains; any new failure belongs to the retry job.
- Offline task cells show **Job pending · offline** or **Resume required**,
  rather than claiming active pushing. Reconciliation
  shows **Awaiting status confirmation**, with **offline** appended when disconnected;
  unconfirmed outcomes show **Status unknown**. This is distinct from job history.
  Canonical devices needing operator attention also appear in **Needs attention**
  while remaining in **Devices**. This list is derived from current job state and
  disappears as action requirements clear; confirmed cancellation and success do
  not leave stale entries. These registered devices are separate from provisional
  connection rows and never become provisional power-control targets.

The 60-second deadline requires the matching Android APK; older clients retain
their previous retry timing. Deploy the server and APK together while the old APK
has no active Push/Sync transfer: its unscoped job-v1 artifact URLs are rejected
after cutover.
`CANCEL_PUSH_JOB` remains an admin action; clients receive the existing exact-identity
`PUSH_RESUME_REJECTED` only after they report interrupted work. Older job-v1 APKs
omit `artifact_id` from an `absent` reconciliation report; the server accepts that
omission only from the current device owner for the exact job and attempt. An
explicit mismatched artifact ID never confirms cancellation.

APK installation is a separate client worker and can overlap Push/Sync when a shared
network slot is available. Reboot does not wait for a transfer slot but requires a
live eligible connection. Neither operation requires this Cancel action.

## Ownership model

Push/Sync uses three independent ownership mechanisms:

- **Device execution ownership** remains held through validation and apply until a terminal result.
- **Global transfer slot** is independent of the device WebSocket. A job-v1 slot is released when the client reports `PUSH_PHASE validating` after receiving all artifact bytes, or on a matching terminal result; `PUSH_TRANSFER_COMPLETE` remains the post-SHA-256 checkpoint. If HTTP writes stop for 60 seconds before validation starts, the server revokes the assignment URL, aborts and awaits that HTTP handler, then releases the slot. Healthy job-v1 HTTP transfers have no 600-second cap.
- **Persistent device fence** blocks later jobs after an `unconfirmed` outcome until exact evidence proves the old worker is gone.

The transfer registry uses typed keys. A job-v1 slot is addressed by `(job_id, device_id, attempt=1)`; a stale result cannot release a different job's slot. Job-v1 uses the existing server-wide transfer semaphore. APK install and standalone `/api/bundles` Push remain on their separate legacy path and keep its existing slot handling. Job-v1 HTTP URLs carry an in-memory assignment token; server restart invalidates it, so manual Resume is required to issue a replacement.

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

`PushJobCoordinator` is owned by `MdmClientApplication`, not by a Service instance. It serializes commands on one actor, permits one active Push/Sync worker, persists active state before `PUSH_JOB_ACCEPTED`, and stores terminal results in an outbox until a matching `PUSH_RESULT_ACK` either accepts the result or marks its rejection as permanent with `retryable: false`. An older server that omits `retryable` is treated as retryable. Completed receipts retain the original command metadata for at most 256 entries and seven days. A missing state file is initialized as an empty state, while malformed JSON or malformed active/receipt entries are classified as corrupt, left untouched, and registered as unavailable rather than authoritative absence. If durable state cannot be loaded or saved, the coordinator keeps the rest of the MDM client alive but rejects new work with `client_persistence_unavailable`; it never starts a worker, publishes a phase, releases an execution lease, or sends a terminal result whose required state was not persisted.

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

Push jobs require a registered GUID client advertising `push_job_id_v1`; large job artifacts also require `push_resume_v1`. The legacy job fallback is disabled. The standalone `/api/bundles` Push flow still uses the legacy `EXECUTE_PUSH_FILES` format and has no job-v1 exact-size, resume, or HTTP-lease guarantees. Legacy serial-ID clients remain eligible only for APK installation/update. Consistent with the standalone flow's prior behavior, its 2 GiB bundle limit applies to uploaded source bytes and extracted content, not the ZIP artifact; container overhead may make the downloaded artifact slightly larger.

Finish pending serial-ID Push/Sync jobs with the previous server before upgrading
to GUID identity. Old assignments are not migrated or resumed on serial clients;
queued assignments may remain pending. Retain those records and create a new job
for the registered GUID if delivery is still needed. A database reset is not
required, and an old pending record is not evidence of successful delivery.

## State and revision

Each job has a monotonic 64-bit `revision`. Canonical mutations are committed and snapshotted in the same serialized SQLite operation. The current console receives:

- `PUSH_JOBS_SNAPSHOT` after connection,
- `PUSH_JOB_UPDATED` with a full job snapshot.

The server coalesces pending publication by `job_id` and sends only the newest queued revision from a single best-effort publication worker. Admin WebSocket sends are concurrent across connections and individually bounded by `MDM_ADMIN_SEND_TIMEOUT`, so a stalled browser cannot hold a device owner lock or delay another browser. A failed or timed-out admin socket, including one whose initial canonical snapshot cannot be sent, is closed; the current console's existing reconnect loop opens a new socket after three seconds and restores state from a fresh full snapshot. The console treats `PUSH_JOBS_SNAPSHOT` as a full replacement, buffers updates that arrive before that initial snapshot, and ignores revisions that are not newer. Fence create/clear, including an opaque fence, revises every non-terminal job whose snapshot displays that device fence plus the terminal blocking job.

## Scheduling and connection ownership

The exact transfer waiter is registered before `waiting_transfer -> dispatching` commits and before the command is sent. Waiter cleanup is identity-checked, so a superseded dispatch task cannot cancel a replacement task's waiter. The durable `accept_deadline` is swept into short reconciliation only when no live dispatch task still owns the exact in-memory acceptance waiter. The local waiter owns the normal timeout; the durable sweep recovers process/background-task loss. Its transition compares the exact stored deadline in the same transaction, so a stale sweep cannot capture a later replay of the same attempt. After slot acquisition, the scheduler rechecks the live connection and capability. It then holds the same per-device owner lock used by REGISTER replacement and disconnect while performing the final session check and bounded `send_str`.

Job-v1 messages from a device are also checked and settled under that owner lock. Disconnect removes the old owner and commits its canonical queue/reconciliation transition before a replacement REGISTER may acquire ownership, but it does not release an active Push transfer slot because the Android HTTP worker outlives the WebSocket. An exact active `downloading` report preserves ownership of an in-process live HTTP lease and also satisfies a still-live acceptance waiter. After a server restart, no HTTP token or active stream is recovered; the old URL is rejected and the client must report interrupted work before manual Resume can issue a fresh token and slot. The server maps `server_lease_revoked` to the existing per-device `download_retry_exhausted` Resume gate. Once a replacement REGISTER owns the device, the superseded socket cannot settle an ACK, phase, transfer completion, result, or reconciliation report. Committed admin snapshots are queued for publication and sent after this correctness path returns; browser backpressure is not part of device ownership.

Artifact URLs in device commands are absolute HTTP(S) URLs derived from the accepted device connection's server authority and include a per-assignment `lease` query token. The canonical snapshot retains the relative `/artifacts/{artifact_id}` route for console/API use; it is not a downloadable job-v1 URL without an active lease.

## Restart and reconciliation

On server restart:

- `uploading` and `packaging` become `interrupted` and their owned work trees are cleaned;
- expired `created` jobs become `interrupted`;
- `waiting_transfer` returns to `queued`;
- dispatched phases become `reconciling`;
- active download URLs are not recovered after process restart; the old URL is rejected and the device must report `server_lease_revoked` before manual Resume can issue a new lease. The server maps this reason to the existing `download_retry_exhausted` gate;
- dispatch is paused and no command is automatically resent;
- the operator re-enables an existing job through **Resume** or **Resume all** in Needs attention, which sends `PUSH_FILES` for the selected target devices; this also covers an uploaded `ready` job whose original dispatch send was lost;
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
- an elapsed reconciliation deadline becomes `unconfirmed` only if the callback's stored deadline still matches and no live HTTP lease exists. A live lease defers reconciliation expiry; its watchdog owns the 60-second no-write timeout and refreshes the reconciliation deadline before revoking the lease;
- a matching late result or exact `absent|interrupted` clears the fence without rewriting the old terminal job, except that an exact result for an assignment with pending cancellation records its actual success or failure (an explicit cancellation receipt records `cancelled`);
- a different recorded job-v1 process UUID proves process replacement; a new job-v1 process that explicitly reports no active execution also safely replaces a legacy or offline-timeout fence that had no process UUID.

An exact terminal result from the current owner settles any dispatched active phase even if an intermediate phase frame was lost during WebSocket replacement. Reconciliation can replay a matching completed receipt after its original result was already ACKed, allowing a server that missed that ACK boundary to converge without rerunning the worker.

## Configuration

| Environment variable | Default | Meaning |
|---|---:|---|
| `MDM_PUSH_CREATE_TIMEOUT` | `600` | Seconds allowed before upload starts |
| `MDM_PUSH_COMMAND_SEND_TIMEOUT` | `5` | Bounded WebSocket send time |
| `MDM_PUSH_COMMAND_ACCEPT_TIMEOUT` | `15` | Wait for accept/reject before probing |
| `MDM_PUSH_ACCEPT_RECONCILIATION_TIMEOUT` | `60` | Pre-accept reconciliation window |
| `MDM_PUSH_RECONCILIATION_TIMEOUT` | `1800` | Accepted-work reconciliation window when no live HTTP transfer lease is active |
| `MDM_PUSH_RESUME_THRESHOLD_BYTES` | `67108864` | Exact artifact bytes above which every target must advertise `push_resume_v1` |
| `MDM_PUSH_ARTIFACT_RETRY_WINDOW` | `604800` | Seconds terminal artifact bytes remain available for recoverable retry |
| `MDM_PUSH_ARTIFACT_GC_INTERVAL` | `60` | Seconds between lease-aware artifact GC scans |
| `MDM_PUSH_RECENT_JOB_LIMIT` | `100` | Recent terminal snapshots returned |
| `MDM_PUSH_RECENT_JOB_DAYS` | `30` | Recent terminal metadata window |
| `MDM_ADMIN_SEND_TIMEOUT` | `5` | Per-browser WebSocket send timeout in seconds |

`MDM_MAX_CONCURRENT_TRANSFERS` controls the shared network resource. `MDM_TRANSFER_TIMEOUT` (default 600 seconds) remains the fallback for APK install and standalone `/api/bundles` Push. Job-v1 Push instead releases a stalled slot only after 60 seconds without successful HTTP response writes, after revoking the URL and stopping the exact handler; successful writes keep healthy transfers alive beyond 600 seconds.

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
    S->>D: EXECUTE_PUSH_FILES(job_id, attempt, artifact URL + lease token)
    D->>D: persist active command
    D-->>S: PUSH_JOB_ACCEPTED
    S->>DB: downloading
    D->>S: HTTP Range + If-Match + identity encoding + lease token
    opt No successful HTTP writes for 60 seconds
        S->>S: revoke token; abort and await exact handler; release exact slot
        D->>S: retry old URL + revoked token
        S-->>D: HTTP 409 X-Push-Lease-Status: revoked
        Note over S,D: Keep partial; manual Resume issues a new URL and lease
    end
    opt Server restarts while the device keeps downloading
        S->>S: discard in-memory lease; do not reissue the old URL
        D->>S: retry old URL + revoked token
        S-->>D: HTTP 409 X-Push-Lease-Status: revoked
        D-->>S: PUSH_RECONCILE_REPORT interrupted + server_lease_revoked + offset
        S->>DB: map to existing per-device manual Resume gate
        Note over S,D: Retain partial; Resume creates a new lease and reserves a slot
    end
    opt No artifact bytes received for 60 seconds
        D->>D: persist interruption reason; retain partial and original expiry
        D-->>S: PUSH_RECONCILE_REPORT interrupted + exact identity + offset
        S->>DB: requeue only this device with manual Resume required
        S-->>B: snapshot: download_retry_exhausted
        Note over S,D: Repeated waiting reports retain work and replacement slots
        Note over S,D: Other devices continue; reconnect alone cannot resume this one
        B->>S: Resume interrupted devices in existing job
        S->>D: EXECUTE_PUSH_FILES with same assignment revision
        D->>S: HTTP Range + If-Match + identity encoding
    end
    D-->>S: PUSH_PHASE validating (all bytes received, before SHA-256)
    S->>DB: validating; release exact transfer slot
    D->>D: verify exact size and SHA-256
    D-->>S: PUSH_TRANSFER_COMPLETE
    D-->>S: DOWNLOAD_COMPLETE
    D-->>S: PUSH_PHASE applying
    S->>DB: applying
    D->>D: persist terminal outbox
    D-->>S: PUSH_FILES_RESULT
    S->>DB: terminal assignment + aggregate job state
    S-->>D: PUSH_RESULT_ACK accepted or permanent/retryable rejection
    D->>D: remove accepted/permanently rejected result; retain retryable result
    opt Operator cancels interrupted or unresolved offline work
        B->>S: CANCEL_PUSH_JOB(job_id, target_devices)
        S->>DB: persist cancel request; retain ownership until confirmed
        S->>D: PUSH_RESUME_REJECTED(exact identity), on reconnect if offline
        D->>D: clear interrupted ownership; retain receipt; queue partial cleanup
        D-->>S: PUSH_RECONCILE_REPORT(absent)
        S->>DB: settle cancelled; allow next job
    end
    opt Operator retries failed targets
        B->>S: RETRY_FAILED_PUSH_JOB(job_id, request UUID)
        S->>DB: new job + unsuccessful targets + shared artifact lease
        Note over S,D: Successful/cancelled targets excluded; fences remain
    end
```

Activity log is read-only history. Needs attention contains Resume all, Cancel
all, Retry failed devices, and Reconcile; eligible device rows have individual
Resume and Cancel controls.
