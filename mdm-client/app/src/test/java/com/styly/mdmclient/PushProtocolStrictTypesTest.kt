package com.styly.mdmclient

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PushProtocolStrictTypesTest {
    private fun payload() = JSONObject().apply {
        put("job_id", UUID.randomUUID().toString())
        put("attempt", 1)
        put("artifact_id", UUID.randomUUID().toString())
        put("artifact_url", "http://server/artifacts/value")
        put("artifact_size", 42L)
        put("artifact_sha256", "a".repeat(64))
        put("revision", 1L)
        put("dest_path", "/sdcard/STYLY/content")
    }

    @Test
    fun `delete extras accepts only a JSON boolean`() {
        assertFalse(PushProtocol.parseCommand(payload()).deleteExtras)
        assertTrue(
            PushProtocol.parseCommand(payload().apply { put("delete_extras", true) })
                .deleteExtras,
        )
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseCommand(payload().apply { put("delete_extras", "true") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseCommand(payload().apply { put("delete_extras", 1) })
        }
    }

    @Test
    fun `attempt and artifact size reject string coercion`() {
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseCommand(payload().apply { put("attempt", "1") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseCommand(payload().apply { put("artifact_size", "42") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseCommand(payload().apply { put("artifact_size", 42.0) })
        }
    }

    @Test
    fun `job-v1 accepts issue 91 missing revision but rejects wrong types and weak etag`() {
        assertTrue(
            PushProtocol.parseCommand(payload().apply { remove("revision") }).revision == 0L,
        )
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseCommand(payload().apply { put("revision", "1") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseCommand(payload().apply { put("artifact_etag", "W/\"weak\"") })
        }
        assertTrue(
            PushProtocol.parseCommand(payload().apply { put("artifact_etag", "\"strong\"") })
                .artifactEtag == "\"strong\"",
        )
    }

    @Test
    fun `identity url and destination require actual strings`() {
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseCommand(payload().apply { put("job_id", 1) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseCommand(payload().apply { put("artifact_url", true) })
        }
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseCommand(payload().apply { put("dest_path", 123) })
        }
    }

    @Test
    fun `result ACK requires exact JSON types`() {
        val jobId = UUID.randomUUID().toString()
        val valid = JSONObject().apply {
            put("job_id", jobId)
            put("attempt", 1)
            put("accepted", true)
        }
        val parsed = PushProtocol.parseResultAck(valid)
        assertTrue(parsed.accepted)
        assertFalse(parsed.retryable)

        val legacyRejected = PushProtocol.parseResultAck(
            JSONObject(valid.toString()).apply { put("accepted", false) },
        )
        assertTrue(legacyRejected.retryable)

        val permanentRejected = PushProtocol.parseResultAck(
            JSONObject(valid.toString()).apply {
                put("accepted", false)
                put("retryable", false)
                put("reason", "malformed_terminal_result")
            },
        )
        assertFalse(permanentRejected.retryable)
        assertTrue(permanentRejected.reason == "malformed_terminal_result")

        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseResultAck(JSONObject(valid.toString()).apply {
                put("accepted", "true")
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseResultAck(JSONObject(valid.toString()).apply {
                put("attempt", "1")
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseResultAck(JSONObject(valid.toString()).apply {
                put("job_id", 1)
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseResultAck(JSONObject(valid.toString()).apply {
                put("retryable", "false")
            })
        }
    }

    @Test
    fun `reconcile identity validates artifact and numeric attempt`() {
        val valid = JSONObject().apply {
            put("job_id", UUID.randomUUID().toString())
            put("attempt", 1)
            put("artifact_id", UUID.randomUUID().toString())
        }
        val parsed = PushProtocol.parseReconcileIdentity(valid)
        assertTrue(parsed.artifactId != null)

        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseReconcileIdentity(JSONObject(valid.toString()).apply {
                put("attempt", "1")
            })
        }
        assertThrows(IllegalArgumentException::class.java) {
            PushProtocol.parseReconcileIdentity(JSONObject(valid.toString()).apply {
                put("artifact_id", "not-a-uuid")
            })
        }
    }

}
