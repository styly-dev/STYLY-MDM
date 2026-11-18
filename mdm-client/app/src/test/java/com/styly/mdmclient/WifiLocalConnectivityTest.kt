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
}
