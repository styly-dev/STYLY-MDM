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
        assertEquals("boot-a", harness.store.state.bootGeneration)
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
    fun `first managed apply records existing auto join state and preserves every network`() {
        val existing = network("existing-device-network")
        val candidate = network("candidate")
        val harness = Harness(initialNetworks = listOf(existing))
        harness.adapter.setAutoJoin(existing.identity, allowed = true)
        harness.adapter.beforeApply = {
            val journal = requireNotNull(harness.store.state.manualOperation)
            val saved = journal.recoverySnapshot.networks.single()
            assertEquals(0, saved.networkId)
            assertEquals(existing.ssid, saved.ssid)
            assertEquals(listOf(existing.security), saved.securities)
            assertTrue(saved.allowed)
            assertTrue(journal.recoverySnapshot.knownCredentials.isEmpty())
        }
        harness.adapter.afterReconnect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))

        assertEquals("success", requireNotNull(harness.store.state.latestResult).status)
        assertEquals(listOf(existing, candidate), harness.adapter.current)
        assertFalse(harness.adapter.autoJoinAllowed(existing.identity))
        assertTrue(harness.adapter.autoJoinAllowed(candidate.identity))
        assertEquals(1, harness.adapter.captureRecoveryCalls)
    }

    @Test
    fun `legacy recovery mode is rejected`() {
        val harness = Harness()
        val payload = command(OPERATION_ID, REVISION, "recovery", network("candidate"))

        harness.apply(payload)

        assertEquals(WifiReason.UNSUPPORTED, requireNotNull(harness.store.state.latestResult).reason)
    }

    @Test
    fun `legacy recovery scope is rejected`() {
        val harness = Harness()
        val payload = command(OPERATION_ID, REVISION, "managed", network("candidate"))
            .put("recovery_scope", "mdm_profiles")

        harness.apply(payload)

        assertEquals(WifiReason.UNSUPPORTED, requireNotNull(harness.store.state.latestResult).reason)
        assertNull(harness.store.state.manualOperation)
        assertEquals(0, harness.adapter.captureRecoveryCalls)
    }

    @Test
    fun `managed apply commits after a fresh candidate Wi-Fi network receives an IP`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
            initialNetworks = listOf(original),
            initialWifi = listOf(observation(10, original.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.afterReconnect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))

        assertEquals(listOf(original, candidate), harness.adapter.current)
        assertEquals(WifiSavedProfile(REVISION, listOf(candidate)), harness.store.state.lastApplied)
        assertTrue(harness.store.state.managedWifi)
        assertEquals(1, harness.adapter.disconnectCalls)
        assertEquals(1, harness.adapter.reconnectCalls)
        assertEquals("success", requireNotNull(harness.store.state.latestResult).status)
        assertEquals(1, harness.connectivity.preflightCalls)
    }

    @Test
    fun `old network handle with candidate SSID and IP is not fresh proof`() {
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(network("office-old"))),
            initialWifi = listOf(observation(10, candidate.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.afterReconnect = {
            harness.connectivity.update(10, candidate.ssid, hasIp = true)
        }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))

        assertNotNull(harness.store.state.manualOperation)
        harness.timeout()
        assertEquals(WifiReason.TIMEOUT_ROLLED_BACK, requireNotNull(harness.store.state.latestResult).reason)
        assertNull(harness.store.state.manualOperation)
    }

    @Test
    fun `new Wi-Fi network seen before the original is lost is disconnected before reconnect`() {
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(network("office-old"))),
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
    fun `wrong SSID and missing IP restore prior auto join state`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val notReady = listOf(
            observation(21, "other-network", hasIp = true),
            observation(22, candidate.ssid, hasIp = false),
        )
        for ((index, observation) in notReady.withIndex()) {
            val harness = Harness(
                initialState = WifiLocalState(lastApplied = savedProfile(original)),
                initialNetworks = listOf(original),
                initialWifi = listOf(observation(10, original.ssid, hasIp = true)),
            )
            harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
            harness.adapter.afterReconnect = {
                harness.connectivity.update(observation.networkHandle, observation.ssid!!, observation.hasUsableIp)
            }

            harness.apply(command(if (index == 0) OPERATION_ID else OPERATION_ID_2, REVISION, "managed", candidate))
            harness.timeout()

            assertEquals(listOf(original, candidate), harness.adapter.current)
            assertEquals(WifiReason.TIMEOUT_ROLLED_BACK, requireNotNull(harness.store.state.latestResult).reason)
            assertTrue(requireNotNull(harness.store.state.latestResult).rolledBack)
            assertEquals(savedProfile(original), harness.store.state.lastApplied)
            assertTrue(harness.adapter.autoJoinAllowed(original.identity))
            assertFalse(harness.adapter.autoJoinAllowed(candidate.identity))
        }
    }

    @Test
    fun `known same SSID credentials and prior disabled auto join are restored`() {
        val original = network("same-network")
        val candidate = original.copy(password = "new-shared-secret")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
            initialNetworks = listOf(original),
        )
        harness.adapter.setAutoJoin(original.identity, allowed = false)
        harness.adapter.afterReconnect = { harness.connectivity.update(20, "other-network", hasIp = true) }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.timeout()

        assertEquals(listOf(original), harness.adapter.current)
        assertFalse(harness.adapter.autoJoinAllowed(original.identity))
        assertTrue(requireNotNull(harness.store.state.latestResult).rolledBack)
    }

    @Test
    fun `unknown old password cannot be restored when the same SSID password is overwritten`() {
        val oldUnknown = network("same-network").copy(password = "unknown-old-secret")
        val candidate = oldUnknown.copy(password = "new-shared-secret")
        val harness = Harness(initialNetworks = listOf(oldUnknown))
        harness.adapter.afterReconnect = { harness.connectivity.update(20, "other-network", hasIp = true) }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.timeout()

        assertEquals(listOf(candidate), harness.adapter.current)
        assertTrue(harness.adapter.autoJoinAllowed(candidate.identity))
        assertTrue(requireNotNull(harness.store.state.latestResult).rolledBack)
    }

    @Test
    fun `readiness latched before deadline wins over a queued watchdog`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
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
        assertEquals(listOf(original, candidate), harness.adapter.current)
        assertNotNull(harness.store.state.manualOperation)

        harness.operationExecutor.runAll()
        assertEquals(WifiSavedProfile(REVISION, listOf(candidate)), harness.store.state.lastApplied)
        assertEquals("success", requireNotNull(harness.store.state.latestResult).status)
        assertNull(harness.store.state.manualOperation)
        assertEquals(1, harness.adapter.applyCalls.size)
    }

    @Test
    fun `later disconnection does not roll back a locally successful profile`() {
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(network("office-old"))),
            initialNetworks = listOf(network("office-old")),
        )
        harness.adapter.afterReconnect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.connectivity.lose(11)

        assertEquals(listOf(network("office-old"), candidate), harness.adapter.current)
        assertEquals(WifiSavedProfile(REVISION, listOf(candidate)), harness.store.state.lastApplied)
        assertEquals(1, harness.adapter.applyCalls.size)
    }

    @Test
    fun `finishing rollback on its executor cannot cancel the next operation alarm`() {
        val candidateA = network("office-a")
        val candidateB = network("office-b")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(network("baseline"))),
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
        assertTrue(requireNotNull(harness.store.state.latestResult).rolledBack)
        assertEquals(OPERATION_ID_2, harness.store.state.manualOperation?.operationId)
        assertNotEquals(wifiRollbackAlarmIdentity(OPERATION_ID), wifiRollbackAlarmIdentity(OPERATION_ID_2))
    }

    @Test
    fun `service restart restores pending settings without renewing its deadline`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
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
        assertEquals(listOf(original, candidate), restarted.adapter.current)
        assertEquals(WifiReason.UNKNOWN, requireNotNull(restarted.store.state.latestResult).reason)
        assertEquals(1, restarted.adapter.restoreCalls.size)
        assertEquals(1, restarted.adapter.applyCalls.size)
    }

    @Test
    fun `startup retries a failed rollback while its recovery journal remains fenced`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
            initialNetworks = listOf(original),
        )
        harness.adapter.afterReconnect = { harness.connectivity.update(11, "wrong", hasIp = true) }
        harness.adapter.restoreFailure = WifiReason.RESTORE_FAILED

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.timeout()

        assertNotNull(harness.store.state.manualOperation)
        assertEquals(WifiManualPhase.ROLLING_BACK, harness.store.state.manualOperation?.phase)
        assertEquals(WifiReason.RESTORE_FAILED, requireNotNull(harness.store.state.latestResult).reason)
        assertEquals(listOf(original, candidate), harness.adapter.current)

        harness.adapter.restoreFailure = null
        val restarted = Harness(
            initialState = harness.store.state,
            initialNetworks = harness.adapter.current,
            generation = "boot-a",
            sharedStore = harness.store,
            sharedAdapter = harness.adapter,
            sharedClock = harness.clock,
            deferStartup = true,
        )
        restarted.controller.startStartupRecovery()
        restarted.operationExecutor.runAll()

        assertNull(restarted.store.state.manualOperation)
        assertEquals(listOf(original, candidate), restarted.adapter.current)
        assertTrue(restarted.adapter.autoJoinAllowed(original.identity))
        assertFalse(restarted.adapter.autoJoinAllowed(candidate.identity))
        assertEquals(2, restarted.adapter.restoreCalls.size)
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
    fun `operation receipts stay distinct and bounded across unmanaged and managed deploy`() {
        val initialReceipts = (1..499).map(::receiptId)
        val candidate = network("candidate")
        val harness = Harness(initialState = WifiLocalState(processedOperationIds = initialReceipts))
        harness.adapter.afterReconnect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }

        harness.apply(command(OPERATION_ID, REVISION, "unmanaged"))
        harness.apply(command(OPERATION_ID_2, REVISION_2, "unmanaged"))
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
        assertEquals("success", requireNotNull(harness.store.state.latestResult).status)
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
        failure.adapter.applyFailure = WifiReason.UPDATE_FAILED
        failure.apply(command(OPERATION_ID_2, REVISION, "managed", candidate))
        assertFalse(failure.store.state.managedWifi)
        assertEquals(baseline, failure.store.state.lastApplied)
    }

    @Test
    fun `candidate API timeout does not restore concurrently with mutation`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
            initialNetworks = listOf(original),
        )
        harness.adapter.afterCandidateMutationStarted = {
            harness.clock.value = WIFI_MANUAL_TIMEOUT_MS
            harness.watchdogs.single().task.run()
            harness.rollbackExecutor.runNext()
        }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.rollbackExecutor.runAll()

        assertEquals(listOf(original, candidate), harness.adapter.current)
        assertEquals(1, harness.adapter.maxConcurrentMutations)
        assertEquals(WifiReason.TIMEOUT_ROLLED_BACK, requireNotNull(harness.store.state.latestResult).reason)
        assertTrue(requireNotNull(harness.store.state.latestResult).rolledBack)
        assertEquals(savedProfile(original), harness.store.state.lastApplied)
    }

    @Test
    fun `commit persistence failure restores settings and durable journal recovers on restart`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
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
                assertEquals(listOf(original, candidate), harness.adapter.current)
                true
            } else false
        }
        harness.operationExecutor.runAll()
        harness.rollbackExecutor.runAll()

        assertEquals(listOf(original, candidate), harness.adapter.current)
        assertEquals(savedProfile(original), harness.store.state.lastApplied)
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
        assertEquals(savedProfile(original), restarted.store.state.lastApplied)
        assertEquals(listOf(original, candidate), restarted.adapter.current)
    }

    @Test
    fun `restore failure retains local fence`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
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
        assertEquals(WifiReason.RESTORE_FAILED, requireNotNull(harness.store.state.latestResult).reason)
        assertFalse(requireNotNull(harness.store.state.latestResult).rolledBack)
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
            deferStartup = true,
        )
        managed.adapter.beforeApply = {
            assertEquals("boot-b", managed.store.state.bootGeneration)
        }
        managed.controller.startStartupRecovery()
        managed.controller.startStartupRecovery()
        managed.operationExecutor.runAll()
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

        val empty = Harness(
            initialState = WifiLocalState(bootGeneration = "boot-a"),
            initialNetworks = listOf(network("changed")),
            generation = "boot-b",
        )
        assertTrue(empty.controller.startupCompletion().join())
        assertTrue(empty.adapter.applyCalls.isEmpty())
        assertEquals("boot-b", empty.store.state.bootGeneration)
    }

    @Test
    fun `unchanged verified connected policy commits without mutations or disconnect`() {
        val candidate = network("known-network")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(candidate)),
            initialNetworks = listOf(candidate),
            initialWifi = listOf(observation(10, candidate.ssid, hasIp = true)),
        )
        harness.apply(command(OPERATION_ID, REVISION_2, "managed", candidate))
        assertEquals("success", harness.store.state.latestResult?.status)
        assertEquals(REVISION_2, harness.store.state.policyRevision)
        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertEquals(0, harness.adapter.disconnectCalls)
        assertEquals(0, harness.adapter.captureRecoveryCalls)
        assertTrue(harness.scheduledAlarmIds.isEmpty())
    }

    @Test
    fun `changed password or unverified policy never uses unchanged fast path`() {
        val candidate = network("known-network")
        for (case in listOf("password", "autojoin", "hidden", "no-ip", "unknown-credentials")) {
            val harness = Harness(
                initialState = WifiLocalState(lastApplied = if (case == "unknown-credentials") null else savedProfile(candidate)),
                initialNetworks = listOf(candidate),
                initialWifi = listOf(observation(10, candidate.ssid, hasIp = case != "no-ip")),
            )
            if (case == "autojoin") harness.adapter.setAutoJoin(candidate.identity, false)
            val target = when (case) {
                "password" -> candidate.copy(password = "changed-secret")
                "hidden" -> candidate.copy(hidden = !candidate.hidden)
                else -> candidate
            }
            harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
            harness.adapter.afterReconnect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }
            harness.apply(command(OPERATION_ID, REVISION_2, "managed", target))
            assertEquals(case, 1, harness.adapter.applyCalls.size)
            assertEquals(case, 1, harness.adapter.disconnectCalls)
            assertEquals(case, "success", harness.store.state.latestResult?.status)
        }
    }

    @Test
    fun `unmanaged abandons completed restore failure and stale rollback cannot mutate later`() {
        val candidate = network("candidate")
        val harness = Harness()
        harness.adapter.restoreFailure = WifiReason.RESTORE_FAILED
        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.timeout()
        assertNotNull(harness.store.state.manualOperation)
        harness.apply(command(OPERATION_ID_2, REVISION_2, "unmanaged"))
        assertNull(harness.store.state.manualOperation)
        assertFalse(harness.store.state.managedWifi)
        val restoreCount = harness.adapter.restoreCalls.size
        harness.watchdogs.forEach { it.task.run() }
        harness.rollbackExecutor.runAll()
        assertEquals(restoreCount, harness.adapter.restoreCalls.size)
        harness.adapter.restoreFailure = null
        harness.adapter.afterReconnect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }
        harness.apply(command(OPERATION_ID_3, REVISION, "managed", candidate))
        assertEquals("success", harness.store.state.latestResult?.status)
    }

    @Test
    fun `unmanaged cannot release fence while restore owner can still mutate`() {
        val harness = Harness()
        harness.apply(command(OPERATION_ID, REVISION, "managed", network("candidate")))
        harness.adapter.beforeRestore = {
            harness.apply(command(OPERATION_ID_2, REVISION_2, "unmanaged"))
            assertNotNull(harness.store.state.manualOperation)
            assertTrue(harness.controller.stateSnapshot().getBoolean("busy"))
        }
        harness.adapter.restoreFailure = WifiReason.RESTORE_FAILED
        harness.timeout()
        assertNotNull(harness.store.state.manualOperation)
        harness.apply(command(OPERATION_ID_3, REVISION_2, "unmanaged"))
        assertNull(harness.store.state.manualOperation)
    }

    @Test
    fun `state reports results without reconnect or secrets and removed listeners stop`() {
        val harness = Harness()
        val states = mutableListOf<JSONObject>()
        val listener: () -> Unit = { states += harness.controller.stateSnapshot() }
        harness.controller.addStateListener(listener)
        harness.connectivity.preflightResult = WifiReason.PERMISSION_DENIED
        harness.apply(command(OPERATION_ID, REVISION, "managed", network("private-ssid")))
        val result = states.last().getJSONObject("last_result")
        assertEquals("PERMISSION_DENIED", result.getString("reason"))
        assertEquals(OPERATION_ID, result.getString("operation_id"))
        assertFalse(states.last().getBoolean("busy"))
        assertFalse(states.joinToString().contains("private-ssid"))
        assertFalse(states.joinToString().contains("password"))
        val count = states.size
        harness.controller.removeStateListener(listener)
        harness.apply(command(OPERATION_ID_2, REVISION_2, "unmanaged"))
        assertEquals(count, states.size)
        assertEquals(REVISION_2, harness.controller.stateSnapshot().getString("policy_revision"))
    }

    @Test
    fun `busy rejection is reported while original journal remains active`() {
        val harness = Harness()
        harness.apply(command(OPERATION_ID, REVISION, "managed", network("candidate")))
        harness.apply(command(OPERATION_ID_2, REVISION_2, "managed", network("next")))
        val snapshot = harness.controller.stateSnapshot()
        assertTrue(snapshot.getBoolean("busy"))
        assertEquals("BUSY", snapshot.getJSONObject("last_result").getString("reason"))
        assertEquals(OPERATION_ID, harness.store.state.manualOperation?.operationId)
    }

    @Test
    fun `snapshots use cached readiness and refresh publishes changed permissions`() {
        var reason: WifiReason? = WifiReason.PERMISSION_DENIED
        var checks = 0
        val harness = Harness(readinessCheck = { checks++; reason })
        val baselineChecks = checks
        repeat(5) {
            assertFalse(harness.controller.stateSnapshot().getBoolean("available"))
        }
        assertEquals(baselineChecks, checks)
        reason = null
        harness.controller.refreshReadiness()
        assertFalse(harness.controller.stateSnapshot().getBoolean("available"))
        harness.operationExecutor.runAll()
        assertTrue(harness.controller.stateSnapshot().getBoolean("available"))
        assertEquals(baselineChecks + 1, checks)
    }

    @Test
    fun `unreadable journal remains unknown and unmanaged cannot overwrite it`() {
        val store = MemoryStore(WifiLocalState()).apply { failRead = true }
        val harness = Harness(sharedStore = store)
        assertFalse(harness.controller.startupCompletion().join())
        harness.apply(command(OPERATION_ID, REVISION, "unmanaged"))
        val snapshot = harness.controller.stateSnapshot()
        assertTrue(snapshot.isNull("managed"))
        assertEquals("STORAGE_ERROR", snapshot.getString("unavailable_reason"))
        assertFalse(snapshot.getBoolean("available"))
        assertTrue(store.state.processedOperationIds.isEmpty())
    }

    private class Harness(
        initialState: WifiLocalState = WifiLocalState(),
        initialNetworks: List<WifiNetworkProfile> = emptyList(),
        initialWifi: List<WifiLocalNetworkObservation> = emptyList(),
        generation: String? = "boot-a",
        val sharedStore: MemoryStore? = null,
        val sharedAdapter: FakeAdapter? = null,
        val sharedClock: MutableClock? = null,
        deferStartup: Boolean = false,
        readinessCheck: () -> WifiReason? = { null },
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
                readinessFailure = readinessCheck,
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

        init { if (!deferStartup) operationExecutor.runAll() }

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
        var failRead = false
        override fun read(): WifiLocalState {
            if (failRead) throw IllegalStateException("injected read failure")
            return state
        }
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

    private data class FakeSavedNetwork(
        val networkId: Int,
        var profile: WifiNetworkProfile,
        var autoJoinAllowed: Boolean = true,
    )

    private class FakeAdapter(initialNetworks: List<WifiNetworkProfile>) : WifiSettingsAdapter {
        private val saved = initialNetworks.mapIndexed { index, profile ->
            FakeSavedNetwork(index, profile)
        }.toMutableList()
        val current: List<WifiNetworkProfile> get() = saved.map { it.profile }
        val applyCalls = mutableListOf<ApplyCall>()
        val restoreCalls = mutableListOf<WifiRecoverySnapshot>()
        var captureRecoveryCalls = 0
        var beforeApply: (() -> Unit)? = null
        var applyFailure: WifiReason? = null
        var restoreFailure: WifiReason? = null
        var beforeRestore: (() -> Unit)? = null
        var afterCandidateMutationStarted: (() -> Unit)? = null
        var afterDisconnect: (() -> Unit)? = null
        var afterReconnect: (() -> Unit)? = null
        var disconnectCalls = 0
        var reconnectCalls = 0
        private var activeMutations = 0
        var maxConcurrentMutations = 0

        fun setAutoJoin(identity: String, allowed: Boolean) {
            saved.single { it.profile.identity == identity }.autoJoinAllowed = allowed
        }

        fun autoJoinAllowed(identity: String): Boolean =
            saved.single { it.profile.identity == identity }.autoJoinAllowed

        override fun readCurrent(): List<WifiConfiguredEntry> {
            return saved.flatMap { item ->
                listOf(WifiConfiguredEntry(
                    item.networkId,
                    item.profile.ssid,
                    item.profile.security,
                    item.profile.hidden,
                    item.autoJoinAllowed,
                ))
            }
        }

        override fun captureRecovery(knownProfiles: List<WifiNetworkProfile>): WifiRecoverySnapshot {
            captureRecoveryCalls++
            val entries = readCurrent()
            return WifiRecoverySnapshot(
                networks = entries.groupBy { it.networkId }.map { (networkId, views) ->
                    WifiAutoJoinState(
                        networkId = networkId,
                        ssid = views.first().ssid,
                        securities = views.mapNotNull { it.security }.distinct(),
                        allowed = views.first().autoJoinEnabled,
                    )
                },
                knownCredentials = knownProfiles.filter { known ->
                    entries.any { it.ssid == known.ssid && it.security == known.security }
                },
            )
        }

        override fun applyProfile(
            target: List<WifiNetworkProfile>,
            shouldContinue: () -> Boolean,
            beforeFirstMutation: () -> WifiReason?,
            onMutationStarted: () -> WifiReason?,
            onMutationFinished: () -> Unit,
        ): WifiReason {
            applyCalls += ApplyCall(target.toList())
            beforeApply?.invoke()
            applyFailure?.let { return it }
            if (!shouldContinue()) return WifiReason.TIMEOUT_ROLLED_BACK
            val desired = saved.map { it.copy() }.toMutableList()
            for (profile in target) {
                val index = desired.indexOfFirst { it.profile.identity == profile.identity }
                if (index < 0) desired += FakeSavedNetwork(desired.maxOfOrNull { it.networkId }?.plus(1) ?: 0, profile)
                else desired[index].profile = profile
            }
            val candidateIdentities = target.mapTo(HashSet()) { it.identity }
            desired.forEach { it.autoJoinAllowed = it.profile.identity in candidateIdentities }
            if (saved == desired) return WifiReason.NONE
            beforeFirstMutation()?.let { return it }
            onMutationStarted()?.let { return it }
            enterMutation()
            try {
                if (applyCalls.size == 1) afterCandidateMutationStarted?.invoke()
                saved.clear()
                saved.addAll(desired)
            } finally {
                leaveMutation()
                onMutationFinished()
            }
            return WifiReason.NONE
        }

        override fun restoreRecovery(
            snapshot: WifiRecoverySnapshot,
            candidate: List<WifiNetworkProfile>,
            shouldContinue: () -> Boolean,
            onMutationStarted: () -> WifiReason?,
            onMutationFinished: () -> Unit,
        ): WifiReason {
            restoreCalls += snapshot
            beforeRestore?.invoke()
            restoreFailure?.let { return it }
            if (!shouldContinue()) return WifiReason.UNKNOWN

            val knownByIdentity = snapshot.knownCredentials.associateBy { it.identity }
            val desired = saved.map { it.copy() }.toMutableList()
            val resolved = mutableMapOf<WifiAutoJoinState, FakeSavedNetwork>()
            for (entry in snapshot.networks) {
                val existing = desired.firstOrNull { it.networkId == entry.networkId && it.profile.ssid == entry.ssid }
                    ?: desired.singleOrNull { savedNetwork ->
                        savedNetwork.profile.ssid == entry.ssid && savedNetwork.profile.security in entry.securities
                    }
                    ?: return WifiReason.RESTORE_FAILED
                resolved[entry] = existing
            }
            for ((entry, existing) in resolved) {
                existing.autoJoinAllowed = entry.allowed
            }
            for (network in candidate) {
                val existing = desired.firstOrNull { it.profile.identity == network.identity } ?: continue
                val known = knownByIdentity[network.identity]
                if (known != null) existing.profile = known
                val existedBefore = snapshot.networks.any {
                    (it.networkId == existing.networkId && it.ssid == existing.profile.ssid) ||
                        (it.ssid == existing.profile.ssid && existing.profile.security in it.securities)
                }
                if (!existedBefore) {
                    existing.autoJoinAllowed = false
                }
            }
            if (saved == desired) return WifiReason.NONE
            onMutationStarted()?.let { return it }
            enterMutation()
            try {
                saved.clear()
                saved.addAll(desired)
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

        private fun savedProfile(profile: WifiNetworkProfile) = WifiSavedProfile(REVISION_2, listOf(profile))

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
