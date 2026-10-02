package com.styly.mdmclient

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

private fun JSONObject.putActivePushFields(active: PushProtocol.Active, validatedOffset: Long) {
    put("phase", active.phase)
    put("status", if (active.interrupted) "interrupted" else "active")
    put("revision", active.command.revision)
    put("validated_offset", validatedOffset)
    if (active.interrupted && active.interruptionReason != null) {
        put("reason", active.interruptionReason)
    }
}

internal fun buildActivePushReconcileReport(
    identity: PushProtocol.ReconcileIdentity,
    active: PushProtocol.Active,
    validatedOffset: Long,
): JSONObject = JSONObject().apply {
    put("type", "PUSH_RECONCILE_REPORT")
    put("job_id", identity.jobId)
    put("attempt", identity.attempt)
    put("artifact_id", active.command.artifactId)
    putActivePushFields(active, validatedOffset)
}

/**
 * Answers one reconcile identity from durable state. Returns null while [stateLoaded] is
 * false: the empty in-memory state is not authoritative, so it is no evidence of absence.
 */
internal fun buildPushReconcileReply(
    state: PushProtocol.State,
    stateLoaded: Boolean,
    identity: PushProtocol.ReconcileIdentity,
    validatedOffset: (PushProtocol.Command) -> Long,
): JSONObject? {
    if (!stateLoaded) return null
    fun matches(command: PushProtocol.Command): Boolean =
        command.jobId == identity.jobId &&
            command.attempt == identity.attempt &&
            (identity.artifactId == null ||
                command.artifactId == identity.artifactId)

    val active = state.active
    if (active != null && matches(active.command)) {
        return buildActivePushReconcileReport(identity, active, validatedOffset(active.command))
    }
    val settled = findPushReconcileReceipt(state) { matches(it) }
    if (settled != null) return settled.result.toJson()
    return JSONObject().apply {
        put("type", "PUSH_RECONCILE_REPORT")
        put("job_id", identity.jobId)
        put("attempt", identity.attempt)
        if (identity.artifactId != null) put("artifact_id", identity.artifactId)
        put("status", "absent")
    }
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

internal fun interruptPushAfterRestart(active: PushProtocol.Active, now: Long): PushProtocol.Active =
    active.copy(
        interrupted = true,
        interruptedAt = active.interruptedAt ?: now,
        interruptionReason = active.interruptionReason ?: "client_restarted",
    )

internal fun buildPushRegistrationFields(
    state: PushProtocol.State,
    durabilityAvailable: Boolean,
    processInstanceId: String,
    resetNotice: PushStateResetNotice? = null,
    validatedOffset: (PushProtocol.Command) -> Long,
): JSONObject = JSONObject().apply {
    put("process_instance_id", processInstanceId)
    put("capabilities", JSONArray().apply {
        if (durabilityAvailable) {
            put(PushProtocol.CAP_PUSH_JOB_ID_V1)
            put(PushProtocol.CAP_PUSH_RESUME_V1)
        }
    })
    put("push_state", JSONObject().apply {
        put("status", if (durabilityAvailable) "available" else "unavailable")
        if (durabilityAvailable && resetNotice != null) {
            put("reset", JSONObject().apply {
                put("reason", resetNotice.reason)
                put("detail", resetNotice.detail)
            })
        }
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
                putActivePushFields(active, validatedOffset(active.command))
            })
        }
    })
}

/** One-shot notice that unparseable durable Push/Sync state was discarded at load. */
internal data class PushStateResetNotice(val reason: String, val detail: String) {
    companion object {
        const val REASON_CORRUPT_STATE_DISCARDED = "corrupt_state_discarded"
        private const val MAX_DETAIL_LENGTH = 256

        fun corruptStateDiscarded(error: Throwable): PushStateResetNotice {
            val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.name
            val detail = message.replace(Regex("\\s+"), " ").trim().take(MAX_DETAIL_LENGTH)
            return PushStateResetNotice(REASON_CORRUPT_STATE_DISCARDED, detail)
        }
    }
}

/**
 * Keeps a [PushStateResetNotice] until a registration that carried it is acknowledged
 * on the same transport. A registration on another transport carries it again.
 */
internal class PushStateResetNoticeTracker {
    private var notice: PushStateResetNotice? = null
    private var carriedBy: Any? = null

    fun record(next: PushStateResetNotice) {
        notice = next
        carriedBy = null
    }

    /** Returns the notice to include in a registration built for [transport]. */
    fun forRegistration(transport: Any?): PushStateResetNotice? {
        val current = notice ?: return null
        if (transport != null) carriedBy = transport
        return current
    }

    fun onRegistered(transport: Any) {
        if (notice != null && carriedBy === transport) {
            notice = null
            carriedBy = null
        }
    }
}

internal enum class PushStateRecoveryAction { Reload, SaveUnsavedTerminal, ResaveCurrent }

/**
 * Chooses what an automatic durable-state retry does. Before durable state was ever
 * adopted the in-memory state is not authoritative, so only a reload is safe. Afterwards
 * the file is never reloaded: a worker may own it, and a terminal outcome that could not
 * be saved must be saved as is, because recovery would turn a finished execution into a
 * resumable one. Otherwise re-saving the in-memory state (equal to the last successful
 * save) probes whether storage is writable again.
 */
internal fun decidePushStateRecovery(
    stateLoaded: Boolean,
    hasUnsavedTerminal: Boolean,
): PushStateRecoveryAction = when {
    !stateLoaded -> PushStateRecoveryAction.Reload
    hasUnsavedTerminal -> PushStateRecoveryAction.SaveUnsavedTerminal
    else -> PushStateRecoveryAction.ResaveCurrent
}

/**
 * Bounded automatic recovery of durable Push/Sync state. A failure opens an incident
 * with [maxAttempts] scheduled retries; success closes it so a later failure opens a
 * fresh incident. Once an incident exhausts its attempts no further incident starts
 * in this process; the next process start loads the state again.
 */
internal class PushStateRecoveryBudget(private val maxAttempts: Int) {
    enum class Phase { Healthy, Recovering, Exhausted }

    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    var phase: Phase = Phase.Healthy
        private set
    var attemptsUsed: Int = 0
        private set

    /** Records a durability failure. Returns true when a first retry must be scheduled. */
    fun onFailure(): Boolean {
        if (phase != Phase.Healthy) return false
        phase = Phase.Recovering
        attemptsUsed = 0
        return true
    }

    /** Records a failed retry. Returns true when another retry must be scheduled. */
    fun onAttemptFailed(): Boolean {
        check(phase == Phase.Recovering) { "no recovery incident is open" }
        attemptsUsed++
        if (attemptsUsed < maxAttempts) return true
        phase = Phase.Exhausted
        return false
    }

    /** Records a successful retry and closes the incident. */
    fun onRecovered() {
        check(phase == Phase.Recovering) { "no recovery incident is open" }
        phase = Phase.Healthy
        attemptsUsed = 0
    }
}

/**
 * Application-scoped single owner for every Push/Sync execution.
 *
 * Commands, transport changes, worker callbacks, reconciliation, result ACKs, and
 * every durable state mutation are serialized on [actor]. File/network work uses a
 * separate single worker thread and never owns the WebSocket transport.
 */
class PushJobCoordinator(
    context: Context,
    private val recoveryRetryDelayMs: Long = RECOVERY_RETRY_DELAY_MS,
    recoveryMaxAttempts: Int = RECOVERY_MAX_ATTEMPTS,
) {
    companion object {
        private const val TAG = "PushJobCoordinator"
        private const val MAX_RECEIPTS = 256
        private const val RECOVERY_RETRY_DELAY_MS = 60_000L
        private const val RECOVERY_MAX_ATTEMPTS = 5
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
    private var durabilityAvailable = false
    /** True once durable state was loaded (or reset) and adopted; until then [state] is not authoritative. */
    private var stateLoaded = false
    private val recoveryBudget = PushStateRecoveryBudget(recoveryMaxAttempts)
    private val resetNotices = PushStateResetNoticeTracker()
    private var transportToken: Any? = null
    private var transportSend: ((JSONObject) -> Unit)? = null
    private var transportRegistrationRefresh: (() -> Unit)? = null
    private var transportRegistered = false
    /** A worker's terminal outcome whose durable save failed; saved by automatic recovery. */
    private var unsavedTerminal: Pair<PushProtocol.Command, PushFilesWorker.Execution>? = null

    init {
        actor.execute {
            if (!loadDurableState()) beginRecovery()
        }
    }

    /**
     * Attaches the transport that receives Push/Sync messages. [requestRegistrationRefresh]
     * asks that transport to refresh the registration after durable-state availability
     * changes, so the server can stop or resume Push dispatch promptly.
     */
    fun attachTransport(
        token: Any,
        send: (JSONObject) -> Unit,
        requestRegistrationRefresh: () -> Unit,
    ) {
        actor.execute {
            transportToken = token
            transportSend = send
            transportRegistrationRefresh = requestRegistrationRefresh
            transportRegistered = false
        }
    }

    fun detachTransport(token: Any) {
        actor.execute {
            if (transportToken === token) {
                transportToken = null
                transportSend = null
                transportRegistrationRefresh = null
                transportRegistered = false
            }
        }
    }

    fun registrationFields(onReady: (JSONObject) -> Unit) {
        actor.execute {
            onReady(buildRegistrationFields())
        }
    }

    /**
     * Runs for every REGISTERED on [token], including the reply to a registration refresh
     * on the same socket. Replaying the durable outbox again is safe: results are ACKed
     * idempotently by job identity.
     */
    fun onRegistered(token: Any) {
        actor.execute {
            if (transportToken === token) {
                transportRegistered = true
                resetNotices.onRegistered(token)
                replayPendingResults()
            }
        }
    }

    /** Returns true when the Application-scoped coordinator owns this message. */
    fun handleServerMessage(type: String, payload: JSONObject): Boolean = when (type) {
        "EXECUTE_PUSH_FILES" -> {
            actor.execute { handleCommand(payload) }
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
        else -> false
    }

    private fun buildRegistrationFields(): JSONObject {
        return buildPushRegistrationFields(
            state,
            durabilityAvailable,
            processInstanceId,
            resetNotice = if (durabilityAvailable) resetNotices.forRegistration(transportToken) else null,
            validatedOffset = ::validatedOffset,
        )
    }

    /**
     * Loads durable state and adopts it. Unparseable content is discarded and replaced
     * by an empty state, with a one-shot reset notice for the next registration. Returns
     * false when the file could not be read or the adopted state could not be saved;
     * the caller retries in that case.
     */
    private fun loadDurableState(): Boolean {
        try {
            val loaded = when (val result = store.load()) {
                is PushStateLoadResult.Valid -> result.state
                PushStateLoadResult.Missing -> store.emptyState()
                is PushStateLoadResult.Unreadable -> {
                    // The content is unknown. Never overwrite it with an empty snapshot.
                    Log.e(TAG, "Could not read durable Push/Sync state", result.error)
                    return false
                }
                is PushStateLoadResult.Corrupt -> {
                    Log.w(TAG, "Discarding unparseable durable Push/Sync state", result.error)
                    store.discard()
                    resetNotices.record(PushStateResetNotice.corruptStateDiscarded(result.error))
                    store.emptyState()
                }
            }
            if (!adoptRecoveredState(loaded)) return false
            stateLoaded = true
            return true
        } catch (error: Throwable) {
            Log.e(TAG, "Could not load durable Push/Sync state", error)
            return false
        }
    }

    /** Opens a recovery incident for a durability failure unless one is open or exhausted. */
    private fun beginRecovery() {
        if (!recoveryBudget.onFailure()) return
        Log.w(TAG, "Durable Push/Sync state is unavailable; retrying automatically")
        scheduleRecoveryAttempt()
    }

    private fun scheduleRecoveryAttempt() {
        actor.schedule({ attemptRecovery() }, recoveryRetryDelayMs, TimeUnit.MILLISECONDS)
    }

    private fun attemptRecovery() {
        val recovered = try {
            when (decidePushStateRecovery(stateLoaded, unsavedTerminal != null)) {
                PushStateRecoveryAction.Reload -> loadDurableState()
                PushStateRecoveryAction.SaveUnsavedTerminal -> {
                    val (command, execution) = requireNotNull(unsavedTerminal)
                    onTerminal(command, execution)
                    unsavedTerminal == null
                }
                PushStateRecoveryAction.ResaveCurrent -> persist(state)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Could not recover durable Push/Sync state", error)
            false
        }
        if (recovered) {
            recoveryBudget.onRecovered()
            setDurabilityAvailable(true)
            Log.i(TAG, "Durable Push/Sync state recovered")
        } else if (recoveryBudget.onAttemptFailed()) {
            scheduleRecoveryAttempt()
        } else {
            Log.e(
                TAG,
                "Durable Push/Sync state is still unavailable after " +
                    "${recoveryBudget.attemptsUsed} attempts; giving up until the client restarts",
            )
        }
    }

    /** Refreshes server-visible Push availability if the current WebSocket has sent REGISTER. */
    private fun requestRegistrationRefresh() {
        try {
            transportRegistrationRefresh?.invoke()
        } catch (error: Throwable) {
            Log.w(TAG, "Could not request Push/Sync registration refresh", error)
        }
    }

    private fun setDurabilityAvailable(available: Boolean) {
        if (durabilityAvailable == available) return
        durabilityAvailable = available
        requestRegistrationRefresh()
    }

    private fun handleCommand(payload: JSONObject) {
        val command = try {
            PushProtocol.parseCommand(payload)
        } catch (error: IllegalArgumentException) {
            rejectMalformed(payload, error.message ?: "malformed_command")
            return
        }

        // A delayed scheduler must not let an already-expired retry pause restart
        // simply because its exact command arrived first. A successful expiry still
        // lets an unrelated incoming command proceed against the new terminal state.
        if (settleExpiredInterruptedOwnership() == ExpiredOwnership.PersistenceFailed) return

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
                    // The artifact URL is a replaceable locator, not execution identity.
                    // A manual resume may therefore carry a fresh server lease URL while
                    // reusing the durable partial for this exact artifact and revision.
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
        val interruptedAt = state.active
            ?.takeIf { it.interrupted && it.command.identity == command.identity }
            ?.interruptedAt
        val nextState = state.copy(
            active = PushProtocol.Active(
                command,
                PushProtocol.PHASE_DOWNLOADING,
                interruptedAt = interruptedAt,
            ),
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
                    onValidationStart = { actor.execute { onValidationStart(command) } },
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
        if (!persist(nextState, afterPublish = { gate.release(command) })) return
        cleanupExecution(attemptDirectory(command))
        send(JSONObject().apply {
            put("type", "PUSH_RECONCILE_REPORT")
            put("job_id", command.jobId)
            put("attempt", command.attempt)
            put("artifact_id", command.artifactId)
            put("status", "absent")
            put("detail", reason)
        })
    }

    private fun onValidationStart(command: PushProtocol.Command) {
        if (!command.isJobV1 || !setPhase(command, PushProtocol.PHASE_VALIDATING)) return
        send(JSONObject().apply {
            put("type", "PUSH_PHASE")
            put("job_id", command.jobId)
            put("attempt", command.attempt)
            put("artifact_id", command.artifactId)
            put("phase", PushProtocol.PHASE_VALIDATING)
        })
    }

    private fun onTransferComplete(command: PushProtocol.Command, received: Long) {
        if (!command.isJobV1 && !setPhase(command, PushProtocol.PHASE_VALIDATING)) return
        if (command.isJobV1 && (state.active?.command?.identity != command.identity ||
            state.active?.phase != PushProtocol.PHASE_VALIDATING)) return
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
        unsavedTerminal = null
        if (!isCurrent(command)) {
            cleanupExecution(execution.workDirectory)
            return
        }
        if (execution.interrupted) {
            val active = state.active ?: return
            val nextState = state.copy(
                active = active.copy(
                    interrupted = true,
                    interruptedAt = active.interruptedAt ?: System.currentTimeMillis(),
                    interruptionReason = execution.interruptionReason,
                ),
            )
            // Preserve work. The durable interrupted state fences other jobs; release
            // the in-memory gate as recovery does, then require exact reauthorization.
            if (!persist(nextState, afterPublish = { gate.release(command) })) {
                unsavedTerminal = command to execution
                return
            }
            scheduleInterruptedExpiry(state.active)
            if (command.isJobV1) {
                val identity = PushProtocol.ReconcileIdentity(
                    requireNotNull(command.jobId),
                    command.attempt,
                    command.artifactId,
                )
                send(buildActivePushReconcileReport(
                    identity,
                    requireNotNull(state.active),
                    validatedOffset(command),
                ))
            }
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
        )) {
            unsavedTerminal = command to execution
            return
        }
        cleanupExecution(execution.workDirectory)
        send(execution.result.toJson())
    }

    private fun cleanupExecution(workDirectory: File) {
        workerExecutor.execute {
            try {
                worker.cleanup(workDirectory)
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
            val reply = buildPushReconcileReply(state, stateLoaded, identity, ::validatedOffset)
            if (reply == null) {
                Log.w(TAG, "Ignoring Push reconcile request for ${identity.jobId} until durable state is loaded")
                continue
            }
            send(reply)
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
            return Recovery(loaded.copy(active = interruptPushAfterRestart(active, System.currentTimeMillis())))
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

    /**
     * Recovers [loaded] durable state and makes it current. Returns false when the
     * recovered state could not be saved; nothing else changes in that case.
     */
    private fun adoptRecoveredState(loaded: PushProtocol.State): Boolean {
        val recovery = recover(loaded)
        if (!persist(recovery.state)) return false
        // Keep resumable job-v1 work after a process restart. An exact EXECUTE
        // command is required before a worker can resume or apply it.
        gate.restore(state.active?.takeUnless { it.interrupted }?.command)
        recovery.cleanupCommand?.let { command -> cleanupExecution(attemptDirectory(command)) }
        scheduleInterruptedExpiry(state.active)
        return true
    }

    private fun scheduleInterruptedExpiry(active: PushProtocol.Active?) {
        val delay = interruptedExpiryDelayMillis(
            active,
            System.currentTimeMillis(),
            PushFilesWorker.PARTIAL_RETENTION_MS,
        ) ?: return
        actor.schedule({ expireInterruptedOwnership() }, delay, TimeUnit.MILLISECONDS)
    }

    private enum class ExpiredOwnership { NotDue, Settled, PersistenceFailed }

    private fun expireInterruptedOwnership() {
        when (settleExpiredInterruptedOwnership()) {
            ExpiredOwnership.NotDue -> scheduleInterruptedExpiry(state.active)
            ExpiredOwnership.PersistenceFailed ->
                actor.schedule({ expireInterruptedOwnership() }, 60_000L, TimeUnit.MILLISECONDS)
            ExpiredOwnership.Settled -> Unit
        }
    }

    /** Settles only an already-expired interrupted ownership; never schedules work. */
    private fun settleExpiredInterruptedOwnership(): ExpiredOwnership {
        val settled = expireInterruptedPushState(
            state,
            System.currentTimeMillis(),
            PushFilesWorker.PARTIAL_RETENTION_MS,
            MAX_RECEIPTS,
        )
        if (settled == null) return ExpiredOwnership.NotDue
        val (nextState, receipt) = settled
        val command = receipt.command
        if (!persist(nextState, afterPublish = { gate.release(command) })) {
            return ExpiredOwnership.PersistenceFailed
        }
        cleanupExecution(attemptDirectory(command))
        if (transportRegistered) send(receipt.result.toJson())
        return ExpiredOwnership.Settled
    }

    private fun attemptDirectory(command: PushProtocol.Command) =
        PushFilesWorker.defaultAttemptDirectory(command)

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
        if (!persisted) {
            setDurabilityAvailable(false)
            beginRecovery()
        } else if (recoveryBudget.phase == PushStateRecoveryBudget.Phase.Healthy) {
            // While an incident is open (or exhausted), only a recovery attempt makes
            // state available again, so the server is told through a re-registration.
            setDurabilityAvailable(true)
        }
        return persisted
    }

    private fun validatedOffset(command: PushProtocol.Command): Long {
        return worker.validatedResumeOffset(command)
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
