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
    fun `round trip preserves auto join recovery journal and current state`() {
        val file = File(temporaryFolder.newFolder("wifi-state"), "state.json")
        val store = WifiSettingsStore(file) {}
        val oldProfile = WifiNetworkProfile("office", "WPA2_WPA3", "correcthorsebattery", hidden = true)
        val candidate = WifiNetworkProfile("guest", "OPEN", "", hidden = false)
        val operation = WifiManualOperation(
            operationId = "a4d13b84-1bd7-4bce-bd4d-d056801d4a3f",
            revision = "91b831b3-884e-4e0e-8700-07d2fbe1c436",
            candidate = listOf(candidate),
            recoverySnapshot = WifiRecoverySnapshot(
                networks = listOf(WifiAutoJoinState(7, "office", listOf("WPA_PSK"), allowed = false)),
                knownCredentials = listOf(oldProfile),
            ),
            phase = WifiManualPhase.RESTORING_CONNECTION,
            deadlineElapsedMillis = 60_000L,
            settingsChanged = true,
            policyEpoch = 6L,
            selectionOnly = true,
            selectionIndexes = listOf(0),
            autoReturn = true,
            retryDisconnected = true,
            priorConnectionHadUsableIp = true,
            restoreTarget = WifiRollbackTarget("office", "WPA_PSK"),
            settingsRestored = true,
            restoreStartedElapsedMillis = 10_000L,
            restoreDeadlineElapsedMillis = 70_000L,
            restoreBootCount = 133,
            restoreRetryCount = 2,
            restoreUnconfirmedReported = true,
        )
        val expected = WifiLocalState(
            lastApplied = WifiSavedProfile(operation.revision, listOf(oldProfile), autoReturn = false),
            manualOperation = operation,
            latestResult = WifiManualResult(operation.operationId, operation.revision, "failed", WifiReason.RESTORE_UNCONFIRMED, false),
            processedOperationIds = listOf(operation.operationId),
            managedWifi = false,
            policyEpoch = 6L,
            policyRevision = operation.revision,
        )

        store.write(expected)

        assertEquals(expected, store.read())
        assertEquals(2, JSONObject(file.readText()).getInt("version"))
    }

    @Test
    fun `completed version one state is discarded for the new Wi-Fi contract`() {
        val profile = WifiNetworkProfile("office", "WPA2_WPA3", "correcthorsebattery", hidden = false)
        val operation = WifiManualOperation(
            operationId = "a4d13b84-1bd7-4bce-bd4d-d056801d4a3f",
            revision = "91b831b3-884e-4e0e-8700-07d2fbe1c436",
            candidate = listOf(profile),
            recoverySnapshot = WifiRecoverySnapshot(emptyList(), emptyList()),
            phase = WifiManualPhase.PREPARED,
        )
        val legacy = JSONObject(WifiSettingsCodec.encode(WifiLocalState(lastApplied = WifiSavedProfile(
            operation.revision, listOf(profile), autoReturn = true,
        )))).put("version", 1)

        val restored = WifiSettingsCodec.decode(legacy.toString())

        assertEquals(WifiLocalState(managedWifi = false), restored)
    }

    @Test
    fun `version one pending recovery is never discarded`() {
        val operation = WifiManualOperation(
            operationId = "a4d13b84-1bd7-4bce-bd4d-d056801d4a3f",
            revision = "91b831b3-884e-4e0e-8700-07d2fbe1c436",
            candidate = listOf(WifiNetworkProfile("office", "WPA2_WPA3", "correcthorsebattery", false)),
            recoverySnapshot = WifiRecoverySnapshot(emptyList(), emptyList()),
            phase = WifiManualPhase.ROLLING_BACK,
            settingsChanged = true,
        )
        val legacy = JSONObject(WifiSettingsCodec.encode(WifiLocalState(manualOperation = operation)))
            .put("version", 1)

        assertThrows(IllegalArgumentException::class.java) { WifiSettingsCodec.decode(legacy.toString()) }
    }

    @Test
    fun `version one malformed or missing operation journal fails closed`() {
        val base = JSONObject(WifiSettingsCodec.encode(WifiLocalState())).put("version", 1)
        val malformed = listOf(
            JSONObject(base.toString()).put("manual_operation", "not-json-object"),
            JSONObject(base.toString()).put("manual_operation", JSONArray()),
            JSONObject(base.toString()).apply { remove("manual_operation") },
        )

        for (state in malformed) {
            assertThrows(IllegalArgumentException::class.java) { WifiSettingsCodec.decode(state.toString()) }
        }
    }

    @Test
    fun `prototype and unknown state formats are rejected`() {
        val current = JSONObject(WifiSettingsCodec.encode(WifiLocalState()))
        assertThrows(IllegalArgumentException::class.java) {
            WifiSettingsCodec.decode(current.put("version", 5).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            WifiSettingsCodec.decode(current.put("version", 3).toString())
        }
    }

    @Test
    fun `operation receipts stay distinct and bounded`() {
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

    private fun operationId(value: Int): String =
        "%08x-0000-4000-8000-%012x".format(value, value)
}
