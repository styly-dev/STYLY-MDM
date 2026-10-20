package com.styly.mdmclient

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class WifiSettingsControllerTest {
    @Test
    fun `deferred startup waits for service readiness and duplicate starts recover only once`() {
        val profile = network("successful-network")
        val harness = Harness(
            initialState = WifiLocalState(
                lastApplied = WifiSavedProfile(REVISION, listOf(profile)),
                bootGeneration = "previous-boot",
            ),
            initialNetworks = emptyList(),
            deferStartup = true,
        )
        assertFalse(harness.controller.startupCompletion().isDone)
        assertTrue(harness.adapter.applyCalls.isEmpty())

        harness.controller.startStartupRecovery()
        harness.controller.startStartupRecovery()
        harness.operationExecutor.runAll()

        assertTrue(harness.controller.startupCompletion().join())
        assertEquals(1, harness.adapter.applyCalls.size)
        assertEquals(1, harness.store.state.bootResults.size)
        harness.controller.startStartupRecovery()
        harness.operationExecutor.runAll()
        assertEquals(1, harness.adapter.applyCalls.size)
    }

    @Test
    fun `wifi command is ignored before canonical registration acknowledgement`() {
        val harness = Harness()

        assertTrue(
            harness.controller.handleServerMessage(
                "EXECUTE_WIFI_APPLY",
                command(OPERATION_ID, REVISION, "unmanaged"),
                canonicalRegistrationAcknowledged = false,
            ),
        )
        harness.operationExecutor.runAll()

        assertNull(harness.store.state.manualOperation)
        assertTrue(harness.store.state.processedOperationIds.isEmpty())
    }

    @Test
    fun `managed apply without an MDM-held recovery profile fails before touching Wi-Fi`() {
        val harness = Harness(initialNetworks = listOf(network("existing-device-network")))

        harness.apply(command(OPERATION_ID, REVISION, "managed", network("candidate")))

        assertEquals(WifiReason.RESTORE_INFO_MISSING, harness.store.state.manualResults.single().reason)
        assertEquals("failed", harness.store.state.manualResults.single().status)
        assertNull(harness.store.state.manualOperation)
        assertNull(harness.store.state.lastApplied)
        assertNull(harness.store.state.initialRecoveryProfile)
        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertEquals(0, harness.adapter.readCurrentCalls)
        assertEquals(0, harness.connectivity.preflightCalls)
    }

    @Test
    fun `recovery registration stores credentials without changing Wi-Fi or boot policy and drives rollback`() {
        val existingDeviceProfile = network("existing-device-network")
        val recoveryProfile = network("registered-recovery-network")
        val candidate = network("candidate")
        val initialState = WifiLocalState(
            managedWifi = false,
            policyEpoch = 8L,
            policyRevision = REVISION_2,
            bootGeneration = "boot-a",
        )
        val harness = Harness(
            initialState = initialState,
            initialNetworks = listOf(existingDeviceProfile),
        )

        harness.apply(command(OPERATION_ID, REVISION, "recovery", recoveryProfile))

        assertEquals(WifiSavedProfile(REVISION, listOf(recoveryProfile)), harness.store.state.initialRecoveryProfile)
        assertNull(harness.store.state.lastApplied)
        assertEquals(initialState.managedWifi, harness.store.state.managedWifi)
        assertEquals(initialState.policyEpoch, harness.store.state.policyEpoch)
        assertEquals(initialState.policyRevision, harness.store.state.policyRevision)
        assertEquals(initialState.bootGeneration, harness.store.state.bootGeneration)
        assertEquals(listOf(existingDeviceProfile), harness.adapter.current)
        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertEquals(1, harness.adapter.readCurrentCalls)

        val correctedRecoveryProfile = network("corrected-recovery-network")
        harness.apply(command(OPERATION_ID_2, REVISION_2, "recovery", correctedRecoveryProfile))
        assertEquals(
            WifiSavedProfile(REVISION_2, listOf(correctedRecoveryProfile)),
            harness.store.state.initialRecoveryProfile,
        )
        assertEquals(2, harness.adapter.readCurrentCalls)

        harness.adapter.afterReconnect = { harness.connectivity.update(50, "other", hasIp = true) }
        harness.apply(command("33333333-3333-4333-8333-333333333333", REVISION, "managed", candidate))
        harness.timeout()

        assertEquals(listOf(correctedRecoveryProfile), harness.adapter.current)
        assertEquals(WifiReason.TIMEOUT_ROLLED_BACK, harness.store.state.manualResults.last().reason)
        assertTrue(harness.store.state.manualResults.last().rolledBack)
        assertNull(harness.store.state.lastApplied)
        assertFalse(harness.store.state.managedWifi)
        assertEquals(listOf(correctedRecoveryProfile), harness.adapter.applyCalls.last().target)
    }

    @Test
    fun `last applied profile cannot be replaced by initial recovery registration`() {
        val lastApplied = recoveryProfile(network("already-applied"))
        val oldSeed = recoveryProfile(network("old-seed"))
        val harness = Harness(initialState = WifiLocalState(
            lastApplied = lastApplied,
            initialRecoveryProfile = oldSeed,
        ))

        harness.apply(command(OPERATION_ID, REVISION, "recovery", network("attempted-seed")))

        assertEquals(lastApplied, harness.store.state.lastApplied)
        assertEquals(oldSeed, harness.store.state.initialRecoveryProfile)
        assertEquals(0, harness.adapter.readCurrentCalls)
        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertEquals("success", harness.store.state.manualResults.single().status)
    }

    @Test
    fun `recovery registration is rejected while a managed apply is active`() {
        val baseline = network("applied-baseline")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = recoveryProfile(baseline)),
            initialNetworks = listOf(baseline),
        )
        harness.apply(command(OPERATION_ID, REVISION, "managed", network("candidate")))
        val readCallsBeforeRecovery = harness.adapter.readCurrentCalls
        assertNotNull(harness.store.state.manualOperation)

        harness.apply(command(OPERATION_ID_2, REVISION_2, "recovery", network("attempted-seed")))

        assertEquals(WifiReason.BUSY, harness.store.state.manualResults.last().reason)
        assertEquals(recoveryProfile(baseline), harness.store.state.lastApplied)
        assertNull(harness.store.state.initialRecoveryProfile)
        assertNotNull(harness.store.state.manualOperation)
        assertEquals(readCallsBeforeRecovery, harness.adapter.readCurrentCalls)
    }

    @Test
    fun `recovery registration is rejected while a boot operation is active`() {
        val bootOperation = WifiBootOperation(
            bootId = "boot-operation",
            generation = "boot-b",
            revision = REVISION,
            profile = listOf(network("boot-profile")),
        )
        val harness = Harness(
            initialState = WifiLocalState(bootOperation = bootOperation),
            runStartupOnInit = false,
        )
        harness.enqueue(command(OPERATION_ID, REVISION_2, "recovery", network("attempted-seed")))
        harness.operationExecutor.runLast()

        assertEquals(WifiReason.BUSY, harness.store.state.manualResults.last().reason)
        assertEquals(bootOperation, harness.store.state.bootOperation)
        assertNull(harness.store.state.initialRecoveryProfile)
        assertEquals(0, harness.adapter.readCurrentCalls)
    }

    @Test
    fun `managed apply requires MDM profile recovery scope`() {
        val baseline = network("baseline")
        val harness = Harness(
            initialState = WifiLocalState(initialRecoveryProfile = recoveryProfile(baseline)),
            initialNetworks = listOf(baseline),
        )
        val payload = command(OPERATION_ID, REVISION, "managed", network("candidate")).apply {
            remove("recovery_scope")
        }

        harness.apply(payload)

        assertEquals(WifiReason.UNSUPPORTED, harness.store.state.manualResults.single().reason)
        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertTrue(harness.adapter.current.any { it.ssid == "baseline" })
    }

    @Test
    fun `managed apply commits after a fresh candidate Wi-Fi network receives an IP`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(initialRecoveryProfile = recoveryProfile(original)),
            initialNetworks = listOf(original),
            initialWifi = listOf(observation(10, original.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.afterReconnect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))

        assertEquals(listOf(candidate), harness.adapter.current)
        assertEquals(WifiSavedProfile(REVISION, listOf(candidate)), harness.store.state.lastApplied)
        assertTrue(harness.store.state.managedWifi)
        assertEquals(1, harness.adapter.disconnectCalls)
        assertEquals(1, harness.adapter.reconnectCalls)
        assertEquals("success", harness.store.state.manualResults.single().status)
        assertEquals(1, harness.connectivity.preflightCalls)
    }

    @Test
    fun `old network handle with candidate SSID and IP is not fresh proof`() {
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(initialRecoveryProfile = recoveryProfile(network("office-old"))),
            initialWifi = listOf(observation(10, candidate.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.afterReconnect = {
            harness.connectivity.update(10, candidate.ssid, hasIp = true)
        }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))

        assertNotNull(harness.store.state.manualOperation)
        harness.timeout()
        assertEquals(WifiReason.TIMEOUT_ROLLED_BACK, harness.store.state.manualResults.single().reason)
        assertNull(harness.store.state.manualOperation)
    }

    @Test
    fun `new Wi-Fi network seen before the original is lost is disconnected before reconnect`() {
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(initialRecoveryProfile = recoveryProfile(network("office-old"))),
            initialWifi = listOf(observation(10, "office-old", hasIp = true)),
        )
        harness.adapter.afterDisconnect = {
            when (harness.adapter.disconnectCalls) {
                1 -> {
                    harness.connectivity.update(11, candidate.ssid, hasIp = true)
                    harness.connectivity.lose(10)
                }
                2 -> harness.connectivity.lose(11)
            }
        }
        harness.adapter.afterReconnect = { harness.connectivity.update(12, candidate.ssid, hasIp = true) }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))

        assertEquals(2, harness.adapter.disconnectCalls)
        assertEquals(1, harness.adapter.reconnectCalls)
        assertEquals(WifiSavedProfile(REVISION, listOf(candidate)), harness.store.state.lastApplied)
    }

    @Test
    fun `wrong SSID and missing IP both time out and restore the MDM recovery profile`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val notReady = listOf(
            observation(21, "other-network", hasIp = true),
            observation(22, candidate.ssid, hasIp = false),
        )
        for ((index, observation) in notReady.withIndex()) {
            val harness = Harness(
                initialState = WifiLocalState(initialRecoveryProfile = recoveryProfile(original)),
                initialNetworks = listOf(original),
                initialWifi = listOf(observation(10, original.ssid, hasIp = true)),
            )
            harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
            harness.adapter.afterReconnect = {
                harness.connectivity.update(observation.networkHandle, observation.ssid!!, observation.hasUsableIp)
            }

            harness.apply(command(if (index == 0) OPERATION_ID else OPERATION_ID_2, REVISION, "managed", candidate))
            harness.timeout()

            assertEquals(listOf(original), harness.adapter.current)
            assertEquals(WifiReason.TIMEOUT_ROLLED_BACK, harness.store.state.manualResults.last().reason)
            assertTrue(harness.store.state.manualResults.last().rolledBack)
            assertNull(harness.store.state.lastApplied)
        }
    }

    @Test
    fun `readiness latched before deadline wins over a queued watchdog`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(initialRecoveryProfile = recoveryProfile(original)),
            initialNetworks = listOf(original),
            initialWifi = listOf(observation(10, original.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.afterReconnect = {
            harness.clock.value = WIFI_MANUAL_TIMEOUT_MS - 100
            harness.connectivity.update(11, candidate.ssid, hasIp = true)
        }
        harness.enqueue(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.operationExecutor.runNext() // accept and enqueue apply
        harness.operationExecutor.runNext() // apply and latch readiness; commit remains queued

        harness.clock.value = WIFI_MANUAL_TIMEOUT_MS
        harness.watchdogs.first().task.run()
        harness.rollbackExecutor.runAll()
        assertEquals(listOf(candidate), harness.adapter.current)
        assertNotNull(harness.store.state.manualOperation)

        harness.operationExecutor.runAll()
        assertEquals(WifiSavedProfile(REVISION, listOf(candidate)), harness.store.state.lastApplied)
        assertEquals("success", harness.store.state.manualResults.single().status)
        assertNull(harness.store.state.manualOperation)
        assertEquals(1, harness.adapter.applyCalls.size)
    }

    @Test
    fun `later disconnection does not roll back a locally successful profile`() {
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(initialRecoveryProfile = recoveryProfile(network("office-old"))),
        )
        harness.adapter.afterReconnect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.connectivity.lose(11)

        assertEquals(listOf(candidate), harness.adapter.current)
        assertEquals(WifiSavedProfile(REVISION, listOf(candidate)), harness.store.state.lastApplied)
        assertEquals(1, harness.adapter.applyCalls.size)
    }

    @Test
    fun `finishing rollback on its executor cannot cancel the next operation alarm`() {
        val candidateA = network("office-a")
        val candidateB = network("office-b")
        val harness = Harness(
            initialState = WifiLocalState(initialRecoveryProfile = recoveryProfile(network("baseline"))),
        )
        harness.beforeCancelAlarm = { operationId ->
            if (operationId == OPERATION_ID) {
                harness.enqueue(command(OPERATION_ID_2, REVISION_2, "managed", candidateB))
                harness.operationExecutor.runAll()
            }
        }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidateA))
        harness.timeout()

        assertEquals(setOf(OPERATION_ID_2), harness.scheduledAlarmIds)
        assertEquals(listOf(OPERATION_ID), harness.canceledAlarmIds)
        assertTrue(harness.store.state.manualResults.single().rolledBack)
        assertEquals(OPERATION_ID_2, harness.store.state.manualOperation?.operationId)
        assertNotEquals(wifiRollbackAlarmIdentity(OPERATION_ID), wifiRollbackAlarmIdentity(OPERATION_ID_2))
    }

    @Test
    fun `service restart restores pending settings without renewing its deadline`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(initialRecoveryProfile = recoveryProfile(original)),
            initialNetworks = listOf(original),
            initialWifi = listOf(observation(10, original.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.afterReconnect = { harness.connectivity.update(11, "wrong", hasIp = true) }
        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        val originalDeadline = requireNotNull(harness.store.state.manualOperation?.deadlineElapsedMillis)
        harness.clock.value = originalDeadline - 1

        val restarted = Harness(
            initialState = harness.store.state,
            initialNetworks = harness.adapter.current,
            generation = "boot-a",
            sharedStore = harness.store,
            sharedAdapter = harness.adapter,
            sharedClock = harness.clock,
            deferStartup = true,
        )

        assertNotNull(restarted.store.state.manualOperation)
        restarted.controller.onRollbackDeadline(OPERATION_ID, originalDeadline)
        assertNotNull(restarted.store.state.manualOperation)
        restarted.controller.startStartupRecovery()
        restarted.operationExecutor.runAll()
        assertNull(restarted.store.state.manualOperation)
        assertEquals(listOf(original), restarted.adapter.current)
        assertEquals(WifiReason.UNKNOWN, restarted.store.state.manualResults.single().reason)
        assertEquals(listOf(original), restarted.adapter.applyCalls.last().target)
        assertEquals(2, restarted.adapter.applyCalls.size)
    }

    @Test
    fun `unmanaged command disables boot replay without reading or changing Wi-Fi settings`() {
        val baseline = WifiSavedProfile(REVISION, listOf(network("saved-baseline")))
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = baseline, managedWifi = true),
            initialNetworks = listOf(network("live-network")),
        )
        harness.connectivity.preflightResult = WifiReason.PERMISSION_DENIED
        val original = harness.adapter.current.toList()

        harness.apply(command(OPERATION_ID, REVISION_2, "unmanaged"))

        assertEquals(original, harness.adapter.current)
        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertEquals(0, harness.adapter.disconnectCalls)
        assertEquals(0, harness.adapter.reconnectCalls)
        assertEquals(0, harness.connectivity.preflightCalls)
        assertFalse(harness.store.state.managedWifi)
        assertEquals(1L, harness.store.state.policyEpoch)
        assertEquals(REVISION_2, harness.store.state.policyRevision)
        assertEquals(baseline, harness.store.state.lastApplied)

        val callsBeforeRestart = harness.adapter.applyCalls.size
        val reboot = Harness(
            initialState = harness.store.state.copy(bootGeneration = "boot-a"),
            initialNetworks = original,
            generation = "boot-b",
            sharedStore = harness.store,
            sharedAdapter = harness.adapter,
        )
        assertTrue(reboot.controller.startupCompletion().join())
        assertEquals(callsBeforeRestart, harness.adapter.applyCalls.size)
        assertFalse(reboot.store.state.managedWifi)
        assertEquals(baseline, reboot.store.state.lastApplied)
    }

    @Test
    fun `duplicate unmanaged command is idempotent`() {
        val harness = Harness()
        val payload = command(OPERATION_ID, REVISION, "unmanaged")

        harness.apply(payload)
        val epoch = harness.store.state.policyEpoch
        harness.apply(payload)

        assertEquals(1L, epoch)
        assertEquals(epoch, harness.store.state.policyEpoch)
        assertTrue(harness.adapter.applyCalls.isEmpty())
    }

    @Test
    fun `operation receipts stay distinct and bounded across unmanaged recovery and deploy`() {
        val initialReceipts = (1..499).map(::receiptId)
        val candidate = network("candidate")
        val harness = Harness(initialState = WifiLocalState(processedOperationIds = initialReceipts))
        harness.adapter.afterReconnect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }

        harness.apply(command(OPERATION_ID, REVISION, "unmanaged"))
        harness.apply(command(OPERATION_ID_2, REVISION_2, "recovery", network("baseline")))
        harness.apply(command(OPERATION_ID_3, REVISION, "managed", candidate))

        val expected = (initialReceipts + OPERATION_ID + OPERATION_ID_2 + OPERATION_ID_3)
            .distinct().takeLast(WIFI_MAX_OPERATION_RECEIPTS)
        assertEquals(expected, harness.store.state.processedOperationIds)
        assertEquals(WIFI_MAX_OPERATION_RECEIPTS, harness.store.state.processedOperationIds.size)
        assertEquals(WIFI_MAX_OPERATION_RECEIPTS, harness.store.state.processedOperationIds.distinct().size)
    }

    @Test
    fun `unmanaged command supersedes older in-flight baseline promotion`() {
        val baseline = WifiSavedProfile(REVISION_2, listOf(network("old-baseline")))
        val candidate = network("candidate")
        val harness = Harness(initialState = WifiLocalState(lastApplied = baseline))
        harness.enqueue(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.operationExecutor.runAll() // connection attempt remains pending without SSID/IP

        harness.apply(command(OPERATION_ID_2, REVISION_2, "unmanaged"))
        harness.connectivity.update(20, candidate.ssid, hasIp = true)
        harness.operationExecutor.runAll()

        assertFalse(harness.store.state.managedWifi)
        assertEquals(baseline, harness.store.state.lastApplied)
        assertEquals(1L, harness.store.state.policyEpoch)
        assertEquals("success", harness.store.state.manualResults.last().status)
    }

    @Test
    fun `managed success reenables boot replay but failed managed apply preserves disabled policy`() {
        val baseline = WifiSavedProfile(REVISION_2, listOf(network("old-baseline")))
        val candidate = network("candidate")
        val success = Harness(initialState = WifiLocalState(lastApplied = baseline, managedWifi = false))
        success.adapter.afterReconnect = { success.connectivity.update(20, candidate.ssid, hasIp = true) }
        success.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        assertTrue(success.store.state.managedWifi)
        assertEquals(WifiSavedProfile(REVISION, listOf(candidate)), success.store.state.lastApplied)

        val failure = Harness(initialState = WifiLocalState(lastApplied = baseline, managedWifi = false))
        failure.adapter.applyFailure = WifiReason.ADD_FAILED
        failure.apply(command(OPERATION_ID_2, REVISION, "managed", candidate))
        assertFalse(failure.store.state.managedWifi)
        assertEquals(baseline, failure.store.state.lastApplied)
    }

    @Test
    fun `candidate API timeout does not restore concurrently with mutation`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(initialRecoveryProfile = recoveryProfile(original)),
            initialNetworks = listOf(original),
        )
        harness.adapter.afterCandidateMutationStarted = {
            harness.clock.value = WIFI_MANUAL_TIMEOUT_MS
            harness.watchdogs.single().task.run()
            harness.rollbackExecutor.runNext()
        }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.rollbackExecutor.runAll()

        assertEquals(listOf(original), harness.adapter.current)
        assertEquals(1, harness.adapter.maxConcurrentMutations)
        assertEquals(WifiReason.TIMEOUT_ROLLED_BACK, harness.store.state.manualResults.single().reason)
        assertTrue(harness.store.state.manualResults.single().rolledBack)
        assertNull(harness.store.state.lastApplied)
    }

    @Test
    fun `commit persistence failure restores settings and durable journal recovers on restart`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(initialRecoveryProfile = recoveryProfile(original)),
            initialNetworks = listOf(original),
            initialWifi = listOf(observation(10, original.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.afterReconnect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }
        harness.enqueue(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.operationExecutor.runNext()
        harness.operationExecutor.runNext()
        harness.store.failWriteWhen = { attempted ->
            if (attempted.lastApplied?.networks == listOf(candidate)) {
                assertEquals(listOf(candidate), harness.adapter.current)
                true
            } else false
        }
        harness.operationExecutor.runAll()
        harness.rollbackExecutor.runAll()

        assertEquals(listOf(original), harness.adapter.current)
        assertNull(harness.store.state.lastApplied)
        assertEquals(WifiManualPhase.APPLYING, harness.store.state.manualOperation?.phase)
        assertTrue(requireNotNull(harness.store.state.manualOperation).settingsChanged)

        val restarted = Harness(
            initialState = harness.store.state,
            initialNetworks = harness.adapter.current,
            generation = "boot-a",
            sharedStore = harness.store,
            sharedAdapter = harness.adapter,
            sharedClock = harness.clock,
        )
        assertNull(restarted.store.state.manualOperation)
        assertNull(restarted.store.state.lastApplied)
        assertEquals(listOf(original), restarted.adapter.current)
    }

    @Test
    fun `restore failure retains local fence`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(initialRecoveryProfile = recoveryProfile(original)),
            initialNetworks = listOf(original),
        )
        harness.adapter.afterCandidateMutationStarted = {
            harness.clock.value = WIFI_MANUAL_TIMEOUT_MS
            harness.watchdogs.single().task.run()
            harness.rollbackExecutor.runNext()
        }
        harness.adapter.restoreFailure = WifiReason.RESTORE_FAILED

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.rollbackExecutor.runAll()

        assertEquals(WifiManualPhase.ROLLING_BACK, harness.store.state.manualOperation?.phase)
        assertEquals(WifiReason.RESTORE_FAILED, harness.store.state.manualResults.single().reason)
        assertFalse(harness.store.state.manualResults.single().rolledBack)
        val calls = harness.adapter.applyCalls.size
        harness.apply(command(OPERATION_ID_2, REVISION_2, "managed", network("other")))
        assertEquals(calls, harness.adapter.applyCalls.size)
    }

    @Test
    fun `actual boot reapplies last successful profile once unless unmanaged`() {
        val baseline = WifiSavedProfile(REVISION, listOf(network("saved")))
        val managed = Harness(
            initialState = WifiLocalState(lastApplied = baseline, bootGeneration = "boot-a"),
            initialNetworks = listOf(network("changed")),
            generation = "boot-b",
        )
        assertTrue(managed.controller.startupCompletion().join())
        assertEquals(listOf(baseline.networks), managed.adapter.applyCalls.map { it.target })

        val unmanagedStore = MemoryStore(
            WifiLocalState(lastApplied = baseline, managedWifi = false, bootGeneration = "boot-a"),
        )
        val unmanaged = Harness(
            initialState = unmanagedStore.state,
            initialNetworks = listOf(network("changed")),
            generation = "boot-b",
            sharedStore = unmanagedStore,
        )
        assertTrue(unmanaged.controller.startupCompletion().join())
        assertTrue(unmanaged.adapter.applyCalls.isEmpty())
        assertEquals("boot-b", unmanaged.store.state.bootGeneration)

        val seedOnly = Harness(
            initialState = WifiLocalState(
                initialRecoveryProfile = baseline,
                bootGeneration = "boot-a",
            ),
            initialNetworks = listOf(network("changed")),
            generation = "boot-b",
        )
        assertTrue(seedOnly.controller.startupCompletion().join())
        assertTrue(seedOnly.adapter.applyCalls.isEmpty())
        assertEquals(baseline, seedOnly.store.state.initialRecoveryProfile)
    }

    @Test
    fun `legacy SSID observation is only used before Android 12 and must match one Wi-Fi network`() {
        assertTrue(usesLegacyWifiSsidLookup(29))
        assertTrue(usesLegacyWifiSsidLookup(30))
        assertFalse(usesLegacyWifiSsidLookup(31))
        assertFalse(networkCallbackNeedsLocationInfo(29))
        assertFalse(networkCallbackNeedsLocationInfo(30))
        assertTrue(networkCallbackNeedsLocationInfo(31))
        assertTrue(legacyWifiSsidCanIdentifyNetwork(activeWifiNetworkCount = 1, ipv4Matches = true))
        assertFalse(legacyWifiSsidCanIdentifyNetwork(activeWifiNetworkCount = 2, ipv4Matches = true))
        assertFalse(legacyWifiSsidCanIdentifyNetwork(activeWifiNetworkCount = 1, ipv4Matches = false))
    }

    private class Harness(
        initialState: WifiLocalState = WifiLocalState(),
        initialNetworks: List<WifiNetworkProfile> = emptyList(),
        initialWifi: List<WifiLocalNetworkObservation> = emptyList(),
        generation: String? = "boot-a",
        val sharedStore: MemoryStore? = null,
        val sharedAdapter: FakeAdapter? = null,
        val sharedClock: MutableClock? = null,
        runStartupOnInit: Boolean = true,
        deferStartup: Boolean = false,
    ) {
        val store = sharedStore ?: MemoryStore(initialState)
        val adapter = sharedAdapter ?: FakeAdapter(initialNetworks)
        val clock = sharedClock ?: MutableClock()
        val operationExecutor = QueueExecutor()
        val rollbackExecutor = QueueExecutor()
        val watchdogs = mutableListOf<ScheduledTask>()
        val scheduledAlarmIds = linkedSetOf<String>()
        val canceledAlarmIds = mutableListOf<String>()
        var beforeCancelAlarm: ((String) -> Unit)? = null
        val connectivity = FakeConnectivity(initialWifi)
        val controller = WifiSettingsController(
            WifiControllerDependencies(
                store = store,
                adapter = adapter,
                connectivity = connectivity,
                nowElapsed = { clock.value },
                bootGeneration = { generation },
                scheduleAlarm = { operationId, _ -> scheduledAlarmIds.add(operationId); true },
                cancelAlarm = { operationId ->
                    beforeCancelAlarm?.invoke(operationId)
                    scheduledAlarmIds.remove(operationId)
                    canceledAlarmIds += operationId
                },
                operationExecutor = operationExecutor,
                rollbackExecutor = rollbackExecutor,
                scheduleWatchdog = { task, delay -> watchdogs += ScheduledTask(task, delay) },
            ),
            autoStart = !deferStartup,
        )

        init {
            if (runStartupOnInit) operationExecutor.runAll()
        }

        fun enqueue(payload: JSONObject) {
            controller.handleServerMessage(
                "EXECUTE_WIFI_APPLY",
                payload,
                canonicalRegistrationAcknowledged = true,
            )
        }

        fun apply(payload: JSONObject) {
            enqueue(payload)
            operationExecutor.runAll()
        }

        fun timeout() {
            clock.value = watchdogs.firstOrNull()?.delayMillis ?: WIFI_MANUAL_TIMEOUT_MS
            watchdogs.lastOrNull()?.task?.run() ?: error("no Wi-Fi watchdog was scheduled")
            rollbackExecutor.runAll()
        }
    }

    private data class ScheduledTask(val task: Runnable, val delayMillis: Long)

    private class MutableClock(var value: Long = 0L)

    private class QueueExecutor : Executor {
        private val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.addLast(command) }
        fun runLast() {
            check(pending.isNotEmpty()) { "no queued task" }
            pending.removeLast().run()
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
            if (failWriteWhen?.invoke(state) == true) throw IllegalStateException("injected state write failure")
            this.state = state
        }
    }

    private class FakeConnectivity(private val initial: List<WifiLocalNetworkObservation>) : WifiLocalConnectivitySource {
        var preflightResult: WifiReason? = null
        var preflightCalls = 0
        private val sessions = mutableListOf<FakeSession>()
        override fun preflight(): WifiReason? {
            preflightCalls++
            return preflightResult
        }
        override fun observe(listener: (WifiLocalConnectivityEvent) -> Unit): WifiLocalConnectivitySession {
            return FakeSession(initial, listener).also { sessions += it }
        }
        fun update(handle: Long, ssid: String, hasIp: Boolean) = sessions.last().update(handle, ssid, hasIp)
        fun lose(handle: Long) = sessions.lastOrNull()?.lose(handle)

        private class FakeSession(
            initial: List<WifiLocalNetworkObservation>,
            private val listener: (WifiLocalConnectivityEvent) -> Unit,
        ) : WifiLocalConnectivitySession {
            private var sequence = initial.maxOfOrNull { it.sequence } ?: 0L
            private val observations = initial.associateBy { it.networkHandle }.toMutableMap()
            private var closed = false
            override fun snapshot(): List<WifiLocalNetworkObservation> = observations.values.toList()
            override fun lastSequence(): Long = sequence
            fun update(handle: Long, ssid: String, hasIp: Boolean) {
                if (closed) return
                val observation = WifiLocalNetworkObservation(handle, ssid, hasIp, ++sequence)
                observations[handle] = observation
                listener(WifiLocalConnectivityEvent.Updated(observation))
            }
            fun lose(handle: Long) {
                if (closed) return
                observations.remove(handle)
                listener(WifiLocalConnectivityEvent.Lost(handle, ++sequence))
            }
            override fun close() { closed = true }
        }
    }

    private data class ApplyCall(
        val target: List<WifiNetworkProfile>,
    )

    private class FakeAdapter(initialNetworks: List<WifiNetworkProfile>) : WifiSettingsAdapter {
        var current = initialNetworks.toMutableList()
        val applyCalls = mutableListOf<ApplyCall>()
        var readCurrentCalls = 0
        var applyFailure: WifiReason? = null
        var restoreFailure: WifiReason? = null
        var afterCandidateMutationStarted: (() -> Unit)? = null
        var afterDisconnect: (() -> Unit)? = null
        var afterReconnect: (() -> Unit)? = null
        var disconnectCalls = 0
        var reconnectCalls = 0
        private var activeMutations = 0
        var maxConcurrentMutations = 0

        override fun readCurrent(): List<WifiConfiguredEntry> {
            readCurrentCalls++
            return current.mapIndexed { index, profile ->
                WifiConfiguredEntry(index, profile.ssid, profile.security, profile.password, true,
                    profile.hidden, true)
            }
        }

        override fun applyProfile(
            target: List<WifiNetworkProfile>,
            shouldContinue: () -> Boolean,
            beforeFirstMutation: () -> WifiReason?,
            onMutationStarted: () -> WifiReason?,
            onMutationFinished: () -> Unit,
        ): WifiReason {
            applyCalls += ApplyCall(target.toList())
            if (applyCalls.size > 1 && restoreFailure != null) return requireNotNull(restoreFailure)
            applyFailure?.let { return it }
            if (!shouldContinue()) return WifiReason.TIMEOUT_ROLLED_BACK
            if (current == target) return WifiReason.NONE
            beforeFirstMutation()?.let { return it }
            onMutationStarted()?.let { return it }
            enterMutation()
            try {
                if (applyCalls.size == 1) afterCandidateMutationStarted?.invoke()
                current = target.toMutableList()
            } finally {
                leaveMutation()
                onMutationFinished()
            }
            return WifiReason.NONE
        }

        override fun disconnectCurrent(
            shouldContinue: () -> Boolean,
            onMutationStarted: () -> WifiReason?,
            onMutationFinished: () -> Unit,
        ): WifiReason = runConnectionMutation(shouldContinue, onMutationStarted, onMutationFinished) {
            disconnectCalls++
            afterDisconnect?.invoke()
        }

        override fun reconnect(
            shouldContinue: () -> Boolean,
            onMutationStarted: () -> WifiReason?,
            onMutationFinished: () -> Unit,
        ): WifiReason = runConnectionMutation(shouldContinue, onMutationStarted, onMutationFinished) {
            reconnectCalls++
            afterReconnect?.invoke()
        }

        private fun runConnectionMutation(
            shouldContinue: () -> Boolean,
            onMutationStarted: () -> WifiReason?,
            onMutationFinished: () -> Unit,
            block: () -> Unit,
        ): WifiReason {
            if (!shouldContinue()) return WifiReason.TIMEOUT_ROLLED_BACK
            onMutationStarted()?.let { return it }
            enterMutation()
            try { block() } finally {
                leaveMutation()
                onMutationFinished()
            }
            return WifiReason.NONE
        }

        private fun enterMutation() {
            activeMutations++
            maxConcurrentMutations = maxOf(maxConcurrentMutations, activeMutations)
        }
        private fun leaveMutation() { activeMutations-- }
    }

    companion object {
        private const val OPERATION_ID = "11111111-1111-4111-8111-111111111111"
        private const val OPERATION_ID_2 = "22222222-2222-4222-8222-222222222222"
        private const val OPERATION_ID_3 = "33333333-3333-4333-8333-333333333333"
        private const val REVISION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        private const val REVISION_2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

        private fun network(ssid: String) = WifiNetworkProfile(ssid, "WPA_PSK", "correct-horse-battery", false)

        private fun recoveryProfile(profile: WifiNetworkProfile) = WifiSavedProfile(REVISION_2, listOf(profile))

        private fun receiptId(value: Int): String =
            "%08x-0000-4000-8000-%012x".format(value, value)

        private fun observation(handle: Long, ssid: String, hasIp: Boolean) =
            WifiLocalNetworkObservation(handle, ssid, hasIp, 1L)

        private fun command(
            operationId: String,
            revision: String,
            mode: String,
            network: WifiNetworkProfile? = null,
        ) = JSONObject().apply {
            put("operation_id", operationId)
            put("revision", revision)
            put("mode", mode)
            put("recovery_scope", WIFI_RECOVERY_SCOPE_MDM_PROFILES)
            put("networks", JSONArray().apply {
                network?.let { profile -> put(JSONObject().apply {
                    put("ssid", profile.ssid)
                    put("security", profile.security)
                    put("password", profile.password)
                    put("hidden", profile.hidden)
                }) }
            })
        }
    }
}
