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
    fun `startup recovers journals without reapplying a cached Wi-Fi profile`() {
        val baseline = savedProfile(network("successful-network"))
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = baseline),
            initialNetworks = listOf(network("current-network")),
            deferStartup = true,
        )
        assertFalse(harness.controller.startupCompletion().isDone)

        harness.controller.startStartupRecovery()
        harness.controller.startStartupRecovery()
        harness.operationExecutor.runAll()

        assertTrue(harness.controller.startupCompletion().join())
        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertEquals(baseline, harness.store.state.lastApplied)
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
        harness.adapter.afterSelect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))

        assertEquals("success", requireNotNull(harness.store.state.latestResult).status)
        assertEquals(listOf(existing, candidate), harness.adapter.current)
        assertFalse(harness.adapter.autoJoinAllowed(existing.identity))
        assertTrue(harness.adapter.autoJoinAllowed(candidate.identity))
        assertEquals(1, harness.adapter.captureRecoveryCalls)
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
        harness.adapter.afterSelect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))

        assertEquals(listOf(original, candidate), harness.adapter.current)
        assertEquals(WifiSavedProfile(REVISION, listOf(candidate)), harness.store.state.lastApplied)
        assertTrue(harness.store.state.managedWifi)
        assertEquals(1, harness.adapter.disconnectCalls)
        assertEquals("success", requireNotNull(harness.store.state.latestResult).status)
        assertEquals(1, harness.connectivity.preflightCalls)
    }

    @Test
    fun `auto return false selects candidates in order without starting failback scans`() {
        val original = network("old")
        val preferred = network("preferred")
        val fallback = network("fallback")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
            initialNetworks = listOf(original),
            initialWifi = listOf(observation(10, original.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.afterSelect = { selected ->
            if (selected == preferred) harness.connectivity.update(11, selected.ssid, hasIp = true)
        }

        harness.apply(command(
            OPERATION_ID, REVISION, "managed", autoReturn = false,
            networks = listOf(preferred, fallback),
        ))

        assertEquals(listOf(preferred), harness.adapter.selectCalls)
        assertEquals(1, harness.adapter.applyCalls.size)
        assertEquals(WifiSavedProfile(REVISION, listOf(preferred, fallback), false), harness.store.state.lastApplied)
        assertEquals("success", requireNotNull(harness.store.state.latestResult).status)
        assertEquals(0, harness.priorityScanSource.startCalls)
        assertEquals(WifiPriorityMonitorStatus.OFF.wireValue, harness.controller.stateSnapshot().getString("priority_monitor"))
    }

    @Test
    fun `four ordered networks are accepted`() {
        val original = network("old")
        val candidates = (1..WIFI_MAX_NETWORKS).map { network("priority-$it") }
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
            initialNetworks = listOf(original),
            initialWifi = listOf(observation(10, original.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.afterSelect = { selected -> harness.connectivity.update(11, selected.ssid, hasIp = true) }

        harness.apply(command(
            OPERATION_ID, REVISION, "managed", autoReturn = false, networks = candidates,
        ))

        assertEquals("success", harness.store.state.latestResult?.status)
        assertEquals(candidates, harness.store.state.lastApplied?.networks)
        assertEquals(listOf(candidates.first()), harness.adapter.selectCalls)

        val parsed = WifiSettingsProtocol.parseApplyCommand(command(
            OPERATION_ID_2, REVISION, "managed", autoReturn = true, networks = candidates,
        ))
        assertEquals(WIFI_MAX_NETWORKS, parsed.networks.size)
        assertTrue(parsed.autoReturn)
    }

    @Test
    fun `five ordered networks are rejected before configuration mutation`() {
        val harness = Harness()
        val candidates = (1..WIFI_MAX_NETWORKS + 1).map { network("priority-$it") }

        harness.apply(command(
            OPERATION_ID, REVISION, "managed", autoReturn = false, networks = candidates,
        ))

        assertEquals(WifiReason.UNSUPPORTED_NETWORK_LIMIT, harness.store.state.latestResult?.reason)
        assertEquals(0, harness.adapter.captureRecoveryCalls)
        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertTrue(harness.adapter.selectCalls.isEmpty())
    }

    @Test
    fun `priority candidate timer is bound to its candidate index`() {
        val original = network("old")
        val preferred = network("preferred")
        val middle = network("middle")
        val fallback = network("fallback")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
            initialNetworks = listOf(original),
            initialWifi = listOf(observation(10, original.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }

        harness.apply(command(
            OPERATION_ID, REVISION, "managed", autoReturn = false,
            networks = listOf(preferred, middle, fallback),
        ))
        val firstCandidateTimeout = harness.priorityTimeouts.single().task
        assertEquals(listOf(preferred), harness.adapter.selectCalls)

        firstCandidateTimeout.run()
        harness.operationExecutor.runAll()
        assertEquals(listOf(preferred, middle), harness.adapter.selectCalls)
        assertEquals(2, harness.priorityTimeouts.size)

        // The old timeout must not advance the still-waiting middle candidate.
        firstCandidateTimeout.run()
        harness.operationExecutor.runAll()
        assertEquals(listOf(preferred, middle), harness.adapter.selectCalls)

        harness.connectivity.update(12, middle.ssid, hasIp = true)
        harness.operationExecutor.runAll()
        assertEquals("success", requireNotNull(harness.store.state.latestResult).status)
        assertEquals(listOf(preferred, middle, fallback), harness.store.state.lastApplied?.networks)
    }

    @Test
    fun `auto return uses fresh higher candidate scan and selection-only rollback preserves result and credentials`() {
        val preferred = network("preferred")
        val current = network("current")
        val saved = WifiSavedProfile(REVISION, listOf(preferred, current), true)
        val priorResult = WifiManualResult(OPERATION_ID, REVISION, "success", WifiReason.NONE, false)
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = saved, latestResult = priorResult, policyRevision = REVISION),
            initialNetworks = saved.networks,
            initialWifi = listOf(observation(10, current.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.selectFailure = { WifiReason.UPDATE_FAILED }

        harness.priorityScanSource.emit(WifiPriorityScanEvent(
            WifiPriorityMonitorStatus.MONITORING, setOf(preferred.identity), "fresh_scan_results",
        ))
        harness.operationExecutor.runAll()
        harness.rollbackExecutor.runAll()

        assertEquals(listOf(preferred), harness.adapter.selectCalls)
        assertEquals(listOf(emptyList<WifiNetworkProfile>()), harness.adapter.restoreCandidates)
        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertEquals(saved, harness.store.state.lastApplied)
        val result = requireNotNull(harness.store.state.latestResult)
        assertNotEquals(priorResult.operationId, result.operationId)
        assertEquals(WifiReason.UNREACHABLE, result.reason)
        assertTrue(result.rolledBack)
    }

    @Test
    fun `failed auto return waits five minutes after recovery before another switch`() {
        val preferred = network("preferred")
        val current = network("current")
        val saved = WifiSavedProfile(REVISION, listOf(preferred, current), true)
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = saved, policyRevision = REVISION),
            initialNetworks = saved.networks,
            initialWifi = listOf(observation(10, current.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        harness.adapter.selectFailure = { WifiReason.UPDATE_FAILED }
        val scan = WifiPriorityScanEvent(
            WifiPriorityMonitorStatus.MONITORING, setOf(preferred.identity), "fresh_scan_results",
        )

        harness.priorityScanSource.emit(scan)
        harness.operationExecutor.runAll()
        harness.rollbackExecutor.runAll()
        harness.operationExecutor.runAll()
        assertTrue(requireNotNull(harness.store.state.latestResult).rolledBack)
        assertEquals(listOf(preferred), harness.adapter.selectCalls)

        // Immediate and passive scan results must not repeatedly interrupt the restored Wi-Fi.
        harness.priorityScanSource.emit(WifiPriorityScanEvent(WifiPriorityMonitorStatus.DEGRADED))
        harness.operationExecutor.runAll()
        assertEquals(WifiPriorityMonitorStatus.DEGRADED.wireValue,
            harness.controller.stateSnapshot().getString("priority_monitor"))
        harness.priorityScanSource.emit(scan)
        harness.operationExecutor.runAll()
        assertEquals(listOf(preferred), harness.adapter.selectCalls)

        harness.clock.value += 5 * 60_000L - 1L
        harness.priorityScanSource.emit(scan)
        harness.operationExecutor.runAll()
        assertEquals(listOf(preferred), harness.adapter.selectCalls)
        harness.clock.value += 1L
        harness.priorityScanSource.emit(scan)
        harness.operationExecutor.runAll()
        assertEquals(listOf(preferred, preferred), harness.adapter.selectCalls)
    }

    @Test
    fun `partial auto return waits before retrying an even higher candidate`() {
        val preferred = network("preferred")
        val middle = network("middle")
        val current = network("current")
        val saved = WifiSavedProfile(REVISION, listOf(preferred, middle, current), true)
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = saved, policyRevision = REVISION),
            initialNetworks = saved.networks,
            initialWifi = listOf(observation(10, current.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        harness.adapter.selectFailure = { if (it == preferred) WifiReason.UPDATE_FAILED else null }
        harness.adapter.afterSelect = { harness.connectivity.update(11, it.ssid, hasIp = true) }
        val bothVisible = WifiPriorityScanEvent(
            WifiPriorityMonitorStatus.MONITORING, setOf(preferred.identity, middle.identity), "fresh_scan_results",
        )
        val preferredVisible = WifiPriorityScanEvent(
            WifiPriorityMonitorStatus.MONITORING, setOf(preferred.identity), "fresh_scan_results",
        )

        harness.priorityScanSource.emit(bothVisible)
        harness.operationExecutor.runAll()
        assertEquals(listOf(preferred, middle), harness.adapter.selectCalls)
        assertEquals("success", harness.store.state.latestResult?.status)

        harness.priorityScanSource.emit(preferredVisible)
        harness.operationExecutor.runAll()
        assertEquals(listOf(preferred, middle), harness.adapter.selectCalls)

        harness.clock.value += 5 * 60_000L
        harness.priorityScanSource.emit(preferredVisible)
        harness.operationExecutor.runAll()
        assertEquals(listOf(preferred, middle, preferred), harness.adapter.selectCalls)
    }

    @Test
    fun `auto return observation failure restarts monitoring and waits before retry`() {
        val preferred = network("preferred")
        val current = network("current")
        val saved = WifiSavedProfile(REVISION, listOf(preferred, current), true)
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = saved, policyRevision = REVISION),
            initialNetworks = saved.networks,
            initialWifi = listOf(observation(10, current.ssid, hasIp = true)),
        )
        val scan = WifiPriorityScanEvent(WifiPriorityMonitorStatus.MONITORING, setOf(preferred.identity))
        harness.connectivity.failNextObserve = true

        harness.priorityScanSource.emit(scan)
        harness.operationExecutor.runAll()
        assertEquals(WifiReason.PERMISSION_DENIED, harness.store.state.latestResult?.reason)
        assertEquals(2, harness.priorityScanSource.startCalls)
        assertTrue(harness.adapter.selectCalls.isEmpty())

        harness.priorityScanSource.emit(scan)
        harness.operationExecutor.runAll()
        assertTrue(harness.adapter.selectCalls.isEmpty())
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        harness.clock.value += 5 * 60_000L
        harness.priorityScanSource.emit(scan)
        harness.operationExecutor.runAll()
        assertEquals(listOf(preferred), harness.adapter.selectCalls)
    }

    @Test
    fun `a new policy revision is not delayed by the previous auto return attempt`() {
        val preferred = network("preferred")
        val current = network("current")
        val saved = WifiSavedProfile(REVISION, listOf(preferred, current), true)
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = saved, policyRevision = REVISION),
            initialNetworks = saved.networks,
            initialWifi = listOf(observation(10, current.ssid, hasIp = true)),
        )
        val scan = WifiPriorityScanEvent(WifiPriorityMonitorStatus.MONITORING, setOf(preferred.identity))
        harness.connectivity.failNextObserve = true
        harness.priorityScanSource.emit(scan)
        harness.operationExecutor.runAll()
        assertEquals(WifiReason.PERMISSION_DENIED, harness.store.state.latestResult?.reason)

        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        harness.adapter.afterSelect = { harness.connectivity.update(11, it.ssid, hasIp = true) }
        harness.apply(command(OPERATION_ID_2, REVISION_2, "managed", autoReturn = true, networks = saved.networks))
        assertEquals("success", harness.store.state.latestResult?.status)
        harness.connectivity.loseAll()
        harness.connectivity.update(12, current.ssid, hasIp = true)
        harness.operationExecutor.runAll()
        val callsBeforeScan = harness.adapter.selectCalls.size
        harness.priorityScanSource.emit(scan)
        harness.operationExecutor.runAll()
        assertEquals(callsBeforeScan + 1, harness.adapter.selectCalls.size)
    }

    @Test
    fun `auto return off does not interrupt a lower network that reconnects during disconnect grace`() {
        val preferred = network("preferred")
        val current = network("current")
        val saved = WifiSavedProfile(REVISION, listOf(preferred, current), false)
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = saved, policyRevision = REVISION),
            initialNetworks = saved.networks,
            initialWifi = listOf(observation(10, current.ssid, hasIp = true)),
        )
        assertEquals(0, harness.priorityScanSource.startCalls)

        harness.connectivity.lose(10)
        harness.operationExecutor.runAll()
        val grace = harness.priorityTimeouts.single()
        assertEquals(8_000L, grace.delayMillis)
        harness.connectivity.update(11, current.ssid, hasIp = true)
        harness.operationExecutor.runAll()
        grace.task.run()
        harness.operationExecutor.runAll()

        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertEquals(saved, harness.store.state.lastApplied)
    }

    @Test
    fun `updated callback without usable IP starts ordered reconnect after grace`() {
        val preferred = network("preferred")
        val current = network("current")
        val saved = WifiSavedProfile(REVISION, listOf(preferred, current), false)
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = saved, policyRevision = REVISION),
            initialNetworks = saved.networks,
            initialWifi = listOf(observation(10, current.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.afterSelect = { profile -> harness.connectivity.update(11, profile.ssid, hasIp = true) }

        // A capability update can remove the usable IP without ConnectivityManager sending Lost.
        harness.connectivity.update(10, current.ssid, hasIp = false)
        harness.operationExecutor.runAll()
        val grace = harness.priorityTimeouts.last()
        assertEquals(8_000L, grace.delayMillis)
        assertTrue(harness.adapter.selectCalls.isEmpty())

        grace.task.run()
        harness.operationExecutor.runAll()
        harness.expirePriorConnectionObservation()

        assertEquals(listOf(preferred), harness.adapter.selectCalls)
        assertEquals(0, harness.priorityScanSource.startCalls)
    }

    @Test
    fun `usable IP returning during updated callback grace cancels ordered reconnect`() {
        val preferred = network("preferred")
        val current = network("current")
        val saved = WifiSavedProfile(REVISION, listOf(preferred, current), false)
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = saved, policyRevision = REVISION),
            initialNetworks = saved.networks,
            initialWifi = listOf(observation(10, current.ssid, hasIp = true)),
        )

        harness.connectivity.update(10, current.ssid, hasIp = false)
        harness.operationExecutor.runAll()
        val grace = harness.priorityTimeouts.last()
        harness.connectivity.update(10, current.ssid, hasIp = true)
        harness.operationExecutor.runAll()
        grace.task.run()
        harness.operationExecutor.runAll()

        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertEquals(saved, harness.store.state.lastApplied)
    }

    @Test
    fun `priority off preserves an already connected lower network after reboot`() {
        val preferred = network("preferred")
        val current = network("current")
        val saved = WifiSavedProfile(REVISION, listOf(preferred, current), false)
        val priorResult = WifiManualResult(OPERATION_ID, REVISION, "success", WifiReason.NONE, false)
        val harness = Harness(
            initialState = WifiLocalState(
                lastApplied = saved,
                latestResult = priorResult,
                policyRevision = REVISION,
            ),
            initialNetworks = saved.networks,
            initialWifi = listOf(observation(10, current.ssid, hasIp = true)),
        )

        assertTrue(harness.priorityTimeouts.isEmpty())
        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertEquals(saved, harness.store.state.lastApplied)
        assertEquals("success", harness.store.state.latestResult?.status)
    }

    @Test
    fun `priority off selects in order after reboot when no network has usable IP`() {
        val preferred = network("preferred")
        val fallback = network("fallback")
        val saved = WifiSavedProfile(REVISION, listOf(preferred, fallback), false)
        val priorResult = WifiManualResult(OPERATION_ID, REVISION, "success", WifiReason.NONE, false)
        val harness = Harness(
            initialState = WifiLocalState(
                lastApplied = saved,
                latestResult = priorResult,
                policyRevision = REVISION,
            ),
            initialNetworks = saved.networks,
        )
        harness.adapter.afterSelect = { profile -> harness.connectivity.update(11, profile.ssid, hasIp = true) }
        val bootSelection = harness.priorityTimeouts.single()
        assertEquals(8_000L, bootSelection.delayMillis)

        bootSelection.task.run()
        harness.operationExecutor.runAll()
        harness.expirePriorConnectionObservation()

        assertEquals(listOf(preferred), harness.adapter.selectCalls)
        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertEquals(saved, harness.store.state.lastApplied)
        val result = requireNotNull(harness.store.state.latestResult)
        assertNotEquals(priorResult.operationId, result.operationId)
        assertEquals("success", result.status)
    }

    @Test
    fun `priority on returns to the preferred network after reboot from a connected lower network`() {
        val preferred = network("preferred")
        val current = network("current")
        val saved = WifiSavedProfile(REVISION, listOf(preferred, current), true)
        val priorResult = WifiManualResult(OPERATION_ID, REVISION, "success", WifiReason.NONE, false)
        val harness = Harness(
            initialState = WifiLocalState(
                lastApplied = saved,
                latestResult = priorResult,
                policyRevision = REVISION,
            ),
            initialNetworks = saved.networks,
            initialWifi = listOf(observation(10, current.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.afterSelect = { profile -> harness.connectivity.update(11, profile.ssid, hasIp = true) }
        assertTrue(harness.priorityTimeouts.isEmpty())
        assertEquals(1, harness.priorityScanSource.startCalls)

        harness.priorityScanSource.emit(WifiPriorityScanEvent(
            WifiPriorityMonitorStatus.MONITORING, setOf(preferred.identity), "fresh_scan_results",
        ))
        harness.operationExecutor.runAll()

        assertEquals(listOf(preferred), harness.adapter.selectCalls)
        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertEquals(saved, harness.store.state.lastApplied)
        val result = requireNotNull(harness.store.state.latestResult)
        assertNotEquals(priorResult.operationId, result.operationId)
        assertEquals("success", result.status)
    }

    @Test
    fun `auto return on retries ordinary disconnected reconnection after a bounded backoff`() {
        val preferred = network("preferred")
        val fallback = network("fallback")
        val saved = WifiSavedProfile(REVISION, listOf(preferred, fallback), true)
        val priorResult = WifiManualResult(OPERATION_ID, REVISION, "success", WifiReason.NONE, false)
        val harness = Harness(
            initialState = WifiLocalState(
                lastApplied = saved,
                latestResult = priorResult,
                policyRevision = REVISION,
            ),
            initialNetworks = saved.networks,
            initialWifi = listOf(observation(10, fallback.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.selectFailure = { WifiReason.UPDATE_FAILED }

        harness.connectivity.lose(10)
        harness.operationExecutor.runAll()
        val disconnectGrace = harness.priorityTimeouts.last()
        disconnectGrace.task.run()
        harness.operationExecutor.runAll()
        harness.expirePriorConnectionObservation()
        harness.rollbackExecutor.runAll()

        assertEquals(listOf(preferred, fallback), harness.adapter.selectCalls)
        val result = requireNotNull(harness.store.state.latestResult)
        assertNotEquals(priorResult.operationId, result.operationId)
        assertEquals("failed", result.status)
        assertEquals(WifiReason.UNREACHABLE, result.reason)
        assertTrue(result.rolledBack)
        assertNull(harness.store.state.manualOperation)
        assertTrue(harness.priorityTimeouts.last().delayMillis >= 5 * 60_000L)
        assertEquals(WifiPriorityMonitorStatus.DEGRADED.wireValue,
            harness.controller.stateSnapshot().getString("priority_monitor"))
    }

    @Test
    fun `old network handle with candidate SSID and IP is not fresh proof`() {
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(network("office-old"))),
            initialNetworks = listOf(network("office-old"), candidate),
            initialWifi = listOf(observation(10, candidate.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.afterSelect = {
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
            initialNetworks = listOf(network("office-old")),
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
        harness.adapter.afterSelect = { harness.connectivity.update(12, candidate.ssid, hasIp = true) }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))

        assertEquals(2, harness.adapter.disconnectCalls)
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
            harness.adapter.afterSelect = {
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
        harness.adapter.afterSelect = { harness.connectivity.update(20, "other-network", hasIp = true) }

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
        harness.adapter.afterSelect = { harness.connectivity.update(20, "other-network", hasIp = true) }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.timeout()

        assertEquals(listOf(candidate), harness.adapter.current)
        assertTrue(harness.adapter.autoJoinAllowed(candidate.identity))
        assertTrue(requireNotNull(harness.store.state.latestResult).rolledBack)
    }

    @Test
    fun `restored personal network may negotiate the other WPA2 WPA3 security view`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
            initialNetworks = listOf(original),
            initialWifi = listOf(observation(10, original.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.afterSelect = { harness.connectivity.update(11, "wrong-network", hasIp = true) }
        harness.adapter.afterRestore = {}

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.timeout()
        assertEquals(WifiManualPhase.RESTORING_CONNECTION, harness.store.state.manualOperation?.phase)

        // PICO may reconnect to the same transition AP using SAE although it used PSK before.
        harness.connectivity.update(12, original.ssid, hasIp = true, security = "SAE")
        harness.operationExecutor.runAll()

        val result = requireNotNull(harness.store.state.latestResult)
        assertEquals(WifiReason.TIMEOUT_ROLLED_BACK, result.reason)
        assertTrue(result.rolledBack)
        assertNull(harness.store.state.manualOperation)
    }

    @Test
    fun `originally disconnected device reports completed settings rollback without restore unconfirmed`() {
        val candidate = network("office-new")
        val harness = Harness(initialNetworks = emptyList())
        harness.adapter.afterSelect = { harness.connectivity.update(11, "wrong-network", hasIp = true) }

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.timeout()

        val result = requireNotNull(harness.store.state.latestResult)
        assertEquals("failed", result.status)
        assertNotEquals(WifiReason.RESTORE_UNCONFIRMED, result.reason)
        assertTrue(result.rolledBack)
        assertNull(harness.store.state.manualOperation)
    }

    @Test
    fun `delayed same-handle identity is captured before any Wi-Fi mutation`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
            initialNetworks = listOf(original),
            initialWifi = listOf(WifiLocalNetworkObservation(10, null, true, 1L, null)),
        )
        harness.adapter.beforeApply = {
            assertEquals(WifiRollbackTarget(original.ssid, "SAE"), harness.store.state.manualOperation?.restoreTarget)
        }
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.afterSelect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }

        harness.enqueue(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.operationExecutor.runAll() // The synchronous seed has IP but no SSID yet.
        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertEquals(WifiManualPhase.PREPARED, harness.store.state.manualOperation?.phase)

        // An identity from another handle cannot be combined with the first handle's IP.
        harness.connectivity.update(11, original.ssid, hasIp = false, security = "SAE")
        harness.operationExecutor.runAll()
        assertTrue(harness.adapter.applyCalls.isEmpty())
        harness.connectivity.lose(11)

        // The callback for the same handle completes the observation and allows mutation.
        harness.connectivity.update(10, original.ssid, hasIp = true, security = "SAE")
        harness.operationExecutor.runAll()

        assertEquals(1, harness.adapter.applyCalls.size)
        assertEquals(WifiSavedProfile(REVISION, listOf(candidate)), harness.store.state.lastApplied)
    }

    @Test
    fun `empty initial snapshot waits for a callback before deciding connection was absent`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
            initialNetworks = listOf(original),
        )
        harness.adapter.beforeApply = {
            assertEquals(WifiRollbackTarget(original.ssid, "WPA_PSK"), harness.store.state.manualOperation?.restoreTarget)
        }
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.afterSelect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }

        harness.enqueue(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.operationExecutor.runAll()
        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertEquals(WifiManualPhase.PREPARED, harness.store.state.manualOperation?.phase)

        harness.connectivity.update(10, original.ssid, hasIp = true)
        harness.operationExecutor.runAll()

        assertEquals(1, harness.adapter.applyCalls.size)
        assertEquals(WifiSavedProfile(REVISION, listOf(candidate)), harness.store.state.lastApplied)
    }

    @Test
    fun `unidentified or ambiguous usable Wi-Fi fails before mutation when observation window expires`() {
        val candidate = network("office-new")
        val cases = listOf(
            listOf(WifiLocalNetworkObservation(10, null, true, 1L, null)) to null,
            listOf(WifiLocalNetworkObservation(10, null, true, 1L, null)) to "second-usable-handle",
        )
        for ((index, testCase) in cases.withIndex()) {
            val harness = Harness(initialWifi = testCase.first)
            harness.enqueue(command(if (index == 0) OPERATION_ID else OPERATION_ID_2, REVISION, "managed", candidate))
            harness.operationExecutor.runAll()
            assertTrue(harness.adapter.applyCalls.isEmpty())

            testCase.second?.let { secondSsid ->
                harness.connectivity.update(11, secondSsid, hasIp = true)
                harness.operationExecutor.runAll()
                assertTrue(harness.adapter.applyCalls.isEmpty())
            }

            harness.expirePriorConnectionObservation()
            val result = requireNotNull(harness.store.state.latestResult)
            assertEquals(WifiReason.VERIFY_FAILED, result.reason)
            assertFalse(result.rolledBack)
            assertTrue(harness.adapter.applyCalls.isEmpty())
            assertNull(harness.store.state.manualOperation)
        }
    }

    @Test
    fun `current connection must resolve to exactly one saved restore identity before mutation`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val cases = listOf(emptyList(), listOf(original, original))
        for ((index, savedNetworks) in cases.withIndex()) {
            val harness = Harness(
                initialNetworks = savedNetworks,
                initialWifi = listOf(WifiLocalNetworkObservation(10, null, true, 1L, null)),
            )
            val operationId = if (index == 0) OPERATION_ID else OPERATION_ID_2
            harness.enqueue(command(operationId, REVISION, "managed", candidate))
            harness.operationExecutor.runAll()

            harness.connectivity.update(10, original.ssid, hasIp = true, security = "SAE")
            harness.operationExecutor.runAll()
            assertTrue(harness.adapter.applyCalls.isEmpty())

            harness.expirePriorConnectionObservation()
            assertEquals(WifiReason.VERIFY_FAILED, harness.store.state.latestResult?.reason)
            assertTrue(harness.adapter.applyCalls.isEmpty())
            assertNull(harness.store.state.manualOperation)
        }
    }

    @Test
    fun `identity first observed after the bounded window cannot start mutation`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialNetworks = listOf(original),
            initialWifi = listOf(WifiLocalNetworkObservation(10, null, true, 1L, null)),
        )

        harness.enqueue(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.operationExecutor.runAll()
        harness.clock.value += WIFI_PRIOR_CONNECTION_OBSERVATION_MS
        harness.connectivity.update(10, original.ssid, hasIp = true, security = "WPA_PSK")
        harness.operationExecutor.runAll()

        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertEquals(WifiReason.VERIFY_FAILED, harness.store.state.latestResult?.reason)
        assertNull(harness.store.state.manualOperation)
    }

    @Test
    fun `delayed identity remains valid after saved network ID is reassigned`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
            initialNetworks = listOf(original),
            initialWifi = listOf(WifiLocalNetworkObservation(10, null, true, 1L, null)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.afterSelect = {
            harness.adapter.reassignNetworkId(original.identity, 77)
            harness.connectivity.update(11, "wrong-network", hasIp = true)
        }
        harness.adapter.afterRestore = {}

        harness.enqueue(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.operationExecutor.runAll()
        harness.connectivity.update(10, original.ssid, hasIp = true, security = "SAE")
        harness.operationExecutor.runAll()
        harness.timeout()

        assertEquals(WifiManualPhase.RESTORING_CONNECTION, harness.store.state.manualOperation?.phase)
        assertEquals(WifiRollbackTarget(original.ssid, "SAE"), harness.store.state.manualOperation?.restoreTarget)
        assertEquals(77, harness.adapter.readCurrent().single { it.ssid == original.ssid }.networkId)
        harness.connectivity.update(12, original.ssid, hasIp = true, security = "WPA_PSK")
        harness.operationExecutor.runAll()

        assertTrue(requireNotNull(harness.store.state.latestResult).rolledBack)
        assertNull(harness.store.state.manualOperation)
    }

    @Test
    fun `known restored SSID is retried after unconfirmed deadline and later confirms same operation`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
            initialNetworks = listOf(original),
            initialWifi = listOf(observation(10, original.ssid, hasIp = true)),
        )
        val pending = createUnconfirmedRestore(harness, original, candidate)
        val unconfirmed = requireNotNull(harness.store.state.latestResult)
        assertEquals(WifiReason.RESTORE_UNCONFIRMED, unconfirmed.reason)
        assertFalse(unconfirmed.rolledBack)
        assertTrue(requireNotNull(harness.store.state.manualOperation).settingsRestored)
        assertTrue(requireNotNull(harness.store.state.manualOperation).restoreUnconfirmedReported)
        assertTrue(harness.controller.policyProgress(pending.operationId, pending.revision).settled)

        harness.adapter.afterSavedReconnect = { target ->
            harness.connectivity.update(90, target.ssid, hasIp = true, security = target.security)
        }
        val retry = requireNotNull(harness.watchdogs.lastOrNull { it.delayMillis == 5 * 60_000L })
        harness.clock.value += retry.delayMillis
        retry.task.run()
        harness.rollbackExecutor.runAll()

        val restored = requireNotNull(harness.store.state.latestResult)
        assertEquals(pending.operationId, restored.operationId)
        assertEquals(WifiReason.TIMEOUT_ROLLED_BACK, restored.reason)
        assertTrue(restored.rolledBack)
        assertNull(harness.store.state.manualOperation)
        assertEquals(listOf(WifiRollbackTarget(original.ssid, "WPA_PSK")), harness.adapter.connectCalls)
    }

    @Test
    fun `process restart after restore unconfirmed resumes observation and saved reconnect retry`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val before = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
            initialNetworks = listOf(original),
            initialWifi = listOf(observation(10, original.ssid, hasIp = true)),
        )
        val pending = createUnconfirmedRestore(before, original, candidate)

        val after = Harness(
            initialState = before.store.state,
            initialNetworks = before.adapter.current,
            initialWifi = listOf(observation(40, "unrelated-network", hasIp = true)),
            sharedStore = before.store,
            sharedAdapter = before.adapter,
            sharedClock = before.clock,
            bootCount = 134,
            deferStartup = true,
        )
        after.controller.startStartupRecovery()
        after.operationExecutor.runAll()

        val resumed = requireNotNull(after.store.state.manualOperation)
        assertEquals(pending.operationId, resumed.operationId)
        assertEquals(WifiManualPhase.RESTORING_CONNECTION, resumed.phase)
        assertEquals(134, resumed.restoreBootCount)
        assertTrue(resumed.restoreUnconfirmedReported)
        assertEquals(WifiReason.RESTORE_UNCONFIRMED, after.store.state.latestResult?.reason)
        assertNotNull(after.watchdogs.lastOrNull { it.delayMillis == 5 * 60_000L })

        after.adapter.afterSavedReconnect = { target ->
            after.connectivity.update(90, target.ssid, hasIp = true, security = target.security)
        }
        val retry = requireNotNull(after.watchdogs.lastOrNull { it.delayMillis == 5 * 60_000L })
        after.clock.value += retry.delayMillis
        retry.task.run()
        after.rollbackExecutor.runAll()

        val result = requireNotNull(after.store.state.latestResult)
        assertEquals(pending.operationId, result.operationId)
        assertTrue(result.rolledBack)
        assertNull(after.store.state.manualOperation)
    }

    @Test
    fun `new policy fences old restore retry and late callback`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val next = network("office-next")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original)),
            initialNetworks = listOf(original),
            initialWifi = listOf(observation(10, original.ssid, hasIp = true)),
        )
        createUnconfirmedRestore(harness, original, candidate)
        val oldRetry = requireNotNull(harness.watchdogs.lastOrNull { it.delayMillis == 5 * 60_000L })
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        harness.adapter.afterSelect = { profile -> harness.connectivity.update(91, profile.ssid, hasIp = true) }
        // The current connection is identifiable, but it does not satisfy the older restore target.
        harness.connectivity.loseAll()
        harness.connectivity.update(90, candidate.ssid, hasIp = true)
        harness.operationExecutor.runAll()

        harness.apply(command(OPERATION_ID_2, REVISION_2, "managed", next))
        assertEquals(REVISION_2, harness.store.state.lastApplied?.revision)
        assertEquals(listOf(next), harness.store.state.lastApplied?.networks)
        assertNull(harness.store.state.manualOperation)

        harness.connectivity.update(90, original.ssid, hasIp = true, security = "WPA_PSK")
        harness.operationExecutor.runAll()
        harness.clock.value += oldRetry.delayMillis
        oldRetry.task.run()
        harness.rollbackExecutor.runAll()

        assertEquals(listOf(next), harness.store.state.lastApplied?.networks)
        assertNull(harness.store.state.manualOperation)
        assertTrue(harness.adapter.connectCalls.isEmpty())
        assertEquals(OPERATION_ID_2, harness.store.state.latestResult?.operationId)
    }

    @Test
    fun `management release fences old restore retry and late callback`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(original), policyRevision = REVISION_2),
            initialNetworks = listOf(original),
            initialWifi = listOf(observation(10, original.ssid, hasIp = true)),
        )
        createUnconfirmedRestore(harness, original, candidate)
        val oldRetry = requireNotNull(harness.watchdogs.lastOrNull { it.delayMillis == 5 * 60_000L })

        harness.controller.stopManagingPolicy(REVISION_2)
        harness.operationExecutor.runAll()
        assertFalse(harness.store.state.managedWifi)
        assertNull(harness.store.state.manualOperation)

        harness.connectivity.update(90, original.ssid, hasIp = true, security = "WPA_PSK")
        harness.operationExecutor.runAll()
        harness.clock.value += oldRetry.delayMillis
        oldRetry.task.run()
        harness.rollbackExecutor.runAll()

        assertFalse(harness.store.state.managedWifi)
        assertNull(harness.store.state.manualOperation)
        assertTrue(harness.adapter.connectCalls.isEmpty())
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
        harness.adapter.afterSelect = {
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
        harness.adapter.afterSelect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }

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
        harness.expirePriorConnectionObservation()

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
        harness.adapter.afterSelect = { harness.connectivity.update(11, "wrong", hasIp = true) }
        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        val originalDeadline = requireNotNull(harness.store.state.manualOperation?.deadlineElapsedMillis)
        harness.clock.value = originalDeadline - 1

        val restarted = Harness(
            initialState = harness.store.state,
            initialNetworks = harness.adapter.current,
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
        harness.adapter.afterSelect = { harness.connectivity.update(11, "wrong", hasIp = true) }
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
    fun `unmanaged command preserves all configured Wi-Fi settings`() {
        val baseline = WifiSavedProfile(REVISION, listOf(network("saved-baseline")))
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = baseline, managedWifi = true, policyRevision = REVISION),
            initialNetworks = listOf(network("live-network")),
        )
        harness.connectivity.preflightResult = WifiReason.PERMISSION_DENIED
        val original = harness.adapter.current.toList()

        harness.apply(command(OPERATION_ID, REVISION_2, "unmanaged"))

        assertEquals(original, harness.adapter.current)
        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertEquals(0, harness.adapter.disconnectCalls)
        assertEquals(0, harness.connectivity.preflightCalls)
        assertFalse(harness.store.state.managedWifi)
        assertEquals(1L, harness.store.state.policyEpoch)
        assertEquals(REVISION_2, harness.store.state.policyRevision)
        assertEquals(baseline, harness.store.state.lastApplied)

        val callsBeforeRestart = harness.adapter.applyCalls.size
        val reboot = Harness(
            initialState = harness.store.state,
            initialNetworks = original,
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
        val harness = Harness(initialState = WifiLocalState(policyRevision = REVISION))
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
        val initialReceipts = (1 until WIFI_MAX_OPERATION_RECEIPTS).map(::receiptId)
        val candidate = network("candidate")
        val harness = Harness(
            initialState = WifiLocalState(processedOperationIds = initialReceipts, policyRevision = REVISION_2),
        )
        harness.adapter.afterSelect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }

        harness.apply(command(OPERATION_ID, REVISION, "unmanaged"))
        val release = requireNotNull(harness.store.state.latestResult).operationId
        harness.apply(command(OPERATION_ID_3, REVISION, "managed", candidate))

        val expected = (initialReceipts + release + OPERATION_ID_3).takeLast(WIFI_MAX_OPERATION_RECEIPTS)
        assertEquals(expected, harness.store.state.processedOperationIds)
        assertEquals(WIFI_MAX_OPERATION_RECEIPTS, harness.store.state.processedOperationIds.distinct().size)
    }

    @Test
    fun `release received during an in-flight apply is applied once the apply settles`() {
        val baseline = WifiSavedProfile(REVISION_2, listOf(network("old-baseline")))
        val candidate = network("candidate")
        val harness = Harness(initialState = WifiLocalState(lastApplied = baseline, policyRevision = REVISION_2))
        harness.enqueue(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.operationExecutor.runAll() // connection attempt remains pending without SSID/IP
        harness.expirePriorConnectionObservation()

        harness.apply(command(OPERATION_ID_2, REVISION_2, "unmanaged"))
        assertTrue(harness.store.state.managedWifi)
        assertNotNull(harness.store.state.manualOperation)

        harness.connectivity.update(20, candidate.ssid, hasIp = true)
        harness.operationExecutor.runAll()

        assertNull(harness.store.state.manualOperation)
        assertEquals(REVISION, harness.store.state.lastApplied?.revision)
        assertFalse(harness.store.state.managedWifi)
        assertEquals(REVISION_2, harness.store.state.policyRevision)
        assertEquals(1L, harness.store.state.policyEpoch)
        assertEquals("success", requireNotNull(harness.store.state.latestResult).status)
    }

    @Test
    fun `managed success updates ownership but failed managed apply preserves disabled policy`() {
        val baseline = WifiSavedProfile(REVISION_2, listOf(network("old-baseline")))
        val candidate = network("candidate")
        val success = Harness(initialState = WifiLocalState(lastApplied = baseline, managedWifi = false))
        success.adapter.afterSelect = { success.connectivity.update(20, candidate.ssid, hasIp = true) }
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
            harness.clock.value = requireNotNull(harness.store.state.manualOperation?.deadlineElapsedMillis)
            harness.watchdogs.single { it.delayMillis == WIFI_MANUAL_TIMEOUT_MS }.task.run()
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
        harness.adapter.afterSelect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }
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
            harness.clock.value = requireNotNull(harness.store.state.manualOperation?.deadlineElapsedMillis)
            harness.watchdogs.single { it.delayMillis == WIFI_MANUAL_TIMEOUT_MS }.task.run()
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
    fun `same desired policy replaces credentials changed outside MDM`() {
        val candidate = network("known-network")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = savedProfile(candidate)),
            initialNetworks = listOf(candidate.copy(password = "changed-by-operator")),
            initialWifi = listOf(observation(10, candidate.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.lose(10) }
        harness.adapter.afterSelect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }

        harness.apply(command(OPERATION_ID, REVISION_2, "managed", candidate))

        assertEquals("success", harness.store.state.latestResult?.status)
        assertEquals(REVISION_2, harness.store.state.policyRevision)
        assertEquals(listOf(candidate), harness.adapter.current)
        assertEquals(1, harness.adapter.disconnectCalls)
        assertEquals(1, harness.adapter.captureRecoveryCalls)
    }

    @Test
    fun `Wi-Fi deployment verifies a fresh connection across existing network configurations`() {
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
            harness.adapter.afterSelect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }
            harness.apply(command(OPERATION_ID, REVISION_2, "managed", target))
            assertEquals(case, 1, harness.adapter.applyCalls.size)
            assertEquals(case, 1, harness.adapter.disconnectCalls)
            assertEquals(case, "success", harness.store.state.latestResult?.status)
        }
    }

    @Test
    fun `new managed attempt recovers a settled rollback failure before writing Wi-Fi`() {
        val original = network("office-old")
        val candidate = network("office-new")
        val harness = Harness(initialNetworks = listOf(original))
        harness.adapter.restoreFailure = WifiReason.RESTORE_FAILED

        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.timeout()
        assertEquals(OPERATION_ID, harness.store.state.manualOperation?.operationId)

        harness.apply(command(OPERATION_ID_2, REVISION, "managed", candidate))
        assertEquals(OPERATION_ID, harness.store.state.manualOperation?.operationId)
        assertEquals(OPERATION_ID_2, harness.store.state.latestResult?.operationId)
        assertEquals(WifiReason.RESTORE_FAILED, harness.store.state.latestResult?.reason)
        assertEquals(2, harness.adapter.restoreCalls.size)
        assertEquals(1, harness.adapter.applyCalls.size)

        harness.adapter.restoreFailure = null
        harness.adapter.afterSelect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }
        harness.apply(command(OPERATION_ID_3, REVISION, "managed", candidate))

        assertNull(harness.store.state.manualOperation)
        assertEquals(OPERATION_ID_3, harness.store.state.latestResult?.operationId)
        assertEquals("success", harness.store.state.latestResult?.status)
        assertEquals(3, harness.adapter.restoreCalls.size)
        assertEquals(2, harness.adapter.applyCalls.size)
    }

    @Test
    fun `unmanaged abandons completed restore failure and stale rollback cannot mutate later`() {
        val candidate = network("candidate")
        val harness = Harness(initialState = WifiLocalState(managedWifi = false))
        harness.adapter.restoreFailure = WifiReason.RESTORE_FAILED
        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.timeout()
        assertNotNull(harness.store.state.manualOperation)
        assertTrue(harness.controller.readyForPolicy())
        harness.controller.stopManagingPolicy(REVISION_2)
        harness.operationExecutor.runAll()
        assertNull(harness.store.state.manualOperation)
        assertFalse(harness.store.state.managedWifi)
        val restoreCount = harness.adapter.restoreCalls.size
        harness.watchdogs.forEach { it.task.run() }
        harness.rollbackExecutor.runAll()
        assertEquals(restoreCount, harness.adapter.restoreCalls.size)
        harness.adapter.restoreFailure = null
        harness.adapter.afterSelect = { harness.connectivity.update(11, candidate.ssid, hasIp = true) }
        harness.apply(command(OPERATION_ID_3, REVISION, "managed", candidate))
        assertEquals("success", harness.store.state.latestResult?.status)
    }

    @Test
    fun `unmanaged cannot release fence while restore owner can still mutate`() {
        val harness = Harness()
        harness.apply(command(OPERATION_ID, REVISION, "managed", network("candidate")))
        harness.adapter.beforeRestore = {
            assertFalse(harness.controller.readyForPolicy())
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
        harness.apply(command(OPERATION_ID_2, REVISION_2, "managed", network("other-ssid")))
        assertEquals(count, states.size)
        assertEquals(OPERATION_ID_2, harness.controller.stateSnapshot().getJSONObject("last_result").getString("operation_id"))
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

    @Test
    fun `headset reapply works offline with the last received revision and shares Wi-Fi rollback`() {
        val harness = PackageHarness()
        val version = ConfigurationVersion(REVISION_2, REVISION)
        val deploymentTime = 1_795_000_000_000L
        val policy = packagePolicy().put("group_name", "Studio").put("deployed_at_ms", deploymentTime)
        var connected = true
        harness.queueSuccessfulWifi()
        harness.receive(policy, isCurrent = { connected })
        harness.runWifiTasks()
        harness.worker.runAll()
        val firstOperation = requireNotNull(harness.wifi.store.state.latestResult).operationId
        assertEquals(ConfigurationApplyStatus.APPLIED, harness.controller.screenSnapshot()?.applyStatus)
        assertEquals("Studio", harness.controller.screenSnapshot()?.groupName)
        assertTrue(harness.controller.screenSnapshot()?.canReapply == true)

        val clear = JSONObject().put("package_id", JSONObject.NULL).put("revision", JSONObject.NULL)
            .put("settings", JSONObject()).put("group_name", "Studio")
        connected = false
        // An obsolete socket cannot replace the last accepted policy.
        harness.receive(clear, isCurrent = { connected })
        assertTrue(harness.controller.screenSnapshot()?.canReapply == true)

        harness.wifi.adapter.afterSelect = {}
        assertTrue(harness.reapply(version))
        val manual = requireNotNull(harness.store.attempt())
        assertNotEquals(firstOperation, manual.wifiOperationId)
        assertTrue(manual.retryIds.isEmpty())
        assertEquals(ConfigurationApplyStatus.APPLYING, harness.controller.screenSnapshot()?.applyStatus)
        assertFalse(harness.reapply(version))
        harness.runWifiTasks()
        harness.wifi.timeout()
        harness.worker.runAll()
        assertEquals(ConfigurationApplyStatus.INCOMPLETE, harness.controller.screenSnapshot()?.applyStatus)
        assertTrue(harness.store.attempt()?.results?.getJSONObject("wifi")?.getBoolean("rolled_back") == true)
        assertEquals(deploymentTime, harness.controller.screenSnapshot()?.deployedAtMillis)
        assertEquals(2, harness.scalarWrites.count)

        assertTrue(harness.controller.screenSnapshot()?.canReapply == true)
        harness.queueSuccessfulWifi()
        assertTrue(harness.reapply(version))
        harness.runWifiTasks()
        harness.worker.runAll()
        assertEquals(ConfigurationApplyStatus.APPLIED, harness.controller.screenSnapshot()?.applyStatus)
        assertEquals(3, harness.scalarWrites.count)

        connected = true
        harness.receive(policy, isCurrent = { connected })
        assertEquals(3, harness.scalarWrites.count)

        harness.receive(clear, isCurrent = { connected })
        connected = false
        assertFalse(harness.reapply(version))
        assertEquals("Studio", harness.controller.screenSnapshot()?.groupName)
        assertNull(harness.controller.screenSnapshot()?.version)
        assertEquals(3, harness.scalarWrites.count)
    }

    @Test
    fun `last package remains available for manual reapply after an offline restart until cleared`() {
        val before = PackageHarness()
        val version = ConfigurationVersion(REVISION_2, REVISION)
        val deployedAt = 1_795_000_000_000L
        val policy = packagePolicy().put("group_name", "Studio").put("deployed_at_ms", deployedAt)
        before.queueSuccessfulWifi()
        before.receive(policy)
        before.runWifiTasks()
        before.worker.runAll()

        val after = before.restart(bootCount = 134)
        assertEquals("Studio", after.controller.screenSnapshot()?.groupName)
        assertEquals(deployedAt, after.controller.screenSnapshot()?.deployedAtMillis)
        assertFalse(after.controller.screenSnapshot()?.canReapply == true)
        after.finishWifiStartup()
        assertEquals(1, after.scalarWrites.count)
        assertEquals(1, after.wifi.adapter.applyCalls.size)

        after.queueSuccessfulWifi()
        assertTrue(after.controller.screenSnapshot()?.canReapply == true)
        assertTrue(after.reapply(version))
        after.runWifiTasks()
        after.worker.runAll()
        assertEquals(ConfigurationApplyStatus.APPLIED, after.controller.screenSnapshot()?.applyStatus)
        assertEquals(2, after.scalarWrites.count)
        assertEquals(2, after.wifi.adapter.applyCalls.size)

        after.receive(JSONObject().put("package_id", JSONObject.NULL).put("revision", JSONObject.NULL)
            .put("settings", JSONObject()))
        val cleared = after.restart()
        cleared.finishWifiStartup()
        assertNull(cleared.controller.screenSnapshot())
        assertFalse(cleared.reapply(version))
        assertEquals(2, cleared.scalarWrites.count)
    }

    @Test
    fun `summary-only clients display the assignment without applying it`() {
        val harness = PackageHarness()
        val version = ConfigurationVersion(REVISION_2, REVISION)
        val summary = version.toJson().put("settings", JSONObject())
            .put("group_name", "Gallery").put("deployed_at_ms", JSONObject.NULL)
        harness.receive(summary, supportsApply = false)
        val screen = requireNotNull(harness.controller.screenSnapshot())
        assertEquals("Gallery", screen.groupName)
        assertEquals(version, screen.version)
        assertNull(screen.deployedAtMillis)
        assertNull(screen.applyStatus)
        assertFalse(screen.canReapply)
        assertFalse(harness.reapply(version))
        assertEquals(0, harness.scalarWrites.count)
        assertNull(harness.store.lastPolicy)
        assertNull(harness.controller.snapshot())
    }

    @Test
    fun `package recovers a durable Wi-Fi success before its own finish without applying again`() {
        val before = PackageHarness()
        before.queueSuccessfulWifi()
        before.receive(packagePolicy())
        val checkpoint = requireNotNull(before.store.attempt())
        assertEquals(70, checkpoint.results.getJSONObject("brightness").getInt("value"))
        assertFalse(checkpoint.results.has("wifi"))

        // Only the Wi-Fi worker sees the terminal commit before the process stops.
        before.runWifiTasks()
        val receipt = requireNotNull(before.wifi.store.state.latestResult)
        assertEquals("success", receipt.status)
        assertEquals(checkpoint.wifiOperationId, receipt.operationId)
        assertEquals("applying", before.controller.snapshot()?.getString("status"))

        val after = before.restart()
        after.receive(packagePolicy(retryId = OPERATION_ID_3))
        assertNull(after.controller.snapshot())
        after.finishWifiStartup()

        val report = requireNotNull(after.controller.snapshot())
        assertEquals("complete", report.getString("status"))
        assertEquals("applied", report.getJSONObject("results").getJSONObject("wifi").getString("status"))
        assertEquals(70, report.getJSONObject("results").getJSONObject("brightness").getInt("value"))
        assertFalse(report.has("wifi_operation_id"))
        assertFalse(report.toString().contains(network("candidate").password))
        assertEquals(checkpoint.bootCount, after.store.attempt()?.bootCount)
        assertNull(after.store.attempt()?.wifiOperationId)
        assertEquals(1, after.scalarWrites.count)
        assertEquals(1, after.wifi.adapter.applyCalls.size)
    }

    @Test
    fun `Wi-Fi package reports one Wi-Fi terminal result across package process recovery`() {
        val before = PackageHarness()
        before.queueSuccessfulWifi()
        val policy = packagePolicy(autoReturn = false)
        before.receive(policy)
        val checkpoint = requireNotNull(before.store.attempt())
        assertFalse(checkpoint.results.has("wifiPriority"))
        before.runWifiTasks()
        assertEquals("success", before.wifi.store.state.latestResult?.status)

        val after = before.restart()
        after.receive(policy)
        after.finishWifiStartup()

        val report = requireNotNull(after.controller.snapshot())
        val results = report.getJSONObject("results")
        assertEquals("applied", results.getJSONObject("wifi").getString("status"))
        assertFalse(results.has("wifiPriority"))
        assertFalse(after.store.attempt()?.results?.has("wifiPriority") == true)
        assertEquals(1, after.scalarWrites.count)
        assertEquals(1, after.wifi.adapter.applyCalls.size)
    }

    @Test
    fun `hidden auto return policy reports unsupported before any credentials mutation`() {
        val harness = PackageHarness()
        val hidden = network("hidden").copy(hidden = true)
        val policy = packagePolicy(autoReturn = true).apply {
            getJSONObject("settings").getJSONObject("wifi").put("networks", JSONArray().put(JSONObject().apply {
                put("ssid", hidden.ssid)
                put("security", hidden.security)
                put("password", hidden.password)
                put("hidden", true)
            }))
        }
        harness.receive(policy)
        harness.runWifiTasks()
        harness.worker.runAll()

        val results = requireNotNull(harness.controller.snapshot()).getJSONObject("results")
        assertEquals("unsupported", results.getJSONObject("wifi").getString("status"))
        assertEquals("UNSUPPORTED_HIDDEN_NETWORK", results.getJSONObject("wifi").getString("reason"))
        assertFalse(results.has("wifiPriority"))
        assertEquals(0, harness.wifi.adapter.captureRecoveryCalls)
        assertTrue(harness.wifi.adapter.applyCalls.isEmpty())
    }

    @Test
    fun `a recovered success keeps its original boot and a new boot reapplies scalars without Wi-Fi`() {
        val before = PackageHarness()
        before.queueSuccessfulWifi()
        before.receive(packagePolicy())
        val checkpoint = requireNotNull(before.store.attempt())
        before.runWifiTasks()

        val newBoot = checkpoint.bootCount + 1
        val after = before.restart(bootCount = newBoot, deferSdk = true)
        after.receive(packagePolicy())
        after.finishWifiStartup()
        assertEquals("complete", after.controller.snapshot()?.getString("status"))
        assertEquals(checkpoint.bootCount, after.store.attempt()?.bootCount)
        assertEquals(1, after.scalarWrites.count)
        assertEquals(1, after.wifi.adapter.applyCalls.size)

        after.queueSuccessfulWifi()
        after.enableSdk()
        val next = requireNotNull(after.store.attempt())
        assertEquals(newBoot, next.bootCount)
        assertNull(next.wifiOperationId)
        after.runWifiTasks()
        after.worker.runAll()

        after.receive(packagePolicy())
        after.runWifiTasks()
        after.worker.runAll()
        assertEquals("complete", after.store.attempt()?.status)
        assertEquals(newBoot, after.store.attempt()?.bootCount)
        assertEquals("applied", after.store.attempt()?.results?.getJSONObject("wifi")?.getString("status"))
        assertEquals(2, after.scalarWrites.count)
        assertEquals(1, after.wifi.adapter.applyCalls.size)
    }

    @Test
    fun `a package restart during Wi-Fi mutation records the exact startup rollback without replay`() {
        val original = network("office-old")
        val candidate = network("candidate")
        val before = PackageHarness(wifi = Harness(initialNetworks = listOf(original)))
        before.receive(packagePolicy())
        before.runWifiTasks()
        val operation = requireNotNull(before.wifi.store.state.manualOperation)
        assertEquals(WifiManualPhase.APPLYING, operation.phase)
        assertTrue(operation.settingsChanged)
        assertEquals(before.store.attempt()?.wifiOperationId, operation.operationId)
        assertFalse(before.wifi.adapter.autoJoinAllowed(original.identity))
        assertTrue(before.wifi.adapter.autoJoinAllowed(candidate.identity))

        val after = before.restart()
        assertNull(after.controller.snapshot())
        assertFalse(after.controller.screenSnapshot()?.canReapply == true)
        after.finishWifiStartup()
        val report = requireNotNull(after.controller.snapshot())
        val wifiResult = report.getJSONObject("results").getJSONObject("wifi")
        assertEquals("complete", report.getString("status"))
        assertEquals("failed", wifiResult.getString("status"))
        assertTrue(wifiResult.getBoolean("rolled_back"))
        assertEquals(operation.operationId, after.wifi.store.state.latestResult?.operationId)
        assertNull(after.wifi.store.state.manualOperation)
        assertTrue(after.wifi.adapter.autoJoinAllowed(original.identity))
        assertFalse(after.wifi.adapter.autoJoinAllowed(candidate.identity))
        assertTrue(after.controller.screenSnapshot()?.canReapply == true)

        after.receive(packagePolicy())
        after.runWifiTasks()
        assertEquals(1, after.scalarWrites.count)
        assertEquals(1, after.wifi.adapter.applyCalls.size)
        assertEquals(1, after.wifi.adapter.restoreCalls.size)
    }

    @Test
    fun `a restored handoff without exact terminal proof stays interrupted until exact Retry`() {
        val before = PackageHarness()
        before.receive(packagePolicy())
        val checkpoint = requireNotNull(before.store.attempt())
        val operationId = requireNotNull(checkpoint.wifiOperationId)
        val receipts = listOf(
            null,
            WifiManualResult(OPERATION_ID, REVISION, "success", WifiReason.NONE, false),
            WifiManualResult(operationId, REVISION_2, "success", WifiReason.NONE, false),
            WifiManualResult(operationId, REVISION, "unknown", WifiReason.UNKNOWN, false),
        )
        for (receipt in receipts) {
            val after = before.restart(WifiLocalState(latestResult = receipt), bootCount = checkpoint.bootCount + 1)
            after.finishWifiStartup()
            after.receive(packagePolicy())
            assertEquals("interrupted", after.controller.snapshot()?.getString("status"))
            assertEquals(1, after.scalarWrites.count)
            assertTrue(after.wifi.adapter.applyCalls.isEmpty())
        }

        val after = before.restart()
        after.finishWifiStartup()
        after.receive(packagePolicy(retryId = OPERATION_ID_3))
        val retried = requireNotNull(after.store.attempt())
        assertEquals(setOf(OPERATION_ID_3), retried.retryIds)
        assertFalse(JSONObject(requireNotNull(after.store.lastPolicy)).has("retry_id"))
        assertNotEquals(operationId, retried.wifiOperationId)
        assertEquals(2, after.scalarWrites.count)

        val retryRestart = after.restart()
        retryRestart.finishWifiStartup()
        retryRestart.receive(packagePolicy(retryId = OPERATION_ID_3))
        assertEquals("interrupted", retryRestart.controller.snapshot()?.getString("status"))
        assertEquals(2, retryRestart.scalarWrites.count)
        assertTrue(retryRestart.wifi.adapter.applyCalls.isEmpty())
    }

    @Test
    fun `a failed package checkpoint leaves the claim fenced and never queues Wi-Fi`() {
        val before = PackageHarness()
        before.store.failWriteWhen = { it.latest?.wifiOperationId != null }
        before.receive(packagePolicy())
        before.runWifiTasks()

        assertEquals("interrupted", before.controller.snapshot()?.getString("status"))
        assertNull(before.store.attempt()?.wifiOperationId)
        assertEquals(1, before.scalarWrites.count)
        assertTrue(before.wifi.adapter.applyCalls.isEmpty())

        val after = before.restart()
        after.finishWifiStartup()
        after.receive(packagePolicy())
        assertEquals("interrupted", after.controller.snapshot()?.getString("status"))
        assertEquals(1, after.scalarWrites.count)
        assertTrue(after.wifi.adapter.applyCalls.isEmpty())
    }

    @Test
    fun `a failed package finish retains Wi-Fi proof while a new policy waits`() {
        val harness = PackageHarness()
        harness.queueSuccessfulWifi()
        harness.receive(packagePolicy())
        harness.store.failWriteWhen = { it.latest?.status == "complete" }
        harness.runWifiTasks()
        harness.worker.runAll()
        val receipt = requireNotNull(harness.wifi.store.state.latestResult)
        assertEquals("success", receipt.status)
        assertEquals("applying", harness.controller.snapshot()?.getString("status"))

        val clear = JSONObject().put("package_id", JSONObject.NULL).put("revision", JSONObject.NULL)
            .put("settings", JSONObject())
        harness.receive(clear)
        harness.runWifiTasks()
        assertEquals(receipt, harness.wifi.store.state.latestResult)
        assertTrue(harness.wifi.store.state.managedWifi)

        // Simulate a separate Wi-Fi result taking the latest-result slot during the package write failure.
        harness.wifi.apply(command(OPERATION_ID, REVISION_2, "managed"))
        assertEquals(OPERATION_ID, harness.wifi.store.state.latestResult?.operationId)
        assertTrue(harness.wifi.store.state.managedWifi)

        harness.store.failWriteWhen = null
        harness.receive(clear)
        harness.runWifiTasks()
        harness.worker.runAll()
        assertEquals("complete", harness.store.attempt()?.status)
        assertEquals("applied", harness.store.attempt()?.results?.getJSONObject("wifi")?.getString("status"))
        assertFalse(harness.wifi.store.state.managedWifi)
        assertNull(harness.controller.snapshot())
        assertEquals(1, harness.scalarWrites.count)
        assertEquals(1, harness.wifi.adapter.applyCalls.size)
    }

    @Test
    fun `same revision after reboot keeps a connected lower network and reapplies scalars only`() {
        val preferred = network("preferred")
        val fallback = network("fallback")
        val version = ConfigurationVersion(REVISION_2, REVISION)
        val applied = ConfigurationAttempt(
            version, bootCount = 133, status = "complete",
            results = JSONObject()
                .put("brightness", settingOutcome("applied", 70))
                .put("wifi", settingOutcome("applied").put("rolled_back", false)),
        )
        val harness = PackageHarness(
            wifi = Harness(
                initialState = WifiLocalState(
                    lastApplied = WifiSavedProfile(REVISION, listOf(preferred, fallback), autoReturn = false),
                    policyRevision = REVISION,
                ),
                initialNetworks = listOf(preferred, fallback),
                initialWifi = listOf(observation(10, fallback.ssid, hasIp = true)),
                bootCount = 134,
            ),
            store = PackageStore(ConfigurationJournal().withAttempt(applied).encode()),
            bootCount = 134,
        )

        harness.receive(packagePolicy(networks = listOf(preferred, fallback)))
        harness.runWifiTasks()
        harness.worker.runAll()

        assertEquals(0, harness.wifi.adapter.disconnectCalls)
        assertTrue(harness.wifi.adapter.selectCalls.isEmpty())
        assertTrue(harness.wifi.adapter.applyCalls.isEmpty())
        assertEquals(1, harness.scalarWrites.count)
        val attempt = requireNotNull(harness.store.attempt())
        assertEquals(134, attempt.bootCount)
        assertEquals("complete", attempt.status)
        assertEquals("applied", attempt.results.getJSONObject("wifi").getString("status"))
        assertTrue(harness.wifi.store.state.managedWifi)

        // An explicit reapply from the headset still applies Wi-Fi.
        assertTrue(harness.reapply(version))
        harness.wifi.operationExecutor.runAll()
        assertEquals(1, harness.wifi.adapter.applyCalls.size)
    }

    @Test
    fun `package release received during an automatic switch is applied after the switch settles`() {
        val preferred = network("preferred")
        val current = network("current")
        val wifi = Harness(
            initialState = WifiLocalState(
                lastApplied = WifiSavedProfile(REVISION, listOf(preferred, current), autoReturn = true),
                policyRevision = REVISION,
            ),
            initialNetworks = listOf(preferred, current),
            initialWifi = listOf(observation(10, current.ssid, hasIp = true)),
        )
        val harness = PackageHarness(wifi)
        wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
        wifi.priorityScanSource.emit(WifiPriorityScanEvent(
            WifiPriorityMonitorStatus.MONITORING, setOf(preferred.identity), "fresh_scan_results",
        ))
        wifi.operationExecutor.runAll()
        val switching = requireNotNull(wifi.store.state.manualOperation)
        assertTrue(switching.selectionOnly)

        harness.receive(JSONObject()
            .put("package_id", JSONObject.NULL)
            .put("revision", JSONObject.NULL)
            .put("settings", JSONObject()))
        wifi.operationExecutor.runAll()
        assertTrue(wifi.store.state.managedWifi)
        assertEquals(switching.operationId, wifi.store.state.manualOperation?.operationId)

        wifi.connectivity.update(11, preferred.ssid, hasIp = true)
        wifi.operationExecutor.runAll()
        harness.worker.runAll()

        assertNull(wifi.store.state.manualOperation)
        assertFalse(wifi.store.state.managedWifi)
        val scansBefore = wifi.priorityScanSource.startCalls
        wifi.priorityScanSource.emit(WifiPriorityScanEvent(
            WifiPriorityMonitorStatus.MONITORING, setOf(preferred.identity), "fresh_scan_results",
        ))
        wifi.operationExecutor.runAll()
        assertEquals(scansBefore, wifi.priorityScanSource.startCalls)
        assertNull(wifi.store.state.manualOperation)
    }

    @Test
    fun `a newer managed policy supersedes a waiting release`() {
        val preferred = network("preferred")
        val current = network("current")
        val harness = Harness(
            initialState = WifiLocalState(
                lastApplied = WifiSavedProfile(REVISION, listOf(preferred, current), autoReturn = true),
                policyRevision = REVISION,
            ),
            initialNetworks = listOf(preferred, current),
            initialWifi = listOf(observation(10, current.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        harness.priorityScanSource.emit(WifiPriorityScanEvent(
            WifiPriorityMonitorStatus.MONITORING, setOf(preferred.identity), "fresh_scan_results",
        ))
        harness.operationExecutor.runAll()
        harness.controller.stopManagingPolicy()
        harness.enqueue(command(OPERATION_ID_2, REVISION_2, "managed", current))
        harness.operationExecutor.runAll()

        harness.connectivity.update(11, preferred.ssid, hasIp = true)
        harness.operationExecutor.runAll()

        assertTrue(harness.store.state.managedWifi)
        assertEquals(REVISION, harness.store.state.policyRevision)
    }

    @Test
    fun `repeated failed automatic returns back off`() {
        val preferred = network("preferred")
        val current = network("current")
        val saved = WifiSavedProfile(REVISION, listOf(preferred, current), true)
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = saved, policyRevision = REVISION),
            initialNetworks = saved.networks,
            initialWifi = listOf(observation(10, current.ssid, hasIp = true)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        harness.adapter.selectFailure = { WifiReason.UPDATE_FAILED }
        val scan = WifiPriorityScanEvent(
            WifiPriorityMonitorStatus.MONITORING, setOf(preferred.identity), "fresh_scan_results",
        )
        fun scanAndSettle() {
            harness.priorityScanSource.emit(scan)
            harness.operationExecutor.runAll()
            harness.rollbackExecutor.runAll()
            harness.operationExecutor.runAll()
        }

        scanAndSettle()
        assertEquals(1, harness.adapter.selectCalls.size)
        harness.clock.value += 5 * 60_000L
        scanAndSettle()
        assertEquals(2, harness.adapter.selectCalls.size)

        // The second failure doubles the wait to ten minutes.
        harness.clock.value += 5 * 60_000L
        scanAndSettle()
        assertEquals(2, harness.adapter.selectCalls.size)
        harness.clock.value += 5 * 60_000L
        scanAndSettle()
        assertEquals(3, harness.adapter.selectCalls.size)
    }

    @Test
    fun `unchanged readiness does not notify listeners`() {
        val harness = Harness()
        var notifications = 0
        harness.controller.addStateListener { notifications++ }

        harness.controller.refreshReadiness()
        harness.operationExecutor.runAll()

        assertEquals(0, notifications)
    }

    private fun createUnconfirmedRestore(
        harness: Harness,
        original: WifiNetworkProfile,
        candidate: WifiNetworkProfile,
    ): WifiManualOperation {
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        harness.adapter.afterSelect = { harness.connectivity.update(11, "wrong-network", hasIp = true) }
        harness.adapter.afterRestore = {}
        harness.apply(command(OPERATION_ID, REVISION, "managed", candidate))
        harness.timeout()

        val pending = requireNotNull(harness.store.state.manualOperation)
        assertEquals(WifiManualPhase.RESTORING_CONNECTION, pending.phase)
        assertEquals(WifiRollbackTarget(original.ssid, "WPA_PSK"), pending.restoreTarget)
        val deadline = requireNotNull(pending.restoreDeadlineElapsedMillis)
        val timeout = requireNotNull(harness.watchdogs.lastOrNull { it.delayMillis == RESTORE_CONFIRM_TIMEOUT_MS })
        harness.clock.value = deadline
        timeout.task.run()
        harness.operationExecutor.runAll()

        assertEquals(WifiReason.RESTORE_UNCONFIRMED, harness.store.state.latestResult?.reason)
        assertEquals(pending.operationId, harness.store.state.manualOperation?.operationId)
        return requireNotNull(harness.store.state.manualOperation)
    }

    private class PackageHarness(
        val wifi: Harness = Harness(),
        val store: PackageStore = PackageStore(),
        val scalarWrites: ScalarWrites = ScalarWrites(),
        private val bootCount: Int = 133,
        deferSdk: Boolean = false,
    ) {
        val worker = QueueExecutor()
        val controller = ConfigurationPackageController(
            ConfigurationPackageDependencies(
                worker = worker,
                scheduleSdkReady = worker::execute,
                bootCount = bootCount,
                readJournal = { store.encoded },
                writeJournal = store::write,
                readLastPolicy = { store.lastPolicy },
                writeLastPolicy = { store.lastPolicy = it; true },
                scalarWriter = { scalarWrites::apply },
            ), wifi.controller,
        )

        init {
            if (!deferSdk) enableSdk()
        }

        fun enableSdk() {
            controller.onSdkReady()
            worker.runAll()
        }

        fun receive(payload: JSONObject, supportsApply: Boolean = true, isCurrent: () -> Boolean = { true }) {
            controller.receive(payload, supportsApply, isCurrent)
            worker.runAll()
        }

        fun reapply(version: ConfigurationVersion): Boolean {
            var started: Boolean? = null
            controller.reapply(version) { started = it }
            worker.runAll()
            return requireNotNull(started)
        }

        fun queueSuccessfulWifi() {
            wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
            wifi.adapter.afterSelect = { profile -> wifi.connectivity.update(11, profile.ssid, hasIp = true) }
        }

        fun runWifiTasks() {
            wifi.operationExecutor.runAll()
            if (wifi.store.state.manualOperation?.phase == WifiManualPhase.PREPARED &&
                !wifi.connectivity.hasCurrentUsableIp()
            ) {
                wifi.expirePriorConnectionObservation()
            }
        }

        fun restart(
            wifiState: WifiLocalState = wifi.store.state,
            bootCount: Int = this.bootCount,
            deferSdk: Boolean = false,
        ): PackageHarness {
            val restoredWifi = WifiSettingsCodec.decode(WifiSettingsCodec.encode(wifiState))
            return PackageHarness(
                Harness(initialState = restoredWifi, sharedAdapter = wifi.adapter, deferStartup = true),
                PackageStore(store.encoded, store.lastPolicy), scalarWrites, bootCount, deferSdk,
            )
        }

        fun finishWifiStartup() {
            wifi.controller.startStartupRecovery()
            wifi.operationExecutor.runAll()
            worker.runAll()
        }
    }

    private class PackageStore(var encoded: String? = null, var lastPolicy: String? = null) {
        var failWriteWhen: ((ConfigurationJournal) -> Boolean)? = null

        fun write(next: String): Boolean {
            if (failWriteWhen?.invoke(ConfigurationJournal.restore(next)) == true) return false
            encoded = next
            return true
        }

        fun attempt(): ConfigurationAttempt? = encoded?.let(ConfigurationJournal::restore)?.latest
    }

    private class ScalarWrites {
        var count = 0

        fun apply(values: JSONObject): JSONObject {
            count++
            return JSONObject().put("brightness", settingOutcome("applied", values.getInt("brightness")))
        }
    }

    private class Harness(
        initialState: WifiLocalState = WifiLocalState(),
        initialNetworks: List<WifiNetworkProfile> = emptyList(),
        initialWifi: List<WifiLocalNetworkObservation> = emptyList(),
        val sharedStore: MemoryStore? = null,
        val sharedAdapter: FakeAdapter? = null,
        val sharedClock: MutableClock? = null,
        bootCount: Int = 133,
        deferStartup: Boolean = false,
        readinessCheck: () -> WifiReason? = { null },
    ) {
        val store = sharedStore ?: MemoryStore(initialState)
        val adapter = sharedAdapter ?: FakeAdapter(initialNetworks)
        val clock = sharedClock ?: MutableClock()
        val operationExecutor = QueueExecutor()
        val rollbackExecutor = QueueExecutor()
        val watchdogs = mutableListOf<ScheduledTask>()
        val priorityTimeouts = mutableListOf<ScheduledTask>()
        val scheduledAlarmIds = linkedSetOf<String>()
        val canceledAlarmIds = mutableListOf<String>()
        var beforeCancelAlarm: ((String) -> Unit)? = null
        val connectivity = FakeConnectivity(initialWifi)
        val priorityScanSource = FakePriorityScanSource()
        init {
            adapter.afterRestore = { target ->
                target?.let { connectivity.update(90, it.ssid, hasIp = true, security = it.security) }
            }
        }
        val controller = WifiSettingsController(
            WifiControllerDependencies(
                store = store,
                readinessFailure = readinessCheck,
                adapter = adapter,
                connectivity = connectivity,
                nowElapsed = { clock.value },
                scheduleAlarm = { operationId, _ -> scheduledAlarmIds.add(operationId); true },
                cancelAlarm = { operationId ->
                    beforeCancelAlarm?.invoke(operationId)
                    scheduledAlarmIds.remove(operationId)
                    canceledAlarmIds += operationId
                },
                operationExecutor = operationExecutor,
                rollbackExecutor = rollbackExecutor,
                scheduleWatchdog = { task, delay -> watchdogs += ScheduledTask(task, delay) },
                priorityScanSource = priorityScanSource,
                bootCount = { bootCount },
                schedulePriorityTimeout = { task, delay -> priorityTimeouts += ScheduledTask(task, delay) },
            ),
            autoStart = !deferStartup,
        )

        init { if (!deferStartup) operationExecutor.runAll() }

        /** Routes a test command through the production entry points. */
        fun enqueue(payload: JSONObject) {
            val revision = payload.getString("revision")
            if (payload.getString("mode") == "unmanaged") {
                controller.stopManagingPolicy(revision)
            } else {
                controller.applyManagedPolicy(
                    payload.getString("operation_id"), revision, JSONObject().put("wifi", payload.getJSONObject("wifi")),
                )
            }
        }

        fun apply(payload: JSONObject) {
            enqueue(payload)
            operationExecutor.runAll()
            if (store.state.manualOperation?.phase == WifiManualPhase.PREPARED &&
                !connectivity.hasCurrentUsableIp()
            ) {
                expirePriorConnectionObservation()
            }
        }

        fun expirePriorConnectionObservation() {
            val task = watchdogs.lastOrNull { it.delayMillis == WIFI_PRIOR_CONNECTION_OBSERVATION_MS }
                ?: error("no prior connection observation timer was scheduled")
            clock.value += task.delayMillis
            task.task.run()
            operationExecutor.runAll()
        }

        fun timeout() {
            clock.value = requireNotNull(store.state.manualOperation?.deadlineElapsedMillis)
            watchdogs.lastOrNull { it.delayMillis == WIFI_MANUAL_TIMEOUT_MS }?.task?.run()
                ?: error("no Wi-Fi operation timeout was scheduled")
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

    private class FakeConnectivity(initial: List<WifiLocalNetworkObservation>) : WifiLocalConnectivitySource {
        var preflightResult: WifiReason? = null
        var preflightCalls = 0
        var failNextObserve = false
        private val sessions = mutableListOf<FakeSession>()
        private val current = initial.associateBy { it.networkHandle }.toMutableMap()
        override fun preflight(): WifiReason? {
            preflightCalls++
            return preflightResult
        }
        override fun observe(listener: (WifiLocalConnectivityEvent) -> Unit): WifiLocalConnectivitySession {
            if (failNextObserve) {
                failNextObserve = false
                throw SecurityException("injected observation failure")
            }
            return FakeSession(current.values.toList(), listener).also { sessions += it }
        }
        fun update(
            handle: Long,
            ssid: String,
            hasIp: Boolean,
            security: String? = "WPA_PSK",
        ) {
            current[handle] = WifiLocalNetworkObservation(handle, ssid, hasIp, 0L, security)
            sessions.toList().forEach { it.update(handle, ssid, hasIp, security) }
        }
        fun lose(handle: Long) {
            current.remove(handle)
            sessions.toList().forEach { it.lose(handle) }
        }

        fun loseAll() {
            current.keys.toList().forEach(::lose)
        }

        fun hasCurrentUsableIp(): Boolean = current.values.any { it.hasUsableIp }

        private class FakeSession(
            initial: List<WifiLocalNetworkObservation>,
            private val listener: (WifiLocalConnectivityEvent) -> Unit,
        ) : WifiLocalConnectivitySession {
            private var sequence = initial.maxOfOrNull { it.sequence } ?: 0L
            private val observations = initial.associateBy { it.networkHandle }.toMutableMap()
            var closed: Boolean = false
                private set
            override fun snapshot(): List<WifiLocalNetworkObservation> = observations.values.toList()
            override fun lastSequence(): Long = sequence
            fun update(handle: Long, ssid: String, hasIp: Boolean, security: String?) {
                if (closed) return
                val observation = WifiLocalNetworkObservation(handle, ssid, hasIp, ++sequence, security)
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

    private class FakePriorityScanSource : WifiPriorityScanSource {
        var startCalls = 0
        var stopCalls = 0
        var networks: List<WifiNetworkProfile> = emptyList()
        private var listener: ((WifiPriorityScanEvent) -> Unit)? = null

        override fun start(networks: List<WifiNetworkProfile>, listener: (WifiPriorityScanEvent) -> Unit) {
            startCalls++
            this.networks = networks.toList()
            this.listener = listener
            listener(WifiPriorityScanEvent(WifiPriorityMonitorStatus.MONITORING, detail = "test_started"))
        }

        override fun stop() {
            stopCalls++
            listener = null
        }

        fun emit(event: WifiPriorityScanEvent) { listener?.invoke(event) }
    }

    private data class ApplyCall(val target: List<WifiNetworkProfile>)

    private data class FakeSavedNetwork(
        var networkId: Int,
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
        val restoreCandidates = mutableListOf<List<WifiNetworkProfile>>()
        val connectCalls = mutableListOf<WifiRollbackTarget>()
        val selectCalls = mutableListOf<WifiNetworkProfile>()
        var captureRecoveryCalls = 0
        var beforeApply: (() -> Unit)? = null
        var applyFailure: WifiReason? = null
        var restoreFailure: WifiReason? = null
        var connectFailure: WifiReason? = null
        var beforeRestore: (() -> Unit)? = null
        var afterCandidateMutationStarted: (() -> Unit)? = null
        var afterDisconnect: (() -> Unit)? = null
        var afterSavedReconnect: ((WifiRollbackTarget) -> Unit)? = null
        var afterSelect: ((WifiNetworkProfile) -> Unit)? = null
        var afterRestore: ((WifiRollbackTarget?) -> Unit)? = null
        var selectFailure: ((WifiNetworkProfile) -> WifiReason?)? = null
        var disconnectCalls = 0
        private var activeMutations = 0
        var maxConcurrentMutations = 0

        fun setAutoJoin(identity: String, allowed: Boolean) {
            saved.single { it.profile.identity == identity }.autoJoinAllowed = allowed
        }

        fun reassignNetworkId(identity: String, networkId: Int) {
            saved.single { it.profile.identity == identity }.networkId = networkId
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

        override fun selectNetwork(
            profile: WifiNetworkProfile,
            shouldContinue: () -> Boolean,
            onMutationStarted: () -> WifiReason?,
            onMutationFinished: () -> Unit,
        ): WifiReason {
            selectCalls += profile
            if (!shouldContinue()) return WifiReason.TIMEOUT_ROLLED_BACK
            onMutationStarted()?.let { return it }
            return try {
                if (!shouldContinue()) WifiReason.TIMEOUT_ROLLED_BACK
                else selectFailure?.invoke(profile) ?: run {
                    afterSelect?.invoke(profile)
                    WifiReason.NONE
                }
            } finally {
                onMutationFinished()
            }
        }

        override fun restoreRecovery(
            snapshot: WifiRecoverySnapshot,
            candidate: List<WifiNetworkProfile>,
            restoreTarget: WifiRollbackTarget?,
            shouldContinue: () -> Boolean,
            onMutationStarted: () -> WifiReason?,
            onMutationFinished: () -> Unit,
        ): WifiReason {
            restoreCalls += snapshot
            restoreCandidates += candidate.toList()
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
            if (saved != desired) {
                onMutationStarted()?.let { return it }
                enterMutation()
                try {
                    saved.clear()
                    saved.addAll(desired)
                } finally {
                    leaveMutation()
                    onMutationFinished()
                }
            }
            afterRestore?.invoke(restoreTarget)
            return WifiReason.NONE
        }

        override fun connectToSavedNetwork(
            target: WifiRollbackTarget,
            shouldContinue: () -> Boolean,
            onMutationStarted: () -> WifiReason?,
            onMutationFinished: () -> Unit,
        ): WifiReason {
            connectCalls += target
            if (!shouldContinue()) return WifiReason.TIMEOUT_ROLLED_BACK
            onMutationStarted()?.let { return it }
            return try {
                if (!shouldContinue()) WifiReason.TIMEOUT_ROLLED_BACK
                else connectFailure ?: run {
                    afterSavedReconnect?.invoke(target)
                    WifiReason.NONE
                }
            } finally {
                onMutationFinished()
            }
        }

        override fun disconnectCurrent(
            shouldContinue: () -> Boolean,
            onMutationStarted: () -> WifiReason?,
            onMutationFinished: () -> Unit,
        ): WifiReason = runConnectionMutation(shouldContinue, onMutationStarted, onMutationFinished) {
            disconnectCalls++
            afterDisconnect?.invoke()
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
        private const val RESTORE_CONFIRM_TIMEOUT_MS = 60_000L

        private fun network(ssid: String) = WifiNetworkProfile(ssid, "WPA2_WPA3", "correct-horse-battery", false)

        private fun savedProfile(profile: WifiNetworkProfile) = WifiSavedProfile(REVISION_2, listOf(profile), autoReturn = false)

        private fun receiptId(value: Int): String =
            "%08x-0000-4000-8000-%012x".format(value, value)

        private fun observation(handle: Long, ssid: String, hasIp: Boolean) =
            WifiLocalNetworkObservation(handle, ssid, hasIp, 1L, security = "WPA_PSK")

        private fun packagePolicy(
            retryId: String? = null,
            autoReturn: Boolean = false,
            networks: List<WifiNetworkProfile> = listOf(network("candidate")),
        ) = JSONObject()
            .put("package_id", REVISION_2)
            .put("revision", REVISION)
            .put("retry_id", retryId)
            .put("settings", JSONObject().put("brightness", 70).put("wifi",
                command(OPERATION_ID, REVISION, "managed", networks = networks)
                    .getJSONObject("wifi").put("auto_return", autoReturn)))

        private fun command(
            operationId: String,
            revision: String,
            mode: String,
            network: WifiNetworkProfile? = null,
            autoReturn: Boolean = false,
            networks: List<WifiNetworkProfile>? = null,
        ) = JSONObject().apply {
            put("operation_id", operationId)
            put("revision", revision)
            put("mode", mode)
            put("wifi", JSONObject().put("networks", JSONArray().apply {
                (networks ?: listOfNotNull(network)).forEach { profile -> put(JSONObject().apply {
                    put("ssid", profile.ssid)
                    put("security", profile.security)
                    put("password", profile.password)
                    put("hidden", profile.hidden)
                }) }
            }).put("auto_return", autoReturn))
        }
    }
}
