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
    fun `startup keeps the saved Wi-Fi profile without rewriting configured networks`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val profile = WifiSavedProfile(REVISION, listOf(preferred, fallback))
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = profile, policyRevision = REVISION),
            initialNetworks = listOf(preferred, fallback),
            initialWifi = listOf(observation(12, fallback.ssid)),
        )

        harness.controller.start()
        harness.worker.runAll()

        assertEquals(profile, harness.store.state.lastApplied)
        assertEquals(0, harness.adapter.applyCalls.size)
        assertEquals(listOf(preferred, fallback), harness.adapter.currentProfiles)
        assertTrue(harness.controller.canProcessPolicy())
    }

    @Test
    fun `preferred candidate connects and only it has auto join enabled`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val harness = Harness()
        harness.adapter.afterSelect = { selected ->
            if (selected == preferred) harness.connectivity.update(21, selected.ssid)
        }

        harness.apply(listOf(preferred, fallback))
        harness.expireNext(2_000L)

        assertEquals(listOf(preferred, fallback), harness.adapter.currentProfiles)
        assertEquals(listOf(preferred), harness.adapter.selectCalls)
        assertEquals(listOf(true, false), harness.adapter.currentAutoJoin)
        assertEquals(listOf(preferred, fallback), harness.store.state.lastApplied?.networks)
        assertEquals("success", harness.store.state.latestResult?.status)
        assertEquals(WifiReason.NONE, harness.store.state.latestResult?.reason)
    }

    @Test
    fun `Android selection applies on deploy and boot without observing or requesting a connection`() {
        val primary = network("office-a")
        val backup = network("office-b")
        val unlisted = network("unlisted")
        val wifi = Harness(initialNetworks = listOf(unlisted))
        val packages = PackageHarness(wifi)
        wifi.connectivity.preflightResult = WifiReason.UNSUPPORTED
        val policy = packagePayload(listOf(primary, backup), prioritizeNetworks = false)
        packages.receive(policy)
        wifi.worker.runAll()
        packages.worker.runAll()

        assertEquals(listOf(unlisted, primary, backup), wifi.adapter.currentProfiles)
        assertEquals(listOf(false, true, true), wifi.adapter.currentAutoJoin)
        val savedResult = packages.attempt()?.results?.getJSONObject("wifi")
        assertEquals("applied", savedResult?.getString("status"))
        assertEquals("SAVED", savedResult?.getString("reason"))
        assertEquals(0, wifi.connectivity.preflightCalls)
        assertEquals(0, wifi.connectivity.observeCalls)
        assertEquals(0, wifi.priorityScanSource.startCalls)
        assertEquals(0, wifi.adapter.disconnectCalls)
        assertTrue(wifi.adapter.selectCalls.isEmpty())
        val deployedNotice = packages.appliedNotices.single()
        assertFalse(deployedNotice.bootReapply)
        assertTrue(deployedNotice.isCurrent())

        packages.receive(policy)
        wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals(1, packages.appliedNotices.size)

        val nextBoot = packages.restart(bootCount = 134)
        nextBoot.wifi.worker.runAll()
        nextBoot.worker.runAll()
        assertEquals(2, wifi.adapter.applyCalls.size)
        assertEquals(0, wifi.adapter.disconnectCalls)
        assertTrue(wifi.adapter.selectCalls.isEmpty())
        assertEquals(0, nextBoot.wifi.connectivity.observeCalls)
        assertEquals("SAVED", nextBoot.attempt()?.results?.getJSONObject("wifi")?.getString("reason"))
        val bootNotice = nextBoot.appliedNotices.single()
        assertTrue(bootNotice.bootReapply)
        assertTrue(bootNotice.isCurrent())
    }

    @Test
    fun `switching to Android selection stops the old retry loop even when all writes fail`() {
        val networks = listOf(network("office-a"), network("office-b"))
        val previous = WifiSavedProfile(OLD_REVISION, networks)
        val wifi = Harness(initialNetworks = networks,
            initialState = WifiLocalState(lastApplied = previous, policyRevision = OLD_REVISION))
        wifi.adapter.application = WifiProfileApplication(emptyList(), WifiReason.UPDATE_FAILED)
        wifi.apply(networks, prioritizeNetworks = false)

        assertEquals(previous, wifi.store.state.lastApplied)
        assertFalse(wifi.store.state.managedWifi)
        assertEquals(WifiReason.UPDATE_FAILED, wifi.store.state.latestResult?.reason)
        wifi.expireNext(8_000L)
        assertTrue(wifi.adapter.selectCalls.isEmpty())
        assertEquals(0, wifi.adapter.disconnectCalls)
    }

    @Test
    fun `switching from Android selection to priority does not treat a saved credential as verified`() {
        val primary = network("office-a")
        val wifi = Harness(initialNetworks = listOf(primary), initialWifi = listOf(observation(10, primary.ssid)))
        wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
        wifi.adapter.afterSelect = { wifi.connectivity.update(11, it.ssid) }
        wifi.apply(listOf(primary), prioritizeNetworks = false)
        assertEquals(WifiReason.SAVED, wifi.store.state.latestResult?.reason)

        wifi.apply(listOf(primary), reuseMatchingProfile = true)
        assertEquals(2, wifi.adapter.applyCalls.size)
        assertEquals(WifiReason.VERIFY_AFTER_REBOOT, wifi.store.state.latestResult?.reason)
    }

    @Test
    fun `process restart retains both configured candidates after a successful apply`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val firstRun = Harness()
        firstRun.adapter.afterSelect = { selected ->
            if (selected == preferred) firstRun.connectivity.update(21, selected.ssid)
        }
        firstRun.apply(listOf(preferred, fallback))
        firstRun.expireNext(2_000L)

        val restarted = Harness(
            initialState = WifiSettingsCodec.decode(WifiSettingsCodec.encode(firstRun.store.state)),
            initialNetworks = firstRun.adapter.currentProfiles,
            initialWifi = listOf(observation(21, preferred.ssid)),
        )

        assertEquals(listOf(preferred, fallback), restarted.store.state.lastApplied?.networks)
        assertEquals(listOf(preferred, fallback), restarted.adapter.currentProfiles)
        assertEquals(0, restarted.adapter.applyCalls.size)
    }

    @Test
    fun `a verified primary keeps its usable connection after saving`() {
        val primary = network("office-a")
        val fallback = network("office-b")
        val networks = listOf(primary, fallback)
        val wifi = Harness(
            initialState = WifiLocalState(
                lastApplied = WifiSavedProfile(OLD_REVISION, networks),
                policyRevision = OLD_REVISION,
            ),
            initialNetworks = networks,
            initialWifi = listOf(observation(10, primary.ssid)),
        )

        wifi.apply(networks)

        assertEquals(listOf(networks), wifi.adapter.applyCalls)
        assertEquals(listOf(true, false), wifi.adapter.currentAutoJoin)
        assertEquals(0, wifi.adapter.disconnectCalls)
        assertTrue(wifi.adapter.selectCalls.isEmpty())
        assertEquals(primary.ssid, wifi.connectivity.currentUsableSsid())
        assertEquals("success", wifi.store.state.latestResult?.status)
        assertEquals(WifiReason.NONE, wifi.store.state.latestResult?.reason)
    }

    @Test
    fun `a newly managed or changed primary keeps its usable connection pending verification`() {
        val oldA = network("office-a", password = "old-password")
        val changedA = network("office-a", password = "new-password")
        val fallbackB = network("office-b", password = "backup-password")
        val previous = WifiLocalState(
            lastApplied = WifiSavedProfile(OLD_REVISION, listOf(oldA, fallbackB)),
            policyRevision = OLD_REVISION,
        )
        for ((state, primary) in listOf(WifiLocalState() to oldA, previous to changedA)) {
            val wifi = Harness(
                initialState = state,
                initialNetworks = listOf(oldA, fallbackB),
                initialWifi = listOf(observation(10, oldA.ssid)),
            )

            wifi.apply(listOf(primary, fallbackB))

            assertEquals(listOf(primary, fallbackB), wifi.adapter.currentProfiles)
            assertEquals(listOf(true, false), wifi.adapter.currentAutoJoin)
            assertEquals(listOf(primary, fallbackB), wifi.store.state.lastApplied?.networks)
            assertEquals(0, wifi.adapter.disconnectCalls)
            assertTrue(wifi.adapter.selectCalls.isEmpty())
            assertEquals("failed", wifi.store.state.latestResult?.status)
            assertEquals(WifiReason.VERIFY_AFTER_REBOOT, wifi.store.state.latestResult?.reason)
            assertEquals(primary.ssid, wifi.connectivity.currentUsableSsid())
        }
    }

    @Test
    fun `a save that drops the link can reconnect to the unverified primary`() {
        val primary = network("office-a")
        val fallback = network("office-b")
        val wifi = Harness(initialNetworks = listOf(primary), initialWifi = listOf(observation(10, primary.ssid)))
        wifi.adapter.afterApply = { wifi.connectivity.loseAll() }
        wifi.adapter.afterSelect = { wifi.connectivity.update(11, it.ssid) }

        wifi.apply(listOf(primary, fallback))

        assertEquals(0, wifi.adapter.disconnectCalls)
        assertEquals(listOf(primary), wifi.adapter.selectCalls)
        assertEquals(primary.ssid, wifi.connectivity.currentUsableSsid())
        assertEquals(WifiReason.VERIFY_AFTER_REBOOT, wifi.store.state.latestResult?.reason)
    }

    @Test
    fun `a prior open connection does not mark the same SSID's protected candidate unverified`() {
        val secured = network("office-a")
        val priorOpen = WifiNetworkProfile(secured.ssid, "OPEN", "", hidden = false)
        val harness = Harness(
            initialNetworks = listOf(priorOpen),
            initialWifi = listOf(observation(10, priorOpen.ssid).copy(security = "OPEN")),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        harness.adapter.afterSelect = { selected -> harness.connectivity.update(11, selected.ssid) }

        harness.apply(listOf(secured))

        assertEquals(WifiReason.NONE, harness.store.state.latestResult?.reason)
        assertTrue(requireNotNull(harness.store.state.lastApplied).unverifiedOnBoot.isEmpty())
    }

    @Test
    fun `a prior connection with unknown security keeps its changed password unverified`() {
        val old = network("office-a", password = "old-password")
        val changed = network(old.ssid, password = "new-password")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old)),
                policyRevision = OLD_REVISION),
            initialNetworks = listOf(old),
            initialWifi = listOf(observation(10, old.ssid).copy(security = null)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        harness.adapter.afterSelect = { selected -> harness.connectivity.update(11, selected.ssid) }

        harness.apply(listOf(changed))
        harness.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)

        assertEquals(WifiReason.VERIFY_AFTER_REBOOT, harness.store.state.latestResult?.reason)
    }

    @Test
    fun `automatic return from fallback keeps an unverified primary and its pending result`() {
        val old = network("office-a", password = "old-password")
        val changed = network("office-a", password = "new-password")
        val backup = network("office-b")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old, backup)),
                policyRevision = OLD_REVISION),
            initialNetworks = listOf(old, backup),
            initialWifi = listOf(observation(10, old.ssid)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        var handle = 10L
        harness.adapter.afterSelect = { selected ->
            if (selected == backup) harness.connectivity.update(++handle, selected.ssid)
        }

        harness.apply(listOf(changed, backup), autoReturn = true)
        val pending = requireNotNull(harness.store.state.latestResult)
        assertEquals(WifiReason.VERIFY_AFTER_REBOOT, pending.reason)
        harness.connectivity.loseAll()
        harness.worker.runAll()
        harness.expireNext(8_000L)
        harness.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        harness.expireNext(12_000L)
        assertEquals(backup.ssid, harness.connectivity.currentUsableSsid())
        assertEquals(listOf(changed, backup), harness.adapter.selectCalls)
        assertEquals(pending, harness.store.state.latestResult)

        val observeCalls = harness.connectivity.observeCalls
        harness.adapter.afterSelect = { harness.connectivity.update(++handle, it.ssid) }
        harness.priorityScanSource.emit(WifiPriorityScanEvent(
            WifiPriorityMonitorStatus.MONITORING, setOf(changed.identity), "fresh_scan_results",
        ))
        harness.worker.runAll()
        assertEquals(listOf(changed, backup, changed), harness.adapter.selectCalls)
        assertEquals(changed.ssid, harness.connectivity.currentUsableSsid())
        assertEquals(pending, harness.store.state.latestResult)
        assertEquals(observeCalls + 1, harness.connectivity.observeCalls)
    }

    @Test
    fun `same boot reapply must not accept the same cached association`() {
        val old = network("office-a", password = "old-password")
        val changed = network("office-a", password = "new-password")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old)),
                policyRevision = OLD_REVISION),
            initialNetworks = listOf(old),
            initialWifi = listOf(observation(10, old.ssid)),
            bootCount = 133,
        )
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        var handle = 10L
        harness.adapter.afterSelect = { selected ->
            harness.connectivity.update(++handle, selected.ssid)
        }

        harness.apply(listOf(changed))
        assertEquals(WifiReason.VERIFY_AFTER_REBOOT, harness.store.state.latestResult?.reason)
        assertEquals(listOf(changed), harness.adapter.currentProfiles)

        harness.apply(listOf(changed))
        assertEquals(WifiReason.VERIFY_AFTER_REBOOT, harness.store.state.latestResult?.reason)

        val saved = WifiSettingsCodec.decode(WifiSettingsCodec.encode(harness.store.state))
        for ((boot, expected) in listOf(133 to WifiReason.VERIFY_AFTER_REBOOT, 134 to WifiReason.NONE)) {
            val restarted = Harness(
                initialState = saved,
                initialNetworks = listOf(changed),
                initialWifi = listOf(observation(10, changed.ssid)),
                bootCount = boot,
            )
            restarted.adapter.afterDisconnect = { restarted.connectivity.loseAll() }
            restarted.adapter.afterSelect = { restarted.connectivity.update(11, it.ssid) }

            restarted.apply(listOf(changed))
            assertEquals(expected, restarted.store.state.latestResult?.reason)
        }
    }

    @Test
    fun `all candidates can fail while their profiles and auto join settings stay saved`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val harness = Harness()
        harness.apply(listOf(preferred, fallback))
        harness.expireNext(2_000L)

        harness.expireNext(12_000L)
        harness.expireNext(12_000L)

        assertEquals(listOf(preferred, fallback), harness.adapter.currentProfiles)
        assertEquals(listOf(true, false), harness.adapter.currentAutoJoin)
        assertEquals(listOf(preferred, fallback), harness.store.state.lastApplied?.networks)
        assertEquals("failed", harness.store.state.latestResult?.status)
        assertEquals(WifiReason.UNREACHABLE, harness.store.state.latestResult?.reason)
    }

    @Test
    fun `a partially writable profile still selects its usable fallback and reports the save failure`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val harness = Harness()
        harness.adapter.application = WifiProfileApplication(
            usableIndexes = listOf(1), failure = WifiReason.UPDATE_FAILED,
        )
        harness.adapter.afterSelect = { selected ->
            if (selected == fallback) harness.connectivity.update(21, selected.ssid)
        }

        harness.apply(listOf(preferred, fallback))
        harness.expireNext(2_000L)

        assertEquals(listOf(fallback), harness.adapter.selectCalls)
        assertEquals(listOf(fallback), harness.store.state.lastApplied?.networks)
        assertEquals(listOf(false), harness.adapter.currentAutoJoin)
        assertEquals("failed", harness.store.state.latestResult?.status)
        assertEquals(WifiReason.UPDATE_FAILED, harness.store.state.latestResult?.reason)

        val persisted = WifiSettingsCodec.decode(WifiSettingsCodec.encode(harness.store.state))
        val restarted = Harness(initialState = persisted, sharedAdapter = harness.adapter)
        restarted.adapter.afterSelect = { selected -> restarted.connectivity.update(23, selected.ssid) }
        restarted.expireNext(8_000L)
        restarted.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)

        assertEquals(listOf(fallback, fallback), restarted.adapter.selectCalls)
        assertEquals(fallback.ssid, restarted.connectivity.currentUsableSsid())
        assertEquals(WifiReason.FALLBACK, restarted.store.state.latestResult?.reason)
    }

    @Test
    fun `all writes failing preserves the applied profile and the next Retry can verify the same credential`() {
        val primary = network("office-a")
        val priorProfile = WifiSavedProfile(OLD_REVISION, listOf(primary))
        val wifi = Harness(
            initialState = WifiLocalState(lastApplied = priorProfile, policyRevision = OLD_REVISION),
            initialNetworks = listOf(primary),
            initialWifi = listOf(observation(10, primary.ssid)),
        )
        wifi.adapter.application = WifiProfileApplication(emptyList(), WifiReason.UPDATE_FAILED)
        val packages = PackageHarness(wifi)
        val payload = packagePayload(listOf(primary))
        packages.receive(payload)
        wifi.worker.runAll()
        packages.worker.runAll()

        assertEquals(priorProfile, wifi.store.state.lastApplied)
        assertEquals(OLD_REVISION, wifi.store.state.policyRevision)
        assertEquals(listOf(true), wifi.adapter.currentAutoJoin)
        assertEquals(0, wifi.adapter.disconnectCalls)
        assertEquals("UPDATE_FAILED", packages.attempt()?.results?.getJSONObject("wifi")?.getString("reason"))

        wifi.adapter.application = null
        wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
        wifi.adapter.afterSelect = { wifi.connectivity.update(11, it.ssid) }
        packages.receive(JSONObject(payload.toString()).put("retry_id", OPERATION_ID))
        wifi.worker.runAll()
        packages.worker.runAll()

        assertEquals("applied", packages.attempt()?.results?.getJSONObject("wifi")?.getString("status"))
        assertEquals(WifiReason.NONE, wifi.store.state.latestResult?.reason)
    }

    @Test
    fun `when selection is needed old network handles do not count as a fresh connection`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val harness = Harness(
            initialState = WifiLocalState(
                lastApplied = WifiSavedProfile(OLD_REVISION, listOf(preferred, fallback)),
                policyRevision = OLD_REVISION,
            ),
            initialWifi = listOf(observation(10, preferred.ssid)),
        )
        harness.adapter.afterApply = { harness.connectivity.update(10, preferred.ssid, hasUsableIp = false) }
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        // The OS reports the old handle again while disconnect is in progress. It must be
        // disconnected before a fresh association can complete the apply.
        harness.adapter.onDisconnect = { harness.connectivity.update(10, preferred.ssid) }

        harness.apply(listOf(preferred, fallback))
        harness.worker.runAll()
        assertNull(harness.store.state.latestResult)

        harness.connectivity.update(11, preferred.ssid)
        harness.worker.runAll()

        assertEquals("success", harness.store.state.latestResult?.status)
        assertEquals(WifiReason.NONE, harness.store.state.latestResult?.reason)
        assertEquals(listOf(preferred), harness.adapter.selectCalls)
    }

    @Test
    fun `candidate timeout advances to the next index and an old timeout cannot advance it again`() {
        val preferred = network("office-a")
        val middle = network("office-b")
        val fallback = network("office-c")
        val harness = Harness()

        harness.apply(listOf(preferred, middle, fallback))
        harness.expireNext(2_000L)
        assertEquals(listOf(preferred), harness.adapter.selectCalls)

        harness.expireNext(12_000L)
        assertEquals(listOf(preferred, middle), harness.adapter.selectCalls)
        harness.replayTimer(12_000L, index = 0)
        assertEquals(listOf(preferred, middle), harness.adapter.selectCalls)

        harness.connectivity.update(31, middle.ssid)
        harness.worker.runAll()

        assertEquals("success", harness.store.state.latestResult?.status)
        assertEquals(WifiReason.FALLBACK, harness.store.state.latestResult?.reason)
        assertEquals(listOf(preferred, middle, fallback), harness.store.state.lastApplied?.networks)
    }

    @Test
    fun `auto return off keeps the primary when Android reconnects during grace`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val saved = WifiSavedProfile(REVISION, listOf(preferred, fallback), autoReturn = false)
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = saved, policyRevision = REVISION),
            initialNetworks = listOf(preferred, fallback),
        )
        assertEquals(0, harness.priorityScanSource.startCalls)

        harness.connectivity.update(32, preferred.ssid)
        harness.worker.runAll()
        harness.expireNext(8_000L)

        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertEquals(saved, harness.store.state.lastApplied)
        assertEquals(preferred.ssid, harness.connectivity.currentUsableSsid())
    }

    @Test
    fun `offline retry finds a fallback that becomes available after the first pass`() {
        val primary = network("office-a")
        val fallback = network("office-b")
        val networks = listOf(primary, fallback)
        val wifi = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(REVISION, networks), policyRevision = REVISION),
            initialNetworks = networks,
        )

        wifi.expireNext(8_000L)
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        repeat(2) { wifi.expireNext(12_000L) }
        assertEquals(listOf(primary, fallback), wifi.adapter.selectCalls)

        wifi.adapter.afterSelect = { if (it == fallback) wifi.connectivity.update(34, fallback.ssid) }
        wifi.expireNext(16_000L)
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        wifi.expireNext(12_000L)

        assertEquals(listOf(primary, fallback, primary, fallback), wifi.adapter.selectCalls)
        assertEquals(fallback.ssid, wifi.connectivity.currentUsableSsid())
        assertEquals(WifiReason.FALLBACK, wifi.store.state.latestResult?.reason)
        assertEquals(0, wifi.priorityScanSource.startCalls)
    }

    @Test
    fun `offline retry can rejoin the unverified primary after all candidates fail`() {
        val primary = network("office-a")
        val fallback = network("office-b")
        val networks = listOf(primary, fallback)
        val profile = WifiSavedProfile(REVISION, networks, unverifiedOnBoot = mapOf(primary.identity to 133))
        val pending = WifiManualResult(OPERATION_ID, REVISION, "failed", WifiReason.VERIFY_AFTER_REBOOT)
        val wifi = Harness(
            initialState = WifiLocalState(lastApplied = profile, policyRevision = REVISION, latestResult = pending),
            initialNetworks = networks,
        )

        wifi.expireNext(8_000L)
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        repeat(2) { wifi.expireNext(12_000L) }
        assertEquals(listOf(primary, fallback), wifi.adapter.selectCalls)
        assertEquals(pending, wifi.store.state.latestResult)

        wifi.adapter.afterSelect = { wifi.connectivity.update(34, it.ssid) }
        wifi.expireNext(16_000L)
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)

        assertEquals(listOf(primary, fallback, primary), wifi.adapter.selectCalls)
        assertEquals(primary.ssid, wifi.connectivity.currentUsableSsid())
        assertEquals(pending, wifi.store.state.latestResult)
    }

    @Test
    fun `readiness failure reports without touching saved Wi-Fi settings`() {
        val preferred = network("office-a")
        val harness = Harness(initialNetworks = listOf(preferred), preflightFailure = WifiReason.PERMISSION_DENIED)

        harness.apply(listOf(network("office-b")))

        assertEquals(listOf(preferred), harness.adapter.currentProfiles)
        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertEquals("failed", harness.store.state.latestResult?.status)
        assertEquals(WifiReason.PERMISSION_DENIED, harness.store.state.latestResult?.reason)
    }

    @Test
    fun `invalid candidate count is rejected before changing Wi-Fi`() {
        val harness = Harness()
        val candidates = (1..WIFI_MAX_NETWORKS + 1).map { network("office-$it") }

        harness.apply(candidates)

        assertEquals(WifiReason.UNSUPPORTED_NETWORK_LIMIT, harness.store.state.latestResult?.reason)
        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertTrue(harness.adapter.currentProfiles.isEmpty())
    }

    @Test
    fun `priority scan can switch from B to A and stops monitoring after success`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val saved = WifiSavedProfile(REVISION, listOf(preferred, fallback), autoReturn = true)
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = saved, policyRevision = REVISION),
            initialNetworks = listOf(preferred, fallback),
            initialWifi = listOf(observation(10, fallback.ssid)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        harness.adapter.afterSelect = { selected ->
            if (selected == preferred) harness.connectivity.update(11, selected.ssid)
        }

        harness.priorityScanSource.emit(WifiPriorityScanEvent(
            WifiPriorityMonitorStatus.MONITORING, setOf(preferred.identity), "fresh_scan_results",
        ))
        harness.worker.runAll()

        assertEquals(listOf(preferred), harness.adapter.selectCalls)
        assertEquals("off", harness.controller.stateSnapshot().getString("priority_monitor"))
        assertEquals(listOf(preferred, fallback), harness.adapter.currentProfiles)
    }

    @Test
    fun `result snapshot is secret free and carries fallback status`() {
        val preferred = network("office-a", password = "secret-a")
        val fallback = network("office-b", password = "secret-b")
        val harness = Harness()
        harness.adapter.afterSelect = { selected ->
            if (selected == fallback) harness.connectivity.update(22, selected.ssid)
        }
        harness.apply(listOf(preferred, fallback))
        harness.expireNext(2_000L)
        harness.expireNext(12_000L)

        val snapshot = harness.controller.stateSnapshot().toString()
        assertTrue(snapshot.contains("FALLBACK"))
        assertFalse(snapshot.contains("secret-a"))
        assertFalse(snapshot.contains("secret-b"))
        assertFalse(snapshot.contains("rolled_back"))
    }

    @Test
    fun `configuration package reports a save failure after connecting to either remaining candidate`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val networks = listOf(preferred, fallback)
        for ((index, reason) in listOf(0 to WifiReason.UPDATE_FAILED, 1 to WifiReason.VERIFY_FAILED)) {
            val wifi = Harness()
            wifi.adapter.afterSelect = { wifi.connectivity.update(24, it.ssid) }
            wifi.adapter.application = WifiProfileApplication(listOf(index), reason)
            val packages = PackageHarness(wifi)
            packages.receive(packagePayload(networks))

            wifi.worker.runAll()
            wifi.expireNext(2_000L)
            packages.worker.runAll()

            val result = requireNotNull(packages.controller.snapshot())
                .getJSONObject("results").getJSONObject("wifi")
            assertEquals(networks[index].ssid, wifi.connectivity.currentUsableSsid())
            assertEquals("failed", result.getString("status"))
            assertEquals(reason.wireValue, result.getString("reason"))
            assertEquals(1, packages.scalarWrites.count)
        }
    }

    @Test
    fun `successful fallback cannot report applied when unlisted auto join could not be disabled`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val wifi = Harness()
        wifi.adapter.afterSelect = { selected ->
            if (selected == fallback) wifi.connectivity.update(24, selected.ssid)
        }
        wifi.adapter.application = WifiProfileApplication(
            usableIndexes = listOf(1),
            failure = WifiReason.UPDATE_FAILED,
            autoJoinFailure = WifiReason.VERIFY_FAILED,
        )
        val packages = PackageHarness(wifi)
        packages.receive(packagePayload(listOf(preferred, fallback)))

        wifi.worker.runAll()
        wifi.expireNext(2_000L)
        packages.worker.runAll()

        val result = requireNotNull(packages.controller.snapshot())
            .getJSONObject("results").getJSONObject("wifi")
        assertEquals(listOf(fallback), wifi.adapter.selectCalls)
        assertEquals(fallback.ssid, wifi.connectivity.currentUsableSsid())
        assertEquals("failed", result.getString("status"))
        assertEquals("VERIFY_FAILED", result.getString("reason"))
    }

    @Test
    fun `deferred verification does not hide other candidate failures`() {
        val active = network("office-a", password = "old-password")
        val changed = active.copy(password = "new-password")
        val other = network("office-b")
        for (saveFailure in listOf(null, WifiReason.UPDATE_FAILED, WifiReason.VERIFY_FAILED)) {
            val wifi = Harness(initialNetworks = listOf(active), initialWifi = listOf(observation(10, active.ssid)))
            if (saveFailure != null) wifi.adapter.application = WifiProfileApplication(listOf(1), saveFailure)
            wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
            wifi.adapter.afterSelect = { if (it == changed) wifi.connectivity.update(11, it.ssid) }

            wifi.apply(listOf(other, changed))
            if (saveFailure == null) wifi.expireNext(12_000L)

            assertEquals(changed.ssid, wifi.connectivity.currentUsableSsid())
            assertEquals("failed", wifi.store.state.latestResult?.status)
            assertEquals(saveFailure ?: WifiReason.VERIFY_AFTER_REBOOT, wifi.store.state.latestResult?.reason)
        }
    }

    @Test
    fun `brightness-only package revision reuses the same-boot Wi-Fi connection`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val wifi = Harness()
        wifi.adapter.afterSelect = { selected ->
            if (selected == preferred) wifi.connectivity.update(40, selected.ssid)
        }
        val packages = PackageHarness(wifi)
        val initial = packagePayload(listOf(preferred, fallback))
        packages.receive(initial)
        wifi.worker.runAll()
        wifi.expireNext(2_000L)
        packages.worker.runAll()
        val applyCalls = wifi.adapter.applyCalls.size
        val selectCalls = wifi.adapter.selectCalls.size
        val disconnectCalls = wifi.adapter.disconnectCalls

        val changed = packagePayload(listOf(preferred, fallback), revision = REVISION_2)
            .also { it.getJSONObject("settings").put("brightness", 65) }
        packages.receive(changed)
        wifi.worker.runAll()
        packages.worker.runAll()

        assertEquals(applyCalls, wifi.adapter.applyCalls.size)
        assertEquals(selectCalls, wifi.adapter.selectCalls.size)
        assertEquals(disconnectCalls, wifi.adapter.disconnectCalls)
        assertEquals(preferred.ssid, wifi.connectivity.currentUsableSsid())
        assertEquals(setOf("brightness"), packages.scalarWrites.calls.last())
        assertEquals(2, packages.scalarWrites.count)
        assertEquals(REVISION_2, wifi.store.state.policyRevision)
        assertEquals("complete", packages.controller.snapshot()?.getString("status"))
    }

    @Test
    fun `brightness update does not rewrite Wi-Fi after failed offline selection and OS reconnect`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val networks = listOf(preferred, fallback)
        val wifi = Harness()
        val packages = PackageHarness(wifi)
        var handle = 40L
        val connectSelected: (WifiNetworkProfile) -> Unit = { wifi.connectivity.update(++handle, it.ssid) }
        wifi.adapter.afterSelect = connectSelected
        wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
        packages.receive(packagePayload(networks))
        wifi.worker.runAll()
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        packages.worker.runAll()
        val initialResult = requireNotNull(wifi.store.state.latestResult)
        assertEquals("success", initialResult.status)

        wifi.adapter.afterSelect = null
        wifi.connectivity.loseAll()
        wifi.worker.runAll()
        wifi.expireNext(8_000L)
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        repeat(2) { wifi.expireNext(12_000L) }
        assertEquals(listOf(preferred, preferred, fallback), wifi.adapter.selectCalls)
        assertTrue(wifi.controller.canProcessPolicy())
        val resultAfterSelection = wifi.store.state.latestResult

        wifi.connectivity.update(++handle, preferred.ssid)
        wifi.worker.runAll()
        val applyCalls = wifi.adapter.applyCalls.size
        val disconnectCalls = wifi.adapter.disconnectCalls
        wifi.adapter.afterSelect = connectSelected
        val changed = packagePayload(networks, revision = REVISION_2)
            .also { it.getJSONObject("settings").put("brightness", 65) }
        packages.receive(changed)
        wifi.worker.runAll()
        packages.worker.runAll()

        assertEquals(2, packages.scalarWrites.count)
        val results = requireNotNull(packages.controller.snapshot()).getJSONObject("results")
        assertEquals(65, results.getJSONObject("brightness").getInt("value"))
        assertEquals("applied", results.getJSONObject("wifi").getString("status"))
        assertEquals(applyCalls, wifi.adapter.applyCalls.size)
        assertEquals(disconnectCalls, wifi.adapter.disconnectCalls)
        assertEquals(initialResult, resultAfterSelection)
    }

    @Test
    fun `cached package release leaves Wi-Fi settings and the same package can be applied again`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val wifi = Harness()
        wifi.adapter.afterSelect = { selected ->
            if (selected == preferred) wifi.connectivity.update(50L + wifi.adapter.selectCalls.size, selected.ssid)
        }
        wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
        val packages = PackageHarness(wifi)
        val original = packagePayload(listOf(preferred, fallback))
        packages.receive(original)
        wifi.worker.runAll()
        wifi.expireNext(2_000L)
        packages.worker.runAll()
        assertNotNull(packages.persistedPolicy)

        packages.receive(noPackagePayload())
        wifi.worker.runAll()
        packages.worker.runAll()

        assertNull(packages.persistedPolicy)
        assertFalse(wifi.store.state.managedWifi)
        assertEquals(listOf(preferred, fallback), wifi.adapter.currentProfiles)

        packages.receive(original)
        wifi.worker.runAll()
        packages.worker.runAll()

        assertNotNull(packages.persistedPolicy)
        assertTrue(wifi.store.state.managedWifi)
        assertEquals(listOf(preferred, fallback), wifi.adapter.currentProfiles)
        assertEquals(2, wifi.adapter.applyCalls.size)
        assertEquals(1, wifi.adapter.selectCalls.size)
        assertEquals(0, wifi.adapter.disconnectCalls)
        assertEquals("applied", packages.attempt()?.results?.getJSONObject("wifi")?.getString("status"))
    }

    @Test
    fun `a package cached before its first attempt is fully applied after reboot`() {
        val before = PackageHarness(deferSdk = true)
        val policy = packagePayload(listOf(network("unused"))).also {
            it.getJSONObject("settings").remove("wifi")
        }
        before.receive(policy)
        assertNull(before.attempt())
        assertNotNull(before.persistedPolicy)

        val after = before.restart(bootCount = 134, deferSdk = true)
        after.enableSdk()
        after.wifi.worker.runAll()
        after.worker.runAll()

        assertEquals(listOf(true), after.scalarWrites.overwriteModes)
        assertEquals(134, after.attempt()?.bootCount)
        assertEquals("complete", after.attempt()?.status)
        assertTrue(after.appliedNotices.single().bootReapply)

        after.receive(policy)
        after.wifi.worker.runAll()
        after.worker.runAll()
        assertEquals(1, after.scalarWrites.count)
        assertEquals(134, after.attempt()?.bootCount)
        assertEquals(1, after.appliedNotices.size)
    }

    @Test
    fun `a new deployment replacing the cached package at boot uses the ordinary notice`() {
        val previousPolicy = packagePayload(listOf(network("unused"))).also {
            it.getJSONObject("settings").remove("wifi")
        }
        val boot = bootWithCachedPackage(previousPolicy)
        val newPolicy = JSONObject(previousPolicy.toString()).put("revision", REVISION_2)

        boot.receive(newPolicy)
        boot.enableSdk()
        boot.wifi.worker.runAll()
        boot.worker.runAll()

        assertEquals(REVISION_2, boot.attempt()?.version?.revision)
        assertFalse(boot.appliedNotices.single().bootReapply)
    }

    @Test
    fun `an offline OS reboot reapplies a partially failed package once before or after redelivery`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val beforeWifi = Harness()
        beforeWifi.adapter.afterSelect = { selected ->
            if (selected == preferred) beforeWifi.connectivity.update(60, selected.ssid)
        }
        val before = PackageHarness(beforeWifi, failedScalars = setOf("brightness"))
        val policy = packagePayload(listOf(preferred, fallback))
        before.receive(policy)
        beforeWifi.worker.runAll()
        beforeWifi.expireNext(2_000L)
        before.worker.runAll()
        assertTrue(requireNotNull(before.attempt()).needsRetry)
        assertEquals("applied", before.attempt()?.results?.getJSONObject("wifi")?.getString("status"))

        for (redeliverBeforeBootApply in listOf(true, false)) {
            val after = before.restart(bootCount = 134, deferSdk = true)
            after.wifi.adapter.afterDisconnect = { after.wifi.connectivity.loseAll() }
            after.wifi.adapter.afterSelect = { selected ->
                if (selected == preferred) after.wifi.connectivity.update(61, selected.ssid)
            }
            val applyCallsBeforeBoot = after.wifi.adapter.applyCalls.size
            if (redeliverBeforeBootApply) after.receive(policy)
            after.enableSdk()
            after.wifi.worker.runAll()
            after.worker.runAll()

            val attempt = requireNotNull(after.attempt())
            assertEquals(134, attempt.bootCount)
            assertTrue(attempt.needsRetry)
            assertEquals("applied", attempt.results.getJSONObject("wifi").getString("status"))
            assertEquals(applyCallsBeforeBoot + 1, after.wifi.adapter.applyCalls.size)

            val writesAfterBootApply = after.journalWriteCalls
            after.receive(policy)
            after.wifi.worker.runAll()
            after.worker.runAll()
            assertEquals(writesAfterBootApply, after.journalWriteCalls)
            assertEquals(1, after.scalarWrites.count)
            assertEquals(applyCallsBeforeBoot + 1, after.wifi.adapter.applyCalls.size)
        }
    }

    @Test
    fun `late Android connection clears a single network boot timeout across a process restart`() {
        val primary = network("office-a")
        val policy = packagePayload(listOf(primary))
        val boot = bootWithCachedPackage(policy)
        boot.enableSdk()
        boot.wifi.worker.runAll()
        boot.wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        boot.wifi.expireNext(12_000L)
        boot.worker.runAll()
        assertEquals("UNREACHABLE", boot.attempt()?.results?.getJSONObject("wifi")?.getString("reason"))
        assertEquals(REVISION, boot.attempt()?.wifiConnectionCheckRevision)
        assertTrue(boot.appliedNotices.isEmpty())

        val restarted = boot.restart(bootCount = 134)
        val wifi = restarted.wifi
        wifi.worker.runAll()
        wifi.connectivity.update(20, "other")
        wifi.connectivity.update(21, primary.ssid, hasUsableIp = false)
        wifi.connectivity.update(22, primary.ssid, security = "OPEN")
        wifi.worker.runAll()
        restarted.worker.runAll()
        assertEquals("failed", restarted.attempt()?.results?.getJSONObject("wifi")?.getString("status"))

        restarted.journalWriteToFail = restarted.journalWriteCalls + 1
        wifi.connectivity.update(21, primary.ssid)
        wifi.worker.runAll()
        restarted.worker.runAll()
        assertEquals("failed", restarted.controller.snapshot()?.getJSONObject("results")?.getJSONObject("wifi")
            ?.getString("status"))
        assertTrue(restarted.appliedNotices.isEmpty())

        wifi.connectivity.update(21, primary.ssid)
        wifi.worker.runAll()
        restarted.worker.runAll()
        wifi.worker.runAll()
        assertEquals("applied", restarted.controller.snapshot()?.getJSONObject("results")?.getJSONObject("wifi")
            ?.getString("status"))
        assertNull(restarted.attempt()?.wifiConnectionCheckRevision)
        assertEquals(ConfigurationApplyStatus.APPLIED, restarted.controller.screenSnapshot()?.applyStatus)
        val notice = restarted.appliedNotices.single()
        assertTrue(notice.bootReapply)
        assertTrue(notice.isCurrent())
        assertEquals(1, wifi.adapter.applyCalls.size)
        assertEquals(listOf(primary), wifi.adapter.selectCalls)
        assertEquals(0, wifi.adapter.disconnectCalls)
        assertEquals(0, wifi.connectivity.openSessionCount)

        val scalarDelta = packagePayload(listOf(primary), revision = REVISION_2)
        scalarDelta.getJSONObject("settings").put("brightness", 65)
        restarted.receive(scalarDelta)
        wifi.worker.runAll()
        restarted.worker.runAll()
        assertEquals(1, wifi.adapter.applyCalls.size)
        assertEquals(65, restarted.attempt()?.results?.getJSONObject("brightness")?.getInt("value"))
        assertFalse(notice.isCurrent())
    }

    @Test
    fun `a late verified fallback clears only Wi-Fi in the current revision`() {
        val primary = network("office-a")
        val fallback = network("office-b")
        val networks = listOf(primary, fallback)
        val initialWifi = Harness(initialNetworks = listOf(primary), initialWifi = listOf(observation(10, primary.ssid)))
        val boot = bootWithCachedPackage(packagePayload(networks), initialWifi, failedScalars = setOf("brightness"))
        val wifi = boot.wifi
        wifi.adapter.afterApply = { wifi.connectivity.loseAll() }
        boot.enableSdk()
        wifi.worker.runAll()
        repeat(2) { wifi.expireNext(12_000L) }
        boot.worker.runAll()
        wifi.worker.runAll()

        val updated = packagePayload(networks, revision = REVISION_2)
        updated.getJSONObject("settings").put("brightness", 65)
        boot.receive(updated)
        wifi.worker.runAll()
        assertEquals(REVISION, boot.attempt()?.wifiConnectionCheckRevision)

        // The active credential changed or was unknown before the boot write.
        wifi.connectivity.update(91, primary.ssid)
        wifi.worker.runAll()
        boot.worker.runAll()
        assertEquals("failed", boot.attempt()?.results?.getJSONObject("wifi")?.getString("status"))

        wifi.connectivity.lose(91)
        wifi.connectivity.update(92, fallback.ssid)
        wifi.worker.runAll()
        boot.worker.runAll()
        val current = requireNotNull(boot.attempt())
        assertEquals(REVISION_2, current.version.revision)
        assertEquals("FALLBACK", current.results.getJSONObject("wifi").getString("reason"))
        assertEquals("applied", current.results.getJSONObject("wifi").getString("status"))
        assertEquals("failed", current.results.getJSONObject("brightness").getString("status"))
        assertEquals(1, wifi.adapter.applyCalls.size)
        assertEquals(networks, wifi.adapter.selectCalls)
    }

    @Test
    fun `a boot deadline does not make partial Wi-Fi writes eligible for recovery`() {
        val primary = network("office-a")
        val fallback = network("office-b")
        val networks = listOf(primary, fallback)
        val failures = listOf(
            WifiProfileApplication(listOf(0), failure = WifiReason.UPDATE_FAILED),
            WifiProfileApplication(listOf(0, 1), autoJoinFailure = WifiReason.VERIFY_FAILED),
        )
        for (application in failures) {
            val previous = WifiSavedProfile(OLD_REVISION, networks)
            val initialWifi = Harness(initialState = WifiLocalState(lastApplied = previous, policyRevision = OLD_REVISION))
            val boot = bootWithCachedPackage(packagePayload(networks), initialWifi)
            val wifi = boot.wifi
            wifi.adapter.application = application
            boot.enableSdk()
            wifi.worker.runAll()
            wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
            wifi.expireNext(WIFI_MANUAL_TIMEOUT_MS)
            boot.worker.runAll()
            assertNull(boot.attempt()?.wifiConnectionCheckRevision)

            wifi.connectivity.update(94, primary.ssid)
            wifi.worker.runAll()
            boot.worker.runAll()
            assertEquals("failed", boot.attempt()?.results?.getJSONObject("wifi")?.getString("status"))
        }
    }

    @Test
    fun `boot apply keeps a surviving verified primary and reports profile or flag failures`() {
        val primary = network("office-a")
        val networks = listOf(primary, network("office-b"))
        val applications = listOf(
            null to "NONE",
            WifiProfileApplication(listOf(0), failure = WifiReason.UPDATE_FAILED) to "UPDATE_FAILED",
            WifiProfileApplication(listOf(0, 1), autoJoinFailure = WifiReason.VERIFY_FAILED) to "VERIFY_FAILED",
        )
        for ((application, reason) in applications) {
            val initialWifi = Harness(
                initialState = WifiLocalState(
                    lastApplied = WifiSavedProfile(OLD_REVISION, networks), policyRevision = OLD_REVISION,
                ),
                initialNetworks = networks,
                initialWifi = listOf(observation(10, primary.ssid)),
            )
            val boot = bootWithCachedPackage(packagePayload(networks), initialWifi)
            val wifi = boot.wifi
            wifi.adapter.application = application
            boot.enableSdk()
            wifi.worker.runAll()
            boot.worker.runAll()

            assertEquals(1, wifi.adapter.applyCalls.size)
            assertEquals(0, wifi.adapter.disconnectCalls)
            assertTrue(wifi.adapter.selectCalls.isEmpty())
            val result = boot.attempt()?.results?.getJSONObject("wifi")
            assertEquals(if (application == null) "applied" else "failed", result?.getString("status"))
            assertEquals(reason, wifi.store.state.latestResult?.reason?.name)
            if (application != null) assertEquals(reason, result?.getString("reason"))
        }
    }

    @Test
    fun `a late verification callback cannot apply after No package`() {
        val primary = network("office-a")
        val boot = bootWithCachedPackage(packagePayload(listOf(primary)))
        val wifi = boot.wifi
        boot.enableSdk()
        wifi.worker.runAll()
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        wifi.expireNext(12_000L)
        boot.worker.runAll()
        wifi.worker.runAll()

        boot.controller.receive(noPackagePayload(), isCurrent = { true })
        wifi.connectivity.update(20, primary.ssid)
        wifi.worker.runAll()
        boot.worker.runAll()
        wifi.worker.runAll()
        boot.worker.runAll()
        assertNull(boot.controller.snapshot())
        assertEquals("failed", boot.attempt()?.results?.getJSONObject("wifi")?.getString("status"))
        assertEquals(0, wifi.connectivity.openSessionCount)
    }

    @Test
    fun `No package survives a reboot before or after the Wi-Fi worker records release`() {
        val preferred = network("office-a")
        val fallback = network("office-b")

        for (releaseRecordedBeforeRestart in listOf(false, true)) {
            val wifi = Harness()
            wifi.adapter.afterSelect = { selected ->
                if (selected == preferred) wifi.connectivity.update(70, selected.ssid)
            }
            val packages = PackageHarness(wifi)
            packages.receive(packagePayload(listOf(preferred, fallback)))
            wifi.worker.runAll()
            wifi.expireNext(2_000L)
            packages.worker.runAll()

            packages.receive(noPackagePayload())
            val removal = JSONObject(requireNotNull(packages.persistedPolicy))
            assertTrue(removal.isNull("package_id"))
            assertEquals(0, removal.getJSONObject("settings").length())
            if (releaseRecordedBeforeRestart) {
                wifi.worker.runAll()
                assertFalse(wifi.store.state.managedWifi)
                assertNotNull(packages.persistedPolicy)
            }

            val after = packages.restart(bootCount = 134)
            after.wifi.worker.runAll()
            after.worker.runAll()

            assertFalse(after.wifi.store.state.managedWifi)
            assertNull(after.persistedPolicy)
            assertEquals(preferred.ssid, after.wifi.connectivity.currentUsableSsid())
            assertEquals(1, after.wifi.adapter.applyCalls.size)
        }
    }

    @Test
    fun `No package releases Wi-Fi even if writing the removal marker fails`() {
        val preferred = network("office-a")
        for (failedWrites in 1..2) {
            val packages = PackageHarness()
            val wifi = packages.wifi
            wifi.adapter.afterSelect = { selected -> wifi.connectivity.update(70, selected.ssid) }
            packages.receive(packagePayload(listOf(preferred)))
            wifi.worker.runAll()
            wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
            packages.worker.runAll()
            val cachedPackage = requireNotNull(packages.persistedPolicy)
            assertTrue(wifi.store.state.managedWifi)

            packages.policyWritesToFail = failedWrites
            packages.receive(noPackagePayload())
            assertEquals(cachedPackage, packages.persistedPolicy)
            wifi.worker.runAll()
            packages.worker.runAll()

            assertFalse(wifi.store.state.managedWifi)
            assertNull(packages.controller.snapshot())
            if (failedWrites == 2) {
                // The cache still needs cleanup. A repeated release can finish it once storage recovers.
                assertEquals(cachedPackage, packages.persistedPolicy)
                packages.receive(noPackagePayload())
                wifi.worker.runAll()
                packages.worker.runAll()
            }
            assertNull(packages.persistedPolicy)

            val restarted = packages.restart()
            restarted.wifi.worker.runAll()
            restarted.worker.runAll()
            assertFalse(restarted.wifi.store.state.managedWifi)
            assertEquals(preferred.ssid, restarted.wifi.connectivity.currentUsableSsid())
            assertEquals(1, restarted.wifi.adapter.applyCalls.size)
        }
    }

    @Test
    fun `explicit Retry is applied once and duplicate retry id does not write again`() {
        val packages = PackageHarness(failedScalars = setOf("brightness"))
        val payload = scalarPackagePayload()
        val retryId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"

        packages.receive(payload)
        packages.wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals(1, packages.scalarWrites.count)
        assertTrue(requireNotNull(packages.attempt()).needsRetry)

        val retry = JSONObject(payload.toString()).put("retry_id", retryId)
        packages.receive(retry)
        packages.wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals(2, packages.scalarWrites.count)
        assertTrue(requireNotNull(packages.attempt()).retryIds.contains(retryId))

        packages.receive(JSONObject(retry.toString()))
        packages.wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals(2, packages.scalarWrites.count)
        assertTrue(requireNotNull(packages.attempt()).retryIds.contains(retryId))
    }

    @Test
    fun `a package awaiting verification skips same boot Retry and verifies at the next OS boot`() {
        val primary = network("office-a")
        val wifi = Harness(initialNetworks = listOf(primary), initialWifi = listOf(observation(10, primary.ssid)))
        wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
        wifi.adapter.afterSelect = { wifi.connectivity.update(11, it.ssid) }
        val packages = PackageHarness(wifi)
        val payload = packagePayload(listOf(primary))
        packages.receive(payload)
        wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals("VERIFY_AFTER_REBOOT", packages.attempt()?.results?.getJSONObject("wifi")?.getString("reason"))

        val sameBoot = packages.restart(bootCount = 133)
        sameBoot.receive(JSONObject(payload.toString()).put("retry_id", OPERATION_ID))
        sameBoot.wifi.worker.runAll()
        sameBoot.worker.runAll()
        assertEquals(0, sameBoot.scalarWrites.count)
        assertEquals(1, wifi.adapter.applyCalls.size)
        assertEquals(0, wifi.adapter.disconnectCalls)
        assertEquals("VERIFY_AFTER_REBOOT", sameBoot.attempt()?.results?.getJSONObject("wifi")?.getString("reason"))

        val nextBoot = sameBoot.restart(bootCount = 134)
        nextBoot.wifi.adapter.afterDisconnect = { nextBoot.wifi.connectivity.loseAll() }
        nextBoot.wifi.adapter.afterSelect = { nextBoot.wifi.connectivity.update(91, it.ssid) }
        nextBoot.wifi.worker.runAll()
        nextBoot.worker.runAll()
        assertEquals(2, wifi.adapter.applyCalls.size)
        assertEquals("applied", nextBoot.attempt()?.results?.getJSONObject("wifi")?.getString("status"))
    }

    @Test
    fun `switching a deferred package to Android selection clears its previous verification result`() {
        val primary = network("office-a")
        val wifi = Harness(initialNetworks = listOf(primary), initialWifi = listOf(observation(10, primary.ssid)))
        wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
        wifi.adapter.afterSelect = { wifi.connectivity.update(11, it.ssid) }
        val packages = PackageHarness(wifi)
        packages.receive(packagePayload(listOf(primary)))
        wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals("VERIFY_AFTER_REBOOT", packages.attempt()?.results?.getJSONObject("wifi")?.getString("reason"))

        packages.receive(packagePayload(listOf(primary), revision = REVISION_2, prioritizeNetworks = false))
        wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals("SAVED", packages.attempt()?.results?.getJSONObject("wifi")?.getString("reason"))
        assertEquals(2, wifi.adapter.applyCalls.size)
        assertEquals(0, wifi.adapter.disconnectCalls)
    }

    @Test
    fun `Retry for another setting keeps deferred Wi-Fi verification through a journal write failure`() {
        val primary = network("office-a")
        val wifi = Harness(initialNetworks = listOf(primary), initialWifi = listOf(observation(10, primary.ssid)))
        wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
        wifi.adapter.afterSelect = { wifi.connectivity.update(11, it.ssid) }
        val failedScalars = mutableSetOf("brightness")
        val packages = PackageHarness(wifi, failedScalars = failedScalars)
        val payload = packagePayload(listOf(primary))
        packages.receive(payload)
        wifi.worker.runAll()
        packages.worker.runAll()

        packages.journalWriteToFail = packages.journalWriteCalls + 1
        packages.receive(JSONObject(payload.toString()).put("retry_id", OPERATION_ID))
        wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals("interrupted", packages.controller.snapshot()?.getString("status"))
        assertEquals("VERIFY_AFTER_REBOOT", packages.controller.snapshot()?.getJSONObject("results")
            ?.getJSONObject("wifi")?.getString("reason"))

        failedScalars.clear()
        packages.receive(JSONObject(payload.toString()).put("retry_id", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"))
        wifi.worker.runAll()
        packages.worker.runAll()
        val results = requireNotNull(packages.attempt()).results
        assertEquals(2, packages.scalarWrites.count)
        assertEquals("applied", results.getJSONObject("brightness").getString("status"))
        assertEquals("VERIFY_AFTER_REBOOT", results.getJSONObject("wifi").getString("reason"))
        assertEquals(1, wifi.adapter.applyCalls.size)
        assertEquals(0, wifi.adapter.disconnectCalls)
    }

    @Test
    fun `cached package Reapply writes scalar and Wi-Fi settings while offline`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val wifi = Harness()
        wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
        wifi.adapter.afterSelect = { selected ->
            if (selected == preferred) wifi.connectivity.update(80L + wifi.adapter.selectCalls.size, selected.ssid)
        }
        val packages = PackageHarness(wifi)
        val policy = packagePayload(listOf(preferred, fallback))
        packages.receive(policy)
        wifi.worker.runAll()
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        packages.worker.runAll()
        assertEquals("complete", packages.attempt()?.status)

        wifi.connectivity.loseAll()
        val writeCount = packages.scalarWrites.count
        val profileWriteCount = wifi.adapter.applyCalls.size
        val selectCount = wifi.adapter.selectCalls.size
        var started: Boolean? = null
        packages.controller.reapply(ConfigurationVersion(PACKAGE_ID, REVISION)) { started = it }
        packages.worker.runAll()
        wifi.worker.runAll()
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        packages.worker.runAll()

        assertEquals(true, started)
        assertEquals(writeCount + 1, packages.scalarWrites.count)
        assertEquals(listOf(false, true), packages.scalarWrites.overwriteModes)
        assertEquals(profileWriteCount + 1, wifi.adapter.applyCalls.size)
        assertEquals(selectCount + 1, wifi.adapter.selectCalls.size)
        assertEquals("office-a", wifi.connectivity.currentUsableSsid())
        assertEquals("complete", packages.attempt()?.status)
    }

    @Test
    fun `journal claim and checkpoint failures report STORAGE_ERROR and recover on Retry`() {
        val preferred = network("office-a")
        val policy = packagePayload(listOf(preferred))

        for (failedWrite in 1..2) {
            val packages = PackageHarness()
            packages.wifi.adapter.afterSelect = { selected ->
                packages.wifi.connectivity.update(90, selected.ssid)
            }
            packages.journalWriteToFail = failedWrite
            packages.receive(policy)

            packages.wifi.worker.runAll()
            packages.worker.runAll()
            assertTrue(packages.wifi.controller.canProcessPolicy())

            assertEquals(0, packages.wifi.adapter.applyCalls.size)
            assertEquals(if (failedWrite == 1) 0 else 1, packages.scalarWrites.count)
            val failed = requireNotNull(packages.controller.snapshot())
            assertEquals("interrupted", failed.getString("status"))
            assertEquals("failed", failed.getJSONObject("results").getJSONObject("wifi").getString("status"))
            assertEquals("STORAGE_ERROR", failed.getJSONObject("results").getJSONObject("wifi").getString("reason"))

            val recovery = JSONObject(policy.toString()).put("retry_id", "ffffffff-ffff-4fff-8fff-ffffffffffff")
            packages.receive(recovery)
            packages.wifi.worker.runAll()
            packages.wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
            packages.worker.runAll()

            assertEquals("complete", packages.attempt()?.status)
            assertEquals("applied", packages.attempt()?.results?.getJSONObject("wifi")?.getString("status"))
            assertEquals(1, packages.wifi.adapter.applyCalls.size)
        }
    }

    @Test
    fun `stale Wi-Fi completion after release and reassignment cannot restore the old journal entry`() {
        val oldNetwork = network("old-office")
        val newNetwork = network("new-office")
        val wifi = Harness()
        wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
        val packages = PackageHarness(wifi)
        val oldPolicy = packagePayload(listOf(oldNetwork))
        val newPolicy = packagePayload(listOf(newNetwork), revision = REVISION_2, packageId = PACKAGE_ID_2)

        packages.receive(oldPolicy)
        wifi.worker.runAll()
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        assertEquals(listOf(oldNetwork), wifi.adapter.selectCalls)
        // Queue old A's connection proof for the Wi-Fi worker to process after B is assigned.
        wifi.connectivity.update(100, oldNetwork.ssid)

        packages.receive(noPackagePayload())
        packages.receive(newPolicy)
        wifi.adapter.afterSelect = { selected ->
            if (selected == newNetwork) wifi.connectivity.update(101, selected.ssid)
        }
        wifi.worker.runAll()
        packages.worker.runAll()
        wifi.worker.runAll()
        packages.worker.runAll()

        val journal = packages.journal()
        assertNull(journal.find(ConfigurationVersion(PACKAGE_ID, REVISION)))
        assertEquals(ConfigurationVersion(PACKAGE_ID_2, REVISION_2), journal.latest?.version)
        assertEquals("complete", journal.latest?.status)
        assertEquals("new-office", wifi.connectivity.currentUsableSsid())
    }

    @Test
    fun `active and queued Wi-Fi requests report BUSY to a third request`() {
        val harness = Harness()
        val results = mutableListOf<WifiManualResult>()
        repeat(3) { index ->
            harness.controller.applyManagedPolicy(
                REVISION,
                settings(listOf(network("office-$index"))),
                reuseMatchingProfile = false,
                isCurrent = { true },
                onFinished = { results += it },
                operationId = "${index + 1}1111111-1111-4111-8111-111111111111",
            )
        }
        harness.worker.runAll()

        assertEquals(1, results.count { it.reason == WifiReason.BUSY })
        assertTrue(results.any { it.operationId.startsWith("311") && it.reason == WifiReason.BUSY })
        assertTrue(harness.controller.stateSnapshot().getBoolean("busy"))
    }

    @Test
    fun `failed auto return restores the fallback and keeps result and backoff`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val profile = WifiSavedProfile(REVISION, listOf(preferred, fallback), autoReturn = true)
        val success = WifiManualResult(OPERATION_ID, REVISION, "success", WifiReason.NONE)
        val preferredVisible = WifiPriorityScanEvent(WifiPriorityMonitorStatus.MONITORING, setOf(preferred.identity))
        val wifi = Harness(
            initialState = WifiLocalState(
                managedWifi = true,
                lastApplied = profile,
                policyRevision = REVISION,
                latestResult = success,
            ),
            initialNetworks = listOf(preferred, fallback),
            initialWifi = listOf(observation(110, fallback.ssid)),
        )
        wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
        var handle = 110L
        wifi.adapter.afterSelect = { if (it == fallback) wifi.connectivity.update(++handle, fallback.ssid) }

        assertEquals(1, wifi.priorityScanSource.startCalls)
        wifi.priorityScanSource.emit(preferredVisible)
        wifi.worker.runAll()
        wifi.expireNext(12_000L)
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        assertEquals(success, wifi.store.state.latestResult)
        assertEquals(listOf(preferred, fallback), wifi.adapter.selectCalls)
        assertEquals(fallback.ssid, wifi.connectivity.currentUsableSsid())

        wifi.clock.value += 5 * 60_000L - WIFI_PRIOR_CONNECTION_OBSERVATION_MS - 1
        wifi.priorityScanSource.emit(preferredVisible)
        wifi.worker.runAll()
        assertEquals(2, wifi.adapter.selectCalls.size)

        wifi.clock.value++
        wifi.priorityScanSource.emit(preferredVisible)
        wifi.worker.runAll()
        assertEquals(3, wifi.adapter.selectCalls.size)
        wifi.expireNext(12_000L)
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        assertEquals(success, wifi.store.state.latestResult)
        assertEquals(listOf(preferred, fallback, preferred, fallback), wifi.adapter.selectCalls)
        assertEquals(fallback.ssid, wifi.connectivity.currentUsableSsid())

        wifi.clock.value += 10 * 60_000L - WIFI_PRIOR_CONNECTION_OBSERVATION_MS - 1
        wifi.priorityScanSource.emit(preferredVisible)
        wifi.worker.runAll()
        assertEquals(4, wifi.adapter.selectCalls.size)

        wifi.adapter.afterSelect = { selected -> wifi.connectivity.update(++handle, selected.ssid) }
        wifi.clock.value++
        wifi.priorityScanSource.emit(preferredVisible)
        wifi.worker.runAll()

        assertEquals(listOf(preferred, fallback, preferred, fallback, preferred), wifi.adapter.selectCalls)
        assertEquals(preferred.ssid, wifi.connectivity.currentUsableSsid())
        val recovered = requireNotNull(wifi.store.state.latestResult)
        assertEquals("success", recovered.status)
        assertFalse(recovered.operationId == success.operationId)
    }

    @Test
    fun `auto return restores the prior candidate even at the overall deadline`() {
        val primary = network("office-a")
        val middle = network("office-b")
        val prior = network("office-c")
        val networks = listOf(primary, middle, prior)
        val wifi = Harness(
            initialState = WifiLocalState(
                lastApplied = WifiSavedProfile(REVISION, networks, autoReturn = true), policyRevision = REVISION,
            ),
            initialNetworks = networks,
            initialWifi = listOf(observation(10, prior.ssid)),
        )
        wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
        wifi.adapter.afterSelect = { if (it == prior) wifi.connectivity.update(11, prior.ssid) }

        wifi.priorityScanSource.emit(WifiPriorityScanEvent(WifiPriorityMonitorStatus.MONITORING, setOf(primary.identity)))
        wifi.worker.runAll()
        wifi.expireNext(WIFI_MANUAL_TIMEOUT_MS)
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        wifi.replayTimer(12_000L, 0)

        assertEquals(listOf(primary, prior), wifi.adapter.selectCalls)
        assertEquals(prior.ssid, wifi.connectivity.currentUsableSsid())
    }

    @Test
    fun `an uncached removal cannot clear a newer assignment when its release finishes`() {
        val preferred = network("office-a")
        val packages = PackageHarness()
        val wifi = packages.wifi
        wifi.adapter.afterSelect = { selected -> wifi.connectivity.update(70, selected.ssid) }

        packages.policyWritesToFail = 1
        packages.receive(noPackagePayload())
        packages.receive(packagePayload(listOf(preferred)))
        val cachedPackage = requireNotNull(packages.persistedPolicy)
        wifi.worker.runAll()
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        packages.worker.runAll()

        assertEquals(cachedPackage, packages.persistedPolicy)
        assertTrue(wifi.store.state.managedWifi)
        assertEquals(REVISION, packages.controller.snapshot()?.optString("revision"))
    }

    private class Harness(
        initialState: WifiLocalState = WifiLocalState(),
        initialNetworks: List<WifiNetworkProfile> = emptyList(),
        initialWifi: List<WifiLocalNetworkObservation> = emptyList(),
        preflightFailure: WifiReason? = null,
        sharedAdapter: FakeAdapter? = null,
        bootCount: Int = 133,
    ) {
        val store = MemoryStore(initialState)
        val adapter = sharedAdapter ?: FakeAdapter(initialNetworks)
        val clock = MutableClock()
        val worker = QueueExecutor()
        private val timers = mutableListOf<ScheduledTask>()
        val connectivity = FakeConnectivity(initialWifi).apply { preflightResult = preflightFailure }
        val priorityScanSource = FakePriorityScanSource()
        val controller = WifiSettingsController(
            WifiControllerDependencies(
                store = store,
                adapter = adapter,
                connectivity = connectivity,
                nowElapsed = { clock.value },
                worker = worker,
                schedule = { task, delay -> timers += ScheduledTask(task, delay, clock.value + delay, worker) },
                readinessFailure = { preflightFailure },
                priorityScanSource = priorityScanSource,
                bootCount = bootCount,
            ),
        )

        init { worker.runAll() }

        fun apply(
            networks: List<WifiNetworkProfile>,
            autoReturn: Boolean = false,
            reuseMatchingProfile: Boolean = false,
            prioritizeNetworks: Boolean = true,
        ) {
            controller.applyManagedPolicy(
                REVISION,
                settings(networks, autoReturn, prioritizeNetworks),
                reuseMatchingProfile = reuseMatchingProfile,
                isCurrent = { true },
                onFinished = {},
                operationId = OPERATION_ID,
            )
            worker.runAll()
        }

        fun expireNext(delayMillis: Long) {
            val timer = timers.firstOrNull { !it.fired && it.delayMillis == delayMillis }
                ?: error("no pending timer with delay $delayMillis")
            clock.value = maxOf(clock.value, timer.dueAt)
            timer.run()
            worker.runAll()
        }

        fun replayTimer(delayMillis: Long, index: Int) {
            val timer = timers.filter { it.delayMillis == delayMillis }.getOrNull(index)
                ?: error("no timer $index with delay $delayMillis")
            clock.value = maxOf(clock.value, timer.dueAt)
            timer.run()
            worker.runAll()
        }
    }

    private class FakeAdapter(initialNetworks: List<WifiNetworkProfile>) : WifiSettingsAdapter {
        private data class Saved(var profile: WifiNetworkProfile, var autoJoin: Boolean = true)

        private val saved = initialNetworks.map(::Saved).toMutableList()
        val currentProfiles: List<WifiNetworkProfile> get() = saved.map { it.profile }
        val currentAutoJoin: List<Boolean> get() = saved.map { it.autoJoin }
        val applyCalls = mutableListOf<List<WifiNetworkProfile>>()
        val selectCalls = mutableListOf<WifiNetworkProfile>()
        var disconnectCalls = 0
            private set
        var application: WifiProfileApplication? = null
        var afterApply: (() -> Unit)? = null
        var afterDisconnect: (() -> Unit)? = null
        var onDisconnect: (() -> Unit)? = null
        var afterSelect: ((WifiNetworkProfile) -> Unit)? = null

        override fun applyProfile(
            target: List<WifiNetworkProfile>,
            prioritizeNetworks: Boolean,
            beforeMutation: () -> WifiReason?,
        ): WifiProfileApplication {
            applyCalls += target.toList()
            val reason = beforeMutation()
            if (reason != null) return WifiProfileApplication(emptyList(), reason)

            val result = application ?: WifiProfileApplication(target.indices.toList())
            if (result.usableIndexes.isEmpty()) return result
            for ((index, profile) in target.withIndex()) {
                if (index !in result.usableIndexes) continue
                val existing = saved.firstOrNull { it.profile.identity == profile.identity }
                if (existing == null) saved += Saved(profile) else existing.profile = profile
            }
            val autoJoinTargets = if (prioritizeNetworks) target.take(1) else target
            for (entry in saved) {
                entry.autoJoin = autoJoinTargets.any { it.identity == entry.profile.identity }
            }
            afterApply?.invoke()
            return result
        }

        override fun selectSavedCandidate(
            candidate: WifiNetworkProfile,
            beforeMutation: () -> WifiReason?,
        ): WifiReason {
            selectCalls += candidate
            beforeMutation()?.let { return it }
            afterSelect?.invoke(candidate)
            return WifiReason.NONE
        }

        override fun disconnectCurrent(beforeMutation: () -> WifiReason?): WifiReason {
            disconnectCalls++
            beforeMutation()?.let { return it }
            onDisconnect?.invoke()
            afterDisconnect?.invoke()
            return WifiReason.NONE
        }
    }

    private class FakeConnectivity(initial: List<WifiLocalNetworkObservation>) : WifiLocalConnectivitySource {
        var preflightResult: WifiReason? = null
        var preflightCalls = 0
        var observeCalls = 0
        private var sequence = initial.maxOfOrNull { it.sequence } ?: 0L
        private val current = initial.associateBy { it.networkHandle }.toMutableMap()
        private val sessions = mutableListOf<FakeSession>()
        val openSessionCount get() = sessions.count { !it.closed }

        override fun preflight(): WifiReason? {
            preflightCalls++
            return preflightResult
        }

        override fun observe(listener: (WifiLocalConnectivityEvent) -> Unit): WifiLocalConnectivitySession {
            observeCalls++
            return FakeSession(current.values.toList(), listener).also { sessions += it }
        }

        fun update(handle: Long, ssid: String, hasUsableIp: Boolean = true, security: String = "WPA_PSK") {
            val observation = WifiLocalNetworkObservation(handle, ssid, hasUsableIp, ++sequence, security)
            current[handle] = observation
            sessions.toList().forEach { it.update(observation) }
        }

        fun lose(handle: Long) {
            current.remove(handle)
            val event = WifiLocalConnectivityEvent.Lost(handle, ++sequence)
            sessions.toList().forEach { it.lose(event) }
        }

        fun loseAll() { current.keys.toList().forEach(::lose) }

        fun currentUsableSsid(): String? = current.values.lastOrNull { it.hasUsableIp }?.ssid

        private class FakeSession(
            initial: List<WifiLocalNetworkObservation>,
            private val listener: (WifiLocalConnectivityEvent) -> Unit,
        ) : WifiLocalConnectivitySession {
            private val observations = initial.associateBy { it.networkHandle }.toMutableMap()
            var closed = false
                private set

            override fun snapshot(): List<WifiLocalNetworkObservation> = observations.values.toList()
            override fun lastSequence(): Long = observations.values.maxOfOrNull { it.sequence } ?: 0L
            fun update(observation: WifiLocalNetworkObservation) {
                if (closed) return
                observations[observation.networkHandle] = observation
                listener(WifiLocalConnectivityEvent.Updated(observation))
            }
            fun lose(event: WifiLocalConnectivityEvent.Lost) {
                if (closed) return
                observations.remove(event.networkHandle)
                listener(event)
            }
            override fun close() { closed = true }
        }
    }

    private class FakePriorityScanSource : WifiPriorityScanSource {
        private var listener: ((WifiPriorityScanEvent) -> Unit)? = null
        var startCalls = 0

        override fun start(networks: List<WifiNetworkProfile>, listener: (WifiPriorityScanEvent) -> Unit) {
            startCalls++
            this.listener = listener
            listener(WifiPriorityScanEvent(WifiPriorityMonitorStatus.MONITORING, detail = "test_started"))
        }

        override fun stop() {
            listener = null
        }

        fun emit(event: WifiPriorityScanEvent) { listener?.invoke(event) }
    }

    private data class AppliedNotice(val bootReapply: Boolean, val isCurrent: () -> Boolean)

    private class PackageHarness(
        val wifi: Harness = Harness(),
        private var encodedJournal: String? = null,
        initialPolicy: String? = null,
        private val bootCount: Int = 133,
        deferSdk: Boolean = false,
        failedScalars: Set<String> = emptySet(),
    ) {
        val worker = QueueExecutor()
        val scalarWrites = ScalarWrites(failedScalars)
        val appliedNotices = mutableListOf<AppliedNotice>()
        var persistedPolicy: String? = initialPolicy
            private set
        var policyWritesToFail = 0
        var journalWriteToFail = 0
        var journalWriteCalls = 0
            private set
        val controller = ConfigurationPackageController(
            ConfigurationPackageDependencies(
                worker = worker,
                bootCount = bootCount,
                readJournal = { encodedJournal },
                writeJournal = {
                    journalWriteCalls++
                    if (journalWriteCalls == journalWriteToFail) {
                        false
                    } else {
                        encodedJournal = it
                        true
                    }
                },
                readLastPolicy = { persistedPolicy },
                writeLastPolicy = {
                    if (policyWritesToFail > 0) {
                        policyWritesToFail--
                        false
                    } else {
                        persistedPolicy = it
                        true
                    }
                },
                scalarWriter = { { values, overwrite -> scalarWrites.apply(values, overwrite) } },
                onApplied = { bootReapply, isCurrent -> appliedNotices += AppliedNotice(bootReapply, isCurrent) },
            ),
            wifi.controller,
        )

        init { if (!deferSdk) enableSdk() }

        fun enableSdk() { controller.onSdkReady(); worker.runAll() }

        fun receive(payload: JSONObject) {
            controller.receive(payload, supportsApply = true, isCurrent = { true })
            worker.runAll()
        }

        fun restart(
            bootCount: Int = this.bootCount + 1,
            deferSdk: Boolean = false,
        ): PackageHarness {
            val restoredState = WifiSettingsCodec.decode(WifiSettingsCodec.encode(wifi.store.state))
            val connected = wifi.connectivity.currentUsableSsid()
            val restartedWifi = Harness(
                initialState = restoredState,
                initialNetworks = wifi.adapter.currentProfiles,
                initialWifi = connected?.let { listOf(observation(90, it)) }.orEmpty(),
                sharedAdapter = wifi.adapter,
                bootCount = bootCount,
            )
            return PackageHarness(
                wifi = restartedWifi,
                encodedJournal = encodedJournal,
                initialPolicy = persistedPolicy,
                bootCount = bootCount,
                deferSdk = deferSdk,
                failedScalars = scalarWrites.failedKeys,
            )
        }

        fun attempt(): ConfigurationAttempt? = encodedJournal?.let(ConfigurationJournal::restore)?.latest

        fun journal(): ConfigurationJournal = encodedJournal?.let(ConfigurationJournal::restore)
            ?: ConfigurationJournal()
    }

    private class ScalarWrites(val failedKeys: Set<String> = emptySet()) {
        var count = 0
            private set
        val calls = mutableListOf<Set<String>>()
        val overwriteModes = mutableListOf<Boolean>()

        fun apply(values: JSONObject, overwrite: Boolean): JSONObject {
            count++
            val keys = values.keys().asSequence().toSet()
            calls.add(keys)
            overwriteModes.add(overwrite)
            return JSONObject().apply {
                for (key in keys) {
                    val outcome = if (key in failedKeys) settingOutcome("failed", reason = "UPDATE_FAILED")
                        else settingOutcome("applied", values.get(key))
                    put(key, outcome)
                }
            }
        }
    }

    private class MemoryStore(initial: WifiLocalState) : WifiLocalStore {
        var state = initial
            private set

        override fun read(): WifiLocalState = state
        override fun write(state: WifiLocalState) { this.state = state }
    }

    private class MutableClock(var value: Long = 0L)

    private class ScheduledTask(
        action: Runnable,
        val delayMillis: Long,
        val dueAt: Long,
        private val worker: QueueExecutor,
    ) {
        var fired = false
            private set
        private val action = action
        fun run() {
            fired = true
            worker.execute(action)
        }
    }

    private class QueueExecutor : Executor {
        private val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.addLast(command) }
        fun runAll() {
            var count = 0
            while (pending.isNotEmpty()) {
                check(++count <= 100) { "executor did not quiesce" }
                pending.removeFirst().run()
            }
        }
    }

    companion object {
        private const val OPERATION_ID = "11111111-1111-4111-8111-111111111111"
        private const val REVISION = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        private const val OLD_REVISION = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        private const val REVISION_2 = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        private const val PACKAGE_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        private const val PACKAGE_ID_2 = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

        private fun network(ssid: String, password: String = "password-123") =
            WifiNetworkProfile(ssid, "WPA2_WPA3", password, hidden = false)

        private fun bootWithCachedPackage(
            policy: JSONObject,
            initialWifi: Harness = Harness(),
            failedScalars: Set<String> = emptySet(),
        ): PackageHarness {
            val before = PackageHarness(initialWifi, deferSdk = true, failedScalars = failedScalars)
            before.receive(policy)
            return before.restart(bootCount = 134, deferSdk = true)
        }

        private fun observation(handle: Long, ssid: String) =
            WifiLocalNetworkObservation(handle, ssid, true, 1L, "WPA_PSK")

        private fun settings(
            networks: List<WifiNetworkProfile>,
            autoReturn: Boolean = false,
            prioritizeNetworks: Boolean = true,
        ) = JSONObject()
            .put("networks", JSONArray().apply {
                networks.forEach { profile -> put(JSONObject()
                    .put("ssid", profile.ssid)
                    .put("security", profile.security)
                    .put("password", profile.password)
                    .put("hidden", profile.hidden))
                }
            })
            .put("auto_return", autoReturn)
            .apply { if (!prioritizeNetworks) put("prioritize_networks", false) }

        private fun packagePayload(
            networks: List<WifiNetworkProfile>,
            revision: String = REVISION,
            packageId: String = PACKAGE_ID,
            prioritizeNetworks: Boolean = true,
        ) = JSONObject()
            .put("package_id", packageId)
            .put("revision", revision)
            .put("settings", JSONObject().put("brightness", 60)
                .put("wifi", settings(networks, prioritizeNetworks = prioritizeNetworks)))

        private fun scalarPackagePayload() = JSONObject()
            .put("package_id", PACKAGE_ID)
            .put("revision", REVISION)
            .put("settings", JSONObject().put("brightness", 60))

        private fun noPackagePayload() = JSONObject()
            .put("package_id", JSONObject.NULL)
            .put("revision", JSONObject.NULL)
            .put("settings", JSONObject())
    }
}
