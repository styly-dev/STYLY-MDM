package com.styly.mdmclient

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class WifiControllerDependencies(
    val store: WifiLocalStore,
    val adapter: WifiSettingsAdapter,
    val connectivity: WifiLocalConnectivitySource,
    val nowElapsed: () -> Long,
    val worker: Executor,
    val schedule: (Runnable, Long) -> Unit,
    val readinessFailure: () -> WifiReason? = { null },
    val priorityScanSource: WifiPriorityScanSource? = null,
    val logWarning: (String) -> Unit = {},
    /** Returns a delay in 0..bound so headsets that lose Wi-Fi together do not retry together. */
    val jitter: (Long) -> Long = { 0L },
    val platformSupported: Boolean = true,
    val bootCount: Int = -1,
)

internal const val WIFI_PRIOR_CONNECTION_OBSERVATION_MS = 2_000L

/** All policy, connection, and deadline events run on one scheduled worker. */
internal class WifiSettingsController internal constructor(
    private val dependencies: WifiControllerDependencies,
    autoStart: Boolean = true,
) {
    private enum class AttemptPhase { OBSERVING, DISCONNECTING, CONNECTING }
    private enum class SelectionKind { RECONNECT, AUTO_RETURN, RESTORE }

    private class PolicyRequest(
        val operationId: String,
        val revision: String,
        val settings: JSONObject,
        val reuseMatchingProfile: Boolean,
        val isCurrent: () -> Boolean,
        val onFinished: (WifiManualResult) -> Unit,
    )

    private class Attempt(
        val id: String,
        val profile: WifiSavedProfile,
        var candidates: List<Int>,
        val request: PolicyRequest? = null,
        val priority: PriorityRuntime? = null,
        val selectionKind: SelectionKind? = null,
    ) {
        var session: WifiLocalConnectivitySession? = null
        var phase = AttemptPhase.OBSERVING
        var observationDeadline = 0L
        var deadlineElapsedMillis: Long? = null
        var position = 0
        var reconnectSequence = 0L
        var applicationFailure: WifiReason? = null
        var previousCandidateIndex: Int? = null
        val oldHandles = mutableSetOf<Long>()
        val lostHandles = mutableSetOf<Long>()
        val waitingForLoss = mutableSetOf<Long>()

        val candidate get() = profile.networks[candidates[position]]
    }

    private class PriorityRuntime {
        var connection: WifiLocalConnectivitySession? = null
        var scanToken: Any? = null
        var disconnectCheck: Any? = null
        var reconnectFailures = 0
        var retryAllowedAt = 0L
        var failedReturns = 0
    }

    private class SavedConnectionWatch(
        val profile: WifiSavedProfile,
        val onVerified: (WifiReason) -> Unit,
    ) {
        var session: WifiLocalConnectivitySession? = null
    }

    private data class View(
        val managed: Boolean?,
        val revision: String?,
        val busy: Boolean,
        val canProcessPolicy: Boolean,
        val unavailableReason: String?,
        val monitor: WifiPriorityMonitorStatus,
        val result: WifiManualResult?,
    )

    companion object {
        private const val CANDIDATE_TIMEOUT_MS = 12_000L
        private const val DISCONNECT_GRACE_MS = 8_000L
        private const val DISCONNECT_GRACE_JITTER_MS = 3_000L
        private const val DISCONNECT_MAX_RETRY_DELAY_MS = 60_000L
        private const val DISCONNECT_MAX_RETRY_EXPONENT = 3
        private const val AUTO_RETURN_RETRY_DELAY_MS = 5 * 60_000L
        private const val AUTO_RETURN_MAX_RETRY_DELAY_MS = 30 * 60_000L
    }

    constructor(context: Context) : this(productionDependencies(context.applicationContext), autoStart = false)

    private val worker = dependencies.worker
    private val adapter = dependencies.adapter
    private val connectivity = dependencies.connectivity
    private val now = dependencies.nowElapsed
    private var initialized = false
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var state = runCatching { dependencies.store.read() }.getOrNull()
    private var storageHealthy = state != null
    private var unavailableReason: String? = "INITIALIZING"
    private var monitor = WifiPriorityMonitorStatus.OFF
    private var active: Attempt? = null
    private var pendingApply: PolicyRequest? = null
    private var priority: PriorityRuntime? = null
    private var savedConnectionWatch: SavedConnectionWatch? = null
    @Volatile private var view = currentView()

    init {
        if (autoStart) start()
    }

    /** Called after the service can read configured Wi-Fi networks. */
    fun start() {
        worker.execute {
            if (initialized) return@execute
            initialized = true
            updateReadiness()
            runIdleWork()
        }
    }

    fun addStateListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeStateListener(listener: () -> Unit) { listeners.remove(listener) }
    fun readyForPolicy(): Boolean = view.let { it.canProcessPolicy && it.unavailableReason != WifiReason.STORAGE_ERROR.wireValue }

    fun canProcessPolicy(): Boolean = view.canProcessPolicy

    /** Registration and socket callbacks read only the immutable, secret-free view. */
    fun stateSnapshot(): JSONObject {
        val snapshot = view
        return JSONObject().apply {
            put("managed", snapshot.managed ?: JSONObject.NULL)
            put("policy_revision", snapshot.revision ?: JSONObject.NULL)
            put("busy", snapshot.busy)
            put("available", snapshot.unavailableReason == null)
            put("unavailable_reason", snapshot.unavailableReason ?: JSONObject.NULL)
            put("priority_monitor", snapshot.monitor.wireValue)
            put("last_result", snapshot.result?.let {
                JSONObject()
                    .put("operation_id", it.operationId)
                    .put("revision", it.revision)
                    .put("status", it.status)
                    .put("reason", it.reason.wireValue)
            } ?: JSONObject.NULL)
        }
    }

    fun refreshReadiness() {
        worker.execute {
            updateReadiness()
            reconcileSavedConnection()
        }
    }

    private fun updateReadiness() {
        unavailableReason = if (!dependencies.platformSupported) WifiReason.UNSUPPORTED.wireValue else {
            runCatching { dependencies.readinessFailure()?.wireValue }.getOrElse { WifiReason.UNKNOWN.wireValue }
        }
        publishView()
    }

    private fun currentView() = View(
        state?.managedWifi,
        state?.policyRevision,
        !initialized || active != null,
        initialized && active == null && pendingApply == null,
        if (storageHealthy) unavailableReason else WifiReason.STORAGE_ERROR.wireValue,
        monitor,
        state?.latestResult,
    )

    private fun publishView() {
        val next = currentView()
        if (next == view) return
        view = next
        listeners.forEach { listener -> runCatching(listener) }
    }

    fun applyManagedPolicy(
        revision: String,
        settings: JSONObject,
        reuseMatchingProfile: Boolean,
        isCurrent: () -> Boolean,
        onFinished: (WifiManualResult) -> Unit,
        operationId: String = UUID.randomUUID().toString(),
    ) {
        val request = PolicyRequest(operationId, revision, settings, reuseMatchingProfile, isCurrent, onFinished)
        worker.execute {
            if (pendingApply != null) {
                reportRequest(request, WifiReason.BUSY)
                return@execute
            }
            pendingApply = request
            runIdleWork()
        }
    }

    fun watchSavedConnection(profile: WifiSavedProfile?, onVerified: (WifiReason) -> Unit = {}) {
        worker.execute {
            val previous = savedConnectionWatch
            savedConnectionWatch = profile?.let { SavedConnectionWatch(it, onVerified) }
            runCatching { previous?.session?.close() }
            reconcileSavedConnection()
        }
    }

    /** Release management without changing the saved Android networks. */
    fun stopManagingPolicy(revision: String? = null, onRecorded: (Boolean) -> Unit = {}) {
        worker.execute {
            val current = state
            if (current == null || !storageHealthy) {
                onRecorded(false)
                return@execute
            }
            val releasedRevision = revision ?: current.policyRevision
            val result = releasedRevision?.let { WifiManualResult(UUID.randomUUID().toString(), it, "success", WifiReason.NONE) }
            val released = current.copy(managedWifi = false, policyRevision = releasedRevision,
                latestResult = result ?: current.latestResult)
            if (released != current && !save(released)) {
                onRecorded(false)
                return@execute
            }
            resetPriority()
            val queued = pendingApply
            pendingApply = null
            queued?.let { reportRequest(it, WifiReason.UNKNOWN, publish = false) }
            onRecorded(true)
            val attempt = active
            if (attempt != null) endAttempt(attempt, failure(attempt.id, attempt.profile.revision, WifiReason.UNKNOWN))
            else runIdleWork()
        }
    }

    private fun runIdleWork() {
        if (!initialized || active != null) {
            publishView()
            return
        }
        val request = pendingApply
        pendingApply = null
        if (request != null) accept(request)
        if (active == null) {
            reconcileSavedConnection()
            reconcilePriority()
        }
        publishView()
    }

    private fun matchingProfile(current: WifiLocalState, requested: WifiSavedProfile): Boolean =
        current.lastApplied?.hasSameSettings(requested) == true

    private fun adoptProfile(current: WifiLocalState, requested: WifiSavedProfile): Boolean {
        val reset = !current.managedWifi || !matchingProfile(current, requested)
        val next = current.copy(lastApplied = requested, policyRevision = requested.revision, managedWifi = true)
        if (next != state && !save(next)) return false
        if (reset) resetPriority()
        return true
    }

    private fun accept(request: PolicyRequest) {
        if (!request.isCurrent()) {
            reportRequest(request, WifiReason.UNKNOWN, publish = false)
            return
        }
        if (!storageHealthy) {
            reportRequest(request, WifiReason.STORAGE_ERROR, publish = false)
            return
        }
        updateReadiness()
        try {
            val requested = WifiSettingsProtocol.parseManagedPolicy(request.revision, request.settings)
            if (!dependencies.platformSupported || (requested.autoReturn && dependencies.priorityScanSource == null)) {
                reportRequest(request, WifiReason.UNSUPPORTED)
                return
            }
            if (!request.isCurrent()) {
                reportRequest(request, WifiReason.UNKNOWN, publish = false)
                return
            }
            val current = checkNotNull(state)
            val applied = current.lastApplied
            if (request.reuseMatchingProfile && current.managedWifi &&
                applied != null && current.policyRevision == applied.revision && applied.hasSameSettings(requested) &&
                current.latestResult?.status == "success" && current.latestResult.revision == current.policyRevision
            ) {
                val result = WifiManualResult(request.operationId, request.revision, "success", current.latestResult.reason)
                val retained = requested.copy(unverifiedOnBoot = applied.unverifiedOnBoot)
                if (adoptProfile(current.copy(latestResult = result), retained)) request.onFinished(result)
                else reportRequest(request, WifiReason.STORAGE_ERROR, publish = false)
                return
            }
            if (requested.prioritizeNetworks) {
                connectivity.preflight()?.let { reason ->
                    reportRequest(request, reason)
                    return
                }
            }
            startAttempt(Attempt(request.operationId, requested, requested.networks.indices.toList(), request))
        } catch (failure: WifiPolicyFailure) {
            reportRequest(request, failure.reason)
        } catch (failure: WifiAdapterFailure) {
            reportRequest(request, failure.reason)
        } catch (_: IllegalArgumentException) {
            reportRequest(request, WifiReason.UNSUPPORTED)
        } catch (_: Exception) {
            reportRequest(request, WifiReason.CONFIG_READ_FAILED)
        }
    }

    private fun startAttempt(attempt: Attempt) {
        active = attempt
        priority?.let {
            stopScanner(it)
            it.disconnectCheck = null
        }
        publishView()
        // Allow a release already queued on this worker to abandon an untouched selection.
        worker.execute { prepareAttempt(attempt) }
    }

    private fun prepareAttempt(attempt: Attempt) {
        if (active !== attempt) return
        if (attempt.selectionKind == SelectionKind.RESTORE && pendingApply != null) {
            endAttempt(attempt, failure(attempt.id, attempt.profile.revision, WifiReason.UNKNOWN))
            return
        }
        if (attempt.request?.isCurrent?.invoke() == false) {
            endAttempt(attempt, failure(attempt.id, attempt.profile.revision, WifiReason.UNKNOWN))
            return
        }
        if (!attempt.profile.prioritizeNetworks) {
            if (applyRequestedProfile(attempt, previous = null)) complete(attempt, WifiReason.SAVED)
            return
        }
        attempt.session = try {
            connectivity.observe { event ->
                val observedAt = now()
                worker.execute { onConnectivity(attempt, event, observedAt) }
            }
        } catch (_: SecurityException) {
            fail(attempt, WifiReason.PERMISSION_DENIED)
            return
        } catch (_: Exception) {
            fail(attempt, WifiReason.UNSUPPORTED)
            return
        }
        attempt.observationDeadline = now() + WIFI_PRIOR_CONNECTION_OBSERVATION_MS
        resolvePriorConnection(attempt, expired = false)
        if (active === attempt && attempt.phase == AttemptPhase.OBSERVING) {
            schedule(attempt.observationDeadline - now()) {
                if (active === attempt && attempt.phase == AttemptPhase.OBSERVING) {
                    resolvePriorConnection(attempt, expired = true)
                }
            }
        }
    }

    private fun resolvePriorConnection(attempt: Attempt, expired: Boolean) {
        val session = checkNotNull(attempt.session)
        val usable = session.snapshot().filter { it.hasUsableIp }
        // Keep a connection that recovered while waiting to reconnect or restore.
        if ((attempt.selectionKind == SelectionKind.RECONNECT || attempt.selectionKind == SelectionKind.RESTORE) &&
            usable.isNotEmpty()
        ) {
            endAttempt(attempt, failure(attempt.id, attempt.profile.revision, WifiReason.UNKNOWN))
            return
        }
        val previous = usable.maxByOrNull { it.sequence }
        if (!expired && (previous?.ssid == null || previous.security == null)) return

        if (attempt.selectionKind == SelectionKind.AUTO_RETURN && previous != null) {
            val previousIndex = attempt.profile.networks.indexOfFirst { observationMatches(it, previous) }
            attempt.candidates = attempt.candidates.filter { it < previousIndex }
            if (attempt.candidates.isEmpty()) {
                endAttempt(attempt, failure(attempt.id, attempt.profile.revision, WifiReason.UNKNOWN))
                return
            }
            attempt.previousCandidateIndex = previousIndex
        }

        if (attempt.request != null) {
            if (!applyRequestedProfile(attempt, previous)) return
            val candidate = attempt.candidate
            // Use the link observed after saving the profiles.
            if (candidate.identity == attempt.profile.primaryIdentity &&
                session.snapshot().any { it.hasUsableIp && observationMatches(candidate, it) }
            ) {
                candidateConnected(attempt)
                return
            }
        }
        disconnectForCandidate(attempt)
    }

    private fun applyRequestedProfile(attempt: Attempt, previous: WifiLocalNetworkObservation?): Boolean {
        val profile = attempt.profile
        var current = checkNotNull(state)
        if (!profile.prioritizeNetworks && current.managedWifi && current.lastApplied?.prioritizeNetworks == true) {
            // Disable selection from the previous policy even if the new settings cannot be saved.
            current = current.copy(managedWifi = false)
            if (!save(current)) {
                fail(attempt, WifiReason.STORAGE_ERROR)
                return false
            }
            resetPriority()
        }

        val connected = previous?.let { observation ->
            profile.networks.firstOrNull { network ->
                network.password.isNotEmpty() && network.ssid == observation.ssid &&
                    (observation.security == null || wifiSecurityViewMatchesProfile(network, observation.security))
            }
        }
        // A fresh association on the same boot can reuse cached authentication.
        // Profiles saved with Android selection only have configuration readback.
        val previousPrioritized = current.lastApplied?.takeIf { it.prioritizeNetworks }
        val changedActive = connected?.takeUnless {
            previousPrioritized?.networks?.any { saved ->
                saved.identity == it.identity && saved.password == it.password
            } == true
        }

        val application = try {
            adapter.applyProfile(profile.networks, profile.prioritizeNetworks) { beforeMutation(attempt) }
        } catch (failure: WifiAdapterFailure) {
            fail(attempt, failure.reason)
            return false
        }
        attempt.candidates = application.usableIndexes
        attempt.applicationFailure = application.autoJoinFailure ?: application.failure
        if (attempt.candidates.isEmpty()) {
            fail(attempt, attempt.applicationFailure ?: WifiReason.UPDATE_FAILED)
            return false
        }
        val savedNetworks = attempt.candidates.map(profile.networks::get)
        val unverifiedOnBoot = buildMap {
            if (profile.prioritizeNetworks) {
                for (network in savedNetworks) {
                    val markedBoot = if (network.identity == changedActive?.identity) dependencies.bootCount
                    else previousPrioritized?.unverifiedBoot(network, dependencies.bootCount)
                    if (markedBoot != null) put(network.identity, markedBoot)
                }
            }
        }
        val savedProfile = profile.copy(networks = savedNetworks, unverifiedOnBoot = unverifiedOnBoot)
        if (adoptProfile(current, savedProfile)) return true
        fail(attempt, WifiReason.STORAGE_ERROR)
        return false
    }

    private fun disconnectForCandidate(attempt: Attempt) {
        attempt.phase = AttemptPhase.DISCONNECTING
        attempt.waitingForLoss.clear()
        val handles = checkNotNull(attempt.session).snapshot().map { it.networkHandle }
        attempt.oldHandles.addAll(handles)
        attempt.waitingForLoss.addAll(handles.filterNot { it in attempt.lostHandles })
        if (attempt.waitingForLoss.isEmpty()) selectCandidate(attempt)
        else disconnect(attempt)
    }

    private fun disconnect(attempt: Attempt) {
        val reason = adapter.disconnectCurrent { beforeMutation(attempt) }
        if (reason != WifiReason.NONE) advance(attempt, reason)
    }

    private fun selectCandidate(attempt: Attempt) {
        if (attempt.deadlineElapsedMillis?.let { now() >= it } == true) {
            fail(attempt, WifiReason.UNREACHABLE)
            return
        }
        attempt.phase = AttemptPhase.CONNECTING
        val session = checkNotNull(attempt.session)
        val reason = adapter.selectSavedCandidate(attempt.candidate) {
            beforeMutation(attempt).also {
                if (it == null) attempt.reconnectSequence = session.lastSequence()
            }
        }
        if (reason != WifiReason.NONE) {
            advance(attempt, reason)
            return
        }
        val deadline = checkNotNull(attempt.deadlineElapsedMillis)
        val observedAt = now()
        if (observedAt >= deadline) {
            fail(attempt, WifiReason.UNREACHABLE)
            return
        }
        if (session.snapshot().any { isFreshCandidate(attempt, it, observedAt) }) {
            candidateConnected(attempt)
            return
        }
        val position = attempt.position
        schedule(minOf(CANDIDATE_TIMEOUT_MS, deadline - observedAt)) {
            if (active === attempt && attempt.phase == AttemptPhase.CONNECTING && attempt.position == position) {
                advance(attempt, WifiReason.UNREACHABLE)
            }
        }
    }

    private fun advance(attempt: Attempt, reason: WifiReason) {
        if (attempt.deadlineElapsedMillis?.let { now() >= it } == true) {
            fail(attempt, WifiReason.UNREACHABLE)
        } else if (reason !in setOf(WifiReason.UPDATE_FAILED, WifiReason.VERIFY_FAILED, WifiReason.UNREACHABLE)) {
            fail(attempt, reason)
        } else if (attempt.position == attempt.candidates.lastIndex) {
            fail(attempt, attempt.applicationFailure ?: reason)
        } else {
            attempt.position++
            disconnectForCandidate(attempt)
        }
    }

    private fun onConnectivity(attempt: Attempt, event: WifiLocalConnectivityEvent, observedAt: Long) {
        if (active !== attempt) return
        when (attempt.phase) {
            // Prior identity comes from the current snapshot, so use the worker's time here.
            AttemptPhase.OBSERVING -> resolvePriorConnection(attempt, expired = now() >= attempt.observationDeadline)
            AttemptPhase.DISCONNECTING -> when (event) {
                is WifiLocalConnectivityEvent.Updated -> {
                    val handle = event.observation.networkHandle
                    if (attempt.oldHandles.add(handle) && handle !in attempt.lostHandles) {
                        attempt.waitingForLoss.add(handle)
                        disconnect(attempt)
                    }
                }
                is WifiLocalConnectivityEvent.Lost -> {
                    attempt.lostHandles.add(event.networkHandle)
                    attempt.waitingForLoss.remove(event.networkHandle)
                    if (attempt.waitingForLoss.isEmpty()) selectCandidate(attempt)
                }
            }
            AttemptPhase.CONNECTING -> when (event) {
                is WifiLocalConnectivityEvent.Updated ->
                    if (isFreshCandidate(attempt, event.observation, observedAt)) candidateConnected(attempt)
                is WifiLocalConnectivityEvent.Lost -> attempt.lostHandles.add(event.networkHandle)
            }
        }
    }

    private fun isFreshCandidate(attempt: Attempt, observation: WifiLocalNetworkObservation, observedAt: Long): Boolean {
        val deadline = attempt.deadlineElapsedMillis ?: return false
        return observedAt < deadline && observation.sequence > attempt.reconnectSequence &&
            observation.networkHandle !in attempt.oldHandles && observation.hasUsableIp &&
            observationMatches(attempt.candidate, observation)
    }

    /** Keep the accepted operation bounded even when Wi-Fi takes a long time to disconnect. */
    private fun beforeMutation(attempt: Attempt): WifiReason? {
        if (!storageHealthy) return WifiReason.STORAGE_ERROR
        attempt.deadlineElapsedMillis?.let { deadline ->
            return if (now() < deadline) null else WifiReason.UNREACHABLE
        }
        if (attempt.request?.isCurrent?.invoke() == false) return WifiReason.UNKNOWN
        val deadline = now() + WIFI_MANUAL_TIMEOUT_MS
        attempt.deadlineElapsedMillis = deadline
        schedule(deadline - now()) {
            if (active === attempt && now() >= deadline) fail(attempt, WifiReason.UNREACHABLE)
        }
        return null
    }

    private fun isUnverified(candidate: WifiNetworkProfile): Boolean =
        state?.lastApplied?.unverifiedBoot(candidate, dependencies.bootCount) != null

    private fun candidateConnected(attempt: Attempt) {
        val reason = when {
            isUnverified(attempt.candidate) -> WifiReason.VERIFY_AFTER_REBOOT
            attempt.candidate.identity == attempt.profile.primaryIdentity -> WifiReason.NONE
            else -> WifiReason.FALLBACK
        }
        complete(attempt, reason, connectionSucceeded = true)
    }

    private fun complete(attempt: Attempt, reason: WifiReason, connectionSucceeded: Boolean = false) {
        val failureReason = attempt.applicationFailure ?: reason.takeIf { it == WifiReason.VERIFY_AFTER_REBOOT }
        val result = failureReason?.let { failure(attempt.id, attempt.profile.revision, it) }
            ?: WifiManualResult(attempt.id, attempt.profile.revision, "success", reason)
        recordResult(attempt, result, connectionSucceeded)
    }

    private fun fail(attempt: Attempt, reason: WifiReason) {
        recordResult(attempt, failure(attempt.id, attempt.profile.revision, reason))
    }

    private fun recordResult(attempt: Attempt, result: WifiManualResult, connectionSucceeded: Boolean = false) {
        if (attempt.selectionKind == SelectionKind.RESTORE) {
            endAttempt(attempt, result, connectionSucceeded)
            return
        }
        val current = checkNotNull(state)
        val previous = current.latestResult
        if (attempt.priority != null && previous?.revision == result.revision &&
            (previous.reason == WifiReason.VERIFY_AFTER_REBOOT ||
                (previous.status == "success" && result.status != "success"))
        ) {
            // Automatic selection can finish while the policy still awaits credential verification.
            endAttempt(attempt, result, connectionSucceeded)
            return
        }
        val saved = save(current.copy(latestResult = result))
        val reported = if (saved) result else result.copy(status = "unknown", reason = WifiReason.STORAGE_ERROR)
        endAttempt(attempt, reported, connectionSucceeded)
    }

    private fun endAttempt(attempt: Attempt, result: WifiManualResult, connectionSucceeded: Boolean = false) {
        active = null
        runCatching { attempt.session?.close() }
        val runtime = attempt.priority
        if (runtime != null && attempt.selectionKind == SelectionKind.RECONNECT && !connectionSucceeded) {
            runtime.reconnectFailures = (runtime.reconnectFailures + 1).coerceAtMost(DISCONNECT_MAX_RETRY_EXPONENT)
        }
        if (runtime != null && attempt.selectionKind == SelectionKind.AUTO_RETURN && attempt.deadlineElapsedMillis != null) {
            runtime.failedReturns = if (connectionSucceeded) 0 else (runtime.failedReturns + 1).coerceAtMost(16)
            val multiplier = 1L shl (runtime.failedReturns - 1).coerceIn(0, 10)
            runtime.retryAllowedAt = now() + (AUTO_RETURN_RETRY_DELAY_MS * multiplier).coerceAtMost(AUTO_RETURN_MAX_RETRY_DELAY_MS)
            val previousIndex = attempt.previousCandidateIndex
            if (!connectionSucceeded && previousIndex != null && storageHealthy &&
                priority === runtime && pendingApply == null
            ) {
                // Give restoration its own deadline, including when the return attempt timed out.
                startAttempt(Attempt(UUID.randomUUID().toString(), attempt.profile, listOf(previousIndex),
                    priority = runtime, selectionKind = SelectionKind.RESTORE))
                return
            }
        }
        attempt.request?.let {
            // Selection begins after the writes are adopted and the old association is gone.
            val canConfirmLater = result.reason == WifiReason.UNREACHABLE && attempt.phase == AttemptPhase.CONNECTING &&
                attempt.applicationFailure == null && attempt.candidates.size == attempt.profile.networks.size
            it.onFinished(result.copy(canConfirmConnectionLater = canConfirmLater))
        }
        runIdleWork()
    }

    private fun reportRequest(request: PolicyRequest, reason: WifiReason, publish: Boolean = true) {
        val result = failure(request.operationId, request.revision, reason)
        val reported = if (publish && storageHealthy && !save(checkNotNull(state).copy(latestResult = result))) {
            result.copy(status = "unknown", reason = WifiReason.STORAGE_ERROR)
        } else result
        request.onFinished(reported)
    }

    private fun failure(id: String, revision: String, reason: WifiReason): WifiManualResult {
        val interrupted = reason == WifiReason.UNKNOWN || reason == WifiReason.STORAGE_ERROR
        return WifiManualResult(id, revision, if (interrupted) "unknown" else "failed", reason)
    }

    private fun save(next: WifiLocalState): Boolean {
        if (!storageHealthy) return false
        try {
            dependencies.store.write(next)
            state = next
        } catch (_: Exception) {
            storageHealthy = false
            dependencies.logWarning("Could not save Wi-Fi policy state")
        }
        publishView()
        return storageHealthy
    }

    private fun reconcileSavedConnection() {
        val watch = savedConnectionWatch ?: return
        if (!initialized || active != null || !storageHealthy) return
        val current = state?.takeIf { it.managedWifi && it.policyRevision == watch.profile.revision } ?: return
        val applied = current.lastApplied?.takeIf { it.revision == watch.profile.revision && it.hasSameSettings(watch.profile) }
            ?: return
        val session = watch.session ?: try {
            connectivity.observe {
                worker.execute { if (savedConnectionWatch === watch) reconcileSavedConnection() }
            }.also { watch.session = it }
        } catch (_: Exception) {
            return
        }
        val connected = session.snapshot().filter { it.hasUsableIp }
        val candidate = applied.networks.firstOrNull { network ->
            applied.unverifiedBoot(network, dependencies.bootCount) == null &&
                connected.any { observationMatches(network, it) }
        } ?: return
        val reason = if (candidate.identity == applied.primaryIdentity) WifiReason.NONE else WifiReason.FALLBACK
        val previous = current.latestResult
        if (previous == null || previous.revision != applied.revision || previous.status != "success" || previous.reason != reason) {
            val result = WifiManualResult(UUID.randomUUID().toString(), applied.revision, "success", reason)
            if (!save(current.copy(latestResult = result))) return
        }
        watch.onVerified(reason)
    }

    private fun activePriorityProfile(): WifiSavedProfile? {
        val current = state ?: return null
        if (!dependencies.platformSupported || !storageHealthy || !current.managedWifi ||
            active != null
        ) return null
        return current.lastApplied?.takeIf { profile ->
            profile.prioritizeNetworks && profile.revision == current.policyRevision && profile.networks.isNotEmpty() &&
                (profile.networks.size > 1 || profile.networks[0].identity != profile.primaryIdentity)
        }
    }

    private fun reconcilePriority() {
        val profile = activePriorityProfile()
        if (profile == null) {
            resetPriority()
            setMonitor(WifiPriorityMonitorStatus.OFF)
            return
        }
        val runtime = priority ?: PriorityRuntime().also { priority = it }
        if (runtime.connection == null) {
            runtime.connection = try {
                connectivity.observe {
                    worker.execute {
                        if (priority === runtime && active == null) reconcilePriority()
                    }
                }
            } catch (_: Exception) {
                setMonitor(if (profile.autoReturn) WifiPriorityMonitorStatus.UNSUPPORTED else WifiPriorityMonitorStatus.OFF)
                return
            }
        }
        val connected = priorityConnection(runtime)
        if (connected == null) {
            stopScanner(runtime)
            setMonitor(WifiPriorityMonitorStatus.OFF)
            scheduleDisconnectedSelection(runtime)
            return
        }
        runtime.reconnectFailures = 0
        runtime.disconnectCheck = null
        if (!profile.autoReturn || profile.networks.indexOfFirst { observationMatches(it, connected) } <= 0) {
            stopScanner(runtime)
            setMonitor(WifiPriorityMonitorStatus.OFF)
            return
        }
        val scanner = dependencies.priorityScanSource
        if (scanner == null) setMonitor(WifiPriorityMonitorStatus.UNSUPPORTED)
        else if (runtime.scanToken == null) {
            val token = Any()
            runtime.scanToken = token
            scanner.start(profile.networks) { event ->
                worker.execute {
                    if (priority === runtime && runtime.scanToken === token) onScan(runtime, event)
                }
            }
        }
    }

    private fun scheduleDisconnectedSelection(runtime: PriorityRuntime) {
        if (runtime.disconnectCheck != null) return
        val token = Any()
        runtime.disconnectCheck = token
        val delay = (DISCONNECT_GRACE_MS * (1L shl runtime.reconnectFailures)).coerceAtMost(DISCONNECT_MAX_RETRY_DELAY_MS)
        schedule(delay + dependencies.jitter(DISCONNECT_GRACE_JITTER_MS)) {
            if (priority !== runtime || runtime.disconnectCheck !== token) return@schedule
            runtime.disconnectCheck = null
            val profile = activePriorityProfile() ?: return@schedule
            if (priorityConnection(runtime) == null) {
                if (!startSelection(runtime, profile, profile.networks.indices.toList(), SelectionKind.RECONNECT)) {
                    runtime.reconnectFailures = (runtime.reconnectFailures + 1).coerceAtMost(DISCONNECT_MAX_RETRY_EXPONENT)
                    scheduleDisconnectedSelection(runtime)
                }
            } else reconcilePriority()
        }
    }

    private fun onScan(runtime: PriorityRuntime, event: WifiPriorityScanEvent) {
        val profile = activePriorityProfile() ?: return
        setMonitor(event.status)
        if (event.status == WifiPriorityMonitorStatus.DEGRADED || event.status == WifiPriorityMonitorStatus.UNSUPPORTED) {
            dependencies.logWarning("Wi-Fi priority monitor: " + event.status.wireValue + " " + event.detail.orEmpty())
        }
        if (event.status != WifiPriorityMonitorStatus.MONITORING || event.visibleIdentities.isEmpty() ||
            now() < runtime.retryAllowedAt
        ) return
        val connected = priorityConnection(runtime)
        val currentIndex = connected?.let { observation -> profile.networks.indexOfFirst { observationMatches(it, observation) } } ?: -1
        if (currentIndex <= 0) {
            reconcilePriority()
            return
        }
        val candidates = (0 until currentIndex).filter { profile.networks[it].identity in event.visibleIdentities }
        if (candidates.isNotEmpty()) startSelection(runtime, profile, candidates, SelectionKind.AUTO_RETURN)
    }

    private fun startSelection(
        runtime: PriorityRuntime, profile: WifiSavedProfile, candidates: List<Int>, selectionKind: SelectionKind,
    ): Boolean {
        try {
            connectivity.preflight()?.let { throw WifiAdapterFailure(it) }
        } catch (failure: Exception) {
            if (profile.autoReturn) setMonitor(WifiPriorityMonitorStatus.DEGRADED)
            dependencies.logWarning("Could not prepare Wi-Fi priority selection: " +
                ((failure as? WifiAdapterFailure)?.reason ?: WifiReason.CONFIG_READ_FAILED).wireValue)
            return false
        }
        if (selectionKind == SelectionKind.AUTO_RETURN) setMonitor(WifiPriorityMonitorStatus.SWITCHING)
        startAttempt(Attempt(UUID.randomUUID().toString(), profile, candidates,
            priority = runtime, selectionKind = selectionKind))
        return true
    }

    private fun resetPriority() {
        val runtime = priority ?: return
        stopScanner(runtime)
        priority = null
        runCatching { runtime.connection?.close() }
    }

    private fun stopScanner(runtime: PriorityRuntime) {
        if (runtime.scanToken == null) return
        runtime.scanToken = null
        dependencies.priorityScanSource?.stop()
    }

    private fun setMonitor(status: WifiPriorityMonitorStatus) {
        monitor = status
        publishView()
    }

    private fun priorityConnection(runtime: PriorityRuntime): WifiLocalNetworkObservation? =
        runtime.connection?.snapshot()?.filter { it.hasUsableIp }?.maxByOrNull { it.sequence }

    private fun observationMatches(profile: WifiNetworkProfile, observation: WifiLocalNetworkObservation): Boolean =
        profile.ssid == observation.ssid && wifiSecurityViewMatchesProfile(profile, observation.security)

    private fun schedule(delay: Long, action: () -> Unit) {
        dependencies.schedule(Runnable(action), delay.coerceAtLeast(0))
    }
}

private fun productionDependencies(context: Context): WifiControllerDependencies {
    val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "wifi-settings").apply { isDaemon = true }
    }
    val adapter = WifiManagerAdapter(context)
    val connectivity = AndroidWifiLocalConnectivitySource(context)
    return WifiControllerDependencies(
        readinessFailure = { adapter.readinessFailure() ?: connectivity.preflight() },
        store = WifiSettingsStore(File(context.noBackupFilesDir, "wifi-policy.json"), ::syncDirectory),
        adapter = adapter,
        nowElapsed = SystemClock::elapsedRealtime,
        connectivity = connectivity,
        priorityScanSource = if (supportsManagedWifi()) systemWifiPriorityScanSource(context) else null,
        logWarning = { Log.w("WifiSettings", it) },
        platformSupported = supportsManagedWifi(),
        bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1),
        worker = worker,
        schedule = { task, delay -> worker.schedule(task, delay, TimeUnit.MILLISECONDS) },
        jitter = { bound -> if (bound <= 0L) 0L else kotlin.random.Random.nextLong(bound + 1) },
    )
}
