package com.styly.mdmclient

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WifiSettingsStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun roundTripPreservesRecoveryJournalAndOperationReceipts() {
        val file = File(temporaryFolder.newFolder("wifi-state"), "state.json")
        val store = WifiSettingsStore(file) {}
        val profile = WifiNetworkProfile("office", "WPA_PSK", "correcthorsebattery", hidden = true)
        val operation = WifiManualOperation(
            operationId = "a4d13b84-1bd7-4bce-bd4d-d056801d4a3f",
            revision = "91b831b3-884e-4e0e-8700-07d2fbe1c436",
            candidate = listOf(profile),
            recoveryNetworks = listOf(WifiNetworkProfile("recovery", "OPEN", "", false)),
            phase = WifiManualPhase.APPLYING,
            deadlineElapsedMillis = 60_000L,
            settingsChanged = true,
        )
        val expected = WifiLocalState(
            connection = WifiConnectionSettings(tlsEnabled = true),
            lastApplied = WifiSavedProfile(operation.revision, listOf(profile)),
            initialRecoveryProfile = WifiSavedProfile(
                "b35a8eb2-0a20-43dc-bf2f-ec4ef66aaf49",
                listOf(WifiNetworkProfile("seed", "WPA_PSK", "recovery-password", hidden = false)),
            ),
            manualOperation = operation,
            manualResults = listOf(
                WifiManualResult(operation.operationId, operation.revision, "unknown", WifiReason.UNKNOWN, false),
            ),
            bootGeneration = "count:42",
            bootClaimedGeneration = "count:41",
            processedOperationIds = listOf(operation.operationId),
        )

        store.write(expected)

        assertEquals(expected, store.read())
        assertEquals(5, JSONObject(file.readText()).getInt("version"))
    }

    @Test
    fun `version four known profile journal migrates to compact recovery profiles`() {
        val expected = stateWithPendingOperation()
        val v4 = asVersionFourKnownProfileState(expected)

        val decoded = WifiSettingsCodec.decode(v4.toString())

        assertEquals(expected, decoded)
        assertEquals(expected.manualOperation?.recoveryNetworks, decoded.manualOperation?.recoveryNetworks)
        assertEquals(expected, WifiSettingsCodec.decode(WifiSettingsCodec.encode(decoded)))
    }

    @Test
    fun `version four journal with unsupported parcel or restore flags is rejected`() {
        val expected = stateWithPendingOperation()
        val withParcel = asVersionFourKnownProfileState(expected).apply {
            getJSONObject("manual_operation").getJSONArray("restore_snapshot")
                .getJSONObject(0).put("configuration_parcel", "unsupported-old-state")
        }
        val disabled = asVersionFourKnownProfileState(expected).apply {
            getJSONObject("manual_operation").getJSONArray("restore_snapshot")
                .getJSONObject(0).put("enabled", false)
        }
        val prioritized = asVersionFourKnownProfileState(expected).apply {
            getJSONObject("manual_operation").getJSONArray("restore_snapshot")
                .getJSONObject(0).put("priority", 1)
        }

        assertThrows(IllegalArgumentException::class.java) { WifiSettingsCodec.decode(withParcel.toString()) }
        assertThrows(IllegalArgumentException::class.java) { WifiSettingsCodec.decode(disabled.toString()) }
        assertThrows(IllegalArgumentException::class.java) { WifiSettingsCodec.decode(prioritized.toString()) }
    }

    @Test
    fun `version four verifying journal is migrated to recovery before any server confirmation`() {
        val expected = stateWithPendingOperation()
        val v4 = asVersionFourKnownProfileState(expected).apply {
            getJSONObject("manual_operation")
                .put("phase", "verifying")
                .put("settings_changed", false)
                .put("challenge", "b12b4fcb-90b4-4a07-a3a8-e9775e804fe5")
        }

        val operation = requireNotNull(WifiSettingsCodec.decode(v4.toString()).manualOperation)

        assertEquals(WifiManualPhase.ROLLING_BACK, operation.phase)
        assertTrue(operation.settingsChanged)
        assertEquals(expected.manualOperation?.recoveryNetworks, operation.recoveryNetworks)
    }

    @Test
    fun `pre-v4 local state is rejected instead of discarding recovery evidence`() {
        val legacy = JSONObject(WifiSettingsCodec.encode(stateWithPendingOperation())).put("version", 3)
        assertThrows(IllegalArgumentException::class.java) { WifiSettingsCodec.decode(legacy.toString()) }
    }

    @Test
    fun `legacy device token is ignored and removed on next state write`() {
        val file = File(temporaryFolder.newFolder("wifi-state-legacy-token"), "state.json")
        val v4 = asVersionFourKnownProfileState(stateWithPendingOperation()).apply {
            getJSONObject("connection").put("tls_enabled", true).put("device_token", "legacy-secret")
        }
        file.writeText(v4.toString())
        val store = WifiSettingsStore(file) {}
        val decoded = store.read()

        assertEquals(true, decoded.connection.tlsEnabled)
        assertEquals(true, decoded.manualOperation?.settingsChanged)
        assertEquals(1, decoded.manualOperation?.candidate?.size)
        assertEquals(true, file.readText().contains("legacy-secret"))

        store.write(decoded)

        assertEquals(false, file.readText().contains("device_token"))
        assertEquals(false, file.readText().contains("legacy-secret"))
    }

    @Test
    fun `operation receipts are decoded with a distinct 500 item limit`() {
        val ids = (0..500).map(::operationId)
        val encoded = JSONObject(WifiSettingsCodec.encode(WifiLocalState()))
            .put("processed_operation_ids", JSONArray(ids))

        val receipts = WifiSettingsCodec.decode(encoded.toString()).processedOperationIds

        assertEquals(WIFI_MAX_OPERATION_RECEIPTS, receipts.size)
        assertEquals(ids.takeLast(WIFI_MAX_OPERATION_RECEIPTS), receipts)
    }

    @Test
    fun directorySyncFailureIsReportedAndDoesNotLeaveTemporaryFile() {
        val directory = temporaryFolder.newFolder("wifi-state-sync-failure")
        val file = File(directory, "state.json")
        val store = WifiSettingsStore(file) { throw IllegalStateException("directory sync failed") }

        assertThrows(IllegalStateException::class.java) {
            store.write(WifiLocalState(bootGeneration = "count:1"))
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

    private fun stateWithPendingOperation() = WifiLocalState(
        manualOperation = WifiManualOperation(
            operationId = "a4d13b84-1bd7-4bce-bd4d-d056801d4a3f",
            revision = "91b831b3-884e-4e0e-8700-07d2fbe1c436",
            candidate = listOf(WifiNetworkProfile("candidate", "OPEN", "", false)),
            recoveryNetworks = listOf(WifiNetworkProfile("baseline", "WPA_PSK", "known-password", true)),
            phase = WifiManualPhase.ROLLING_BACK,
            deadlineElapsedMillis = 60_000L,
            failureReason = WifiReason.TIMEOUT_ROLLED_BACK,
            settingsChanged = true,
            policyEpoch = 6L,
        ),
        manualResults = listOf(
            WifiManualResult("a4d13b84-1bd7-4bce-bd4d-d056801d4a3f", "91b831b3-884e-4e0e-8700-07d2fbe1c436", "unknown", WifiReason.UNKNOWN, false),
        ),
        bootGeneration = "count:42",
        bootClaimedGeneration = "count:41",
        processedOperationIds = listOf("a4d13b84-1bd7-4bce-bd4d-d056801d4a3f"),
        managedWifi = false,
        policyEpoch = 6L,
        policyRevision = "91b831b3-884e-4e0e-8700-07d2fbe1c436",
    )

    private fun asVersionFourKnownProfileState(state: WifiLocalState): JSONObject {
        val root = JSONObject(WifiSettingsCodec.encode(state)).put("version", 4)
        val operation = root.optJSONObject("manual_operation") ?: return root
        val networks = operation.getJSONArray("recovery_networks")
        val legacySnapshot = JSONArray()
        for (index in 0 until networks.length()) {
            legacySnapshot.put(JSONObject().apply {
                put("network", networks.getJSONObject(index))
                put("enabled", true)
                put("priority", 0)
                put("configuration_parcel", JSONObject.NULL)
            })
        }
        operation.remove("recovery_networks")
        operation.put("restore_snapshot", legacySnapshot)
        operation.put("challenge", JSONObject.NULL)
        operation.put("final_state_validated", false)
        return root
    }

    private fun operationId(value: Int): String =
        "%08x-0000-4000-8000-%012x".format(value, value)
}
