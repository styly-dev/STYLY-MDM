package com.styly.mdmclient

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class WifiControllerDependencies(
    val store: WifiLocalStore,
    val adapter: WifiSettingsAdapter,
    val connectivity: WifiLocalConnectivitySource,
    val nowElapsed: () -> Long,
    val scheduleAlarm: (String, Long) -> Boolean,
    val cancelAlarm: (String) -> Unit,
    val operationExecutor: Executor,
    val rollbackExecutor: Executor,
    val scheduleWatchdog: (Runnable, Long) -> Unit,
    val readinessFailure: () -> WifiReason? = { null },
    val priorityScanSource: WifiPriorityScanSource? = null,
    val bootCount: () -> Int = { -1 },
    val schedulePriorityTimeout: (Runnable, Long) -> Unit = scheduleWatchdog,
    /** Returns a delay in 0..bound so headsets that lose Wi-Fi together do not retry together. */
    val jitter: (Long) -> Long = { 0L },
    val platformSupported: Boolean = true,
)

internal data class WifiPolicyProgress(
    val result: WifiManualResult?,
    val settled: Boolean,
    val storageError: Boolean,
)

private enum class WifiRestoreProgress {
    COMPLETE,
    WAITING_FOR_CONNECTION,
    FAILED,
}

internal const val WIFI_PRIOR_CONNECTION_OBSERVATION_MS = 2_000L

/** Process-wide Wi-Fi owner; service recreation never creates a second transaction owner. */
internal class WifiSettingsController internal constructor(
    dependencies: WifiControllerDependencies,
    autoStart: Boolean = true,
) {
    private enum class LocalAttemptPhase {
        WAITING_FOR_PRIOR_CONNECTION,
        DISCONNECTING,
        RECONNECTING,
        WAITING_FOR_FRESH_NETWORK,
        COMMITTING,
        ROLLBACK_READY,
    }

    private enum class ConnectivityAction { NONE, RECONNECT, DISCONNECT }

    private data class LocalAttempt(
        val operationId: String,
        val session: WifiLocalConnectivitySession,
        var phase: LocalAttemptPhase,
        val oldNetworkHandles: MutableSet<Long> = linkedSetOf(),
        val pendingLostHandles: MutableSet<Long> = linkedSetOf(),
        val observedLostHandles: MutableSet<Long> = linkedSetOf(),
        var disconnectCallReturned: Boolean = false,
        var disconnectCallInFlight: Boolean = false,
        val disconnectRequestedHandles: MutableSet<Long> = linkedSetOf(),
        var reconnectScheduled: Boolean = false,
        var reconnectAccepted: Boolean = false,
        var reconnectStartSequence: Long = 0L,
        var readyObservedElapsedMillis: Long? = null,
        val priorConnectionObservationDeadlineElapsedMillis: Long,
        var candidateIndex: Int = 0,
    )

    private class PriorityRuntime(val epoch: Long) {
        var connection: WifiLocalConnectivitySession? = null
        var connectionToken: Any? = null
        var scanToken: Any? = null
        var disconnectCheck: Any? = null
        var selectedWhileOffline = false
        // Rollback also updates these fields under stateLock.
        var retryAllowedAt = 0L
        var failedReturns = 0
    }

    companion object {
        private const val TAG = "WifiSettings"
        private const val WIFI_PRIORITY_CANDIDATE_TIMEOUT_MS = 12_000L
        private const val WIFI_PRIORITY_DISCONNECT_GRACE_MS = 8_000L
        private const val WIFI_AUTO_RETURN_RETRY_DELAY_MS = 5 * 60_000L
        private const val WIFI_AUTO_RETURN_MAX_RETRY_DELAY_MS = 30 * 60_000L
        private const val WIFI_RESTORE_CONFIRM_TIMEOUT_MS = 60_000L
        private const val WIFI_DISCONNECT_GRACE_JITTER_MS = 3_000L
        // A restore can fail while TobService is still binding after boot. Retry a few times.
        private val WIFI_RESTORE_FAILURE_RETRY_DELAYS_MS = longArrayOf(30_000L, 2 * 60_000L, 10 * 60_000L)
        const val ACTION_ROLLBACK = "com.styly.mdmclient.WIFI_ROLLBACK"
    }

    constructor(context: Context) : this(productionDependencies(context.applicationContext), autoStart = false)

    private val stateLock = Any()
    private val store = dependencies.store
    private val adapter = dependencies.adapter
    private val connectivity = dependencies.connectivity
    private val nowElapsed = dependencies.nowElapsed
    private val scheduleAlarm = dependencies.scheduleAlarm
    private val cancelAlarm = dependencies.cancelAlarm
    private val operationExecutor = dependencies.operationExecutor
    private val rollbackExecutor = dependencies.rollbackExecutor
    private val scheduleWatchdog = dependencies.scheduleWatchdog
    private val schedulePriorityTimeout = dependencies.schedulePriorityTimeout
    private val priorityScanSource = dependencies.priorityScanSource
    private val bootCount = dependencies.bootCount
    private val readinessFailure = dependencies.readinessFailure
    private val jitter = dependencies.jitter
    private val platformSupported = dependencies.platformSupported
    private val stateListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var unavailableReason: String? = "INITIALIZING"
    private val activeMutations = AtomicInteger(0)
    private val startup = CompletableFuture<Boolean>()
    private val startupStarted = AtomicBoolean(false)
    private var localAttempt: LocalAttempt? = null
    private var restoreOwnerOperationId: String? = null
    private var restoreSession: WifiLocalConnectivitySession? = null
    private var restoreSessionOperationId: String? = null
    private var priorityRuntime: PriorityRuntime? = null
    private var restoreFailureRetryOperationId: String? = null
    private var restoreFailureRetries = 0
    @Volatile private var priorityMonitorStatus = WifiPriorityMonitorStatus.OFF

    @Volatile private var storageHealthy = true
    private var state: WifiLocalState? = try {
        store.read()
    } catch (_: Exception) {
        storageHealthy = false
        null
    }

    init {
        if (autoStart) startStartupRecovery()
    }

    /** The service grants foreground location visibility before any configured-network reads. */
    fun startStartupRecovery() {
        if (startupStarted.compareAndSet(false, true)) {
            try {
                operationExecutor.execute(::recoverInterruptedOperation)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                startup.complete(false)
            }
        }
    }

    fun startupCompletion(): CompletableFuture<Boolean> = startup

    /** Listeners enqueue transport work only; callbacks may run under the state lock. */
    fun addStateListener(listener: () -> Unit) { stateListeners.add(listener) }
    fun removeStateListener(listener: () -> Unit) { stateListeners.remove(listener) }

    /** Cached only: registration and socket callbacks never call platform services. */
    fun stateSnapshot(): JSONObject = synchronized(stateLock) {
        val current = state
        val reason = if (!storageHealthy || current == null) "STORAGE_ERROR" else unavailableReason
        JSONObject().apply {
            put("managed", current?.managedWifi ?: JSONObject.NULL)
            put("policy_revision", current?.policyRevision ?: JSONObject.NULL)
            put("busy", !startup.isDone || current?.manualOperation != null || activeMutations.get() > 0)
            put("available", reason == null)
            put("unavailable_reason", reason ?: JSONObject.NULL)
            put("priority_monitor", priorityMonitorStatus.wireValue)
            put("last_result", current?.latestResult?.let { result ->
                JSONObject().apply {
                    put("operation_id", result.operationId)
                    put("revision", result.revision)
                    put("status", result.status)
                    put("reason", result.reason.wireValue)
                    put("rolled_back", result.rolledBack)
                }
            } ?: JSONObject.NULL)
        }
    }

    fun refreshReadiness() { operationExecutor.execute(::updateReadiness) }

    private fun updateReadiness() {
        val next = if (!platformSupported) WifiReason.UNSUPPORTED.wireValue else try {
            readinessFailure()?.wireValue
        } catch (_: Exception) {
            WifiReason.UNKNOWN.wireValue
        }
        if (next == unavailableReason) return
        unavailableReason = next
        notifyStateChanged()
    }

    private fun notifyStateChanged() {
        scheduleReleaseCheck()
        stateListeners.forEach { listener ->
            try { listener() } catch (_: Exception) { /* Transport failure cannot undo local state. */ }
        }
    }

    fun onRollbackAlarm(intent: Intent?) {
        val operationId = intent?.getStringExtra("operation_id") ?: return
        val alarmDeadline = intent.getLongExtra("deadline_elapsed_ms", -1L)
        onRollbackDeadline(operationId, alarmDeadline)
    }

    internal fun onRollbackDeadline(operationId: String, alarmDeadline: Long) {
        // Startup recovery is the sole restoration owner until it completes. It already
        // inspects every durable manual journal, so a cold-start alarm can safely defer.
        if (!platformSupported || !startup.isDone) return
        val operation = synchronized(stateLock) { state?.manualOperation?.takeIf { it.operationId == operationId } }
            ?: return
        if (alarmDeadline < 0 || operation.deadlineElapsedMillis != alarmDeadline ||
            nowElapsed() < alarmDeadline
        ) return
        requestRollback(operationId, WifiReason.TIMEOUT_ROLLED_BACK)
    }

    fun applyManagedPolicy(
        operationId: String, revision: String, settings: JSONObject, reuseMatchingProfile: Boolean = false,
    ) {
        if (!WifiSettingsProtocol.isValidUuid(operationId) || !WifiSettingsProtocol.isValidUuid(revision)) return
        val reuse = synchronized(stateLock) {
            val current = state ?: return
            if (current.policyReceipt?.operationId == operationId) return
            val receipt = WifiPolicyReceipt(operationId, revision)
            // The same commit claims the handoff and supersedes any earlier release. A later
            // release is then durable even while the admitted request is still queued.
            if (!writeAndCommit(current.copy(policyReceipt = receipt, pendingRelease = null))) return
            reuseMatchingProfile && current.pendingRelease == null
        }
        operationExecutor.execute {
            updateReadiness()
            acceptPolicyRequest(operationId, revision, settings, reuse)
        }
    }

    /** Carries a verified profile forward without a platform write or reconnect. */
    private fun reuseAppliedProfile(operationId: String, requested: WifiSavedProfile): Boolean {
        synchronized(stateLock) {
            val current = state ?: return false
            val applied = current.lastApplied ?: return false
            if (!startup.isDone || !storageHealthy || !current.managedWifi || current.policyRevision != applied.revision ||
                current.manualOperation != null || current.policyReceipt?.operationId != operationId ||
                !applied.hasSameSettings(requested)
            ) return false
            val result = WifiManualResult(operationId, requested.revision, "success", WifiReason.NONE, false)
            if (!writeAndCommit(current.withAppliedProfile(requested).withOperationResult(result))) return true
        }
        reconcilePriorityRuntime()
        return true
    }

    /** Reassignment resumes an already applied policy without repeating its settings writes. */
    fun resumeAppliedPolicy(revision: String, wifi: JSONObject, isCurrent: () -> Boolean) {
        if (!platformSupported) return
        val requested = try {
            WifiSettingsProtocol.parseManagedPolicy(revision, wifi)
        } catch (_: Exception) {
            return
        }
        startup.whenComplete { _, _ ->
            operationExecutor.execute {
                val resumed = synchronized(stateLock) {
                    if (!isCurrent() || !storageHealthy) return@synchronized false
                    val current = state ?: return@synchronized false
                    val applied = current.lastApplied ?: return@synchronized false
                    if (!applied.hasSameSettings(requested)) return@synchronized false
                    val operation = current.manualOperation
                    // A matching automatic selection can finish under its existing epoch.
                    if (operation != null && (!operation.selectionOnly || operation.revision != revision ||
                            applied.revision != revision)
                    ) return@synchronized false
                    val next = current.withAppliedProfile(requested).copy(pendingRelease = null)
                    next != current && writeAndCommit(next)
                }
                if (resumed) reconcilePriorityRuntimeNow()
            }
        }
    }

    private fun WifiLocalState.withAppliedProfile(
        profile: WifiSavedProfile,
        rewroteWifi: Boolean = false,
    ): WifiLocalState {
        val newGeneration = !managedWifi || lastApplied?.hasSameSettings(profile) != true ||
            (rewroteWifi && policyRevision != profile.revision)
        return copy(
            managedWifi = true,
            policyRevision = profile.revision,
            lastApplied = profile,
            policyEpoch = if (newGeneration) policyEpoch + 1 else policyEpoch,
        )
    }

    /** Wait for a live rollback. A new policy attempt can retry a settled restore failure. */
    fun readyForPolicy(): Boolean = synchronized(stateLock) { readyForPolicyLocked() }

    fun policyProgress(operationId: String, revision: String): WifiPolicyProgress = synchronized(stateLock) {
        val current = state
        val operation = current?.manualOperation?.takeIf { it.operationId == operationId }
        val settledNow = readyForPolicyLocked() ||
            (!storageHealthy && startup.isDone && current?.manualOperation == null && activeMutations.get() == 0)
        val result = current?.policyReceipt?.takeIf {
            it.operationId == operationId && it.revision == revision
        }?.result
        val unconfirmedRestoreIsTerminal = operation?.settingsRestored == true &&
            result?.reason == WifiReason.RESTORE_UNCONFIRMED
        WifiPolicyProgress(
            result,
            settledNow || unconfirmedRestoreIsTerminal,
            !storageHealthy,
        )
    }

    private fun readyForPolicyLocked(): Boolean {
        if (!startup.isDone || !storageHealthy) return false
        val operation = state?.manualOperation
        return operation == null || isAbandonable(operation) ||
            (operation.settingsRestored && activeMutations.get() == 0 && restoreOwnerOperationId == null)
    }

    /**
     * Releases Wi-Fi ownership. The request is journaled first. While an operation (including
     * an automatic switch or a restore reconnect) can still mutate Wi-Fi, it waits and is applied
     * once that operation settles, including after a process restart.
     */
    fun stopManagingPolicy(revision: String? = null) {
        synchronized(stateLock) {
            val current = state ?: return
            if (!releaseNeeded(current) && current.policyReceipt?.awaitsOperation(current) != true) return
            val request = WifiPendingRelease(revision)
            if (request != current.pendingRelease) writeAndCommit(current.copy(pendingRelease = request))
        }
    }

    private fun WifiPolicyReceipt.awaitsOperation(current: WifiLocalState): Boolean =
        result == null && operationId != current.manualOperation?.operationId

    private fun releaseNeeded(current: WifiLocalState): Boolean =
        current.manualOperation != null || (current.managedWifi && current.policyRevision != null)

    private fun scheduleReleaseCheck() {
        val waiting = synchronized(stateLock) { state?.pendingRelease != null }
        if (waiting) operationExecutor.execute(::processPendingRelease)
    }

    private fun releaseBlocksSelectionLocked(operation: WifiManualOperation): Boolean =
        operation.selectionOnly && !operation.settingsChanged && state?.pendingRelease != null

    private fun releaseWaitingForSelection(operationId: String): Boolean = synchronized(stateLock) {
        state?.manualOperation?.let {
            it.operationId == operationId && releaseBlocksSelectionLocked(it)
        } == true
    }

    /** Runs on the operation executor only. Clears the request only with the durable release. */
    private fun processPendingRelease() {
        var closeRestoreSession: WifiLocalConnectivitySession? = null
        val abandoned = synchronized(stateLock) {
            val current = state ?: return
            val request = current.pendingRelease ?: return
            if (!startup.isDone || current.policyReceipt?.awaitsOperation(current) == true) return
            if (!releaseNeeded(current)) {
                writeAndCommit(current.copy(pendingRelease = null))
                return
            }
            val active = current.manualOperation
            if (active != null && !canReleaseDuring(active)) return
            val revision = request.revision ?: active?.revision ?: checkNotNull(current.policyRevision)
            val terminal = WifiManualResult(UUID.randomUUID().toString(), revision, "success", WifiReason.NONE, false)
            val receipt = current.policyReceipt
            val next = current.copy(
                managedWifi = false,
                manualOperation = null,
                policyEpoch = current.policyEpoch + 1L,
                policyRevision = revision,
                latestResult = terminal,
                policyReceipt = if (receipt != null && receipt.result == null &&
                    receipt.operationId == active?.operationId
                ) receipt.interrupted() else receipt,
                pendingRelease = null,
            )
            if (!writeAndCommit(next)) return
            closeRestoreSession = active?.let { takeRestoreSessionLocked(it.operationId) }
            active
        }
        closeRestoreSession?.close()
        abandoned?.let {
            cancelAlarm(it.operationId)
            closeAttempt(it.operationId)
        }
        reconcilePriorityRuntimeNow()
    }

    private fun WifiPolicyReceipt.interrupted() =
        copy(result = WifiManualResult(operationId, revision, "unknown", WifiReason.UNKNOWN, false))

    /** A release can abandon an untouched selection or wait for an active mutation to settle. */
    private fun canReleaseDuring(operation: WifiManualOperation): Boolean =
        activeMutations.get() == 0 && restoreOwnerOperationId == null &&
            ((operation.selectionOnly && !operation.settingsChanged) ||
                (operation.settingsRestored && operation.phase == WifiManualPhase.RESTORING_CONNECTION) ||
                isAbandonable(operation))

    private fun isAbandonable(operation: WifiManualOperation): Boolean =
        operation.phase == WifiManualPhase.ROLLING_BACK && operation.failureReason == WifiReason.RESTORE_FAILED &&
            startup.isDone && activeMutations.get() == 0 && restoreOwnerOperationId == null

    private fun acceptPolicyRequest(
        operationId: String, revision: String, settings: JSONObject, reuseMatchingProfile: Boolean,
    ) {
        fun fail(reason: WifiReason) {
            persist { it.withOperationResult(WifiManualResult(operationId, revision, "failed", reason, false)) }
        }

        val claimed = synchronized(stateLock) {
            state?.policyReceipt?.let { it.operationId == operationId && it.result == null } == true
        }
        if (!claimed) return
        try {
            val requested = WifiSettingsProtocol.parseManagedPolicy(revision, settings.getJSONObject("wifi"))
            if (!platformSupported || (requested.autoReturn && priorityScanSource == null)) {
                fail(WifiReason.UNSUPPORTED)
                return
            }
            val unconfirmedRestore = synchronized(stateLock) {
                state?.manualOperation?.takeIf { it.settingsRestored && it.phase == WifiManualPhase.RESTORING_CONNECTION }
            }
            if (unconfirmedRestore != null && !supersedeRestoredConnection(unconfirmedRestore.operationId)) {
                fail(WifiReason.BUSY)
                return
            }
            val failedRestore = synchronized(stateLock) { state?.manualOperation?.takeIf(::isAbandonable) }
            if (failedRestore != null) {
                when (restoreClaimedOperation(
                    failedRestore.operationId, WifiReason.RESTORE_FAILED, allowExistingClaim = true,
                )) {
                    WifiRestoreProgress.COMPLETE -> Unit
                    WifiRestoreProgress.FAILED -> {
                        fail(WifiReason.RESTORE_FAILED)
                        return
                    }
                    WifiRestoreProgress.WAITING_FOR_CONNECTION -> {
                        if (!supersedeRestoredConnection(failedRestore.operationId)) {
                            fail(WifiReason.BUSY)
                            return
                        }
                    }
                }
            }
            if (reuseMatchingProfile && reuseAppliedProfile(operationId, requested)) return
            val current = synchronized(stateLock) { state } ?: return
            if (current.manualOperation != null) {
                fail(WifiReason.BUSY)
                return
            }
            val observationFailure = connectivity.preflight()
            if (observationFailure != null) {
                fail(observationFailure)
                return
            }
            val recoverySnapshot = adapter.captureRecovery(current.lastApplied?.networks.orEmpty())
            val operation = synchronized(stateLock) {
                val old = state ?: return@synchronized null
                if (old.manualOperation != null || old.lastApplied != current.lastApplied ||
                    old.policyReceipt?.operationId != operationId
                ) return@synchronized null
                val prepared = WifiManualOperation(
                    operationId = operationId,
                    revision = requested.revision,
                    candidate = requested.networks,
                    recoverySnapshot = recoverySnapshot,
                    phase = WifiManualPhase.PREPARED,
                    policyEpoch = old.policyEpoch,
                    autoReturn = requested.autoReturn,
                )
                if (writeAndCommit(old.copy(manualOperation = prepared))) prepared else null
            }
            if (operation == null) {
                fail(WifiReason.BUSY)
                return
            }

            operationExecutor.execute { applyManual(operation) }
        } catch (failure: WifiAdapterFailure) {
            fail(failure.reason)
        } catch (failure: WifiPolicyFailure) {
            fail(failure.reason)
        } catch (_: IllegalArgumentException) {
            fail(WifiReason.UNSUPPORTED)
        } catch (_: Exception) {
            fail(WifiReason.CONFIG_READ_FAILED)
        }
    }

    private fun supersedeRestoredConnection(operationId: String): Boolean {
        val close = synchronized(stateLock) {
            val old = state ?: return false
            // The previous connection may already have been confirmed by its callback.
            val current = old.manualOperation ?: return storageHealthy
            if (current.operationId != operationId || !current.settingsRestored ||
                current.phase != WifiManualPhase.RESTORING_CONNECTION ||
                activeMutations.get() > 0 || restoreOwnerOperationId != null
            ) return false
            if (!writeAndCommit(old.copy(manualOperation = null))) return false
            takeRestoreSessionLocked(operationId)
        }
        close?.close()
        cancelAlarm(operationId)
        return true
    }

    private fun applyManual(operation: WifiManualOperation) {
        if (releaseWaitingForSelection(operation.operationId)) return
        val session = try {
            connectivity.observe { event -> onConnectivityEvent(operation.operationId, event) }
        } catch (_: SecurityException) {
            finishManualFailure(operation.operationId, WifiReason.PERMISSION_DENIED, rolledBack = false)
            return
        } catch (_: Exception) {
            finishManualFailure(operation.operationId, WifiReason.UNSUPPORTED, rolledBack = false)
            return
        }
        val installed = synchronized(stateLock) {
            val current = state?.manualOperation?.takeIf {
                it.operationId == operation.operationId && it.phase == WifiManualPhase.PREPARED
            } ?: return@synchronized false
            localAttempt = LocalAttempt(
                operationId = current.operationId,
                session = session,
                phase = LocalAttemptPhase.WAITING_FOR_PRIOR_CONNECTION,
                priorConnectionObservationDeadlineElapsedMillis =
                    nowElapsed() + WIFI_PRIOR_CONNECTION_OBSERVATION_MS,
                candidateIndex = current.selectionIndexes?.firstOrNull() ?: 0,
            )
            // Keep the durable phase at PREPARED until an API mutation starts.
            current.phase == WifiManualPhase.PREPARED
        }
        if (!installed) {
            session.close()
            return
        }

        resolvePriorConnection(operation.operationId, observationWindowExpired = false)
        val stillWaiting = synchronized(stateLock) {
            localAttempt?.let {
                it.operationId == operation.operationId &&
                    it.phase == LocalAttemptPhase.WAITING_FOR_PRIOR_CONNECTION
            } == true
        }
        if (stillWaiting) {
            val delay = synchronized(stateLock) {
                localAttempt?.takeIf { it.operationId == operation.operationId }
                    ?.priorConnectionObservationDeadlineElapsedMillis?.let { (it - nowElapsed()).coerceAtLeast(0L) }
            } ?: 0L
            scheduleWatchdog(Runnable {
                operationExecutor.execute {
                    resolvePriorConnection(
                        operation.operationId,
                        observationWindowExpired = true,
                        acceptIdentifiedConnection = false,
                    )
                }
            }, delay)
        }
    }

    /** Do not mutate configured networks until the current Wi-Fi handle is identifiable. */
    private fun resolvePriorConnection(
        operationId: String,
        observationWindowExpired: Boolean,
        acceptIdentifiedConnection: Boolean = !observationWindowExpired,
    ) {
        if (releaseWaitingForSelection(operationId)) return
        var joinedByOs = false
        val resolved = synchronized(stateLock) {
            val currentState = state ?: return
            val current = currentState.manualOperation?.takeIf {
                it.operationId == operationId && it.phase == WifiManualPhase.PREPARED &&
                    it.policyEpoch == currentState.policyEpoch
            } ?: return
            val attempt = localAttempt?.takeIf {
                it.operationId == operationId && it.phase == LocalAttemptPhase.WAITING_FOR_PRIOR_CONNECTION
            } ?: return
            val usable = attempt.session.snapshot().filter { it.hasUsableIp }
            // A reselection after a disconnect starts offline. A usable connection seen now was
            // joined by the OS meanwhile; keep it instead of treating it as the one to replace.
            if (current.retryDisconnected && usable.isNotEmpty()) {
                if (!writeAndCommit(currentState.copy(manualOperation = null))) return
                joinedByOs = true
                return@synchronized null
            }
            val previous = usable.singleOrNull()
            val previousSsid = previous?.ssid
            val previousSecurity = previous?.security
            val target = if (previousSsid != null && previousSecurity != null &&
                previousSecurity in setOf("OPEN", "WPA_PSK", "SAE")
            ) {
                WifiRollbackTarget(previousSsid, requireNotNull(previousSecurity))
            } else null
            val restorableTarget = target?.takeIf {
                hasUniqueSavedRestoreTarget(it, current.recoverySnapshot)
            }

            if (restorableTarget != null && acceptIdentifiedConnection) {
                val prepared = current.copy(priorConnectionHadUsableIp = true, restoreTarget = restorableTarget)
                if (!writeAndCommit(currentState.copy(manualOperation = prepared))) return
                attempt.phase = LocalAttemptPhase.RECONNECTING
                prepared
            } else if (!observationWindowExpired) {
                return
            } else if (usable.isEmpty()) {
                val prepared = current.copy(priorConnectionHadUsableIp = false, restoreTarget = null)
                if (!writeAndCommit(currentState.copy(manualOperation = prepared))) return
                attempt.phase = LocalAttemptPhase.RECONNECTING
                prepared
            } else {
                null
            }
        }

        if (joinedByOs) {
            closeAttempt(operationId)
            reconcilePriorityRuntime()
            return
        }
        if (resolved == null) {
            if (observationWindowExpired) {
                closeAttempt(operationId)
                finishManualFailure(operationId, WifiReason.VERIFY_FAILED, rolledBack = false)
            }
            return
        }
        applyPreparedManual(resolved)
    }

    private fun hasUniqueSavedRestoreTarget(
        target: WifiRollbackTarget,
        snapshot: WifiRecoverySnapshot,
    ): Boolean = snapshot.networks.count { network ->
        network.ssid == target.ssid && network.securities.any { savedSecurity ->
            when (target.security) {
                "OPEN" -> savedSecurity == "OPEN"
                "WPA_PSK", "SAE" -> savedSecurity in setOf("WPA_PSK", "SAE", "WPA2_WPA3")
                else -> false
            }
        }
    } == 1

    private fun applyPreparedManual(operationToApply: WifiManualOperation) {
        val operationId = operationToApply.operationId

        val applyReason = if (operationToApply.selectionOnly) {
            WifiReason.NONE
        } else {
            adapter.applyProfile(
                target = operationToApply.candidate,
                shouldContinue = {
                    synchronized(stateLock) {
                        val current = state ?: return@synchronized false
                        val latest = current.manualOperation ?: return@synchronized false
                        latest.operationId == operationId && latest.policyEpoch == current.policyEpoch &&
                            (latest.phase == WifiManualPhase.PREPARED || latest.phase == WifiManualPhase.APPLYING) &&
                            (latest.deadlineElapsedMillis == null || nowElapsed() < latest.deadlineElapsedMillis)
                    }
                },
                beforeFirstMutation = { prepareManualDeadline(operationId) },
                onMutationStarted = { beginManualMutation(operationId) },
                onMutationFinished = { finishMutation(operationId) },
            )
        }

        val latest = synchronized(stateLock) { state?.manualOperation }
            ?.takeIf { it.operationId == operationToApply.operationId } ?: return
        if (latest.phase == WifiManualPhase.ROLLING_BACK) {
            continueClaimedRollback(operationToApply.operationId)
            return
        }
        if (applyReason == WifiReason.NONE) {
            beginLocalReconnectFlow(operationToApply.operationId)
            return
        }

        val changed = latest.settingsChanged || activeMutations.get() != 0
        if (changed) requestRollback(operationToApply.operationId, applyReason)
        else {
            closeAttempt(operationToApply.operationId)
            finishManualFailure(operationToApply.operationId, applyReason, rolledBack = false)
        }
    }

    private fun beginLocalReconnectFlow(operationId: String) {
        val attempt = synchronized(stateLock) {
            localAttempt?.takeIf { it.operationId == operationId }
        } ?: return
        val deadlineFailure = prepareManualDeadline(operationId)
        if (deadlineFailure != null) {
            failManualBeforeReadiness(operationId, deadlineFailure)
            return
        }
        val currentNetworks = attempt.session.snapshot()
        val handles = currentNetworks.map { it.networkHandle }.toSet()
        if (handles.isEmpty()) {
            runReconnect(operationId)
            return
        }

        disconnectCurrentNetworks(operationId, handles)
    }

    private fun disconnectCurrentNetworks(operationId: String, handles: Set<Long>) {
        val shouldCallDisconnect = synchronized(stateLock) {
            val operation = state?.manualOperation?.takeIf {
                it.operationId == operationId && it.phase == WifiManualPhase.APPLYING
            } ?: return@synchronized null
            if (operation.deadlineElapsedMillis?.let { nowElapsed() >= it } == true) return@synchronized null
            val active = localAttempt?.takeIf { it.operationId == operationId } ?: return@synchronized null
            if (active.phase != LocalAttemptPhase.DISCONNECTING) {
                active.phase = LocalAttemptPhase.DISCONNECTING
                active.pendingLostHandles.clear()
                active.disconnectRequestedHandles.clear()
                active.disconnectCallReturned = false
                active.disconnectCallInFlight = false
            }
            for (handle in handles) {
                active.oldNetworkHandles.add(handle)
                if (handle !in active.observedLostHandles) active.pendingLostHandles.add(handle)
            }
            val newlySeen = active.oldNetworkHandles - active.disconnectRequestedHandles
            if (newlySeen.isNotEmpty() && !active.disconnectCallInFlight) {
                active.disconnectRequestedHandles.addAll(newlySeen)
                active.disconnectCallInFlight = true
                active.disconnectCallReturned = false
                true
            } else {
                false
            }
        }
        if (shouldCallDisconnect == null) {
            requestRollback(operationId, WifiReason.TIMEOUT_ROLLED_BACK)
            return
        }
        if (!shouldCallDisconnect) return

        val reason = adapter.disconnectCurrent(
            shouldContinue = { manualOperationCanMutate(operationId) },
            onMutationStarted = { beginManualMutation(operationId) },
            onMutationFinished = { finishMutation(operationId) },
        )
        if (reason != WifiReason.NONE) {
            failManualBeforeReadiness(operationId, reason)
            return
        }

        val action = synchronized(stateLock) {
            val active = localAttempt?.takeIf { it.operationId == operationId } ?: return@synchronized false
            val current = state ?: return@synchronized false
            val operation = current.manualOperation?.takeIf {
                it.operationId == operationId && it.phase == WifiManualPhase.APPLYING &&
                    it.policyEpoch == current.policyEpoch
            } ?: return@synchronized false
            if (operation.deadlineElapsedMillis?.let { nowElapsed() >= it } == true) return@synchronized false
            active.disconnectCallInFlight = false
            val newlySeen = active.oldNetworkHandles - active.disconnectRequestedHandles
            if (newlySeen.isNotEmpty()) {
                return@synchronized ConnectivityAction.DISCONNECT
            }
            active.disconnectCallReturned = true
            if (scheduleReconnectLocked(active)) ConnectivityAction.RECONNECT else ConnectivityAction.NONE
        }
        if (action == ConnectivityAction.DISCONNECT) disconnectCurrentNetworks(operationId, emptySet())
        else if (action == ConnectivityAction.RECONNECT) runReconnect(operationId)
        else if (synchronized(stateLock) {
                state?.manualOperation?.let { it.operationId == operationId && it.phase == WifiManualPhase.ROLLING_BACK }
            } == true
        ) continueClaimedRollback(operationId)
    }

    /** Must be called with stateLock held. */
    private fun scheduleReconnectLocked(attempt: LocalAttempt): Boolean {
        if (attempt.pendingLostHandles.isNotEmpty() || attempt.disconnectCallInFlight ||
            !attempt.disconnectCallReturned || attempt.reconnectScheduled
        ) {
            return false
        }
        attempt.reconnectScheduled = true
        attempt.phase = LocalAttemptPhase.RECONNECTING
        return true
    }

    private fun runReconnect(operationId: String) {
        val pendingAttempt = synchronized(stateLock) {
            localAttempt?.takeIf {
                it.operationId == operationId && it.phase == LocalAttemptPhase.RECONNECTING
            }
        } ?: return
        if (pendingAttempt.oldNetworkHandles.isEmpty()) {
            val lateNetworks = pendingAttempt.session.snapshot().map { it.networkHandle }.toSet()
            if (lateNetworks.isNotEmpty()) {
                disconnectCurrentNetworks(operationId, lateNetworks)
                return
            }
        }

        val selection = synchronized(stateLock) {
            val active = localAttempt?.takeIf {
                it.operationId == operationId && it.phase == LocalAttemptPhase.RECONNECTING
            } ?: return
            val operation = state?.manualOperation?.takeIf {
                it.operationId == operationId && it.phase == WifiManualPhase.APPLYING
            } ?: return
            if (operation.deadlineElapsedMillis?.let { nowElapsed() >= it } == true) return
            if (operation.policyEpoch != state?.policyEpoch) return
            active.reconnectStartSequence = active.session.lastSequence()
            active.reconnectAccepted = false
            // Candidates (1..4) and selection indexes are validated before the journal is written.
            active to operation.candidate[active.candidateIndex]
        }
        val attempt = selection.first
        val selectedProfile = selection.second

        val reason = adapter.selectNetwork(
            profile = selectedProfile,
            shouldContinue = { manualOperationCanMutate(operationId) },
            onMutationStarted = { beginManualMutation(operationId) },
            onMutationFinished = { finishMutation(operationId) },
        )
        if (reason != WifiReason.NONE) {
            advancePriorityCandidate(operationId, attempt.candidateIndex, reason)
            return
        }
        val accepted = synchronized(stateLock) {
            val active = localAttempt?.takeIf {
                it.operationId == operationId && it.phase == LocalAttemptPhase.RECONNECTING
            } ?: return@synchronized false
            val operation = state?.manualOperation?.takeIf {
                it.operationId == operationId && it.phase == WifiManualPhase.APPLYING
            } ?: return@synchronized false
            if (operation.deadlineElapsedMillis?.let { nowElapsed() >= it } == true) return@synchronized false
            active.reconnectAccepted = true
            active.phase = LocalAttemptPhase.WAITING_FOR_FRESH_NETWORK
            true
        }
        if (!accepted) {
            continueClaimedRollback(operationId)
            return
        }
        val operation = synchronized(stateLock) { state?.manualOperation?.takeIf { it.operationId == operationId } }
        val deadline = operation?.deadlineElapsedMillis ?: return
        val expectedCandidateIndex = attempt.candidateIndex
        val delay = (deadline - nowElapsed()).coerceAtLeast(0L)
            .coerceAtMost(WIFI_PRIORITY_CANDIDATE_TIMEOUT_MS)
        schedulePriorityTimeout(Runnable {
            operationExecutor.execute { advancePriorityCandidate(operationId, expectedCandidateIndex) }
        }, delay)
        evaluateFreshSnapshot(attempt)
    }

    private fun advancePriorityCandidate(
        operationId: String,
        expectedIndex: Int,
        selectionFailure: WifiReason? = null,
    ) {
        var nextIndex: Int? = null
        var handles: Set<Long> = emptySet()
        var rollbackReason: WifiReason? = null
        synchronized(stateLock) {
            val current = state ?: return
            val operation = current.manualOperation?.takeIf {
                it.operationId == operationId && it.phase == WifiManualPhase.APPLYING &&
                    it.policyEpoch == current.policyEpoch
            } ?: return
            if (releaseBlocksSelectionLocked(operation)) return
            val active = localAttempt?.takeIf { it.operationId == operationId } ?: return
            if (active.candidateIndex != expectedIndex ||
                active.phase !in setOf(LocalAttemptPhase.RECONNECTING, LocalAttemptPhase.WAITING_FOR_FRESH_NETWORK)
            ) return
            val deadline = operation.deadlineElapsedMillis ?: return
            if (nowElapsed() >= deadline) {
                rollbackReason = WifiReason.TIMEOUT_ROLLED_BACK
                return@synchronized
            }
            if (selectionFailure != null && selectionFailure !in setOf(WifiReason.UPDATE_FAILED, WifiReason.VERIFY_FAILED)) {
                rollbackReason = selectionFailure
                return@synchronized
            }
            val indexes = operation.selectionIndexes ?: operation.candidate.indices.toList()
            val nextPosition = indexes.indexOf(expectedIndex) + 1
            nextIndex = indexes.getOrNull(nextPosition)
            if (nextIndex == null) {
                rollbackReason = WifiReason.UNREACHABLE
                return@synchronized
            }
            active.candidateIndex = checkNotNull(nextIndex)
            active.phase = LocalAttemptPhase.DISCONNECTING
            active.reconnectScheduled = false
            active.reconnectAccepted = false
            active.pendingLostHandles.clear()
            active.disconnectRequestedHandles.clear()
            active.disconnectCallReturned = false
            active.disconnectCallInFlight = false
            handles = active.session.snapshot().map { it.networkHandle }.toSet()
            if (handles.isEmpty()) {
                active.phase = LocalAttemptPhase.RECONNECTING
                active.reconnectScheduled = true
            }
        }
        rollbackReason?.let {
            requestRollback(operationId, it)
            return
        }
        if (handles.isEmpty()) runReconnect(operationId)
        else disconnectCurrentNetworks(operationId, handles)
    }

    private fun onConnectivityEvent(operationId: String, event: WifiLocalConnectivityEvent) {
        val waitingForPriorConnection = synchronized(stateLock) {
            localAttempt?.let {
                it.operationId == operationId && it.phase == LocalAttemptPhase.WAITING_FOR_PRIOR_CONNECTION
            } == true && state?.manualOperation?.let {
                it.operationId == operationId && it.phase == WifiManualPhase.PREPARED
            } == true
        }
        if (waitingForPriorConnection) {
            val acceptIdentifiedConnection = synchronized(stateLock) {
                localAttempt?.takeIf { it.operationId == operationId }
                    ?.priorConnectionObservationDeadlineElapsedMillis?.let { nowElapsed() < it } == true
            }
            operationExecutor.execute {
                resolvePriorConnection(
                    operationId,
                    observationWindowExpired = !acceptIdentifiedConnection,
                    acceptIdentifiedConnection = acceptIdentifiedConnection,
                )
            }
            return
        }

        val action = synchronized(stateLock) {
            val attempt = localAttempt?.takeIf { it.operationId == operationId } ?: return
            val operation = state?.manualOperation?.takeIf {
                it.operationId == operationId && it.phase == WifiManualPhase.APPLYING
            } ?: return
            if (operation.deadlineElapsedMillis?.let { nowElapsed() >= it } == true) return
            if (event is WifiLocalConnectivityEvent.Updated && attempt.phase == LocalAttemptPhase.DISCONNECTING) {
                val wasNew = attempt.oldNetworkHandles.add(event.observation.networkHandle)
                val handle = event.observation.networkHandle
                if (handle !in attempt.observedLostHandles) attempt.pendingLostHandles.add(handle)
                if (wasNew && handle !in attempt.observedLostHandles && !attempt.disconnectCallInFlight) {
                    attempt.disconnectCallReturned = false
                    return@synchronized ConnectivityAction.DISCONNECT
                }
            }
            if (event is WifiLocalConnectivityEvent.Lost && attempt.phase == LocalAttemptPhase.DISCONNECTING) {
                attempt.observedLostHandles.add(event.networkHandle)
                attempt.pendingLostHandles.remove(event.networkHandle)
                return@synchronized if (scheduleReconnectLocked(attempt)) {
                    ConnectivityAction.RECONNECT
                } else {
                    ConnectivityAction.NONE
                }
            }
            if (event is WifiLocalConnectivityEvent.Lost) attempt.observedLostHandles.add(event.networkHandle)
            ConnectivityAction.NONE
        }
        if (action == ConnectivityAction.DISCONNECT) {
            operationExecutor.execute { disconnectCurrentNetworks(operationId, emptySet()) }
            return
        }
        if (action == ConnectivityAction.RECONNECT) {
            operationExecutor.execute { runReconnect(operationId) }
            return
        }
        evaluateFreshEvent(operationId, event)
    }

    private fun evaluateFreshSnapshot(attempt: LocalAttempt) {
        for (observation in attempt.session.snapshot()) {
            if (latchFreshReadiness(attempt.operationId, observation)) {
                operationExecutor.execute { commitLocalSuccess(attempt.operationId) }
                return
            }
        }
    }

    private fun evaluateFreshEvent(operationId: String, event: WifiLocalConnectivityEvent) {
        val observation = (event as? WifiLocalConnectivityEvent.Updated)?.observation ?: return
        if (latchFreshReadiness(operationId, observation)) {
            operationExecutor.execute { commitLocalSuccess(operationId) }
        }
    }

    private fun latchFreshReadiness(operationId: String, observation: WifiLocalNetworkObservation): Boolean =
        synchronized(stateLock) {
            val attempt = localAttempt?.takeIf {
                it.operationId == operationId &&
                    it.phase == LocalAttemptPhase.WAITING_FOR_FRESH_NETWORK &&
                    it.reconnectAccepted
            } ?: return@synchronized false
            val current = state ?: return@synchronized false
            val operation = current.manualOperation?.takeIf {
                it.operationId == operationId && it.phase == WifiManualPhase.APPLYING &&
                    it.policyEpoch == current.policyEpoch
            } ?: return@synchronized false
            val deadline = operation.deadlineElapsedMillis ?: return@synchronized false
            val observedAt = nowElapsed()
            val expected = operation.candidate.getOrNull(attempt.candidateIndex) ?: return@synchronized false
            val matchingNetwork = expected.ssid == observation.ssid &&
                wifiSecurityViewMatchesProfile(expected, observation.security)
            val ready = observedAt < deadline &&
                activeMutations.get() == 0 &&
                observation.sequence > attempt.reconnectStartSequence &&
                observation.networkHandle !in attempt.oldNetworkHandles &&
                matchingNetwork &&
                observation.hasUsableIp
            if (ready) {
                attempt.readyObservedElapsedMillis = observedAt
                attempt.phase = LocalAttemptPhase.COMMITTING
            }
            ready
        }

    private fun commitLocalSuccess(operationId: String) {
        var completedSession: WifiLocalConnectivitySession? = null
        var completedOperation: WifiManualOperation? = null
        var rollback = false
        val committed = synchronized(stateLock) {
            val old = state ?: return
            val operation = old.manualOperation?.takeIf {
                it.operationId == operationId && it.phase == WifiManualPhase.APPLYING
            } ?: return
            val attempt = localAttempt?.takeIf {
                it.operationId == operationId && it.phase == LocalAttemptPhase.COMMITTING
            } ?: return
            val deadline = operation.deadlineElapsedMillis ?: return
            val observedAt = attempt.readyObservedElapsedMillis ?: return
            if (observedAt >= deadline || activeMutations.get() != 0) return
            if (operation.policyEpoch != old.policyEpoch) {
                attempt.phase = LocalAttemptPhase.ROLLBACK_READY
                rollback = true
                return@synchronized false
            }
            val terminal = WifiManualResult(operationId, operation.revision, "success", WifiReason.NONE, false)
            val applied = if (operation.selectionOnly) old else old.withAppliedProfile(
                WifiSavedProfile(operation.revision, operation.candidate, operation.autoReturn),
                rewroteWifi = true,
            )
            val next = applied.copy(manualOperation = null).withOperationResult(terminal)
            if (writeAndCommit(next)) {
                delayAutomaticReturn(operation, succeeded = true)
                localAttempt = null
                completedSession = attempt.session
                completedOperation = operation
                restoreOwnerOperationId = null
                true
            } else {
                attempt.phase = LocalAttemptPhase.ROLLBACK_READY
                rollback = true
                false
            }
        }
        if (committed) {
            completedSession?.close()
            cancelAlarm(operationId)
            if (completedOperation?.selectionOnly == true && completedOperation.autoReturn) {
                setPriorityMonitorStatus(WifiPriorityMonitorStatus.MONITORING)
            }
            reconcilePriorityRuntime()
        } else if (rollback) {
            requestRollback(operationId, WifiReason.UNKNOWN)
        }
    }

    private fun failManualBeforeReadiness(operationId: String, reason: WifiReason) {
        val operation = synchronized(stateLock) {
            state?.manualOperation?.takeIf { it.operationId == operationId && !releaseBlocksSelectionLocked(it) }
        } ?: return
        if (operation.settingsChanged || activeMutations.get() > 0) {
            requestRollback(operationId, reason)
        } else {
            closeAttempt(operationId)
            finishManualFailure(operationId, reason, rolledBack = false)
        }
    }

    private fun manualOperationCanMutate(operationId: String): Boolean = synchronized(stateLock) {
        val current = state ?: return@synchronized false
        val operation = current.manualOperation?.takeIf { it.operationId == operationId } ?: return@synchronized false
        operation.policyEpoch == current.policyEpoch && operation.phase == WifiManualPhase.APPLYING &&
            operation.deadlineElapsedMillis?.let { nowElapsed() < it } == true
    }

    private fun closeAttempt(operationId: String) {
        val session = synchronized(stateLock) {
            val attempt = localAttempt?.takeIf { it.operationId == operationId } ?: return
            localAttempt = null
            attempt.session
        }
        session.close()
    }

    private fun finishMutation(operationId: String) {
        activeMutations.decrementAndGet()
        val shouldContinue = synchronized(stateLock) {
            state?.manualOperation?.let {
                it.operationId == operationId && it.phase == WifiManualPhase.ROLLING_BACK
            } == true
        }
        if (shouldContinue) continueClaimedRollback(operationId)
        scheduleReleaseCheck()
    }

    private fun prepareManualDeadline(operationId: String): WifiReason? {
        val deadline = nowElapsed() + WIFI_MANUAL_TIMEOUT_MS
        synchronized(stateLock) {
            val old = state ?: return WifiReason.UNKNOWN
            val operation = old.manualOperation?.takeIf { it.operationId == operationId }
                ?: return WifiReason.UNKNOWN
            if (operation.policyEpoch != old.policyEpoch ||
                operation.phase !in setOf(WifiManualPhase.PREPARED, WifiManualPhase.APPLYING)
            ) {
                return WifiReason.UNKNOWN
            }
            if (releaseBlocksSelectionLocked(operation)) return WifiReason.BUSY
            if (operation.deadlineElapsedMillis != null) return null
            val nextOperation = operation.copy(
                phase = WifiManualPhase.APPLYING,
                deadlineElapsedMillis = deadline,
            )
            if (!writeAndCommit(old.copy(manualOperation = nextOperation))) return WifiReason.UNKNOWN
        }
        // The exact alarm is best-effort: it wakes a sleeping device and restarts a dead
        // process at the deadline. Without it, the watchdog and startup recovery still roll back.
        if (!scheduleAlarm(operationId, deadline)) {
            Log.w(TAG, "Exact rollback alarm unavailable; rollback may be delayed while the device sleeps")
        }
        val delay = (deadline - nowElapsed()).coerceAtLeast(0)
        scheduleWatchdog(Runnable { requestRollback(operationId, WifiReason.TIMEOUT_ROLLED_BACK) }, delay)
        return null
    }

    private fun beginManualMutation(operationId: String): WifiReason? = synchronized(stateLock) {
            val old = state ?: return@synchronized WifiReason.UNKNOWN
            val operation = old.manualOperation?.takeIf { it.operationId == operationId }
                ?: return@synchronized WifiReason.TIMEOUT_ROLLED_BACK
            val deadline = operation.deadlineElapsedMillis ?: return@synchronized WifiReason.UNKNOWN
            if (operation.policyEpoch != old.policyEpoch || operation.phase != WifiManualPhase.APPLYING ||
                nowElapsed() >= deadline
            ) {
                return@synchronized WifiReason.TIMEOUT_ROLLED_BACK
            }
            if (releaseBlocksSelectionLocked(operation)) return@synchronized WifiReason.BUSY
            if (!operation.settingsChanged && !writeAndCommit(
                    old.copy(manualOperation = operation.copy(settingsChanged = true)),
                )
            ) return@synchronized WifiReason.UNKNOWN
            activeMutations.incrementAndGet()
            null
    }

    private fun requestRollback(operationId: String, reason: WifiReason) {
        rollbackExecutor.execute {
            if (reason == WifiReason.TIMEOUT_ROLLED_BACK) {
                val active = synchronized(stateLock) { state?.manualOperation?.takeIf { it.operationId == operationId } }
                    ?: return@execute
                val deadline = active.deadlineElapsedMillis ?: return@execute
                if (nowElapsed() < deadline) return@execute
            }
            restoreClaimedOperation(operationId, reason)
        }
    }

    private fun continueClaimedRollback(operationId: String) {
        rollbackExecutor.execute {
            if (activeMutations.get() > 0) return@execute
            val operation = synchronized(stateLock) {
                state?.manualOperation?.takeIf {
                    it.operationId == operationId && it.phase == WifiManualPhase.ROLLING_BACK
                }
            } ?: return@execute
            restoreClaimedOperation(
                operation.operationId,
                operation.failureReason ?: WifiReason.UNKNOWN,
                allowExistingClaim = true,
            )
        }
    }

    private fun restoreClaimedOperation(
        operationId: String,
        reason: WifiReason,
        allowExistingClaim: Boolean = false,
    ): WifiRestoreProgress {
        val pendingConfirmation = synchronized(stateLock) {
            state?.manualOperation?.takeIf {
                it.operationId == operationId && it.settingsRestored &&
                    it.phase == WifiManualPhase.RESTORING_CONNECTION
            }
        }
        if (pendingConfirmation != null) {
            resumeRestoreConfirmation(pendingConfirmation)
            return restoreProgress(operationId)
        }
        val operation = claimRollback(operationId, reason, allowExistingClaim)
            ?: return restoreProgress(operationId)
        closeAttempt(operationId)
        if (activeMutations.get() > 0) {
            finishManualUnknown(operationId)
            return WifiRestoreProgress.FAILED
        }
        val reserved = synchronized(stateLock) {
            val current = state?.manualOperation?.takeIf {
                it.operationId == operationId && it.phase == WifiManualPhase.ROLLING_BACK
            } ?: return@synchronized null
            if (activeMutations.get() != 0 || restoreOwnerOperationId != null) return@synchronized null
            restoreOwnerOperationId = operationId
            current
        } ?: return restoreProgress(operationId)
        val restoreReason = try {
            adapter.restoreRecovery(
                snapshot = reserved.recoverySnapshot,
                // A priority reselection changes only which saved ID is selected. Never
                // rewrite credentials as part of its rollback.
                candidate = if (reserved.selectionOnly) emptyList() else reserved.candidate,
                restoreTarget = reserved.restoreTarget,
                shouldContinue = {
                    synchronized(stateLock) {
                        state?.manualOperation?.let {
                            it.operationId == operationId && it.phase == WifiManualPhase.ROLLING_BACK &&
                                it.policyEpoch == state?.policyEpoch && restoreOwnerOperationId == operationId
                        } == true
                    }
                },
                onMutationStarted = {
                    activeMutations.incrementAndGet()
                    scheduleWatchdog(Runnable {
                        if (activeMutations.get() > 0) finishManualUnknown(operationId)
                    }, 1_000L)
                    null
                },
                onMutationFinished = { activeMutations.decrementAndGet() },
            )
        } catch (_: Exception) {
            WifiReason.RESTORE_FAILED
        }
        synchronized(stateLock) {
            if (restoreOwnerOperationId == operationId) restoreOwnerOperationId = null
        }
        if (restoreReason == WifiReason.NONE) {
            beginRestoreConfirmation(operationId)
        } else {
            finishManualRestoreFailure(operationId)
        }
        return restoreProgress(operationId)
    }

    private fun restoreProgress(operationId: String): WifiRestoreProgress = synchronized(stateLock) {
        val current = state?.manualOperation?.takeIf { it.operationId == operationId }
        when {
            current == null -> WifiRestoreProgress.COMPLETE
            current.settingsRestored && current.phase == WifiManualPhase.RESTORING_CONNECTION ->
                WifiRestoreProgress.WAITING_FOR_CONNECTION
            else -> WifiRestoreProgress.FAILED
        }
    }

    private fun beginRestoreConfirmation(operationId: String) {
        val now = nowElapsed()
        val boot = runCatching { bootCount() }.getOrDefault(-1).takeIf { it >= 0 }
        val operation = synchronized(stateLock) {
            val old = state ?: return
            val current = old.manualOperation?.takeIf {
                it.operationId == operationId && it.phase == WifiManualPhase.ROLLING_BACK
            } ?: return
            val restoring = current.copy(
                phase = WifiManualPhase.RESTORING_CONNECTION,
                settingsRestored = true,
                restoreDeadlineElapsedMillis = now + WIFI_RESTORE_CONFIRM_TIMEOUT_MS,
                restoreBootCount = boot,
            )
            if (!writeAndCommit(old.copy(manualOperation = restoring))) return
            restoring
        }
        resumeRestoreConfirmation(operation)
    }

    private fun resumeRestoreConfirmation(operation: WifiManualOperation) {
        if (!operation.priorConnectionHadUsableIp) {
            finishManualFailure(
                operation.operationId,
                operation.failureReason ?: WifiReason.UNKNOWN,
                rolledBack = true,
            )
            return
        }
        if (operation.restoreTarget == null) {
            finishRestoreUnconfirmed(operation.operationId, operation.policyEpoch, deadline = null)
            return
        }
        val currentBoot = runCatching { bootCount() }.getOrDefault(-1).takeIf { it >= 0 }
        val deadline = operation.restoreDeadlineElapsedMillis
        val needsNewDeadline = deadline == null || operation.restoreBootCount != null &&
            currentBoot != null && operation.restoreBootCount != currentBoot
        val resumed = if (needsNewDeadline) {
            val now = nowElapsed()
            val updated = operation.copy(
                restoreDeadlineElapsedMillis = now + WIFI_RESTORE_CONFIRM_TIMEOUT_MS,
                restoreBootCount = currentBoot,
            )
            synchronized(stateLock) {
                val old = state ?: return
                val live = old.manualOperation?.takeIf { it.operationId == operation.operationId } ?: return
                if (!live.settingsRestored || live.policyEpoch != old.policyEpoch) return
                if (!writeAndCommit(old.copy(manualOperation = updated))) return
            }
            updated
        } else operation
        startRestoreObservation(resumed)
        scheduleRestoreDeadline(resumed)
    }

    private fun startRestoreObservation(operation: WifiManualOperation) {
        val existing = synchronized(stateLock) {
            restoreSession?.takeIf { restoreSessionOperationId == operation.operationId }
        }
        if (existing != null) {
            if (restoreTargetIsConnected(operation, existing.snapshot())) {
                confirmRestoredConnection(operation.operationId, operation.policyEpoch)
            }
            return
        }
        val session = try {
            connectivity.observe { event -> onRestoreConnectivity(operation.operationId, operation.policyEpoch, event) }
        } catch (_: Exception) {
            Log.w(TAG, "Could not observe the restored Wi-Fi connection")
            return
        }
        val installed = synchronized(stateLock) {
            val current = state?.manualOperation?.takeIf {
                it.operationId == operation.operationId && it.policyEpoch == operation.policyEpoch &&
                    it.settingsRestored && it.phase == WifiManualPhase.RESTORING_CONNECTION
            } ?: return@synchronized false
            restoreSession?.close()
            restoreSession = session
            restoreSessionOperationId = current.operationId
            true
        }
        if (!installed) {
            session.close()
            return
        }
        if (restoreTargetIsConnected(operation, session.snapshot())) {
            confirmRestoredConnection(operation.operationId, operation.policyEpoch)
        }
    }

    private fun restoreTargetIsConnected(
        operation: WifiManualOperation,
        observations: List<WifiLocalNetworkObservation>,
    ): Boolean {
        val target = operation.restoreTarget ?: return false
        return observations.any {
            it.hasUsableIp && it.ssid == target.ssid && restoreSecurityMatches(target.security, it.security)
        }
    }

    private fun restoreSecurityMatches(expected: String, observed: String?): Boolean = when (expected) {
        "OPEN" -> observed == "OPEN"
        "WPA_PSK", "SAE" -> observed == "WPA_PSK" || observed == "SAE"
        else -> observed == expected
    }

    private fun onRestoreConnectivity(
        operationId: String,
        epoch: Long,
        event: WifiLocalConnectivityEvent,
    ) {
        val observation = (event as? WifiLocalConnectivityEvent.Updated)?.observation ?: return
        val operation = synchronized(stateLock) {
            val current = state?.manualOperation?.takeIf {
                it.operationId == operationId && it.policyEpoch == epoch && it.settingsRestored &&
                    it.phase == WifiManualPhase.RESTORING_CONNECTION
            } ?: return
            current
        }
        if (restoreTargetIsConnected(operation, listOf(observation))) {
            confirmRestoredConnection(operationId, epoch)
        }
    }

    private fun confirmRestoredConnection(operationId: String, epoch: Long) {
        var close: WifiLocalConnectivitySession? = null
        synchronized(stateLock) {
            val old = state ?: return
            val operation = old.manualOperation?.takeIf {
                it.operationId == operationId && it.policyEpoch == epoch && it.settingsRestored &&
                    it.phase == WifiManualPhase.RESTORING_CONNECTION
            } ?: return
            val reason = operation.failureReason ?: WifiReason.UNKNOWN
            val terminal = WifiManualResult(operationId, operation.revision, "failed", reason, rolledBack = true)
            if (!settleLocked(old, operation, terminal)) return
            close = takeRestoreSessionLocked(operationId)
        }
        close?.close()
        cancelAlarm(operationId)
        reconcilePriorityRuntime()
    }

    private fun scheduleRestoreDeadline(operation: WifiManualOperation) {
        val deadline = operation.restoreDeadlineElapsedMillis ?: return
        val delay = (deadline - nowElapsed()).coerceAtLeast(0L)
        scheduleWatchdog(Runnable {
            operationExecutor.execute {
                finishRestoreUnconfirmed(operation.operationId, operation.policyEpoch, deadline)
            }
        }, delay)
    }

    /**
     * Settle a restore whose prior connection was not observed in time. The restored
     * settings re-enable auto-join, so the OS rejoins the network on its own (observed on
     * PUI 5.15.7); the app does not keep forcing it. A null [deadline] settles immediately.
     */
    private fun finishRestoreUnconfirmed(operationId: String, epoch: Long, deadline: Long?) {
        var close: WifiLocalConnectivitySession? = null
        synchronized(stateLock) {
            val old = state ?: return
            val operation = old.manualOperation?.takeIf {
                it.operationId == operationId && it.policyEpoch == epoch && it.settingsRestored &&
                    it.phase == WifiManualPhase.RESTORING_CONNECTION
            } ?: return
            if (deadline != null && (operation.restoreDeadlineElapsedMillis != deadline || nowElapsed() < deadline)) return
            val terminal = WifiManualResult(
                operationId,
                operation.revision,
                "failed",
                WifiReason.RESTORE_UNCONFIRMED,
                rolledBack = false,
            )
            if (!settleLocked(old, operation, terminal)) return
            close = takeRestoreSessionLocked(operationId)
        }
        close?.close()
        cancelAlarm(operationId)
        reconcilePriorityRuntime()
    }

    private fun finishManualFailure(operationId: String, reason: WifiReason, rolledBack: Boolean) {
        var resumeMonitoring = false
        synchronized(stateLock) {
            val old = state ?: return
            val operation = old.manualOperation?.takeIf { it.operationId == operationId } ?: return
            if (restoreOwnerOperationId == operationId) restoreOwnerOperationId = null
            val terminal = WifiManualResult(operationId, operation.revision, "failed", reason, rolledBack)
            if (!settleLocked(old, operation, terminal)) return
            resumeMonitoring = operation.selectionOnly
        }
        cancelAlarm(operationId)
        closeAttempt(operationId)
        if (resumeMonitoring) reconcilePriorityRuntime()
    }

    private fun finishManualUnknown(operationId: String) {
        synchronized(stateLock) {
            val old = state ?: return
            val operation = old.manualOperation?.takeIf { it.operationId == operationId } ?: return
            val terminal = WifiManualResult(operationId, operation.revision, "unknown", WifiReason.UNKNOWN, false)
            val next = old.copy(
                manualOperation = operation.copy(
                    phase = WifiManualPhase.ROLLING_BACK,
                    failureReason = operation.failureReason ?: WifiReason.UNKNOWN,
                ),
            ).withOperationResult(terminal)
            if (!writeAndCommit(next)) return
        }
        // The recovery fence remains durable until the platform mutation finishes.
    }

    private fun finishManualRestoreFailure(operationId: String) {
        synchronized(stateLock) {
            val old = state ?: return
            val operation = old.manualOperation?.takeIf { it.operationId == operationId } ?: return
            if (restoreOwnerOperationId == operationId) restoreOwnerOperationId = null
            val terminal = WifiManualResult(
                operationId,
                operation.revision,
                "failed",
                WifiReason.RESTORE_FAILED,
                false,
            )
            val fenced = operation.copy(
                phase = WifiManualPhase.ROLLING_BACK,
                failureReason = WifiReason.RESTORE_FAILED,
            )
            if (!writeAndCommit(old.copy(manualOperation = fenced).withOperationResult(terminal))) return
        }
        // Keep the fence until recovery succeeds or an explicit unmanaged command abandons it.
        // A transient platform failure should not need an operator, so retry a few times.
        scheduleRestoreFailureRetry(operationId)
    }

    private fun scheduleRestoreFailureRetry(operationId: String) {
        val delay = synchronized(stateLock) {
            if (restoreFailureRetryOperationId != operationId) {
                restoreFailureRetryOperationId = operationId
                restoreFailureRetries = 0
            }
            val delay = WIFI_RESTORE_FAILURE_RETRY_DELAYS_MS.getOrNull(restoreFailureRetries) ?: return
            restoreFailureRetries++
            delay
        }
        scheduleWatchdog(Runnable {
            rollbackExecutor.execute {
                val failed = synchronized(stateLock) {
                    state?.manualOperation?.takeIf { it.operationId == operationId && isAbandonable(it) }
                } ?: return@execute
                restoreClaimedOperation(failed.operationId, WifiReason.RESTORE_FAILED, allowExistingClaim = true)
            }
        }, delay + jitter(delay / 10))
    }

    private fun finishManualNoChangeUnknown(operationId: String) {
        synchronized(stateLock) {
            val old = state ?: return
            val operation = old.manualOperation?.takeIf { it.operationId == operationId } ?: return
            val terminal = WifiManualResult(operationId, operation.revision, "unknown", WifiReason.UNKNOWN, false)
            if (!settleLocked(old, operation, terminal)) return
        }
        cancelAlarm(operationId)
    }

    private fun settleLocked(old: WifiLocalState, operation: WifiManualOperation, result: WifiManualResult): Boolean {
        if (!writeAndCommit(old.copy(manualOperation = null).withOperationResult(result))) return false
        delayAutomaticReturn(operation, succeeded = false)
        return true
    }

    private fun takeRestoreSessionLocked(operationId: String): WifiLocalConnectivitySession? {
        if (restoreSessionOperationId != operationId) return null
        val session = restoreSession
        restoreSession = null
        restoreSessionOperationId = null
        return session
    }

    private fun WifiLocalState.withOperationResult(result: WifiManualResult): WifiLocalState {
        val receipt = policyReceipt
        return copy(
            latestResult = result,
            policyReceipt = if (receipt != null && receipt.operationId == result.operationId &&
                receipt.revision == result.revision
            ) receipt.copy(result = result) else receipt,
        )
    }

    /** The operation executor is the single owner of the priority session and scanner. */
    private fun reconcilePriorityRuntime() {
        operationExecutor.execute(::reconcilePriorityRuntimeNow)
    }

    private fun reconcilePriorityRuntimeNow() {
        val previous = priorityRuntime
        val active = synchronized(stateLock) {
            val profile = activePriorityProfileLocked() ?: return@synchronized null
            val epoch = checkNotNull(state).policyEpoch
            val runtime = previous?.takeIf { it.epoch == epoch } ?: PriorityRuntime(epoch).also {
                priorityRuntime = it
            }
            runtime to profile
        }
        if (previous != null && previous !== active?.first) {
            stopPriorityScanner(previous)
            previous.disconnectCheck = null
            previous.connectionToken = null
            previous.connection?.close()
            previous.connection = null
        }
        if (active == null) {
            setPriorityMonitorStatus(WifiPriorityMonitorStatus.OFF)
            return
        }
        val (runtime, profile) = active
        if (runtime.connection == null) {
            val token = Any()
            runtime.connectionToken = token
            runtime.connection = try {
                connectivity.observe { event ->
                    operationExecutor.execute { onPriorityConnectivity(runtime, token, event) }
                }
            } catch (_: Exception) {
                runtime.connectionToken = null
                setPriorityMonitorStatus(
                    if (profile.autoReturn) WifiPriorityMonitorStatus.UNSUPPORTED else WifiPriorityMonitorStatus.OFF,
                )
                return
            }
        }
        val connected = currentPriorityConnection(runtime)
        if (connected == null) {
            stopPriorityScanner(runtime)
            setPriorityMonitorStatus(WifiPriorityMonitorStatus.OFF)
            schedulePrioritySelectionAfterGrace(runtime)
            return
        }
        runtime.selectedWhileOffline = false
        runtime.disconnectCheck = null
        if (!profile.autoReturn || profile.networks.indexOfFirst { observationMatches(it, connected) } <= 0) {
            stopPriorityScanner(runtime)
            setPriorityMonitorStatus(WifiPriorityMonitorStatus.OFF)
            return
        }
        val scanner = priorityScanSource
        if (scanner == null) {
            setPriorityMonitorStatus(WifiPriorityMonitorStatus.UNSUPPORTED)
        } else if (runtime.scanToken == null) {
            val token = Any()
            runtime.scanToken = token
            scanner.start(profile.networks) { event ->
                operationExecutor.execute { onPriorityScanEvent(runtime, token, event) }
            }
        }
    }

    private fun activePriorityProfileLocked(runtime: PriorityRuntime? = null): WifiSavedProfile? {
        val current = state ?: return null
        if (!platformSupported || !startup.isDone || !storageHealthy || !current.managedWifi ||
            current.manualOperation != null || current.pendingRelease != null
        ) return null
        if (runtime != null && (priorityRuntime !== runtime || current.policyEpoch != runtime.epoch)) return null
        return current.lastApplied?.takeIf { it.revision == current.policyRevision && it.networks.size >= 2 }
    }

    private fun currentPriorityConnection(runtime: PriorityRuntime): WifiLocalNetworkObservation? =
        runtime.connection?.snapshot()?.filter { it.hasUsableIp }?.maxByOrNull { it.sequence }

    private fun stopPriorityScanner(runtime: PriorityRuntime) {
        if (runtime.scanToken == null) return
        runtime.scanToken = null
        priorityScanSource?.stop()
    }

    private fun setPriorityMonitorStatus(status: WifiPriorityMonitorStatus) {
        if (priorityMonitorStatus == status) return
        priorityMonitorStatus = status
        notifyStateChanged()
    }

    private fun onPriorityConnectivity(runtime: PriorityRuntime, token: Any, event: WifiLocalConnectivityEvent) {
        if (runtime.connectionToken !== token || synchronized(stateLock) { activePriorityProfileLocked(runtime) } == null) {
            return
        }
        if (event is WifiLocalConnectivityEvent.Updated && event.observation.hasUsableIp) {
            runtime.selectedWhileOffline = false
            runtime.disconnectCheck = null
        }
        reconcilePriorityRuntimeNow()
    }

    private fun schedulePrioritySelectionAfterGrace(runtime: PriorityRuntime) {
        if (runtime.disconnectCheck != null || runtime.selectedWhileOffline) return
        val token = Any()
        runtime.disconnectCheck = token
        schedulePriorityTimeout(Runnable {
            operationExecutor.execute {
                if (runtime.disconnectCheck !== token) return@execute
                runtime.disconnectCheck = null
                val profile = synchronized(stateLock) { activePriorityProfileLocked(runtime) } ?: return@execute
                if (currentPriorityConnection(runtime) != null) {
                    reconcilePriorityRuntimeNow()
                } else {
                    startPrioritySelection(runtime, profile.networks.indices.toList(), retryDisconnected = true)
                }
            }
            // Jitter keeps headsets that lost the same AP from reselecting in lockstep.
        }, WIFI_PRIORITY_DISCONNECT_GRACE_MS + jitter(WIFI_DISCONNECT_GRACE_JITTER_MS))
    }

    private fun onPriorityScanEvent(runtime: PriorityRuntime, token: Any, event: WifiPriorityScanEvent) {
        if (runtime.scanToken !== token) return
        val saved = synchronized(stateLock) { activePriorityProfileLocked(runtime) } ?: return
        setPriorityMonitorStatus(event.status)
        event.detail?.let { detail ->
            if (event.status == WifiPriorityMonitorStatus.DEGRADED ||
                event.status == WifiPriorityMonitorStatus.UNSUPPORTED
            ) Log.w(TAG, "Wi-Fi priority monitor status=${event.status.wireValue} detail=$detail")
        }
        if (event.status != WifiPriorityMonitorStatus.MONITORING || event.visibleIdentities.isEmpty()) return
        if (synchronized(stateLock) { nowElapsed() < runtime.retryAllowedAt }) return

        val connected = currentPriorityConnection(runtime)
        val currentIndex = connected?.let { observation ->
            saved.networks.indexOfFirst { observationMatches(it, observation) }
        } ?: -1
        if (currentIndex <= 0) {
            reconcilePriorityRuntimeNow()
            return
        }
        val upperCandidates = (0 until currentIndex).filter { saved.networks[it].identity in event.visibleIdentities }
        if (upperCandidates.isNotEmpty()) startPrioritySelection(runtime, upperCandidates)
    }

    // Called with stateLock held when an automatic-return operation settles.
    private fun delayAutomaticReturn(operation: WifiManualOperation, succeeded: Boolean) {
        if (!operation.selectionOnly || operation.retryDisconnected) return
        val runtime = priorityRuntime?.takeIf { it.epoch == operation.policyEpoch } ?: return
        runtime.failedReturns = if (succeeded) 0 else (runtime.failedReturns + 1).coerceAtMost(16)
        val multiplier = 1L shl (runtime.failedReturns - 1).coerceIn(0, 10)
        runtime.retryAllowedAt = nowElapsed() +
            (WIFI_AUTO_RETURN_RETRY_DELAY_MS * multiplier).coerceAtMost(WIFI_AUTO_RETURN_MAX_RETRY_DELAY_MS)
    }

    private fun startPrioritySelection(
        runtime: PriorityRuntime, indexes: List<Int>, retryDisconnected: Boolean = false,
    ) {
        if (retryDisconnected && runtime.selectedWhileOffline) return
        val profile = synchronized(stateLock) { activePriorityProfileLocked(runtime) } ?: return
        connectivity.preflight()?.let { reason ->
            if (profile.autoReturn) setPriorityMonitorStatus(WifiPriorityMonitorStatus.DEGRADED)
            Log.w(TAG, "Wi-Fi priority selection preflight failed: ${reason.wireValue}")
            return
        }
        val recovery = try {
            adapter.captureRecovery(profile.networks)
        } catch (failure: Exception) {
            if (profile.autoReturn) setPriorityMonitorStatus(WifiPriorityMonitorStatus.DEGRADED)
            val reason = (failure as? WifiAdapterFailure)?.reason ?: WifiReason.CONFIG_READ_FAILED
            Log.w(TAG, "Wi-Fi priority selection snapshot failed: ${reason.wireValue}")
            return
        }
        val operation = synchronized(stateLock) {
            val current = state ?: return
            val saved = activePriorityProfileLocked(runtime) ?: return
            if (current.policyReceipt?.awaitsOperation(current) == true) return
            val prepared = WifiManualOperation(
                operationId = UUID.randomUUID().toString(),
                revision = saved.revision,
                candidate = saved.networks,
                recoverySnapshot = recovery,
                phase = WifiManualPhase.PREPARED,
                policyEpoch = runtime.epoch,
                autoReturn = saved.autoReturn,
                selectionOnly = true,
                selectionIndexes = indexes,
                retryDisconnected = retryDisconnected,
            )
            if (!writeAndCommit(current.copy(manualOperation = prepared))) return
            prepared
        }
        if (retryDisconnected) runtime.selectedWhileOffline = true
        stopPriorityScanner(runtime)
        if (!retryDisconnected) setPriorityMonitorStatus(WifiPriorityMonitorStatus.SWITCHING)
        operationExecutor.execute { applyManual(operation) }
    }

    private fun observationMatches(
        profile: WifiNetworkProfile,
        observation: WifiLocalNetworkObservation,
    ): Boolean {
        if (profile.ssid != observation.ssid) return false
        return wifiSecurityViewMatchesProfile(profile, observation.security)
    }

    private fun recoverInterruptedOperation() {
        var completed = false
        try {
            val current = synchronized(stateLock) {
                val loaded = state ?: return
                if (!storageHealthy) return
                val receipt = loaded.policyReceipt?.takeIf { it.awaitsOperation(loaded) }
                if (receipt == null) loaded else {
                    // The queue did not survive this process restart. Keep exact interruption
                    // evidence and allow any journaled release to proceed.
                    val interrupted = loaded.copy(policyReceipt = receipt.interrupted())
                    if (!writeAndCommit(interrupted)) return
                    interrupted
                }
            }
            if (!platformSupported) {
                completed = true
                return
            }
            current.manualOperation?.let { operation ->
                if (operation.selectionOnly) synchronized(stateLock) {
                    priorityRuntime = PriorityRuntime(operation.policyEpoch)
                }
                when {
                    operation.settingsRestored -> resumeRestoreConfirmation(operation)
                    operation.settingsChanged -> {
                        val restored = restoreClaimedOperation(
                            operation.operationId,
                            operation.failureReason ?: WifiReason.UNKNOWN,
                            allowExistingClaim = true,
                        )
                        val pendingConfirmation = synchronized(stateLock) {
                            state?.manualOperation?.takeIf { it.operationId == operation.operationId }?.settingsRestored == true
                        }
                        if (restored == WifiRestoreProgress.FAILED && !pendingConfirmation) return
                    }
                    else -> finishManualNoChangeUnknown(operation.operationId)
                }
            }

            completed = synchronized(stateLock) {
                storageHealthy && state?.manualOperation?.let { it.settingsRestored } != false
            }
        } catch (_: Exception) {
            Log.e(TAG, "Wi-Fi startup recovery did not complete")
        } finally {
            startup.complete(completed)
            updateReadiness()
            // A release journaled before the restart runs before automatic selection resumes.
            if (completed) scheduleReleaseCheck()
            if (completed) reconcilePriorityRuntime()
        }
    }

    /** The durable phase change arbitrates rollback against a concurrent local readiness claim. */
    private fun claimRollback(
        operationId: String,
        reason: WifiReason,
        allowExistingClaim: Boolean = false,
    ): WifiManualOperation? = synchronized(stateLock) {
        val old = state ?: return@synchronized null
        val operation = old.manualOperation?.takeIf { it.operationId == operationId } ?: return@synchronized null
        if (releaseBlocksSelectionLocked(operation)) return@synchronized null
        // Readiness claims the outcome under the same lock as timeout/rollback. If its
        // queued durable commit runs after the deadline, that earlier observation still
        // wins; persistence failure moves it to ROLLBACK_READY explicitly.
        if (localAttempt?.let {
                it.operationId == operationId && it.phase == LocalAttemptPhase.COMMITTING
            } == true
        ) return@synchronized null
        if (operation.phase == WifiManualPhase.ROLLING_BACK && !allowExistingClaim) return@synchronized null
        val claimed = operation.copy(
            phase = WifiManualPhase.ROLLING_BACK,
            failureReason = operation.failureReason ?: reason,
        )
        if (!writeAndCommit(old.copy(manualOperation = claimed))) {
            // The earlier journal records the complete restore snapshot and that mutation
            // started. Keep an in-memory fence and restore from it even when this phase
            // transition cannot be persisted. Never clear that fence or report a result
            // unless a later durable write succeeds; process restart replays the old journal.
            if (operation.settingsChanged) {
                state = old.copy(manualOperation = claimed)
                return@synchronized claimed
            }
            return@synchronized null
        }
        claimed
    }

    private fun writeAndCommit(next: WifiLocalState): Boolean {
        if (!storageHealthy) return false
        return try {
            store.write(next)
            state = next
            notifyStateChanged()
            true
        } catch (_: Exception) {
            storageHealthy = false
            notifyStateChanged()
            false
        }
    }

    private fun persist(transform: (WifiLocalState) -> WifiLocalState): Boolean = synchronized(stateLock) {
        val old = state ?: return@synchronized false
        if (!storageHealthy) return@synchronized false
        writeAndCommit(transform(old))
    }
}

private fun productionDependencies(context: Context): WifiControllerDependencies {
    val appContext = context.applicationContext
    val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "wifi-settings-watchdog").apply { isDaemon = true }
    }
    val operationExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "wifi-settings-operation").apply { isDaemon = true }
    }
    val rollbackExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "wifi-settings-rollback").apply { isDaemon = true }
    }
    val adapter = WifiManagerAdapter(appContext)
    val connectivity = AndroidWifiLocalConnectivitySource(appContext)
    return WifiControllerDependencies(
        readinessFailure = { adapter.readinessFailure() ?: connectivity.preflight() },
        store = WifiSettingsStore(File(appContext.noBackupFilesDir, "wifi-settings.json"), ::syncDirectory),
        adapter = adapter,
        nowElapsed = SystemClock::elapsedRealtime,
        scheduleAlarm = { operationId, deadline -> scheduleExactRollback(appContext, operationId, deadline) },
        cancelAlarm = { operationId -> cancelExactRollback(appContext, operationId) },
        connectivity = connectivity,
        priorityScanSource = if (supportsManagedWifi()) systemWifiPriorityScanSource(appContext) else null,
        platformSupported = supportsManagedWifi(),
        bootCount = { Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1) },
        operationExecutor = operationExecutor,
        rollbackExecutor = rollbackExecutor,
        scheduleWatchdog = { task, delay -> watchdog.schedule(task, delay, java.util.concurrent.TimeUnit.MILLISECONDS) },
        schedulePriorityTimeout = { task, delay -> watchdog.schedule(task, delay, java.util.concurrent.TimeUnit.MILLISECONDS) },
        jitter = { bound -> if (bound <= 0L) 0L else kotlin.random.Random.nextLong(bound + 1) },
    )
}

private fun scheduleExactRollback(context: Context, operationId: String, deadlineElapsed: Long): Boolean {
    val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) return false
    val intent = Intent(context, WifiRollbackReceiver::class.java)
        .setAction(WifiSettingsController.ACTION_ROLLBACK)
        .setData(Uri.parse(wifiRollbackAlarmIdentity(operationId)))
        .putExtra("operation_id", operationId)
        .putExtra("deadline_elapsed_ms", deadlineElapsed)
    val pending = PendingIntent.getBroadcast(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return try {
        manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadlineElapsed, pending)
        true
    } catch (_: SecurityException) {
        false
    }
}

internal fun wifiRollbackAlarmIdentity(operationId: String): String =
    "styly-mdm://wifi-rollback/$operationId"

private fun cancelExactRollback(context: Context, operationId: String) {
    val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    val intent = Intent(context, WifiRollbackReceiver::class.java)
        .setAction(WifiSettingsController.ACTION_ROLLBACK)
        .setData(Uri.parse(wifiRollbackAlarmIdentity(operationId)))
    val pending = PendingIntent.getBroadcast(
        context,
        0,
        intent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    ) ?: return
    manager.cancel(pending)
    pending.cancel()
}
