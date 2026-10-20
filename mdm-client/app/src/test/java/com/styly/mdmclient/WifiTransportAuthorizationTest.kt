package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiTransportAuthorizationTest {
    private val manualWsUrl = "ws://mdm.local:7070/ws/device"
    private val manualWssUrl = "wss://mdm.local:7070/ws/device"

    @Test
    fun `manual ws endpoint with TLS disabled may send token and enable wifi`() {
        val settings = WifiConnectionSettings(tlsEnabled = false, deviceToken = "device-token")

        assertTrue(WebSocketManager.isTrustedManualWifiEndpoint(manualWsUrl, manualWsUrl, false, true))
        assertTrue(WifiTransportAuthorization.isAuthorizedSession(manualWsUrl, true, settings))
        assertEquals("device-token", WifiTransportAuthorization.authorizationTokenFor(manualWsUrl, true, settings))
    }

    @Test
    fun `discovered or cached endpoints never receive token or wifi authorization`() {
        val settings = WifiConnectionSettings(tlsEnabled = false, deviceToken = "device-token")

        assertFalse(WebSocketManager.isTrustedManualWifiEndpoint(manualWsUrl, manualWsUrl, false, false))
        assertFalse(WifiTransportAuthorization.isAuthorizedSession(manualWsUrl, false, settings))
        assertEquals(null, WifiTransportAuthorization.authorizationTokenFor(manualWsUrl, false, settings))

        val tlsSettings = settings.copy(tlsEnabled = true)
        assertFalse(WebSocketManager.isTrustedManualWifiEndpoint(manualWsUrl, manualWssUrl, true, false))
        assertFalse(WifiTransportAuthorization.isAuthorizedSession(manualWssUrl, false, tlsSettings))
        assertEquals(null, WifiTransportAuthorization.authorizationTokenFor(manualWssUrl, false, tlsSettings))
    }

    @Test
    fun `manual endpoint protocol must match TLS setting`() {
        val tlsOff = WifiConnectionSettings(tlsEnabled = false, deviceToken = "device-token")
        val tlsOn = tlsOff.copy(tlsEnabled = true)

        assertFalse(WebSocketManager.isTrustedManualWifiEndpoint(manualWsUrl, manualWssUrl, false, true))
        assertFalse(WifiTransportAuthorization.isAuthorizedSession(manualWssUrl, true, tlsOff))
        assertFalse(WifiTransportAuthorization.isAuthorizedSession(manualWsUrl, true, tlsOn))

        assertTrue(WebSocketManager.isTrustedManualWifiEndpoint(manualWsUrl, manualWssUrl, true, true))
        assertTrue(WifiTransportAuthorization.isAuthorizedSession(manualWssUrl, true, tlsOn))
    }
}
