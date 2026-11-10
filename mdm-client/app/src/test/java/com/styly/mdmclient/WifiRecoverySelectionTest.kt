package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WifiRecoverySelectionTest {
    private val candidate = WifiNetworkProfile("new", "WPA2_WPA3", "test-key-123", false)
    private fun entry(id: Int, ssid: String, security: String = "WPA_PSK", allowed: Boolean = true) =
        WifiConfiguredEntry(id, ssid, security, false, allowed)
    private fun snapshot(vararg states: WifiAutoJoinState) = WifiRecoverySnapshot(states.toList(), emptyList())

    @Test
    fun `reallocated old ID resolves by identity and a reused old ID is untouched`() {
        val old = snapshot(WifiAutoJoinState(1, "old", listOf("WPA_PSK"), true))
        val now = listOf(entry(1, "other"), entry(7, "old", allowed = false), entry(8, "new"))
        val plan = wifiRecoveryAutoJoinPlan(old, now, listOf(candidate))
        assertEquals(mapOf(7 to true, 8 to false), plan.targets)
        assertEquals(true, plan.fullyResolved)
        assertFalse(1 in plan.targets)
    }

    @Test
    fun `security views sharing one ID restore one original flag`() {
        val old = snapshot(WifiAutoJoinState(1, "old", listOf("WPA_PSK", "SAE"), false))
        val now = listOf(entry(1, "old"), entry(1, "old", "SAE"), entry(8, "new"))
        val plan = wifiRecoveryAutoJoinPlan(old, now, listOf(candidate))
        assertEquals(mapOf(1 to false, 8 to false), plan.targets)
        assertEquals(true, plan.fullyResolved)
    }

    @Test
    fun `candidate added before its returned ID was journaled is disabled after restart`() {
        val old = snapshot(WifiAutoJoinState(1, "old", listOf("WPA_PSK"), true))
        val plan = wifiRecoveryAutoJoinPlan(
            old,
            listOf(entry(1, "old", allowed = false), entry(8, "new")),
            listOf(candidate),
        )
        assertEquals(mapOf(1 to true, 8 to false), plan.targets)
        assertEquals(true, plan.fullyResolved)
    }

    @Test
    fun `missing or ambiguous original remains unresolved without discarding safe targets`() {
        val old = snapshot(WifiAutoJoinState(1, "old", listOf("WPA_PSK"), true))
        val missing = wifiRecoveryAutoJoinPlan(old, listOf(entry(8, "new")), listOf(candidate))
        assertEquals(mapOf(8 to false), missing.targets)
        assertEquals(false, missing.fullyResolved)

        val ambiguous = wifiRecoveryAutoJoinPlan(
            old,
            listOf(entry(7, "old"), entry(9, "old"), entry(8, "new")),
            listOf(candidate),
        )
        assertEquals(mapOf(8 to false), ambiguous.targets)
        assertEquals(false, ambiguous.fullyResolved)
    }

    @Test
    fun `old ID does not break ambiguity between two matching saved networks`() {
        val old = snapshot(WifiAutoJoinState(1, "old", listOf("WPA_PSK"), true))
        val plan = wifiRecoveryAutoJoinPlan(
            old,
            listOf(entry(1, "old"), entry(7, "old")),
            emptyList(),
        )

        assertEquals(emptyMap<Int, Boolean>(), plan.targets)
        assertEquals(false, plan.fullyResolved)
    }
}
