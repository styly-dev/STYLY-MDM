package com.styly.mdmclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

internal enum class WifiPriorityMonitorStatus(val wireValue: String) {
    OFF("off"),
    MONITORING("monitoring"),
    DEGRADED("degraded"),
    SWITCHING("switching"),
    UNSUPPORTED("unsupported"),
}

internal data class WifiScanSample(
    val ssid: String,
    val securityTypes: Set<String>,
    val observedElapsedMillis: Long,
)

internal data class WifiScanBroadcast(
    val resultsUpdated: Boolean,
    val samples: List<WifiScanSample>,
    val detail: String? = null,
)

internal data class WifiPriorityScanEvent(
    val status: WifiPriorityMonitorStatus,
    val visibleIdentities: Set<String> = emptySet(),
    val detail: String? = null,
)

internal interface WifiPriorityScanBackend {
    fun register(listener: (WifiScanBroadcast) -> Unit)
    fun unregister()
    fun startScan(): Boolean
}

internal interface WifiPriorityScanScheduler {
    fun schedule(task: Runnable, delayMillis: Long)
    fun cancel(task: Runnable)
}

internal interface WifiPriorityScanSource {
    fun start(networks: List<WifiNetworkProfile>, listener: (WifiPriorityScanEvent) -> Unit)
    fun stop()
}

/** Rate-limited scan loop. Only fresh requested or passive results can trigger failback. */
internal class WifiPriorityScanLoop(
    private val backend: WifiPriorityScanBackend,
    private val scheduler: WifiPriorityScanScheduler,
    private val nowElapsed: () -> Long,
    private val requestIntervalMillis: Long = REQUEST_INTERVAL_MILLIS,
    private val broadcastTimeoutMillis: Long = BROADCAST_TIMEOUT_MILLIS,
    private val maxBackoffMillis: Long = MAX_BACKOFF_MILLIS,
) : WifiPriorityScanSource {
    private val lock = Any()
    private var generation = 0L
    private var active = false
    private var targets: List<WifiNetworkProfile> = emptyList()
    private var listener: ((WifiPriorityScanEvent) -> Unit)? = null
    private var requestedAtElapsedMillis: Long? = null
    private var failures = 0
    private var nextRequest: Runnable? = null
    private var broadcastTimeout: Runnable? = null
    private var lastProcessedScanTimestampMillis = 0L

    override fun start(networks: List<WifiNetworkProfile>, listener: (WifiPriorityScanEvent) -> Unit) {
        stop()
        val token = synchronized(lock) {
            generation++
            active = true
            targets = networks.toList()
            this.listener = listener
            failures = 0
            lastProcessedScanTimestampMillis = 0L
            generation
        }
        try {
            backend.register { broadcast -> onBroadcast(token, broadcast) }
        } catch (failure: Exception) {
            synchronized(lock) { if (generation == token) active = false }
            runCatching { backend.unregister() }
            emit(token, WifiPriorityScanEvent(WifiPriorityMonitorStatus.UNSUPPORTED, detail = "receiver_registration_failed"))
            return
        }
        emit(token, WifiPriorityScanEvent(WifiPriorityMonitorStatus.MONITORING, detail = "waiting_for_scan"))
        scheduleRequest(token, 0L)
    }

    override fun stop() {
        val tasks = synchronized(lock) {
            if (!active && nextRequest == null && broadcastTimeout == null) return
            active = false
            generation++
            requestedAtElapsedMillis = null
            listener = null
            val pending = listOfNotNull(nextRequest, broadcastTimeout)
            nextRequest = null
            broadcastTimeout = null
            pending
        }
        tasks.forEach(scheduler::cancel)
        runCatching { backend.unregister() }
    }

    private fun scheduleRequest(token: Long, delayMillis: Long) {
        val task = Runnable { requestScan(token) }
        val old = synchronized(lock) {
            if (!active || generation != token) return
            nextRequest.also { nextRequest = task }
        }
        old?.let(scheduler::cancel)
        scheduler.schedule(task, delayMillis)
    }

    private fun requestScan(token: Long) {
        val shouldRequest = synchronized(lock) {
            if (!active || generation != token) return
            nextRequest = null
            requestedAtElapsedMillis = nowElapsed()
            true
        }
        if (!shouldRequest) return
        val accepted = try {
            backend.startScan()
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
        if (!accepted) {
            synchronized(lock) {
                if (active && generation == token) requestedAtElapsedMillis = null
            }
            val delay = failedRequest(token)
            emit(token, WifiPriorityScanEvent(
                WifiPriorityMonitorStatus.DEGRADED,
                detail = "scan_request_rejected_or_throttled",
            ))
            scheduleRequest(token, delay)
            return
        }
        val timeout = Runnable { onBroadcastTimeout(token) }
        val old = synchronized(lock) {
            if (!active || generation != token) return
            broadcastTimeout = timeout
            null
        }
        old?.let(scheduler::cancel)
        emit(token, WifiPriorityScanEvent(WifiPriorityMonitorStatus.MONITORING, detail = "scan_requested"))
        scheduler.schedule(timeout, broadcastTimeoutMillis)
    }

    private fun onBroadcast(token: Long, broadcast: WifiScanBroadcast) {
        val pending = synchronized(lock) {
            if (!active || generation != token) return
            requestedAtElapsedMillis
        }
        if (pending == null) {
            if (!broadcast.resultsUpdated) return
            val now = nowElapsed()
            val fresh = broadcast.samples.filter { sample ->
                sample.observedElapsedMillis <= now + FRESHNESS_TOLERANCE_MILLIS &&
                    now - sample.observedElapsedMillis <= MAX_RESULT_AGE_MILLIS
            }
            if (fresh.isEmpty() || !claimFreshTimestamp(token, fresh)) return
            val visible = visibleIdentities(token, fresh) ?: return
            emit(token, WifiPriorityScanEvent(
                WifiPriorityMonitorStatus.MONITORING,
                visible,
                "passive_fresh_scan_results",
            ))
            return
        }
        val requestAt = pending
        val timeout = synchronized(lock) {
            if (!active || generation != token || requestedAtElapsedMillis != requestAt) return
            requestedAtElapsedMillis = null
            broadcastTimeout.also { broadcastTimeout = null }
        }
        timeout?.let(scheduler::cancel)

        if (!broadcast.resultsUpdated) {
            val delay = failedRequest(token)
            emit(token, WifiPriorityScanEvent(
                WifiPriorityMonitorStatus.DEGRADED,
                detail = broadcast.detail ?: "broadcast_not_updated",
            ))
            scheduleRequest(token, delay)
            return
        }
        val now = nowElapsed()
        val fresh = broadcast.samples.filter { sample ->
            sample.observedElapsedMillis >= requestAt - FRESHNESS_TOLERANCE_MILLIS &&
                sample.observedElapsedMillis <= now + FRESHNESS_TOLERANCE_MILLIS &&
                now - sample.observedElapsedMillis <= MAX_RESULT_AGE_MILLIS
        }
        if (fresh.isEmpty()) {
            val delay = failedRequest(token)
            emit(token, WifiPriorityScanEvent(WifiPriorityMonitorStatus.DEGRADED, detail = "stale_scan_results"))
            scheduleRequest(token, delay)
            return
        }
        if (!claimFreshTimestamp(token, fresh)) {
            scheduleRequest(token, requestIntervalMillis)
            return
        }
        synchronized(lock) { if (active && generation == token) failures = 0 }
        val visible = visibleIdentities(token, fresh) ?: return
        emit(token, WifiPriorityScanEvent(WifiPriorityMonitorStatus.MONITORING, visible, "fresh_scan_results"))
        scheduleRequest(token, requestIntervalMillis)
    }

    private fun claimFreshTimestamp(token: Long, fresh: List<WifiScanSample>): Boolean {
        val newest = fresh.maxOfOrNull { it.observedElapsedMillis } ?: return false
        return synchronized(lock) {
            if (!active || generation != token || newest <= lastProcessedScanTimestampMillis) return@synchronized false
            lastProcessedScanTimestampMillis = newest
            true
        }
    }

    private fun visibleIdentities(token: Long, fresh: List<WifiScanSample>): Set<String>? {
        val availableTargets = synchronized(lock) {
            if (!active || generation != token) return null
            targets
        }
        return fresh.mapNotNull { sample ->
            availableTargets.filter { !it.hidden && wifiScanMatchesProfile(sample, it) }.map { it.identity }
        }.flatten().toSet()
    }

    private fun failedRequest(token: Long): Long = synchronized(lock) {
        if (!active || generation != token) return@synchronized requestIntervalMillis
        failures = (failures + 1).coerceAtMost(16)
        val multiplier = 1L shl failures.coerceAtMost(10)
        (requestIntervalMillis * multiplier).coerceAtMost(maxBackoffMillis)
    }

    private fun onBroadcastTimeout(token: Long) {
        val pending = synchronized(lock) {
            if (!active || generation != token || requestedAtElapsedMillis == null) return
            requestedAtElapsedMillis = null
            broadcastTimeout = null
            true
        }
        if (!pending) return
        val delay = failedRequest(token)
        emit(token, WifiPriorityScanEvent(WifiPriorityMonitorStatus.DEGRADED, detail = "scan_broadcast_timeout"))
        scheduleRequest(token, delay)
    }

    private fun emit(token: Long, event: WifiPriorityScanEvent) {
        val callback = synchronized(lock) {
            if (generation != token) return
            listener
        } ?: return
        runCatching { callback(event) }
    }

    companion object {
        const val REQUEST_INTERVAL_MILLIS = 5 * 60_000L
        const val BROADCAST_TIMEOUT_MILLIS = 20_000L
        const val MAX_BACKOFF_MILLIS = 30 * 60_000L
        private const val FRESHNESS_TOLERANCE_MILLIS = 2_000L
        private const val MAX_RESULT_AGE_MILLIS = 30_000L
    }
}

internal class AndroidWifiPriorityScanBackend(context: Context) : WifiPriorityScanBackend {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var receiver: BroadcastReceiver? = null

    override fun register(listener: (WifiScanBroadcast) -> Unit) {
        val wifi = manager ?: throw IllegalStateException("Wi-Fi service unavailable")
        check(receiver == null) { "Wi-Fi scan receiver already registered" }
        val nextReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                val samples = if (!updated) emptyList() else try {
                    @Suppress("DEPRECATION")
                    wifi.scanResults.mapNotNull { it.toWifiScanSample() }
                } catch (failure: Exception) {
                    listener(WifiScanBroadcast(false, emptyList(), "scan_results_read_failed"))
                    return
                }
                listener(WifiScanBroadcast(updated, samples))
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            // PUI delivers scan results through the Wi-Fi system process. The probe APK
            // received this protected system broadcast as an exported dynamic receiver.
            appContext.registerReceiver(nextReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(nextReceiver, filter)
        }
        receiver = nextReceiver
    }

    override fun unregister() {
        val current = receiver ?: return
        receiver = null
        appContext.unregisterReceiver(current)
    }

    override fun startScan(): Boolean = manager?.startScan() == true

    private fun ScanResult.toWifiScanSample(): WifiScanSample? {
        if (SSID.isEmpty()) return null
        val observedSecurityTypes = if (Build.VERSION.SDK_INT >= 33) {
            wifiScanSecurityTypes(securityTypes.toSet())
        } else {
            wifiScanSecurity(capabilities)
        }
        if (observedSecurityTypes.isEmpty()) return null
        return WifiScanSample(SSID, observedSecurityTypes, timestamp / 1_000L)
    }
}

internal class AndroidWifiPriorityScanScheduler : WifiPriorityScanScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun schedule(task: Runnable, delayMillis: Long) { handler.postDelayed(task, delayMillis) }
    override fun cancel(task: Runnable) { handler.removeCallbacks(task) }
}

internal fun systemWifiPriorityScanSource(context: Context): WifiPriorityScanSource = WifiPriorityScanLoop(
    backend = AndroidWifiPriorityScanBackend(context.applicationContext),
    scheduler = AndroidWifiPriorityScanScheduler(),
    nowElapsed = SystemClock::elapsedRealtime,
)

/** Match only security modes the Wi-Fi policy can configure. */
internal fun wifiScanMatchesProfile(sample: WifiScanSample, profile: WifiNetworkProfile): Boolean {
    if (sample.ssid != profile.ssid) return false
    return when (profile.security) {
        "OPEN" -> "OPEN" in sample.securityTypes
        "WPA2_WPA3" -> when (profile.password.length) {
            in 1..7 -> "SAE" in sample.securityTypes
            in 8..63 -> "WPA_PSK" in sample.securityTypes || "SAE" in sample.securityTypes
            else -> false
        }
        else -> false
    }
}

/** Parse the supported subset without treating an unknown protected mode as open. */
internal fun wifiScanSecurity(capabilities: String): Set<String> {
    val tokens = Regex("\\[[^]]*]").findAll(capabilities.uppercase()).map { it.value }.toSet()
    if (tokens.isEmpty()) return emptySet()

    val unsupportedMarkers = listOf(
        "WAPI", "OWE", "DPP", "OSEN", "SUITE_B", "EAP", "WEP", "FILS", "PASSPOINT",
    )
    if (unsupportedMarkers.any { marker -> tokens.any { marker in it } }) return emptySet()

    val supported = buildSet {
        if (tokens.any { "PSK" in it }) add("WPA_PSK")
        if (tokens.any { "SAE" in it }) add("SAE")
    }
    if (supported.isNotEmpty()) return supported

    // Do not classify an unfamiliar privacy/WPA/RSN advertisement as an open network.
    if (tokens.any { "PRIVACY" in it || "WPA" in it || "RSN" in it || "SAE" in it || "PSK" in it }) {
        return emptySet()
    }
    return if (tokens.all { it == "[ESS]" || it == "[IBSS]" }) setOf("OPEN") else emptySet()
}

/** API 33 ScanResult can advertise PSK and SAE together for transition-mode APs. */
internal fun wifiScanSecurityTypes(types: Set<Int>): Set<String> {
    val supported = setOf(
        android.net.wifi.WifiInfo.SECURITY_TYPE_PSK,
        android.net.wifi.WifiInfo.SECURITY_TYPE_SAE,
    )
    if (types == setOf(android.net.wifi.WifiInfo.SECURITY_TYPE_OPEN)) return setOf("OPEN")
    if (types.isEmpty() || types.any { it !in supported }) return emptySet()
    return buildSet {
        if (android.net.wifi.WifiInfo.SECURITY_TYPE_PSK in types) add("WPA_PSK")
        if (android.net.wifi.WifiInfo.SECURITY_TYPE_SAE in types) add("SAE")
    }
}
