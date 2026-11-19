package com.styly.mdmclient

import android.net.wifi.WifiConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiManagerAdapterApplicationTest {
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
    fun `password updates preserve the existing SAE security view`() {
        val current = WifiNetworkProfile("saved-sae", "WPA2_WPA3", "old-password", false)
        val updated = current.copy(password = "new-password")
        val savedView = entry(4, current.ssid, "SAE")

        assertEquals("SAE", desiredWifiSecurityView(updated, savedView.security))
        assertEquals("SAE", desiredWifiSecurityView(current, savedView.security))
        assertEquals("WPA_PSK", desiredWifiSecurityView(updated))
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
    fun `only the original primary auto joins and all other saved IDs are disabled`() {
        val primary = WifiNetworkProfile("primary", "WPA2_WPA3", "new-password", false)
        val entries = listOf(
            entry(3, "primary", "WPA_PSK", allowed = false),
            entry(4, "backup", "OPEN"),
            entry(8, "backup", "WPA_PSK"),
            entry(8, "backup", "SAE", allowed = false),
            entry(12, "unlisted", "WPA_PSK"),
            entry(12, "unlisted", "SAE"),
            entry(14, "already-off", "OPEN", allowed = false),
        )

        assertEquals(
            mapOf(3 to true, 4 to false, 8 to false, 12 to false),
            wifiAutoJoinChanges(entries, listOf(primary), prioritizeNetworks = true, hasVerifiedCandidate = true),
        )
    }

    @Test
    fun `missing original primary does not promote an enabled fallback`() {
        val primary = WifiNetworkProfile("primary", "WPA2_WPA3", "primary-password", false)
        val entries = listOf(
            entry(8, "backup", "WPA_PSK"),
            entry(8, "backup", "SAE"),
            entry(12, "unlisted", "OPEN", allowed = false),
        )

        assertEquals(mapOf(8 to false),
            wifiAutoJoinChanges(entries, listOf(primary), prioritizeNetworks = true, hasVerifiedCandidate = true))
    }

    @Test
    fun `auto join stays as it was when no candidate write can be confirmed`() {
        val primary = WifiNetworkProfile("primary", "WPA2_WPA3", "primary-password", hidden = true)
        val entries = listOf(entry(3, "primary", "WPA_PSK", allowed = false), entry(7, "unlisted", "OPEN"))

        for (writes in listOf(emptyMap(), mapOf(0 to (3 to "WPA_PSK")))) {
            val confirmed = wifiProfileReadbackIndexes(entries, listOf(primary), writes)
            assertTrue(confirmed.isEmpty())
            for (prioritizeNetworks in listOf(true, false)) {
                assertTrue(wifiAutoJoinChanges(entries, listOf(primary), prioritizeNetworks,
                    hasVerifiedCandidate = confirmed.isNotEmpty()).isEmpty())
            }
        }
    }

    @Test
    fun `Android selection enables all listed security matches and disables other saved IDs`() {
        val target = listOf(
            WifiNetworkProfile("primary", "WPA2_WPA3", "primary-password", false),
            WifiNetworkProfile("backup", "OPEN", "", false),
        )
        val entries = listOf(
            entry(3, "primary", "WPA_PSK"),
            entry(3, "primary", "SAE", allowed = false),
            entry(6, "primary", "OPEN"),
            entry(8, "backup", "OPEN", allowed = false),
            entry(9, "unlisted", "WPA_PSK"),
        )

        assertEquals(mapOf(3 to true, 8 to true, 6 to false, 9 to false),
            wifiAutoJoinChanges(entries, target, prioritizeNetworks = false, hasVerifiedCandidate = true))
    }

    @Test
    fun `readback keeps a successfully written later candidate when the first candidate failed`() {
        val profiles = listOf(
            WifiNetworkProfile("primary", "WPA2_WPA3", "new-password", false),
            WifiNetworkProfile("backup", "WPA2_WPA3", "backup-password", false),
        )
        val entries = listOf(
            entry(3, "primary", "WPA_PSK"), // Old saved network remains after its update failed.
            entry(8, "backup", "WPA_PSK"),
            entry(12, "unrelated", "WPA_PSK"),
        )

        assertEquals(
            listOf(1),
            wifiProfileReadbackIndexes(entries, profiles, mapOf(1 to (8 to "WPA_PSK"))),
        )
    }

    @Test
    fun `readback requires candidate ID security and hidden state but ignores auto join`() {
        val profile = WifiNetworkProfile("backup", "WPA2_WPA3", "backup-password", hidden = true)
        val expected = mapOf(0 to (8 to "SAE"))

        assertEquals(
            listOf(0),
            wifiProfileReadbackIndexes(listOf(entry(8, "backup", "SAE", hidden = true, allowed = false)), listOf(profile), expected),
        )
        assertFalse(wifiProfileReadbackIndexes(listOf(entry(7, "backup", "SAE", hidden = true)), listOf(profile), expected).isNotEmpty())
        assertFalse(wifiProfileReadbackIndexes(listOf(entry(8, "backup", "WPA_PSK", hidden = true)), listOf(profile), expected).isNotEmpty())
        assertFalse(wifiProfileReadbackIndexes(listOf(entry(8, "backup", "SAE", hidden = false)), listOf(profile), expected).isNotEmpty())
    }

    @Test
    fun `readback checks each security view for one PICO network ID`() {
        val profile = WifiNetworkProfile("transition", "WPA2_WPA3", "test-password", false)
        val expected = mapOf(0 to (4 to "SAE"))
        val entries = listOf(
            entry(4, "transition", "WPA_PSK"),
            entry(4, "transition", "SAE"),
            entry(9, "unrelated", "WPA_PSK"),
        )

        assertEquals(listOf(0), wifiProfileReadbackIndexes(entries, listOf(profile), expected))
    }

    @Test
    fun `privileged update readback requires the original ID and requested profile fields`() {
        val original = WifiConfiguredEntry(7, "saved", "WPA_PSK", hidden = false, autoJoinEnabled = false)
        val updated = original.copy(hidden = true)

        assertTrue(wifiSavedUpdateReadback(listOf(updated, updated.copy(security = "SAE")), original, "WPA_PSK", true))
        assertFalse(wifiSavedUpdateReadback(listOf(updated.copy(networkId = 8)), original, "WPA_PSK", true))
        assertFalse(wifiSavedUpdateReadback(listOf(updated.copy(ssid = "other")), original, "WPA_PSK", true))
        assertFalse(wifiSavedUpdateReadback(listOf(updated.copy(security = "SAE")), original, "WPA_PSK", true))
        assertFalse(wifiSavedUpdateReadback(listOf(original), original, "WPA_PSK", true))
        assertFalse(wifiSavedUpdateReadback(listOf(updated.copy(autoJoinEnabled = true)), original, "WPA_PSK", true))
    }

    @Test
    fun `stored MAC policy matches full or PUI masked SSIDs by network ID`() {
        val dump = """
            * ID: 7 SSID: "office" PROVIDER-NAME: null
             macRandomizationSetting: 1
            WifiConfigManager - Configured networks Begin ----
            * ID: 7 SSID: "office" PROVIDER-NAME: null
             NetworkSelectionStatus NETWORK_SELECTION_ENABLED
             macRandomizationSetting: 3
            - DSBLE ID: 9 SSID: *****aintenance" PROVIDER-NAME: null
             NetworkSelectionStatus NETWORK_SELECTION_TEMPORARY_DISABLED
             macRandomizationSetting: 0
            ID: 10 SSID: **** profile" PROVIDER-NAME: null
             NetworkSelectionStatus NETWORK_SELECTION_ENABLED
             macRandomizationSetting: 2
            WifiConfigManager - Configured networks End ----
        """.trimIndent()

        assertEquals(
            mapOf(7 to WifiConfiguration.RANDOMIZATION_AUTO, 9 to WifiConfiguration.RANDOMIZATION_NONE),
            wifiStoredMacSettings(dump.lineSequence(), mapOf(7 to "\"office\"", 9 to "\"VR-Maintenance\"", 10 to "\"old network\"")),
        )
    }

    @Test
    fun `invalid or incomplete saved network dumps cannot supply a stored MAC setting`() {
        val begin = "WifiConfigManager - Configured networks Begin ----"
        val end = "WifiConfigManager - Configured networks End ----"
        val savedHeader = "ID: 7 SSID: \"office\" PROVIDER-NAME: null"
        val setting = " macRandomizationSetting: 3"
        val original = mapOf(7 to "\"office\"")

        assertTrue(wifiStoredMacSettings(sequenceOf(begin, savedHeader, setting), original).isEmpty())
        assertTrue(wifiStoredMacSettings(sequenceOf(begin, savedHeader, setting, savedHeader, end), original).isEmpty())
        assertTrue(wifiStoredMacSettings(sequenceOf(begin, savedHeader, setting.replace('3', '9'), end), original).isEmpty())
    }

    @Test
    fun `configured SSID normalization skips null and empty entries independently`() {
        val configuredSsids = listOf(null, "\"office\"", "\"\"", "   ")
            .mapNotNull(::normalizeConfiguredWifiSsid)

        assertEquals(listOf("office", "   "), configuredSsids)
    }

    private fun entry(
        id: Int,
        ssid: String,
        security: String,
        hidden: Boolean = false,
        allowed: Boolean = true,
    ) = WifiConfiguredEntry(id, ssid, security, hidden, allowed)
}
