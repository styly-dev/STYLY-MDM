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
    fun roundTripPreservesRestorationJournalAndOperationReceipts() {
        val directory = temporaryFolder.newFolder("wifi-state")
        val file = File(directory, "state.json")
        val store = WifiSettingsStore(file) {}
        val profile = WifiNetworkProfile("office", "WPA_PSK", "correcthorsebattery", hidden = true)
        val operation = WifiManualOperation(
            operationId = "a4d13b84-1bd7-4bce-bd4d-d056801d4a3f",
            revision = "91b831b3-884e-4e0e-8700-07d2fbe1c436",
            candidate = listOf(profile),
            restoreSnapshot = listOf(
                WifiNetworkSnapshot(
                    WifiNetworkProfile("old-network", "OPEN", "", hidden = false),
                    enabled = false,
                    priority = 7,
                    configurationParcel = "serialized-config",
                ),
            ),
            phase = WifiManualPhase.VERIFYING,
            challenge = "b12b4fcb-90b4-4a07-a3a8-e9775e804fe5",
            deadlineElapsedMillis = 60_000L,
            settingsChanged = true,
            finalStateValidated = true,
        )
        val expected = WifiLocalState(
            connection = WifiConnectionSettings(tlsEnabled = true),
            lastApplied = WifiSavedProfile(operation.revision, listOf(profile)),
            initialRecoveryProfile = WifiSavedProfile(
                "b35a8eb2-0a20-43dc-bf2f-ec4ef66aaf49",
                listOf(WifiNetworkProfile("recovery", "WPA_PSK", "recovery-password", hidden = false)),
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
        assertEquals(true, file.isFile)
    }

    @Test
    fun `version three state upgrades without losing pending recovery journal`() {
        val operation = WifiManualOperation(
            operationId = "a4d13b84-1bd7-4bce-bd4d-d056801d4a3f",
            revision = "91b831b3-884e-4e0e-8700-07d2fbe1c436",
            candidate = listOf(WifiNetworkProfile("candidate", "OPEN", "", hidden = false)),
            restoreSnapshot = listOf(
                WifiNetworkSnapshot(
                    WifiNetworkProfile("legacy-baseline", "WPA_PSK", "known-password", hidden = true),
                    enabled = false,
                    priority = 5,
                    configurationParcel = "legacy-config-parcel",
                ),
            ),
            phase = WifiManualPhase.ROLLING_BACK,
            failureReason = WifiReason.TIMEOUT_ROLLED_BACK,
            settingsChanged = true,
            policyEpoch = 6L,
        )
        val legacyState = WifiLocalState(
            connection = WifiConnectionSettings(tlsEnabled = true),
            lastApplied = WifiSavedProfile(operation.revision, operation.candidate),
            manualOperation = operation,
            manualResults = listOf(
                WifiManualResult(operation.operationId, operation.revision, "unknown", WifiReason.UNKNOWN, false),
            ),
            bootGeneration = "count:42",
            bootClaimedGeneration = "count:41",
            processedOperationIds = listOf(operation.operationId),
            managedWifi = false,
            policyEpoch = 6L,
            policyRevision = operation.revision,
        )
        val legacyJsonObject = JSONObject(WifiSettingsCodec.encode(legacyState)).put("version", 3)
        legacyJsonObject.remove("initial_recovery_profile")
        val legacyJson = legacyJsonObject.toString()

        val decoded = WifiSettingsCodec.decode(legacyJson)

        assertEquals(legacyState, decoded)
        assertEquals(null, decoded.initialRecoveryProfile)
        assertEquals(operation.restoreSnapshot, decoded.manualOperation?.restoreSnapshot)
        val upgradedJson = WifiSettingsCodec.encode(decoded)
        assertEquals(4, JSONObject(upgradedJson).getInt("version"))
        assertEquals(decoded, WifiSettingsCodec.decode(upgradedJson))
    }

    @Test
    fun legacyDeviceTokenIsIgnoredAndRemovedOnNextStateWrite() {
        val directory = temporaryFolder.newFolder("wifi-state-legacy-token")
        val file = File(directory, "state.json")
        file.writeText(
            """{"version":3,"connection":{"tls_enabled":true,"device_token":"legacy-secret"},"manual_operation":{"operation_id":"a4d13b84-1bd7-4bce-bd4d-d056801d4a3f","revision":"91b831b3-884e-4e0e-8700-07d2fbe1c436","candidate":[{"ssid":"office","security":"OPEN","password":"","hidden":false}],"restore_snapshot":[],"phase":"applying","settings_changed":true}}""",
        )
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
        val directory = temporaryFolder.newFolder("wifi-state-corrupt")
        val file = File(directory, "state.json")
        val corrupt = "{not-json"
        file.writeText(corrupt)
        val store = WifiSettingsStore(file) {}

        assertThrows(Exception::class.java) { store.read() }

        assertEquals(corrupt, file.readText())
    }
}
