package com.styly.mdmclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WebSocketMessageLoggingTest {
    @Test fun `push command summary excludes lease and other payload fields`() {
        val message = JSONObject()
            .put("type", "EXECUTE_PUSH_FILES")
            .put("artifact_url", "http://server/artifacts/example?lease=private-token")
            .put("detail", "private payload")

        assertEquals("EXECUTE_PUSH_FILES", messageTypeForLog(message))
    }

    @Test fun `unknown or malformed type never becomes arbitrary log content`() {
        listOf<Any>("lease=private-token", "REGISTERED\nprivate-token", "A".repeat(65), 1, JSONObject.NULL)
            .forEach { type ->
                assertEquals("UNKNOWN", messageTypeForLog(JSONObject().put("type", type)))
            }
        assertEquals("UNKNOWN", messageTypeForLog(JSONObject()))
    }
}
