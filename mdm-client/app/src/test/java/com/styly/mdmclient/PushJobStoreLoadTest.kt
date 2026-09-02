package com.styly.mdmclient

import java.io.FileNotFoundException
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
        )

        assertSame(PushStateLoadResult.Missing, result)
    }

    @Test
    fun validEmptyStateRemainsAuthoritative() {
        val result = loadPushState(
            readText = { "{\"active\":null,\"pending_results\":[],\"completed_receipts\":[]}" },
            normalize = { it },
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
        )

        assertTrue(result is PushStateLoadResult.Corrupt)
    }

    @Test
    fun malformedActiveStateIsNotSanitizedIntoAuthoritativeAbsence() {
        val result = loadPushState(
            readText = {
                "{\"active\":{\"phase\":\"downloading\"}," +
                    "\"pending_results\":[],\"completed_receipts\":[]}"
            },
            normalize = { it },
        )

        assertTrue(result is PushStateLoadResult.Corrupt)
    }

    @Test
    fun wrongTypedActiveStateIsNotTreatedAsNull() {
        val result = loadPushState(
            readText = {
                "{\"active\":\"broken\"," +
                    "\"pending_results\":[],\"completed_receipts\":[]}"
            },
            normalize = { it },
        )

        assertTrue(result is PushStateLoadResult.Corrupt)
    }
}
