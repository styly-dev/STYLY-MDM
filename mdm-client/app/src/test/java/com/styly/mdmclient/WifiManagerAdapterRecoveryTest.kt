package com.styly.mdmclient

import android.net.wifi.WifiConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiManagerAdapterRecoveryTest {
    @Test
    fun `saved network update tries the default MAC setting first and stops at the accepted one`() {
        val tried = mutableListOf<Int>()
        // A network saved in system Settings with "device MAC" accepts only NONE.
        val id = updateWithMacRandomizationFallback { setting ->
            tried += setting
            if (setting == WifiConfiguration.RANDOMIZATION_NONE) 7 else -1
        }

        assertEquals(7, id)
        assertEquals(listOf(WifiConfiguration.RANDOMIZATION_AUTO, WifiConfiguration.RANDOMIZATION_NONE), tried)
    }

    @Test
    fun `saved network update reports failure after every MAC setting is rejected`() {
        val tried = mutableListOf<Int>()

        assertEquals(-1, updateWithMacRandomizationFallback { tried += it; -1 })
        assertEquals(WIFI_MAC_RANDOMIZATION_TRIALS, tried)
        assertEquals(4, tried.toSet().size)
    }

    @Test
    fun `profile update resolves the matching security view when PICO exposes duplicate ID views`() {
        val profile = WifiNetworkProfile("transition", "WPA2_WPA3", "test-password", false)
        val entries = listOf(
            entry(4, "transition", "WPA_PSK"),
            entry(4, "transition", "SAE"),
        )

        assertEquals(0, resolveWifiProfileViewIndex(profile, entries))
    }

    @Test
    fun `credential recovery resolves the requested original view among same ID security views`() {
        val state = WifiAutoJoinState(4, "transition", listOf("WPA_PSK", "SAE"), true)
        val entries = listOf(
            entry(4, "transition", "WPA_PSK"),
            entry(4, "transition", "SAE"),
        )

        assertEquals(1, resolveWifiRecoveryViewIndex(state, "SAE", entries))
    }

    @Test
    fun `profile resolution rejects the same identity saved under distinct IDs`() {
        val profile = WifiNetworkProfile("transition", "WPA2_WPA3", "test-password", false)
        val entries = listOf(
            entry(4, "transition", "WPA_PSK"),
            entry(8, "transition", "WPA_PSK"),
        )

        val failure = assertThrows(WifiAdapterFailure::class.java) { resolveWifiProfileViewIndex(profile, entries) }

        assertEquals(WifiReason.VERIFY_FAILED, failure.reason)
    }

    @Test
    fun `an unrelated security view for the same SSID does not block adding the profile`() {
        val profile = WifiNetworkProfile("open", "OPEN", "", false)

        assertNull(resolveWifiProfileViewIndex(profile, listOf(entry(4, "open", "OTHER:{0}"))))
    }

    @Test
    fun `recovery never uses a reassigned ID and still restores other uniquely resolved networks`() {
        val snapshot = WifiRecoverySnapshot(
            networks = listOf(
                WifiAutoJoinState(1, "former", listOf("WPA_PSK"), allowed = true),
                WifiAutoJoinState(2, "saved", listOf("WPA_PSK"), allowed = true),
            ),
            knownCredentials = emptyList(),
        )
        val candidate = listOf(WifiNetworkProfile("candidate", "WPA2_WPA3", "test-password", false))
        val entries = listOf(
            WifiConfiguredEntry(1, "unrelated", "WPA_PSK", false, true),
            WifiConfiguredEntry(7, "saved", "WPA_PSK", false, false),
            WifiConfiguredEntry(8, "candidate", "WPA_PSK", false, true),
        )

        val plan = wifiRecoveryAutoJoinPlan(snapshot, entries, candidate)

        assertEquals(mapOf(7 to true, 8 to false), plan.targets)
        assertEquals(false, plan.fullyResolved)
        assertTrue(1 !in plan.targets)
    }

    @Test
    fun `unresolved saved networks do not fall back to their old IDs`() {
        val snapshot = WifiRecoverySnapshot(
            networks = listOf(WifiAutoJoinState(1, "former", listOf("WPA_PSK"), allowed = true)),
            knownCredentials = emptyList(),
        )

        val plan = wifiRecoveryAutoJoinPlan(
            snapshot,
            listOf(WifiConfiguredEntry(1, "unrelated", "WPA_PSK", false, true)),
            emptyList(),
        )

        assertEquals(emptyMap<Int, Boolean>(), plan.targets)
        assertEquals(false, plan.fullyResolved)
    }

    @Test
    fun `recovery re-enables every eligible network and reports any failure`() {
        val attempted = mutableListOf<Int>()

        val result = reenableWifiRecoveryNetworks(
            expectedAutoJoin = linkedMapOf(1 to true, 2 to false, 3 to true, 4 to true),
            selectedTargetId = 3,
        ) { id ->
            attempted += id
            if (id == 1) WifiReason.RESTORE_FAILED else WifiReason.NONE
        }

        assertEquals(listOf(1, 4), attempted)
        assertEquals(WifiReason.RESTORE_FAILED, result)
    }

    @Test
    fun `without a selected target all eligible networks are re-enabled`() {
        val attempted = mutableListOf<Int>()

        val result = reenableWifiRecoveryNetworks(
            expectedAutoJoin = linkedMapOf(3 to true, 4 to true),
            selectedTargetId = null,
        ) { id ->
            attempted += id
            WifiReason.NONE
        }

        assertEquals(listOf(3, 4), attempted)
        assertEquals(WifiReason.NONE, result)
    }

    @Test
    fun `credential failure still restores auto join reconnects and verifies`() {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, WifiReason>>()

        val result = restoreWifiRecoveryBestEffort(
            restoreCredentials = { calls += "credentials"; WifiReason.RESTORE_FAILED },
            restoreAutoJoin = { calls += "auto-join"; WifiReason.NONE },
            reconnect = { calls += "reconnect"; WifiReason.NONE },
            verify = { calls += "verify"; WifiReason.NONE },
            onFailure = { operation, reason -> failures += operation to reason },
        )

        assertEquals(WifiReason.RESTORE_FAILED, result)
        assertEquals(listOf("credentials", "auto-join", "reconnect", "verify"), calls)
        assertEquals(listOf("RESTORE" to WifiReason.RESTORE_FAILED), failures)
    }

    @Test
    fun `auto join failure still attempts reconnect and verification and remains a failure`() {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, WifiReason>>()

        val result = restoreWifiRecoveryBestEffort(
            restoreCredentials = { calls += "credentials"; WifiReason.NONE },
            restoreAutoJoin = { calls += "auto-join"; WifiReason.UPDATE_FAILED },
            reconnect = { calls += "reconnect"; WifiReason.NONE },
            verify = { calls += "verify"; WifiReason.NONE },
            onFailure = { operation, reason -> failures += operation to reason },
        )

        assertEquals(WifiReason.RESTORE_FAILED, result)
        assertEquals(listOf("credentials", "auto-join", "reconnect", "verify"), calls)
        assertEquals(listOf("AUTO_JOIN" to WifiReason.UPDATE_FAILED), failures)
        assertTrue(calls.contains("reconnect"))
    }

    @Test
    fun `recovery helper continues verification after a reconnect callback failure`() {
        val calls = mutableListOf<String>()

        val result = restoreWifiRecoveryBestEffort(
            restoreCredentials = { calls += "credentials"; WifiReason.NONE },
            restoreAutoJoin = { calls += "auto-join"; WifiReason.NONE },
            reconnect = { calls += "reconnect"; WifiReason.RESTORE_FAILED },
            verify = { calls += "verify"; WifiReason.NONE },
        )

        assertEquals(WifiReason.RESTORE_FAILED, result)
        assertEquals(listOf("credentials", "auto-join", "reconnect", "verify"), calls)
    }

    private fun entry(id: Int, ssid: String, security: String) =
        WifiConfiguredEntry(id, ssid, security, hidden = false, autoJoinEnabled = true)
}
