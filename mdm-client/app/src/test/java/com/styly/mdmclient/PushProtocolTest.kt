package com.styly.mdmclient

import org.json.JSONArray
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
        put("revision", 1)
        put("artifact_etag", "\"v1\"")
        put("dest_path", "/sdcard/STYLY/content")
    }

    @Test
    fun `job v1 requires attempt one and complete artifact identity`() {
        val value = payload()
        val command = PushProtocol.parseWireCommand(value)
        assertEquals(1, command.attempt)
        assertNotNull(command.artifactId)

        value.put("attempt", 2)
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseWireCommand(value)
        }
    }

    @Test
    fun `live job v1 requires artifact url even when bundle url is present`() {
        val value = payload().apply { remove("artifact_url") }
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseWireCommand(value)
        }

        value.put("bundle_url", "http://server/legacy.zip")
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseWireCommand(value)
        }

        value.put("artifact_url", "http://server/artifacts/value")
        assertEquals("http://server/artifacts/value", PushProtocol.parseWireCommand(value).artifactUrl)
        value.put("bundle_url", "http://server/legacy.zip")
        assertEquals("http://server/artifacts/value", PushProtocol.parseWireCommand(value).artifactUrl)
    }

    @Test
    fun `live wire rejects identity-less legacy command`() {
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseWireCommand(
                JSONObject().apply {
                    put("bundle_url", "http://server/legacy.zip")
                    put("dest_path", "/sdcard/STYLY/content")
                },
            )
        }
    }

    @Test
    fun `stored legacy command remains readable`() {
        val command = PushProtocol.parseStoredCommand(
            JSONObject().apply {
                put("bundle_url", "http://server/legacy.zip")
                put("dest_path", "/sdcard/STYLY/content")
            },
        )

        assertEquals("http://server/legacy.zip", command.artifactUrl)
        assertEquals(null, command.jobId)
    }

    @Test
    fun `stored job-v1 commands require complete artifact identity and artifact url`() {
        val invalidRecords = listOf(
            payload().apply { remove("attempt") },
            payload().apply { remove("artifact_id") },
            payload().apply { remove("artifact_size") },
            payload().apply { remove("artifact_sha256") },
            payload().apply {
                remove("artifact_url")
                put("bundle_url", "http://server/legacy.zip")
            },
        )

        invalidRecords.forEach { record ->
            assertThrows(IllegalArgumentException::class.java) {
                PushProtocol.parseStoredCommand(record)
            }
        }
    }

    @Test
    fun `job v1 rejects relative artifact URL and non-v4 UUID`() {
        val value = payload().apply { put("artifact_url", "/artifacts/value") }
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseWireCommand(value)
        }
        value.put("artifact_url", "http://server/artifacts/value")
        value.put("job_id", "00000000-0000-1000-8000-000000000000")
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseWireCommand(value)
        }
    }

    @Test
    fun `missing delete extras remains safe push`() {
        assertFalse(PushProtocol.parseWireCommand(payload()).deleteExtras)
    }

    @Test
    fun `durable receipt preserves failure code detail and completion time`() {
        val command = PushProtocol.parseWireCommand(payload())
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
    fun `malformed active invalidates the durable snapshot even when receipts are valid`() {
        val command = PushProtocol.parseWireCommand(payload())
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

        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.stateFromJson(json)
        }
    }

    @Test
    fun `interrupted retention timestamp survives state round trip`() {
        val command = PushProtocol.parseWireCommand(payload())
        val state = PushProtocol.State(
            active = PushProtocol.Active(
                command,
                PushProtocol.PHASE_DOWNLOADING,
                interrupted = true,
                retention = PushProtocol.Retention(PushFilesWorker.PARTIAL_RETENTION_MS, 1, 123456L),
                interruptionReason = "download_interrupted",
            ),
            pendingResults = emptyList(),
            completedReceipts = emptyList(),
        )

        val decoded = PushProtocol.stateFromJson(PushProtocol.stateToJson(state))

        assertTrue(decoded.active?.interrupted == true)
        assertEquals(PushProtocol.Retention(PushFilesWorker.PARTIAL_RETENTION_MS, 1, 123456L), decoded.active?.retention)
        assertEquals("download_interrupted", decoded.active?.interruptionReason)
    }

    @Test
    fun `durable issue 91 active command without revision remains readable`() {
        val command = PushProtocol.parseWireCommand(payload()).toJson().apply { remove("revision") }
        val state = JSONObject().apply {
            put("active", JSONObject().apply {
                put("command", command)
                put("phase", PushProtocol.PHASE_DOWNLOADING)
            })
            put("pending_results", JSONArray())
            put("completed_receipts", JSONArray())
        }

        val decoded = PushProtocol.stateFromJson(state)

        assertEquals(0L, decoded.active?.command?.revision)
    }

    @Test
    fun `wire and stored command readers keep separate compatibility rules`() {
        val issue91 = payload().apply {
            remove("revision")
            remove("artifact_etag")
        }

        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseWireCommand(issue91)
        }
        val stored = PushProtocol.parseStoredCommand(issue91)
        assertEquals(0L, stored.revision)
        assertEquals(null, stored.artifactEtag)
    }

    @Test
    fun `execution fingerprint allows locator refresh and compares every authorization field`() {
        val command = PushProtocol.parseWireCommand(payload())
        assertTrue(command.sameExecution(command.copy(artifactUrl = "http://server/new-lease")))

        val changes = listOf(
            command.copy(jobId = UUID.randomUUID().toString()),
            command.copy(attempt = 2),
            command.copy(artifactId = UUID.randomUUID().toString()),
            command.copy(artifactSize = command.artifactSize!! + 1),
            command.copy(artifactSha256 = "b".repeat(64)),
            command.copy(bundleFilename = "other.zip"),
            command.copy(destPath = "/sdcard/STYLY/other"),
            command.copy(deleteExtras = true),
            command.copy(revision = command.revision + 1),
            command.copy(artifactEtag = "\"v2\""),
        )

        changes.forEach { assertFalse(command.sameExecution(it)) }
    }
}
