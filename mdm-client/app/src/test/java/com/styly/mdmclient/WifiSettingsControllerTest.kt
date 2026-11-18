package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiSettingsControllerTest {
    @Test
    fun `startup keeps the saved Wi-Fi profile without rewriting configured networks`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val profile = WifiSavedProfile(REVISION, listOf(preferred, fallback))
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = profile),
            initialNetworks = listOf(preferred, fallback),
            initialWifi = listOf(observation(12, fallback.ssid)),
        )

        harness.controller.start()
        harness.worker.runAll()

        assertEquals(profile, harness.store.state.lastApplied)
        assertEquals(0, harness.adapter.applyCalls.size)
        assertEquals(listOf(preferred, fallback), harness.adapter.currentProfiles)
        assertTrue(harness.controller.canAcceptPolicy())
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
    fun `selection accepts only connections received before the deadline while the worker is blocked`() {
        val preferred = network("office-a")
        for (offset in listOf(-1L, 0L, 1L)) {
            val harness = Harness()
            harness.adapter.afterSelect = { selected ->
                harness.clock.value += WIFI_MANUAL_TIMEOUT_MS + offset
                harness.connectivity.update(21, selected.ssid)
                harness.clock.value += 2
            }

            harness.apply(listOf(preferred))
            harness.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
            if (offset >= 0) harness.expireNext(WIFI_MANUAL_TIMEOUT_MS)

            val result = requireNotNull(harness.store.state.latestResult)
            assertEquals(if (offset < 0) WifiReason.NONE else WifiReason.UNREACHABLE, result.reason)
        }
    }

    @Test
    fun `the candidate timeout uses the callback receipt time`() {
        val preferred = network("office-a")
        for (offset in listOf(-1L, 0L, 1L)) {
            val harness = Harness()
            harness.apply(listOf(preferred))
            harness.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
            harness.clock.value += 12_000L + offset
            harness.connectivity.update(21, preferred.ssid)
            harness.clock.value += 2
            harness.expireNext(12_000L)

            val result = requireNotNull(harness.store.state.latestResult)
            assertEquals(if (offset < 0) WifiReason.NONE else WifiReason.UNREACHABLE, result.reason)
        }
    }

    @Test
    fun `a primary connection first observed during a slow profile save must arrive before the deadline`() {
        val preferred = network("office-a")
        for (offset in listOf(-1L, 0L)) {
            val harness = Harness(initialWifi = listOf(observation(10, "other")))
            harness.adapter.afterApply = {
                harness.clock.value += WIFI_MANUAL_TIMEOUT_MS + offset
                harness.connectivity.lose(10)
                harness.connectivity.update(21, preferred.ssid)
                harness.clock.value += 2
            }

            harness.apply(listOf(preferred))

            val result = requireNotNull(harness.store.state.latestResult)
            assertEquals(if (offset < 0) WifiReason.NONE else WifiReason.UNREACHABLE, result.reason)
        }
    }

    @Test
    fun `a queued observation from before the attempt cannot trigger another disconnect`() {
        val saved = WifiSavedProfile(OLD_REVISION, listOf(network("office-a"), network("office-b")))
        val wifi = Harness(
            initialState = WifiLocalState(lastApplied = saved),
            initialNetworks = saved.networks,
            initialWifi = listOf(observation(10, "office-a")),
        )
        val requested = network("new-office")
        wifi.controller.applyManagedPolicy(
            REVISION, settings(listOf(requested)), reuseMatchingProfile = false, isCurrent = { true },
            onFinished = {},
        )
        wifi.worker.runNext()
        wifi.connectivity.update(11, "temporary")
        wifi.connectivity.lose(11)
        wifi.worker.runAll()

        assertEquals(1, wifi.adapter.disconnectCalls)
        assertEquals(1, wifi.connectivity.observeCalls)
        wifi.adapter.afterSelect = { selected -> wifi.connectivity.update(12, selected.ssid) }
        wifi.connectivity.lose(10)
        wifi.worker.runAll()
        assertEquals(listOf(requested), wifi.adapter.selectCalls)
        assertEquals(0, wifi.connectivity.openSessionCount)
    }

    @Test
    fun `late verification shares the priority observer and release closes it`() {
        val profile = WifiSavedProfile(REVISION, listOf(network("office-a"), network("office-b")))
        val wifi = Harness(initialState = WifiLocalState(lastApplied = profile))
        val confirmations = mutableListOf<WifiReason>()
        wifi.controller.watchSavedConnection(profile, confirmations::add)
        wifi.worker.runAll()

        assertEquals(1, wifi.connectivity.observeCalls)
        assertEquals(1, wifi.connectivity.openSessionCount)
        wifi.connectivity.update(10, "office-b")
        wifi.worker.runAll()
        assertEquals(listOf(WifiReason.FALLBACK), confirmations)

        wifi.controller.stopManagingPolicy { assertTrue(it) }
        wifi.worker.runAll()
        assertEquals(0, wifi.connectivity.openSessionCount)
    }

    @Test
    fun `priority monitoring resumes when observation becomes available`() {
        val profile = WifiSavedProfile(REVISION, listOf(network("office-a"), network("office-b")), autoReturn = true)
        val wifi = Harness(
            initialState = WifiLocalState(lastApplied = profile),
            initialWifi = listOf(observation(10, "office-b")),
            preflightFailure = WifiReason.PERMISSION_DENIED,
        )
        assertEquals(0, wifi.connectivity.openSessionCount)
        assertEquals(0, wifi.priorityScanSource.startCalls)

        wifi.connectivity.preflightResult = null
        wifi.controller.refreshReadiness()
        wifi.worker.runAll()
        assertEquals(1, wifi.connectivity.openSessionCount)
        assertEquals(1, wifi.priorityScanSource.startCalls)
    }

    @Test
    fun `switching to Android selection stops the old retry loop even when all writes fail`() {
        val networks = listOf(network("office-a"), network("office-b"))
        val previous = WifiSavedProfile(OLD_REVISION, networks)
        val wifi = Harness(initialNetworks = networks,
            initialState = WifiLocalState(lastApplied = previous))
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
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old))),
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
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old, backup))),
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
        assertEquals(observeCalls, harness.connectivity.observeCalls)
    }

    @Test
    fun `same boot reapply must not accept the same cached association`() {
        val old = network("office-a", password = "old-password")
        val changed = network("office-a", password = "new-password")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old))),
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
    fun `superseded assignment cannot disconnect after profile writes already started`() {
        val previous = network("old-office")
        val requested = network("new-office")
        val harness = Harness(initialWifi = listOf(observation(10, previous.ssid)))
        var current = true
        var disconnected = false
        var outcome: WifiManualResult? = null
        harness.adapter.afterApply = { current = false }
        harness.adapter.onDisconnect = { disconnected = true }

        harness.controller.applyManagedPolicy(
            REVISION, settings(listOf(requested)), reuseMatchingProfile = false,
            isCurrent = { current }, onFinished = { outcome = it },
        )
        harness.worker.runAll()

        assertEquals(listOf(requested), harness.adapter.currentProfiles)
        assertFalse(disconnected)
        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertEquals(WifiReason.UNKNOWN, outcome?.reason)
    }

    @Test
    fun `overall deadline preserves the profile write failure`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val harness = Harness(initialWifi = listOf(observation(10, "old-office")))
        harness.adapter.application = WifiProfileApplication(listOf(1), WifiReason.UPDATE_FAILED)

        harness.apply(listOf(preferred, fallback))
        assertEquals(1, harness.adapter.disconnectCalls)
        harness.expireNext(WIFI_MANUAL_TIMEOUT_MS)

        assertEquals(WifiReason.UPDATE_FAILED, harness.store.state.latestResult?.reason)
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
    fun `unverified marker survives candidate exclusion and readdition`() {
        val primary = network("office-a")
        val fallback = network("office-b")
        val marker = UnverifiedWifiCredential(133, wifiPasswordHash(primary.password))
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(
                OLD_REVISION, listOf(primary, fallback),
                unverifiedOnBoot = mapOf(primary.identity to marker),
            )),
            initialNetworks = listOf(primary, fallback),
            initialWifi = listOf(observation(10, fallback.ssid)),
        )

        harness.apply(listOf(fallback))
        assertEquals(marker, harness.store.state.lastApplied?.unverifiedOnBoot?.get(primary.identity))

        harness.apply(listOf(primary, fallback))

        val saved = requireNotNull(harness.store.state.lastApplied)
        assertEquals(133, saved.unverifiedBoot(primary, 133))
    }

    @Test
    fun `unverified marker survives partial save then a successful retry from fallback`() {
        val oldPrimary = network("office-a", "old-password")
        val primary = network("office-a", "new-password")
        val fallback = network("office-b")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(
                OLD_REVISION, listOf(oldPrimary, fallback),
            )),
            initialNetworks = listOf(oldPrimary, fallback),
            initialWifi = listOf(observation(10, oldPrimary.ssid)),
        )
        harness.adapter.application = WifiProfileApplication(
            usableIndexes = listOf(1), failure = WifiReason.UPDATE_FAILED,
        )
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        harness.adapter.afterSelect = { selected ->
            if (selected == fallback) harness.connectivity.update(11, selected.ssid)
        }

        harness.apply(listOf(primary, fallback))

        val partial = requireNotNull(harness.store.state.lastApplied)
        assertEquals(listOf(fallback), partial.networks)
        assertEquals(133, partial.unverifiedBoot(primary, 133))
        assertEquals(fallback.ssid, harness.connectivity.currentUsableSsid())

        harness.adapter.application = null
        harness.adapter.afterSelect = { selected ->
            if (selected == primary) harness.connectivity.update(12, selected.ssid)
        }
        harness.apply(listOf(primary, fallback))

        val retried = requireNotNull(harness.store.state.lastApplied)
        assertEquals(listOf(primary, fallback), retried.networks)
        assertEquals(primary.ssid, harness.connectivity.currentUsableSsid())
        assertEquals("failed", harness.store.state.latestResult?.status)
        assertEquals(WifiReason.VERIFY_AFTER_REBOOT, harness.store.state.latestResult?.reason)
    }

    @Test
    fun `empty first apply retains a marker without making its empty profile managed`() {
        val primary = network("office-a")
        val fallback = network("office-b")
        val harness = Harness(
            initialNetworks = listOf(primary, fallback),
            initialWifi = listOf(observation(10, primary.ssid)),
        )
        harness.adapter.application = WifiProfileApplication(emptyList(), WifiReason.UPDATE_FAILED)

        harness.apply(listOf(primary, fallback))

        val markerOnly = requireNotNull(harness.store.state.lastApplied)
        assertTrue(markerOnly.networks.isEmpty())
        assertFalse(harness.store.state.managedWifi)
        assertEquals(133, markerOnly.unverifiedBoot(primary, 133))

        harness.connectivity.loseAll()
        harness.connectivity.update(11, fallback.ssid)
        harness.adapter.application = null
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        harness.adapter.afterSelect = { selected ->
            if (selected == primary) harness.connectivity.update(12, selected.ssid)
        }
        harness.apply(listOf(primary, fallback))

        assertEquals(listOf(primary, fallback), harness.store.state.lastApplied?.networks)
        assertEquals(primary.ssid, harness.connectivity.currentUsableSsid())
        assertEquals(WifiReason.VERIFY_AFTER_REBOOT, harness.store.state.latestResult?.reason)
    }

    @Test
    fun `unverified marker survives process restart while its candidate is omitted`() {
        val primary = network("office-a")
        val fallback = network("office-b")
        val marker = UnverifiedWifiCredential(133, wifiPasswordHash(primary.password))
        val compacted = WifiLocalState(lastApplied = WifiSavedProfile(
            REVISION, listOf(fallback),
            unverifiedOnBoot = mapOf(primary.identity to marker),
            primaryIdentity = primary.identity,
        ))
        val restored = WifiSettingsCodec.decode(WifiSettingsCodec.encode(compacted))
        val restarted = Harness(
            initialState = restored,
            initialNetworks = listOf(primary, fallback),
            initialWifi = listOf(observation(10, fallback.ssid)),
        )

        restarted.apply(listOf(primary, fallback))

        assertEquals(marker, restarted.store.state.lastApplied?.unverifiedOnBoot?.get(primary.identity))
        assertEquals(133, restarted.store.state.lastApplied?.unverifiedBoot(primary, 133))
    }

    @Test
    fun `when selection is needed old network handles do not count as a fresh connection`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val harness = Harness(
            initialState = WifiLocalState(
                lastApplied = WifiSavedProfile(OLD_REVISION, listOf(preferred, fallback)),
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
            initialState = WifiLocalState(lastApplied = saved),
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
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(REVISION, networks)),
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
        val profile = WifiSavedProfile(REVISION, networks, unverifiedOnBoot = mapOf(
            primary.identity to UnverifiedWifiCredential(133, wifiPasswordHash(primary.password)),
        ))
        val pending = WifiManualResult(REVISION, "failed", WifiReason.VERIFY_AFTER_REBOOT)
        val wifi = Harness(
            initialState = WifiLocalState(lastApplied = profile, latestResult = pending),
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
            initialState = WifiLocalState(lastApplied = saved),
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
    fun `Wi-Fi telemetry carries only readiness and monitor state after application`() {
        val preferred = network("office-a", password = "secret-a")
        val fallback = network("office-b", password = "secret-b")
        val harness = Harness()
        harness.adapter.afterSelect = { selected ->
            if (selected == fallback) harness.connectivity.update(22, selected.ssid)
        }
        harness.apply(listOf(preferred, fallback))
        harness.expireNext(2_000L)
        harness.expireNext(12_000L)

        val snapshot = harness.controller.stateSnapshot()
        assertEquals(setOf("busy", "available", "unavailable_reason", "priority_monitor"), snapshot.keySet())
        assertFalse(snapshot.getBoolean("busy"))
        assertTrue(snapshot.getBoolean("available"))
        assertTrue(snapshot.isNull("unavailable_reason"))
        assertEquals("off", snapshot.getString("priority_monitor"))
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
    fun `failed auto return restores the fallback and keeps result and backoff`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val profile = WifiSavedProfile(REVISION, listOf(preferred, fallback), autoReturn = true)
        val success = WifiManualResult(REVISION, "success", WifiReason.NONE)
        val preferredVisible = WifiPriorityScanEvent(WifiPriorityMonitorStatus.MONITORING, setOf(preferred.identity))
        val wifi = Harness(
            initialState = WifiLocalState(
                managedWifi = true,
                lastApplied = profile,
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
        assertEquals(success, wifi.store.state.latestResult)
    }

    @Test
    fun `auto return restores the prior candidate even at the overall deadline`() {
        val primary = network("office-a")
        val middle = network("office-b")
        val prior = network("office-c")
        val networks = listOf(primary, middle, prior)
        val wifi = Harness(
            initialState = WifiLocalState(
                lastApplied = WifiSavedProfile(REVISION, networks, autoReturn = true),
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
}
