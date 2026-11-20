package com.styly.mdmclient

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.Executor

internal const val REVISION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
internal const val OLD_REVISION = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

internal fun network(ssid: String, password: String = "password-123") =
    WifiNetworkProfile(ssid, "WPA2_WPA3", password, hidden = false)

internal fun observation(handle: Long, ssid: String) =
    WifiLocalNetworkObservation(handle, ssid, true, 1L, 0L, "WPA_PSK")

internal fun settings(
    networks: List<WifiNetworkProfile>,
    autoReturn: Boolean = false,
    prioritizeNetworks: Boolean = true,
) = JSONObject()
    .put("networks", JSONArray().apply {
        networks.forEach { profile -> put(JSONObject()
            .put("ssid", profile.ssid)
            .put("security", profile.security)
            .put("password", profile.password)
            .put("hidden", profile.hidden))
        }
    })
    .put("auto_return", autoReturn)
    .apply { if (!prioritizeNetworks) put("prioritize_networks", false) }

internal class Harness(
    initialState: WifiLocalState = WifiLocalState(),
    initialNetworks: List<WifiNetworkProfile> = emptyList(),
    initialWifi: List<WifiLocalNetworkObservation> = emptyList(),
    preflightFailure: WifiReason? = null,
    sharedAdapter: FakeAdapter? = null,
    bootCount: Int = 133,
    failStoreRead: Boolean = false,
) {
    val store = MemoryStore(initialState, failStoreRead)
    val adapter = sharedAdapter ?: FakeAdapter(initialNetworks)
    val clock = MutableClock()
    val worker = QueueExecutor()
    private val timers = mutableListOf<ScheduledTask>()
    val connectivity = FakeConnectivity(initialWifi) { clock.value }.apply { preflightResult = preflightFailure }
    val priorityScanSource = FakePriorityScanSource(worker)
    val controller = WifiSettingsController(
        WifiControllerDependencies(
            store = store,
            adapter = adapter,
            connectivity = connectivity,
            nowElapsed = { clock.value },
            worker = worker,
            schedule = { task, delay -> timers += ScheduledTask(task, delay, clock.value + delay, worker) },
            readinessFailure = { connectivity.preflightResult },
            priorityScanSource = priorityScanSource,
            bootCount = bootCount,
        ),
    )

    init { worker.runAll() }

    fun scheduledDelayCount(delayMillis: Long): Int = timers.count { it.delayMillis == delayMillis }

    fun apply(
        networks: List<WifiNetworkProfile>,
        autoReturn: Boolean = false,
        reuseMatchingProfile: Boolean = false,
        prioritizeNetworks: Boolean = true,
    ) {
        controller.applyManagedPolicy(
            REVISION,
            settings(networks, autoReturn, prioritizeNetworks),
            reuseMatchingProfile = reuseMatchingProfile,
            isCurrent = { true },
            onFinished = {},
        )
        worker.runAll()
    }

    fun expireNext(delayMillis: Long) {
        val matching = timers.filter { !it.fired && it.delayMillis == delayMillis }
        val timer = matching.filter { it.dueAt >= clock.value }.minByOrNull { it.dueAt }
            ?: matching.maxByOrNull { it.dueAt }
            ?: error("no pending timer with delay $delayMillis")
        clock.value = maxOf(clock.value, timer.dueAt)
        timer.run()
        worker.runAll()
    }

    fun advanceBy(millis: Long) {
        val end = clock.value + millis
        while (true) {
            val timer = timers.filter { !it.fired && it.dueAt <= end }.minByOrNull { it.dueAt } ?: break
            clock.value = maxOf(clock.value, timer.dueAt)
            timer.run()
            worker.runAll()
        }
        clock.value = maxOf(clock.value, end)
    }

    fun replayTimer(delayMillis: Long, index: Int) {
        val timer = timers.filter { it.delayMillis == delayMillis }.getOrNull(index)
            ?: error("no timer $index with delay $delayMillis")
        clock.value = maxOf(clock.value, timer.dueAt)
        timer.run()
        worker.runAll()
    }
}

internal class FakeAdapter(initialNetworks: List<WifiNetworkProfile>) : WifiSettingsAdapter {
    private data class Saved(var profile: WifiNetworkProfile, var autoJoin: Boolean = true)

    private val saved = initialNetworks.map(::Saved).toMutableList()
    val currentProfiles: List<WifiNetworkProfile> get() = saved.map { it.profile }
    val currentAutoJoin: List<Boolean> get() = saved.map { it.autoJoin }
    val applyCalls = mutableListOf<List<WifiNetworkProfile>>()
    val trustedNetworkCalls = mutableListOf<List<WifiNetworkProfile>>()
    val rewrittenNetworkCalls = mutableListOf<Set<Int>>()
    val recreationFallbacks = mutableListOf<WifiNetworkProfile?>()
    val selectCalls = mutableListOf<WifiNetworkProfile>()
    var disconnectCalls = 0
        private set
    var application: WifiProfileApplication? = null
    var recreatesOwnedPrimary = false
    var wifiEnabled = true
    var disconnectResult = WifiReason.NONE
    var disconnectFailure: Throwable? = null
    var selectionFailure: Throwable? = null
    var afterApply: (() -> Unit)? = null
    var afterDisconnect: (() -> Unit)? = null
    var onDisconnect: (() -> Unit)? = null
    var afterSelect: ((WifiNetworkProfile) -> Unit)? = null
    var onCheckWifiEnabled: (() -> Unit)? = null

    fun setAutoJoin(profile: WifiNetworkProfile, enabled: Boolean) {
        val entry = saved.firstOrNull { it.profile.identity == profile.identity }
            ?: error("No saved network ${profile.ssid}")
        entry.autoJoin = enabled
    }

    override fun isWifiEnabled(): Boolean {
        onCheckWifiEnabled?.invoke()
        return wifiEnabled
    }

    override fun applyProfile(
        target: List<WifiNetworkProfile>,
        prioritizeNetworks: Boolean,
        primaryRecreation: WifiPrimaryRecreation?,
        trustedSavedNetworks: List<WifiNetworkProfile>,
        beforeMutation: () -> WifiReason?,
    ): WifiProfileApplication {
        applyCalls += target.toList()
        trustedNetworkCalls += trustedSavedNetworks.toList()
        recreationFallbacks += primaryRecreation?.fallback
        val reason = beforeMutation()
        if (reason != null) {
            rewrittenNetworkCalls.add(emptySet())
            return WifiProfileApplication(emptyList(), reason)
        }

        val result = application ?: WifiProfileApplication(target.indices.toList())
        if (result.usableIndexes.isEmpty()) {
            rewrittenNetworkCalls += result.rewrittenIndexes
            return result
        }
        val newlyAdded = result.newlyAddedIndexes.toMutableSet()
        val rewritten = result.rewrittenIndexes.toMutableSet()
        for ((index, profile) in target.withIndex()) {
            if (index !in result.usableIndexes) continue
            val existing = saved.firstOrNull { it.profile.identity == profile.identity }
            if (existing == null) {
                saved += Saved(profile)
                newlyAdded += index
                rewritten += index
            } else if (profile !in trustedSavedNetworks ||
                existing.profile.ssid != profile.ssid || existing.profile.security != profile.security ||
                existing.profile.hidden != profile.hidden
            ) {
                existing.profile = profile
                rewritten += index
                if (index == 0 && recreatesOwnedPrimary && primaryRecreation?.isFallbackStillConnected?.invoke() == true) {
                    newlyAdded += index
                }
            }
        }
        val autoJoinTargets = if (prioritizeNetworks) target.take(1) else target
        for (entry in saved) {
            entry.autoJoin = autoJoinTargets.any { it.identity == entry.profile.identity }
        }
        afterApply?.invoke()
        rewrittenNetworkCalls += rewritten.toSet()
        return result.copy(newlyAddedIndexes = newlyAdded, rewrittenIndexes = rewritten)
    }

    override fun selectSavedCandidate(
        candidate: WifiNetworkProfile,
        beforeMutation: () -> WifiReason?,
    ): WifiReason {
        selectCalls += candidate
        beforeMutation()?.let { return it }
        selectionFailure?.let { failure ->
            selectionFailure = null
            throw failure
        }
        afterSelect?.invoke(candidate)
        return WifiReason.NONE
    }

    override fun disconnectCurrent(beforeMutation: () -> WifiReason?): WifiReason {
        disconnectCalls++
        beforeMutation()?.let { return it }
        disconnectFailure?.let { failure ->
            disconnectFailure = null
            throw failure
        }
        onDisconnect?.invoke()
        afterDisconnect?.invoke()
        return disconnectResult
    }
}

internal class FakeConnectivity(
    initial: List<WifiLocalNetworkObservation>,
    private val now: () -> Long,
) : WifiLocalConnectivitySource {
    var preflightResult: WifiReason? = null
    var preflightCalls = 0
    var observeCalls = 0
    private var sequence = initial.maxOfOrNull { it.sequence } ?: 0L
    private val current = initial.associateBy { it.networkHandle }.toMutableMap()
    private val sessions = mutableListOf<FakeSession>()
    val openSessionCount get() = sessions.count { !it.closed }

    override fun preflight(): WifiReason? {
        preflightCalls++
        return preflightResult
    }

    override fun observe(
        listener: (WifiLocalConnectivitySession, WifiLocalConnectivityEvent) -> Unit,
    ): WifiLocalConnectivitySession {
        observeCalls++
        return FakeSession(current.values.toList(), sequence, listener).also { sessions += it }
    }

    fun update(handle: Long, ssid: String?, hasUsableIp: Boolean = true, security: String? = "WPA_PSK") {
        val previous = current[handle]
        val nextSequence = ++sequence
        val observation = WifiLocalNetworkObservation(
            handle,
            ssid,
            hasUsableIp,
            nextSequence,
            now(),
            security,
            associationSequence = previous?.associationSequence ?: nextSequence,
            usableIpReceivedElapsedMillis = when {
                !hasUsableIp -> null
                previous?.hasUsableIp == true -> previous.usableIpReceivedElapsedMillis
                else -> now()
            },
        )
        current[handle] = observation
        sessions.toList().forEach { it.update(current.values.toList(), sequence) }
    }

    fun lose(handle: Long) {
        current.remove(handle)
        val event = WifiLocalConnectivityEvent(++sequence)
        sessions.toList().forEach { it.update(current.values.toList(), event.sequence) }
    }

    fun loseAll() { current.keys.toList().forEach(::lose) }

    fun currentUsableSsid(): String? = current.values.lastOrNull { it.hasUsableIp }?.ssid

    private class FakeSession(
        initial: List<WifiLocalNetworkObservation>,
        private var sequence: Long,
        private val listener: (WifiLocalConnectivitySession, WifiLocalConnectivityEvent) -> Unit,
    ) : WifiLocalConnectivitySession {
        private val observations = initial.associateBy { it.networkHandle }.toMutableMap()
        var closed = false
            private set

        override fun snapshot(): WifiLocalConnectivitySnapshot =
            WifiLocalConnectivitySnapshot(observations.values.toList(), sequence)

        fun update(current: List<WifiLocalNetworkObservation>, currentSequence: Long) {
            if (closed) return
            observations.clear()
            observations.putAll(current.associateBy { it.networkHandle })
            sequence = currentSequence
            listener(this, WifiLocalConnectivityEvent(currentSequence))
        }
        override fun close() { closed = true }
    }
}

internal class FakePriorityScanSource(private val worker: Executor) : WifiPriorityScanSource {
    private var listener: ((WifiPriorityScanEvent) -> Unit)? = null
    var startCalls = 0
    var stopCalls = 0

    override fun ensureStarted(networks: List<WifiNetworkProfile>, listener: (WifiPriorityScanEvent) -> Unit) {
        if (this.listener != null) return
        startCalls++
        this.listener = listener
        listener(WifiPriorityScanEvent(WifiPriorityMonitorStatus.MONITORING, detail = "test_started"))
    }

    override fun stop() {
        if (listener == null) return
        stopCalls++
        listener = null
    }

    fun emit(event: WifiPriorityScanEvent) {
        val callback = listener ?: return
        worker.execute { if (listener === callback) callback(event) }
    }
}

internal class MemoryStore(initial: WifiLocalState, var failRead: Boolean) : WifiLocalStore {
    var state = initial
        private set
    var writeFailuresRemaining = 0

    override fun read(): WifiLocalState {
        check(!failRead) { "Wi-Fi state could not be read" }
        return state
    }
    override fun write(state: WifiLocalState) {
        if (writeFailuresRemaining > 0) {
            writeFailuresRemaining--
            error("Wi-Fi state could not be written")
        }
        this.state = state
    }
}

internal class MutableClock(var value: Long = 0L)

internal class ScheduledTask(
    action: Runnable,
    val delayMillis: Long,
    val dueAt: Long,
    private val worker: QueueExecutor,
) {
    var fired = false
        private set
    private val action = action
    fun run() {
        fired = true
        worker.execute(action)
    }
}

internal class QueueExecutor : Executor {
    private val pending = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) { pending.addLast(command) }
    fun runNext() { pending.removeFirst().run() }
    fun runAll() {
        var count = 0
        while (pending.isNotEmpty()) {
            check(++count <= 100) { "executor did not quiesce" }
            runNext()
        }
    }
}
