package com.styly.mdmclient

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import java.util.concurrent.atomic.AtomicInteger

internal data class WifiControllerDependencies(
    val store: WifiLocalStore,
    val adapter: WifiSettingsAdapter,
    val nowElapsed: () -> Long,
    val bootGeneration: () -> String?,
    val scheduleAlarm: (String, Long) -> Boolean,
    val cancelAlarm: () -> Unit,
    val operationExecutor: Executor,
    val rollbackExecutor: Executor,
    val scheduleWatchdog: (Runnable, Long) -> Unit,
)

/** Process-wide Wi-Fi protocol owner. It is deliberately independent of the foreground service. */
internal class WifiSettingsController internal constructor(
    dependencies: WifiControllerDependencies,
) {
    companion object {
        private const val TAG = "WifiSettings"
        const val ACTION_ROLLBACK = "com.styly.mdmclient.WIFI_ROLLBACK"
        private const val STATE_FILE = "wifi-settings-v1.json"
    }

    constructor(context: Context) : this(productionDependencies(context.applicationContext))

    private val stateLock = Any()
    private val store = dependencies.store
    private val adapter = dependencies.adapter
    private val nowElapsed = dependencies.nowElapsed
    private val bootGeneration = dependencies.bootGeneration
    private val scheduleAlarm = dependencies.scheduleAlarm
    private val cancelAlarm = dependencies.cancelAlarm
    private val operationExecutor = dependencies.operationExecutor
    private val rollbackExecutor = dependencies.rollbackExecutor
    private val scheduleWatchdog = dependencies.scheduleWatchdog
    private val activeMutations = AtomicInteger(0)
    private val startup = CompletableFuture<Boolean>()

    @Volatile private var storageHealthy = true
    @Volatile private var activeSender: ((JSONObject) -> Boolean)? = null
    private var state: WifiLocalState? = try {
        store.read()
    } catch (_: Exception) {
        storageHealthy = false
        null
    }

    init {
        operationExecutor.execute(::recoverAndCheckBoot)
    }

    fun startupCompletion(): CompletableFuture<Boolean> = startup

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

    fun connectionSettings(): WifiConnectionSettings = synchronized(stateLock) {
        state?.connection ?: WifiConnectionSettings()
    }

    fun saveConnectionSettings(settings: WifiConnectionSettings): Boolean = persist { old ->
        old.copy(connection = settings.copy(deviceToken = settings.deviceToken?.takeIf { it.isNotBlank() }))
    }

    fun authorizationTokenFor(url: String, manuallyEnrolledEndpoint: Boolean): String? =
        WifiTransportAuthorization.authorizationTokenFor(
            url,
            manuallyEnrolledEndpoint,
            connectionSettings(),
        )

    fun supportsWifiOn(url: String, manuallyEnrolledEndpoint: Boolean): Boolean =
        WifiTransportAuthorization.isAuthorizedSession(
            url,
            manuallyEnrolledEndpoint,
            connectionSettings(),
        )

    fun onTransportDisconnected() {
        activeSender = null
    }

    /** Called only after the server has acknowledged canonical REGISTERED. */
    fun onCanonicalRegistered(authorized: Boolean, send: (JSONObject) -> Boolean) {
        if (!authorized) return
        activeSender = send
        operationExecutor.execute {
            startup.join()
            val current = synchronized(stateLock) { state } ?: return@execute
            current.manualResults.filterNot { it.acknowledged }.forEach { send(it.toWireJson()) }
            current.bootResults.filterNot { it.acknowledged }.forEach { send(it.toWireJson()) }

            val operation = current.manualOperation
            if (operation?.phase == WifiManualPhase.VERIFYING) {
                val local = adapter.validateFinalState(operation.candidate, operation.finalStateValidated)
                if (local == "validated" && !WifiManualStateMachine.isExpired(operation, nowElapsed())) {
                    sendVerifyRequest(operation, send)
                } else {
                    requestRollback(operation.operationId, WifiReason.VERIFY_FAILED)
                }
            }

            val applied = current.lastApplied
            if (applied != null) {
                val observation = adapter.compareTo(applied.networks)
                send(JSONObject().apply {
                    put("type", "WIFI_OBSERVATION")
                    put("revision", applied.revision)
                    put("status", observation)
                })
            }
        }
    }

    fun handleServerMessage(
        type: String,
        payload: JSONObject,
        authorized: Boolean,
        send: (JSONObject) -> Boolean,
    ): Boolean {
        if (type != "EXECUTE_WIFI_APPLY" && !type.startsWith("WIFI_")) return false
        if (!authorized) return true
        when (type) {
            "EXECUTE_WIFI_APPLY" -> operationExecutor.execute { acceptManualCommand(payload, send) }
            "WIFI_VERIFY_ACK" -> operationExecutor.execute { acceptVerifyAck(payload, send) }
            "WIFI_RESULT_ACK" -> operationExecutor.execute { acceptResultAck(payload) }
        }
        return true
    }

    private fun acceptManualCommand(payload: JSONObject, send: (JSONObject) -> Boolean) {
        val operationId = payload.optString("operation_id", "")
        val revision = payload.optString("revision", "")
        if (!WifiSettingsProtocol.isValidUuid(operationId) || !WifiSettingsProtocol.isValidUuid(revision)) return

        val previous = synchronized(stateLock) {
            state?.let { current ->
                current.manualResults.firstOrNull { it.operationId == operationId }?.let { return@synchronized it.toWireJson() }
                current.manualOperation?.takeIf { it.operationId == operationId }?.let {
                    return@synchronized JSONObject().apply {
                        put("type", "WIFI_APPLY_ACCEPTED")
                        put("operation_id", operationId)
                    }
                }
                null
            }
        }
        if (previous != null) {
            send(previous)
            return
        }
        if (synchronized(stateLock) { state?.processedOperationIds?.contains(operationId) == true }) return

        try {
            val parsed = WifiSettingsProtocol.parseApplyCommand(payload)
            if (parsed.operationId != operationId) return
            val snapshot = adapter.restorableSnapshot()
            val operation = WifiManualOperation(
                operationId = operationId,
                revision = parsed.profile.revision,
                candidate = parsed.profile.networks,
                restoreSnapshot = snapshot,
                phase = WifiManualPhase.PREPARED,
            )
            val accepted = synchronized(stateLock) {
                val old = state ?: return@synchronized false
                if (old.manualOperation != null || old.bootOperation != null) return@synchronized false
                try {
                    val next = old.copy(
                        manualOperation = operation,
                        processedOperationIds = old.processedOperationIds + operationId,
                    )
                    store.write(next)
                    state = next
                    true
                } catch (_: Exception) {
                    storageHealthy = false
                    false
                }
            }
            if (!accepted) {
                persistManualResult(
                    WifiManualResult(operationId, revision, "failed", WifiReason.BUSY, false),
                    send,
                )
                return
            }

            send(JSONObject().apply {
                put("type", "WIFI_APPLY_ACCEPTED")
                put("operation_id", operationId)
            })
            operationExecutor.execute { applyManual(operation) }
        } catch (failure: WifiAdapterFailure) {
            persistManualResult(
                WifiManualResult(operationId, revision, "failed", failure.reason, false),
                send,
            )
        } catch (_: IllegalArgumentException) {
            persistManualResult(
                WifiManualResult(operationId, revision, "failed", WifiReason.UNSUPPORTED, false),
                send,
            )
        } catch (_: Exception) {
            persistManualResult(
                WifiManualResult(operationId, revision, "failed", WifiReason.CONFIG_READ_FAILED, false),
                send,
            )
        }
    }

    private fun applyManual(operation: WifiManualOperation) {
        val applyReason = adapter.applyProfile(
            target = operation.candidate,
            shouldContinue = {
                val latest = synchronized(stateLock) { state?.manualOperation }
                latest?.operationId == operation.operationId &&
                    latest.phase in setOf(WifiManualPhase.PREPARED, WifiManualPhase.APPLYING) &&
                    (latest.deadlineElapsedMillis == null || nowElapsed() < latest.deadlineElapsedMillis)
            },
            restoreSnapshot = null,
            expectedSnapshot = operation.restoreSnapshot,
            beforeFirstMutation = { prepareManualDeadline(operation.operationId) },
            onMutationStarted = { beginManualMutation(operation.operationId) },
            onMutationFinished = { activeMutations.decrementAndGet() },
        )

        val latest = synchronized(stateLock) { state?.manualOperation }
            ?.takeIf { it.operationId == operation.operationId } ?: return
        if (latest.phase == WifiManualPhase.ROLLING_BACK) {
            continueClaimedRollback(operation.operationId)
            return
        }
        if (applyReason == WifiReason.NONE) {
            val local = adapter.validateFinalState(operation.candidate, priorApiWriteValidated = true)
            val deadlineResult = if (latest.deadlineElapsedMillis == null) {
                prepareManualDeadline(operation.operationId)
            } else null
            val refreshed = synchronized(stateLock) { state?.manualOperation }
                ?.takeIf { it.operationId == operation.operationId } ?: return
            if (deadlineResult == null && local == "validated" &&
                !WifiManualStateMachine.isExpired(refreshed, nowElapsed())
            ) {
                val challenge = refreshed.challenge ?: UUID.randomUUID().toString()
                val verifying = WifiManualStateMachine.beginVerify(
                    refreshed,
                    challenge,
                    refreshed.deadlineElapsedMillis ?: (nowElapsed() + WIFI_MANUAL_TIMEOUT_MS),
                ).copy(finalStateValidated = true)
                if (beginManualVerification(operation.operationId, verifying)) {
                    activeSender?.invoke(JSONObject().apply {
                        put("type", "WIFI_VERIFY_REQUEST")
                        put("operation_id", operation.operationId)
                        put("challenge", challenge)
                    })
                    return
                }
            }
            if (deadlineResult != null) finishManualFailure(operation.operationId, deadlineResult, rolledBack = false)
            else requestRollback(operation.operationId, WifiReason.VERIFY_FAILED)
            return
        }

        val changed = latest.settingsChanged || activeMutations.get() != 0
        if (changed) requestRollback(operation.operationId, applyReason)
        else finishManualFailure(operation.operationId, applyReason, rolledBack = false)
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
            try {
                val next = old.copy(manualOperation = nextOperation)
                store.write(next)
                state = next
                deadline
            } catch (_: Exception) {
                storageHealthy = false
                null
            }
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
            val operation = claimRollback(operationId, reason) ?: return@execute
            if (activeMutations.get() > 0) {
                finishManualUnknown(operationId)
                return@execute
            }
            restoreClaimedOperation(operation)
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
            restoreClaimedOperation(operation)
        }
    }

    private fun restoreClaimedOperation(operation: WifiManualOperation) {
        val operationId = operation.operationId
        val restoreReason = adapter.applyProfile(
            target = operation.restoreSnapshot.map { it.profile },
            restoreSnapshot = operation.restoreSnapshot,
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
        if (restoreReason == WifiReason.NONE) {
            finishManualFailure(
                operationId,
                operation.failureReason ?: WifiReason.UNKNOWN,
                rolledBack = true,
            )
        } else {
            finishManualRestoreFailure(operationId)
        }
    }

    private fun finishManualFailure(operationId: String, reason: WifiReason, rolledBack: Boolean) {
        val result = synchronized(stateLock) {
            val old = state ?: return
            val operation = old.manualOperation?.takeIf { it.operationId == operationId } ?: return
            val terminal = WifiManualResult(operationId, operation.revision, "failed", reason, rolledBack)
            val next = old.copy(
                manualOperation = null,
                manualResults = keepPendingResults(old.manualResults, terminal),
            )
            if (!writeAndCommit(next)) return
            terminal
        }
        cancelAlarm()
        sendPendingResult(result)
    }

    private fun finishManualUnknown(operationId: String) {
        val result = synchronized(stateLock) {
            val old = state ?: return
            val operation = old.manualOperation?.takeIf { it.operationId == operationId } ?: return
            val terminal = WifiManualResult(operationId, operation.revision, "unknown", WifiReason.UNKNOWN, false)
            val next = old.copy(
                manualOperation = operation.copy(
                    phase = WifiManualPhase.ROLLING_BACK,
                    failureReason = operation.failureReason ?: WifiReason.UNKNOWN,
                ),
                manualResults = keepPendingResults(old.manualResults, terminal),
            )
            if (!writeAndCommit(next)) return
            terminal
        }
        sendPendingResult(result)
    }

    private fun finishManualRestoreFailure(operationId: String) {
        val result = synchronized(stateLock) {
            val old = state ?: return
            val operation = old.manualOperation?.takeIf { it.operationId == operationId } ?: return
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
                    manualResults = keepPendingResults(old.manualResults, terminal),
                ))
            ) return
            terminal
        }
        sendPendingResult(result)
    }

    private fun finishManualNoChangeUnknown(operationId: String) {
        val result = synchronized(stateLock) {
            val old = state ?: return
            val operation = old.manualOperation?.takeIf { it.operationId == operationId } ?: return
            val terminal = WifiManualResult(operationId, operation.revision, "unknown", WifiReason.UNKNOWN, false)
            val next = old.copy(
                manualOperation = null,
                manualResults = keepPendingResults(old.manualResults, terminal),
            )
            if (!writeAndCommit(next)) return
            terminal
        }
        cancelAlarm()
        sendPendingResult(result)
    }

    private fun acceptVerifyAck(payload: JSONObject, send: (JSONObject) -> Boolean) {
        val operationId = payload.optString("operation_id", "")
        val challenge = payload.optString("challenge", "")
        val operation = synchronized(stateLock) { state?.manualOperation } ?: return
        if (!WifiManualStateMachine.acceptsVerifyAck(
                operation,
                operationId,
                challenge,
                nowElapsed(),
            )
        ) return
        if (adapter.validateFinalState(operation.candidate, operation.finalStateValidated) != "validated") {
            requestRollback(operationId, WifiReason.VERIFY_FAILED)
            return
        }
        val result = WifiManualResult(operationId, operation.revision, "success", WifiReason.NONE, false)
        val committed = synchronized(stateLock) {
            val old = state ?: return@synchronized false
            val current = old.manualOperation?.takeIf {
                it.operationId == operationId && it.phase == WifiManualPhase.VERIFYING
            } ?: return@synchronized false
            if (!WifiManualStateMachine.acceptsVerifyAck(
                    current,
                    operationId,
                    challenge,
                    nowElapsed(),
                )
            ) return@synchronized false
            val next = old.copy(
                lastApplied = WifiSavedProfile(operation.revision, operation.candidate),
                manualOperation = null,
                manualResults = keepPendingResults(old.manualResults, result),
            )
            writeAndCommit(next)
        }
        if (committed) {
            cancelAlarm()
            send(result.toWireJson())
        }
    }

    private fun acceptResultAck(payload: JSONObject) {
        val kind = payload.optString("kind", "")
        val operationId = payload.optString("operation_id", "")
        val bootId = payload.optString("boot_id", "")
        val revision = payload.optString("revision", "")
        val status = payload.optString("status", "")
        val reason = payload.optString("reason", "")
        val rolledBack = payload.opt("rolled_back") as? Boolean
        persist { old ->
            when (kind) {
                "manual" -> old.copy(manualResults = old.manualResults.map {
                    if (it.operationId == operationId && it.revision == revision &&
                        it.status == status && it.reason.wireValue == reason && it.rolledBack == rolledBack
                    ) it.copy(acknowledged = true) else it
                })
                "boot" -> old.copy(bootResults = old.bootResults.map {
                    if (it.bootId == bootId && it.revision == revision &&
                        it.status == status && it.reason.wireValue == reason
                    ) it.copy(acknowledged = true) else it
                })
                else -> old
            }
        }
    }

    private fun persistManualResult(
        result: WifiManualResult,
        send: (JSONObject) -> Boolean,
    ) {
        val committed = persist { old ->
            old.copy(
                manualResults = keepPendingResults(old.manualResults, result),
                processedOperationIds = if (result.operationId in old.processedOperationIds) old.processedOperationIds
                else old.processedOperationIds + result.operationId,
            )
        }
        if (committed) send(result.toWireJson())
    }

    private fun beginManualVerification(operationId: String, verifying: WifiManualOperation): Boolean = synchronized(stateLock) {
        val old = state ?: return@synchronized false
        val current = old.manualOperation?.takeIf {
            it.operationId == operationId && it.phase == WifiManualPhase.APPLYING
        } ?: return@synchronized false
        val deadline = current.deadlineElapsedMillis ?: return@synchronized false
        if (nowElapsed() >= deadline || verifying.challenge == null ||
            verifying.finalStateValidated != true
        ) return@synchronized false
        writeAndCommit(old.copy(manualOperation = verifying.copy(deadlineElapsedMillis = deadline)))
    }

    private fun sendPendingResult(result: WifiManualResult) {
        activeSender?.invoke(result.toWireJson())
    }

    private fun sendVerifyRequest(operation: WifiManualOperation, send: (JSONObject) -> Boolean) {
        if (operation.challenge == null) return
        send(JSONObject().apply {
            put("type", "WIFI_VERIFY_REQUEST")
            put("operation_id", operation.operationId)
            put("challenge", operation.challenge)
        })
    }

    private fun keepPendingResults(
        existing: List<WifiManualResult>,
        replacement: WifiManualResult,
    ): List<WifiManualResult> {
        val combined = existing.filterNot { it.operationId == replacement.operationId } + replacement
        val pending = combined.filterNot { it.acknowledged }
        val acknowledged = combined.filter { it.acknowledged }.takeLast(100)
        return acknowledged + pending
    }

    private fun keepPendingBootResults(
        existing: List<WifiBootResult>,
        replacement: WifiBootResult,
    ): List<WifiBootResult> {
        val combined = existing.filterNot { it.bootId == replacement.bootId } + replacement
        val pending = combined.filterNot { it.acknowledged }
        val acknowledged = combined.filter { it.acknowledged }.takeLast(100)
        return acknowledged + pending
    }

    private fun recoverAndCheckBoot() {
        var completed = false
        try {
            var current = synchronized(stateLock) { state }
            if (current == null || !storageHealthy) return
            current.manualOperation?.let { operation ->
                if (operation.settingsChanged) {
                    if (!requestRollbackAndWait(operation.operationId, operation.failureReason ?: WifiReason.UNKNOWN)) return
                } else {
                    finishManualNoChangeUnknown(operation.operationId)
                }
            }

            current = synchronized(stateLock) { state } ?: return
            if (current.manualOperation != null) return
            current.bootOperation?.let { operation ->
                val unknown = WifiBootResult(operation.bootId, operation.revision, "unknown", WifiReason.UNKNOWN)
                if (!persist { old ->
                    old.copy(
                        bootOperation = null,
                        bootResults = keepPendingBootResults(old.bootResults, unknown),
                    )
                }) return
            }

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
        }
    }

    private fun requestRollbackAndWait(operationId: String, reason: WifiReason): Boolean {
        val operation = claimRollback(operationId, reason, allowExistingClaim = true) ?: return false
        if (activeMutations.get() > 0) finishManualUnknown(operationId)
        val restoreReason = adapter.applyProfile(
            target = operation.restoreSnapshot.map { it.profile },
            restoreSnapshot = operation.restoreSnapshot,
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
        if (restoreReason == WifiReason.NONE) {
            finishManualFailure(operationId, reason, rolledBack = true)
            return synchronized(stateLock) { state?.manualOperation?.operationId != operationId }
        }
        finishManualRestoreFailure(operationId)
        return false
    }

    /** The durable phase change arbitrates rollback against a concurrent verification ACK. */
    private fun claimRollback(
        operationId: String,
        reason: WifiReason,
        allowExistingClaim: Boolean = false,
    ): WifiManualOperation? = synchronized(stateLock) {
        val old = state ?: return@synchronized null
        val operation = old.manualOperation?.takeIf { it.operationId == operationId } ?: return@synchronized null
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
        val profile = synchronized(stateLock) { state?.lastApplied }
        if (profile == null) return persist { it.copy(bootGeneration = generation) }
        val bootId = UUID.randomUUID().toString()
        val operation = WifiBootOperation(bootId, generation, profile.revision, profile.networks)
        val claimed = persist { old ->
            if (old.bootClaimedGeneration == generation || old.manualOperation != null || old.bootOperation != null) old
            else old.copy(
                bootGeneration = generation,
                bootClaimedGeneration = generation,
                bootOperation = operation,
            )
        }
        if (!claimed) return false
        val active = synchronized(stateLock) { state?.bootOperation }
        if (active?.bootId != bootId) return false
        val reason = adapter.applyProfile(profile.networks)
        val result = if (reason == WifiReason.NONE) {
            WifiBootResult(bootId, profile.revision, "success", WifiReason.NONE)
        } else {
            WifiBootResult(bootId, profile.revision, "failed", reason)
        }
        return persist { old ->
            old.copy(
                bootOperation = null,
                bootResults = keepPendingBootResults(old.bootResults, result),
            )
        }
    }

    private fun writeAndCommit(next: WifiLocalState): Boolean {
        if (!storageHealthy) return false
        return try {
            store.write(next)
            state = next
            true
        } catch (_: Exception) {
            storageHealthy = false
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
    return WifiControllerDependencies(
        store = WifiSettingsStore(File(appContext.noBackupFilesDir, "wifi-settings-v1.json"), ::syncDirectory),
        adapter = WifiManagerAdapter(appContext),
        nowElapsed = SystemClock::elapsedRealtime,
        bootGeneration = { currentBootGeneration(appContext) },
        scheduleAlarm = { operationId, deadline -> scheduleExactRollback(appContext, operationId, deadline) },
        cancelAlarm = { cancelExactRollback(appContext) },
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
        .putExtra("operation_id", operationId)
        .putExtra("deadline_elapsed_ms", deadlineElapsed)
    val pending = PendingIntent.getBroadcast(
        context,
        8701,
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

private fun cancelExactRollback(context: Context) {
    val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
    val intent = Intent(context, WifiRollbackReceiver::class.java)
        .setAction(WifiSettingsController.ACTION_ROLLBACK)
    val pending = PendingIntent.getBroadcast(
        context,
        8701,
        intent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    ) ?: return
    manager.cancel(pending)
    pending.cancel()
}
