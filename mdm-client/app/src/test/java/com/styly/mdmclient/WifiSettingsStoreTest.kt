package com.styly.mdmclient

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WifiSettingsStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `v5 round trip preserves ordered candidates and the latest result`() {
        val file = File(temporaryFolder.newFolder("wifi-state"), "state.json")
        val store = WifiSettingsStore(file) {}
        val preferred = WifiNetworkProfile("office-a", "WPA2_WPA3", "first-password", hidden = false)
        val fallback = WifiNetworkProfile("office-b", "WPA2_WPA3", "second-password", hidden = false)
        val expected = WifiLocalState(
            lastApplied = WifiSavedProfile(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", listOf(preferred, fallback), autoReturn = true,
                networkIds = mapOf(preferred.identity to 0, fallback.identity to 7),
            ),
            latestResult = WifiManualResult(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "success",
                WifiReason.FALLBACK,
            ),
            managedWifi = true,
        )

        store.write(expected)

        assertEquals(expected, store.read())
        val encoded = JSONObject(file.readText())
        assertEquals(5, encoded.getInt("version"))
        assertEquals(
            setOf("version", "last_applied", "latest_result", "managed_wifi"),
            encoded.keys().asSequence().toSet(),
        )
        assertEquals("office-a", encoded.getJSONObject("last_applied").getJSONArray("networks")
            .getJSONObject(0).getString("ssid"))
        assertEquals("office-b", encoded.getJSONObject("last_applied").getJSONArray("networks")
            .getJSONObject(1).getString("ssid"))
        val result = encoded.getJSONObject("latest_result")
        assertEquals(setOf("revision", "status", "reason"), result.keySet())
        assertEquals("FALLBACK", result.getString("reason"))
    }

    @Test
    fun `v4 boot markers migrate by fingerprinting their saved credential`() {
        val network = WifiNetworkProfile("office-a", "WPA2_WPA3", "first-password", hidden = false)
        val profile = WifiSavedProfile("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", listOf(network))
        val legacy = JSONObject(WifiSettingsCodec.encode(WifiLocalState(lastApplied = profile)))
            .put("version", 4)
        legacy.getJSONObject("last_applied").put(
            "unverified_on_boot", JSONObject().put(network.identity, 133),
        )

        val migrated = WifiSettingsCodec.decode(legacy.toString())
        val marker = migrated.lastApplied!!.unverifiedOnBoot.getValue(network.identity)

        assertEquals(133, marker.bootCount)
        assertEquals(wifiPasswordHash(network.password), marker.passwordHash)
        val upgraded = JSONObject(WifiSettingsCodec.encode(migrated))
        assertEquals(emptyMap<String, Int>(), migrated.lastApplied.networkIds)
        assertEquals(5, upgraded.getInt("version"))
        assertEquals(133, upgraded.getJSONObject("last_applied")
            .getJSONObject("unverified_on_boot").getJSONObject(network.identity).getInt("boot_count"))
    }

    @Test
    fun `compacted saved networks preserve the original primary identity`() {
        val file = File(temporaryFolder.newFolder("wifi-state-compacted"), "state.json")
        val store = WifiSettingsStore(file) {}
        val originalPrimary = WifiNetworkProfile("office-a", "WPA2_WPA3", "first-password", hidden = false)
        val savedFallback = WifiNetworkProfile("office-b", "WPA2_WPA3", "second-password", hidden = false)
        val profile = WifiSavedProfile(
            revision = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            networks = listOf(savedFallback),
            autoReturn = true,
            primaryIdentity = originalPrimary.identity,
        )

        store.write(WifiLocalState(lastApplied = profile))

        val restored = store.read().lastApplied!!
        assertEquals(listOf(savedFallback), restored.networks)
        assertEquals(originalPrimary.identity, restored.primaryIdentity)
        assertEquals(originalPrimary.identity, JSONObject(file.readText())
            .getJSONObject("last_applied").getString("primary_identity"))
    }

    @Test
    fun `unsupported state versions are rejected and their files are preserved`() {
        val file = File(temporaryFolder.newFolder("wifi-state-unsupported"), "state.json")
        val store = WifiSettingsStore(file) {}
        for (version in listOf(1, 2, 3, 6)) {
            val saved = JSONObject(WifiSettingsCodec.encode(WifiLocalState())).put("version", version).toString()
            file.writeText(saved)

            assertThrows(IllegalArgumentException::class.java) { store.read() }

            assertEquals(saved, file.readText())
        }
    }

    @Test
    fun directorySyncFailureIsReportedAndDoesNotLeaveTemporaryFile() {
        val directory = temporaryFolder.newFolder("wifi-state-sync-failure")
        val file = File(directory, "state.json")
        val store = WifiSettingsStore(file) { throw IllegalStateException("directory sync failed") }

        assertThrows(IllegalStateException::class.java) {
            store.write(WifiLocalState())
        }

        assertEquals(false, File(directory, "state.json.tmp").exists())
    }

    @Test
    fun corruptStateIsNotSilentlyReset() {
        val file = File(temporaryFolder.newFolder("wifi-state-corrupt"), "state.json")
        val corrupt = "{not-json"
        file.writeText(corrupt)
        val store = WifiSettingsStore(file) {}

        assertThrows(Exception::class.java) { store.read() }

        assertEquals(corrupt, file.readText())
    }
}
