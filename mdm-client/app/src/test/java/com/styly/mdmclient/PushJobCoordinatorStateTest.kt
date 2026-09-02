package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

        var failure: Throwable? = null
        val persisted = persistPushStateBeforePublishing(
            replacement,
            save = { throw IllegalStateException("injected persistence failure") },
            afterPublish = { released = true },
            onFailure = { failure = it },
            publish = { published = it },
        )

        assertFalse(persisted)
        assertTrue(failure is IllegalStateException)
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

        val persisted = persistPushStateBeforePublishing(
            next,
            save = { normalized },
            afterPublish = { publishedBeforeRelease = published === normalized },
            publish = { published = it },
        )

        assertTrue(persisted)
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

    @Test
    fun `reconciliation replays an exact completed receipt after ACK`() {
        val completed = receipt()
        val state = PushProtocol.State(
            active = null,
            pendingResults = emptyList(),
            completedReceipts = listOf(completed),
        )

        val found = findPushReconcileReceipt(state) { command ->
            command.identity == completed.command.identity
        }

        assertEquals(completed, found)
    }

    @Test
    fun `exact permanent resume rejection releases interrupted ownership`() {
        val command = command().copy(revision = 7L)
        val state = PushProtocol.State(
            active = PushProtocol.Active(
                command,
                PushProtocol.PHASE_DOWNLOADING,
                interrupted = true,
                interruptedAt = 123L,
            ),
            pendingResults = emptyList(),
            completedReceipts = emptyList(),
        )

        val settled = applyPushResumeRejectionToState(
            state,
            requireNotNull(command.jobId),
            command.attempt,
            requireNotNull(command.artifactId),
            command.revision,
            "resume_not_authorized",
            "server rejected resume",
            256,
        )

        requireNotNull(settled)
        assertTrue(settled.first.active == null)
        assertTrue(settled.first.pendingResults.isEmpty())
        assertEquals("resume_not_authorized", settled.second.result.failureCode)
        assertEquals(listOf(settled.second), settled.first.completedReceipts)
    }

    @Test
    fun `stale resume rejection cannot release a different interrupted revision`() {
        val command = command().copy(revision = 7L)
        val state = PushProtocol.State(
            active = PushProtocol.Active(command, PushProtocol.PHASE_DOWNLOADING, interrupted = true),
            pendingResults = emptyList(),
            completedReceipts = emptyList(),
        )

        val settled = applyPushResumeRejectionToState(
            state,
            requireNotNull(command.jobId),
            command.attempt,
            requireNotNull(command.artifactId),
            revision = 8L,
            reason = "resume_not_authorized",
            detail = "stale",
            maxReceipts = 256,
        )

        assertTrue(settled == null)
        assertSame(command, state.active?.command)
    }

    @Test
    fun `active reconciliation report proves the exact artifact identity`() {
        val command = command().copy(revision = 7L)
        val active = PushProtocol.Active(
            command,
            PushProtocol.PHASE_DOWNLOADING,
            interrupted = true,
            interruptedAt = 123L,
        )
        val report = buildActivePushReconcileReport(
            PushProtocol.ReconcileIdentity(
                requireNotNull(command.jobId),
                command.attempt,
                command.artifactId,
            ),
            active,
            validatedOffset = 41L,
        )

        assertEquals("PUSH_RECONCILE_REPORT", report.getString("type"))
        assertEquals(command.artifactId, report.getString("artifact_id"))
        assertEquals(command.revision, report.getLong("revision"))
        assertEquals("interrupted", report.getString("status"))
        assertEquals(41L, report.getLong("validated_offset"))
    }

    @Test
    fun `interrupted ownership is retained before the twenty four hour deadline`() {
        val command = command().copy(revision = 7L)
        val state = PushProtocol.State(
            active = PushProtocol.Active(
                command,
                PushProtocol.PHASE_DOWNLOADING,
                interrupted = true,
                interruptedAt = 1_000L,
            ),
            pendingResults = emptyList(),
            completedReceipts = emptyList(),
        )

        val settled = expireInterruptedPushState(
            state,
            now = 1_000L + PushFilesWorker.PARTIAL_RETENTION_MS - 1L,
            retentionMs = PushFilesWorker.PARTIAL_RETENTION_MS,
            maxReceipts = 256,
        )

        assertTrue(settled == null)
        assertSame(command, state.active?.command)
    }

    @Test
    fun `interrupted ownership expires durably at twenty four hours`() {
        val command = command().copy(revision = 7L)
        val state = PushProtocol.State(
            active = PushProtocol.Active(
                command,
                PushProtocol.PHASE_DOWNLOADING,
                interrupted = true,
                interruptedAt = 1_000L,
            ),
            pendingResults = emptyList(),
            completedReceipts = emptyList(),
        )

        val settled = expireInterruptedPushState(
            state,
            now = 1_000L + PushFilesWorker.PARTIAL_RETENTION_MS,
            retentionMs = PushFilesWorker.PARTIAL_RETENTION_MS,
            maxReceipts = 256,
        )

        requireNotNull(settled)
        assertTrue(settled.first.active == null)
        assertEquals("resume_expired", settled.second.result.failureCode)
        assertEquals(listOf(settled.second), settled.first.pendingResults)
        assertEquals(listOf(settled.second), settled.first.completedReceipts)
    }

    @Test
    fun `durable outbox and replay set are bounded with newest receipts retained`() {
        val receipts = List(300) { receipt() }
        val state = PushProtocol.State(
            active = null,
            pendingResults = receipts,
            completedReceipts = receipts,
        )

        val normalized = normalizePushState(state, cutoff = 0, maxReceipts = 256)

        assertEquals(receipts.takeLast(256), normalized.pendingResults)
        assertEquals(receipts.takeLast(256), normalized.completedReceipts)
    }

    @Test
    fun `completed dedupe receipts expire without aging out the pending outbox`() {
        val old = receipt().let { it.copy(result = it.result.copy(completedAt = 99L)) }
        val current = receipt().let { it.copy(result = it.result.copy(completedAt = 100L)) }
        val state = PushProtocol.State(
            active = null,
            pendingResults = listOf(old, current),
            completedReceipts = listOf(old, current),
        )

        val normalized = normalizePushState(state, cutoff = 100L, maxReceipts = 256)

        assertEquals(listOf(old, current), normalized.pendingResults)
        assertEquals(listOf(current), normalized.completedReceipts)
    }

    @Test
    fun `unavailable registration advertises only explicit Push state retry`() {
        val fields = buildPushRegistrationFields(
            PushProtocol.State(null, emptyList(), emptyList()),
            durabilityAvailable = false,
            processInstanceId = UUID.randomUUID().toString(),
            validatedOffset = { 0L },
        )
        val capabilities = fields.getJSONArray("capabilities")

        assertEquals(1, capabilities.length())
        assertEquals(PushProtocol.CAP_PUSH_STATE_RETRY_V1, capabilities.getString(0))
        assertEquals("unavailable", fields.getJSONObject("push_state").getString("status"))
        assertTrue(fields.getJSONObject("push_runtime").isNull("active"))
    }

    @Test
    fun `available registration restores Push capabilities without starting work`() {
        val interrupted = PushProtocol.Active(
            command(),
            PushProtocol.PHASE_DOWNLOADING,
            interrupted = true,
            interruptedAt = 1_000L,
        )
        val fields = buildPushRegistrationFields(
            PushProtocol.State(interrupted, emptyList(), emptyList()),
            durabilityAvailable = true,
            processInstanceId = UUID.randomUUID().toString(),
            validatedOffset = { 12L },
        )
        val capabilities = fields.getJSONArray("capabilities")
        val active = fields.getJSONObject("push_runtime").getJSONObject("active")

        assertEquals(3, capabilities.length())
        assertEquals("available", fields.getJSONObject("push_state").getString("status"))
        assertEquals("interrupted", active.getString("status"))
        assertEquals(12L, active.getLong("validated_offset"))
    }

    @Test
    fun `interrupted expiry recomputes a full delay after wall clock rollback`() {
        val active = PushProtocol.Active(
            command(),
            PushProtocol.PHASE_DOWNLOADING,
            interrupted = true,
            interruptedAt = 10_000L,
        )

        assertEquals(
            PushFilesWorker.PARTIAL_RETENTION_MS,
            interruptedExpiryDelayMillis(
                active,
                now = 5_000L,
                retentionMs = PushFilesWorker.PARTIAL_RETENTION_MS,
            ),
        )
        assertEquals(
            PushFilesWorker.PARTIAL_RETENTION_MS - 1_000L,
            interruptedExpiryDelayMillis(
                active,
                now = 11_000L,
                retentionMs = PushFilesWorker.PARTIAL_RETENTION_MS,
            ),
        )
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
