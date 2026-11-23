package com.styly.mdmclient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiSettingsControllerTest {
    @Test
    fun `fresh deployment reads an unchanged saved policy and leaves selection untouched`() {
        val primary = network("office-a")
        val profile = WifiSavedProfile(OLD_REVISION, listOf(primary), networkIds = mapOf(primary.identity to 0))
        val harness = Harness(
            initialState = WifiLocalState(managedWifi = true, lastApplied = profile),
            initialNetworks = listOf(primary),
        )

        harness.apply(listOf(primary), reuseMatchingProfile = true)

        assertEquals(listOf(listOf(primary)), harness.adapter.applyCalls)
        assertEquals(listOf(mapOf(primary to 0)), harness.adapter.trustedNetworkCalls)
        assertEquals(listOf(emptySet<Int>()), harness.adapter.rewrittenNetworkCalls)
        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertEquals(0, harness.adapter.disconnectCalls)
        assertEquals(REVISION, harness.store.state.lastApplied?.revision)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
    }

    @Test
    fun `fresh deployment repairs auto join drift without rewriting or reconnecting`() {
        val primary = network("office-a")
        val profile = WifiSavedProfile(OLD_REVISION, listOf(primary), networkIds = mapOf(primary.identity to 0))
        val harness = Harness(
            initialState = WifiLocalState(managedWifi = true, lastApplied = profile),
            initialNetworks = listOf(primary),
            initialWifi = listOf(observation(10, primary.ssid)),
        )
        harness.adapter.setAutoJoin(primary, false)

        harness.apply(listOf(primary), reuseMatchingProfile = true)

        assertEquals(listOf(true), harness.adapter.currentAutoJoin)
        assertEquals(listOf(emptySet<Int>()), harness.adapter.rewrittenNetworkCalls)
        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertEquals(0, harness.adapter.disconnectCalls)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
    }

    @Test
    fun `fresh deploy preserves an unchanged active fallback when another backup credential changes`() {
        val primary = network("office-a")
        val fallback = network("office-b")
        val backup = network("office-c")
        val previous = WifiSavedProfile(OLD_REVISION, listOf(primary, fallback, backup),
            networkIds = mapOf(primary.identity to 0, fallback.identity to 1, backup.identity to 2))
        val updatedBackup = backup.copy(password = "new-backup-password")
        val harness = Harness(
            initialState = WifiLocalState(managedWifi = true, lastApplied = previous),
            initialNetworks = previous.networks,
            initialWifi = listOf(observation(10, fallback.ssid)),
        )

        harness.apply(listOf(primary, fallback, updatedBackup), reuseMatchingProfile = true)

        assertEquals(listOf(setOf(2)), harness.adapter.rewrittenNetworkCalls)
        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertEquals(0, harness.adapter.disconnectCalls)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
        assertEquals("new-backup-password", harness.store.state.lastApplied?.networks?.get(2)?.password)
    }

    @Test
    fun `fresh deployment restores the credential of a replaced saved network`() {
        val preferred = network("office-a")
        val harness = Harness(initialNetworks = listOf(preferred))
        harness.apply(listOf(preferred), prioritizeNetworks = false)
        val savedId = harness.adapter.savedNetworkId(preferred)
        assertEquals(savedId, harness.store.state.lastApplied?.networkIds?.get(preferred.identity))

        harness.adapter.replaceSavedNetwork(preferred.copy(password = "other-password"))
        val replacementId = harness.adapter.savedNetworkId(preferred)
        assertTrue(replacementId != savedId)
        harness.apply(listOf(preferred), reuseMatchingProfile = true, prioritizeNetworks = false)

        assertEquals(listOf(preferred), harness.adapter.currentProfiles)
        assertEquals(replacementId, harness.store.state.lastApplied?.networkIds?.get(preferred.identity))
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
        assertEquals(0, harness.adapter.disconnectCalls)
    }

    @Test
    fun `an interrupted credential change invalidates the previous password evidence`() {
        val original = network("office-a", password = "original-password")
        val changed = original.copy(password = "changed-password")
        val harness = Harness(initialNetworks = listOf(original))
        harness.apply(listOf(original), prioritizeNetworks = false)

        harness.adapter.afterApply = {
            harness.store.writeFailuresRemaining = 1
            harness.adapter.afterApply = null
        }
        harness.apply(listOf(changed), reuseMatchingProfile = true, prioritizeNetworks = false)
        assertEquals(listOf(changed), harness.adapter.currentProfiles)
        assertTrue(requireNotNull(harness.store.state.lastApplied).networkIds.isEmpty())

        harness.apply(listOf(original), reuseMatchingProfile = true, prioritizeNetworks = false)
        assertEquals(listOf(original), harness.adapter.currentProfiles)
        assertEquals(setOf(0), harness.adapter.rewrittenNetworkCalls.last())
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
    }

    @Test
    fun `a failed credential ID invalidation prevents the password write`() {
        val original = network("office-a", password = "original-password")
        val changed = original.copy(password = "changed-password")
        val profile = WifiSavedProfile(
            OLD_REVISION,
            listOf(original),
            prioritizeNetworks = false,
            networkIds = mapOf(original.identity to 0),
        )
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = profile),
            initialNetworks = listOf(original),
        )
        harness.store.writeFailuresRemaining = 1

        harness.apply(listOf(changed), reuseMatchingProfile = true, prioritizeNetworks = false)

        assertTrue(harness.adapter.applyCalls.isEmpty())
        assertEquals(listOf(original), harness.adapter.currentProfiles)
        assertEquals("unknown", harness.results.lastOrNull()?.status)
        assertEquals(WifiReason.STORAGE_ERROR, harness.results.lastOrNull()?.reason)
    }

    @Test
    fun `released Wi-Fi policy does not authorize trusted credential reuse on redeploy`() {
        val primary = network("office-a")
        val previous = WifiSavedProfile(OLD_REVISION, listOf(primary), prioritizeNetworks = false,
            networkIds = mapOf(primary.identity to 0))
        val harness = Harness(
            initialState = WifiLocalState(managedWifi = false, lastApplied = previous),
            initialNetworks = listOf(primary),
        )

        harness.apply(listOf(primary), reuseMatchingProfile = true, prioritizeNetworks = false)

        assertEquals(listOf(emptyMap<WifiNetworkProfile, Int>()), harness.adapter.trustedNetworkCalls)
        assertEquals(listOf(setOf(0)), harness.adapter.rewrittenNetworkCalls)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
    }

    @Test
    fun `fresh deployment reuses an unchanged open profile without local credential history`() {
        val open = WifiNetworkProfile("open-office", "OPEN", "", hidden = false)
        val harness = Harness(initialNetworks = listOf(open))

        harness.apply(listOf(open), reuseMatchingProfile = true, prioritizeNetworks = false)

        assertEquals(listOf(emptySet<Int>()), harness.adapter.rewrittenNetworkCalls)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
    }

    @Test
    fun `Wi-Fi release retries a state read that initially failed`() {
        val primary = network("office-a")
        val fallback = network("office-b")
        val profile = WifiSavedProfile(REVISION, listOf(primary, fallback), autoReturn = true)
        val harness = Harness(
            initialState = WifiLocalState(managedWifi = true, lastApplied = profile),
            initialNetworks = listOf(primary, fallback),
            initialWifi = listOf(observation(10, fallback.ssid)),
        )
        assertEquals(1, harness.priorityScanSource.startCalls)
        assertEquals(1, harness.connectivity.openSessionCount)

        harness.store.failRead = true
        var firstRelease: Boolean? = null
        harness.controller.stopManagingPolicy { firstRelease = it }
        harness.worker.runAll()

        assertEquals(false, firstRelease)
        assertEquals(1, harness.priorityScanSource.stopCalls)
        assertEquals(0, harness.connectivity.openSessionCount)
        assertEquals("off", harness.controller.stateSnapshot().getString("priority_monitor"))

        harness.controller.refreshReadiness()
        harness.worker.runAll()
        assertEquals(1, harness.priorityScanSource.startCalls)
        assertEquals(0, harness.connectivity.openSessionCount)

        harness.store.failRead = false
        var retryRelease: Boolean? = null
        harness.controller.stopManagingPolicy { retryRelease = it }
        harness.worker.runAll()

        assertEquals(true, retryRelease)
        assertFalse(harness.store.state.managedWifi)
        harness.adapter.onDisconnect = { harness.connectivity.loseAll() }
        harness.adapter.afterSelect = { selected ->
            if (selected == primary) harness.connectivity.update(11, primary.ssid)
        }
        harness.apply(listOf(primary, fallback), autoReturn = true, reuseMatchingProfile = true)
        assertTrue(harness.store.state.managedWifi)
        assertEquals(primary.ssid, harness.connectivity.currentUsableSsid())
        harness.connectivity.update(11, fallback.ssid)
        harness.worker.runAll()
        assertEquals(2, harness.priorityScanSource.startCalls)
        assertEquals(1, harness.connectivity.openSessionCount)
    }

    @Test
    fun `Wi-Fi release stops an active selection when saving release fails`() {
        val primary = network("office-a")
        val fallback = network("office-b")
        val profile = WifiSavedProfile(REVISION, listOf(primary, fallback), autoReturn = true)
        val harness = Harness(
            initialState = WifiLocalState(managedWifi = true, lastApplied = profile),
            initialWifi = listOf(observation(10, fallback.ssid)),
            initialNetworks = listOf(primary, fallback),
        )
        harness.priorityScanSource.emit(WifiPriorityScanEvent(
            WifiPriorityMonitorStatus.MONITORING,
            setOf(primary.identity),
        ))
        harness.worker.runNext()
        harness.store.writeFailuresRemaining = 1

        var releaseRecorded: Boolean? = null
        harness.controller.stopManagingPolicy { releaseRecorded = it }
        harness.worker.runAll()

        assertEquals(false, releaseRecorded)
        assertEquals(1, harness.adapter.disconnectCalls)
        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertEquals(0, harness.connectivity.openSessionCount)
        assertEquals("off", harness.controller.stateSnapshot().getString("priority_monitor"))

        harness.expireNext(12_000L)
        harness.controller.refreshReadiness()
        harness.worker.runAll()
        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertEquals(1, harness.priorityScanSource.startCalls)
        assertEquals(0, harness.connectivity.openSessionCount)
    }

    @Test
    fun `a later deployment retries a Wi-Fi state read that initially failed`() {
        val harness = Harness(failStoreRead = true)
        val requested = network("office-a")
        var firstResult: WifiManualResult? = null
        harness.controller.applyManagedPolicy(
            REVISION, settings(listOf(requested), prioritizeNetworks = false),
            reuseMatchingProfile = false, isCurrent = { true }, onFinished = { firstResult = it },
        )
        harness.worker.runAll()

        assertEquals(WifiReason.STORAGE_ERROR, firstResult?.reason)
        assertTrue(harness.controller.canAcceptPolicy())

        harness.store.failRead = false
        var retryResult: WifiManualResult? = null
        harness.controller.applyManagedPolicy(
            OLD_REVISION, settings(listOf(requested), prioritizeNetworks = false),
            reuseMatchingProfile = false, isCurrent = { true }, onFinished = { retryResult = it },
        )
        harness.worker.runAll()

        assertEquals("success", retryResult?.status)
        assertEquals(OLD_REVISION, harness.store.state.lastApplied?.revision)
    }

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
        assertEquals("success", harness.results.lastOrNull()?.status)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
    }

    @Test
    fun `selection accepts only connections received before its deadline while the worker is blocked`() {
        val preferred = network("office-a")
        for (offset in listOf(-1L, 0L, 1L)) {
            val harness = Harness()
            harness.adapter.afterSelect = { selected ->
                harness.clock.value += 12_000L + offset
                harness.connectivity.update(21, selected.ssid)
                harness.clock.value += 2
            }

            harness.apply(listOf(preferred))
            harness.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
            harness.expireNext(12_000L)

            val result = requireNotNull(harness.results.lastOrNull())
            assertEquals(WifiReason.SAVED, result.reason)
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

            val result = requireNotNull(harness.results.lastOrNull())
            assertEquals(WifiReason.SAVED, result.reason)
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

            val result = requireNotNull(harness.results.lastOrNull())
            assertEquals(WifiReason.SAVED, result.reason)
        }
    }

    @Test
    fun `a same-handle reassociation during a slow save does not inherit the old connection exception`() {
        val preferred = network("office-a")
        val oldProfile = WifiSavedProfile(OLD_REVISION, listOf(preferred))
        for (offset in listOf(-1L, 0L)) {
            val harness = Harness(
                initialState = WifiLocalState(managedWifi = true, lastApplied = oldProfile),
                initialNetworks = listOf(preferred),
                initialWifi = listOf(observation(10, preferred.ssid)),
            )
            harness.adapter.afterApply = {
                harness.clock.value += WIFI_MANUAL_TIMEOUT_MS + offset
                harness.connectivity.lose(10)
                harness.connectivity.update(10, preferred.ssid)
                harness.clock.value += 2
            }

            harness.apply(listOf(preferred))

            val result = requireNotNull(harness.results.lastOrNull())
            assertEquals(WifiReason.SAVED, result.reason)
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
    fun `priority monitoring uses one observer and release closes it`() {
        val profile = WifiSavedProfile(REVISION, listOf(network("office-a"), network("office-b")))
        val wifi = Harness(initialState = WifiLocalState(lastApplied = profile))
        wifi.worker.runAll()

        assertEquals(1, wifi.connectivity.observeCalls)
        assertEquals(1, wifi.connectivity.openSessionCount)
        wifi.connectivity.update(10, "office-b")
        wifi.worker.runAll()
        assertEquals(1, wifi.connectivity.observeCalls)

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
        assertEquals(WifiReason.UPDATE_FAILED, wifi.results.lastOrNull()?.reason)
        wifi.expireNext(8_000L)
        assertTrue(wifi.adapter.selectCalls.isEmpty())
        assertEquals(0, wifi.adapter.disconnectCalls)
    }

    @Test
    fun `switching from Android selection to priority reapplies the managed profile`() {
        val primary = network("office-a")
        val wifi = Harness(initialNetworks = listOf(primary), initialWifi = listOf(observation(10, primary.ssid)))
        wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
        wifi.adapter.afterSelect = { wifi.connectivity.update(11, it.ssid) }
        wifi.apply(listOf(primary), prioritizeNetworks = false)
        assertEquals(WifiReason.SAVED, wifi.results.lastOrNull()?.reason)

        wifi.apply(listOf(primary), reuseMatchingProfile = true)
        assertEquals(2, wifi.adapter.applyCalls.size)
        assertEquals(WifiReason.SAVED, wifi.results.lastOrNull()?.reason)
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
        assertEquals("success", wifi.results.lastOrNull()?.status)
        assertEquals(WifiReason.SAVED, wifi.results.lastOrNull()?.reason)
    }

    @Test
    fun `a newly managed or changed primary keeps its usable connection after save`() {
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
            assertEquals("success", wifi.results.lastOrNull()?.status)
            assertEquals(WifiReason.SAVED, wifi.results.lastOrNull()?.reason)
            assertEquals(primary.ssid, wifi.connectivity.currentUsableSsid())
        }
    }

    @Test
    fun `a save that drops the link can reconnect to the primary`() {
        val primary = network("office-a")
        val fallback = network("office-b")
        val wifi = Harness(initialNetworks = listOf(primary), initialWifi = listOf(observation(10, primary.ssid)))
        wifi.adapter.afterApply = { wifi.connectivity.loseAll() }
        wifi.adapter.afterSelect = { wifi.connectivity.update(11, it.ssid) }

        wifi.apply(listOf(primary, fallback))

        assertEquals(0, wifi.adapter.disconnectCalls)
        assertEquals(listOf(primary), wifi.adapter.selectCalls)
        assertEquals(primary.ssid, wifi.connectivity.currentUsableSsid())
        assertEquals(WifiReason.SAVED, wifi.results.lastOrNull()?.reason)
    }

    @Test
    fun `a prior open connection does not block a protected candidate`() {
        val secured = network("office-a")
        val priorOpen = WifiNetworkProfile(secured.ssid, "OPEN", "", hidden = false)
        val harness = Harness(
            initialNetworks = listOf(priorOpen),
            initialWifi = listOf(observation(10, priorOpen.ssid).copy(security = "OPEN")),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        harness.adapter.afterSelect = { selected -> harness.connectivity.update(11, selected.ssid) }

        harness.apply(listOf(secured))

        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
    }

    @Test
    fun `a prior connection with unknown security still saves its changed password`() {
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

        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
        assertEquals("new-password", harness.store.state.lastApplied?.networks?.single()?.password)
    }

    @Test
    fun `automatic return does not change the saved deployment result`() {
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
        val pending = requireNotNull(harness.results.lastOrNull())
        assertEquals(WifiReason.SAVED, pending.reason)
        harness.connectivity.loseAll()
        harness.worker.runAll()
        harness.expireNext(8_000L)
        harness.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        harness.expireNext(12_000L)
        assertEquals(backup.ssid, harness.connectivity.currentUsableSsid())
        assertEquals(listOf(changed, backup), harness.adapter.selectCalls)
        assertEquals(pending, harness.results.lastOrNull())

        val observeCalls = harness.connectivity.observeCalls
        harness.adapter.afterSelect = { harness.connectivity.update(++handle, it.ssid) }
        harness.priorityScanSource.emit(WifiPriorityScanEvent(
            WifiPriorityMonitorStatus.MONITORING, setOf(changed.identity), "fresh_scan_results",
        ))
        harness.worker.runAll()
        assertEquals(listOf(changed, backup, changed), harness.adapter.selectCalls)
        assertEquals(changed.ssid, harness.connectivity.currentUsableSsid())
        assertEquals(pending, harness.results.lastOrNull())
        assertEquals(observeCalls, harness.connectivity.observeCalls)
    }

    @Test
    fun `reapply must not accept the same cached association`() {
        val old = network("office-a", password = "old-password")
        val changed = network("office-a", password = "new-password")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old))),
            initialNetworks = listOf(old),
            initialWifi = listOf(observation(10, old.ssid)),
        )
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        var handle = 10L
        harness.adapter.afterSelect = { selected ->
            harness.connectivity.update(++handle, selected.ssid)
        }

        harness.apply(listOf(changed))
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
        assertEquals(listOf(changed), harness.adapter.currentProfiles)

        harness.apply(listOf(changed))
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)

        val saved = WifiSettingsCodec.decode(WifiSettingsCodec.encode(harness.store.state))
        val restarted = Harness(
            initialState = saved,
            initialNetworks = listOf(changed),
            initialWifi = listOf(observation(10, changed.ssid)),
        )
        restarted.adapter.afterDisconnect = { restarted.connectivity.loseAll() }
        restarted.adapter.afterSelect = { restarted.connectivity.update(11, it.ssid) }

        restarted.apply(listOf(changed))
        assertEquals(WifiReason.SAVED, restarted.results.lastOrNull()?.reason)
    }

    @Test
    fun `changing an inactive password preserves the ID after the new credential is saved`() {
        val old = network("first-choice", password = "old-password")
        val changed = old.copy(password = "new-password")
        val fallback = network("backup")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old, fallback))),
            initialNetworks = listOf(old, fallback),
            initialWifi = listOf(observation(10, fallback.ssid)),
        )
        val savedId = harness.adapter.savedNetworkId(old)
        harness.adapter.afterDisconnect = { harness.connectivity.loseAll() }
        harness.adapter.afterSelect = { harness.connectivity.update(11, it.ssid) }

        harness.apply(listOf(changed, fallback))

        assertEquals(listOf(changed, fallback), harness.adapter.currentProfiles)
        assertEquals(savedId, harness.adapter.savedNetworkId(changed))
        assertEquals(changed.ssid, harness.connectivity.currentUsableSsid())
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
    }

    @Test
    fun `a failed save preserves the currently usable fallback connection`() {
        val old = network("first-choice", password = "old-password")
        val primary = old.copy(password = "new-password")
        val fallback = network("backup")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old, fallback))),
            initialNetworks = listOf(old, fallback),
            initialWifi = listOf(observation(10, fallback.ssid)),
        )
        val savedId = harness.adapter.savedNetworkId(old)
        harness.adapter.application = WifiProfileApplication(listOf(1), WifiReason.UPDATE_FAILED)

        harness.apply(listOf(primary, fallback))

        assertEquals(listOf(old, fallback), harness.adapter.currentProfiles)
        assertEquals(savedId, harness.adapter.savedNetworkId(old))
        assertEquals(WifiReason.UPDATE_FAILED, harness.results.lastOrNull()?.reason)
        assertEquals(fallback.ssid, harness.connectivity.currentUsableSsid())
        assertEquals(0, harness.adapter.disconnectCalls)
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
        assertEquals("success", harness.results.lastOrNull()?.status)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
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

        assertEquals(WifiReason.UPDATE_FAILED, harness.results.lastOrNull()?.reason)
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
        assertEquals("failed", harness.results.lastOrNull()?.status)
        assertEquals(WifiReason.UPDATE_FAILED, harness.results.lastOrNull()?.reason)

        val persisted = WifiSettingsCodec.decode(WifiSettingsCodec.encode(harness.store.state))
        val restarted = Harness(initialState = persisted, sharedAdapter = harness.adapter)
        restarted.adapter.afterSelect = { selected -> restarted.connectivity.update(23, selected.ssid) }
        restarted.expireNext(8_000L)
        restarted.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)

        assertEquals(listOf(fallback, fallback), restarted.adapter.selectCalls)
        assertEquals(fallback.ssid, restarted.connectivity.currentUsableSsid())
        assertTrue(restarted.results.isEmpty())
    }

    @Test
    fun `partial password writes discard an untrusted prior network ID before retry`() {
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
        assertFalse(partial.networkIds.containsKey(primary.identity))
        assertEquals(fallback.ssid, harness.connectivity.currentUsableSsid())

        harness.adapter.application = null
        harness.adapter.afterSelect = { selected ->
            if (selected == primary) harness.connectivity.update(12, selected.ssid)
        }
        harness.apply(listOf(primary, fallback))

        val retried = requireNotNull(harness.store.state.lastApplied)
        assertEquals(listOf(primary, fallback), retried.networks)
        assertTrue(retried.networkIds.containsKey(primary.identity))
        assertEquals(primary.ssid, harness.connectivity.currentUsableSsid())
        assertEquals("success", harness.results.lastOrNull()?.status)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
    }

    @Test
    fun `a failed first profile write leaves no empty managed profile`() {
        val primary = network("office-a")
        val fallback = network("office-b")
        val harness = Harness(
            initialNetworks = listOf(primary, fallback),
            initialWifi = listOf(observation(10, primary.ssid)),
        )
        harness.adapter.application = WifiProfileApplication(emptyList(), WifiReason.UPDATE_FAILED)

        harness.apply(listOf(primary, fallback))

        assertNull(harness.store.state.lastApplied)
        assertEquals(WifiReason.UPDATE_FAILED, harness.results.lastOrNull()?.reason)

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
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
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
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)

        harness.connectivity.update(11, preferred.ssid)
        harness.worker.runAll()

        assertEquals("success", harness.results.lastOrNull()?.status)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
        assertEquals(listOf(preferred), harness.adapter.selectCalls)
    }

    @Test
    fun `a failed disconnect after the old association is gone keeps the current candidate`() {
        val old = network("old-office")
        val preferred = network("office-a")
        val fallback = network("office-b")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old))),
            initialWifi = listOf(observation(10, old.ssid)),
        )
        harness.adapter.disconnectResult = WifiReason.UNREACHABLE
        harness.adapter.onDisconnect = { harness.connectivity.loseAll() }
        harness.adapter.afterSelect = { if (it == preferred) harness.connectivity.update(11, preferred.ssid) }

        harness.apply(listOf(preferred, fallback))

        assertEquals(listOf(preferred), harness.adapter.selectCalls)
        assertEquals("success", harness.results.lastOrNull()?.status)
        assertEquals(preferred.ssid, harness.connectivity.currentUsableSsid())
    }

    @Test
    fun `a new association arriving during disconnect is also cleared before selection`() {
        val old = network("old-office")
        val preferred = network("office-a")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old))),
            initialWifi = listOf(observation(10, old.ssid)),
        )
        harness.adapter.onDisconnect = {
            if (harness.adapter.disconnectCalls == 1) harness.connectivity.update(11, "unrelated-office")
        }

        harness.apply(listOf(preferred))
        assertTrue(harness.adapter.selectCalls.isEmpty())

        harness.connectivity.lose(10)
        harness.worker.runAll()
        assertTrue(harness.adapter.selectCalls.isEmpty())

        harness.adapter.afterSelect = { harness.connectivity.update(12, preferred.ssid) }
        harness.connectivity.lose(11)
        harness.worker.runAll()

        assertEquals(listOf(preferred), harness.adapter.selectCalls)
        assertEquals("success", harness.results.lastOrNull()?.status)
    }

    @Test
    fun `each unrelated association that appears during disconnect is cleared before selection`() {
        val old = network("old-office")
        val preferred = network("office-a")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old))),
            initialWifi = listOf(observation(10, old.ssid)),
        )
        harness.adapter.onDisconnect = {
            when (harness.adapter.disconnectCalls) {
                1 -> {
                    harness.connectivity.update(11, "unrelated-one")
                    harness.connectivity.lose(10)
                }
                2 -> {
                    harness.connectivity.lose(11)
                    harness.connectivity.update(12, "unrelated-two")
                }
                3 -> harness.connectivity.lose(12)
            }
        }
        harness.adapter.afterSelect = { selected ->
            if (selected == preferred) harness.connectivity.update(13, selected.ssid)
        }

        harness.apply(listOf(preferred))

        assertEquals(3, harness.adapter.disconnectCalls)
        assertEquals(listOf(preferred), harness.adapter.selectCalls)
        assertEquals("success", harness.results.lastOrNull()?.status)
        assertEquals(preferred.ssid, harness.connectivity.currentUsableSsid())
    }

    @Test
    fun `an unrelated association after candidate selection queues disconnect reconciliation`() {
        val old = network("old-office")
        val preferred = network("office-a")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old))),
            initialWifi = listOf(observation(10, old.ssid)),
        )
        harness.adapter.onDisconnect = { harness.connectivity.loseAll() }
        harness.adapter.afterSelect = { selected ->
            if (harness.adapter.selectCalls.size == 1) {
                harness.connectivity.update(11, selected.ssid)
                harness.connectivity.update(12, "unrelated-office")
            } else {
                harness.connectivity.update(13, selected.ssid)
            }
        }
        harness.controller.applyManagedPolicy(
            REVISION,
            settings(listOf(preferred)),
            reuseMatchingProfile = false,
            isCurrent = { true },
            onFinished = {},
        )

        harness.worker.runNext() // Accept the policy and queue attempt preparation.
        harness.worker.runNext() // The first selection observes a candidate and an unrelated AP.

        assertEquals(listOf(preferred), harness.adapter.selectCalls)
        assertEquals(1, harness.adapter.disconnectCalls)
        harness.worker.runAll()

        assertEquals(listOf(preferred, preferred), harness.adapter.selectCalls)
        assertEquals(2, harness.adapter.disconnectCalls)
        assertTrue(harness.results.isEmpty())
    }

    @Test
    fun `a matching association awaiting disconnect does not change the saved result`() {
        val old = network("old-office")
        val preferred = network("office-a")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old))),
            initialWifi = listOf(observation(10, old.ssid)),
        )
        var result: WifiManualResult? = null
        harness.adapter.onDisconnect = {
            when (harness.adapter.disconnectCalls) {
                1 -> harness.connectivity.loseAll()
                2 -> harness.connectivity.lose(12) // Keep the candidate association marked for disconnect.
            }
        }
        harness.adapter.afterSelect = { selected ->
            if (harness.adapter.selectCalls.size == 1) {
                harness.connectivity.update(11, selected.ssid)
                harness.connectivity.update(12, "unrelated-office")
            }
        }
        harness.controller.applyManagedPolicy(
            REVISION,
            settings(listOf(preferred)),
            reuseMatchingProfile = false,
            isCurrent = { true },
            onFinished = { result = it },
        )
        harness.worker.runAll()

        assertEquals(WifiReason.SAVED, result?.reason)
        assertEquals(2, harness.adapter.disconnectCalls)
        harness.expireNext(12_000L)

        assertEquals(WifiReason.SAVED, result?.reason)
        assertTrue(harness.results.isEmpty())
    }

    @Test
    fun `a stale update queued during the disconnect snapshot cannot trigger another disconnect`() {
        val old = network("old-office")
        val preferred = network("office-a")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old))),
            initialWifi = listOf(observation(10, old.ssid)),
        )
        harness.adapter.afterApply = {
            harness.connectivity.update(11, "unrelated-office")
            harness.connectivity.lose(11)
        }

        harness.apply(listOf(preferred))

        assertEquals(1, harness.adapter.disconnectCalls)
        assertTrue(harness.adapter.selectCalls.isEmpty())
    }

    @Test
    fun `a preferred connection reobserved on the same handle during disconnect is adopted`() {
        val old = network("old-office")
        val preferred = network("office-a")
        val fallback = network("office-b")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old))),
            initialWifi = listOf(observation(10, old.ssid)),
        )
        harness.adapter.onDisconnect = {
            harness.connectivity.lose(10)
            harness.connectivity.update(10, preferred.ssid)
        }

        harness.apply(listOf(preferred, fallback))

        assertEquals("success", harness.results.lastOrNull()?.status)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertEquals(1, harness.adapter.disconnectCalls)
    }

    @Test
    fun `a rejected disconnect with its old link still present is bounded by the candidate window`() {
        val old = network("old-office")
        val preferred = network("office-a")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old))),
            initialWifi = listOf(observation(10, old.ssid)),
        )
        harness.adapter.disconnectResult = WifiReason.UNREACHABLE

        harness.apply(listOf(preferred))
        assertTrue(harness.adapter.selectCalls.isEmpty())

        harness.expireNext(12_000L)

        assertEquals("success", harness.results.lastOrNull()?.status)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
        assertTrue(harness.adapter.selectCalls.isEmpty())
    }

    @Test
    fun `an associated candidate without an IP advances to fallback at its candidate deadline`() {
        val old = network("old-office")
        val preferred = network("office-a")
        val fallback = network("office-b")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old))),
            initialWifi = listOf(observation(10, old.ssid)),
        )
        harness.adapter.onDisconnect = {
            when (harness.adapter.disconnectCalls) {
                1 -> {
                    harness.connectivity.update(11, preferred.ssid, hasUsableIp = false)
                    harness.connectivity.lose(10)
                }
                2 -> harness.connectivity.lose(11)
            }
        }
        harness.adapter.afterSelect = { selected ->
            if (selected == fallback) harness.connectivity.update(12, fallback.ssid)
        }

        harness.apply(listOf(preferred, fallback))
        assertTrue(harness.adapter.selectCalls.isEmpty())

        harness.expireNext(12_000L)

        assertEquals(listOf(fallback), harness.adapter.selectCalls)
        assertEquals("success", harness.results.lastOrNull()?.status)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
    }

    @Test
    fun `candidate IP received before its deadline is accepted after worker delay`() {
        val old = network("old-office")
        val preferred = network("office-a")
        val fallback = network("office-b")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old))),
            initialWifi = listOf(observation(10, old.ssid)),
        )
        harness.adapter.onDisconnect = {
            harness.connectivity.update(11, preferred.ssid, hasUsableIp = false)
            harness.connectivity.lose(10)
        }

        harness.apply(listOf(preferred, fallback))
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)

        harness.clock.value = 11_999L
        harness.connectivity.update(11, preferred.ssid, hasUsableIp = true)
        harness.clock.value = 12_000L
        harness.worker.runAll()

        assertEquals("success", harness.results.lastOrNull()?.status)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
        assertTrue(harness.adapter.selectCalls.isEmpty())
    }

    @Test
    fun `an IP observed in time is accepted when the disconnect worker resumes after the overall deadline`() {
        val old = network("old-office")
        val preferred = network("office-a")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old))),
            initialWifi = listOf(observation(10, old.ssid)),
        )
        harness.adapter.onDisconnect = {
            harness.clock.value = 11_999L
            harness.connectivity.update(11, preferred.ssid)
            harness.connectivity.lose(10)
            harness.clock.value = WIFI_MANUAL_TIMEOUT_MS
        }

        harness.apply(listOf(preferred))

        assertEquals("success", harness.results.lastOrNull()?.status)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
        assertTrue(harness.adapter.selectCalls.isEmpty())
    }

    @Test
    fun `an IP observed at the overall deadline is rejected when the disconnect worker resumes`() {
        val old = network("old-office")
        val preferred = network("office-a")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old))),
            initialWifi = listOf(observation(10, old.ssid)),
        )
        harness.adapter.onDisconnect = {
            harness.connectivity.update(11, preferred.ssid, hasUsableIp = false)
            harness.connectivity.lose(10)
            harness.clock.value = WIFI_MANUAL_TIMEOUT_MS
            harness.connectivity.update(11, preferred.ssid)
        }

        harness.apply(listOf(preferred))

        assertEquals("success", harness.results.lastOrNull()?.status)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
        assertTrue(harness.adapter.selectCalls.isEmpty())
    }

    @Test
    fun `a linkage error during candidate selection completes once and a later policy can run`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val harness = Harness()
        harness.adapter.selectionFailure = NoSuchMethodError("SDK method unavailable")
        var completions = 0
        var firstResult: WifiManualResult? = null
        harness.controller.applyManagedPolicy(
            REVISION,
            settings(listOf(preferred)),
            reuseMatchingProfile = false,
            isCurrent = { true },
            onFinished = { completions++; firstResult = it },
        )
        harness.worker.runAll()
        harness.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)

        assertEquals(1, completions)
        assertEquals(WifiReason.SAVED, firstResult?.reason)
        assertEquals("success", firstResult?.status)
        assertTrue(harness.controller.canAcceptPolicy())

        harness.adapter.afterSelect = { selected ->
            if (selected == fallback) harness.connectivity.update(22, fallback.ssid)
        }
        harness.apply(listOf(fallback))
        harness.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        assertEquals("reason=${harness.results.lastOrNull()?.reason}, selections=${harness.adapter.selectCalls}",
            "success", harness.results.lastOrNull()?.status)
        assertEquals(fallback.ssid, harness.connectivity.currentUsableSsid())
    }

    @Test
    fun `a monitoring failure after completion keeps the saved result and releases the worker`() {
        val harness = Harness()
        val preferred = network("office-a")
        val fallback = network("office-b")
        val results = mutableListOf<WifiManualResult>()
        harness.adapter.afterSelect = {
            harness.connectivity.update(70, it.ssid)
            harness.adapter.onCheckWifiEnabled = {
                harness.adapter.onCheckWifiEnabled = null
                throw IllegalStateException("Monitoring failed after the attempt finished")
            }
        }
        harness.controller.applyManagedPolicy(REVISION, settings(listOf(preferred, fallback)), false, { true }, results::add)
        harness.worker.runAll()
        harness.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)

        assertNull(harness.adapter.onCheckWifiEnabled)
        assertEquals("success", results.single().status)
        assertTrue(harness.results.isEmpty())
        assertTrue(harness.controller.canAcceptPolicy())
    }

    @Test
    fun `a disconnect exception completes once and does not block the next policy`() {
        val old = network("old-office")
        val preferred = network("office-a")
        val fallback = network("office-b")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(old))),
            initialWifi = listOf(observation(10, old.ssid)),
        )
        harness.adapter.disconnectFailure = IllegalStateException("disconnect failed")
        var completions = 0
        var firstResult: WifiManualResult? = null
        harness.controller.applyManagedPolicy(
            REVISION,
            settings(listOf(preferred)),
            reuseMatchingProfile = false,
            isCurrent = { true },
            onFinished = { completions++; firstResult = it },
        )
        harness.worker.runAll()

        assertEquals(1, completions)
        assertEquals(WifiReason.SAVED, firstResult?.reason)
        assertTrue(harness.controller.canAcceptPolicy())

        harness.adapter.onDisconnect = { harness.connectivity.loseAll() }
        harness.adapter.afterSelect = { selected ->
            if (selected == fallback) harness.connectivity.update(22, fallback.ssid)
        }
        harness.apply(listOf(fallback))
        assertEquals("success", harness.results.lastOrNull()?.status)
        assertEquals(fallback.ssid, harness.connectivity.currentUsableSsid())
    }

    @Test
    fun `Wi-Fi disabled before candidate selection does not schedule an empty candidate window`() {
        val preferred = network("office-a")
        val harness = Harness()
        harness.adapter.wifiEnabled = false

        harness.apply(listOf(preferred))
        val scheduledCandidateTimers = harness.scheduledDelayCount(12_000L)
        harness.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)

        assertEquals(scheduledCandidateTimers, harness.scheduledDelayCount(12_000L))
        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
    }

    @Test
    fun `a rejected disconnect still allows a delayed loss to start the first candidate`() {
        val old = network("previous")
        val preferred = network("office-a")
        val fallback = network("office-b")
        val harness = Harness(initialWifi = listOf(observation(10, old.ssid)))
        harness.adapter.disconnectResult = WifiReason.UNREACHABLE
        harness.adapter.afterSelect = { if (it == preferred) harness.connectivity.update(11, preferred.ssid) }

        harness.apply(listOf(preferred, fallback))
        assertTrue(harness.adapter.selectCalls.isEmpty())
        harness.connectivity.lose(10)
        harness.worker.runAll()

        assertEquals(listOf(preferred), harness.adapter.selectCalls)
        assertEquals("success", harness.results.lastOrNull()?.status)
    }

    @Test
    fun `an automatic connection to the current candidate during disconnect is accepted`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(fallback))),
            initialWifi = listOf(observation(10, fallback.ssid)),
        )

        harness.apply(listOf(preferred, fallback))
        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertEquals(1, harness.adapter.disconnectCalls)

        harness.connectivity.update(11, null, hasUsableIp = true, security = null)
        harness.worker.runAll()
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
        assertEquals(1, harness.adapter.disconnectCalls)
        assertTrue(harness.adapter.selectCalls.isEmpty())

        harness.connectivity.update(11, preferred.ssid, hasUsableIp = false)
        harness.worker.runAll()
        assertEquals(1, harness.adapter.disconnectCalls)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)

        harness.connectivity.lose(10)
        harness.worker.runAll()
        assertTrue(harness.adapter.selectCalls.isEmpty())

        harness.connectivity.update(11, preferred.ssid)
        harness.worker.runAll()

        assertEquals("success", harness.results.lastOrNull()?.status)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertEquals(1, harness.adapter.disconnectCalls)
    }

    @Test
    fun `a candidate connection arriving after its request expires is not adopted`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        var isCurrent = true
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(fallback))),
            initialWifi = listOf(observation(10, fallback.ssid)),
        )
        var disconnectMutations = 0
        harness.adapter.onDisconnect = { disconnectMutations++ }
        harness.controller.applyManagedPolicy(
            REVISION,
            settings(listOf(preferred, fallback)),
            reuseMatchingProfile = false,
            isCurrent = { isCurrent },
            onFinished = harness.results::add,
        )
        harness.worker.runAll()
        assertEquals(1, harness.adapter.disconnectCalls)

        isCurrent = false
        harness.connectivity.update(11, preferred.ssid)
        harness.worker.runAll()

        assertEquals(listOf(WifiReason.SAVED), harness.results.map { it.reason })
        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertEquals(1, harness.adapter.disconnectCalls)
        assertEquals(1, disconnectMutations)
    }

    @Test
    fun `a candidate update queued before the deadline is not adopted after it expires`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val harness = Harness(
            initialState = WifiLocalState(lastApplied = WifiSavedProfile(OLD_REVISION, listOf(fallback))),
            initialWifi = listOf(observation(10, fallback.ssid)),
        )
        var disconnectMutations = 0
        harness.adapter.onDisconnect = { disconnectMutations++ }
        harness.apply(listOf(preferred, fallback))

        harness.connectivity.update(11, preferred.ssid)
        harness.clock.value = WIFI_MANUAL_TIMEOUT_MS
        harness.worker.runAll()

        assertEquals("success", harness.results.lastOrNull()?.status)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertEquals(1, disconnectMutations)
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

        assertEquals("success", harness.results.lastOrNull()?.status)
        assertEquals(WifiReason.SAVED, harness.results.lastOrNull()?.reason)
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
        assertTrue(wifi.results.isEmpty())
        assertEquals(0, wifi.priorityScanSource.startCalls)
    }

    @Test
    fun `offline retry can rejoin the primary after all candidates fail`() {
        val primary = network("office-a")
        val fallback = network("office-b")
        val networks = listOf(primary, fallback)
        val profile = WifiSavedProfile(REVISION, networks)
        val wifi = Harness(
            initialState = WifiLocalState(lastApplied = profile),
            initialNetworks = networks,
        )

        wifi.expireNext(8_000L)
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        repeat(2) { wifi.expireNext(12_000L) }
        assertEquals(listOf(primary, fallback), wifi.adapter.selectCalls)
        assertTrue(wifi.results.isEmpty())

        wifi.adapter.afterSelect = { wifi.connectivity.update(34, it.ssid) }
        wifi.expireNext(16_000L)
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)

        assertEquals(listOf(primary, fallback, primary), wifi.adapter.selectCalls)
        assertEquals(primary.ssid, wifi.connectivity.currentUsableSsid())
        assertTrue(wifi.results.isEmpty())
    }

    @Test
    fun `Wi-Fi off delays reconnect without selecting until Wi-Fi returns`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val profile = WifiSavedProfile(REVISION, listOf(preferred, fallback))
        val adapter = FakeAdapter(listOf(preferred, fallback)).apply { wifiEnabled = false }
        val wifi = Harness(
            initialState = WifiLocalState(managedWifi = true, lastApplied = profile),
            sharedAdapter = adapter,
        )

        wifi.expireNext(8_000L)
        assertTrue(adapter.selectCalls.isEmpty())
        assertEquals(0, adapter.disconnectCalls)

        adapter.wifiEnabled = true
        wifi.expireNext(8_000L)
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)

        assertEquals(listOf(preferred), adapter.selectCalls)
    }

    @Test
    fun `Wi-Fi off stops priority scanning and monitoring resumes after Wi-Fi returns`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val profile = WifiSavedProfile(REVISION, listOf(preferred, fallback), autoReturn = true)
        val adapter = FakeAdapter(listOf(preferred, fallback)).apply { wifiEnabled = false }
        val wifi = Harness(
            initialState = WifiLocalState(managedWifi = true, lastApplied = profile),
            initialWifi = listOf(observation(10, fallback.ssid)),
            sharedAdapter = adapter,
        )
        assertEquals(0, wifi.priorityScanSource.startCalls)

        wifi.expireNext(8_000L)
        assertEquals(0, wifi.priorityScanSource.startCalls)
        assertTrue(adapter.selectCalls.isEmpty())
        assertEquals(0, adapter.disconnectCalls)

        adapter.wifiEnabled = true
        wifi.expireNext(8_000L)

        assertEquals(1, wifi.priorityScanSource.startCalls)
        assertTrue(adapter.selectCalls.isEmpty())
    }

    @Test
    fun `profile write and readback can succeed without a connection observer`() {
        val requested = network("office-b")
        val harness = Harness(preflightFailure = WifiReason.PERMISSION_DENIED)
        val results = mutableListOf<WifiManualResult>()

        harness.controller.applyManagedPolicy(REVISION, settings(listOf(requested)), false, { true }, results::add)
        harness.worker.runAll()

        assertEquals(listOf(requested), harness.adapter.currentProfiles)
        assertTrue(harness.adapter.selectCalls.isEmpty())
        assertEquals(WifiManualResult(REVISION, "success", WifiReason.SAVED), results.single())
    }

    @Test
    fun `invalid candidate count is rejected before changing Wi-Fi`() {
        val harness = Harness()
        val candidates = (1..WIFI_MAX_NETWORKS + 1).map { network("office-$it") }

        harness.apply(candidates)

        assertEquals(WifiReason.UNSUPPORTED_NETWORK_LIMIT, harness.results.lastOrNull()?.reason)
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
    fun `saved profiles report success while actual write errors remain visible`() {
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
            assertEquals(if (saveFailure == null) "success" else "failed", wifi.results.lastOrNull()?.status)
            assertEquals(saveFailure ?: WifiReason.SAVED, wifi.results.lastOrNull()?.reason)
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
        assertTrue(wifi.results.isEmpty())
        assertEquals(listOf(preferred, fallback), wifi.adapter.selectCalls)
        assertEquals(fallback.ssid, wifi.connectivity.currentUsableSsid())
        assertEquals(1, wifi.priorityScanSource.stopCalls)

        wifi.clock.value += 5 * 60_000L - WIFI_PRIOR_CONNECTION_OBSERVATION_MS - 1
        wifi.priorityScanSource.emit(preferredVisible)
        wifi.worker.runAll()
        assertEquals(2, wifi.adapter.selectCalls.size)

        wifi.clock.value++
        wifi.expireNext(5 * 60_000L - WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        assertEquals(2, wifi.priorityScanSource.startCalls)
        wifi.priorityScanSource.emit(preferredVisible)
        wifi.worker.runAll()
        assertEquals(3, wifi.adapter.selectCalls.size)
        wifi.expireNext(12_000L)
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        assertTrue(wifi.results.isEmpty())
        assertEquals(listOf(preferred, fallback, preferred, fallback), wifi.adapter.selectCalls)
        assertEquals(fallback.ssid, wifi.connectivity.currentUsableSsid())

        wifi.clock.value += 10 * 60_000L - WIFI_PRIOR_CONNECTION_OBSERVATION_MS - 1
        wifi.priorityScanSource.emit(preferredVisible)
        wifi.worker.runAll()
        assertEquals(4, wifi.adapter.selectCalls.size)

        wifi.adapter.afterSelect = { selected -> wifi.connectivity.update(++handle, selected.ssid) }
        wifi.clock.value++
        wifi.expireNext(10 * 60_000L - WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        assertEquals(3, wifi.priorityScanSource.startCalls)
        wifi.priorityScanSource.emit(preferredVisible)
        wifi.worker.runAll()

        assertEquals(listOf(preferred, fallback, preferred, fallback, preferred), wifi.adapter.selectCalls)
        assertEquals(preferred.ssid, wifi.connectivity.currentUsableSsid())
        assertTrue(wifi.results.isEmpty())
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
