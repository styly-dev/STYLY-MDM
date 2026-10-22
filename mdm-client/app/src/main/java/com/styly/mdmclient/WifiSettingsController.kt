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
    val bootGeneration: () -> String?,
    val scheduleAlarm: (String, Long) -> Boolean,
    val cancelAlarm: (String) -> Unit,
    val operationExecutor: Executor,
    val rollbackExecutor: Executor,
    val scheduleWatchdog: (Runnable, Long) -> Unit,
    val readinessFailure: () -> WifiReason? = { null },
)

/** Process-wide Wi-Fi owner; service recreation never creates a second transaction owner. */
internal class WifiSettingsController internal constructor(
    dependencies: WifiControllerDependencies,
    autoStart: Boolean = true,
) {
    private enum class LocalAttemptPhase {
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
    )

    companion object {
        private const val TAG = "WifiSettings"
        const val ACTION_ROLLBACK = "com.styly.mdmclient.WIFI_ROLLBACK"
    }

    constructor(context: Context) : this(productionDependencies(context.applicationContext), autoStart = false)

    private val stateLock = Any()
    private val store = dependencies.store
    private val adapter = dependencies.adapter
    private val connectivity = dependencies.connectivity
    private val nowElapsed = dependencies.nowElapsed
    private val bootGeneration = dependencies.bootGeneration
    private val scheduleAlarm = dependencies.scheduleAlarm
    private val cancelAlarm = dependencies.cancelAlarm
    private val operationExecutor = dependencies.operationExecutor
    private val rollbackExecutor = dependencies.rollbackExecutor
    private val scheduleWatchdog = dependencies.scheduleWatchdog
    private val readinessFailure = dependencies.readinessFailure
    private val stateListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    @Volatile private var unavailableReason: String? = "INITIALIZING"
    private val activeMutations = AtomicInteger(0)
    private val startup = CompletableFuture<Boolean>()
    private val startupStarted = AtomicBoolean(false)
    private var localAttempt: LocalAttempt? = null
    private var restoreOwnerOperationId: String? = null

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
                operationExecutor.execute(::recoverAndCheckBoot)
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
        unavailableReason = try { readinessFailure()?.wireValue } catch (_: Exception) { WifiReason.UNKNOWN.wireValue }
        notifyStateChanged()
    }

    private fun notifyStateChanged() {
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

    fun handleServerMessage(
        type: String,
        payload: JSONObject,
        canonicalRegistrationAcknowledged: Boolean,
    ): Boolean {
        if (type != "EXECUTE_WIFI_APPLY") return type.startsWith("WIFI_")
        if (!canonicalRegistrationAcknowledged) return true
        operationExecutor.execute {
            updateReadiness()
            acceptManualCommand(payload)
        }
        return true
    }

    private fun acceptManualCommand(payload: JSONObject) {
        val operationId = payload.optString("operation_id", "")
        val revision = payload.optString("revision", "")
        if (!WifiSettingsProtocol.isValidUuid(operationId) || !WifiSettingsProtocol.isValidUuid(revision)) return

        if (synchronized(stateLock) { state?.processedOperationIds?.contains(operationId) == true }) return

        try {
            val parsed = WifiSettingsProtocol.parseApplyCommand(payload)
            if (parsed.operationId != operationId) return
            if (parsed.mode == WifiApplyMode.UNMANAGED) {
                applyUnmanagedCommand(parsed)
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
            if (commitUnchangedPolicy(parsed, current)) return
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
        } catch (_: IllegalArgumentException) {
            persistManualResult(WifiManualResult(operationId, revision, "failed", WifiReason.UNSUPPORTED, false))
        } catch (_: Exception) {
            persistManualResult(WifiManualResult(operationId, revision, "failed", WifiReason.CONFIG_READ_FAILED, false))
        }
    }

    private fun applyUnmanagedCommand(command: WifiApplyCommand) {
        var abandonedOperationId: String? = null
        synchronized(stateLock) {
            val old = state ?: return
            // A completed failed restore can be explicitly abandoned. An active Binder call
            // or reserved restore owner must retain its fence until it has returned.
            val abandoned = old.manualOperation?.takeIf {
                it.phase == WifiManualPhase.ROLLING_BACK && it.failureReason == WifiReason.RESTORE_FAILED &&
                    startup.isDone && activeMutations.get() == 0 && restoreOwnerOperationId == null
            }
            val terminal = WifiManualResult(
                command.operationId,
                command.revision,
                "success",
                WifiReason.NONE,
                rolledBack = false,
            )
            val next = old.copy(
                managedWifi = false,
                manualOperation = if (abandoned != null) null else old.manualOperation,
                policyEpoch = old.policyEpoch + 1L,
                policyRevision = command.revision,
                latestResult = terminal,
                processedOperationIds = appendWifiOperationReceipt(old.processedOperationIds, command.operationId),
            )
            if (writeAndCommit(next)) abandonedOperationId = abandoned?.operationId
        }
        abandonedOperationId?.let { cancelAlarm(it); closeAttempt(it) }
    }

    /** The durable successful profile is our only trustworthy source of known credentials. */
    private fun commitUnchangedPolicy(command: WifiApplyCommand, previous: WifiLocalState): Boolean {
        if (previous.lastApplied?.networks?.toSet() != command.networks.toSet()) return false
        if (!wifiProfileReadback(adapter.readCurrent(), command.networks)) return false
        val connected = connectivity.observe {}.use { session ->
            session.snapshot().any { observation ->
                observation.hasUsableIp && command.networks.any { it.ssid == observation.ssid }
            }
        }
        if (!connected) return false
        return synchronized(stateLock) {
            val current = state ?: return@synchronized false
            if (current != previous || current.manualOperation != null || activeMutations.get() != 0) return@synchronized false
            writeAndCommit(current.copy(
                managedWifi = true,
                policyRevision = command.revision,
                lastApplied = WifiSavedProfile(command.revision, command.networks),
                latestResult = WifiManualResult(command.operationId, command.revision, "success", WifiReason.NONE, false),
                processedOperationIds = appendWifiOperationReceipt(current.processedOperationIds, command.operationId),
            ))
        }
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
                operationId = operation.operationId,
                session = session,
                phase = LocalAttemptPhase.RECONNECTING,
            )
            // Keep the durable phase at PREPARED until an API mutation starts.
            current.phase == WifiManualPhase.PREPARED
        }
        if (!installed) {
            session.close()
            return
        }

        val applyReason = adapter.applyProfile(
            target = operation.candidate,
            shouldContinue = {
                val latest = synchronized(stateLock) { state?.manualOperation }
                latest?.operationId == operation.operationId &&
                    (latest.phase == WifiManualPhase.PREPARED || latest.phase == WifiManualPhase.APPLYING) &&
                    (latest.deadlineElapsedMillis == null || nowElapsed() < latest.deadlineElapsedMillis)
            },
            beforeFirstMutation = { prepareManualDeadline(operation.operationId) },
            onMutationStarted = { beginManualMutation(operation.operationId) },
            onMutationFinished = { finishMutation(operation.operationId) },
        )

        val latest = synchronized(stateLock) { state?.manualOperation }
            ?.takeIf { it.operationId == operation.operationId } ?: return
        if (latest.phase == WifiManualPhase.ROLLING_BACK) {
            continueClaimedRollback(operation.operationId)
            return
        }
        if (applyReason == WifiReason.NONE) {
            beginLocalReconnectFlow(operation.operationId)
            return
        }

        val changed = latest.settingsChanged || activeMutations.get() != 0
        if (changed) requestRollback(operation.operationId, applyReason)
        else {
            closeAttempt(operation.operationId)
            finishManualFailure(operation.operationId, applyReason, rolledBack = false)
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
            val operation = state?.manualOperation?.takeIf {
                it.operationId == operationId && it.phase == WifiManualPhase.APPLYING
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

        val attempt = synchronized(stateLock) {
            val active = localAttempt?.takeIf {
                it.operationId == operationId && it.phase == LocalAttemptPhase.RECONNECTING
            } ?: return
            val operation = state?.manualOperation?.takeIf {
                it.operationId == operationId && it.phase == WifiManualPhase.APPLYING
            } ?: return
            if (operation.deadlineElapsedMillis?.let { nowElapsed() >= it } == true) return
            active.reconnectStartSequence = active.session.lastSequence()
            active.reconnectAccepted = false
            active
        }

        val reason = adapter.reconnect(
            shouldContinue = { manualOperationCanMutate(operationId) },
            onMutationStarted = { beginManualMutation(operationId) },
            onMutationFinished = { finishMutation(operationId) },
        )
        if (reason != WifiReason.NONE) {
            failManualBeforeReadiness(operationId, reason)
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
        evaluateFreshSnapshot(attempt)
    }

    private fun onConnectivityEvent(operationId: String, event: WifiLocalConnectivityEvent) {
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
            val operation = state?.manualOperation?.takeIf {
                it.operationId == operationId && it.phase == WifiManualPhase.APPLYING
            } ?: return@synchronized false
            val deadline = operation.deadlineElapsedMillis ?: return@synchronized false
            val observedAt = nowElapsed()
            val ready = observedAt < deadline &&
                activeMutations.get() == 0 &&
                observation.sequence > attempt.reconnectStartSequence &&
                observation.networkHandle !in attempt.oldNetworkHandles &&
                operation.candidate.any { it.ssid == observation.ssid } &&
                observation.hasUsableIp
            if (ready) {
                attempt.readyObservedElapsedMillis = observedAt
                attempt.phase = LocalAttemptPhase.COMMITTING
            }
            ready
        }

    private fun commitLocalSuccess(operationId: String) {
        var completedSession: WifiLocalConnectivitySession? = null
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
            val terminal = WifiManualResult(operationId, operation.revision, "success", WifiReason.NONE, false)
            val next = old.copy(
                lastApplied = if (operation.policyEpoch == old.policyEpoch) {
                    WifiSavedProfile(operation.revision, operation.candidate)
                } else {
                    old.lastApplied
                },
                managedWifi = if (operation.policyEpoch == old.policyEpoch) true else old.managedWifi,
                policyRevision = if (operation.policyEpoch == old.policyEpoch) operation.revision else old.policyRevision,
                manualOperation = null,
                latestResult = terminal,
            )
            if (writeAndCommit(next)) {
                localAttempt = null
                completedSession = attempt.session
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
        val operation = state?.manualOperation?.takeIf { it.operationId == operationId } ?: return@synchronized false
        operation.phase == WifiManualPhase.APPLYING &&
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
            if (operation.phase !in setOf(WifiManualPhase.PREPARED, WifiManualPhase.APPLYING)) {
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
        if (!scheduleAlarm(operationId, persistedDeadline)) return WifiReason.UNSUPPORTED
        val delay = (persistedDeadline - nowElapsed()).coerceAtLeast(0)
        scheduleWatchdog(Runnable { requestRollback(operationId, WifiReason.TIMEOUT_ROLLED_BACK) }, delay)
        return null
    }

    private fun beginManualMutation(operationId: String): WifiReason? = synchronized(stateLock) {
            val old = state ?: return@synchronized WifiReason.UNKNOWN
            val operation = old.manualOperation?.takeIf { it.operationId == operationId }
                ?: return@synchronized WifiReason.TIMEOUT_ROLLED_BACK
            val deadline = operation.deadlineElapsedMillis ?: return@synchronized WifiReason.UNKNOWN
            if (operation.phase != WifiManualPhase.APPLYING || nowElapsed() >= deadline) {
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
                candidate = reserved.candidate,
                shouldContinue = { true },
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
        if (restoreReason == WifiReason.NONE) {
            finishManualFailure(
                operationId,
                reserved.failureReason ?: reason,
                rolledBack = true,
            )
        } else {
            finishManualRestoreFailure(operationId)
        }
        return synchronized(stateLock) { state?.manualOperation?.operationId != operationId }
    }

    private fun finishManualFailure(operationId: String, reason: WifiReason, rolledBack: Boolean) {
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
            terminal
        }
        cancelAlarm(operationId)
        closeAttempt(operationId)
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

    private fun recoverAndCheckBoot() {
        var completed = false
        try {
            var current = synchronized(stateLock) { state }
            if (current == null || !storageHealthy) return
            current.manualOperation?.let { operation ->
                if (operation.settingsChanged) {
                    if (!restoreClaimedOperation(
                            operation.operationId,
                            operation.failureReason ?: WifiReason.UNKNOWN,
                            allowExistingClaim = true,
                        )
                    ) return
                } else {
                    finishManualNoChangeUnknown(operation.operationId)
                }
            }

            current = synchronized(stateLock) { state } ?: return
            if (current.manualOperation != null) return
            val generation = bootGeneration() ?: run {
                completed = true
                return
            }
            current = synchronized(stateLock) { state } ?: return
            if (current.bootGeneration == null) {
                if (!persist { it.copy(bootGeneration = generation) }) return
            } else if (current.bootGeneration != generation) {
                if (!runBootCorrection(generation)) return
            }
            completed = true
        } catch (_: Exception) {
            Log.e(TAG, "Wi-Fi startup recovery did not complete")
        } finally {
            startup.complete(completed)
            updateReadiness()
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

    private fun runBootCorrection(generation: String): Boolean {
        val alreadyClaimed = synchronized(stateLock) { state?.bootGeneration == generation }
        if (alreadyClaimed) return true
        if (!persist { old ->
                if (old.manualOperation != null) old else old.copy(bootGeneration = generation)
            }
        ) return false

        val profile = synchronized(stateLock) {
            state?.takeIf { it.bootGeneration == generation && it.manualOperation == null && it.managedWifi }
                ?.lastApplied
        } ?: return true
        val reason = adapter.applyProfile(profile.networks)
        if (reason != WifiReason.NONE) Log.w(TAG, "Wi-Fi boot reapply failed: ${reason.wireValue}")
        return true
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
        readinessFailure = {
            adapter.readinessFailure() ?: connectivity.preflight() ?: run {
                val alarm = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                if (alarm == null || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms())) {
                    WifiReason.PERMISSION_DENIED
                } else null
            }
        },
        store = WifiSettingsStore(File(appContext.noBackupFilesDir, "wifi-settings.json"), ::syncDirectory),
        adapter = adapter,
        nowElapsed = SystemClock::elapsedRealtime,
        bootGeneration = { currentBootGeneration(appContext) },
        scheduleAlarm = { operationId, deadline -> scheduleExactRollback(appContext, operationId, deadline) },
        cancelAlarm = { operationId -> cancelExactRollback(appContext, operationId) },
        connectivity = connectivity,
        operationExecutor = operationExecutor,
        rollbackExecutor = rollbackExecutor,
        scheduleWatchdog = { task, delay -> watchdog.schedule(task, delay, java.util.concurrent.TimeUnit.MILLISECONDS) },
    )
}

private fun currentBootGeneration(context: Context): String? {
    val bootCount = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (_: Exception) {
        -1
    }
    if (bootCount >= 0) return "count:$bootCount"
    return try {
        File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf { it.isNotEmpty() }?.let { "id:$it" }
    } catch (_: Exception) {
        null
    }
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
