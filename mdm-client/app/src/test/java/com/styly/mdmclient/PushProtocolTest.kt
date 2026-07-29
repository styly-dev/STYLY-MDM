package com.styly.mdmclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PushProtocolTest {
    @Test
    fun `job v1 requires attempt one and artifact identity`() {
        val payload = JSONObject().apply {
            put("job_id", UUID.randomUUID().toString())
            put("attempt", 1)
            put("artifact_id", UUID.randomUUID().toString())
            put("artifact_url", "http://server/artifact")
            put("artifact_size", 42)
            put("artifact_sha256", "a".repeat(64))
            put("dest_path", "/sdcard/STYLY/content")
        }
        val command = PushProtocol.parseCommand(payload)
        assertEquals(1, command.attempt)
        assertNotNull(command.artifactId)

        payload.put("attempt", 2)
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseCommand(payload)
        }
    }

    @Test
    fun `durable receipt preserves command used for dedupe`() {
        val command = PushProtocol.Command(
            UUID.randomUUID().toString(), 1, UUID.randomUUID().toString(),
            "http://server/artifact", 42, "a".repeat(64), "x.zip",
            "/sdcard/STYLY/content", false,
        )
        val result = PushProtocol.Result(
            command.jobId, 1, "success", command.destPath, 1, 2, 0, ""
        )
        val state = PushProtocol.State(
            active = null,
            pendingResults = listOf(PushProtocol.Receipt(command, result)),
            completedReceipts = listOf(PushProtocol.Receipt(command, result)),
        )
        val decoded = PushProtocol.stateFromJson(PushProtocol.stateToJson(state))
        assertEquals(command, decoded.pendingResults.single().command)
        assertEquals(result, decoded.completedReceipts.single().result)
    }


    @Test
    fun `destination is canonicalized and destructive roots are rejected`() {
        val base = JSONObject().apply {
            put("job_id", UUID.randomUUID().toString())
            put("attempt", 1)
            put("artifact_id", UUID.randomUUID().toString())
            put("artifact_url", "http://192.0.2.1:7070/artifacts/example")
            put("artifact_size", 42)
            put("artifact_sha256", "a".repeat(64))
            put("dest_path", "/storage/emulated/0/STYLY/./content")
        }
        assertEquals("/sdcard/STYLY/content", PushProtocol.parseCommand(base).destPath)
        base.put("dest_path", "/sdcard/Download")
        assertThrows(IllegalArgumentException::class.java) { PushProtocol.parseCommand(base) }
        base.put("dest_path", "/sdcard/STYLY/../Download")
        assertThrows(IllegalArgumentException::class.java) { PushProtocol.parseCommand(base) }
    }

    @Test
    fun `job v1 rejects relative artifact URL and missing sha256`() {
        val payload = JSONObject().apply {
            put("job_id", UUID.randomUUID().toString())
            put("attempt", 1)
            put("artifact_id", UUID.randomUUID().toString())
            put("artifact_url", "/artifacts/example")
            put("artifact_size", 42)
            put("artifact_sha256", "a".repeat(64))
            put("dest_path", "/sdcard/STYLY/content")
        }
        assertThrows(IllegalArgumentException::class.java) { PushProtocol.parseCommand(payload) }
        payload.put("artifact_url", "http://192.0.2.1:7070/artifacts/example")
        payload.remove("artifact_sha256")
        val error = assertThrows(IllegalArgumentException::class.java) { PushProtocol.parseCommand(payload) }
        assertTrue(error.message!!.contains("artifact_sha256"))
    }
}
