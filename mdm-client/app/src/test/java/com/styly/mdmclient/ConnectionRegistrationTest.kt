package com.styly.mdmclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRegistrationTest {
    @Test fun `current Wi-Fi snapshot omits unknown SSID and redacted BSSID`() {
        assertEquals(null, currentWifiSnapshot("<unknown ssid>", "02:00:00:00:00:00"))
        val spaces = currentWifiSnapshot("\"   \"", "aa:bb:cc:dd:ee:ff")!!
        assertEquals("   ", spaces.getString("ssid"))
        assertEquals("aa:bb:cc:dd:ee:ff", spaces.getString("bssid"))
        val snapshot = currentWifiSnapshot("\"Office\"", "02:00:00:00:00:00")!!
        assertEquals("Office", snapshot.getString("ssid"))
        assertTrue(snapshot.isNull("bssid"))
        assertEquals("aa:bb:cc:dd:ee:ff", currentWifiSnapshot("Guest", "AA:BB:CC:DD:EE:FF")!!
            .getString("bssid"))
        assertEquals("00:11:22:33:44:55", currentWifiSnapshot("00:11:22:33:44:55", "00:11:22:33:44:55")!!
            .getString("bssid"))
        assertEquals(" Guest ", currentWifiSnapshot("\" Guest \"", "aa:bb:cc:dd:ee:ff")!!
            .getString("ssid"))
    }

    @Test fun `registration suppresses unchanged Wi-Fi reports and a failed update can be sent again`() {
        val socket = Any()
        val report = SocketStateReport<Any>("wifi_state", "WIFI_STATE")
        val initial = JSONObject().put("busy", false)
        report.recordRegistration(socket, JSONObject().put("wifi_state", initial))
        report.publish(socket, initial) { error("Duplicate Wi-Fi state") }

        val latest = JSONObject().put("busy", true)
        val messages = mutableListOf<String>()
        report.publish(socket, latest) {
            messages.add(it)
            false
        }
        report.publish(socket, latest) { messages.add(it) }
        assertEquals(2, messages.size)
        assertEquals(messages.first(), messages.last())
        val update = JSONObject(messages.last())
        assertEquals("WIFI_STATE", update.getString("type"))
        assertTrue(update.getJSONObject("wifi_state").getBoolean("busy"))
        report.publish(socket, latest) { error("Duplicate Wi-Fi update") }

        val nextSocket = Any()
        report.recordRegistration(nextSocket, JSONObject().put("type", "REGISTER"))
        report.publish(nextSocket, latest) { messages.add(it) }
        assertEquals(3, messages.size)
    }

    @Test fun provisionalAckCannotDowngradeCanonicalRegistration() {
        val state = ConnectionRegistration<Any>()
        val socket = Any()
        state.open(socket)
        state.markSent(socket)
        assertTrue(state.acknowledge(socket))
        assertFalse(state.acknowledgeProvisional(socket, "late-connection"))
        assertTrue(state.isCanonicalAcknowledged(socket))
        assertFalse(state.isProvisional(socket))
        assertTrue(state.provisionalConnectionId(socket) == null)
    }

    @Test fun staleAckCannotAcknowledgeReplacementEvenAfterItSendsRegister() {
        val state = ConnectionRegistration<Any>()
        val old = Any()
        val current = Any()
        state.open(old)
        state.markSent(old)
        // The old reader passed its outer identity check before cancellation.
        state.clear()
        state.open(current)
        assertFalse(state.acknowledge(old))
        assertFalse(state.isAcknowledged(current))
        state.markSent(current)
        assertFalse(state.acknowledge(old))
        assertFalse(state.isAcknowledged(current))
        assertTrue(state.acknowledge(current))
        assertTrue(state.isAcknowledged(current))
        assertFalse(state.isAcknowledged(old))
    }

    @Test fun provisionalAndCancelledSocketsCannotAcknowledge() {
        val state = ConnectionRegistration<Any>()
        val socket = Any()
        state.open(socket)
        assertFalse(state.acknowledge(socket))
        state.markSent(socket)
        state.clear()
        assertFalse(state.acknowledge(socket))
        assertFalse(state.isAcknowledged(socket))
    }

    @Test fun staleSendFailureDoesNotClearReplacementRegistration() {
        val state = ConnectionRegistration<Any>()
        val old = Any()
        val current = Any()
        state.open(old)
        state.markSent(old)
        state.open(current)
        state.markSent(current)
        assertTrue(state.acknowledge(current))
        state.clearSent(old)
        assertTrue(state.isAcknowledged(current))
        state.clearSent(current)
        assertFalse(state.isSent(current))
        assertFalse(state.isAcknowledged(current))
    }

    @Test fun provisionalAckBindsConnectionIdAndCanonicalPromotionClearsIt() {
        val state = ConnectionRegistration<Any>()
        val socket = Any()

        state.open(socket)
        assertTrue(state.acknowledgeProvisional(socket, "connection-1"))
        assertTrue(state.isAcknowledged(socket))
        assertTrue(state.isProvisional(socket))
        assertFalse(state.isCanonicalAcknowledged(socket))
        assertTrue(state.provisionalConnectionId(socket) == "connection-1")

        state.markSent(socket)
        assertTrue(state.acknowledge(socket))
        assertTrue(state.isCanonicalAcknowledged(socket))
        assertFalse(state.isProvisional(socket))
        assertTrue(state.provisionalConnectionId(socket) == null)
    }

    @Test fun provisionalAckRejectsBlankOrStaleConnectionIds() {
        val state = ConnectionRegistration<Any>()
        val old = Any()
        val current = Any()

        state.open(old)
        assertFalse(state.acknowledgeProvisional(old, ""))
        state.clear()
        state.open(current)
        assertFalse(state.acknowledgeProvisional(old, "connection-1"))
        assertFalse(state.isAcknowledged(current))
        assertTrue(state.acknowledgeProvisional(current, "connection-1"))
        assertFalse(state.acknowledgeProvisional(current, ""))
        assertTrue(state.provisionalConnectionId(current) == "connection-1")
    }
}
