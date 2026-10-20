package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiProfileReadbackTest {
    private val office = WifiNetworkProfile("office", "WPA_PSK", "test-key-123", false)

    @Test
    fun `PSK and SAE views of one network are not counted as two saved networks`() {
        val views = listOf(entry(5), entry(5, security = null))
        assertEquals("validated", wifiProfileReadback(views, listOf(office), setOf(office.identity)))
    }

    @Test
    fun `masked credentials need a successful known credential write`() {
        assertEquals("unknown", wifiProfileReadback(listOf(entry(5)), listOf(office), emptySet()))
        assertEquals("validated", wifiProfileReadback(listOf(entry(5)), listOf(office), setOf(office.identity)))
    }

    @Test
    fun `a second saved ID for the same profile is not hidden by security normalization`() {
        val views = listOf(entry(5), entry(5, security = null), entry(7))
        assertEquals("mismatch", wifiProfileReadback(views, listOf(office), setOf(office.identity)))
    }

    @Test
    fun `an unrelated unsupported network still fails complete profile replacement`() {
        val views = listOf(entry(5), entry(7, security = null, ssid = "unmanaged"))
        assertEquals("mismatch", wifiProfileReadback(views, listOf(office), setOf(office.identity)))
    }

    @Test
    fun `readable wrong credentials and hidden flag mismatch are not accepted`() {
        assertEquals("mismatch", wifiProfileReadback(listOf(entry(5).copy(passwordReadable = true, password = "wrong-key")), listOf(office), setOf(office.identity)))
        assertEquals("mismatch", wifiProfileReadback(listOf(entry(5).copy(hidden = true)), listOf(office), setOf(office.identity)))
    }

    private fun entry(id: Int, security: String? = "WPA_PSK", ssid: String = "office") = WifiConfiguredEntry(
        networkId = id,
        ssid = ssid,
        security = security,
        password = "",
        passwordReadable = false,
        hidden = false,
        enabled = true,
    )
}
