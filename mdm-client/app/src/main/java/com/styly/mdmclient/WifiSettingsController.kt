package com.styly.mdmclient

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
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

/** One scheduled worker owns policy, connection selection and monitoring decisions. */
internal class WifiSettingsController internal constructor(
    private val dependencies: WifiControllerDependencies,
    autoStart: Boolean = true,
) {
    private enum class AttemptPhase { OBSERVING, DISCONNECTING, CONNECTING }
    private enum class SelectionKind { RECONNECT, AUTO_RETURN, RESTORE }

    private class PolicyRequest(
        val revision: String,
        val settings: JSONObject,
        val reuseMatchingProfile: Boolean,
        val isCurrent: () -> Boolean,
        val onFinished: (WifiManualResult) -> Unit,
    )

    private class Attempt(
        val profile: WifiSavedProfile,
        var candidates: List<Int>,
        val request: PolicyRequest? = null,
        val priority: PriorityRuntime? = null,
        val selectionKind: SelectionKind? = null,
    ) {
        var phase = AttemptPhase.OBSERVING
        var observationStartSequence: Long? = null
        var observationDeadline = 0L
        var deadlineElapsedMillis: Long? = null
        var candidateDeadlineElapsedMillis: Long? = null
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
        var scanning = false
        var disconnectCheck: Any? = null
        var reconnectFailures = 0
        var retryAllowedAt = 0L
        var failedReturns = 0
    }

    private class SavedConnectionWatch(
        val profile: WifiSavedProfile,
        val onVerified: (WifiReason) -> Unit,
    )

    private data class View(
        val busy: Boolean,
        val canAcceptPolicy: Boolean,
        val unavailableReason: String?,
        val monitor: WifiPriorityMonitorStatus,
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
    private var connection: WifiLocalConnectivitySession? = null
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
    fun canAcceptPolicy(): Boolean = view.canAcceptPolicy

    // Automatic delivery can still apply the other settings and report a Wi-Fi storage failure.
    fun canReapplyPolicy(): Boolean =
        view.let { it.canAcceptPolicy && it.unavailableReason != WifiReason.STORAGE_ERROR.wireValue }

    /** Registration and socket callbacks read only the immutable, secret-free view. */
    fun stateSnapshot(): JSONObject {
        val snapshot = view
        return JSONObject().apply {
            put("busy", snapshot.busy)
            put("available", snapshot.unavailableReason == null)
            put("unavailable_reason", snapshot.unavailableReason ?: JSONObject.NULL)
            put("priority_monitor", snapshot.monitor.wireValue)
        }
    }

    fun refreshReadiness() {
        worker.execute {
            updateReadiness()
            if (active == null && connection != null &&
                !runCatching { connectivity.preflight() == null }.getOrDefault(false)
            ) closeConnection()
            reconcileMonitoring()
        }
    }

    private fun updateReadiness() {
        unavailableReason = if (!dependencies.platformSupported) WifiReason.UNSUPPORTED.wireValue else {
            runCatching { dependencies.readinessFailure()?.wireValue }.getOrElse { WifiReason.UNKNOWN.wireValue }
        }
        publishView()
    }

    private fun currentView() = View(
        !initialized || active != null,
        initialized && active == null && pendingApply == null,
        if (storageHealthy) unavailableReason else WifiReason.STORAGE_ERROR.wireValue,
        monitor,
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
    ) {
        val request = PolicyRequest(revision, settings, reuseMatchingProfile, isCurrent, onFinished)
        worker.execute {
            // The package controller serializes policy applications.
            check(pendingApply == null) { "A Wi-Fi policy is already queued" }
            pendingApply = request
            runIdleWork()
        }
    }

    fun watchSavedConnection(profile: WifiSavedProfile?, onVerified: (WifiReason) -> Unit = {}) {
        worker.execute {
            savedConnectionWatch = profile?.let { SavedConnectionWatch(it, onVerified) }
            reconcileMonitoring()
        }
    }

    /** Release management without changing the saved Android networks. */
    fun stopManagingPolicy(onRecorded: (Boolean) -> Unit) {
        worker.execute {
            val current = state
            if (current == null || !storageHealthy) {
                onRecorded(false)
                return@execute
            }
            val released = current.copy(managedWifi = false)
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
            if (attempt != null) endAttempt(attempt, failure(attempt.profile.revision, WifiReason.UNKNOWN))
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
        reconcileMonitoring()
        publishView()
    }

    private fun observeConnection(): WifiLocalConnectivitySession {
        connection?.let { return it }
        connectivity.preflight()?.let { throw WifiAdapterFailure(it) }
        return connectivity.observe { session, event ->
            worker.execute {
                if (connection !== session) return@execute
                val attempt = active
                if (attempt == null) reconcileMonitoring()
                else if (attempt.observationStartSequence?.let { event.sequence > it } == true) {
                    onConnectivity(attempt, event)
                }
            }
        }.also { connection = it }
    }

    private fun closeConnection() {
        val session = connection ?: return
        connection = null
        runCatching { session.close() }
    }

    private fun matchingProfile(current: WifiLocalState, requested: WifiSavedProfile): Boolean =
        current.lastApplied?.hasSameSettings(requested) == true

    private fun adoptProfile(current: WifiLocalState, requested: WifiSavedProfile): Boolean {
        val reset = !current.managedWifi || !matchingProfile(current, requested)
        val next = current.copy(lastApplied = requested, managedWifi = true)
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
                applied != null && applied.hasSameSettings(requested) &&
                current.latestResult?.status == "success" && current.latestResult.revision == applied.revision
            ) {
                val result = WifiManualResult(request.revision, "success", current.latestResult.reason)
                val retained = requested.copy(unverifiedOnBoot = retainCurrentBootMarkers(applied))
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
            startAttempt(Attempt(requested, requested.networks.indices.toList(), request))
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
            endAttempt(attempt, failure(attempt.profile.revision, WifiReason.UNKNOWN))
            return
        }
        if (attempt.request?.isCurrent?.invoke() == false) {
            endAttempt(attempt, failure(attempt.profile.revision, WifiReason.UNKNOWN))
            return
        }
        if (!attempt.profile.prioritizeNetworks) {
            if (applyRequestedProfile(attempt, previous = null)) complete(attempt, WifiReason.SAVED)
            return
        }
        val session = try {
            observeConnection()
        } catch (failure: WifiAdapterFailure) {
            fail(attempt, failure.reason)
            return
        } catch (_: SecurityException) {
            fail(attempt, WifiReason.PERMISSION_DENIED)
            return
        } catch (_: Exception) {
            fail(attempt, WifiReason.UNSUPPORTED)
            return
        }
        // Events queued for the previous attempt cannot drive this attempt.
        attempt.observationStartSequence = session.lastSequence()
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
        val session = checkNotNull(connection)
        val usable = session.snapshot().filter { it.hasUsableIp }
        // Keep a connection that recovered while waiting to reconnect or restore.
        if ((attempt.selectionKind == SelectionKind.RECONNECT || attempt.selectionKind == SelectionKind.RESTORE) &&
            usable.isNotEmpty()
        ) {
            endAttempt(attempt, failure(attempt.profile.revision, WifiReason.UNKNOWN))
            return
        }
        val previous = usable.maxByOrNull { it.sequence }
        if (!expired && (previous?.ssid == null || previous.security == null)) return

        if (attempt.selectionKind == SelectionKind.AUTO_RETURN && previous != null) {
            val previousIndex = attempt.profile.networks.indexOfFirst { observationMatches(it, previous) }
            attempt.candidates = attempt.candidates.filter { it < previousIndex }
            if (attempt.candidates.isEmpty()) {
                endAttempt(attempt, failure(attempt.profile.revision, WifiReason.UNKNOWN))
                return
            }
            attempt.previousCandidateIndex = previousIndex
        }

        if (attempt.request != null) {
            if (!applyRequestedProfile(attempt, previous)) return
            val candidate = attempt.candidate
            val previousHandle = previous?.takeIf { observationMatches(candidate, it) }?.networkHandle
            val deadline = attempt.deadlineElapsedMillis
            // A link already in use can stay connected. A new link must arrive within the deadline.
            if (candidate.identity == attempt.profile.primaryIdentity &&
                session.snapshot().any {
                    it.hasUsableIp && observationMatches(candidate, it) &&
                        (it.networkHandle == previousHandle || deadline == null || it.receivedElapsedMillis < deadline)
                }
            ) {
                candidateConnected(attempt)
                return
            }
        }
        disconnectForCandidate(attempt)
    }

    private fun applyRequestedProfile(attempt: Attempt, previous: WifiLocalNetworkObservation?): Boolean {
        if (attempt.request?.isCurrent?.invoke() == false) {
            endAttempt(attempt, failure(attempt.profile.revision, WifiReason.UNKNOWN))
            return false
        }
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

        // Persist the uncertainty before any adapter mutation. A later failure can report no
        // usable candidates even after an earlier candidate was already written.
        if (profile.prioritizeNetworks && changedActive != null) {
            if (!persistUnverifiedCredential(current, profile, changedActive)) {
                fail(attempt, WifiReason.STORAGE_ERROR)
                return false
            }
            current = checkNotNull(state)
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
        // Verification state is stored independently from adapter readback and was persisted
        // before mutation, so it survives credentials omitted from this usable-candidate list.
        val unverifiedOnBoot = retainCurrentBootMarkers(current.lastApplied)
        val savedProfile = profile.copy(networks = savedNetworks, unverifiedOnBoot = unverifiedOnBoot)
        if (adoptProfile(current, savedProfile)) return true
        fail(attempt, WifiReason.STORAGE_ERROR)
        return false
    }

    private fun disconnectForCandidate(attempt: Attempt) {
        attempt.phase = AttemptPhase.DISCONNECTING
        attempt.waitingForLoss.clear()
        val handles = checkNotNull(connection).snapshot().map { it.networkHandle }
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
            fail(attempt, attempt.applicationFailure ?: WifiReason.UNREACHABLE)
            return
        }
        attempt.phase = AttemptPhase.CONNECTING
        val session = checkNotNull(connection)
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
        val candidateDeadline = minOf(deadline, now() + CANDIDATE_TIMEOUT_MS)
        attempt.candidateDeadlineElapsedMillis = candidateDeadline
        if (confirmCandidate(attempt)) return
        val position = attempt.position
        schedule(candidateDeadline - now()) {
            if (active === attempt && attempt.phase == AttemptPhase.CONNECTING && attempt.position == position &&
                !confirmCandidate(attempt)
            ) {
                advance(attempt, WifiReason.UNREACHABLE)
            }
        }
    }

    private fun advance(attempt: Attempt, reason: WifiReason) {
        if (reason !in setOf(WifiReason.UPDATE_FAILED, WifiReason.VERIFY_FAILED, WifiReason.UNREACHABLE)) {
            fail(attempt, reason)
        } else if (attempt.deadlineElapsedMillis?.let { now() >= it } == true ||
            attempt.position == attempt.candidates.lastIndex
        ) {
            fail(attempt, attempt.applicationFailure ?: reason)
        } else {
            attempt.position++
            disconnectForCandidate(attempt)
        }
    }

    private fun onConnectivity(attempt: Attempt, event: WifiLocalConnectivityEvent) {
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
                    if (isFreshCandidate(attempt, event.observation)) candidateConnected(attempt)
                is WifiLocalConnectivityEvent.Lost -> attempt.lostHandles.add(event.networkHandle)
            }
        }
    }

    private fun confirmCandidate(attempt: Attempt): Boolean {
        if (attempt.phase != AttemptPhase.CONNECTING ||
            connection?.snapshot()?.any { isFreshCandidate(attempt, it) } != true
        ) return false
        candidateConnected(attempt)
        return true
    }

    private fun isFreshCandidate(attempt: Attempt, observation: WifiLocalNetworkObservation): Boolean {
        val deadline = attempt.candidateDeadlineElapsedMillis ?: return false
        return observation.receivedElapsedMillis < deadline && observation.sequence > attempt.reconnectSequence &&
            observation.networkHandle !in attempt.oldHandles && observation.hasUsableIp &&
            observationMatches(attempt.candidate, observation)
    }

    /** Keep the accepted operation bounded even when Wi-Fi takes a long time to disconnect. */
    private fun beforeMutation(attempt: Attempt): WifiReason? {
        if (!storageHealthy) return WifiReason.STORAGE_ERROR
        if (attempt.request?.isCurrent?.invoke() == false) return WifiReason.UNKNOWN
        attempt.deadlineElapsedMillis?.let { deadline ->
            return if (now() < deadline) null else WifiReason.UNREACHABLE
        }
        val deadline = now() + WIFI_MANUAL_TIMEOUT_MS
        attempt.deadlineElapsedMillis = deadline
        schedule(deadline - now()) {
            if (active === attempt && now() >= deadline && !confirmCandidate(attempt)) {
                fail(attempt, attempt.applicationFailure ?: WifiReason.UNREACHABLE)
            }
        }
        return null
    }

    private fun isUnverified(candidate: WifiNetworkProfile): Boolean =
        state?.lastApplied?.unverifiedBoot(candidate, dependencies.bootCount) != null

    private fun retainCurrentBootMarkers(profile: WifiSavedProfile?): Map<String, UnverifiedWifiCredential> =
        profile?.unverifiedOnBoot.orEmpty().filterValues { marker ->
            marker.bootCount < 0 || dependencies.bootCount < 0 || marker.bootCount == dependencies.bootCount
        }

    private fun persistUnverifiedCredential(
        current: WifiLocalState,
        requested: WifiSavedProfile,
        network: WifiNetworkProfile,
    ): Boolean {
        val previous = current.lastApplied
        val markerBase = previous ?: requested.copy(
            networks = emptyList(),
            autoReturn = false,
            primaryIdentity = null,
        )
        val markers = retainCurrentBootMarkers(previous) + (
            network.identity to UnverifiedWifiCredential(dependencies.bootCount, wifiPasswordHash(network.password))
        )
        val nextProfile = markerBase.copy(unverifiedOnBoot = markers)
        val next = current.copy(
            lastApplied = nextProfile,
            managedWifi = previous != null && current.managedWifi,
        )
        return save(next)
    }

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
        val result = failureReason?.let { failure(attempt.profile.revision, it) }
            ?: WifiManualResult(attempt.profile.revision, "success", reason)
        recordResult(attempt, result, connectionSucceeded)
    }

    private fun fail(attempt: Attempt, reason: WifiReason) {
        recordResult(attempt, failure(attempt.profile.revision, reason))
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
                startAttempt(Attempt(attempt.profile, listOf(previousIndex),
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
        val result = failure(request.revision, reason)
        val reported = if (publish && storageHealthy && !save(checkNotNull(state).copy(latestResult = result))) {
            result.copy(status = "unknown", reason = WifiReason.STORAGE_ERROR)
        } else result
        request.onFinished(reported)
    }

    private fun failure(revision: String, reason: WifiReason): WifiManualResult {
        val interrupted = reason == WifiReason.UNKNOWN || reason == WifiReason.STORAGE_ERROR
        return WifiManualResult(revision, if (interrupted) "unknown" else "failed", reason)
    }

    private fun save(next: WifiLocalState): Boolean {
        if (!storageHealthy) return false
        if (state == next) {
            publishView()
            return true
        }
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

    private fun reconcileMonitoring() {
        if (!initialized || active != null) return
        val applied = managedProfile()
        val watch = savedConnectionWatch?.takeIf {
            applied != null && applied.revision == it.profile.revision && applied.hasSameSettings(it.profile)
        }
        val profile = activePriorityProfile()
        val connected = if (watch == null && profile == null) {
            closeConnection()
            null
        } else runCatching { observeConnection().snapshot().filter { it.hasUsableIp } }.getOrNull()
        if (watch != null && applied != null && connected != null) {
            reconcileSavedConnection(watch, applied, connected)
        }
        if (!storageHealthy) closeConnection()
        reconcilePriority(profile.takeIf { storageHealthy }, connected)
    }

    private fun reconcileSavedConnection(
        watch: SavedConnectionWatch,
        applied: WifiSavedProfile,
        connected: List<WifiLocalNetworkObservation>,
    ) {
        val candidate = applied.networks.firstOrNull { network ->
            applied.unverifiedBoot(network, dependencies.bootCount) == null &&
                connected.any { observationMatches(network, it) }
        } ?: return
        val reason = if (candidate.identity == applied.primaryIdentity) WifiReason.NONE else WifiReason.FALLBACK
        val current = checkNotNull(state)
        val previous = current.latestResult
        if (previous == null || previous.revision != applied.revision || previous.status != "success" || previous.reason != reason) {
            val result = WifiManualResult(applied.revision, "success", reason)
            if (!save(current.copy(latestResult = result))) return
        }
        watch.onVerified(reason)
    }

    private fun managedProfile(): WifiSavedProfile? =
        state?.takeIf { dependencies.platformSupported && storageHealthy && it.managedWifi }?.lastApplied

    private fun activePriorityProfile(): WifiSavedProfile? {
        if (active != null) return null
        return managedProfile()?.takeIf { profile ->
            profile.prioritizeNetworks && profile.networks.isNotEmpty() &&
                (profile.networks.size > 1 || profile.networks[0].identity != profile.primaryIdentity)
        }
    }

    private fun reconcilePriority(profile: WifiSavedProfile?, observations: List<WifiLocalNetworkObservation>?) {
        if (profile == null) {
            resetPriority()
            setMonitor(WifiPriorityMonitorStatus.OFF)
            return
        }
        if (observations == null) {
            resetPriority()
            setMonitor(if (profile.autoReturn) WifiPriorityMonitorStatus.UNSUPPORTED else WifiPriorityMonitorStatus.OFF)
            return
        }
        val runtime = priority ?: PriorityRuntime().also { priority = it }
        val connected = observations.maxByOrNull { it.sequence }
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
        else if (!runtime.scanning) {
            runtime.scanning = true
            scanner.start(profile.networks) { event -> onScan(runtime, event) }
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
            if (priorityConnection() == null) {
                if (!startSelection(runtime, profile, profile.networks.indices.toList(), SelectionKind.RECONNECT)) {
                    runtime.reconnectFailures = (runtime.reconnectFailures + 1).coerceAtMost(DISCONNECT_MAX_RETRY_EXPONENT)
                    scheduleDisconnectedSelection(runtime)
                }
            } else reconcileMonitoring()
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
        val connected = priorityConnection()
        val currentIndex = connected?.let { observation -> profile.networks.indexOfFirst { observationMatches(it, observation) } } ?: -1
        if (currentIndex <= 0) {
            reconcileMonitoring()
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
        startAttempt(Attempt(profile, candidates,
            priority = runtime, selectionKind = selectionKind))
        return true
    }

    private fun resetPriority() {
        val runtime = priority ?: return
        stopScanner(runtime)
        priority = null
    }

    private fun stopScanner(runtime: PriorityRuntime) {
        if (!runtime.scanning) return
        runtime.scanning = false
        dependencies.priorityScanSource?.stop()
    }

    private fun setMonitor(status: WifiPriorityMonitorStatus) {
        monitor = status
        publishView()
    }

    private fun priorityConnection(): WifiLocalNetworkObservation? =
        connection?.snapshot()?.filter { it.hasUsableIp }?.maxByOrNull { it.sequence }

    private fun observationMatches(profile: WifiNetworkProfile, observation: WifiLocalNetworkObservation): Boolean =
        profile.ssid == observation.ssid && wifiSecurityViewMatchesProfile(profile, observation.security)

    private fun schedule(delay: Long, action: () -> Unit) {
        dependencies.schedule(Runnable(action), delay.coerceAtLeast(0))
    }
}

private fun productionDependencies(context: Context): WifiControllerDependencies {
    val worker = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "wifi-settings").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    val adapter = WifiManagerAdapter(context)
    val connectivity = AndroidWifiLocalConnectivitySource(context)
    return WifiControllerDependencies(
        readinessFailure = { adapter.readinessFailure() ?: connectivity.preflight() },
        store = WifiSettingsStore(File(context.noBackupFilesDir, "wifi-policy.json"), ::syncDirectory),
        adapter = adapter,
        nowElapsed = SystemClock::elapsedRealtime,
        connectivity = connectivity,
        priorityScanSource = if (supportsManagedWifi()) systemWifiPriorityScanSource(context, worker) else null,
        logWarning = { Log.w("WifiSettings", it) },
        platformSupported = supportsManagedWifi(),
        bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1),
        worker = worker,
        schedule = { task, delay -> worker.schedule(task, delay, TimeUnit.MILLISECONDS) },
        jitter = { bound -> if (bound <= 0L) 0L else kotlin.random.Random.nextLong(bound + 1) },
    )
}
