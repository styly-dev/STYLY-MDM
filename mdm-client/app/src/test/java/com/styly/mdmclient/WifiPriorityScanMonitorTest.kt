package com.styly.mdmclient

import android.net.wifi.WifiInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiPriorityScanMonitorTest {
    @Test
    fun `rejected active request ignores stale data but accepts fresh passive system scan`() {
        val backend = FakeBackend().apply { accepted = false }
        val scheduler = FakeScheduler(now = 50_000L)
        val source = WifiPriorityScanLoop(backend, scheduler, { scheduler.now })
        val events = mutableListOf<WifiPriorityScanEvent>()
        val profile = network("preferred")
        source.start(listOf(profile), events::add)
        scheduler.runNext()

        assertEquals(WifiPriorityMonitorStatus.DEGRADED, events.last().status)
        assertEquals("scan_request_rejected_or_throttled", events.last().detail)

        backend.emit(WifiScanBroadcast(true, listOf(sample(profile, 10_000L))))
        assertEquals("scan_request_rejected_or_throttled", events.last().detail)

        backend.emit(WifiScanBroadcast(true, listOf(sample(profile, scheduler.now))))
        assertEquals(WifiPriorityMonitorStatus.MONITORING, events.last().status)
        assertEquals("passive_fresh_scan_results", events.last().detail)
        assertEquals(setOf(profile.identity), events.last().visibleIdentities)
    }

    @Test
    fun `accepted request distinguishes unchanged broadcast and applies interval backoff`() {
        val backend = FakeBackend().apply { accepted = true }
        val scheduler = FakeScheduler(now = 100_000L)
        val source = WifiPriorityScanLoop(backend, scheduler, { scheduler.now })
        val events = mutableListOf<WifiPriorityScanEvent>()
        val profile = network("preferred")
        source.start(listOf(profile), events::add)
        scheduler.runNext()
        backend.emit(WifiScanBroadcast(false, emptyList()))

        assertEquals(WifiPriorityMonitorStatus.DEGRADED, events.last().status)
        assertEquals("broadcast_not_updated", events.last().detail)
        assertEquals(10 * 60_000L, scheduler.pending.single().delayMillis)
    }

    @Test
    fun `stale accepted-request results cannot advertise a visible candidate`() {
        val backend = FakeBackend().apply { accepted = true }
        val scheduler = FakeScheduler(now = 100_000L)
        val source = WifiPriorityScanLoop(backend, scheduler, { scheduler.now })
        val events = mutableListOf<WifiPriorityScanEvent>()
        val profile = network("preferred")
        source.start(listOf(profile), events::add)
        scheduler.runNext()
        backend.emit(WifiScanBroadcast(true, listOf(sample(profile, 90_000L))))

        assertEquals(WifiPriorityMonitorStatus.DEGRADED, events.last().status)
        assertEquals("stale_scan_results", events.last().detail)
        assertTrue(events.none { profile.identity in it.visibleIdentities })
    }

    @Test
    fun `security parsing preserves transition types and excludes unsupported modes`() {
        assertEquals(setOf("WPA_PSK", "SAE"), wifiScanSecurity("[WPA2-PSK][RSN-PSK+SAE][ESS]"))
        assertEquals(setOf("WPA_PSK", "SAE"), wifiScanSecurityTypes(setOf(
            WifiInfo.SECURITY_TYPE_PSK,
            WifiInfo.SECURITY_TYPE_SAE,
        )))
        assertEquals(setOf("SAE"), wifiScanSecurityTypes(setOf(WifiInfo.SECURITY_TYPE_SAE)))
        assertEquals(setOf("OPEN"), wifiScanSecurityTypes(setOf(WifiInfo.SECURITY_TYPE_OPEN)))
        assertEquals(setOf("OPEN"), wifiScanSecurity("[ESS]"))
        assertEquals(emptySet<String>(), wifiScanSecurity("[WAPI-PSK][ESS]"))
        assertEquals(emptySet<String>(), wifiScanSecurity("[OWE][ESS]"))
        assertEquals(emptySet<String>(), wifiScanSecurity("[DPP][ESS]"))
        assertEquals(emptySet<String>(), wifiScanSecurity("[PRIVACY][ESS]"))
        assertEquals(emptySet<String>(), wifiScanSecurity("[WPA3-UNKNOWN][ESS]"))
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

    private fun network(ssid: String) = WifiNetworkProfile(ssid, "WPA2_WPA3", "correct-horse", false)

    private fun sample(profile: WifiNetworkProfile, timestamp: Long) =
        WifiScanSample(profile.ssid, setOf("WPA_PSK"), timestamp)

    private class FakeBackend : WifiPriorityScanBackend {
        var accepted = true
        var listener: ((WifiScanBroadcast) -> Unit)? = null
        var registered = 0
        var unregistered = 0
        override fun register(listener: (WifiScanBroadcast) -> Unit) {
            this.listener = listener
            registered++
        }
        override fun unregister() {
            listener = null
            unregistered++
        }
        override fun startScan(): Boolean = accepted
        fun emit(broadcast: WifiScanBroadcast) { listener?.invoke(broadcast) }
    }

    private class FakeScheduler(var now: Long) : WifiPriorityScanScheduler {
        data class Scheduled(val task: Runnable, val delayMillis: Long)
        val pending = mutableListOf<Scheduled>()
        override fun schedule(task: Runnable, delayMillis: Long) { pending += Scheduled(task, delayMillis) }
        override fun cancel(task: Runnable) { pending.removeAll { it.task === task } }
        fun runNext() {
            val next = pending.removeAt(0)
            now += next.delayMillis
            next.task.run()
        }
    }
}
