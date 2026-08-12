package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PushJobCoordinatorStateTest {
    private fun command() = PushProtocol.Command(
        jobId = UUID.randomUUID().toString(),
        attempt = PushProtocol.ATTEMPT_V1,
        artifactId = UUID.randomUUID().toString(),
        artifactUrl = "http://server/artifacts/value",
        artifactSize = 42,
        artifactSha256 = "a".repeat(64),
        bundleFilename = "bundle.zip",
        destPath = "/sdcard/STYLY/content",
        deleteExtras = false,
    )

    @Test
    fun `failed persistence neither publishes replacement state nor releases the lease`() {
        val previous = PushProtocol.State(
            active = null,
            pendingResults = emptyList(),
            completedReceipts = emptyList(),
        )
        val replacement = previous.copy(
            active = PushProtocol.Active(command(), PushProtocol.PHASE_DOWNLOADING),
        )
        var published = previous
        var released = false

        assertThrows(IllegalStateException::class.java) {
            persistPushStateBeforePublishing(
                replacement,
                save = { throw IllegalStateException("injected persistence failure") },
                afterPublish = { released = true },
                publish = { published = it },
            )
        }

        assertSame(previous, published)
        assertFalse(released)
    }

    @Test
    fun `successful persistence publishes normalized state before releasing the lease`() {
        val next = PushProtocol.State(
            active = null,
            pendingResults = emptyList(),
            completedReceipts = emptyList(),
        )
        val normalized = next.copy(completedReceipts = emptyList())
        var published: PushProtocol.State? = null
        var publishedBeforeRelease = false

        persistPushStateBeforePublishing(
            next,
            save = { normalized },
            afterPublish = { publishedBeforeRelease = published === normalized },
            publish = { published = it },
        )

        assertSame(normalized, published)
        assertTrue(publishedBeforeRelease)
    }

    @Test
    fun `accepted ACK removes outbox and retains one completed receipt`() {
        val receipt = receipt()
        val state = stateWithPending(receipt)

        val next = applyPushResultAckToState(
            state,
            ack(receipt, accepted = true, retryable = false),
            maxReceipts = 256,
        )

        assertTrue(next.pendingResults.isEmpty())
        assertEquals(listOf(receipt), next.completedReceipts)
    }

    @Test
    fun `permanent rejected ACK settles outbox and retains dedupe receipt`() {
        val receipt = receipt()
        val state = stateWithPending(receipt)

        val next = applyPushResultAckToState(
            state,
            ack(receipt, accepted = false, retryable = false),
            maxReceipts = 256,
        )

        assertTrue(next.pendingResults.isEmpty())
        assertEquals(listOf(receipt), next.completedReceipts)
    }

    @Test
    fun `retryable rejected ACK keeps durable outbox unchanged`() {
        val receipt = receipt()
        val state = stateWithPending(receipt)

        val next = applyPushResultAckToState(
            state,
            ack(receipt, accepted = false, retryable = true),
            maxReceipts = 256,
        )

        assertSame(state, next)
    }

    @Test
    fun `settled receipt is moved to end within retention cap without duplication`() {
        val settled = receipt()
        val older = receipt()
        val state = PushProtocol.State(
            active = null,
            pendingResults = listOf(settled),
            completedReceipts = listOf(settled, older),
        )

        val next = applyPushResultAckToState(
            state,
            ack(settled, accepted = true, retryable = false),
            maxReceipts = 2,
        )

        assertEquals(listOf(older, settled), next.completedReceipts)
    }

    private fun receipt(): PushProtocol.Receipt {
        val command = command()
        return PushProtocol.Receipt(
            command,
            PushProtocol.Result(
                jobId = command.jobId,
                attempt = command.attempt,
                status = "success",
                destPath = command.destPath,
            ),
        )
    }

    private fun stateWithPending(receipt: PushProtocol.Receipt) = PushProtocol.State(
        active = null,
        pendingResults = listOf(receipt),
        completedReceipts = listOf(receipt),
    )

    private fun ack(
        receipt: PushProtocol.Receipt,
        accepted: Boolean,
        retryable: Boolean,
    ) = PushProtocol.ResultAck(
        jobId = requireNotNull(receipt.result.jobId),
        attempt = receipt.result.attempt,
        accepted = accepted,
        retryable = retryable,
        reason = null,
    )
}
