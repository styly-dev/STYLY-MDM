package com.styly.mdmclient

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal fun persistPushStateBeforePublishing(
    nextState: PushProtocol.State,
    save: (PushProtocol.State) -> PushProtocol.State,
    afterPublish: () -> Unit = {},
    publish: (PushProtocol.State) -> Unit,
) {
    val persisted = save(nextState)
    publish(persisted)
    afterPublish()
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
    private val actor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "push-job-actor")
    }
    private val workerExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "push-job-worker")
    }
    private val processInstanceId = UUID.randomUUID().toString()

    @Volatile
    private var visibleState: PushProtocol.State = store.emptyState()
    private var state: PushProtocol.State = store.emptyState()
    private var transportToken: Any? = null
    private var transportSend: ((JSONObject) -> Unit)? = null

    init {
        actor.execute {
            val loaded = store.load()
            val recovery = recover(loaded)
            persist(recovery.state)
            gate.restore(state.active?.command)
            recovery.cleanupCommand?.let { command ->
                workerExecutor.execute {
                    try {
                        worker.cleanup(
                            PushFilesWorker.Execution(
                                result = PushProtocol.Result(
                                    command.jobId,
                                    command.attempt,
                                    "fail",
                                    command.destPath,
                                    failureCode = "client_restarted",
                                    detail = "recovered work cleanup",
                                ),
                                workDirectory = attemptDirectory(command),
                            )
                        )
                    } catch (error: Throwable) {
                        Log.w(TAG, "Could not clean recovered Push/Sync work directory", error)
                    }
                }
            }
        }
    }

    fun attachTransport(token: Any, send: (JSONObject) -> Unit) {
        actor.execute {
            transportToken = token
            transportSend = send
        }
    }

    fun detachTransport(token: Any) {
        actor.execute {
            if (transportToken === token) {
                transportToken = null
                transportSend = null
            }
        }
    }

    fun registrationFields(): JSONObject {
        val snapshot = visibleState
        return JSONObject().apply {
            put("process_instance_id", processInstanceId)
            put("capabilities", JSONArray().put(PushProtocol.CAP_PUSH_JOB_ID_V1))
            put("push_runtime", JSONObject().apply {
                val active = snapshot.active
                if (active == null || active.command.jobId == null) {
                    put("active", JSONObject.NULL)
                } else {
                    put("active", JSONObject().apply {
                        put("job_id", active.command.jobId)
                        put("attempt", active.command.attempt)
                        put("artifact_id", active.command.artifactId)
                        put("phase", active.phase)
                    })
                }
            })
        }
    }

    /** Returns true when the Application-scoped coordinator owns this message. */
    fun handleServerMessage(type: String, payload: JSONObject): Boolean = when (type) {
        "EXECUTE_PUSH_FILES" -> {
            actor.execute { handleCommand(payload) }
            true
        }
        "REGISTERED" -> {
            actor.execute { replayPendingResults() }
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
        else -> false
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
        try {
            persist(nextState) // durability before acceptance and before worker start
        } catch (error: Throwable) {
            gate.release(command)
            throw error
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
        if (!isCurrent(command) || !command.isJobV1) return
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
        persist(
            nextState,
            afterPublish = { gate.release(command) },
        )
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
        persist(state.copy(active = active.copy(phase = phase)))
        return true
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
        if (!ack.accepted) return
        val next = state.pendingResults.filterNot {
            it.result.jobId == ack.jobId && it.result.attempt == ack.attempt
        }
        if (next.size == state.pendingResults.size) return
        persist(state.copy(pendingResults = next))
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
                send(JSONObject().apply {
                    put("type", "PUSH_RECONCILE_REPORT")
                    put("job_id", identity.jobId)
                    put("attempt", identity.attempt)
                    put("status", "active")
                    put("phase", active.phase)
                    put("validated_offset", 0)
                })
                continue
            }
            val pending = state.pendingResults.firstOrNull {
                matches(it.command)
            }
            if (pending != null) {
                send(pending.result.toJson())
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
        val cleanupCommand: PushProtocol.Command?,
    )

    private fun recover(loaded: PushProtocol.State): Recovery {
        val active = loaded.active ?: return Recovery(loaded, null)
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
        return Recovery(
            loaded.copy(
                active = null,
                pendingResults = pending,
                completedReceipts = (loaded.completedReceipts + receipt).takeLast(MAX_RECEIPTS),
            ),
            active.command,
        )
    }

    private fun attemptDirectory(command: PushProtocol.Command) =
        java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS,
            ),
            "styly-mdm/.push-tmp/jobs/${command.jobId ?: "legacy"}/${command.attempt}",
        )

    private fun persist(
        nextState: PushProtocol.State,
        afterPublish: () -> Unit = {},
    ) {
        try {
            persistPushStateBeforePublishing(
                nextState,
                save = store::save,
                afterPublish = afterPublish,
            ) { persisted ->
                state = persisted
                visibleState = persisted
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Could not persist durable Push/Sync state", error)
            throw error
        }
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
