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

        fields.seed(
            ssid = null,
            security = null,
            hasUsableIp = true,
            associationSequence = 8L,
            receivedElapsedMillis = 100L,
        )

        assertEquals("office", fields.ssid)
        assertEquals("SAE", fields.security)
        assertTrue(fields.hasUsableIp)
        assertEquals(8L, fields.associationSequence)
        assertEquals(100L, fields.usableIpReceivedElapsedMillis)
    }

    @Test
    fun `seed cannot restore an address the link callback reported absent`() {
        val fields = WifiLocalObservationState()
        fields.updateLinks(false, 50L)

        fields.seed("office", "SAE", hasUsableIp = true, associationSequence = 7L, receivedElapsedMillis = 100L)

        assertEquals("office", fields.ssid)
        assertFalse(fields.hasUsableIp)
        assertEquals(null, fields.usableIpReceivedElapsedMillis)
    }

    @Test
    fun `usable IP receipt time remains tied to the association until it is lost`() {
        val fields = WifiLocalObservationState()

        fields.updateLinks(true, 80L)
        fields.updateLinks(true, 120L)
        assertEquals(80L, fields.usableIpReceivedElapsedMillis)

        fields.updateLinks(false, 130L)
        fields.updateLinks(true, 140L)
        assertEquals(140L, fields.usableIpReceivedElapsedMillis)
    }
}
