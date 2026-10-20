package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiRecoverySelectionTest {
    private val candidate = WifiNetworkProfile("new", "WPA_PSK", "test-key-123", false)
    private fun entry(id: Int, ssid: String, security: String = "WPA_PSK", allowed: Boolean = true) =
        WifiConfiguredEntry(id, ssid, security, false, allowed)
    private fun snapshot(vararg states: WifiAutoJoinState) = WifiRecoverySnapshot(states.toList(), emptyList())

    @Test
    fun `reallocated old ID is resolved by identity and a reused ID does not change another network`() {
        val old = snapshot(WifiAutoJoinState(1, "old", listOf("WPA_PSK"), true))
        val now = listOf(entry(1, "other"), entry(7, "old", allowed = false), entry(8, "new"))
        assertEquals(mapOf(7 to true, 8 to false), wifiRecoveryAutoJoinTargets(old, now, listOf(candidate)))
    }

    @Test
    fun `security views sharing one ID restore one original flag`() {
        val old = snapshot(WifiAutoJoinState(1, "old", listOf("WPA_PSK", "SAE"), false))
        val now = listOf(entry(1, "old"), entry(1, "old", "SAE"), entry(8, "new"))
        assertEquals(mapOf(1 to false, 8 to false), wifiRecoveryAutoJoinTargets(old, now, listOf(candidate)))
    }

    @Test
    fun `candidate added before its returned ID was journaled is disabled after restart`() {
        val old = snapshot(WifiAutoJoinState(1, "old", listOf("WPA_PSK"), true))
        assertEquals(mapOf(1 to true, 8 to false), wifiRecoveryAutoJoinTargets(old,
            listOf(entry(1, "old", allowed = false), entry(8, "new")), listOf(candidate)))
    }

    @Test
    fun `missing or ambiguous original network cannot be reported as restored`() {
        val old = snapshot(WifiAutoJoinState(1, "old", listOf("WPA_PSK"), true))
        assertNull(wifiRecoveryAutoJoinTargets(old, listOf(entry(8, "new")), listOf(candidate)))
        assertNull(wifiRecoveryAutoJoinTargets(old, listOf(entry(7, "old"), entry(9, "old")), listOf(candidate)))
    }
}
