package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PushJobCoordinatorStateTest {
    @Test
    fun `restart reports manual resume reason without renewing retention`() {
        val active = PushProtocol.Active(command().copy(revision = 7L), PushProtocol.PHASE_DOWNLOADING)
        val recovered = interruptPushAfterRestart(active, PushRetentionClock(1, 1_000L))
        val report = buildActivePushReconcileReport(
            PushProtocol.ReconcileIdentity(requireNotNull(active.command.jobId), active.command.attempt, active.command.artifactId),
            recovered, 0L,
        )
        assertEquals("interrupted", report.getString("status"))
        assertEquals("client_restarted", report.getString("reason"))
        assertEquals(
            PushProtocol.Retention.DURATION_MS - 9_000L,
            interruptPushAfterRestart(recovered, PushRetentionClock(1, 10_000L)).retention?.remainingMs,
        )
        val timedOut = recovered.copy(interruptionReason = "download_interrupted")
        val restarted = interruptPushAfterRestart(timedOut, PushRetentionClock(1, 20_000L))
        assertEquals("download_interrupted", restarted.interruptionReason)
        assertEquals(PushProtocol.Retention.DURATION_MS - 19_000L, restarted.retention?.remainingMs)
        val restartedReport = buildActivePushReconcileReport(
            PushProtocol.ReconcileIdentity(requireNotNull(active.command.jobId), active.command.attempt, active.command.artifactId),
            restarted, 0L,
        )
        assertEquals("download_interrupted", restartedReport.getString("reason"))
    }

    @Test
    fun `recovery reloads only before state was adopted and saves an unsaved terminal as is`() {
        assertEquals(
            PushStateRecoveryAction.Reload,
            decidePushStateRecovery(stateLoaded = false, hasUnsavedTerminal = false),
        )
        // A finished worker whose outcome was not saved must be saved from memory, never
        // reloaded from disk where recovery would make it resumable again.
        assertEquals(
            PushStateRecoveryAction.SaveUnsavedTerminal,
            decidePushStateRecovery(stateLoaded = true, hasUnsavedTerminal = true),
        )
        // Once adopted, the in-memory state equals the last successful save; a retry only
        // re-saves it (also while a worker runs) and never reloads the file.
        assertEquals(
            PushStateRecoveryAction.ResaveCurrent,
            decidePushStateRecovery(stateLoaded = true, hasUnsavedTerminal = false),
        )
    }

    @Test
    fun `recovery budget allows a bounded number of attempts per incident`() {
        val budget = PushStateRecoveryBudget(maxAttempts = 5)
        assertEquals(PushStateRecoveryBudget.Phase.Healthy, budget.phase)

        assertTrue(budget.onFailure())
        assertEquals(PushStateRecoveryBudget.Phase.Recovering, budget.phase)
        // Further failures during an open incident do not schedule a second timer.
        assertFalse(budget.onFailure())
        repeat(4) { assertTrue(budget.onAttemptFailed()) }
        assertFalse(budget.onAttemptFailed())
        assertEquals(PushStateRecoveryBudget.Phase.Exhausted, budget.phase)
        assertEquals(5, budget.attemptsUsed)
    }

    @Test
    fun `exhausted recovery budget never starts a new incident in this process`() {
        val budget = PushStateRecoveryBudget(maxAttempts = 1)
        assertTrue(budget.onFailure())
        assertFalse(budget.onAttemptFailed())

        assertFalse(budget.onFailure())
        assertEquals(PushStateRecoveryBudget.Phase.Exhausted, budget.phase)
    }

    @Test
    fun `successful recovery lets a later failure start a fresh incident`() {
        val budget = PushStateRecoveryBudget(maxAttempts = 5)
        assertTrue(budget.onFailure())
        repeat(4) { assertTrue(budget.onAttemptFailed()) }
        budget.onRecovered()
        assertEquals(PushStateRecoveryBudget.Phase.Healthy, budget.phase)
        assertEquals(0, budget.attemptsUsed)

        assertTrue(budget.onFailure())
        repeat(4) { assertTrue(budget.onAttemptFailed()) }
        assertFalse(budget.onAttemptFailed())
    }

    @Test
    fun `reset notice detail is a trimmed single line of bounded length`() {
        val notice = PushStateResetNotice.corruptStateDiscarded(
            IllegalArgumentException("  malformed\r\n durable\tstate " + "x".repeat(400)),
        )
        assertEquals(PushStateResetNotice.REASON_CORRUPT_STATE_DISCARDED, notice.reason)
        assertTrue(notice.detail.startsWith("malformed durable state x"))
        assertEquals(256, notice.detail.length)
        assertFalse(notice.detail.contains('\n'))

        val unnamed = PushStateResetNotice.corruptStateDiscarded(IllegalStateException())
        assertEquals(IllegalStateException::class.java.name, unnamed.detail)
    }

    @Test
    fun `reset notice stays until the registration that carried it is acknowledged`() {
        val tracker = PushStateResetNoticeTracker()
        val notice = PushStateResetNotice(PushStateResetNotice.REASON_CORRUPT_STATE_DISCARDED, "bad json")
        val first = Any()
        val second = Any()
        tracker.record(notice)

        assertEquals(notice, tracker.forRegistration(first))
        // An acknowledgement on a transport that did not carry it keeps the notice.
        tracker.onRegistered(second)
        assertEquals(notice, tracker.forRegistration(second))
        tracker.onRegistered(first)
        assertEquals(notice, tracker.forRegistration(second))
        tracker.onRegistered(second)
        assertEquals(null, tracker.forRegistration(first))
    }

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
    fun `failed persistence does not publish replacement state`() {
        val previous = PushProtocol.State(
            active = null,
            pendingResults = emptyList(),
            completedReceipts = emptyList(),
        )
        val replacement = previous.copy(
            active = PushProtocol.Active(command(), PushProtocol.PHASE_DOWNLOADING),
        )
        var published = previous
        var failure: Throwable? = null
        val persisted = persistPushStateBeforePublishing(
            replacement,
            save = { throw IllegalStateException("injected persistence failure") },
            onFailure = { failure = it },
            publish = { published = it },
        )

        assertFalse(persisted)
        assertTrue(failure is IllegalStateException)
        assertSame(previous, published)
    }

    @Test
    fun `successful persistence publishes the saved state`() {
        val next = PushProtocol.State(
            active = null,
            pendingResults = emptyList(),
            completedReceipts = emptyList(),
        )
        val normalized = next.copy(completedReceipts = emptyList())
        var published: PushProtocol.State? = null

        val persisted = persistPushStateBeforePublishing(
            next,
            save = { normalized },
            publish = { published = it },
        )

        assertTrue(persisted)
        assertSame(normalized, published)
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
                retention = PushProtocol.Retention(PushProtocol.Retention.DURATION_MS, 1, 123L),
                interruptionReason = "download_interrupted",
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
    fun `reconcile is not answered absent before durable state is loaded`() {
        val command = command()
        val identity = PushProtocol.ReconcileIdentity(
            requireNotNull(command.jobId),
            command.attempt,
            command.artifactId,
        )
        val empty = PushProtocol.State(null, emptyList(), emptyList())

        assertEquals(null, buildPushReconcileReply(empty, stateLoaded = false, identity) { 0L })

        val absent = requireNotNull(buildPushReconcileReply(empty, stateLoaded = true, identity) { 0L })
        assertEquals("PUSH_RECONCILE_REPORT", absent.getString("type"))
        assertEquals("absent", absent.getString("status"))
        assertEquals(command.artifactId, absent.getString("artifact_id"))
    }

    @Test
    fun `loaded reconcile reports active work and replays a settled receipt`() {
        val active = PushProtocol.Active(command().copy(revision = 7L), PushProtocol.PHASE_DOWNLOADING)
        val activeIdentity = PushProtocol.ReconcileIdentity(
            requireNotNull(active.command.jobId),
            active.command.attempt,
            null,
        )
        val activeState = PushProtocol.State(active, emptyList(), emptyList())
        val activeReply = requireNotNull(
            buildPushReconcileReply(activeState, stateLoaded = true, activeIdentity) { 41L },
        )
        assertEquals("active", activeReply.getString("status"))
        assertEquals(41L, activeReply.getLong("validated_offset"))

        val settled = receipt()
        val settledIdentity = PushProtocol.ReconcileIdentity(
            requireNotNull(settled.command.jobId),
            settled.command.attempt,
            settled.command.artifactId,
        )
        val settledState = PushProtocol.State(null, emptyList(), listOf(settled))
        val settledReply = requireNotNull(
            buildPushReconcileReply(settledState, stateLoaded = true, settledIdentity) { 0L },
        )
        assertEquals(settled.result.toJson().toString(), settledReply.toString())
    }

    @Test
    fun `active reconciliation report proves the exact artifact identity`() {
        val command = command().copy(revision = 7L)
        val active = PushProtocol.Active(
            command,
            PushProtocol.PHASE_DOWNLOADING,
            interrupted = true,
            retention = PushProtocol.Retention(PushProtocol.Retention.DURATION_MS, 1, 123L),
            interruptionReason = "download_interrupted",
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
        assertEquals("download_interrupted", report.getString("reason"))
    }

    @Test
    fun `interrupted ownership expires exactly at the twenty four hour deadline`() {
        val command = command().copy(revision = 7L)
        val state = PushProtocol.State(
            active = PushProtocol.Active(
                command, PushProtocol.PHASE_DOWNLOADING, interrupted = true,
                retention = PushProtocol.Retention(PushProtocol.Retention.DURATION_MS, 1, 1_000L),
                interruptionReason = "download_interrupted",
            ),
            pendingResults = emptyList(),
            completedReceipts = emptyList(),
        )
        val deadline = 1_000L + PushProtocol.Retention.DURATION_MS
        val cases = listOf("one millisecond before" to (deadline - 1L), "at deadline" to deadline)
        for ((label, now) in cases) {
            val settled = expireInterruptedPushState(
                state, PushRetentionClock(1, now), maxReceipts = 256,
            )
            if (now < deadline) {
                assertTrue("$label retains ownership", settled == null)
                assertSame(command, state.active?.command)
            } else {
                val expired = requireNotNull(settled) { "$label expires durably" }
                assertTrue(expired.first.active == null)
                assertEquals("resume_expired", expired.second.result.failureCode)
                assertEquals(listOf(expired.second), expired.first.pendingResults)
                assertEquals(listOf(expired.second), expired.first.completedReceipts)
            }
        }
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
    fun `unavailable registration advertises no Push capabilities`() {
        val fields = buildPushRegistrationFields(
            PushProtocol.State(null, emptyList(), emptyList()),
            durabilityAvailable = false,
            processInstanceId = UUID.randomUUID().toString(),
            resetNotice = PushStateResetNotice(PushStateResetNotice.REASON_CORRUPT_STATE_DISCARDED, "bad"),
            validatedOffset = { 0L },
        )
        val pushState = fields.getJSONObject("push_state")

        assertEquals(0, fields.getJSONArray("capabilities").length())
        assertEquals("unavailable", pushState.getString("status"))
        assertFalse(pushState.has("reset"))
        assertTrue(fields.getJSONObject("push_runtime").isNull("active"))
    }

    @Test
    fun `available registration carries a reset notice only when one is pending`() {
        val notice = PushStateResetNotice(PushStateResetNotice.REASON_CORRUPT_STATE_DISCARDED, "bad json")
        val withNotice = buildPushRegistrationFields(
            PushProtocol.State(null, emptyList(), emptyList()),
            durabilityAvailable = true,
            processInstanceId = UUID.randomUUID().toString(),
            resetNotice = notice,
            validatedOffset = { 0L },
        ).getJSONObject("push_state")
        val reset = withNotice.getJSONObject("reset")

        assertEquals("available", withNotice.getString("status"))
        assertEquals("corrupt_state_discarded", reset.getString("reason"))
        assertEquals("bad json", reset.getString("detail"))

        val withoutNotice = buildPushRegistrationFields(
            PushProtocol.State(null, emptyList(), emptyList()),
            durabilityAvailable = true,
            processInstanceId = UUID.randomUUID().toString(),
            validatedOffset = { 0L },
        ).getJSONObject("push_state")
        assertFalse(withoutNotice.has("reset"))
    }

    @Test
    fun `available registration restores Push capabilities without starting work`() {
        val interrupted = PushProtocol.Active(
            command(),
            PushProtocol.PHASE_DOWNLOADING,
            interrupted = true,
            retention = PushProtocol.Retention(PushProtocol.Retention.DURATION_MS, 1, 1_000L),
            interruptionReason = "download_interrupted",
        )
        val fields = buildPushRegistrationFields(
            PushProtocol.State(interrupted, emptyList(), emptyList()),
            durabilityAvailable = true,
            processInstanceId = UUID.randomUUID().toString(),
            validatedOffset = { 12L },
        )
        val capabilities = fields.getJSONArray("capabilities")
        val active = fields.getJSONObject("push_runtime").getJSONObject("active")

        assertEquals(
            listOf(PushProtocol.CAP_PUSH_JOB_ID_V1, PushProtocol.CAP_PUSH_RESUME_V1),
            (0 until capabilities.length()).map(capabilities::getString),
        )
        assertEquals("available", fields.getJSONObject("push_state").getString("status"))
        assertEquals("interrupted", active.getString("status"))
        assertEquals(12L, active.getLong("validated_offset"))
        assertEquals("download_interrupted", active.getString("reason"))
    }

    @Test
    fun `different boot keeps saved remaining time and then counts the new boot`() {
        val saved = PushProtocol.Retention(60_000L, 1, 100_000L)
        val rebooted = saved.checkpoint(2, 1_000L)
        assertEquals(60_000L, rebooted.remainingMs)
        assertEquals(30_000L, rebooted.checkpoint(2, 31_000L).remainingMs)
        assertEquals(0L, rebooted.checkpoint(2, 61_000L).remainingMs)
    }

    @Test
    fun `resumed running ownership consumes retention but never expires while running`() {
        val active = PushProtocol.Active(
            command(), PushProtocol.PHASE_APPLYING,
            retention = PushProtocol.Retention(1_000L, 1, 10_000L),
        )
        val expiredBudget = checkpointPushRetention(active, PushRetentionClock(1, 11_000L))
        assertEquals(0L, expiredBudget.retention?.remainingMs)
        assertEquals(null, expireInterruptedPushState(
            PushProtocol.State(expiredBudget, emptyList(), emptyList()),
            PushRetentionClock(1, 11_000L), 256,
        ))
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
