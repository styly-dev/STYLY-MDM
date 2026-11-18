package com.styly.mdmclient

import android.net.wifi.WifiInfo
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiPriorityScanMonitorTest {
    @Test
    fun `rejected active request ignores stale data but accepts fresh passive system scan`() {
        val backend = FakeBackend().apply { accepted = false }
        val scheduler = FakeScheduler(now = 50_000L)
        val source = WifiPriorityScanLoop(backend, scheduler, scheduler::schedule, { scheduler.now })
        val events = mutableListOf<WifiPriorityScanEvent>()
        val profile = network("preferred")
        source.start(listOf(profile), events::add)
        scheduler.runNext()

        assertEquals(WifiPriorityMonitorStatus.DEGRADED, events.last().status)
        assertEquals("scan_request_rejected_or_throttled", events.last().detail)

        backend.emit(scheduler.now, samples = listOf(sample(profile, 10_000L)))
        scheduler.runCallbacks()
        assertEquals("scan_request_rejected_or_throttled", events.last().detail)

        backend.emit(scheduler.now, samples = listOf(sample(profile, scheduler.now)))
        scheduler.runCallbacks()
        assertEquals(WifiPriorityMonitorStatus.MONITORING, events.last().status)
        assertEquals("passive_fresh_scan_results", events.last().detail)
        assertEquals(setOf(profile.identity), events.last().visibleIdentities)
    }

    @Test
    fun `accepted request distinguishes unchanged broadcast and applies interval backoff`() {
        val backend = FakeBackend().apply { accepted = true }
        val scheduler = FakeScheduler(now = 100_000L)
        val source = WifiPriorityScanLoop(backend, scheduler, scheduler::schedule, { scheduler.now })
        val events = mutableListOf<WifiPriorityScanEvent>()
        val profile = network("preferred")
        source.start(listOf(profile), events::add)
        scheduler.runNext()
        backend.emit(scheduler.now, resultsUpdated = false)
        scheduler.runCallbacks()

        assertEquals(WifiPriorityMonitorStatus.DEGRADED, events.last().status)
        assertEquals("broadcast_not_updated", events.last().detail)
        assertEquals(10 * 60_000L, scheduler.pending.single().delayMillis)
    }

    @Test
    fun `stale accepted-request results cannot advertise a visible candidate`() {
        val backend = FakeBackend().apply { accepted = true }
        val scheduler = FakeScheduler(now = 100_000L)
        val source = WifiPriorityScanLoop(backend, scheduler, scheduler::schedule, { scheduler.now })
        val events = mutableListOf<WifiPriorityScanEvent>()
        val profile = network("preferred")
        source.start(listOf(profile), events::add)
        scheduler.runNext()
        backend.emit(scheduler.now, samples = listOf(sample(profile, 90_000L)))
        scheduler.runCallbacks()

        assertEquals(WifiPriorityMonitorStatus.DEGRADED, events.last().status)
        assertEquals("stale_scan_results", events.last().detail)
        assertTrue(events.none { profile.identity in it.visibleIdentities })
    }

    @Test
    fun `scan security types preserve transition networks and exclude unsupported modes`() {
        assertEquals(setOf("WPA_PSK", "SAE"), wifiScanSecurityTypes(setOf(
            WifiInfo.SECURITY_TYPE_PSK,
            WifiInfo.SECURITY_TYPE_SAE,
        )))
        assertEquals(setOf("SAE"), wifiScanSecurityTypes(setOf(WifiInfo.SECURITY_TYPE_SAE)))
        assertEquals(setOf("OPEN"), wifiScanSecurityTypes(setOf(WifiInfo.SECURITY_TYPE_OPEN)))
        assertEquals(emptySet<String>(), wifiScanSecurityTypes(setOf(WifiInfo.SECURITY_TYPE_WEP)))
        assertEquals(emptySet<String>(), wifiScanSecurityTypes(setOf(WifiInfo.SECURITY_TYPE_WAPI_PSK)))
        assertEquals(emptySet<String>(), wifiScanSecurityTypes(setOf(
            WifiInfo.SECURITY_TYPE_PSK,
            WifiInfo.SECURITY_TYPE_OWE,
        )))
    }

    @Test
    fun `profile matching requires SAE for short keys and accepts PSK or SAE for long keys`() {
        val shortKey = WifiNetworkProfile("preferred", "WPA2_WPA3", "secret", false)
        val longKey = WifiNetworkProfile("preferred", "WPA2_WPA3", "correct-horse", false)
        val transition = WifiScanSample("preferred", setOf("WPA_PSK", "SAE"), 1L)
        val saeOnly = WifiScanSample("preferred", setOf("SAE"), 1L)
        val pskOnly = WifiScanSample("preferred", setOf("WPA_PSK"), 1L)
        val open = WifiScanSample("preferred", setOf("OPEN"), 1L)

        assertTrue(wifiScanMatchesProfile(transition, shortKey))
        assertTrue(wifiScanMatchesProfile(saeOnly, shortKey))
        assertTrue(wifiScanMatchesProfile(transition, longKey))
        assertTrue(wifiScanMatchesProfile(pskOnly, longKey))
        assertFalse(wifiScanMatchesProfile(pskOnly, shortKey))
        assertFalse(wifiScanMatchesProfile(open, shortKey))
        assertTrue(wifiScanMatchesProfile(open, WifiNetworkProfile("preferred", "OPEN", "", false)))
        assertFalse(wifiScanMatchesProfile(transition, longKey.copy(ssid = "other")))
    }

    @Test
    fun `jitter spreads the first request and the regular interval`() {
        val backend = FakeBackend().apply { accepted = true }
        val scheduler = FakeScheduler(now = 100_000L)
        val bounds = mutableListOf<Long>()
        val source = WifiPriorityScanLoop(backend, scheduler, scheduler::schedule, { scheduler.now }, jitter = { bound ->
            bounds += bound
            bound
        })
        val profile = network("preferred")
        source.start(listOf(profile)) {}

        assertEquals(WifiPriorityScanLoop.INITIAL_JITTER_MILLIS, scheduler.pending.single().delayMillis)
        scheduler.runNext()
        backend.emit(scheduler.now, samples = listOf(sample(profile, scheduler.now)))
        scheduler.runCallbacks()

        val interval = WifiPriorityScanLoop.REQUEST_INTERVAL_MILLIS
        assertEquals(interval + interval / 10, scheduler.pending.single().delayMillis)
        assertEquals(listOf(WifiPriorityScanLoop.INITIAL_JITTER_MILLIS, interval / 10), bounds)
    }

    @Test
    fun `scan received before the deadline settles the request after worker delay`() {
        val worker = ScheduledThreadPoolExecutor(1)
        val started = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val now = AtomicLong(100_000L)
        val backend = FakeBackend().apply {
            beforeStart = {
                started.countDown()
                check(unblock.await(5, TimeUnit.SECONDS))
            }
        }
        val source = WifiPriorityScanLoop(
            backend, worker,
            schedule = { task, delay ->
                val future = worker.schedule(task, delay, TimeUnit.MILLISECONDS)
                ({ future.cancel(false) })
            },
            nowElapsed = now::get,
        )
        val profile = network("preferred")
        val settled = CompletableFuture<WifiPriorityScanEvent>()
        try {
            worker.execute {
                source.start(listOf(profile)) { event ->
                    if (event.detail == "fresh_scan_results" || event.status == WifiPriorityMonitorStatus.DEGRADED) {
                        settled.complete(event)
                    }
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val deadline = now.get() + WifiPriorityScanLoop.BROADCAST_TIMEOUT_MILLIS
            backend.emit(deadline - 1, samples = listOf(sample(profile, deadline - 1)))
            now.set(deadline + 1)
            unblock.countDown()

            val result = settled.get(5, TimeUnit.SECONDS)
            assertEquals("fresh_scan_results", result.detail)
            assertEquals(setOf(profile.identity), result.visibleIdentities)
            worker.submit { source.stop() }.get(5, TimeUnit.SECONDS)
        } finally {
            unblock.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun `a queued scan from a stopped monitor cannot affect its replacement`() {
        val backend = FakeBackend()
        val scheduler = FakeScheduler(now = 100_000L)
        val source = WifiPriorityScanLoop(backend, scheduler, scheduler::schedule, { scheduler.now })
        val profile = network("preferred")
        source.start(listOf(profile)) {}
        backend.emit(scheduler.now, samples = listOf(sample(profile, scheduler.now)))

        val replacement = mutableListOf<WifiPriorityScanEvent>()
        source.start(listOf(profile), replacement::add)
        scheduler.runCallbacks()

        assertEquals(listOf("waiting_for_scan"), replacement.map { it.detail })
        assertEquals(0, backend.sampleReads)
        assertEquals(1, scheduler.pending.size)
    }

    private fun network(ssid: String) = WifiNetworkProfile(ssid, "WPA2_WPA3", "correct-horse", false)

    private fun sample(profile: WifiNetworkProfile, timestamp: Long) =
        WifiScanSample(profile.ssid, setOf("WPA_PSK"), timestamp)

    private class FakeBackend : WifiPriorityScanBackend {
        var accepted = true
        var beforeStart: () -> Unit = {}
        private var listener: ((WifiScanBroadcast) -> Unit)? = null
        private var samples = emptyList<WifiScanSample>()
        var sampleReads = 0
            private set
        override fun register(listener: (WifiScanBroadcast) -> Unit) {
            this.listener = listener
        }
        override fun unregister() {
            listener = null
        }
        override fun startScan(): Boolean {
            beforeStart()
            return accepted
        }
        override fun readSamples(): List<WifiScanSample> {
            sampleReads++
            return samples
        }
        fun emit(receivedAt: Long, resultsUpdated: Boolean = true, samples: List<WifiScanSample> = emptyList()) {
            this.samples = samples
            listener?.invoke(WifiScanBroadcast(resultsUpdated, receivedAt))
        }
    }

    private class FakeScheduler(var now: Long) : Executor {
        data class Scheduled(val task: Runnable, val delayMillis: Long)
        val pending = mutableListOf<Scheduled>()
        private val callbacks = ArrayDeque<Runnable>()
        override fun execute(task: Runnable) { callbacks.addLast(task) }
        fun schedule(task: Runnable, delayMillis: Long): () -> Unit {
            val scheduled = Scheduled(task, delayMillis)
            pending += scheduled
            return { pending.remove(scheduled) }
        }
        fun runCallbacks() {
            while (callbacks.isNotEmpty()) callbacks.removeFirst().run()
        }
        fun runNext() {
            val next = pending.removeAt(0)
            now += next.delayMillis
            next.task.run()
        }
    }
}
