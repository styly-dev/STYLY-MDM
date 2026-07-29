package com.styly.mdmclient

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PushExecutionGateTest {
    private fun command(
        jobId: String? = UUID.randomUUID().toString(),
        artifactId: String? = UUID.randomUUID().toString(),
        dest: String = "/sdcard/STYLY/content",
    ) = PushProtocol.Command(
        jobId = jobId,
        attempt = 1,
        artifactId = artifactId,
        artifactUrl = "http://server/artifact.zip",
        artifactSize = 10,
        artifactSha256 = "a".repeat(64),
        bundleFilename = "content.zip",
        destPath = dest,
        deleteExtras = false,
    )

    @Test
    fun `same exact job is duplicate without replacing active execution`() {
        val gate = PushExecutionGate()
        val value = command()
        assertTrue(gate.offer(value) is PushExecutionGate.Decision.Accepted)
        assertTrue(gate.offer(value.copy()) is PushExecutionGate.Decision.Duplicate)
        assertTrue(gate.current() === value)
    }

    @Test
    fun `same identity with different artifact is rejected as conflict`() {
        val gate = PushExecutionGate()
        val value = command()
        gate.offer(value)
        assertTrue(
            gate.offer(value.copy(artifactId = UUID.randomUUID().toString()))
                is PushExecutionGate.Decision.Conflict
        )
    }

    @Test
    fun `legacy command never guesses duplicate identity`() {
        val gate = PushExecutionGate()
        val first = command(jobId = null, artifactId = null)
        gate.offer(first)
        assertTrue(gate.offer(first.copy()) is PushExecutionGate.Decision.Busy)
    }

    @Test
    fun `release requires exact active identity`() {
        val gate = PushExecutionGate()
        val first = command()
        val other = command()
        gate.offer(first)
        assertTrue(!gate.release(other))
        assertTrue(gate.release(first))
        assertTrue(gate.current() == null)
    }
}
