package com.styly.mdmclient

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal fun persistPushStateBeforePublishing(
    nextState: PushProtocol.State,
    save: (PushProtocol.State) -> PushProtocol.State,
    afterPublish: () -> Unit = {},
    onFailure: (Throwable) -> Unit = {},
    publish: (PushProtocol.State) -> Unit,
): Boolean {
    val persisted = try {
        save(nextState)
    } catch (error: Throwable) {
        onFailure(error)
        return false
    }
    publish(persisted)
    afterPublish()
    return true
}

internal fun applyPushResultAckToState(
    current: PushProtocol.State,
    ack: PushProtocol.ResultAck,
    maxReceipts: Int,
): PushProtocol.State {
    if (!ack.accepted && ack.retryable) return current
    fun matches(receipt: PushProtocol.Receipt): Boolean =
        receipt.result.jobId == ack.jobId && receipt.result.attempt == ack.attempt

    val settled = current.pendingResults.lastOrNull(::matches) ?: return current
    return current.copy(
        pendingResults = current.pendingResults.filterNot(::matches),
        completedReceipts = (current.completedReceipts.filterNot(::matches) + settled)
            .takeLast(maxReceipts),
    )
}

internal fun findPushReconcileReceipt(
    state: PushProtocol.State,
    matches: (PushProtocol.Command) -> Boolean,
): PushProtocol.Receipt? = state.pendingResults.firstOrNull { matches(it.command) }
    ?: state.completedReceipts.firstOrNull { matches(it.command) }

internal fun buildActivePushReconcileReport(
    identity: PushProtocol.ReconcileIdentity,
    active: PushProtocol.Active,
    validatedOffset: Long,
): JSONObject = JSONObject().apply {
    put("type", "PUSH_RECONCILE_REPORT")
    put("job_id", identity.jobId)
    put("attempt", identity.attempt)
    put("artifact_id", active.command.artifactId)
    put("status", if (active.interrupted) "interrupted" else "active")
    put("phase", active.phase)
    put("revision", active.command.revision)
    put("validated_offset", validatedOffset)
}

internal fun applyPushResumeRejectionToState(
    current: PushProtocol.State,
    jobId: String,
    attempt: Int,
    artifactId: String,
    revision: Long,
    reason: String,
    detail: String,
    maxReceipts: Int,
): Pair<PushProtocol.State, PushProtocol.Receipt>? {
    val active = current.active?.takeIf { it.interrupted } ?: return null
    val command = active.command
    if (command.jobId != jobId || command.attempt != attempt ||
        command.artifactId != artifactId || command.revision != revision
    ) return null
    val receipt = PushProtocol.Receipt(
        command,
        PushProtocol.Result(
            jobId = command.jobId,
            attempt = command.attempt,
            status = "fail",
            destPath = command.destPath,
            failureCode = reason,
            detail = detail,
        ),
    )
    return current.copy(
        active = null,
        completedReceipts = (current.completedReceipts + receipt).takeLast(maxReceipts),
    ) to receipt
}

internal fun expireInterruptedPushState(
    current: PushProtocol.State,
    now: Long,
    retentionMs: Long,
    maxReceipts: Int,
): Pair<PushProtocol.State, PushProtocol.Receipt>? {
    val active = current.active?.takeIf { it.interrupted } ?: return null
    val interruptedAt = active.interruptedAt ?: return null
    if (now < interruptedAt || now - interruptedAt < retentionMs) return null
    val command = active.command
    val receipt = PushProtocol.Receipt(
        command,
        PushProtocol.Result(
            jobId = command.jobId,
            attempt = command.attempt,
            status = "fail",
            destPath = command.destPath,
            failureCode = "resume_expired",
            detail = "Interrupted Push/Sync resume expired after 24 hours",
        ),
    )
    return current.copy(
        active = null,
        pendingResults = if (command.isJobV1) current.pendingResults + receipt
        else current.pendingResults,
        completedReceipts = (current.completedReceipts + receipt).takeLast(maxReceipts),
    ) to receipt
}

internal fun interruptedExpiryDelayMillis(
    active: PushProtocol.Active?,
    now: Long,
    retentionMs: Long,
): Long? {
    val interruptedAt = active?.takeIf { it.interrupted }?.interruptedAt ?: return null
    val elapsed = if (now >= interruptedAt) now - interruptedAt else 0L
    return (retentionMs - elapsed).coerceAtLeast(0L)
}

internal fun buildPushRegistrationFields(
    state: PushProtocol.State,
    durabilityAvailable: Boolean,
    processInstanceId: String,
    validatedOffset: (PushProtocol.Command) -> Long,
): JSONObject = JSONObject().apply {
    put("process_instance_id", processInstanceId)
    put("capabilities", JSONArray().apply {
        put(PushProtocol.CAP_PUSH_STATE_RETRY_V1)
        if (durabilityAvailable) {
            put(PushProtocol.CAP_PUSH_JOB_ID_V1)
            put(PushProtocol.CAP_PUSH_RESUME_V1)
        }
    })
    put("push_state", JSONObject().apply {
        put("status", if (durabilityAvailable) "available" else "unavailable")
    })
    put("push_runtime", JSONObject().apply {
        val active = state.active
        if (active == null || active.command.jobId == null) {
            put("active", JSONObject.NULL)
        } else {
            put("active", JSONObject().apply {
                put("job_id", active.command.jobId)
                put("attempt", active.command.attempt)
                put("artifact_id", active.command.artifactId)
                put("phase", active.phase)
                put("status", if (active.interrupted) "interrupted" else "active")
                put("revision", active.command.revision)
                put("validated_offset", validatedOffset(active.command))
            })
        }
    })
}

/**
 * Application-scoped single owner for every Push/Sync execution.
 *
 * Commands, transport changes, worker callbacks, reconciliation, result ACKs, and
 * every durable state mutation are serialized on [actor]. File/network work uses a
 * separate single worker thread and never owns the WebSocket transport.
 */
class PushJobCoordinator(context: Context) {
    companion object {
        private const val TAG = "PushJobCoordinator"
        private const val MAX_RECEIPTS = 256
    }

    private val appContext = context.applicationContext
    private val store = PushJobStore(appContext)
    private val gate = PushExecutionGate()
    private val worker = PushFilesWorker()
    private val actor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "push-job-actor")
    }
    private val workerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "push-job-worker")
    }
    private val processInstanceId = UUID.randomUUID().toString()

    private var state: PushProtocol.State = store.emptyState()
    @Volatile
    private var durabilityAvailable = false
    private var transportToken: Any? = null
    private var transportSend: ((JSONObject) -> Unit)? = null
    private var transportRegistered = false

    init {
        actor.execute {
            try {
                val loaded = when (val result = store.load()) {
                    is PushStateLoadResult.Valid -> result.state
                    PushStateLoadResult.Missing -> store.emptyState()
                    is PushStateLoadResult.Corrupt -> {
                        // The in-memory state deliberately remains empty but unavailable.
                        // Never overwrite unknown durable ownership with an empty snapshot.
                        Log.e(TAG, "Could not parse durable Push/Sync state", result.error)
                        return@execute
                    }
                }
                val recovery = recover(loaded)
                if (!persist(recovery.state)) return@execute
                // Keep resumable job-v1 work after a process restart. An exact EXECUTE
                // command is required before a worker can resume or apply it.
                gate.restore(state.active?.takeUnless { it.interrupted }?.command)
                recovery.cleanupCommand?.let { command ->
                    workerExecutor.execute {
                        try {
                            worker.cleanup(PushFilesWorker.Execution(
                                PushProtocol.Result(command.jobId, command.attempt, "fail", command.destPath),
                                attemptDirectory(command),
                            ))
                        } catch (error: Throwable) {
                            Log.w(TAG, "Could not clean recovered Push/Sync work directory", error)
                        }
                    }
                }
                scheduleInterruptedExpiry(state.active)
            } catch (error: Throwable) {
                Log.e(TAG, "Could not initialize durable Push/Sync state", error)
            }
        }
    }

    fun attachTransport(token: Any, send: (JSONObject) -> Unit) {
        actor.execute {
            transportToken = token
            transportSend = send
            transportRegistered = false
        }
    }

    fun detachTransport(token: Any) {
        actor.execute {
            if (transportToken === token) {
                transportToken = null
                transportSend = null
                transportRegistered = false
            }
        }
    }

    fun registrationFields(onReady: (JSONObject) -> Unit) {
        actor.execute {
            onReady(buildRegistrationFields())
        }
    }

    /** Returns true when the Application-scoped coordinator owns this message. */
    fun handleServerMessage(type: String, payload: JSONObject): Boolean = when (type) {
        "EXECUTE_PUSH_FILES" -> {
            actor.execute { handleCommand(payload) }
            true
        }
        "REGISTERED" -> {
            actor.execute {
                transportRegistered = true
                replayPendingResults()
            }
            true
        }
        "PUSH_RESULT_ACK" -> {
            actor.execute { handleResultAck(payload) }
            true
        }
        "PUSH_RECONCILE_REQUEST" -> {
            actor.execute { handleReconcileRequest(payload) }
            true
        }
        "PUSH_RESUME_REJECTED" -> {
            actor.execute { handleResumeRejected(payload) }
            true
        }
        "RETRY_PUSH_STATE" -> {
            actor.execute { retryDurableState() }
            true
        }
        else -> false
    }

    private fun buildRegistrationFields(): JSONObject {
        return buildPushRegistrationFields(
            state,
            durabilityAvailable,
            processInstanceId,
            ::validatedOffset,
        )
    }

    private fun retryDurableState() {
        if (state.active?.interrupted == false) {
            sendPushStateRetryResult(
                "failed",
                "push_state_busy",
                "Push/Sync is active; durable state cannot be reloaded",
            )
            return
        }
        try {
            val loaded = when (val result = store.load()) {
                is PushStateLoadResult.Valid -> result.state
                PushStateLoadResult.Missing -> store.emptyState()
                is PushStateLoadResult.Corrupt -> throw result.error
            }
            val recovery = recover(loaded)
            if (!persist(recovery.state)) {
                sendPushStateRetryResult(
                    "failed",
                    "client_persistence_unavailable",
                    "Device could not save durable Push/Sync state",
                )
                return
            }
            gate.restore(state.active?.takeUnless { it.interrupted }?.command)
            recovery.cleanupCommand?.let { command ->
                workerExecutor.execute {
                    try {
                        worker.cleanup(PushFilesWorker.Execution(
                            PushProtocol.Result(
                                command.jobId,
                                command.attempt,
                                "fail",
                                command.destPath,
                            ),
                            attemptDirectory(command),
                        ))
                    } catch (error: Throwable) {
                        Log.w(TAG, "Could not clean recovered Push/Sync work directory", error)
                    }
                }
            }
            scheduleInterruptedExpiry(state.active)
            sendPushStateRetryResult("success", null, null)
        } catch (error: Throwable) {
            durabilityAvailable = false
            Log.e(TAG, "Could not retry durable Push/Sync state", error)
            sendPushStateRetryResult(
                "failed",
                "client_persistence_unavailable",
                error.message ?: "Device could not reload durable Push/Sync state",
            )
        }
    }

    private fun sendPushStateRetryResult(status: String, reason: String?, detail: String?) {
        val fields = buildRegistrationFields()
        send(JSONObject().apply {
            put("type", "PUSH_STATE_RETRY_RESULT")
            put("status", status)
            if (reason != null) put("reason", reason)
            if (detail != null) put("detail", detail)
            put("process_instance_id", fields.getString("process_instance_id"))
            put("capabilities", fields.getJSONArray("capabilities"))
            put("push_state", fields.getJSONObject("push_state"))
            put("push_runtime", fields.getJSONObject("push_runtime"))
        })
    }

    private fun handleCommand(payload: JSONObject) {
        val command = try {
            PushProtocol.parseCommand(payload)
        } catch (error: IllegalArgumentException) {
            rejectMalformed(payload, error.message ?: "malformed_command")
            return
        }

        val terminal = state.pendingResults.firstOrNull { sameIdentity(it.command, command) }
            ?: state.completedReceipts.firstOrNull { sameIdentity(it.command, command) }
        if (terminal != null) {
            if (!terminal.command.sameExecution(command)) {
                rejectConflict(
                    command,
                    "same identity carried different artifact, destination, or mode",
                )
            } else {
                send(terminal.result.toJson())
            }
            return
        }

        val interrupted = state.active?.takeIf { it.interrupted }
        if (interrupted != null) {
            if (sameIdentity(interrupted.command, command)) {
                if (interrupted.command.sameExecution(command)) {
                    // Recovery deliberately leaves the gate empty while waiting
                    // for exact server authorization. Reacquire it before the
                    // worker starts so duplicate/other commands remain fenced.
                    gate.restore(command)
                    accept(command)
                }
                else rejectConflict(command, "interrupted job identity carried different execution fields")
            } else {
                rejectBusy(command, interrupted.command)
            }
            return
        }

        if (!durabilityAvailable) {
            rejectPersistenceUnavailable(command)
            return
        }

        when (val decision = gate.offer(command)) {
            PushExecutionGate.Decision.Accepted -> accept(command)
            is PushExecutionGate.Decision.Duplicate -> handleDuplicate(command)
            is PushExecutionGate.Decision.Busy -> rejectBusy(command, decision.active)
            is PushExecutionGate.Decision.Conflict -> rejectConflict(command, decision.detail)
        }
    }

    private fun accept(command: PushProtocol.Command) {
        val nextState = state.copy(
            active = PushProtocol.Active(command, PushProtocol.PHASE_DOWNLOADING),
        )
        if (!persist(nextState)) { // durability before acceptance and before worker start
            gate.release(command)
            rejectPersistenceUnavailable(command)
            return
        }
        if (command.isJobV1) sendAccepted(command, PushProtocol.PHASE_DOWNLOADING)
        workerExecutor.execute {
            val execution = worker.execute(
                command,
                PushFilesWorker.Callbacks(
                    onTransferComplete = { received ->
                        actor.execute { onTransferComplete(command, received) }
                    },
                    onValidated = { actor.execute { onValidated(command) } },
                    onApplying = { actor.execute { onApplying(command) } },
                ),
            )
            actor.execute { onTerminal(command, execution) }
        }
    }

    private fun handleDuplicate(command: PushProtocol.Command) {
        val active = state.active
        if (active != null && active.command.identity == command.identity && command.isJobV1) {
            sendAccepted(command, active.phase)
        }
    }

    private fun rejectBusy(command: PushProtocol.Command, active: PushProtocol.Command) {
        if (command.isJobV1) {
            send(JSONObject().apply {
                put("type", "PUSH_JOB_REJECTED")
                put("job_id", command.jobId)
                put("attempt", command.attempt)
                put("reason", "device_busy")
                put("retryable", true)
                put("active_job", JSONObject().apply {
                    if (active.jobId != null) put("job_id", active.jobId)
                    else put("legacy", true)
                    put("attempt", active.attempt)
                })
            })
        } else {
            // Migration-only compatibility for an old server. This terminal-shaped
            // response describes the rejected command, not the current active worker.
            send(PushProtocol.Result(
                jobId = null,
                attempt = PushProtocol.ATTEMPT_V1,
                status = "fail",
                destPath = command.destPath,
                failureCode = "device_busy",
                detail = "Push/Sync already in progress",
            ).toJson())
        }
    }

    private fun rejectConflict(command: PushProtocol.Command, detail: String) {
        if (!command.isJobV1) return
        send(JSONObject().apply {
            put("type", "PUSH_JOB_REJECTED")
            put("job_id", command.jobId)
            put("attempt", command.attempt)
            put("reason", "artifact_identity_mismatch")
            put("retryable", false)
            put("detail", detail)
        })
    }

    private fun rejectMalformed(payload: JSONObject, detail: String) {
        val jobId = payload.optString("job_id", "")
        if (jobId.isBlank()) return
        send(JSONObject().apply {
            put("type", "PUSH_JOB_REJECTED")
            put("job_id", jobId)
            put("attempt", payload.optInt("attempt", -1))
            put("reason", detail.substringBefore(':'))
            put("retryable", false)
            put("detail", detail)
        })
    }

    private fun rejectPersistenceUnavailable(command: PushProtocol.Command) {
        if (command.isJobV1) {
            send(JSONObject().apply {
                put("type", "PUSH_JOB_REJECTED")
                put("job_id", command.jobId)
                put("attempt", command.attempt)
                put("reason", "client_persistence_unavailable")
                put("retryable", false)
                put("detail", "Device could not save durable Push/Sync state")
            })
            return
        }
        send(PushProtocol.Result(
            jobId = null,
            attempt = PushProtocol.ATTEMPT_V1,
            status = "fail",
            destPath = command.destPath,
            failureCode = "client_persistence_unavailable",
            detail = "Device could not save durable Push/Sync state",
        ).toJson())
    }

    private fun handleResumeRejected(payload: JSONObject) {
        val jobId = payload.opt("job_id") as? String ?: return
        val attempt = when (val value = payload.opt("attempt")) {
            is Int -> value
            is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            else -> null
        } ?: return
        val artifactId = payload.opt("artifact_id") as? String ?: return
        val revision = when (val value = payload.opt("revision")) {
            is Int -> value.toLong()
            is Long -> value
            else -> null
        } ?: return
        val reason = (payload.opt("reason") as? String)?.ifBlank { null }
            ?: "resume_not_authorized"
        val detail = payload.opt("detail") as? String
            ?: "Server rejected interrupted Push/Sync resume"
        val settled = applyPushResumeRejectionToState(
            state, jobId, attempt, artifactId, revision, reason, detail, MAX_RECEIPTS,
        ) ?: return
        val (nextState, receipt) = settled
        val command = receipt.command
        val result = receipt.result
        if (!persist(nextState, afterPublish = { gate.release(command) })) return
        cleanupExecution(PushFilesWorker.Execution(result, attemptDirectory(command)))
        send(JSONObject().apply {
            put("type", "PUSH_RECONCILE_REPORT")
            put("job_id", command.jobId)
            put("attempt", command.attempt)
            put("artifact_id", command.artifactId)
            put("status", "absent")
            put("detail", reason)
        })
    }

    private fun onTransferComplete(command: PushProtocol.Command, received: Long) {
        if (!setPhase(command, PushProtocol.PHASE_VALIDATING)) return
        if (command.isJobV1) {
            send(JSONObject().apply {
                put("type", "PUSH_TRANSFER_COMPLETE")
                put("job_id", command.jobId)
                put("attempt", command.attempt)
                put("artifact_id", command.artifactId)
                put("received_size", received)
            })
        } else {
            send(JSONObject().apply {
                put("type", "DOWNLOAD_COMPLETE")
                put("task", "push")
                put("dest_path", command.destPath)
                put("delete_extras", command.deleteExtras)
            })
        }
    }

    private fun onValidated(command: PushProtocol.Command) {
        val active = state.active
        if (active?.command?.identity != command.identity ||
            active.phase != PushProtocol.PHASE_VALIDATING ||
            !command.isJobV1
        ) return
        send(JSONObject().apply {
            put("type", "DOWNLOAD_COMPLETE")
            put("task", "push")
            put("job_id", command.jobId)
            put("attempt", command.attempt)
            put("artifact_id", command.artifactId)
            put("dest_path", command.destPath)
            put("delete_extras", command.deleteExtras)
        })
    }

    private fun onApplying(command: PushProtocol.Command) {
        if (!setPhase(command, PushProtocol.PHASE_APPLYING)) return
        if (command.isJobV1) {
            send(JSONObject().apply {
                put("type", "PUSH_PHASE")
                put("job_id", command.jobId)
                put("attempt", command.attempt)
                put("phase", PushProtocol.PHASE_APPLYING)
            })
        }
    }

    private fun onTerminal(
        command: PushProtocol.Command,
        execution: PushFilesWorker.Execution,
    ) {
        if (!isCurrent(command)) {
            cleanupExecution(execution)
            return
        }
        val receipt = PushProtocol.Receipt(command, execution.result)
        val pending = if (command.isJobV1) state.pendingResults + receipt else state.pendingResults
        val completed = (state.completedReceipts + receipt).takeLast(MAX_RECEIPTS)
        val nextState = state.copy(
            active = null,
            pendingResults = pending,
            completedReceipts = completed,
        )
        // Persist the terminal outbox before releasing the lease, cleaning up, or sending.
        if (!persist(
            nextState,
            afterPublish = { gate.release(command) },
        )) return
        cleanupExecution(execution)
        send(execution.result.toJson())
    }

    private fun cleanupExecution(execution: PushFilesWorker.Execution) {
        workerExecutor.execute {
            try {
                worker.cleanup(execution)
            } catch (error: Throwable) {
                Log.w(TAG, "Could not clean Push/Sync attempt directory", error)
            }
        }
    }

    private fun setPhase(command: PushProtocol.Command, phase: String): Boolean {
        val active = state.active ?: return false
        if (active.command.identity != command.identity) return false
        return persist(state.copy(active = active.copy(phase = phase)))
    }

    private fun isCurrent(command: PushProtocol.Command): Boolean =
        state.active?.command?.identity == command.identity

    private fun sendAccepted(command: PushProtocol.Command, phase: String) {
        send(JSONObject().apply {
            put("type", "PUSH_JOB_ACCEPTED")
            put("job_id", command.jobId)
            put("attempt", command.attempt)
            put("phase", phase)
        })
    }

    private fun handleResultAck(payload: JSONObject) {
        val ack = try {
            PushProtocol.parseResultAck(payload)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Ignoring malformed Push result ACK: ${error.message}")
            return
        }
        val next = applyPushResultAckToState(state, ack, MAX_RECEIPTS)
        if (next === state) return
        persist(next)
    }

    private fun replayPendingResults() {
        state.pendingResults.forEach { send(it.result.toJson()) }
    }

    private fun handleReconcileRequest(payload: JSONObject) {
        val jobs = payload.optJSONArray("jobs") ?: JSONArray()
        for (index in 0 until jobs.length()) {
            val requested = jobs.optJSONObject(index) ?: continue
            val identity = try {
                PushProtocol.parseReconcileIdentity(requested)
            } catch (error: IllegalArgumentException) {
                Log.w(TAG, "Ignoring malformed Push reconcile identity: ${error.message}")
                continue
            }
            fun matches(command: PushProtocol.Command): Boolean =
                command.jobId == identity.jobId &&
                    command.attempt == identity.attempt &&
                    (identity.artifactId == null ||
                        command.artifactId == identity.artifactId)

            val active = state.active
            if (active != null && matches(active.command)) {
                send(buildActivePushReconcileReport(
                    identity,
                    active,
                    validatedOffset(active.command),
                ))
                continue
            }
            val settled = findPushReconcileReceipt(state) { matches(it) }
            if (settled != null) {
                send(settled.result.toJson())
                continue
            }
            send(JSONObject().apply {
                put("type", "PUSH_RECONCILE_REPORT")
                put("job_id", identity.jobId)
                put("attempt", identity.attempt)
                put("status", "absent")
            })
        }
    }

    private data class Recovery(
        val state: PushProtocol.State,
        val cleanupCommand: PushProtocol.Command? = null,
    )

    private fun recover(loaded: PushProtocol.State): Recovery {
        val active = loaded.active ?: return Recovery(loaded)
        // State written by issue #91 has no immutable dispatch revision and is
        // migrated as revision=0. It cannot be resumed safely, so preserve the
        // old client_restarted terminal/cleanup behavior for that state only.
        if (active.command.isJobV1 && active.command.revision > 0L) {
            val expired = expireInterruptedPushState(
                loaded,
                System.currentTimeMillis(),
                PushFilesWorker.PARTIAL_RETENTION_MS,
                MAX_RECEIPTS,
            )
            if (expired != null) {
                Log.w(TAG, "Expired interrupted Push/Sync ${active.command.identity}")
                return Recovery(expired.first, active.command)
            }
            Log.w(TAG, "Recovered resumable Push/Sync ${active.command.identity}")
            return Recovery(loaded.copy(active = active.copy(
                interrupted = true,
                interruptedAt = active.interruptedAt ?: System.currentTimeMillis(),
            )))
        }
        val interrupted = PushProtocol.Result(
            jobId = active.command.jobId,
            attempt = active.command.attempt,
            status = "fail",
            destPath = active.command.destPath,
            failureCode = "client_restarted",
            detail = "client process restarted; the previous worker did not survive and " +
                "the destination may be partially applied",
        )
        val receipt = PushProtocol.Receipt(active.command, interrupted)
        val pending = if (active.command.isJobV1) loaded.pendingResults + receipt
        else loaded.pendingResults
        Log.w(TAG, "Recovered interrupted Push/Sync ${active.command.identity}")
        return Recovery(loaded.copy(
            active = null,
            pendingResults = pending,
            completedReceipts = (loaded.completedReceipts + receipt).takeLast(MAX_RECEIPTS),
        ), active.command)
    }

    private fun scheduleInterruptedExpiry(active: PushProtocol.Active?) {
        val delay = interruptedExpiryDelayMillis(
            active,
            System.currentTimeMillis(),
            PushFilesWorker.PARTIAL_RETENTION_MS,
        ) ?: return
        actor.schedule({ expireInterruptedOwnership() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun expireInterruptedOwnership() {
        val settled = expireInterruptedPushState(
            state,
            System.currentTimeMillis(),
            PushFilesWorker.PARTIAL_RETENTION_MS,
            MAX_RECEIPTS,
        )
        if (settled == null) {
            // ScheduledExecutorService advances monotonically while the retention
            // predicate uses wall time. Re-arm after RTC/NTP moves the clock back.
            scheduleInterruptedExpiry(state.active)
            return
        }
        val (nextState, receipt) = settled
        val command = receipt.command
        if (!persist(nextState, afterPublish = { gate.release(command) })) {
            actor.schedule({ expireInterruptedOwnership() }, 60_000L, TimeUnit.MILLISECONDS)
            return
        }
        cleanupExecution(PushFilesWorker.Execution(receipt.result, attemptDirectory(command)))
        if (transportRegistered) send(receipt.result.toJson())
    }

    private fun attemptDirectory(command: PushProtocol.Command) =
        java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS,
            ),
            "styly-mdm/.push-tmp/jobs/${command.jobId?.let { UUID.fromString(it).toString() } ?: "legacy"}/${command.attempt}",
        )

    private fun persist(
        nextState: PushProtocol.State,
        afterPublish: () -> Unit = {},
    ): Boolean {
        val persisted = persistPushStateBeforePublishing(
            nextState,
            save = store::save,
            afterPublish = afterPublish,
            onFailure = { error ->
                Log.e(TAG, "Could not persist durable Push/Sync state", error)
            },
        ) { saved ->
            state = saved
        }
        durabilityAvailable = persisted
        return persisted
    }

    private fun validatedOffset(command: PushProtocol.Command): Long {
        if (!command.isJobV1) return 0L
        val file = java.io.File(attemptDirectory(command), "artifact.part")
        return file.takeIf { it.isFile }?.length() ?: 0L
    }

    private fun send(message: JSONObject) {
        try {
            transportSend?.invoke(message)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not send Push/Sync protocol message", error)
        }
    }

    private fun sameIdentity(first: PushProtocol.Command, second: PushProtocol.Command): Boolean =
        first.jobId != null && first.jobId == second.jobId && first.attempt == second.attempt
}
