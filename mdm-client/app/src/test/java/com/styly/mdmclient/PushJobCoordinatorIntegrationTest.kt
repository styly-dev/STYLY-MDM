package com.styly.mdmclient

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class PushJobCoordinatorIntegrationTest {
    private val harnesses = CopyOnWriteArrayList<Harness>()

    private inner class Harness(
        initialState: PushProtocol.State = PushProtocol.State(null, emptyList(), emptyList()),
        retryDelayMs: Long = 10_000L,
        recoveryMaxAttempts: Int = 5,
    ) {
        val durableState = AtomicReference(initialState)
        val messages = CopyOnWriteArrayList<JSONObject>()
        val events = CopyOnWriteArrayList<String>()
        val workerRuns = AtomicInteger()
        val cleanupCalls = AtomicInteger()
        private val actor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "push-coordinator-test-actor").apply { isDaemon = true }
        }
        private val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "push-coordinator-test-worker").apply { isDaemon = true }
        }

        @Volatile var shouldFailSave: (PushProtocol.State) -> Boolean = { false }
        @Volatile var runExecution: (PushProtocol.Command, PushFilesWorker.Callbacks) -> PushFilesWorker.Execution =
            { command, _ -> execution(command) }

        val coordinator = PushJobCoordinator(
            PushJobCoordinatorDependencies(
                loadState = { PushStateLoadResult.Valid(durableState.get()) },
                discardState = {},
                saveState = { next ->
                    if (shouldFailSave(next)) {
                        events.add("save:failed")
                        throw IOException("injected save failure")
                    }
                    durableState.set(next)
                    events.add(when {
                        next.active != null -> "save:active"
                        next.completedReceipts.isNotEmpty() -> "save:result"
                        else -> "save:empty"
                    })
                    next
                },
                emptyState = { PushProtocol.State(null, emptyList(), emptyList()) },
                execute = { command, callbacks ->
                    workerRuns.incrementAndGet()
                    events.add("worker:start")
                    runExecution(command, callbacks)
                },
                cleanup = {
                    cleanupCalls.incrementAndGet()
                    events.add("cleanup")
                },
                validatedResumeOffset = { 0L },
                attemptDirectory = { File("/tmp/push-coordinator-${it.identity}") },
                actor = actor,
                workerExecutor = workerExecutor,
                log = { _, _, _ -> },
            ),
            retryDelayMs,
            recoveryMaxAttempts,
        )

        init {
            harnesses.add(this)
        }

        fun close() {
            actor.shutdownNow()
            workerExecutor.shutdownNow()
            assertTrue(actor.awaitTermination(2, TimeUnit.SECONDS))
            assertTrue(workerExecutor.awaitTermination(2, TimeUnit.SECONDS))
        }

        fun attach() {
            val token = Any()
            coordinator.attachTransport(
                token,
                { message ->
                    messages.add(message)
                    events.add("send:${message.optString("type")}")
                },
                {},
            )
            coordinator.onRegistered(token)
            snapshot()
        }

        fun send(command: PushProtocol.Command) {
            assertTrue(coordinator.handleServerMessage("EXECUTE_PUSH_FILES", command.toJson()))
        }

        fun snapshot(): JSONObject {
            val ready = CountDownLatch(1)
            val result = AtomicReference<JSONObject>()
            coordinator.registrationFields {
                result.set(it)
                ready.countDown()
            }
            assertTrue("coordinator actor did not respond", ready.await(2, TimeUnit.SECONDS))
            return requireNotNull(result.get())
        }
    }

    @After
    fun closeExecutors() {
        harnesses.forEach(Harness::close)
        harnesses.clear()
    }

    @Test
    fun `first active save failure rejects command without starting worker`() {
        val harness = Harness().apply { shouldFailSave = { it.active != null } }
        harness.attach()
        val command = command()

        harness.send(command)
        await { harness.messages.any { it.optString("type") == "PUSH_JOB_REJECTED" } }

        assertEquals(0, harness.workerRuns.get())
        assertEquals(null, harness.durableState.get().active)
        assertTrue(harness.snapshot().getJSONObject("push_runtime").isNull("active"))
    }

    @Test
    fun `terminal save failure keeps active ownership until recovery persists result`() {
        val allowTerminalSave = AtomicBoolean(false)
        val releaseWorker = CountDownLatch(1)
        val harness = Harness(retryDelayMs = 100L, recoveryMaxAttempts = 1_000).apply {
            shouldFailSave = { next ->
                next.active == null && next.completedReceipts.isNotEmpty() &&
                    !allowTerminalSave.get()
            }
            runExecution = { command, _ ->
                releaseWorker.await()
                execution(command)
            }
        }
        harness.attach()
        val command = command()
        harness.send(command)
        await { harness.workerRuns.get() == 1 }
        harness.send(command.copy(artifactUrl = "http://server/fresh-lease.zip"))
        await { harness.messages.count { it.optString("type") == "PUSH_JOB_ACCEPTED" } >= 2 }
        assertEquals(1, harness.workerRuns.get())

        releaseWorker.countDown()
        await { harness.events.contains("save:failed") }
        assertEquals(command.jobId, harness.durableState.get().active?.command?.jobId)
        assertEquals(0, harness.cleanupCalls.get())
        assertFalse(harness.messages.any { it.optString("type") == "PUSH_FILES_RESULT" })
        val next = command()
        harness.send(next)
        await {
            harness.messages.any {
                it.optString("type") == "PUSH_JOB_REJECTED" &&
                    it.optString("job_id") == next.jobId
            }
        }
        assertEquals(1, harness.workerRuns.get())
        assertEquals(command.jobId, harness.durableState.get().active?.command?.jobId)

        allowTerminalSave.set(true)
        await {
            harness.cleanupCalls.get() == 1 &&
                harness.messages.any { it.optString("type") == "PUSH_FILES_RESULT" }
        }
        val failed = harness.events.indexOf("save:failed")
        val saved = harness.events.indexOf("save:result")
        val cleanup = harness.events.indexOf("cleanup")
        val result = harness.events.indexOf("send:PUSH_FILES_RESULT")
        assertTrue(failed >= 0 && saved > failed && saved < cleanup && saved < result)
        assertEquals(null, harness.durableState.get().active)

        harness.send(next)
        await {
            harness.workerRuns.get() == 2 && harness.cleanupCalls.get() == 2 &&
                harness.messages.count { it.optString("type") == "PUSH_FILES_RESULT" } == 2
        }
        assertEquals(2, harness.messages.count { it.optString("type") == "PUSH_FILES_RESULT" })
    }

    @Test
    fun `interrupted resume is exact and duplicate delivery starts one worker`() {
        val saved = command()
        val interrupted = PushProtocol.State(
            active = PushProtocol.Active(
                saved,
                PushProtocol.PHASE_DOWNLOADING,
                interrupted = true,
                interruptedAt = System.currentTimeMillis(),
            ),
            pendingResults = emptyList(),
            completedReceipts = emptyList(),
        )
        val started = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val harness = Harness(interrupted).apply {
            runExecution = { command, _ ->
                started.countDown()
                releaseWorker.await()
                execution(command, interruptionReason = "download_interrupted")
            }
        }
        harness.attach()

        harness.send(command())
        await { harness.messages.any { it.optString("reason") == "device_busy" } }
        assertEquals(0, harness.workerRuns.get())

        val resumed = saved.copy(artifactUrl = "http://server/fresh-lease.zip")
        harness.send(resumed)
        assertTrue("exact resume did not start", started.await(2, TimeUnit.SECONDS))
        harness.send(resumed.copy(artifactUrl = "http://server/replayed-lease.zip"))
        await { harness.messages.count { it.optString("type") == "PUSH_JOB_ACCEPTED" } >= 2 }
        assertEquals(1, harness.workerRuns.get())

        releaseWorker.countDown()
        await { harness.messages.any { it.optString("type") == "PUSH_RECONCILE_REPORT" } }
        assertEquals(0, harness.cleanupCalls.get())
        assertTrue(harness.durableState.get().active?.interrupted == true)
    }

    @Test
    fun `active job and legacy ownership reject conflicting and unrelated commands`() {
        for (legacyOwner in listOf(false, true)) {
            val releaseWorker = CountDownLatch(1)
            val harness = Harness().apply {
                runExecution = { command, _ ->
                    releaseWorker.await()
                    execution(command)
                }
            }
            harness.attach()
            val current = command().let {
                if (legacyOwner) it.copy(jobId = null, revision = 0L) else it
            }
            harness.send(current)
            await { harness.workerRuns.get() == 1 }

            if (!legacyOwner) {
                harness.send(current.copy(deleteExtras = !current.deleteExtras))
                harness.snapshot()
                val rejection = harness.messages.last()
                assertEquals("artifact_identity_mismatch", rejection.getString("reason"))
                assertFalse(rejection.getBoolean("retryable"))
            }

            val other = command()
            harness.send(other)
            harness.snapshot()
            val busy = harness.messages.last()
            assertEquals("device_busy", busy.getString("reason"))
            assertEquals(other.jobId, busy.getString("job_id"))
            if (legacyOwner) assertTrue(busy.getJSONObject("active_job").getBoolean("legacy"))
            else assertEquals(current.jobId, busy.getJSONObject("active_job").getString("job_id"))

            harness.send(current.copy(jobId = null, revision = 0L))
            harness.snapshot()
            val legacyBusy = harness.messages.last()
            assertEquals("PUSH_FILES_RESULT", legacyBusy.getString("type"))
            assertEquals("device_busy", legacyBusy.getString("failure_code"))
            assertEquals(1, harness.workerRuns.get())
            assertEquals(current, harness.durableState.get().active?.command)

            releaseWorker.countDown()
            await { harness.cleanupCalls.get() == 1 }
        }
    }

    private fun await(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("condition was not reached", condition())
    }

    private fun command() = pushTestCommand(revision = 7L, artifactEtag = "\"etag\"")

    private fun execution(
        command: PushProtocol.Command,
        interruptionReason: String? = null,
    ) = PushFilesWorker.Execution(
        result = PushProtocol.Result(
            jobId = command.jobId,
            attempt = command.attempt,
            status = if (interruptionReason == null) "success" else "fail",
            destPath = command.destPath,
            failureCode = if (interruptionReason != null) "download_failed" else null,
        ),
        workDirectory = File("/tmp/push-coordinator-${command.identity}"),
        interruptionReason = interruptionReason,
    )
}
