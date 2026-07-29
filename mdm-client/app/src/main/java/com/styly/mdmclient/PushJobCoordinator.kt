package com.styly.mdmclient

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Application-scoped single owner for every Push/Sync execution.
 *
 * Commands, transport changes, worker callbacks, reconciliation, and result ACKs
 * are serialized on [actor].  The worker uses a separate single thread so file
 * I/O never blocks WebSocket callbacks or persistence ordering.
 */
class PushJobCoordinator(context: Context) {
    companion object {
        private const val TAG = "PushJobCoordinator"
    }

    private val appContext = context.applicationContext
    private val store = PushJobStore(appContext)
    private val gate = PushExecutionGate()
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
            state = recover(store.load())
            gate.restore(state.active?.command)
            persist()
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

    /** Returns true when the coordinator owns this protocol message. */
    fun handleServerMessage(type: String, payload: JSONObject): Boolean {
        return when (type) {
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
    }

    private fun handleCommand(payload: JSONObject) {
        val command = try {
            PushProtocol.parseCommand(payload)
        } catch (error: IllegalArgumentException) {
            rejectMalformed(payload, error.message ?: "malformed_command")
            return
        }
        // A terminal replay must never start a second worker.  Receipts retain the
        // original command so same identity with changed metadata is rejected.
        val terminal = state.pendingResults.firstOrNull { sameIdentity(it.command, command) }
            ?: state.completedReceipts.firstOrNull { sameIdentity(it.command, command) }
        if (terminal != null) {
            if (!terminal.command.sameExecution(command)) {
                rejectConflict(command, "same identity carried different artifact, destination, or mode")
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
        state = state.copy(active = PushProtocol.Active(command, "downloading"))
        persist() // durability before PUSH_JOB_ACCEPTED and before the worker starts
        if (command.isJobV1) sendAccepted(command, "downloading")
        workerExecutor.execute {
            val result = PushFilesWorker().execute(
                command,
                PushFilesWorker.Callbacks(
                    onTransferComplete = { received -> actor.execute { onTransferComplete(command, received) } },
                    onValidated = { actor.execute { onValidated(command) } },
                    onApplying = { actor.execute { onApplying(command) } },
                )
            )
            actor.execute { onTerminal(command, result) }
        }
    }

    private fun handleDuplicate(command: PushProtocol.Command) {
        val active = state.active
        if (active != null && active.command.identity == command.identity) {
            if (command.isJobV1) sendAccepted(command, active.phase)
            return
        }
        // Completed/pending receipts are handled before gate.offer().  This branch
        // is therefore only for a duplicate of the currently active execution.
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
            // Old servers understand only the legacy terminal shape.  This result
            // describes the rejected command and never releases/mutates the active one.
            send(PushProtocol.Result(
                null, PushProtocol.ATTEMPT_V1, "fail", command.destPath,
                0, 0, 0, "Push/Sync already in progress"
            ).toJson())
        }
    }

    private fun rejectConflict(command: PushProtocol.Command, detail: String) {
        if (command.isJobV1) {
            send(JSONObject().apply {
                put("type", "PUSH_JOB_REJECTED")
                put("job_id", command.jobId)
                put("attempt", command.attempt)
                put("reason", "artifact_identity_mismatch")
                put("retryable", false)
                put("detail", detail)
            })
        }
    }

    private fun rejectMalformed(payload: JSONObject, detail: String) {
        val jobId = payload.optString("job_id", "")
        if (jobId.isNotBlank()) {
            send(JSONObject().apply {
                put("type", "PUSH_JOB_REJECTED")
                put("job_id", jobId)
                put("attempt", payload.optInt("attempt", -1))
                put("reason", detail.substringBefore(':'))
                put("retryable", false)
                put("detail", detail)
            })
        }
    }

    private fun onTransferComplete(command: PushProtocol.Command, received: Long) {
        if (!setPhase(command, "validating")) return
        if (command.isJobV1) {
            send(JSONObject().apply {
                put("type", "PUSH_TRANSFER_COMPLETE")
                put("job_id", command.jobId)
                put("attempt", command.attempt)
                put("artifact_id", command.artifactId)
                put("received_size", received)
            })
        } else {
            // Preserve the old server's slot-release message.
            send(JSONObject().apply {
                put("type", "DOWNLOAD_COMPLETE")
                put("task", "push")
                put("dest_path", command.destPath)
                put("delete_extras", command.deleteExtras)
            })
        }
    }

    private fun onValidated(command: PushProtocol.Command) {
        if (!isCurrent(command)) return
        if (command.isJobV1) {
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
    }

    private fun onApplying(command: PushProtocol.Command) {
        if (!setPhase(command, "applying")) return
        if (command.isJobV1) {
            send(JSONObject().apply {
                put("type", "PUSH_PHASE")
                put("job_id", command.jobId)
                put("attempt", command.attempt)
                put("phase", "applying")
            })
        }
    }

    private fun onTerminal(command: PushProtocol.Command, result: PushProtocol.Result) {
        if (!isCurrent(command)) return
        gate.release(command)
        val receipt = PushProtocol.Receipt(command, result)
        val receipts = (state.completedReceipts + receipt).takeLast(256)
        val pending = if (command.isJobV1) state.pendingResults + receipt else state.pendingResults
        state = state.copy(active = null, pendingResults = pending, completedReceipts = receipts)
        persist() // result outbox is durable before transport send
        send(result.toJson())
    }

    private fun setPhase(command: PushProtocol.Command, phase: String): Boolean {
        val active = state.active ?: return false
        if (active.command.identity != command.identity) return false
        state = state.copy(active = active.copy(phase = phase))
        persist()
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
        if (!payload.optBoolean("accepted", false)) return
        val jobId = payload.optString("job_id", "")
        val attempt = payload.optInt("attempt", -1)
        val next = state.pendingResults.filterNot {
            it.result.jobId == jobId && it.result.attempt == attempt
        }
        if (next.size == state.pendingResults.size) return
        state = state.copy(pendingResults = next)
        persist()
    }

    private fun replayPendingResults() {
        state.pendingResults.forEach { send(it.result.toJson()) }
    }

    private fun handleReconcileRequest(payload: JSONObject) {
        val jobs = payload.optJSONArray("jobs") ?: JSONArray()
        for (index in 0 until jobs.length()) {
            val requested = jobs.optJSONObject(index) ?: continue
            val jobId = requested.optString("job_id", "")
            val attempt = requested.optInt("attempt", -1)
            val active = state.active
            if (active != null && active.command.jobId == jobId && active.command.attempt == attempt) {
                send(JSONObject().apply {
                    put("type", "PUSH_RECONCILE_REPORT")
                    put("job_id", jobId)
                    put("attempt", attempt)
                    put("status", "active")
                    put("phase", active.phase)
                    put("validated_offset", 0)
                })
                continue
            }
            val pending = state.pendingResults.firstOrNull {
                it.result.jobId == jobId && it.result.attempt == attempt
            }
            if (pending != null) {
                send(pending.result.toJson())
                continue
            }
            send(JSONObject().apply {
                put("type", "PUSH_RECONCILE_REPORT")
                put("job_id", jobId)
                put("attempt", attempt)
                put("status", "absent")
            })
        }
    }

    private fun recover(loaded: PushProtocol.State): PushProtocol.State {
        val active = loaded.active ?: return loaded
        cleanupAttemptDirectory(active.command)
        val interrupted = PushProtocol.Result(
            active.command.jobId,
            active.command.attempt,
            "fail",
            active.command.destPath,
            0, 0, 0,
            "client process restarted; the previous worker did not survive and the destination may be partially applied"
        )
        val receipt = PushProtocol.Receipt(active.command, interrupted)
        val pending = if (active.command.isJobV1) loaded.pendingResults + receipt else loaded.pendingResults
        Log.w(TAG, "Recovered interrupted push execution ${active.command.identity}")
        return loaded.copy(
            active = null,
            pendingResults = pending,
            completedReceipts = (loaded.completedReceipts + receipt).takeLast(256),
        )
    }

    private fun cleanupAttemptDirectory(command: PushProtocol.Command) {
        try {
            val key = command.jobId?.let { UUID.fromString(it).toString() } ?: "legacy"
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(downloads, "styly-mdm/.push-tmp/jobs/$key/${command.attempt}").deleteRecursively()
        } catch (_: Throwable) {}
    }

    private fun persist() {
        store.save(state)
        visibleState = state
    }

    private fun send(message: JSONObject) {
        try {
            transportSend?.invoke(message)
        } catch (error: Throwable) {
            Log.w(TAG, "Could not send push protocol message", error)
        }
    }

    private fun sameIdentity(first: PushProtocol.Command, second: PushProtocol.Command): Boolean =
        first.jobId != null && first.jobId == second.jobId && first.attempt == second.attempt
}
