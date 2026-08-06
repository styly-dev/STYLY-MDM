package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerEndpointTest {

    @Test
    fun addressIsExpandedToDeviceWebSocketUrl() {
        assertEquals(
            "ws://192.168.1.42:7080/ws/device",
            WebSocketManager.normalizeManualServerUrl(" 192.168.1.42:7080 "),
        )
    }

    @Test
    fun hostnameIsExpandedToDeviceWebSocketUrl() {
        assertEquals(
            "ws://mdm.local:7080/ws/device",
            WebSocketManager.normalizeManualServerUrl("mdm.local:7080"),
        )
    }

    @Test
    fun hostnameIsNormalizedToLowercase() {
        assertEquals(
            "ws://mdm.local:7080/ws/device",
            WebSocketManager.normalizeManualServerUrl("MDM.local:7080"),
        )
    }

    @Test
    fun portIsRequiredForAddressInput() {
        assertNull(WebSocketManager.normalizeManualServerUrl("192.168.1.42"))
    }

    @Test
    fun pathAndSchemeAreRejectedForAddressInput() {
        assertNull(
            WebSocketManager.normalizeManualServerUrl("192.168.1.42:7080/ws/device"),
        )
        assertNull(
            WebSocketManager.normalizeManualServerUrl("ws://192.168.1.42:7080/ws/device"),
        )
        assertNull(
            WebSocketManager.normalizeManualServerUrl("wss://192.168.1.42:7080/ws/device"),
        )
    }

    @Test
    fun malformedAddressPartsAreRejected() {
        assertNull(WebSocketManager.normalizeManualServerUrl("192.168.1.+5:7080"))
        assertNull(WebSocketManager.normalizeManualServerUrl("192.168.1.42:+7080"))
    }

    @Test
    fun serverAddressOmitsSchemeAndPath() {
        assertEquals(
            "192.168.1.42:7080",
            WebSocketManager.serverAddress("ws://192.168.1.42:7080/ws/device"),
        )
    }
}
