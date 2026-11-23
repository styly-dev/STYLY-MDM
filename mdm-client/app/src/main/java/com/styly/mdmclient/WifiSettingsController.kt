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
        val previousProfile: WifiSavedProfile? = null,
    ) {
        var phase = AttemptPhase.OBSERVING
        var observationStartSequence: Long? = null
        var observationDeadline = 0L
        var deadlineElapsedMillis: Long? = null
        var candidateDeadlineElapsedMillis: Long? = null
        var position = 0
        var reconnectSequence = 0L
        var applicationFailure: WifiReason? = null
        var policyResultReported = false
        var previousCandidateIndex: Int? = null
        var rewrittenProfileIndexes: Set<Int> = emptySet()
        val disconnectRequestedAssociations = mutableMapOf<Long, Long>()

        val candidate get() = profile.networks[candidates[position]]
    }

    private class PriorityRuntime {
        var disconnectCheck: Any? = null
        var scanResumeCheck: Any? = null
        var reconnectFailures = 0
        var retryAllowedAt = 0L
        var failedReturns = 0
    }

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
    private val priorityScanner = dependencies.priorityScanSource
    private val now = dependencies.nowElapsed
    private var initialized = false
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private var state = runCatching { dependencies.store.read() }.getOrNull()
    private var unavailableReason: String? = "INITIALIZING"
    private var monitor = WifiPriorityMonitorStatus.OFF
    private var managementReleased = false
    private var active: Attempt? = null
    private var pendingApply: PolicyRequest? = null
    private var priority: PriorityRuntime? = null
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
        if (state != null) unavailableReason else WifiReason.STORAGE_ERROR.wireValue,
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

    /** Release management without changing the saved Android networks. */
    fun stopManagingPolicy(onRecorded: (Boolean) -> Unit) {
        worker.execute {
            // The package assignment is authoritative even if the Wi-Fi state file is unavailable.
            managementReleased = true
            resetPriority()
            closeConnection()
            setMonitor(WifiPriorityMonitorStatus.OFF)
            val queued = pendingApply
            pendingApply = null
            queued?.let { reportRequest(it, WifiReason.UNKNOWN, publish = false) }

            val current = reloadState()
            val recorded = if (current == null) false else {
                val released = current.copy(managedWifi = false)
                released == current || save(released)
            }
            try {
                onRecorded(recorded)
            } finally {
                val attempt = active
                if (attempt != null) endAttempt(attempt, failure(attempt.profile.revision, WifiReason.UNKNOWN))
                else runIdleWork()
            }
        }
    }

    private fun runIdleWork() {
        if (!initialized || active != null) {
            publishView()
            return
        }
        val request = pendingApply
        pendingApply = null
        try {
            if (request != null) accept(request)
            reconcileMonitoring()
        } catch (failure: Exception) {
            dependencies.logWarning("Wi-Fi idle work failed (${failure.javaClass.simpleName})")
        } catch (failure: LinkageError) {
            dependencies.logWarning("Wi-Fi idle work failed (${failure.javaClass.simpleName})")
        } finally {
            publishView()
        }
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
        try {
            if (!request.isCurrent()) {
                reportRequest(request, WifiReason.UNKNOWN, publish = false)
                return
            }
            val current = reloadState()
            if (current == null) {
                reportRequest(request, WifiReason.STORAGE_ERROR, publish = false)
                return
            }
            updateReadiness()
            val requested = WifiSettingsProtocol.parseManagedPolicy(request.revision, request.settings)
            if (!dependencies.platformSupported || (requested.autoReturn && priorityScanner == null)) {
                reportRequest(request, WifiReason.UNSUPPORTED)
                return
            }
            if (!request.isCurrent()) {
                reportRequest(request, WifiReason.UNKNOWN, publish = false)
                return
            }
            managementReleased = false
            val applied = current.lastApplied
            if (request.reuseMatchingProfile && current.managedWifi &&
                applied != null && applied.hasSameSettings(requested)
            ) {
                refreshMatchingPolicy(request, current, requested, applied)
                return
            }
            startAttempt(Attempt(
                requested,
                requested.networks.indices.toList(),
                request = request,
                previousProfile = applied,
            ))
        } catch (failure: WifiPolicyFailure) {
            reportRequest(request, failure.reason)
        } catch (failure: WifiAdapterFailure) {
            reportRequest(request, failure.reason)
        } catch (_: IllegalArgumentException) {
            reportRequest(request, WifiReason.UNSUPPORTED)
        } catch (_: LinkageError) {
            reportRequest(request, WifiReason.UNSUPPORTED)
        } catch (_: Exception) {
            reportRequest(request, WifiReason.CONFIG_READ_FAILED)
        }
    }

    /** Freshly verify an unchanged local policy without disturbing the current association. */
    private fun refreshMatchingPolicy(
        request: PolicyRequest,
        current: WifiLocalState,
        requested: WifiSavedProfile,
        applied: WifiSavedProfile,
    ) {
        val application = adapter.applyProfile(
            requested.networks,
            requested.prioritizeNetworks,
            trustedNetworkIds = applied.networkIdsByProfile(),
        ) {
            if (request.isCurrent()) null else WifiReason.UNKNOWN
        }
        if (!request.isCurrent()) {
            reportRequest(request, WifiReason.UNKNOWN, publish = false)
            return
        }

        val applicationFailure = application.failure
            ?: WifiReason.VERIFY_FAILED.takeIf { application.usableIndexes.size != requested.networks.size }
        if (application.usableIndexes.isEmpty()) {
            reportRequest(request, applicationFailure ?: WifiReason.UPDATE_FAILED)
            return
        }
        val savedNetworks = application.usableIndexes.map(requested.networks::get)
        val result = applicationFailure?.let { failure(request.revision, it) }
            ?: WifiManualResult(request.revision, "success", WifiReason.SAVED)
        val refreshed = requested.copy(
            networks = savedNetworks,
            unverifiedOnBoot = retainCurrentBootMarkers(applied),
            networkIds = application.networkIds.mapKeys { (index, _) -> requested.networks[index].identity },
        )
        if (adoptProfile(current.copy(latestResult = result), refreshed)) notifyFinished(request, result)
        else reportRequest(request, WifiReason.STORAGE_ERROR, publish = false)
    }

    private fun startAttempt(attempt: Attempt) {
        active = attempt
        runAttempt(attempt) {
            priorityScanner?.stop()
            priority?.disconnectCheck = null
            publishView()
            // Allow a release already queued on this worker to abandon an untouched selection.
            worker.execute { runAttempt(attempt) { prepareAttempt(attempt) } }
        }
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
            if (applyRequestedProfile(attempt, previousAssociations = emptyList())) {
                complete(attempt, WifiReason.SAVED)
            }
            return
        }
        val session = try {
            observeConnection()
        } catch (failure: WifiAdapterFailure) {
            finishWithoutConnectionObserver(attempt, failure.reason)
            return
        } catch (_: SecurityException) {
            finishWithoutConnectionObserver(attempt, WifiReason.PERMISSION_DENIED)
            return
        } catch (_: LinkageError) {
            finishWithoutConnectionObserver(attempt, WifiReason.UNSUPPORTED)
            return
        } catch (_: Exception) {
            finishWithoutConnectionObserver(attempt, WifiReason.UNSUPPORTED)
            return
        }
        // Events queued for the previous attempt cannot drive this attempt.
        val snapshot = session.snapshot()
        attempt.observationStartSequence = snapshot.sequence
        attempt.observationDeadline = now() + WIFI_PRIOR_CONNECTION_OBSERVATION_MS
        resolvePriorConnection(attempt, expired = false)
        if (active === attempt && attempt.phase == AttemptPhase.OBSERVING) {
            schedule(attempt.observationDeadline - now()) {
                runAttempt(attempt) {
                    if (attempt.phase == AttemptPhase.OBSERVING) {
                        resolvePriorConnection(attempt, expired = true)
                    }
                }
            }
        }
    }

    private fun finishWithoutConnectionObserver(attempt: Attempt, reason: WifiReason) {
        if (attempt.request == null) {
            fail(attempt, reason)
        } else if (applyRequestedProfile(attempt, previousAssociations = null)) {
            endAttempt(attempt, failure(attempt.profile.revision, reason))
        }
    }

    private fun resolvePriorConnection(attempt: Attempt, expired: Boolean) {
        val session = checkNotNull(connection)
        val snapshot = session.snapshot()
        val usable = snapshot.observations.filter { it.hasUsableIp }
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
            if (!applyRequestedProfile(attempt, snapshot.observations)) return
            if (shouldKeepExistingAssociation(attempt, previous)) {
                complete(attempt, WifiReason.SAVED)
                return
            }
            val candidate = attempt.candidate
            val previousAssociation = previous?.takeIf { observationMatches(candidate, it) }
            val deadline = attempt.deadlineElapsedMillis
            // A link already in use can stay connected. A new link must arrive within the deadline.
            val keepUsableFallback = attempt.applicationFailure != null && previousAssociation != null
            if ((candidate.identity == attempt.profile.primaryIdentity || keepUsableFallback) &&
                session.snapshot().observations.any {
                    it.hasUsableIp && observationMatches(candidate, it) &&
                        ((it.networkHandle == previousAssociation?.networkHandle &&
                            it.associationSequence == previousAssociation.associationSequence) ||
                            deadline == null || it.usableIpReceivedElapsedMillis?.let { received -> received < deadline } == true)
                }
            ) {
                candidateConnected(attempt)
                return
            }
        }
        disconnectForCandidate(attempt)
    }

    private fun applyRequestedProfile(
        attempt: Attempt,
        previousAssociations: List<WifiLocalNetworkObservation>?,
    ): Boolean {
        if (attempt.request?.isCurrent?.invoke() == false) {
            endAttempt(attempt, failure(attempt.profile.revision, WifiReason.UNKNOWN))
            return false
        }
        val profile = attempt.profile
        var current = checkNotNull(state)
        val trustedManagedProfile = current.lastApplied.takeIf { current.managedWifi }
        if (!profile.prioritizeNetworks && current.managedWifi && current.lastApplied?.prioritizeNetworks == true) {
            // Disable selection from the previous policy even if the new settings cannot be saved.
            current = current.copy(managedWifi = false)
            if (!save(current)) {
                fail(attempt, WifiReason.STORAGE_ERROR)
                return false
            }
            resetPriority()
        }

        // Even an inactive saved network can reuse authentication from earlier in this boot.
        val previousPrioritized = current.lastApplied?.takeIf { it.prioritizeNetworks }
        val credentialsToVerify = profile.networks.filter { network ->
            profile.prioritizeNetworks && network.password.isNotEmpty() &&
                (previousPrioritized?.networks?.any {
                    it.identity == network.identity && it.password == network.password
                } != true || previousPrioritized.unverifiedBoot(network, dependencies.bootCount) != null)
        }
        // A failed readback or interrupted attempt can leave an updated credential on the headset.
        if (!prepareCredentialWrites(current, profile, credentialsToVerify)) {
            fail(attempt, WifiReason.STORAGE_ERROR)
            return false
        }
        current = checkNotNull(state)

        val application = try {
            val trustedNetworkIds = if (attempt.request?.reuseMatchingProfile == true) {
                trustedManagedProfile?.networkIdsByProfile().orEmpty()
            } else null
            adapter.applyProfile(profile.networks, profile.prioritizeNetworks, trustedNetworkIds) {
                beforeMutation(attempt)
            }
        } catch (failure: WifiAdapterFailure) {
            fail(attempt, failure.reason)
            return false
        }
        if (!requestIsCurrent(attempt)) {
            fail(attempt, WifiReason.UNKNOWN)
            return false
        }
        attempt.candidates = application.usableIndexes
        attempt.rewrittenProfileIndexes = application.rewrittenIndexes
        attempt.applicationFailure = application.failure
            ?: WifiReason.VERIFY_FAILED.takeIf { application.usableIndexes.size != profile.networks.size }
        if (attempt.candidates.isEmpty()) {
            fail(attempt, attempt.applicationFailure ?: WifiReason.UPDATE_FAILED)
            return false
        }
        val savedNetworks = attempt.candidates.map(profile.networks::get)
        // Verification state is stored independently from adapter readback and was persisted
        // before mutation, so it survives credentials omitted from this usable-candidate list.
        val freshIdentities = application.newlyAddedIndexes.mapNotNull { index ->
            val network = profile.networks[index]
            // A new saved ID proves nothing about a link that is still in use.
            val couldMatchPrevious = previousAssociations?.any {
                it.ssid == null || (it.ssid == network.ssid &&
                    (it.security == null || wifiSecurityViewMatchesProfile(network, it.security)))
            } != false
            network.identity.takeUnless { couldMatchPrevious }
        }
        val unverifiedOnBoot = retainCurrentBootMarkers(current.lastApplied) - freshIdentities.toSet()
        val savedProfile = profile.copy(
            networks = savedNetworks,
            unverifiedOnBoot = unverifiedOnBoot,
            networkIds = application.networkIds.mapKeys { (index, _) -> profile.networks[index].identity },
        )
        if (!adoptProfile(current, savedProfile)) {
            fail(attempt, WifiReason.STORAGE_ERROR)
            return false
        }
        // Profile writes and readback complete the policy. Connection selection
        // continues on the same worker and may finish after the policy result.
        val result = attempt.applicationFailure?.let { failure(profile.revision, it) }
            ?: WifiManualResult(profile.revision, "success", WifiReason.SAVED)
        reportPolicyResult(attempt, result)
        return true
    }

    /** Keep a healthy listed link when Deploy only repaired unrelated profile fields or flags. */
    private fun shouldKeepExistingAssociation(
        attempt: Attempt,
        previous: WifiLocalNetworkObservation?,
    ): Boolean {
        val oldProfile = attempt.previousProfile ?: return false
        val request = attempt.request ?: return false
        if (!request.reuseMatchingProfile || previous == null ||
            oldProfile.prioritizeNetworks != attempt.profile.prioritizeNetworks ||
            oldProfile.networks.map { it.identity } != attempt.profile.networks.map { it.identity }
        ) return false
        val index = attempt.profile.networks.indexOfFirst { observationMatches(it, previous) }
        if (index !in attempt.candidates || index in attempt.rewrittenProfileIndexes) return false
        return connection?.snapshot()?.observations?.any { current ->
            current.hasUsableIp && current.networkHandle == previous.networkHandle &&
                current.associationSequence == previous.associationSequence &&
                observationMatches(attempt.profile.networks[index], current)
        } == true
    }

    private fun disconnectForCandidate(attempt: Attempt) {
        val allowed = beforeMutation(attempt)
        if (allowed != null) {
            advance(attempt, allowed)
            return
        }
        attempt.phase = AttemptPhase.DISCONNECTING
        val session = checkNotNull(connection)
        val snapshot = session.snapshot()
        attempt.reconnectSequence = snapshot.sequence
        attempt.disconnectRequestedAssociations.clear()
        attempt.candidateDeadlineElapsedMillis = null
        if (!adapter.isWifiEnabled()) {
            advance(attempt, WifiReason.UNREACHABLE)
        } else if (snapshot.observations.isEmpty()) {
            startCandidateWindow(attempt)
            selectCandidate(attempt)
        } else {
            startCandidateWindow(attempt)
            requestDisconnectForSnapshot(attempt, snapshot)
        }
    }

    private fun requestDisconnectForSnapshot(attempt: Attempt, snapshot: WifiLocalConnectivitySnapshot) {
        if (active !== attempt || attempt.phase != AttemptPhase.DISCONNECTING) return
        if (snapshot.observations.isEmpty()) {
            selectCandidate(attempt, snapshot)
            return
        }
        snapshot.observations.forEach {
            attempt.disconnectRequestedAssociations[it.networkHandle] = it.associationSequence
        }
        if (!requestCandidateDisconnect(attempt)) return

        val refreshed = checkNotNull(connection).snapshot()
        val assessment = assessAssociations(attempt, refreshed)
        if (settleCandidateIfCompleteOrExpired(attempt, refreshed, assessment)) return
        if (assessment.needsDisconnect) {
            enqueueDisconnectReconcile(attempt)
        } else if (!assessment.waitingForDisconnect && !assessment.unresolvedAssociation) {
            performCandidateSelection(attempt, refreshed, assessment)
        }
    }

    private fun requestCandidateDisconnect(attempt: Attempt): Boolean {
        val reason = adapter.disconnectCurrent { beforeCandidateMutation(attempt) }
        if (reason == WifiReason.NONE || (reason == WifiReason.UNREACHABLE && adapter.isWifiEnabled())) return true
        advance(attempt, reason)
        return false
    }

    private fun selectCandidate(
        attempt: Attempt,
        snapshot: WifiLocalConnectivitySnapshot = checkNotNull(connection).snapshot(),
        assessment: AssociationAssessment = assessAssociations(attempt, snapshot),
    ) {
        if (settleCandidateIfCompleteOrExpired(attempt, snapshot, assessment)) return
        performCandidateSelection(attempt, snapshot, assessment)
    }

    private fun performCandidateSelection(
        attempt: Attempt,
        snapshot: WifiLocalConnectivitySnapshot,
        assessment: AssociationAssessment,
    ) {
        if (active !== attempt) return
        if (assessment.waitingForDisconnect || assessment.unresolvedAssociation) return
        if (assessment.needsDisconnect) {
            attempt.phase = AttemptPhase.DISCONNECTING
            requestDisconnectForSnapshot(attempt, snapshot)
            return
        }
        attempt.phase = AttemptPhase.CONNECTING
        val reason = adapter.selectSavedCandidate(attempt.candidate) { beforeCandidateMutation(attempt) }
        if (reason != WifiReason.NONE) {
            advance(attempt, reason)
            return
        }
        reconcileDisconnecting(attempt, checkNotNull(connection).snapshot())
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
        runAttempt(attempt) {
            if (event.sequence <= attempt.reconnectSequence) return@runAttempt
            when (attempt.phase) {
                // Prior identity comes from the current snapshot, so use the worker's time here.
                AttemptPhase.OBSERVING -> resolvePriorConnection(attempt, expired = now() >= attempt.observationDeadline)
                AttemptPhase.DISCONNECTING, AttemptPhase.CONNECTING -> {
                    if (!requestIsCurrent(attempt)) {
                        fail(attempt, WifiReason.UNKNOWN)
                        return@runAttempt
                    }
                    val snapshot = connection?.snapshot() ?: return@runAttempt
                    reconcileDisconnecting(attempt, snapshot)
                }
            }
        }
    }

    private fun reconcileDisconnecting(attempt: Attempt, snapshot: WifiLocalConnectivitySnapshot) {
        if (active !== attempt || attempt.phase !in setOf(AttemptPhase.DISCONNECTING, AttemptPhase.CONNECTING)) return
        if (!requestIsCurrent(attempt)) {
            fail(attempt, WifiReason.UNKNOWN)
            return
        }
        val assessment = assessAssociations(attempt, snapshot)
        if (settleCandidateIfCompleteOrExpired(attempt, snapshot, assessment)) return
        if (assessment.needsDisconnect) {
            val wasConnecting = attempt.phase == AttemptPhase.CONNECTING
            attempt.phase = AttemptPhase.DISCONNECTING
            if (wasConnecting) enqueueDisconnectReconcile(attempt)
            else requestDisconnectForSnapshot(attempt, snapshot)
        } else if (attempt.phase == AttemptPhase.DISCONNECTING &&
            !assessment.waitingForDisconnect && !assessment.unresolvedAssociation
        ) {
            performCandidateSelection(attempt, snapshot, assessment)
        }
    }

    private fun enqueueDisconnectReconcile(attempt: Attempt) {
        worker.execute {
            runAttempt(attempt) {
                if (attempt.phase == AttemptPhase.DISCONNECTING) {
                    reconcileDisconnecting(attempt, checkNotNull(connection).snapshot())
                }
            }
        }
    }

    private data class AssociationAssessment(
        val candidate: WifiLocalNetworkObservation?,
        val waitingForDisconnect: Boolean,
        val unresolvedAssociation: Boolean,
        val needsDisconnect: Boolean,
    )

    private fun assessAssociations(
        attempt: Attempt,
        snapshot: WifiLocalConnectivitySnapshot,
    ): AssociationAssessment {
        var candidate: WifiLocalNetworkObservation? = null
        var waitingForDisconnect = false
        var unresolvedAssociation = false
        var needsDisconnect = false
        for (observation in snapshot.observations) {
            when {
                isAwaitingDisconnect(attempt, observation) -> waitingForDisconnect = true
                isUnresolvedCandidate(attempt, observation) -> unresolvedAssociation = true
                isNewCandidateAssociation(attempt, observation) -> {
                    if (isFreshCandidate(attempt, observation)) candidate = observation
                    else unresolvedAssociation = true
                }
                observation.associationSequence > attempt.reconnectSequence -> needsDisconnect = true
            }
        }
        return AssociationAssessment(candidate, waitingForDisconnect, unresolvedAssociation, needsDisconnect)
    }

    private fun settleCandidateIfCompleteOrExpired(
        attempt: Attempt,
        snapshot: WifiLocalConnectivitySnapshot,
        assessment: AssociationAssessment = assessAssociations(attempt, snapshot),
    ): Boolean {
        if (confirmCandidate(attempt, assessment)) return true
        if (attempt.deadlineElapsedMillis?.let { now() >= it } == true) {
            fail(attempt, attempt.applicationFailure ?: WifiReason.UNREACHABLE)
            return true
        }
        if (attempt.candidateDeadlineElapsedMillis?.let { now() >= it } == true) {
            advance(attempt, WifiReason.UNREACHABLE)
            return true
        }
        return false
    }

    private fun requestIsCurrent(attempt: Attempt): Boolean =
        runCatching { attempt.request?.isCurrent?.invoke() != false }.getOrDefault(false)

    private fun confirmCandidate(attempt: Attempt): Boolean =
        connection?.snapshot()?.let { confirmCandidate(attempt, it) } ?: false

    private fun confirmCandidate(attempt: Attempt, snapshot: WifiLocalConnectivitySnapshot): Boolean {
        if (attempt.phase !in setOf(AttemptPhase.DISCONNECTING, AttemptPhase.CONNECTING) ||
            !requestIsCurrent(attempt)) return false
        return confirmCandidate(attempt, assessAssociations(attempt, snapshot))
    }

    private fun confirmCandidate(attempt: Attempt, assessment: AssociationAssessment): Boolean {
        if (attempt.phase !in setOf(AttemptPhase.DISCONNECTING, AttemptPhase.CONNECTING) ||
            !requestIsCurrent(attempt)) return false
        if (assessment.candidate == null || assessment.waitingForDisconnect ||
            assessment.unresolvedAssociation || assessment.needsDisconnect) return false
        candidateConnected(attempt)
        return true
    }

    private fun isFreshCandidate(attempt: Attempt, observation: WifiLocalNetworkObservation): Boolean {
        val deadline = attempt.candidateDeadlineElapsedMillis ?: return false
        val received = observation.usableIpReceivedElapsedMillis ?: return false
        val overallDeadline = attempt.deadlineElapsedMillis ?: return false
        return received < deadline && received < overallDeadline &&
            observation.hasUsableIp && isNewCandidateAssociation(attempt, observation)
    }

    private fun isNewCandidateAssociation(attempt: Attempt, observation: WifiLocalNetworkObservation): Boolean =
        observation.associationSequence > attempt.reconnectSequence && observationMatches(attempt.candidate, observation)

    private fun isAwaitingDisconnect(attempt: Attempt, observation: WifiLocalNetworkObservation): Boolean =
        observation.associationSequence <= attempt.reconnectSequence ||
            attempt.disconnectRequestedAssociations[observation.networkHandle] == observation.associationSequence

    private fun isUnresolvedCandidate(attempt: Attempt, observation: WifiLocalNetworkObservation): Boolean {
        if (observation.associationSequence <= attempt.reconnectSequence) return false
        return observation.ssid == null ||
            (observation.ssid == attempt.candidate.ssid &&
                (observation.security == null || observationMatches(attempt.candidate, observation) && !observation.hasUsableIp))
    }

    private fun startCandidateWindow(attempt: Attempt) {
        if (attempt.candidateDeadlineElapsedMillis != null) return
        val deadline = attempt.deadlineElapsedMillis ?: return
        val candidateDeadline = minOf(deadline, now() + CANDIDATE_TIMEOUT_MS)
        attempt.candidateDeadlineElapsedMillis = candidateDeadline
        val position = attempt.position
        schedule(candidateDeadline - now()) {
            runAttempt(attempt) {
                if (attempt.position == position && attempt.candidateDeadlineElapsedMillis == candidateDeadline &&
                    attempt.phase in setOf(AttemptPhase.DISCONNECTING, AttemptPhase.CONNECTING)
                ) {
                    if (!requestIsCurrent(attempt)) fail(attempt, WifiReason.UNKNOWN)
                    else if (!confirmCandidate(attempt)) advance(attempt, WifiReason.UNREACHABLE)
                }
            }
        }
    }

    /** Keep the accepted operation bounded even when Wi-Fi takes a long time to disconnect. */
    private fun beforeMutation(attempt: Attempt): WifiReason? {
        if (state == null) return WifiReason.STORAGE_ERROR
        if (attempt.request?.isCurrent?.invoke() == false) return WifiReason.UNKNOWN
        attempt.deadlineElapsedMillis?.let { deadline ->
            return if (now() < deadline) null else WifiReason.UNREACHABLE
        }
        val deadline = now() + WIFI_MANUAL_TIMEOUT_MS
        attempt.deadlineElapsedMillis = deadline
        schedule(deadline - now()) {
            runAttempt(attempt) {
                if (now() >= deadline) {
                    if (!requestIsCurrent(attempt)) fail(attempt, WifiReason.UNKNOWN)
                    else if (!confirmCandidate(attempt)) {
                        fail(attempt, attempt.applicationFailure ?: WifiReason.UNREACHABLE)
                    }
                }
            }
        }
        return null
    }

    private fun beforeCandidateMutation(attempt: Attempt): WifiReason? {
        beforeMutation(attempt)?.let { return it }
        val candidateDeadline = attempt.candidateDeadlineElapsedMillis
        return if (candidateDeadline != null && now() >= candidateDeadline) WifiReason.UNREACHABLE else null
    }

    private fun isUnverified(candidate: WifiNetworkProfile): Boolean =
        state?.lastApplied?.unverifiedBoot(candidate, dependencies.bootCount) != null

    private fun retainCurrentBootMarkers(profile: WifiSavedProfile?): Map<String, UnverifiedWifiCredential> =
        profile?.unverifiedOnBoot.orEmpty().filterValues { marker ->
            marker.bootCount < 0 || dependencies.bootCount < 0 || marker.bootCount == dependencies.bootCount
        }

    private fun prepareCredentialWrites(
        current: WifiLocalState,
        requested: WifiSavedProfile,
        unverifiedNetworks: List<WifiNetworkProfile>,
    ): Boolean {
        val previous = current.lastApplied
        val changedIdentities = previous?.networks.orEmpty().filter { saved ->
            requested.networks.any { it.identity == saved.identity && it.password != saved.password }
        }.map(WifiNetworkProfile::identity)
        val previousIds = previous?.networkIds.orEmpty()
        val retainedIds = previousIds - changedIdentities
        if (unverifiedNetworks.isEmpty() && retainedIds == previousIds) return true

        val markerBase = previous ?: requested.copy(
            networks = emptyList(),
            autoReturn = false,
            primaryIdentity = null,
        )
        val markers = retainCurrentBootMarkers(previous) + unverifiedNetworks.associate { network ->
            network.identity to UnverifiedWifiCredential(dependencies.bootCount, wifiPasswordHash(network.password))
        }
        val nextProfile = markerBase.copy(unverifiedOnBoot = markers, networkIds = retainedIds)
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
        if (active !== attempt) return
        if (attempt.selectionKind == SelectionKind.RESTORE) {
            endAttempt(attempt, result, connectionSucceeded)
            return
        }
        val current = state
        if (current == null) {
            endAttempt(attempt, result.copy(status = "unknown", reason = WifiReason.STORAGE_ERROR))
            return
        }
        val previous = current.latestResult
        if (attempt.priority != null && previous?.revision == result.revision &&
            (previous.reason == WifiReason.VERIFY_AFTER_REBOOT ||
                (previous.status == "success" && result.status != "success"))
        ) {
            // Keep the prior local diagnostic while the credential remains unverified.
            endAttempt(attempt, result, connectionSucceeded)
            return
        }
        val saved = save(current.copy(latestResult = result))
        val reported = if (saved) result else result.copy(status = "unknown", reason = WifiReason.STORAGE_ERROR)
        endAttempt(attempt, reported, connectionSucceeded)
    }

    private fun endAttempt(attempt: Attempt, result: WifiManualResult, connectionSucceeded: Boolean = false) {
        if (active !== attempt) return
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
            if (!connectionSucceeded && previousIndex != null && state != null &&
                priority === runtime && pendingApply == null
            ) {
                // Give restoration its own deadline, including when the return attempt timed out.
                startAttempt(Attempt(attempt.profile, listOf(previousIndex),
                    priority = runtime, selectionKind = SelectionKind.RESTORE))
                return
            }
        }
        try {
            reportPolicyResult(attempt, result)
        } finally {
            runIdleWork()
        }
    }

    private fun reportPolicyResult(attempt: Attempt, result: WifiManualResult) {
        val request = attempt.request ?: return
        if (attempt.policyResultReported) return
        attempt.policyResultReported = true
        notifyFinished(request, result)
    }

    private inline fun runAttempt(attempt: Attempt, action: () -> Unit) {
        if (active !== attempt) return
        try {
            action()
        } catch (failure: LinkageError) {
            fail(attempt, WifiReason.UNSUPPORTED)
        } catch (failure: Exception) {
            dependencies.logWarning("Wi-Fi attempt failed (${failure.javaClass.simpleName})")
            fail(attempt, WifiReason.CONFIG_READ_FAILED)
        }
    }

    private fun reportRequest(request: PolicyRequest, reason: WifiReason, publish: Boolean = true) {
        val result = failure(request.revision, reason)
        val current = state
        val reported = if (publish && current != null && !save(current.copy(latestResult = result))) {
            result.copy(status = "unknown", reason = WifiReason.STORAGE_ERROR)
        } else result
        notifyFinished(request, reported)
    }

    private fun notifyFinished(request: PolicyRequest, result: WifiManualResult) {
        try {
            request.onFinished(result)
        } catch (failure: Exception) {
            dependencies.logWarning("Wi-Fi attempt completion callback failed (${failure.javaClass.simpleName})")
        } catch (failure: LinkageError) {
            dependencies.logWarning("Wi-Fi attempt completion callback failed (${failure.javaClass.simpleName})")
        }
    }

    private fun failure(revision: String, reason: WifiReason): WifiManualResult {
        val interrupted = reason == WifiReason.UNKNOWN || reason == WifiReason.STORAGE_ERROR
        return WifiManualResult(revision, if (interrupted) "unknown" else "failed", reason)
    }

    private fun save(next: WifiLocalState): Boolean {
        if (state == null) return false
        if (state == next) {
            publishView()
            return true
        }
        try {
            dependencies.store.write(next)
            state = next
        } catch (_: Exception) {
            state = null
            dependencies.logWarning("Could not save Wi-Fi policy state")
        }
        publishView()
        return state != null
    }

    /** A new deployment or release can proceed once storage recovers. */
    private fun reloadState(): WifiLocalState? {
        state = try {
            dependencies.store.read()
        } catch (_: Exception) {
            dependencies.logWarning("Could not read Wi-Fi policy state")
            null
        }
        publishView()
        return state
    }

    private fun reconcileMonitoring() {
        if (!initialized || active != null) return
        val profile = activePriorityProfile()
        val connected = if (profile == null) {
            closeConnection()
            null
        } else runCatching { observeConnection().snapshot().observations.filter { it.hasUsableIp } }.getOrNull()
        reconcilePriority(profile, connected)
    }

    private fun managedProfile(): WifiSavedProfile? =
        state?.takeIf { dependencies.platformSupported && it.managedWifi }?.lastApplied

    private fun activePriorityProfile(): WifiSavedProfile? {
        if (active != null || managementReleased) return null
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
        if (!adapter.isWifiEnabled()) {
            priorityScanner?.stop()
            runtime.scanResumeCheck = null
            setMonitor(WifiPriorityMonitorStatus.OFF)
            scheduleDisconnectedSelection(runtime)
            return
        }
        val connected = observations.maxByOrNull { it.sequence }
        if (connected == null) {
            priorityScanner?.stop()
            setMonitor(WifiPriorityMonitorStatus.OFF)
            scheduleDisconnectedSelection(runtime)
            return
        }
        runtime.reconnectFailures = 0
        runtime.disconnectCheck = null
        if (!profile.autoReturn || profile.networks.indexOfFirst { observationMatches(it, connected) } <= 0) {
            priorityScanner?.stop()
            runtime.scanResumeCheck = null
            setMonitor(WifiPriorityMonitorStatus.OFF)
            return
        }
        if (now() < runtime.retryAllowedAt) {
            pauseScannerUntilRetry(runtime)
            return
        }
        runtime.scanResumeCheck = null
        if (priorityScanner == null) {
            setMonitor(WifiPriorityMonitorStatus.UNSUPPORTED)
        } else {
            priorityScanner.ensureStarted(profile.networks) { event -> onScan(runtime, event) }
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
            if (!adapter.isWifiEnabled()) {
                scheduleDisconnectedSelection(runtime)
                return@schedule
            }
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
        if (!adapter.isWifiEnabled()) {
            priorityScanner?.stop()
            setMonitor(WifiPriorityMonitorStatus.OFF)
            scheduleDisconnectedSelection(runtime)
            return
        }
        setMonitor(event.status)
        if (event.status == WifiPriorityMonitorStatus.DEGRADED || event.status == WifiPriorityMonitorStatus.UNSUPPORTED) {
            dependencies.logWarning("Wi-Fi priority monitor: " + event.status.wireValue + " " + event.detail.orEmpty())
        }
        if (now() < runtime.retryAllowedAt) {
            pauseScannerUntilRetry(runtime)
            return
        }
        if (event.status != WifiPriorityMonitorStatus.MONITORING || event.visibleIdentities.isEmpty()) return
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
        priorityScanner?.stop()
        priority = null
    }

    private fun pauseScannerUntilRetry(runtime: PriorityRuntime) {
        priorityScanner?.stop()
        setMonitor(WifiPriorityMonitorStatus.OFF)
        if (runtime.scanResumeCheck != null) return
        val token = Any()
        runtime.scanResumeCheck = token
        schedule(runtime.retryAllowedAt - now()) {
            if (priority !== runtime || runtime.scanResumeCheck !== token) return@schedule
            runtime.scanResumeCheck = null
            reconcileMonitoring()
        }
    }

    private fun setMonitor(status: WifiPriorityMonitorStatus) {
        monitor = status
        publishView()
    }

    private fun priorityConnection(): WifiLocalNetworkObservation? =
        connection?.snapshot()?.observations?.filter { it.hasUsableIp }?.maxByOrNull { it.sequence }

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
