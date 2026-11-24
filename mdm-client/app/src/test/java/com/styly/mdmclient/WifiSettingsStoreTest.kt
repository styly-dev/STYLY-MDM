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
    fun `v5 round trip preserves ordered candidate settings`() {
        val file = File(temporaryFolder.newFolder("wifi-state"), "state.json")
        val store = WifiSettingsStore(file) {}
        val preferred = WifiNetworkProfile("office-a", "WPA2_WPA3", "first-password", hidden = false)
        val fallback = WifiNetworkProfile("office-b", "WPA2_WPA3", "second-password", hidden = false)
        val expected = WifiLocalState(
            lastApplied = WifiSavedProfile(
                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", listOf(preferred, fallback), autoReturn = true,
                networkIds = mapOf(preferred.identity to 0, fallback.identity to 7),
            ),
            managedWifi = true,
        )

        store.write(expected)

        assertEquals(expected, store.read())
        val encoded = JSONObject(file.readText())
        assertEquals(5, encoded.getInt("version"))
        assertEquals(
            setOf("version", "last_applied", "managed_wifi"),
            encoded.keys().asSequence().toSet(),
        )
        assertEquals("office-a", encoded.getJSONObject("last_applied").getJSONArray("networks")
            .getJSONObject(0).getString("ssid"))
        assertEquals("office-b", encoded.getJSONObject("last_applied").getJSONArray("networks")
            .getJSONObject(1).getString("ssid"))
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
        for (version in listOf(1, 2, 3, 4, 6)) {
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
