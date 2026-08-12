package com.styly.mdmclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PushProtocolTest {
    private fun payload() = JSONObject().apply {
        put("job_id", UUID.randomUUID().toString())
        put("attempt", 1)
        put("artifact_id", UUID.randomUUID().toString())
        put("artifact_url", "http://server/artifacts/value")
        put("artifact_size", 42)
        put("artifact_sha256", "a".repeat(64))
        put("dest_path", "/sdcard/STYLY/content")
    }

    @Test
    fun `job v1 requires attempt one and complete artifact identity`() {
        val value = payload()
        val command = PushProtocol.parseCommand(value)
        assertEquals(1, command.attempt)
        assertNotNull(command.artifactId)

        value.put("attempt", 2)
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseCommand(value)
        }
    }

    @Test
    fun `job v1 rejects relative artifact URL and non-v4 UUID`() {
        val value = payload().apply { put("artifact_url", "/artifacts/value") }
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseCommand(value)
        }
        value.put("artifact_url", "http://server/artifacts/value")
        value.put("job_id", "00000000-0000-1000-8000-000000000000")
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseCommand(value)
        }
    }

    @Test
    fun `missing delete extras remains safe push`() {
        assertFalse(PushProtocol.parseCommand(payload()).deleteExtras)
    }

    @Test
    fun `durable receipt preserves failure code detail and completion time`() {
        val command = PushProtocol.parseCommand(payload())
        val result = PushProtocol.Result(
            jobId = command.jobId,
            attempt = 1,
            status = "fail",
            destPath = command.destPath,
            failureCode = "validation_failed",
            detail = "invalid archive",
            completedAt = 1234,
        )
        val state = PushProtocol.State(
            active = null,
            pendingResults = listOf(PushProtocol.Receipt(command, result)),
            completedReceipts = listOf(PushProtocol.Receipt(command, result)),
        )
        val decoded = PushProtocol.stateFromJson(PushProtocol.stateToJson(state))
        assertEquals(command, decoded.pendingResults.single().command)
        assertEquals(result, decoded.completedReceipts.single().result)
        val wire = result.toJson()
        assertEquals("validation_failed", wire.getString("failure_code"))
        assertTrue(wire.getString("detail").contains("invalid"))
    }

    @Test
    fun `malformed active does not erase valid durable receipts`() {
        val command = PushProtocol.parseCommand(payload())
        val result = PushProtocol.Result(
            jobId = command.jobId,
            attempt = command.attempt,
            status = "fail",
            destPath = command.destPath,
            failureCode = "client_restarted",
            detail = "worker did not survive",
            completedAt = 1234,
        )
        val receipt = PushProtocol.Receipt(command, result)
        val json = PushProtocol.stateToJson(
            PushProtocol.State(
                active = null,
                pendingResults = listOf(receipt),
                completedReceipts = listOf(receipt),
            )
        ).apply {
            put("active", JSONObject().apply {
                put("command", JSONObject().apply { put("job_id", "not-a-uuid") })
                put("phase", PushProtocol.PHASE_DOWNLOADING)
            })
        }

        val decoded = PushProtocol.stateFromJson(json)

        assertEquals(null, decoded.active)
        assertEquals(listOf(receipt), decoded.pendingResults)
        assertEquals(listOf(receipt), decoded.completedReceipts)
    }
}
