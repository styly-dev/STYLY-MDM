package com.styly.mdmclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
    val receivedElapsedMillis: Long,
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
    fun readSamples(): List<WifiScanSample>
}

internal interface WifiPriorityScanSource {
    /** Called on the Wi-Fi worker. The listener runs there as well. */
    fun start(networks: List<WifiNetworkProfile>, listener: (WifiPriorityScanEvent) -> Unit)
    fun stop()
}

/** Rate-limited scan loop. Only fresh requested or passive results can trigger failback. */
internal class WifiPriorityScanLoop(
    private val backend: WifiPriorityScanBackend,
    private val worker: Executor,
    private val schedule: (Runnable, Long) -> (() -> Unit),
    private val nowElapsed: () -> Long,
    private val requestIntervalMillis: Long = REQUEST_INTERVAL_MILLIS,
    private val broadcastTimeoutMillis: Long = BROADCAST_TIMEOUT_MILLIS,
    private val maxBackoffMillis: Long = MAX_BACKOFF_MILLIS,
    /** Returns a delay in 0..bound. Spreads scans of headsets that start together. */
    private val jitter: (Long) -> Long = { 0L },
) : WifiPriorityScanSource {
    private var active: ScanSession? = null

    override fun start(networks: List<WifiNetworkProfile>, listener: (WifiPriorityScanEvent) -> Unit) {
        stop()
        val session = ScanSession(networks.toList(), listener)
        active = session
        try {
            backend.register { broadcast ->
                worker.execute { if (active === session) session.onBroadcast(broadcast) }
            }
        } catch (_: Exception) {
            stop()
            session.emit(WifiPriorityMonitorStatus.UNSUPPORTED, detail = "receiver_registration_failed")
            return
        }
        session.scheduleRequest(jitter(INITIAL_JITTER_MILLIS))
        session.emit(WifiPriorityMonitorStatus.MONITORING, detail = "waiting_for_scan")
    }

    override fun stop() {
        val session = active ?: return
        active = null
        session.cancelTimer()
        runCatching { backend.unregister() }
    }

    private inner class ScanSession(
        private val targets: List<WifiNetworkProfile>,
        private val listener: (WifiPriorityScanEvent) -> Unit,
    ) {
        private var cancelScheduled: (() -> Unit)? = null
        private var requestedAt: Long? = null
        private var failures = 0
        private var lastProcessedScanTimestampMillis = 0L

        fun cancelTimer() {
            cancelScheduled?.invoke()
            cancelScheduled = null
        }

        private fun scheduleAfter(delayMillis: Long, action: () -> Unit) {
            cancelTimer()
            cancelScheduled = schedule(Runnable {
                if (active === this) {
                    cancelScheduled = null
                    action()
                }
            }, delayMillis.coerceAtLeast(0))
        }

        fun scheduleRequest(delayMillis: Long) = scheduleAfter(delayMillis, ::requestScan)

        private fun requestScan() {
            val startedAt = nowElapsed()
            if (!runCatching { backend.startScan() }.getOrDefault(false)) {
                failRequest("scan_request_rejected_or_throttled")
                return
            }
            requestedAt = startedAt
            scheduleAfter(startedAt + broadcastTimeoutMillis - nowElapsed()) {
                failRequest("scan_broadcast_timeout")
            }
            emit(WifiPriorityMonitorStatus.MONITORING, detail = "scan_requested")
        }

        fun onBroadcast(broadcast: WifiScanBroadcast) {
            val receivedAt = broadcast.receivedElapsedMillis
            val request = requestedAt?.takeIf { receivedAt - it in 0L until broadcastTimeoutMillis }
            if (request != null) {
                requestedAt = null
                cancelTimer()
            }
            if (!broadcast.resultsUpdated) {
                if (request != null) failRequest("broadcast_not_updated")
                return
            }

            val samples = try {
                backend.readSamples()
            } catch (_: Exception) {
                if (request != null) failRequest("scan_results_read_failed")
                return
            }
            val now = nowElapsed()
            val fresh = samples.filter { sample ->
                sample.observedElapsedMillis <= receivedAt + FRESHNESS_TOLERANCE_MILLIS &&
                    now - sample.observedElapsedMillis <= MAX_RESULT_AGE_MILLIS &&
                    (request == null || sample.observedElapsedMillis >= request - FRESHNESS_TOLERANCE_MILLIS)
            }
            val newest = fresh.maxOfOrNull { it.observedElapsedMillis }
            if (newest == null) {
                if (request != null) failRequest("stale_scan_results")
                return
            }
            if (request != null) {
                if (newest > lastProcessedScanTimestampMillis) failures = 0
                scheduleRequest(requestIntervalMillis + jitter(requestIntervalMillis / 10))
            }
            if (newest <= lastProcessedScanTimestampMillis) return
            lastProcessedScanTimestampMillis = newest

            val visible = targets.filter { target ->
                !target.hidden && fresh.any { wifiScanMatchesProfile(it, target) }
            }.mapTo(mutableSetOf()) { it.identity }
            val detail = if (request == null) "passive_fresh_scan_results" else "fresh_scan_results"
            emit(WifiPriorityMonitorStatus.MONITORING, visible, detail)
        }

        private fun failRequest(detail: String) {
            requestedAt = null
            failures = (failures + 1).coerceAtMost(16)
            val multiplier = 1L shl failures.coerceAtMost(10)
            scheduleRequest((requestIntervalMillis * multiplier).coerceAtMost(maxBackoffMillis))
            emit(WifiPriorityMonitorStatus.DEGRADED, detail = detail)
        }

        fun emit(status: WifiPriorityMonitorStatus, visible: Set<String> = emptySet(), detail: String) {
            runCatching { listener(WifiPriorityScanEvent(status, visible, detail)) }
        }
    }

    companion object {
        const val REQUEST_INTERVAL_MILLIS = 5 * 60_000L
        const val BROADCAST_TIMEOUT_MILLIS = 20_000L
        const val MAX_BACKOFF_MILLIS = 30 * 60_000L
        const val INITIAL_JITTER_MILLIS = 30_000L
        private const val FRESHNESS_TOLERANCE_MILLIS = 2_000L
        private const val MAX_RESULT_AGE_MILLIS = 30_000L
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class AndroidWifiPriorityScanBackend(context: Context) : WifiPriorityScanBackend {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var receiver: BroadcastReceiver? = null

    override fun register(listener: (WifiScanBroadcast) -> Unit) {
        checkNotNull(manager) { "Wi-Fi service unavailable" }
        check(receiver == null) { "Wi-Fi scan receiver already registered" }
        val nextReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val receivedAt = SystemClock.elapsedRealtime()
                val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                listener(WifiScanBroadcast(updated, receivedAt))
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        // The Wi-Fi process sends this protected broadcast. Only the signal is read on the main thread.
        appContext.registerReceiver(nextReceiver, filter, Context.RECEIVER_EXPORTED)
        receiver = nextReceiver
    }

    override fun unregister() {
        val current = receiver ?: return
        receiver = null
        appContext.unregisterReceiver(current)
    }

    override fun startScan(): Boolean = manager?.startScan() == true

    @Suppress("DEPRECATION")
    override fun readSamples(): List<WifiScanSample> =
        checkNotNull(manager).scanResults.mapNotNull { it.toWifiScanSample() }

    private fun ScanResult.toWifiScanSample(): WifiScanSample? {
        if (SSID.isEmpty()) return null
        val observedSecurityTypes = wifiScanSecurityTypes(securityTypes.toSet())
        if (observedSecurityTypes.isEmpty()) return null
        return WifiScanSample(SSID, observedSecurityTypes, timestamp / 1_000L)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun systemWifiPriorityScanSource(context: Context, worker: ScheduledExecutorService): WifiPriorityScanSource =
    WifiPriorityScanLoop(
        backend = AndroidWifiPriorityScanBackend(context.applicationContext),
        worker = worker,
        schedule = { task, delay ->
            val future = worker.schedule(task, delay, TimeUnit.MILLISECONDS)
            ({ future.cancel(false) })
        },
        nowElapsed = SystemClock::elapsedRealtime,
        jitter = { bound -> kotlin.random.Random.nextLong(bound + 1) },
    )

/** Both the policy parser and saved profile decoder validate profiles before monitoring. */
internal fun wifiScanMatchesProfile(sample: WifiScanSample, profile: WifiNetworkProfile): Boolean =
    sample.ssid == profile.ssid && sample.securityTypes.any { wifiSecurityViewMatchesProfile(profile, it) }

/** API 33 ScanResult can advertise PSK and SAE together for transition-mode APs. */
internal fun wifiScanSecurityTypes(types: Set<Int>): Set<String> {
    val supported = setOf(
        WifiInfo.SECURITY_TYPE_PSK,
        WifiInfo.SECURITY_TYPE_SAE,
    )
    if (types == setOf(WifiInfo.SECURITY_TYPE_OPEN)) return setOf("OPEN")
    if (types.isEmpty() || types.any { it !in supported }) return emptySet()
    return buildSet {
        if (WifiInfo.SECURITY_TYPE_PSK in types) add("WPA_PSK")
        if (WifiInfo.SECURITY_TYPE_SAE in types) add("SAE")
    }
}
