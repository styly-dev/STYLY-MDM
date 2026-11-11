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
)

internal data class WifiPolicyProgress(
    val result: WifiManualResult?,
    val settled: Boolean,
    val storageError: Boolean,
)

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

    companion object {
        private const val TAG = "WifiSettings"
        private const val WIFI_PRIORITY_CANDIDATE_TIMEOUT_MS = 12_000L
        private const val WIFI_PRIORITY_DISCONNECT_GRACE_MS = 8_000L
        private const val WIFI_PRIORITY_RETRY_BACKOFF_MS = 5 * 60_000L
        private const val WIFI_PRIORITY_MAX_RETRY_BACKOFF_MS = 30 * 60_000L
        private const val WIFI_AUTO_RETURN_RETRY_DELAY_MS = 5 * 60_000L
        private const val WIFI_AUTO_RETURN_MAX_RETRY_DELAY_MS = 30 * 60_000L
        private const val WIFI_RESTORE_CONFIRM_TIMEOUT_MS = 60_000L
        private const val WIFI_RESTORE_RETRY_INTERVAL_MS = 5 * 60_000L
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
    private val stateListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var unavailableReason: String? = "INITIALIZING"
    private val activeMutations = AtomicInteger(0)
    private val startup = CompletableFuture<Boolean>()
    private val startupStarted = AtomicBoolean(false)
    private var localAttempt: LocalAttempt? = null
    private var restoreOwnerOperationId: String? = null
    private var restoreSession: WifiLocalConnectivitySession? = null
    private var restoreSessionOperationId: String? = null
    private var restoreRetryGeneration = 0L
    private var prioritySession: WifiLocalConnectivitySession? = null
    private var prioritySessionRevision: String? = null
    private var prioritySessionEpoch: Long? = null
    private var priorityScanRevision: String? = null
    private var priorityScanEpoch: Long? = null
    private var priorityReconnectFailures = 0
    private var priorityRetryAllowedAtElapsedMillis = 0L
    private var autoReturnRetryRevision: String? = null
    private var autoReturnRetryEpoch = 0L
    private var autoReturnRetryAllowedAtElapsedMillis = 0L
    private var priorityDisconnectGeneration = 0L
    private var autoReturnFailures = 0
    @Volatile private var priorityMonitorStatus = WifiPriorityMonitorStatus.OFF
    @Volatile private var pendingRelease: PendingRelease? = null

    private class PendingRelease(val revision: String?)

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
        val next = try { readinessFailure()?.wireValue } catch (_: Exception) { WifiReason.UNKNOWN.wireValue }
        if (next == unavailableReason) return
        unavailableReason = next
        notifyStateChanged()
    }

    private fun notifyStateChanged() {
        // A release waits for the active operation; retry it after every state change.
        if (pendingRelease != null) operationExecutor.execute(::processPendingRelease)
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
        if (!startup.isDone) return
        val operation = synchronized(stateLock) { state?.manualOperation?.takeIf { it.operationId == operationId } }
            ?: return
        if (alarmDeadline < 0 || operation.deadlineElapsedMillis != alarmDeadline ||
            nowElapsed() < alarmDeadline
        ) return
        requestRollback(operationId, WifiReason.TIMEOUT_ROLLED_BACK)
    }

    fun applyManagedPolicy(operationId: String, revision: String, settings: JSONObject) {
        operationExecutor.execute {
            // A newer managed policy supersedes a release that is still waiting.
            pendingRelease = null
            updateReadiness()
            acceptManualCommand(operationId, revision) {
                WifiSettingsProtocol.parseApplyCommand(JSONObject()
                    .put("operation_id", operationId)
                    .put("revision", revision)
                    .put("mode", WifiApplyMode.MANAGED.wireValue)
                    .put("wifi", settings.getJSONObject("wifi")))
            }
        }
    }

    /** True when this revision already owns Wi-Fi and no operation is pending. */
    fun isPolicyApplied(revision: String): Boolean = synchronized(stateLock) {
        val current = state ?: return@synchronized false
        startup.isDone && storageHealthy && current.managedWifi && current.manualOperation == null &&
            current.policyRevision == revision && current.lastApplied?.revision == revision
    }

    /** Wait for a live rollback. A new policy attempt can retry a settled restore failure. */
    fun readyForPolicy(): Boolean = synchronized(stateLock) { readyForPolicyLocked() }

    fun policyProgress(operationId: String, revision: String): WifiPolicyProgress = synchronized(stateLock) {
        val result = state?.latestResult?.takeIf { it.operationId == operationId && it.revision == revision }
        val operation = state?.manualOperation?.takeIf { it.operationId == operationId }
        val unconfirmedRestoreIsTerminal = operation?.settingsRestored == true &&
            result?.reason == WifiReason.RESTORE_UNCONFIRMED
        WifiPolicyProgress(
            result,
            readyForPolicyLocked() || unconfirmedRestoreIsTerminal,
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
     * Releases Wi-Fi ownership. While an operation (including an automatic switch) is
     * active the request is retained and applied once that operation settles.
     */
    fun stopManagingPolicy(revision: String? = null) {
        operationExecutor.execute {
            pendingRelease = PendingRelease(revision)
            processPendingRelease()
        }
    }

    /** Runs on the operation executor only. */
    private fun processPendingRelease() {
        val released = synchronized(stateLock) {
            val request = pendingRelease ?: return
            val current = state
            val active = current?.manualOperation
            if (active != null && !active.settingsRestored && !isAbandonable(active)) return
            pendingRelease = null
            if (current == null) return@synchronized null
            val failed = active?.takeIf(::isAbandonable)
            val applied = current.policyRevision?.takeIf { current.managedWifi }
            val previous = failed?.revision ?: applied ?: return@synchronized null
            request.revision ?: previous
        } ?: return
        applyUnmanagedCommand(WifiApplyCommand(
            UUID.randomUUID().toString(), released, WifiApplyMode.UNMANAGED, emptyList(),
        ))
    }

    private fun isAbandonable(operation: WifiManualOperation): Boolean =
        operation.phase == WifiManualPhase.ROLLING_BACK && operation.failureReason == WifiReason.RESTORE_FAILED &&
            startup.isDone && activeMutations.get() == 0 && restoreOwnerOperationId == null

    private fun acceptManualCommand(operationId: String, revision: String, parse: () -> WifiApplyCommand) {
        if (!WifiSettingsProtocol.isValidUuid(operationId) || !WifiSettingsProtocol.isValidUuid(revision)) return

        if (synchronized(stateLock) { state?.processedOperationIds?.contains(operationId) == true }) return

        try {
            val parsed = parse()
            if (parsed.operationId != operationId || parsed.revision != revision) return
            if (parsed.autoReturn && priorityScanSource == null) {
                persistManualResult(WifiManualResult(operationId, revision, "failed", WifiReason.UNSUPPORTED, false))
                return
            }
            val unconfirmedRestore = synchronized(stateLock) {
                state?.manualOperation?.takeIf { it.settingsRestored && it.phase == WifiManualPhase.RESTORING_CONNECTION }
            }
            if (unconfirmedRestore != null && !supersedeRestoredConnection(unconfirmedRestore)) {
                persistManualResult(WifiManualResult(operationId, revision, "failed", WifiReason.BUSY, false))
                return
            }
            val failedRestore = synchronized(stateLock) { state?.manualOperation?.takeIf(::isAbandonable) }
            if (failedRestore != null && !restoreClaimedOperation(
                    failedRestore.operationId, WifiReason.RESTORE_FAILED, allowExistingClaim = true,
                )
            ) {
                persistManualResult(WifiManualResult(operationId, revision, "failed", WifiReason.RESTORE_FAILED, false))
                return
            }
            val current = synchronized(stateLock) {
                val current = state ?: return@synchronized null
                if (current.manualOperation != null) null else current
            }
            if (current == null) {
                val current = synchronized(stateLock) { state }
                val reason = when {
                    current == null -> return
                    current.manualOperation != null -> WifiReason.BUSY
                    else -> WifiReason.UNKNOWN
                }
                persistManualResult(WifiManualResult(operationId, revision, "failed", reason, false))
                return
            }
            val observationFailure = connectivity.preflight()
            if (observationFailure != null) {
                persistManualResult(WifiManualResult(operationId, revision, "failed", observationFailure, false))
                return
            }
            val recoverySnapshot = adapter.captureRecovery(current.lastApplied?.networks.orEmpty())
            val operation = synchronized(stateLock) {
                val old = state ?: return@synchronized null
                if (old.manualOperation != null || old.lastApplied != current.lastApplied) return@synchronized null
                val manualOperation = WifiManualOperation(
                    operationId = operationId,
                    revision = parsed.revision,
                    candidate = parsed.networks,
                    recoverySnapshot = recoverySnapshot,
                    phase = WifiManualPhase.PREPARED,
                    policyEpoch = old.policyEpoch,
                    autoReturn = parsed.autoReturn,
                )
                val next = old.copy(
                    manualOperation = manualOperation,
                    processedOperationIds = appendWifiOperationReceipt(old.processedOperationIds, operationId),
                )
                if (writeAndCommit(next)) manualOperation else null
            }
            if (operation == null) {
                persistManualResult(WifiManualResult(operationId, revision, "failed", WifiReason.BUSY, false))
                return
            }

            operationExecutor.execute { applyManual(operation) }
        } catch (failure: WifiAdapterFailure) {
            persistManualResult(WifiManualResult(operationId, revision, "failed", failure.reason, false))
        } catch (failure: WifiPolicyFailure) {
            persistManualResult(WifiManualResult(operationId, revision, "failed", failure.reason, false))
        } catch (_: IllegalArgumentException) {
            persistManualResult(WifiManualResult(operationId, revision, "failed", WifiReason.UNSUPPORTED, false))
        } catch (_: Exception) {
            persistManualResult(WifiManualResult(operationId, revision, "failed", WifiReason.CONFIG_READ_FAILED, false))
        }
    }

    /** Called by [processPendingRelease] only when no operation can still mutate Wi-Fi. */
    private fun applyUnmanagedCommand(command: WifiApplyCommand) {
        val active = synchronized(stateLock) { state?.manualOperation }
        if (active != null && active.settingsRestored && active.phase == WifiManualPhase.RESTORING_CONNECTION) {
            if (!supersedeRestoredConnection(active)) return
        }
        var abandonedOperationId: String? = null
        var closeRestoreSession: WifiLocalConnectivitySession? = null
        val committed = synchronized(stateLock) {
            val old = state ?: return
            val pending = old.manualOperation
            if (pending != null && !isAbandonable(pending)) return@synchronized false
            val terminal = WifiManualResult(
                command.operationId,
                command.revision,
                "success",
                WifiReason.NONE,
                rolledBack = false,
            )
            val next = old.copy(
                managedWifi = false,
                manualOperation = null,
                policyEpoch = old.policyEpoch + 1L,
                policyRevision = command.revision,
                latestResult = terminal,
                processedOperationIds = appendWifiOperationReceipt(old.processedOperationIds, command.operationId),
            )
            if (writeAndCommit(next)) {
                restoreRetryGeneration++
                abandonedOperationId = pending?.operationId
                if (pending != null && restoreSessionOperationId == pending.operationId) {
                    closeRestoreSession = restoreSession
                    restoreSession = null
                    restoreSessionOperationId = null
                }
                true
            } else {
                false
            }
        }
        if (!committed) return
        closeRestoreSession?.close()
        abandonedOperationId?.let { operationId ->
            cancelAlarm(operationId)
            closeAttempt(operationId)
        }
        reconcilePriorityRuntime()
    }

    private fun supersedeRestoredConnection(operation: WifiManualOperation): Boolean {
        var close: WifiLocalConnectivitySession? = null
        val superseded = synchronized(stateLock) {
            val old = state ?: return@synchronized false
            val current = old.manualOperation?.takeIf { it.operationId == operation.operationId } ?: return@synchronized false
            if (!current.settingsRestored || current.phase != WifiManualPhase.RESTORING_CONNECTION ||
                activeMutations.get() > 0 || restoreOwnerOperationId != null
            ) return@synchronized false
            if (!writeAndCommit(old.copy(manualOperation = null, policyEpoch = old.policyEpoch + 1L))) return@synchronized false
            restoreRetryGeneration++
            if (restoreSessionOperationId == operation.operationId) {
                close = restoreSession
                restoreSession = null
                restoreSessionOperationId = null
            }
            true
        }
        close?.close()
        cancelAlarm(operation.operationId)
        return superseded
    }

    private fun applyManual(operation: WifiManualOperation) {
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
            if (operationToApply.selectionOnly) {
                val deadlineFailure = prepareManualDeadline(operationToApply.operationId)
                if (deadlineFailure != null) {
                    finishManualFailure(operationToApply.operationId, deadlineFailure, rolledBack = false)
                    return
                }
            }
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
                wifiProfileAcceptsObservedSecurity(expected, observation.security)
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
            val next = old.copy(
                lastApplied = if (operation.selectionOnly) old.lastApplied else
                    WifiSavedProfile(operation.revision, operation.candidate, operation.autoReturn),
                managedWifi = if (operation.selectionOnly) old.managedWifi else true,
                policyRevision = if (operation.selectionOnly) old.policyRevision else operation.revision,
                manualOperation = null,
                latestResult = terminal,
            )
            if (writeAndCommit(next)) {
                delayAutomaticReturn(operation, succeeded = true)
                localAttempt = null
                completedSession = attempt.session
                completedOperation = operation
                restoreOwnerOperationId = null
                priorityReconnectFailures = 0
                priorityRetryAllowedAtElapsedMillis = nowElapsed()
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
        val operation = synchronized(stateLock) { state?.manualOperation?.takeIf { it.operationId == operationId } }
            ?: return
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
    }

    private fun prepareManualDeadline(operationId: String): WifiReason? {
        val deadline = nowElapsed() + WIFI_MANUAL_TIMEOUT_MS
        val persistedDeadline = synchronized(stateLock) {
            val old = state ?: return@synchronized null
            val operation = old.manualOperation?.takeIf { it.operationId == operationId }
                ?: return@synchronized null
            if (operation.policyEpoch != old.policyEpoch ||
                operation.phase !in setOf(WifiManualPhase.PREPARED, WifiManualPhase.APPLYING)
            ) {
                return@synchronized null
            }
            operation.deadlineElapsedMillis?.let { return@synchronized it }
            val nextOperation = operation.copy(
                phase = WifiManualPhase.APPLYING,
                deadlineElapsedMillis = deadline,
            )
            if (writeAndCommit(old.copy(manualOperation = nextOperation))) deadline else null
        }
        if (persistedDeadline == null) return WifiReason.UNKNOWN
        if (persistedDeadline != deadline) return null
        // The exact alarm is best-effort: it wakes a sleeping device and restarts a dead
        // process at the deadline. Without it, the watchdog and startup recovery still roll back.
        if (!scheduleAlarm(operationId, persistedDeadline)) {
            Log.w(TAG, "Exact rollback alarm unavailable; rollback may be delayed while the device sleeps")
        }
        val delay = (persistedDeadline - nowElapsed()).coerceAtLeast(0)
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
    ): Boolean {
        val pendingConfirmation = synchronized(stateLock) {
            state?.manualOperation?.takeIf {
                it.operationId == operationId && it.settingsRestored &&
                    it.phase == WifiManualPhase.RESTORING_CONNECTION
            }
        }
        if (pendingConfirmation != null) {
            resumeRestoreConfirmation(pendingConfirmation)
            return false
        }
        val operation = claimRollback(operationId, reason, allowExistingClaim) ?: return false
        closeAttempt(operationId)
        if (activeMutations.get() > 0) {
            finishManualUnknown(operationId)
            return false
        }
        val reserved = synchronized(stateLock) {
            val current = state?.manualOperation?.takeIf {
                it.operationId == operationId && it.phase == WifiManualPhase.ROLLING_BACK
            } ?: return@synchronized null
            if (activeMutations.get() != 0 || restoreOwnerOperationId != null) return@synchronized null
            restoreOwnerOperationId = operationId
            current
        } ?: return false
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
        return synchronized(stateLock) { state?.manualOperation?.operationId != operationId }
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
                restoreUnconfirmedReported = false,
            )
            if (!writeAndCommit(old.copy(manualOperation = restoring))) return
            restoring
        }
        if (!operation.priorConnectionHadUsableIp) {
            // There is no previous connection whose recovery could be confirmed. The saved
            // settings have been restored, so report the original operation failure with a
            // completed rollback instead of turning an already-offline device into an
            // unconfirmed connection restore.
            finishManualFailure(
                operation.operationId,
                operation.failureReason ?: WifiReason.UNKNOWN,
                rolledBack = true,
            )
            return
        }
        if (operation.restoreTarget == null) {
            finishRestoreUnconfirmed(operation.operationId, operation.policyEpoch, operation.restoreDeadlineElapsedMillis, clear = true)
            return
        }
        startRestoreObservation(operation)
        scheduleRestoreDeadline(operation)
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
            finishRestoreUnconfirmed(operation.operationId, operation.policyEpoch, operation.restoreDeadlineElapsedMillis, clear = true)
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
        if (!resumed.restoreUnconfirmedReported) scheduleRestoreDeadline(resumed)
        else scheduleRestoreRetry(resumed, WIFI_RESTORE_RETRY_INTERVAL_MS)
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
            if (!writeAndCommit(old.copy(manualOperation = null, latestResult = terminal))) return
            delayAutomaticReturn(operation, succeeded = false)
            if (restoreSessionOperationId == operationId) {
                close = restoreSession
                restoreSession = null
                restoreSessionOperationId = null
            }
            restoreRetryGeneration++
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
                finishRestoreUnconfirmed(operation.operationId, operation.policyEpoch, deadline, clear = false)
            }
        }, delay)
    }

    private fun finishRestoreUnconfirmed(
        operationId: String,
        epoch: Long,
        deadline: Long?,
        clear: Boolean,
    ) {
        var retry: WifiManualOperation? = null
        var disconnectedRetry: WifiManualOperation? = null
        var resumeAutomaticReturn = false
        var close: WifiLocalConnectivitySession? = null
        synchronized(stateLock) {
            val old = state ?: return
            val operation = old.manualOperation?.takeIf {
                it.operationId == operationId && it.policyEpoch == epoch && it.settingsRestored &&
                    it.phase == WifiManualPhase.RESTORING_CONNECTION
            } ?: return
            if (!clear && (deadline == null || operation.restoreDeadlineElapsedMillis != deadline || nowElapsed() < deadline)) return
            val terminal = WifiManualResult(
                operationId,
                operation.revision,
                "failed",
                WifiReason.RESTORE_UNCONFIRMED,
                rolledBack = false,
            )
            val nextOperation = if (clear) null else operation.copy(restoreUnconfirmedReported = true)
            if (!writeAndCommit(old.copy(manualOperation = nextOperation, latestResult = terminal))) return
            if (clear) {
                delayAutomaticReturn(operation, succeeded = false)
                resumeAutomaticReturn = operation.selectionOnly && !operation.retryDisconnected
            }
            if (clear) disconnectedRetry = operation.takeIf { it.selectionOnly && it.retryDisconnected }
            if (clear || operation.restoreTarget == null) {
                if (restoreSessionOperationId == operationId) {
                    close = restoreSession
                    restoreSession = null
                    restoreSessionOperationId = null
                }
                restoreRetryGeneration++
            } else {
                retry = operation.copy(restoreUnconfirmedReported = true)
            }
        }
        close?.close()
        if (retry != null) scheduleRestoreRetry(checkNotNull(retry), WIFI_RESTORE_RETRY_INTERVAL_MS)
        if (disconnectedRetry != null) schedulePriorityReconnectRetry(checkNotNull(disconnectedRetry))
        if (resumeAutomaticReturn) reconcilePriorityRuntime()
    }

    private fun scheduleRestoreRetry(operation: WifiManualOperation, delay: Long) {
        val generation = synchronized(stateLock) { ++restoreRetryGeneration }
        scheduleWatchdog(Runnable {
            rollbackExecutor.execute { retryRestoredConnection(operation.operationId, operation.policyEpoch, generation) }
        }, delay)
    }

    private fun retryRestoredConnection(operationId: String, epoch: Long, generation: Long) {
        val operation = synchronized(stateLock) {
            val old = state ?: return
            val current = old.manualOperation?.takeIf {
                it.operationId == operationId && it.policyEpoch == epoch && it.settingsRestored &&
                    it.phase == WifiManualPhase.RESTORING_CONNECTION
            } ?: return
            if (generation != restoreRetryGeneration || restoreOwnerOperationId != null || activeMutations.get() != 0) return
            if (current.restoreTarget == null) return
            restoreOwnerOperationId = operationId
            current
        }
        val target = requireNotNull(operation.restoreTarget)
        val reason = runCatching {
            adapter.connectToSavedNetwork(
                target = target,
                shouldContinue = {
                    synchronized(stateLock) {
                        val current = state?.manualOperation
                        current?.operationId == operationId && current.policyEpoch == epoch &&
                            current.settingsRestored && restoreOwnerOperationId == operationId
                    }
                },
                onMutationStarted = {
                    activeMutations.incrementAndGet()
                    null
                },
                onMutationFinished = { activeMutations.decrementAndGet() },
            )
        }.getOrDefault(WifiReason.UNREACHABLE)
        val updated = synchronized(stateLock) {
            val old = state ?: return
            val current = old.manualOperation?.takeIf { it.operationId == operationId && it.policyEpoch == epoch }
            if (restoreOwnerOperationId == operationId) restoreOwnerOperationId = null
            if (current == null || !current.settingsRestored) return
            current
        }
        if (reason != WifiReason.NONE) Log.w(TAG, "Restored Wi-Fi reconnect request failed: ${reason.wireValue}")
        val session = synchronized(stateLock) { restoreSession?.takeIf { restoreSessionOperationId == operationId } }
        if (session == null) startRestoreObservation(updated)
        if (session != null && restoreTargetIsConnected(updated, session.snapshot())) {
            confirmRestoredConnection(operationId, epoch)
        } else {
            scheduleRestoreRetry(updated, WIFI_RESTORE_RETRY_INTERVAL_MS)
        }
    }

    private fun finishManualFailure(operationId: String, reason: WifiReason, rolledBack: Boolean) {
        var failedSelection: WifiManualOperation? = null
        var resumeAutomaticReturn = false
        synchronized(stateLock) {
            val old = state ?: return
            val operation = old.manualOperation?.takeIf { it.operationId == operationId } ?: return
            if (restoreOwnerOperationId == operationId) restoreOwnerOperationId = null
            val terminal = WifiManualResult(operationId, operation.revision, "failed", reason, rolledBack)
            val next = old.copy(
                manualOperation = null,
                latestResult = terminal,
            )
            if (!writeAndCommit(next)) return
            delayAutomaticReturn(operation, succeeded = false)
            failedSelection = operation.takeIf { it.selectionOnly && it.retryDisconnected }
            resumeAutomaticReturn = operation.selectionOnly && !operation.retryDisconnected
            terminal
        }
        cancelAlarm(operationId)
        closeAttempt(operationId)
        failedSelection?.let(::schedulePriorityReconnectRetry)
        if (resumeAutomaticReturn) reconcilePriorityRuntime()
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
                latestResult = terminal,
            )
            if (!writeAndCommit(next)) return
            terminal
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
            if (!writeAndCommit(old.copy(
                    manualOperation = fenced,
                    latestResult = terminal,
                ))
            ) return
            terminal
        }
        // Keep the fence until recovery succeeds or an explicit unmanaged command abandons it.
    }

    private fun finishManualNoChangeUnknown(operationId: String) {
        synchronized(stateLock) {
            val old = state ?: return
            val operation = old.manualOperation?.takeIf { it.operationId == operationId } ?: return
            val terminal = WifiManualResult(operationId, operation.revision, "unknown", WifiReason.UNKNOWN, false)
            val next = old.copy(
                manualOperation = null,
                latestResult = terminal,
            )
            if (!writeAndCommit(next)) return
            delayAutomaticReturn(operation, succeeded = false)
            terminal
        }
        cancelAlarm(operationId)
    }

    private fun persistManualResult(result: WifiManualResult) {
        persist { old ->
            old.copy(
                latestResult = result,
                processedOperationIds = appendWifiOperationReceipt(old.processedOperationIds, result.operationId),
            )
        }
    }

    /** The operation executor is the single owner of the priority session and scanner. */
    private fun reconcilePriorityRuntime() {
        operationExecutor.execute(::reconcilePriorityRuntimeNow)
    }

    private fun reconcilePriorityRuntimeNow() {
        val current = synchronized(stateLock) {
            state?.takeIf { startup.isDone && it.managedWifi && it.manualOperation == null }
                ?.let { it to it.lastApplied }
        }
        val profile = current?.second
        if (profile == null) {
            stopPriorityRuntime()
            return
        }
        val epoch = current!!.first.policyEpoch
        var session = prioritySession
        if (session == null || prioritySessionRevision != profile.revision || prioritySessionEpoch != epoch) {
            closePrioritySession()
            session = try {
                connectivity.observe { event ->
                    operationExecutor.execute { onPriorityConnectivity(profile.revision, epoch, event) }
                }
            } catch (_: Exception) {
                setPriorityMonitorStatus(
                    if (profile.autoReturn) WifiPriorityMonitorStatus.UNSUPPORTED else WifiPriorityMonitorStatus.OFF,
                )
                return
            }
            prioritySession = session
            prioritySessionRevision = profile.revision
            prioritySessionEpoch = epoch
        }

        val liveSession = prioritySession ?: return
        val connected = liveSession.snapshot().filter { it.hasUsableIp }
        if (connected.isEmpty()) {
            stopPriorityScanner()
            setPriorityMonitorStatus(WifiPriorityMonitorStatus.OFF)
            schedulePrioritySelectionAfterGrace(profile.revision, epoch)
            return
        }
        val currentNetwork = connected.maxByOrNull { it.sequence }
        val currentIndex = currentNetwork?.let { observation ->
            profile.networks.indexOfFirst { observationMatches(it, observation) }
        } ?: -1
        if (!profile.autoReturn || currentIndex <= 0) {
            stopPriorityScanner()
            setPriorityMonitorStatus(WifiPriorityMonitorStatus.OFF)
            return
        }
        val scanner = priorityScanSource
        if (scanner == null) {
            setPriorityMonitorStatus(WifiPriorityMonitorStatus.UNSUPPORTED)
            return
        }
        if (priorityScanRevision != profile.revision || priorityScanEpoch != epoch) {
            stopPriorityScanner()
            priorityScanRevision = profile.revision
            priorityScanEpoch = epoch
            scanner.start(profile.networks) { event ->
                operationExecutor.execute { onPriorityScanEvent(profile.revision, epoch, event) }
            }
        }
    }

    private fun stopPriorityScanner() {
        priorityScanSource?.stop()
        priorityScanRevision = null
        priorityScanEpoch = null
    }

    private fun stopPriorityRuntime() {
        stopPriorityScanner()
        closePrioritySession()
        setPriorityMonitorStatus(WifiPriorityMonitorStatus.OFF)
    }

    private fun closePrioritySession() {
        prioritySession?.close()
        prioritySession = null
        prioritySessionRevision = null
        prioritySessionEpoch = null
    }

    private fun setPriorityMonitorStatus(status: WifiPriorityMonitorStatus) {
        if (priorityMonitorStatus == status) return
        priorityMonitorStatus = status
        notifyStateChanged()
    }

    private fun onPriorityConnectivity(revision: String, epoch: Long, event: WifiLocalConnectivityEvent) {
        synchronized(stateLock) {
            val current = state ?: return
            val saved = current.lastApplied ?: return
            if (!current.managedWifi || current.policyEpoch != epoch || current.manualOperation != null ||
                saved.revision != revision
            ) return
        }
        if (event is WifiLocalConnectivityEvent.Updated) {
            if (event.observation.hasUsableIp) {
                synchronized(stateLock) { priorityDisconnectGeneration++ }
                reconcilePriorityRuntime()
            } else if (prioritySession?.snapshot()?.any { it.hasUsableIp } != true) {
                stopPriorityScanner()
                setPriorityMonitorStatus(WifiPriorityMonitorStatus.OFF)
                schedulePrioritySelectionAfterGrace(revision, epoch)
            } else {
                reconcilePriorityRuntime()
            }
        } else if (event is WifiLocalConnectivityEvent.Lost) {
            val stillConnected = prioritySession?.snapshot()?.any { it.hasUsableIp } == true
            if (!stillConnected) {
                stopPriorityScanner()
                setPriorityMonitorStatus(WifiPriorityMonitorStatus.OFF)
                schedulePrioritySelectionAfterGrace(revision, epoch)
            } else {
                reconcilePriorityRuntime()
            }
        }
    }

    private fun schedulePrioritySelectionAfterGrace(revision: String, epoch: Long) {
        val token = synchronized(stateLock) { ++priorityDisconnectGeneration }
        schedulePriorityTimeout(Runnable {
            operationExecutor.execute {
                val current = synchronized(stateLock) {
                    val value = state ?: return@execute
                    val saved = value.lastApplied ?: return@execute
                    if (priorityDisconnectGeneration != token || !value.managedWifi || value.manualOperation != null ||
                        value.policyEpoch != epoch || value.policyRevision != revision || saved.revision != revision
                    ) return@execute
                    value to saved
                }
                val networks = prioritySession?.snapshot()?.filter { it.hasUsableIp }.orEmpty()
                if (networks.isNotEmpty()) {
                    reconcilePriorityRuntime()
                    return@execute
                }
                startPrioritySelection(revision, epoch, current.second.networks.indices.toList(), retryDisconnected = true)
            }
        }, WIFI_PRIORITY_DISCONNECT_GRACE_MS)
    }

    private fun onPriorityScanEvent(revision: String, epoch: Long, event: WifiPriorityScanEvent) {
        val saved = synchronized(stateLock) {
            val current = state ?: return
            val profile = current.lastApplied ?: return
            if (!current.managedWifi || current.policyEpoch != epoch || current.manualOperation != null ||
                profile.revision != revision || !profile.autoReturn
            ) return
            profile
        }
        setPriorityMonitorStatus(event.status)
        event.detail?.let { detail ->
            if (event.status == WifiPriorityMonitorStatus.DEGRADED ||
                event.status == WifiPriorityMonitorStatus.UNSUPPORTED
            ) Log.w(TAG, "Wi-Fi priority monitor status=${event.status.wireValue} detail=$detail")
        }
        if (event.status != WifiPriorityMonitorStatus.MONITORING || event.visibleIdentities.isEmpty()) return
        if (synchronized(stateLock) {
                autoReturnRetryRevision == revision && autoReturnRetryEpoch == epoch &&
                    nowElapsed() < autoReturnRetryAllowedAtElapsedMillis
            }
        ) return

        val currentObservation = prioritySession?.snapshot()?.filter { it.hasUsableIp }
            ?.maxByOrNull { it.sequence }
        val currentIndex = currentObservation?.let { observation ->
            saved.networks.indexOfFirst { observationMatches(it, observation) }
        } ?: -1
        if (currentIndex <= 0) {
            reconcilePriorityRuntime()
            return
        }
        val upperCandidates = saved.networks.indices.filter { index ->
            index < currentIndex && saved.networks[index].identity in event.visibleIdentities
        }
        if (upperCandidates.isNotEmpty() &&
            startPrioritySelection(revision, epoch, upperCandidates)
        ) {
            setPriorityMonitorStatus(WifiPriorityMonitorStatus.SWITCHING)
        }
    }

    // Called with stateLock held when an automatic-return operation settles. Repeated
    // failures (an unusable preferred AP) back off so the working connection is not
    // interrupted every five minutes indefinitely.
    private fun delayAutomaticReturn(operation: WifiManualOperation, succeeded: Boolean) {
        if (!operation.selectionOnly || operation.retryDisconnected) return
        if (autoReturnRetryRevision != operation.revision || autoReturnRetryEpoch != operation.policyEpoch) {
            autoReturnFailures = 0
        }
        autoReturnFailures = if (succeeded) 0 else (autoReturnFailures + 1).coerceAtMost(16)
        val multiplier = 1L shl (autoReturnFailures - 1).coerceIn(0, 10)
        autoReturnRetryRevision = operation.revision
        autoReturnRetryEpoch = operation.policyEpoch
        autoReturnRetryAllowedAtElapsedMillis = nowElapsed() +
            (WIFI_AUTO_RETURN_RETRY_DELAY_MS * multiplier).coerceAtMost(WIFI_AUTO_RETURN_MAX_RETRY_DELAY_MS)
    }

    private fun startPrioritySelection(
        revision: String,
        epoch: Long,
        indexes: List<Int>,
        retryDisconnected: Boolean = false,
    ): Boolean {
        val normalizedIndexes = indexes.distinct().filter { it >= 0 }
        if (normalizedIndexes.isEmpty() || retryDisconnected && nowElapsed() < priorityRetryAllowedAtElapsedMillis) return false
        // Never start an automatic switch while a release is waiting for the current one.
        if (pendingRelease != null) return false
        val current = synchronized(stateLock) {
            val value = state ?: return@synchronized null
            val saved = value.lastApplied ?: return@synchronized null
            if (!startup.isDone || !storageHealthy || value.manualOperation != null || !value.managedWifi ||
                value.policyEpoch != epoch || value.policyRevision != revision || saved.revision != revision ||
                normalizedIndexes.any { it !in saved.networks.indices }
            ) return@synchronized null
            value to saved
        } ?: return false
        connectivity.preflight()?.let { reason ->
            if (current.second.autoReturn) setPriorityMonitorStatus(WifiPriorityMonitorStatus.DEGRADED)
            Log.w(TAG, "Wi-Fi priority selection preflight failed: ${reason.wireValue}")
            return false
        }
        val recovery = try {
            adapter.captureRecovery(current.second.networks)
        } catch (failure: WifiAdapterFailure) {
            if (current.second.autoReturn) setPriorityMonitorStatus(WifiPriorityMonitorStatus.DEGRADED)
            Log.w(TAG, "Wi-Fi priority selection snapshot failed: ${failure.reason.wireValue}")
            return false
        } catch (_: Exception) {
            if (current.second.autoReturn) setPriorityMonitorStatus(WifiPriorityMonitorStatus.DEGRADED)
            return false
        }
        val operation = synchronized(stateLock) {
            val old = state ?: return@synchronized null
            val saved = old.lastApplied ?: return@synchronized null
            if (old.manualOperation != null || !old.managedWifi || old.policyEpoch != epoch ||
                old.policyRevision != revision || saved.revision != revision || normalizedIndexes.any { it !in saved.networks.indices }
            ) return@synchronized null
            val nextOperation = WifiManualOperation(
                operationId = UUID.randomUUID().toString(),
                revision = revision,
                candidate = saved.networks,
                recoverySnapshot = recovery,
                phase = WifiManualPhase.PREPARED,
                policyEpoch = epoch,
                autoReturn = saved.autoReturn,
                selectionOnly = true,
                selectionIndexes = normalizedIndexes,
                retryDisconnected = retryDisconnected,
            )
            if (writeAndCommit(old.copy(manualOperation = nextOperation))) nextOperation else null
        } ?: return false
        stopPriorityScanner()
        if (current.second.autoReturn && !retryDisconnected) setPriorityMonitorStatus(WifiPriorityMonitorStatus.SWITCHING)
        operationExecutor.execute { applyManual(operation) }
        return true
    }

    private fun schedulePriorityReconnectRetry(operation: WifiManualOperation) {
        val saved = synchronized(stateLock) {
            val current = state ?: return
            val profile = current.lastApplied ?: return
            if (!current.managedWifi || current.manualOperation != null || current.policyEpoch != operation.policyEpoch ||
                current.policyRevision != operation.revision || profile.revision != operation.revision ||
                !operation.retryDisconnected
            ) return
            profile
        }
        if (saved.autoReturn) setPriorityMonitorStatus(WifiPriorityMonitorStatus.DEGRADED)
        if (!operation.retryDisconnected) return
        if (prioritySession?.snapshot()?.any { it.hasUsableIp } == true) return
        priorityReconnectFailures = (priorityReconnectFailures + 1).coerceAtMost(16)
        val multiplier = 1L shl (priorityReconnectFailures - 1).coerceAtMost(10)
        val delay = (WIFI_PRIORITY_RETRY_BACKOFF_MS * multiplier)
            .coerceAtMost(WIFI_PRIORITY_MAX_RETRY_BACKOFF_MS)
        priorityRetryAllowedAtElapsedMillis = nowElapsed() + delay
        schedulePriorityTimeout(Runnable {
            operationExecutor.execute {
                if (nowElapsed() < priorityRetryAllowedAtElapsedMillis) return@execute
                val current = synchronized(stateLock) {
                    val value = state ?: return@execute
                    val profile = value.lastApplied ?: return@execute
                    if (!value.managedWifi || value.manualOperation != null || value.policyEpoch != operation.policyEpoch ||
                        value.policyRevision != operation.revision || profile.revision != operation.revision ||
                        profile.autoReturn != saved.autoReturn
                    ) return@execute
                    profile
                }
                if (prioritySession?.snapshot()?.any { it.hasUsableIp } == true) return@execute
                startPrioritySelection(
                    operation.revision,
                    operation.policyEpoch,
                    current.networks.indices.toList(),
                    retryDisconnected = true,
                )
            }
        }, delay)
    }

    private fun observationMatches(
        profile: WifiNetworkProfile,
        observation: WifiLocalNetworkObservation,
    ): Boolean {
        if (profile.ssid != observation.ssid) return false
        return wifiProfileAcceptsObservedSecurity(profile, observation.security)
    }

    private fun recoverInterruptedOperation() {
        var completed = false
        try {
            val current = synchronized(stateLock) { state }
            if (current == null || !storageHealthy) return
            current.manualOperation?.let { operation ->
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
                        if (!restored && !pendingConfirmation) return
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
        priorityScanSource = systemWifiPriorityScanSource(appContext),
        bootCount = { Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1) },
        operationExecutor = operationExecutor,
        rollbackExecutor = rollbackExecutor,
        scheduleWatchdog = { task, delay -> watchdog.schedule(task, delay, java.util.concurrent.TimeUnit.MILLISECONDS) },
        schedulePriorityTimeout = { task, delay -> watchdog.schedule(task, delay, java.util.concurrent.TimeUnit.MILLISECONDS) },
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
