package com.styly.mdmclient

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture

class PushJobCoordinatorIntegrationTest {
    private val harnesses = CopyOnWriteArrayList<Harness>()

    private inner class Harness(
        initialState: PushProtocol.State = PushProtocol.State(null, emptyList(), emptyList()),
        retryDelayMs: Long = 10_000L,
        recoveryMaxAttempts: Int = 5,
        private val loadStateOverride: (() -> PushStateLoadResult)? = null,
        private val attemptDirectoryOverride: ((PushProtocol.Command) -> File)? = null,
        private val cleanupOverride: ((File) -> Unit)? = null,
        initialClock: PushRetentionClock = PushRetentionClock(1, 0L),
        manualRecovery: Boolean = false,
    ) {
        val durableState = AtomicReference(initialState)
        val clock = AtomicReference(initialClock)
        val scheduledDelays = CopyOnWriteArrayList<Long>()
        val checkpointTask = AtomicReference<Runnable?>()
        private val recoveryTask = AtomicReference<Runnable?>()
        @Volatile var clockUnavailable = false
        val messages = CopyOnWriteArrayList<JSONObject>()
        val events = CopyOnWriteArrayList<String>()
        val logs = CopyOnWriteArrayList<Pair<String, Throwable?>>()
        val cleanedDirectories = CopyOnWriteArrayList<File>()
        val workerRuns = AtomicInteger()
        val cleanupCalls = AtomicInteger()
        val discardCalls = AtomicInteger()
        private val actor = object : ScheduledThreadPoolExecutor(1, { runnable ->
            Thread(runnable, "push-coordinator-test-actor").apply { isDaemon = true }
        }) {
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
                scheduledDelays.add(unit.toMillis(delay))
                if (unit.toMillis(delay) == PUSH_RETENTION_CHECKPOINT_MS) checkpointTask.set(command)
                return super.schedule(command, delay, unit).also { scheduled ->
                    if (manualRecovery && unit.toMillis(delay) == retryDelayMs) {
                        scheduled.cancel(false)
                        recoveryTask.set(command)
                    }
                }
            }
        }.apply { removeOnCancelPolicy = true }
        private val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "push-coordinator-test-worker").apply { isDaemon = true }
        }

        @Volatile var shouldFailSave: (PushProtocol.State) -> Boolean = { false }
        @Volatile var beforeSave: (PushProtocol.State) -> Unit = {}
        @Volatile var runExecution: (PushProtocol.Command, PushFilesWorker.Callbacks) -> PushFilesWorker.Execution =
            { command, _ -> execution(command) }

        val coordinator = PushJobCoordinator(
            PushJobCoordinatorDependencies(
                loadState = { loadStateOverride?.invoke() ?: PushStateLoadResult.Valid(durableState.get()) },
                discardState = { discardCalls.incrementAndGet() },
                saveState = { next ->
                    beforeSave(next)
                    if (shouldFailSave(next)) {
                        events.add("save:failed")
                        throw IOException("injected save failure")
                    }
                    durableState.set(next)
                    events.add(when {
                        next.active?.phase == PushProtocol.PHASE_APPLYING -> "save:phase:applying"
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
                    cleanedDirectories.add(it)
                    cleanupCalls.incrementAndGet()
                    events.add("cleanup")
                    cleanupOverride?.invoke(it)
                },
                validatedResumeOffset = { 0L },
                attemptDirectory = attemptDirectoryOverride
                    ?: { File("/tmp/push-coordinator-${it.identity}") },
                actor = actor,
                workerExecutor = workerExecutor,
                log = { _, message, error -> logs.add(message to error) },
                retentionClock = {
                    check(!clockUnavailable) { "injected boot count lookup failure" }
                    clock.get()
                },
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

        fun runCheckpoint() {
            actor.execute(requireNotNull(checkpointTask.get()))
            snapshot()
        }

        fun runRecovery(): JSONObject {
            actor.execute(requireNotNull(recoveryTask.getAndSet(null)))
            return snapshot()
        }

        fun blockActor(started: CountDownLatch, release: CountDownLatch) {
            actor.execute {
                started.countDown()
                release.await()
            }
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
    fun `worker apply gate returns only after applying phase is durable`() {
        val harness = Harness()
        harness.runExecution = { command, callbacks ->
            assertTrue("worker must be allowed after durable phase save", callbacks.beforeApply())
            assertEquals(PushProtocol.PHASE_APPLYING, harness.durableState.get().active?.phase)
            harness.events.add("worker:apply-after-checkpoint")
            execution(command)
        }
        harness.attach()

        harness.send(command())
        await { harness.events.contains("worker:apply-after-checkpoint") }

        assertTrue(
            harness.events.indexOf("save:phase:applying") in 0 until
                harness.events.indexOf("worker:apply-after-checkpoint"),
        )
        assertTrue(harness.messages.any { it.optString("type") == "PUSH_PHASE" })
    }

    @Test
    fun `unsupported durable version remains unavailable and is not discarded`() {
        val unsupported = PushStateLoadResult.UnsupportedVersion(
            UnsupportedPushStateVersionException("future"),
        )
        val harness = Harness(loadStateOverride = { unsupported })
        harness.attach()

        val registration = harness.snapshot()

        assertEquals("unavailable", registration.getJSONObject("push_state").getString("status"))
        assertFalse(registration.getJSONObject("push_state").has("reset"))
        assertEquals(0, harness.discardCalls.get())
        assertEquals(0, harness.workerRuns.get())
    }

    @Test
    fun `state load diagnostics never expose exception content in logs or reset registration`() {
        val sensitiveInput = "https://admin:lease-secret@example.invalid/artifact?token=secret"
        val cases = listOf(
            Triple("unreadable", PushStateLoadResult.Unreadable(IOException(sensitiveInput)), false),
            Triple(
                "invalid receipts",
                PushStateLoadResult.InvalidReceipts(
                    InvalidPushReceiptsException("pending_results", IllegalArgumentException(sensitiveInput)),
                ),
                false,
            ),
            Triple(
                "unsupported version",
                PushStateLoadResult.UnsupportedVersion(UnsupportedPushStateVersionException(sensitiveInput)),
                false,
            ),
            Triple(
                "invalid active",
                PushStateLoadResult.InvalidActive(InvalidPushActiveException(IllegalArgumentException(sensitiveInput))),
                false,
            ),
            Triple(
                "corrupt",
                PushStateLoadResult.Corrupt(IllegalArgumentException("Invalid durable JSON near $sensitiveInput")),
                true,
            ),
        )

        cases.forEach { (label, loadResult, shouldDiscard) ->
            val harness = Harness(loadStateOverride = { loadResult })
            harness.attach()

            val pushState = harness.snapshot().getJSONObject("push_state")

            assertEquals(label, shouldDiscard, harness.discardCalls.get() == 1)
            assertTrue(label, harness.logs.isNotEmpty())
            assertTrue(label, harness.logs.all { (message, error) ->
                error == null && !message.contains(sensitiveInput)
            })
            if (shouldDiscard) {
                val reset = pushState.getJSONObject("reset")
                assertEquals(label, "corrupt_state_discarded", reset.getString("reason"))
                assertEquals(
                    label,
                    "Stored Push/Sync state was reset because its contents were invalid.",
                    reset.getString("detail"),
                )
                assertTrue(label, harness.logs.any {
                    it.first == "Discarding unparseable durable Push/Sync state" && it.second == null
                })
            } else {
                assertEquals(label, "unavailable", pushState.getString("status"))
                assertFalse(label, pushState.has("reset"))
            }
        }
    }

    @Test
    fun `unexpected state load exception does not expose its message or cause`() {
        val sensitiveInput = "https://example.invalid/artifact?lease=secret"
        val harness = Harness(loadStateOverride = {
            throw IllegalStateException(sensitiveInput, IOException(sensitiveInput))
        })
        harness.attach()

        val pushState = harness.snapshot().getJSONObject("push_state")

        assertEquals("unavailable", pushState.getString("status"))
        assertFalse(pushState.has("reset"))
        assertEquals(0, harness.discardCalls.get())
        assertTrue(harness.logs.any { it.first == "Could not load durable Push/Sync state" })
        assertTrue(harness.logs.all { (message, error) ->
            error == null && !message.contains(sensitiveInput)
        })
    }

    @Test
    fun `invalid active durable command remains unavailable and is not discarded`() {
        val invalidActive = PushStateLoadResult.InvalidActive(
            InvalidPushActiveException(IllegalArgumentException("invalid command")),
        )
        val harness = Harness(loadStateOverride = { invalidActive })
        harness.attach()

        val registration = harness.snapshot()

        assertEquals("unavailable", registration.getJSONObject("push_state").getString("status"))
        assertFalse(registration.getJSONObject("push_state").has("reset"))
        assertEquals(0, harness.discardCalls.get())
        assertEquals(0, harness.workerRuns.get())
    }

    @Test
    fun `failed applying phase save becomes terminal persistence failure before apply`() {
        val harness = Harness()
        harness.shouldFailSave = { it.active?.phase == PushProtocol.PHASE_APPLYING }
        harness.runExecution = { command, callbacks ->
            val mayApply = callbacks.beforeApply()
            harness.events.add(if (mayApply) "worker:apply" else "worker:apply-blocked")
            val result = PushProtocol.Result(
                jobId = command.jobId,
                attempt = command.attempt,
                status = if (mayApply) "success" else "fail",
                destPath = command.destPath,
                failureCode = if (mayApply) null else "client_persistence_unavailable",
            )
            PushFilesWorker.Execution(
                PushFilesWorker.Outcome.Completed(result),
                File("/tmp/push-coordinator-${command.identity}"),
            )
        }
        harness.attach()

        harness.send(command())
        await { harness.events.contains("worker:apply-blocked") }
        await { harness.messages.any { it.optString("type") == "PUSH_FILES_RESULT" } }

        assertFalse(harness.events.contains("worker:apply"))
        assertEquals(
            "client_persistence_unavailable",
            harness.messages.last { it.optString("type") == "PUSH_FILES_RESULT" }
                .getString("failure_code"),
        )
    }

    @Test
    fun `interrupted queued applying checkpoint is cancelled before it can save or notify`() {
        val harness = Harness()
        val actorStarted = CountDownLatch(1)
        val releaseActor = CountDownLatch(1)
        val beforeApplyCall = CountDownLatch(1)
        val workerThread = AtomicReference<Thread?>()
        harness.runExecution = { command, callbacks ->
            workerThread.set(Thread.currentThread())
            callbacks.onValidationStart()
            callbacks.onValidated()
            harness.blockActor(actorStarted, releaseActor)
            assertTrue(actorStarted.await(2, TimeUnit.SECONDS))
            beforeApplyCall.countDown()
            val mayApply = callbacks.beforeApply()
            harness.events.add(if (mayApply) "worker:apply" else "worker:apply-blocked")
            execution(
                command,
                failureCode = if (mayApply) null else "client_persistence_unavailable",
            )
        }
        harness.attach()

        harness.send(command())
        assertTrue(beforeApplyCall.await(2, TimeUnit.SECONDS))
        workerThread.get()?.interrupt() ?: throw AssertionError("worker thread was not captured")
        await { harness.events.contains("worker:apply-blocked") }
        releaseActor.countDown()
        await { harness.messages.any { it.optString("type") == "PUSH_FILES_RESULT" } }

        assertFalse(harness.events.contains("save:phase:applying"))
        assertFalse(harness.messages.any {
            it.optString("type") == "PUSH_PHASE" &&
                it.optString("phase") == PushProtocol.PHASE_APPLYING
        })
        assertEquals("client_persistence_unavailable", harness.messages.last {
            it.optString("type") == "PUSH_FILES_RESULT"
        }.getString("failure_code"))
    }

    @Test
    fun `interrupted applying store write records a conservative phase before terminal failure`() {
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val blockOnce = AtomicBoolean(true)
        val workerThread = AtomicReference<Thread?>()
        val harness = Harness().apply {
            beforeSave = { next ->
                if (next.active?.phase == PushProtocol.PHASE_APPLYING && blockOnce.getAndSet(false)) {
                    writeStarted.countDown()
                    releaseWrite.await()
                }
            }
            runExecution = { command, callbacks ->
                workerThread.set(Thread.currentThread())
                callbacks.onValidationStart()
                callbacks.onValidated()
                val mayApply = callbacks.beforeApply()
                events.add(if (mayApply) "worker:apply" else "worker:apply-blocked")
                execution(
                    command,
                    failureCode = if (mayApply) null else "client_persistence_unavailable",
                )
            }
        }
        harness.attach()

        harness.send(command())
        assertTrue(writeStarted.await(2, TimeUnit.SECONDS))
        workerThread.get()?.interrupt() ?: throw AssertionError("worker thread was not captured")
        await { harness.events.contains("worker:apply-blocked") }
        releaseWrite.countDown()
        await { harness.messages.any { it.optString("type") == "PUSH_FILES_RESULT" } }

        assertFalse(harness.events.contains("worker:apply"))
        assertEquals(null, harness.durableState.get().active)
        assertTrue(harness.durableState.get().completedReceipts.any {
            it.result.failureCode == "client_persistence_unavailable"
        })
        val phaseSave = harness.events.indexOf("save:phase:applying")
        val phaseNotification = harness.events.lastIndexOf("send:PUSH_PHASE")
        val terminalSave = harness.events.indexOf("save:result")
        assertTrue(
            harness.events.toString(),
            phaseSave >= 0 && phaseNotification > phaseSave && terminalSave > phaseNotification,
        )
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
                retention = PushProtocol.Retention(PushProtocol.Retention.DURATION_MS, 1, 0L),
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
        assertTrue(harness.logs.any { (message, _) ->
            message.contains(saved.identity) && message.contains("download_interrupted") &&
                message.contains("test interruption")
        })
    }

    @Test
    fun `active job rejects conflicting and unrelated job-v1 commands`() {
        val releaseWorker = CountDownLatch(1)
        val harness = Harness().apply {
            runExecution = { command, _ ->
                releaseWorker.await()
                execution(command)
            }
        }
        harness.attach()
        val current = command()
        harness.send(current)
        await { harness.workerRuns.get() == 1 }

        harness.send(current.copy(deleteExtras = !current.deleteExtras))
        harness.snapshot()
        val rejection = harness.messages.last()
        assertEquals("artifact_identity_mismatch", rejection.getString("reason"))
        assertFalse(rejection.getBoolean("retryable"))

        val other = command()
        harness.send(other)
        harness.snapshot()
        val busy = harness.messages.last()
        assertEquals("device_busy", busy.getString("reason"))
        assertEquals(other.jobId, busy.getString("job_id"))
        assertEquals(current.jobId, busy.getJSONObject("active_job").getString("job_id"))
        assertEquals(1, harness.workerRuns.get())
        assertEquals(current, harness.durableState.get().active?.command)

        releaseWorker.countDown()
        await { harness.cleanupCalls.get() == 1 }
    }

    @Test
    fun `identity-less live command cannot start a worker`() {
        val harness = Harness()
        harness.attach()

        harness.send(command().copy(jobId = null, revision = 0L))
        val state = harness.snapshot()

        assertEquals(0, harness.workerRuns.get())
        assertTrue(state.isNull("active"))
        assertTrue(harness.messages.none { it.optString("type") == "PUSH_FILES_RESULT" })
    }

    @Test
    fun `recovering stored identity-less active state keeps service available and cleans legacy files`() {
        val downloads = Files.createTempDirectory("push-legacy-recovery").toFile()
        try {
            val legacy = PushProtocol.parseStoredCommand(
                JSONObject().apply {
                    put("bundle_url", "http://server/legacy.zip")
                    put("dest_path", "/sdcard/STYLY/content")
                },
            )
            val legacyDirectory = PushFilesWorker.attemptDirectoryUnder(downloads, legacy)
            val partial = File(legacyDirectory, "artifact.part").apply {
                parentFile?.mkdirs()
                writeText("old partial")
            }
            val existingCommand = command()
            val existingReceipt = PushProtocol.Receipt(
                existingCommand,
                PushProtocol.Result(
                    jobId = existingCommand.jobId,
                    attempt = existingCommand.attempt,
                    status = "fail",
                    destPath = existingCommand.destPath,
                    failureCode = "validation_failed",
                    detail = "previous durable result",
                    completedAt = 1L,
                ),
            )
            val initialState = PushProtocol.State(
                active = PushProtocol.Active(legacy, PushProtocol.PHASE_DOWNLOADING),
                pendingResults = listOf(existingReceipt),
                completedReceipts = listOf(existingReceipt),
            )
            val harness = Harness(
                initialState = initialState,
                attemptDirectoryOverride = {
                    PushFilesWorker.attemptDirectoryUnder(downloads, it)
                },
                cleanupOverride = { it.deleteRecursively() },
            )

            harness.attach()
            val registration = harness.snapshot()
            await { harness.cleanupCalls.get() == 1 && !partial.exists() }

            assertEquals("available", registration.getJSONObject("push_state").getString("status"))
            assertEquals(2, registration.getJSONArray("capabilities").length())
            assertTrue(registration.getJSONObject("push_runtime").isNull("active"))
            assertEquals(null, harness.durableState.get().active)
            assertEquals(listOf(existingReceipt), harness.durableState.get().pendingResults)
            assertTrue(harness.durableState.get().completedReceipts.contains(existingReceipt))
            val recoveredLegacyReceipt = harness.durableState.get().completedReceipts
                .single { it.command.jobId == null }
            assertEquals("client_restarted", recoveredLegacyReceipt.result.failureCode)
            assertEquals(listOf(legacyDirectory), harness.cleanedDirectories)
            assertFalse(partial.exists())
            assertEquals(0, harness.workerRuns.get())
        } finally {
            downloads.deleteRecursively()
        }
    }

    @Test
    fun `periodic checkpoint saves five minutes and shutdown saves the final interval`() {
        val harness = Harness(retainedState(600_000L))
        harness.attach()
        assertTrue(harness.scheduledDelays.contains(5 * 60_000L))
        harness.clock.set(PushRetentionClock(1, 300_000L))
        harness.runCheckpoint()
        assertEquals(300_000L, harness.durableState.get().active?.retention?.remainingMs)
        harness.clock.set(PushRetentionClock(1, 320_000L))
        assertTrue(harness.coordinator.checkpointBeforeShutdown())
        assertEquals(280_000L, harness.durableState.get().active?.retention?.remainingMs)
        assertEquals(320_000L, harness.durableState.get().active?.retention?.elapsedRealtimeMs)
        assertEquals(0, harness.cleanupCalls.get())
    }

    @Test
    fun `shutdown waits for the serialized write before returning`() {
        val harness = Harness(retainedState(600_000L))
        harness.attach()
        harness.clock.set(PushRetentionClock(1, 10_000L))
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val result = AtomicBoolean(false)
        harness.beforeSave = {
            writeStarted.countDown()
            releaseWrite.await()
        }
        val shutdown = Thread {
            result.set(harness.coordinator.checkpointBeforeShutdown())
            returned.countDown()
        }.apply { start() }
        try {
            assertTrue(writeStarted.await(2, TimeUnit.SECONDS))
            assertFalse(returned.await(50, TimeUnit.MILLISECONDS))
            assertEquals(600_000L, harness.durableState.get().active?.retention?.remainingMs)
        } finally {
            releaseWrite.countDown()
            shutdown.join(2_000L)
        }
        assertTrue(result.get())
        assertEquals(590_000L, harness.durableState.get().active?.retention?.remainingMs)
    }

    @Test
    fun `shutdown timeout cancels queued checkpoint without an unsynchronized save`() {
        val harness = Harness(retainedState(600_000L))
        harness.attach()
        val started = CountDownLatch(1)
        val releaseActor = CountDownLatch(1)
        harness.blockActor(started, releaseActor)
        assertTrue(started.await(2, TimeUnit.SECONDS))
        harness.clock.set(PushRetentionClock(1, 10_000L))
        try {
            assertFalse(harness.coordinator.checkpointBeforeShutdown())
            assertEquals(600_000L, harness.durableState.get().active?.retention?.remainingMs)
        } finally {
            releaseActor.countDown()
        }
        harness.snapshot()
        assertEquals(600_000L, harness.durableState.get().active?.retention?.remainingMs)
    }

    @Test
    fun `process restart counts same boot downtime while reboot preserves saved budget`() {
        val saved = retainedState(600_000L)
        val restarted = Harness(saved, initialClock = PushRetentionClock(1, 180_000L))
        restarted.attach()
        assertEquals(420_000L, restarted.durableState.get().active?.retention?.remainingMs)
        val rebooted = Harness(restarted.durableState.get(), initialClock = PushRetentionClock(2, 1_000L))
        rebooted.attach()
        assertEquals(PushProtocol.Retention(420_000L, 2, 1_000L), rebooted.durableState.get().active?.retention)
    }

    @Test
    fun `registration after sleep settles expired ownership before reporting it`() {
        val harness = Harness(retainedState(600_000L))
        harness.attach()
        harness.clock.set(PushRetentionClock(1, 600_000L))
        val registration = harness.snapshot()
        assertTrue(registration.getJSONObject("push_runtime").isNull("active"))
        assertEquals("resume_expired", harness.durableState.get().pendingResults.single().result.failureCode)
        await { harness.cleanupCalls.get() == 1 && harness.events.contains("cleanup") }
        assertEquals(1, harness.cleanupCalls.get())
        assertTrue(harness.events.indexOf("save:result") < harness.events.indexOf("cleanup"))
    }

    @Test
    fun `reconcile and exact resume recheck expiry without waiting for the timer`() {
        for (type in listOf("PUSH_RECONCILE_REQUEST", "EXECUTE_PUSH_FILES")) {
            val initial = retainedState(600_000L)
            val command = requireNotNull(initial.active).command
            val harness = Harness(initial)
            harness.attach()
            harness.clock.set(PushRetentionClock(1, 600_000L))
            val payload = if (type == "EXECUTE_PUSH_FILES") command.toJson() else JSONObject().apply {
                put("jobs", org.json.JSONArray().put(JSONObject().apply {
                    put("job_id", command.jobId)
                    put("attempt", command.attempt)
                    put("artifact_id", command.artifactId)
                }))
            }
            harness.coordinator.handleServerMessage(type, payload)
            await {
                harness.messages.any { it.optString("failure_code") == "resume_expired" } &&
                    harness.cleanupCalls.get() == 1
            }
            assertEquals(0, harness.workerRuns.get())
            assertEquals(1, harness.cleanupCalls.get())
        }
    }

    @Test
    fun `resume retains budget and shutdown checkpoint never cleans a running worker`() {
        val initial = retainedState(600_000L)
        val command = requireNotNull(initial.active).command
        val release = CountDownLatch(1)
        val harness = Harness(initial).apply {
            runExecution = { current, _ ->
                release.await()
                execution(current, interruptionReason = "download_interrupted")
            }
        }
        harness.attach()
        harness.clock.set(PushRetentionClock(1, 100_000L))
        harness.send(command)
        await { harness.workerRuns.get() == 1 }
        assertEquals(500_000L, harness.durableState.get().active?.retention?.remainingMs)
        harness.clock.set(PushRetentionClock(1, 700_000L))
        assertTrue(harness.coordinator.checkpointBeforeShutdown())
        assertEquals(0L, harness.durableState.get().active?.retention?.remainingMs)
        assertFalse(requireNotNull(harness.durableState.get().active).interrupted)
        assertEquals(0, harness.cleanupCalls.get())
        release.countDown()
        await {
            harness.durableState.get().pendingResults.any { it.result.failureCode == "resume_expired" } &&
                harness.cleanupCalls.get() == 1
        }
        assertEquals(1, harness.cleanupCalls.get())
    }

    @Test
    fun `checkpoint failure retains saved budget and expiry failure never releases ownership`() {
        val harness = Harness(retainedState(600_000L))
        harness.attach()
        val saved = harness.durableState.get()
        harness.shouldFailSave = { true }
        harness.clock.set(PushRetentionClock(1, 300_000L))
        harness.runCheckpoint()
        assertEquals(saved, harness.durableState.get())
        harness.clock.set(PushRetentionClock(1, 600_000L))
        assertFalse(harness.coordinator.checkpointBeforeShutdown())
        assertEquals(saved, harness.durableState.get())
        assertEquals(0, harness.cleanupCalls.get())
        assertFalse(harness.messages.any { it.optString("type") == "PUSH_FILES_RESULT" })
        assertEquals("unavailable", harness.snapshot().getJSONObject("push_state").getString("status"))
    }

    @Test
    fun `missing boot count retains existing ownership without assuming a new boot`() {
        val harness = Harness(retainedState(600_000L))
        harness.attach()
        val saved = harness.durableState.get()
        harness.clockUnavailable = true
        assertFalse(harness.coordinator.checkpointBeforeShutdown())
        assertEquals(saved, harness.durableState.get())
        assertEquals(0, harness.cleanupCalls.get())
        assertEquals("unavailable", harness.snapshot().getJSONObject("push_state").getString("status"))
    }

    @Test
    fun `retention clock recovery restores availability and counts unavailable same boot time`() {
        val harness = Harness(retainedState(600_000L), manualRecovery = true)
        harness.attach()
        val saved = harness.durableState.get()
        harness.clockUnavailable = true
        harness.clock.set(PushRetentionClock(1, 60_000L))
        harness.runCheckpoint()

        assertEquals("unavailable", harness.snapshot().getJSONObject("push_state").getString("status"))
        assertEquals(saved, harness.durableState.get())
        assertEquals(0, harness.cleanupCalls.get())

        harness.clock.set(PushRetentionClock(1, 150_000L))
        harness.clockUnavailable = false
        val recovered = harness.runRecovery()

        assertEquals("available", recovered.getJSONObject("push_state").getString("status"))
        assertEquals(PushProtocol.Retention(450_000L, 1, 150_000L), harness.durableState.get().active?.retention)
        assertTrue(requireNotNull(harness.durableState.get().active).interrupted)
        assertEquals(0, harness.workerRuns.get())
        assertEquals(0, harness.cleanupCalls.get())
        assertTrue(harness.durableState.get().pendingResults.isEmpty())
    }

    @Test
    fun `expired resumed budget preserves unsaved terminal outcome across checkpoint and recovery`() {
        for (failureCode in listOf(null, "validation_failed")) {
            val initial = retainedState(600_000L)
            val command = requireNotNull(initial.active).command
            val terminal = execution(command, failureCode = failureCode)
            val expected = (terminal.outcome as PushFilesWorker.Outcome.Completed).result
            val releaseWorker = CountDownLatch(1)
            val allowTerminalSave = AtomicBoolean(false)
            val harness = Harness(initial, manualRecovery = true).apply {
                shouldFailSave = { next ->
                    next.active == null && next.pendingResults.isNotEmpty() && !allowTerminalSave.get()
                }
                runExecution = { _, _ ->
                    releaseWorker.await()
                    terminal
                }
            }
            harness.attach()
            harness.clock.set(PushRetentionClock(1, 100_000L))
            harness.send(command)
            await { harness.workerRuns.get() == 1 }
            harness.clock.set(PushRetentionClock(1, 700_000L))
            releaseWorker.countDown()
            await { harness.events.contains("save:failed") }

            harness.runCheckpoint()
            val retained = requireNotNull(harness.durableState.get().active)
            assertEquals(command, retained.command)
            assertFalse(retained.interrupted)
            assertEquals(0L, retained.retention?.remainingMs)
            assertEquals("unavailable", harness.snapshot().getJSONObject("push_state").getString("status"))
            assertEquals(0, harness.cleanupCalls.get())
            assertTrue(harness.durableState.get().pendingResults.isEmpty())
            assertFalse(harness.messages.any { it.optString("type") == "PUSH_FILES_RESULT" })

            allowTerminalSave.set(true)
            val recovered = harness.runRecovery()
            await { harness.cleanupCalls.get() == 1 && harness.events.contains("cleanup") }
            assertEquals("available", recovered.getJSONObject("push_state").getString("status"))
            val settled = harness.durableState.get()
            assertEquals(null, settled.active)
            assertEquals(expected, settled.pendingResults.single().result)
            assertEquals(expected, settled.completedReceipts.single().result)
            assertEquals(1, harness.workerRuns.get())
            val results = harness.messages.filter { it.optString("type") == "PUSH_FILES_RESULT" }
            assertEquals(1, results.size)
            assertEquals(expected.status, results.single().getString("status"))
            assertEquals(failureCode ?: "", results.single().optString("failure_code"))
            assertEquals(1, harness.events.count { it == "save:result" })
            val persisted = harness.events.indexOf("save:result")
            assertTrue(persisted > harness.events.indexOf("save:failed"))
            assertTrue(persisted < harness.events.indexOf("cleanup"))
            assertTrue(persisted < harness.events.indexOf("send:PUSH_FILES_RESULT"))
        }
    }

    @Test
    fun `legacy interrupted wall clock state gets one durable budget before registration`() {
        val legacy = PushProtocol.stateToJson(retainedState(600_000L)).apply {
            put("state_version", 1)
            getJSONObject("active").apply {
                remove("retention")
                put("interrupted_at", 1L)
            }
        }
        val harness = Harness(PushProtocol.stateFromJson(legacy), initialClock = PushRetentionClock(3, 10_000L))
        val registration = harness.snapshot()
        assertEquals("available", registration.getJSONObject("push_state").getString("status"))
        assertEquals(
            PushProtocol.Retention(PushProtocol.Retention.DURATION_MS, 3, 10_000L),
            harness.durableState.get().active?.retention,
        )
        assertEquals(2, PushProtocol.stateToJson(harness.durableState.get()).getInt("state_version"))
    }

    private fun retainedState(remainingMs: Long) = PushProtocol.State(
        PushProtocol.Active(
            command(), PushProtocol.PHASE_DOWNLOADING, interrupted = true,
            retention = PushProtocol.Retention(remainingMs, 1, 0L),
        ), emptyList(), emptyList(),
    )

    private fun await(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("condition was not reached", condition())
    }

    private fun command() = pushTestCommand(revision = 7L, artifactEtag = "\"etag\"")

    private fun execution(
        command: PushProtocol.Command,
        interruptionReason: String? = null,
        failureCode: String? = null,
    ): PushFilesWorker.Execution {
        val outcome = if (interruptionReason == null) {
            PushFilesWorker.Outcome.Completed(
                PushProtocol.Result(
                    jobId = command.jobId,
                    attempt = command.attempt,
                    status = if (failureCode == null) "success" else "fail",
                    destPath = command.destPath,
                    failureCode = failureCode,
                ),
            )
        } else {
            PushFilesWorker.Outcome.Interrupted(
                reason = interruptionReason,
                detail = "test interruption",
            )
        }
        return PushFilesWorker.Execution(
            outcome,
            File("/tmp/push-coordinator-${command.identity}"),
        )
    }
}
