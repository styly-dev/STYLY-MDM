package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiLocalConnectivityTest {
    @Test
    fun `redacted seed preserves callback identity and fills links if not received`() {
        val fields = WifiLocalObservationState()
        fields.updateCapabilities("office", "SAE")

        fields.seed(ssid = null, security = null, hasUsableIp = true)

        assertEquals("office", fields.ssid)
        assertEquals("SAE", fields.security)
        assertTrue(fields.hasUsableIp)
    }

    @Test
    fun `seed cannot restore an address the link callback reported absent`() {
        val fields = WifiLocalObservationState()
        fields.updateLinks(false)

        fields.seed("office", "SAE", hasUsableIp = true)

        assertEquals("office", fields.ssid)
        assertFalse(fields.hasUsableIp)
    }

    @Test
    fun `lost handle blocks stale seed and delayed update until a fresh available event`() {
        val networkHandle = 42L
        val tombstones = WifiNetworkHandleTombstones()

        assertFalse(tombstones.isLost(networkHandle))
        tombstones.markLost(networkHandle) // onLost wins while initial capabilities are being read
        assertTrue(tombstones.isLost(networkHandle)) // both seed and update paths check this state

        tombstones.markAvailable(networkHandle) // a later onAvailable marks a new live observation
        assertFalse(tombstones.isLost(networkHandle))
    }
}
