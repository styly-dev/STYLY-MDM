package com.styly.mdmclient

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
}
