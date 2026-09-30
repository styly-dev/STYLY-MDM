package com.styly.mdmclient

import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class PowerCommandContextTest {
    private class FakeSocket : WebSocket {
        var queued = 0L
        var onQueue: () -> Unit = {}
        var acceptsSend = true
        val messages = mutableListOf<String>()
        override fun request() = Request.Builder().url("http://localhost/").build()
        override fun queueSize(): Long { onQueue(); return queued }
        override fun send(text: String): Boolean {
            if (acceptsSend) messages.add(text)
            return acceptsSend
        }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() {}
    }

    private class Fixture(provisional: Boolean) {
        val socket = FakeSocket()
        var current: WebSocket? = socket
        val registration = ConnectionRegistration<WebSocket>()
        val command: WebSocketManager.CommandContext
        init {
            registration.open(socket)
            if (provisional) registration.acknowledgeProvisional(socket, "connection-1")
            else { registration.markSent(socket); registration.acknowledge(socket) }
            command = WebSocketManager.CommandContext(
                socket, if (provisional) "connection-1" else null, registration,
            ) { current }
        }
    }

    @Test fun activeTimeoutAndDrainedQueueBothAllowExecution() {
        for (provisional in listOf(false, true)) {
            val f = Fixture(provisional)
            f.socket.queued = 128
            assertTrue(f.command.awaitOutboundFlush(0))
            assertTrue(f.command.sendMessage("accepted"))
            assertEquals(listOf("accepted"), f.socket.messages)
            f.socket.queued = 0
            assertTrue(f.command.awaitOutboundFlush(100))
        }
    }

    @Test fun disconnectedOrReplacedConnectionCannotSendOrExecute() {
        for (provisional in listOf(false, true)) {
            val f = Fixture(provisional)
            f.current = null
            assertFalse(f.command.isActive())
            assertFalse(f.command.sendMessage("accepted"))
            assertFalse(f.command.awaitOutboundFlush(0))
            val replacement = FakeSocket()
            f.current = replacement
            f.registration.open(replacement)
            f.registration.markSent(replacement)
            f.registration.acknowledge(replacement)
            assertFalse(f.command.sendMessage("fail"))
            assertFalse(f.command.awaitOutboundFlush(100))
            assertTrue(f.socket.messages.isEmpty())
            assertTrue(replacement.messages.isEmpty())
        }
    }

    @Test fun canonicalPromotionInvalidatesProvisionalCommandOnSameSocket() {
        val f = Fixture(true)
        f.registration.markSent(f.socket)
        f.registration.acknowledge(f.socket)
        assertFalse(f.command.isActive())
        assertFalse(f.command.sendMessage("accepted"))
        assertFalse(f.command.awaitOutboundFlush(0))
        assertTrue(f.socket.messages.isEmpty())
    }

    @Test fun invalidationDuringQueueWaitCancelsExecution() {
        val f = Fixture(true)
        f.socket.queued = 128
        f.socket.onQueue = { f.registration.clear() }
        assertFalse(f.command.awaitOutboundFlush(1_000))
        assertFalse(f.command.sendMessage("fail"))
    }

    @Test fun rejectedSendDoesNotReportSuccess() {
        val f = Fixture(false)
        f.socket.acceptsSend = false
        assertFalse(f.command.sendMessage("accepted"))
        assertTrue(f.socket.messages.isEmpty())
    }
}
