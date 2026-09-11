package com.styly.mdmclient

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRegistrationTest {
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
