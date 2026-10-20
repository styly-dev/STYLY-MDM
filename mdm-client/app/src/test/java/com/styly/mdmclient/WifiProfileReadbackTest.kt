package com.styly.mdmclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiProfileReadbackTest {
    private val office = WifiNetworkProfile("office", "WPA_PSK", "test-key-123", false)

    @Test
    fun `multiple security views share the selected network and retain disabled unrelated settings`() {
        val views = listOf(entry(5), entry(5, security = "SAE"), entry(7, ssid = "retained", allowed = false))
        assertTrue(wifiProfileReadback(views, listOf(office)))
    }

    @Test
    fun `duplicate saved IDs for one profile are rejected`() {
        assertFalse(wifiProfileReadback(listOf(entry(5), entry(7)), listOf(office)))
    }

    @Test
    fun `all unrelated security views must have auto join disabled`() {
        val views = listOf(entry(5), entry(7, "SAE", "retained", false), entry(7, "WPA_PSK", "retained", true))
        assertFalse(wifiProfileReadback(views, listOf(office)))
    }

    @Test
    fun `missing candidate hidden mismatch or disabled candidate fails readback`() {
        assertFalse(wifiProfileReadback(emptyList(), listOf(office)))
        assertFalse(wifiProfileReadback(listOf(entry(5).copy(hidden = true)), listOf(office)))
        assertFalse(wifiProfileReadback(listOf(entry(5, allowed = false)), listOf(office)))
    }

    private fun entry(id: Int, security: String = "WPA_PSK", ssid: String = "office", allowed: Boolean = true) =
        WifiConfiguredEntry(id, ssid, security, hidden = false, autoJoinEnabled = allowed)
}
