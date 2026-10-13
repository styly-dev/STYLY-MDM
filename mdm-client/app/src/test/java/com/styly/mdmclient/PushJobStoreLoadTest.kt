package com.styly.mdmclient

import java.io.FileNotFoundException
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PushJobStoreLoadTest {
    @Test
    fun missingStateIsDistinctFromValidEmptyState() {
        val result = loadPushState(
            readText = { throw FileNotFoundException("missing") },
            normalize = { it },
            fileExists = { false },
        )

        assertSame(PushStateLoadResult.Missing, result)
    }

    @Test
    fun existingFileThatCannotBeOpenedIsUnreadableNotMissing() {
        // EACCES and EMFILE surface as FileNotFoundException even though state exists.
        val failure = FileNotFoundException("state.json: open failed: EACCES (Permission denied)")
        val result = loadPushState(
            readText = { throw failure },
            normalize = { it },
            fileExists = { true },
        )

        assertTrue(result is PushStateLoadResult.Unreadable)
        assertSame(failure, (result as PushStateLoadResult.Unreadable).error)
    }

    @Test
    fun readErrorIsUnreadableNotCorrupt() {
        val failure = IOException("EIO")
        val result = loadPushState(
            readText = { throw failure },
            normalize = { it },
            fileExists = { true },
        )

        assertTrue(result is PushStateLoadResult.Unreadable)
        assertSame(failure, (result as PushStateLoadResult.Unreadable).error)
    }

    @Test
    fun validEmptyStateRemainsAuthoritative() {
        val result = loadPushState(
            readText = { "{\"active\":null,\"pending_results\":[],\"completed_receipts\":[]}" },
            normalize = { it },
            fileExists = { true },
        )

        assertTrue(result is PushStateLoadResult.Valid)
        val state = (result as PushStateLoadResult.Valid).state
        assertEquals(null, state.active)
        assertTrue(state.pendingResults.isEmpty())
        assertTrue(state.completedReceipts.isEmpty())
    }

    @Test
    fun corruptStateIsNotConvertedToEmptyState() {
        val result = loadPushState(
            readText = { "not-json" },
            normalize = { it },
            fileExists = { true },
        )

        assertTrue(result is PushStateLoadResult.Corrupt)
    }

    @Test
    fun malformedActiveStateDoesNotBecomeAuthoritativeAbsence() {
        val cases = listOf(
            "active object missing command" to "{\"active\":{\"phase\":\"downloading\"},",
            "active has wrong type" to "{\"active\":\"broken\",",
        )
        for ((label, prefix) in cases) {
            val json = prefix + "\"pending_results\":[],\"completed_receipts\":[]}"
            val result = loadPushState({ json }, { it }, { true })
            assertTrue(label, result is PushStateLoadResult.Corrupt)
        }
    }

    @Test
    fun invalidReceiptDoesNotAuthorizeDiscardingValidActiveAndResults() {
        val command = pushTestCommand(
            jobId = "12345678-1234-4234-8234-123456789abc",
            artifactId = "87654321-4321-4321-8321-cba987654321",
            artifactSize = 10L,
            bundleFilename = "content.zip",
            revision = 7L,
        )
        val receipt = PushProtocol.Receipt(
            command,
            PushProtocol.Result(command.jobId, 1, "success", command.destPath),
        )
        val state = PushProtocol.State(
            PushProtocol.Active(command, PushProtocol.PHASE_DOWNLOADING),
            listOf(receipt),
            listOf(receipt),
        )
        val malformedReceipts = listOf(
            "not-an-object",
            JSONObject().put("result", receipt.result.toJson()),
            JSONObject().put("command", command.toJson()),
            receipt.toStateJson().put("command", JSONObject()),
            receipt.toStateJson().put("result", receipt.result.toJson().put("status", "unknown")),
        )

        for (collection in listOf("pending_results", "completed_receipts")) {
            for (malformed in malformedReceipts) {
                val json = PushProtocol.stateToJson(state)
                json.getJSONArray(collection).put(malformed)
                val result = loadPushState({ json.toString() }, { it }, { true })

                assertTrue("$collection: $malformed", result is PushStateLoadResult.InvalidReceipts)
            }
        }
    }

    @Test
    fun wrongTypedReceiptCollectionDoesNotAuthorizeStateReset() {
        for (collection in listOf("pending_results", "completed_receipts")) {
            val json = JSONObject()
                .put("active", JSONObject.NULL)
                .put("pending_results", JSONArray())
                .put("completed_receipts", JSONArray())
                .put(collection, "broken")

            val result = loadPushState({ json.toString() }, { it }, { true })

            assertTrue(result is PushStateLoadResult.InvalidReceipts)
        }
    }
}
