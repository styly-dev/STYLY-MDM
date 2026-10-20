package com.styly.mdmclient

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class WifiSettingsControllerTest {
    @Test
    fun `candidate apply requires matching verify challenge ACK before saving baseline`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(initialNetworks = listOf(original))
        harness.register()

        harness.apply(command(OPERATION_ID, REVISION, candidate))

        val operation = requireNotNull(harness.store.state.manualOperation)
        assertEquals(WifiManualPhase.VERIFYING, operation.phase)
        assertEquals(listOf(candidate), operation.candidate)
        assertEquals(listOf(snapshot(original)), operation.restoreSnapshot)
        assertNull(harness.store.state.lastApplied)
        assertEquals(listOf(original), harness.adapter.calls.first().expectedSnapshot?.map { it.profile })
        assertNull("candidate writes must not pass the restore snapshot", harness.adapter.calls.first().restoreSnapshot)

        val verifyRequest = harness.messages.last { it.optString("type") == "WIFI_VERIFY_REQUEST" }
        val challenge = verifyRequest.getString("challenge")
        assertEquals(OPERATION_ID, verifyRequest.getString("operation_id"))

        harness.sendVerifyAck(OPERATION_ID, challenge)

        assertNull(harness.store.state.manualOperation)
        assertEquals(WifiSavedProfile(REVISION, listOf(candidate)), harness.store.state.lastApplied)
        assertEquals(
            WifiManualResult(OPERATION_ID, REVISION, "success", WifiReason.NONE, rolledBack = false),
            harness.store.state.manualResults.single(),
        )
    }

    @Test
    fun `unchanged candidate still enters server verification before commit`() {
        val existing = network("already-configured")
        val harness = Harness(initialNetworks = listOf(existing))
        harness.register()

        harness.apply(command(OPERATION_ID, REVISION, existing))

        assertEquals(1, harness.adapter.calls.size)
        assertTrue(harness.adapter.calls.single().noMutation)
        assertEquals(WifiManualPhase.VERIFYING, harness.store.state.manualOperation?.phase)
        assertTrue(harness.messages.any { it.optString("type") == "WIFI_VERIFY_REQUEST" })
        assertNull(harness.store.state.lastApplied)
    }

    @Test
    fun `failed verify journal persistence restores candidate and keeps durable recovery fence`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(initialNetworks = listOf(original))
        harness.register()
        var failureObservedAfterCandidateApply = false
        harness.store.failWriteWhen = { attempted ->
            if (attempted.manualOperation?.phase == WifiManualPhase.VERIFYING) {
                assertEquals(listOf(candidate), harness.adapter.current.map { it.profile })
                failureObservedAfterCandidateApply = true
                true
            } else {
                false
            }
        }

        harness.apply(command(OPERATION_ID, REVISION, candidate))
        harness.rollbackExecutor.runAll()

        assertTrue("the verifying journal write must fail after the API mutation", failureObservedAfterCandidateApply)
        assertEquals(listOf(original), harness.adapter.current.map { it.profile })
        assertNull(harness.store.state.lastApplied)
        assertTrue(harness.store.state.manualResults.isEmpty())
        val durableJournal = requireNotNull(harness.store.state.manualOperation)
        assertEquals(WifiManualPhase.APPLYING, durableJournal.phase)
        assertTrue(durableJournal.settingsChanged)
        assertEquals(listOf(snapshot(original)), durableJournal.restoreSnapshot)
        assertFalse(harness.messages.any {
            it.optString("type") == "WIFI_VERIFY_REQUEST" || it.optString("type") == "WIFI_APPLY_RESULT"
        })

        val callsBeforeBlockedCommand = harness.adapter.calls.size
        harness.apply(command(OPERATION_ID_2, REVISION_2, network("other-candidate")))
        assertEquals(callsBeforeBlockedCommand, harness.adapter.calls.size)
        assertEquals(durableJournal, harness.store.state.manualOperation)

        val restarted = Harness(
            initialState = harness.store.state,
            initialNetworks = harness.adapter.current.map { it.profile },
            generation = "boot-a",
            sharedStore = harness.store,
            sharedAdapter = harness.adapter,
        )
        assertTrue(restarted.controller.startupCompletion().join())
        assertNull(restarted.store.state.manualOperation)
        assertNull(restarted.store.state.lastApplied)
        assertEquals(listOf(original), restarted.adapter.current.map { it.profile })
    }

    @Test
    fun `watchdog restores candidate and records timeout without success`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(initialNetworks = listOf(original))
        harness.register()
        harness.adapter.afterMutationStarted = {
            harness.clock.value = WIFI_MANUAL_TIMEOUT_MS
            harness.watchdogs.single().task.run()
            harness.rollbackExecutor.runNext()
        }

        harness.apply(command(OPERATION_ID, REVISION, candidate))
        harness.rollbackExecutor.runAll()

        assertEquals(listOf(original), harness.adapter.current.map { it.profile })
        assertNull(harness.store.state.lastApplied)
        assertNull(harness.store.state.manualOperation)
        val result = harness.store.state.manualResults.single()
        assertEquals("failed", result.status)
        assertEquals(WifiReason.TIMEOUT_ROLLED_BACK, result.reason)
        assertTrue(result.rolledBack)
        assertFalse(harness.messages.any {
            it.optString("type") == "WIFI_APPLY_RESULT" && it.optString("status") == "success"
        })
    }

    @Test
    fun `result ACK for transient unknown does not acknowledge later rollback result`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(initialNetworks = listOf(original))
        harness.register()
        harness.adapter.afterMutationStarted = {
            harness.clock.value = WIFI_MANUAL_TIMEOUT_MS
            harness.watchdogs.single().task.run()
            harness.rollbackExecutor.runNext()
        }

        harness.enqueueApply(command(OPERATION_ID, REVISION, candidate))
        harness.operationExecutor.runNext() // accept and enqueue the candidate apply
        harness.operationExecutor.runNext() // candidate API call; watchdog claims rollback in-flight
        val transient = harness.store.state.manualResults.single()
        assertEquals("unknown", transient.status)
        assertEquals(WifiReason.UNKNOWN, transient.reason)
        assertNotNull(harness.store.state.manualOperation)

        harness.sendResultAck(OPERATION_ID, REVISION, "unknown", WifiReason.UNKNOWN, rolledBack = false)
        assertTrue(harness.store.state.manualResults.single().acknowledged)
        harness.rollbackExecutor.runAll()

        val terminal = harness.store.state.manualResults.single()
        assertEquals("failed", terminal.status)
        assertEquals(WifiReason.TIMEOUT_ROLLED_BACK, terminal.reason)
        assertTrue(terminal.rolledBack)
        assertFalse("an ACK for the transient result must not settle a later result", terminal.acknowledged)
        assertNull(harness.store.state.manualOperation)
        assertEquals(listOf(original), harness.adapter.current.map { it.profile })
    }

    @Test
    fun `late verify ACK after rollback claim cannot commit an in-flight candidate`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(initialNetworks = listOf(original))
        harness.register()
        harness.adapter.afterMutationStarted = {
            harness.clock.value = WIFI_MANUAL_TIMEOUT_MS
            harness.watchdogs.single().task.run()
            harness.rollbackExecutor.runNext()
        }

        harness.enqueueApply(command(OPERATION_ID, REVISION, candidate))
        harness.operationExecutor.runNext()
        harness.operationExecutor.runNext()
        val callsBeforeAck = harness.adapter.calls.size

        harness.sendVerifyAck(OPERATION_ID, "late-challenge")
        assertNotNull(harness.store.state.manualOperation)
        assertNull(harness.store.state.lastApplied)
        assertEquals(callsBeforeAck, harness.adapter.calls.size)
        harness.rollbackExecutor.runAll()

        assertEquals(listOf(original), harness.adapter.current.map { it.profile })
        assertNull(harness.store.state.lastApplied)
        assertEquals(WifiReason.TIMEOUT_ROLLED_BACK, harness.store.state.manualResults.single().reason)
    }

    @Test
    fun `restore failure keeps durable fence and prevents another apply or boot correction`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val baseline = WifiSavedProfile(REVISION_2, listOf(network("saved-baseline")))
        val initialState = WifiLocalState(lastApplied = baseline, bootGeneration = "boot-a")
        val harness = Harness(initialState, listOf(original), generation = "boot-a")
        harness.register()
        harness.adapter.restoreFailure = WifiReason.RESTORE_FAILED
        harness.adapter.afterMutationStarted = {
            harness.clock.value = WIFI_MANUAL_TIMEOUT_MS
            harness.watchdogs.single().task.run()
            harness.rollbackExecutor.runNext()
        }

        harness.apply(command(OPERATION_ID, REVISION, candidate))
        harness.rollbackExecutor.runAll()

        val fenced = requireNotNull(harness.store.state.manualOperation)
        assertEquals(WifiManualPhase.ROLLING_BACK, fenced.phase)
        assertEquals(WifiReason.RESTORE_FAILED, fenced.failureReason)
        assertEquals(WifiReason.RESTORE_FAILED, harness.store.state.manualResults.single().reason)
        assertFalse(harness.store.state.manualResults.single().rolledBack)

        val callsBeforeSecondCommand = harness.adapter.calls.size
        harness.apply(command(OPERATION_ID_2, REVISION_2, network("other-candidate")))
        assertEquals(callsBeforeSecondCommand, harness.adapter.calls.size)
        assertEquals(WifiReason.BUSY, harness.store.state.manualResults.last().reason)
        assertNotNull(harness.store.state.manualOperation)

        val callsBeforeRestart = harness.adapter.calls.size
        val restarted = Harness(harness.store.state, harness.adapter.current.map { it.profile }, "boot-b", harness.store, harness.adapter)
        assertFalse(restarted.controller.startupCompletion().join())
        assertTrue(harness.adapter.calls.drop(callsBeforeRestart).all { it.restoreSnapshot != null })
        assertEquals(baseline, restarted.store.state.lastApplied)
        assertNotNull(restarted.store.state.manualOperation)
        assertFalse(restarted.adapter.calls.drop(callsBeforeRestart).any {
            it.restoreSnapshot == null && it.target == baseline.networks
        })
    }

    @Test
    fun `pending manual recovery completes before boot correction`() {
        val original = network("office-old")
        val baseline = WifiSavedProfile(REVISION_2, listOf(original))
        val pending = WifiManualOperation(
            operationId = OPERATION_ID,
            revision = REVISION,
            candidate = listOf(network("unfinished-candidate")),
            restoreSnapshot = listOf(snapshot(original)),
            phase = WifiManualPhase.APPLYING,
            deadlineElapsedMillis = WIFI_MANUAL_TIMEOUT_MS,
            failureReason = WifiReason.TIMEOUT_ROLLED_BACK,
            settingsChanged = true,
        )
        val store = MemoryStore(WifiLocalState(
            lastApplied = baseline,
            manualOperation = pending,
            bootGeneration = "boot-a",
        ))
        val adapter = FakeAdapter(listOf(network("unfinished-candidate")))

        val harness = Harness(
            initialState = store.state,
            initialNetworks = adapter.current.map { it.profile },
            generation = "boot-b",
            sharedStore = store,
            sharedAdapter = adapter,
        )

        assertTrue(harness.controller.startupCompletion().join())
        assertNull(store.state.manualOperation)
        assertEquals(listOf(baseline.networks, baseline.networks), adapter.calls.map { it.target })
        assertNotNull(adapter.calls.first().restoreSnapshot)
        assertNull(adapter.calls.last().restoreSnapshot)
        assertEquals("boot-b", store.state.bootGeneration)
        assertEquals("failed", store.state.manualResults.single().status)
        assertEquals("success", store.state.bootResults.single().status)
    }

    @Test
    fun `expired alarm defers to cold startup recovery and restores only once`() {
        val original = network("office-old")
        val candidate = network("unfinished-candidate")
        val deadline = 5_000L
        val pending = WifiManualOperation(
            operationId = OPERATION_ID,
            revision = REVISION,
            candidate = listOf(candidate),
            restoreSnapshot = listOf(snapshot(original)),
            phase = WifiManualPhase.APPLYING,
            deadlineElapsedMillis = deadline,
            settingsChanged = true,
        )
        val store = MemoryStore(WifiLocalState(
            manualOperation = pending,
            bootGeneration = "boot-a",
        ))
        val adapter = FakeAdapter(listOf(candidate))
        val harness = Harness(
            initialState = store.state,
            initialNetworks = listOf(candidate),
            generation = "boot-a",
            sharedStore = store,
            sharedAdapter = adapter,
            runStartupOnInit = false,
        )

        assertFalse(harness.controller.startupCompletion().isDone)
        harness.clock.value = deadline
        harness.controller.onRollbackDeadline(OPERATION_ID, deadline)
        harness.rollbackExecutor.runAll()

        assertTrue("startup owns recovery before its queued task runs", adapter.calls.isEmpty())
        assertEquals(pending, store.state.manualOperation)

        harness.operationExecutor.runAll()

        assertTrue(harness.controller.startupCompletion().join())
        assertEquals(1, adapter.calls.size)
        assertEquals(listOf(original), adapter.calls.single().target)
        assertEquals(listOf(snapshot(original)), adapter.calls.single().restoreSnapshot)
        assertEquals(listOf(original), adapter.current.map { it.profile })
        assertNull(store.state.manualOperation)
        assertEquals("failed", store.state.manualResults.single().status)
        assertEquals(WifiReason.UNKNOWN, store.state.manualResults.single().reason)
        assertTrue(store.state.manualResults.single().rolledBack)

        harness.controller.onRollbackDeadline(OPERATION_ID, deadline)
        harness.rollbackExecutor.runAll()
        assertEquals("a late duplicate alarm must not restore again", 1, adapter.calls.size)
    }

    @Test
    fun `same boot generation never reapplies while a new generation applies once`() {
        val baseline = WifiSavedProfile(REVISION, listOf(network("saved")))
        val store = MemoryStore(WifiLocalState(lastApplied = baseline, bootGeneration = "boot-a"))
        val adapter = FakeAdapter(listOf(network("changed")))

        val first = Harness(store.state, adapter.current.map { it.profile }, "boot-a", store, adapter)
        assertTrue(first.controller.startupCompletion().join())
        assertTrue(adapter.calls.isEmpty())

        val sameGenerationRestart = Harness(store.state, adapter.current.map { it.profile }, "boot-a", store, adapter)
        assertTrue(sameGenerationRestart.controller.startupCompletion().join())
        assertTrue(adapter.calls.isEmpty())

        val newGeneration = Harness(store.state, adapter.current.map { it.profile }, "boot-b", store, adapter)
        assertTrue(newGeneration.controller.startupCompletion().join())
        assertEquals(1, adapter.calls.size)
        assertEquals(baseline.networks, adapter.calls.single().target)
        assertEquals("boot-b", store.state.bootGeneration)

        val duplicateBootNotification = Harness(store.state, adapter.current.map { it.profile }, "boot-b", store, adapter)
        assertTrue(duplicateBootNotification.controller.startupCompletion().join())
        assertEquals(1, adapter.calls.size)
    }

    @Test
    fun `duplicate completed command only replays result`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(initialNetworks = listOf(original))
        harness.register()
        harness.apply(command(OPERATION_ID, REVISION, candidate))
        val challenge = harness.messages.last { it.optString("type") == "WIFI_VERIFY_REQUEST" }.getString("challenge")
        harness.sendVerifyAck(OPERATION_ID, challenge)
        val callsAfterCompletion = harness.adapter.calls.size
        val messagesBeforeReplay = harness.messages.size

        harness.apply(command(OPERATION_ID, REVISION, candidate))

        assertEquals(callsAfterCompletion, harness.adapter.calls.size)
        val replay = harness.messages.drop(messagesBeforeReplay)
        assertEquals(1, replay.size)
        assertEquals("WIFI_APPLY_RESULT", replay.single().getString("type"))
        assertEquals("success", replay.single().getString("status"))
        assertEquals(candidate, harness.store.state.lastApplied?.networks?.single())
    }

    @Test
    fun `success ACK wins over queued watchdog and prevents restoration`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(initialNetworks = listOf(original))
        harness.register()
        harness.apply(command(OPERATION_ID, REVISION, candidate))
        val challenge = harness.messages.last { it.optString("type") == "WIFI_VERIFY_REQUEST" }.getString("challenge")

        harness.sendVerifyAck(OPERATION_ID, challenge)
        harness.clock.value = WIFI_MANUAL_TIMEOUT_MS
        harness.watchdogs.single().task.run()
        harness.rollbackExecutor.runAll()

        assertEquals(WifiSavedProfile(REVISION, listOf(candidate)), harness.store.state.lastApplied)
        assertNull(harness.store.state.manualOperation)
        assertEquals(1, harness.adapter.calls.size)
        assertEquals(WifiReason.NONE, harness.store.state.manualResults.single().reason)
        assertEquals("success", harness.store.state.manualResults.single().status)
    }

    private class Harness(
        initialState: WifiLocalState = WifiLocalState(),
        initialNetworks: List<WifiNetworkProfile> = emptyList(),
        generation: String? = "boot-a",
        val sharedStore: MemoryStore? = null,
        val sharedAdapter: FakeAdapter? = null,
        runStartupOnInit: Boolean = true,
    ) {
        val store = sharedStore ?: MemoryStore(initialState)
        val adapter = sharedAdapter ?: FakeAdapter(initialNetworks)
        val clock = MutableClock()
        val operationExecutor = QueueExecutor()
        val rollbackExecutor = QueueExecutor()
        val watchdogs = mutableListOf<ScheduledTask>()
        val alarms = mutableListOf<Pair<String, Long>>()
        val messages = mutableListOf<JSONObject>()
        val controller = WifiSettingsController(
            WifiControllerDependencies(
                store = store,
                adapter = adapter,
                nowElapsed = { clock.value },
                bootGeneration = { generation },
                scheduleAlarm = { operationId, deadline -> alarms += operationId to deadline; true },
                cancelAlarm = {},
                operationExecutor = operationExecutor,
                rollbackExecutor = rollbackExecutor,
                scheduleWatchdog = { task, delay -> watchdogs += ScheduledTask(task, delay) },
            ),
        )

        init {
            if (runStartupOnInit) operationExecutor.runAll()
        }

        fun register() {
            controller.onCanonicalRegistered(secure = true) { message ->
                messages += JSONObject(message.toString())
                true
            }
            operationExecutor.runAll()
        }

        fun enqueueApply(payload: JSONObject) {
            controller.handleServerMessage("EXECUTE_WIFI_APPLY", payload, secure = true) { message ->
                messages += JSONObject(message.toString())
                true
            }
        }

        fun apply(payload: JSONObject) {
            enqueueApply(payload)
            operationExecutor.runAll()
        }

        fun sendVerifyAck(operationId: String, challenge: String) {
            val payload = JSONObject().put("operation_id", operationId).put("challenge", challenge)
            controller.handleServerMessage("WIFI_VERIFY_ACK", payload, secure = true) { message ->
                messages += JSONObject(message.toString())
                true
            }
            operationExecutor.runAll()
        }

        fun sendResultAck(
            operationId: String,
            revision: String,
            status: String,
            reason: WifiReason,
            rolledBack: Boolean,
        ) {
            val payload = JSONObject().apply {
                put("kind", "manual")
                put("operation_id", operationId)
                put("revision", revision)
                put("status", status)
                put("reason", reason.wireValue)
                put("rolled_back", rolledBack)
            }
            controller.handleServerMessage("WIFI_RESULT_ACK", payload, secure = true) { true }
            operationExecutor.runAll()
        }
    }

    private data class ScheduledTask(val task: Runnable, val delayMillis: Long)

    private class MutableClock(var value: Long = 0L)

    private class QueueExecutor : Executor {
        private val pending = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            pending.addLast(command)
        }

        fun runNext() {
            check(pending.isNotEmpty()) { "no queued task" }
            pending.removeFirst().run()
        }

        fun runAll() {
            var count = 0
            while (pending.isNotEmpty()) {
                check(++count <= 100) { "executor did not quiesce" }
                runNext()
            }
        }
    }

    private class MemoryStore(initial: WifiLocalState) : WifiLocalStore {
        @Volatile var state: WifiLocalState = initial
            private set
        var failWriteWhen: ((WifiLocalState) -> Boolean)? = null

        override fun read(): WifiLocalState = state

        override fun write(state: WifiLocalState) {
            if (failWriteWhen?.invoke(state) == true) {
                throw IllegalStateException("injected Wi-Fi state write failure")
            }
            this.state = state
        }
    }

    private data class ApplyCall(
        val target: List<WifiNetworkProfile>,
        val restoreSnapshot: List<WifiNetworkSnapshot>?,
        val expectedSnapshot: List<WifiNetworkSnapshot>?,
        val noMutation: Boolean,
    )

    private class FakeAdapter(initialNetworks: List<WifiNetworkProfile>) : WifiSettingsAdapter {
        var current: MutableList<WifiNetworkSnapshot> = initialNetworks.mapIndexed { index, profile ->
            snapshot(profile, priority = index)
        }.toMutableList()
        val calls = mutableListOf<ApplyCall>()
        var restoreFailure: WifiReason? = null
        var afterMutationStarted: (() -> Unit)? = null

        override fun readCurrent(): List<WifiConfiguredEntry> = current.mapIndexed { index, entry ->
            WifiConfiguredEntry(
                networkId = index,
                ssid = entry.profile.ssid,
                security = entry.profile.security,
                password = entry.profile.password,
                passwordReadable = true,
                hidden = entry.profile.hidden,
                enabled = entry.enabled,
                priority = entry.priority,
                configurationParcel = entry.configurationParcel ?: "fixture-${entry.profile.ssid}",
            )
        }

        override fun restorableSnapshot(): List<WifiNetworkSnapshot> = current.toList()

        override fun applyProfile(
            target: List<WifiNetworkProfile>,
            shouldContinue: () -> Boolean,
            restoreSnapshot: List<WifiNetworkSnapshot>?,
            beforeFirstMutation: () -> WifiReason?,
            expectedSnapshot: List<WifiNetworkSnapshot>?,
            onMutationStarted: () -> WifiReason?,
            onMutationFinished: () -> Unit,
        ): WifiReason {
            val noMutation = restoreSnapshot == null &&
                current.map { it.profile } == target && current.all { it.enabled }
            calls += ApplyCall(target.toList(), restoreSnapshot?.toList(), expectedSnapshot?.toList(), noMutation)

            if (restoreSnapshot != null && restoreFailure != null) return requireNotNull(restoreFailure)
            if (!shouldContinue()) return WifiReason.TIMEOUT_ROLLED_BACK
            if (expectedSnapshot != null && current.toList() != expectedSnapshot) return WifiReason.RESTORE_INFO_MISSING
            if (noMutation) return WifiReason.NONE

            beforeFirstMutation()?.let { return it }
            onMutationStarted()?.let { return it }
            try {
                if (restoreSnapshot == null) afterMutationStarted?.invoke()
                current = (restoreSnapshot ?: target.mapIndexed { index, profile ->
                    snapshot(profile, enabled = true, priority = index)
                }).toMutableList()
            } finally {
                onMutationFinished()
            }
            return WifiReason.NONE
        }

        override fun compareTo(profile: List<WifiNetworkProfile>): String =
            if (current.map { it.profile } == profile) "match" else "mismatch"

        override fun validateFinalState(profile: List<WifiNetworkProfile>, priorApiWriteValidated: Boolean): String =
            if (current.map { it.profile } == profile) "validated" else "mismatch"
    }

    companion object {
        private const val OPERATION_ID = "11111111-1111-4111-8111-111111111111"
        private const val OPERATION_ID_2 = "22222222-2222-4222-8222-222222222222"
        private const val REVISION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        private const val REVISION_2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

        private fun network(ssid: String) = WifiNetworkProfile(
            ssid = ssid,
            security = "WPA_PSK",
            password = "correct-horse-battery",
            hidden = false,
        )

        private fun snapshot(
            profile: WifiNetworkProfile,
            enabled: Boolean = true,
            priority: Int = 0,
        ) = WifiNetworkSnapshot(profile, enabled, priority, "fixture-${profile.ssid}")

        private fun command(operationId: String, revision: String, network: WifiNetworkProfile) = JSONObject().apply {
            put("operation_id", operationId)
            put("revision", revision)
            put("networks", JSONArray().put(JSONObject().apply {
                put("ssid", network.ssid)
                put("security", network.security)
                put("password", network.password)
                put("hidden", network.hidden)
            }))
        }
    }
}
