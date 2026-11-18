package com.styly.mdmclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationPackageControllerTest {
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
    fun `all writes failing preserves the applied profile and the next Retry can verify the same credential`() {
        val primary = network("office-a")
        val priorProfile = WifiSavedProfile(OLD_REVISION, listOf(primary))
        val wifi = Harness(
            initialState = WifiLocalState(lastApplied = priorProfile),
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
        assertEquals(OLD_REVISION, wifi.store.state.lastApplied?.revision)
        assertEquals(listOf(true), wifi.adapter.currentAutoJoin)
        assertEquals(0, wifi.adapter.disconnectCalls)
        assertEquals("UPDATE_FAILED", packages.attempt()?.results?.getJSONObject("wifi")?.getString("reason"))

        wifi.adapter.application = null
        wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
        wifi.adapter.afterSelect = { wifi.connectivity.update(11, it.ssid) }
        packages.receive(JSONObject(payload.toString()).put("retry_id", RETRY_ID))
        wifi.worker.runAll()
        packages.worker.runAll()

        assertEquals("applied", packages.attempt()?.results?.getJSONObject("wifi")?.getString("status"))
        assertEquals(WifiReason.NONE, wifi.store.state.latestResult?.reason)
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
            failure = WifiReason.VERIFY_FAILED,
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
        assertEquals(REVISION_2, wifi.store.state.lastApplied?.revision)
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
        assertTrue(wifi.controller.canAcceptPolicy())
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
            WifiProfileApplication(listOf(0, 1), failure = WifiReason.VERIFY_FAILED),
        )
        for (application in failures) {
            val previous = WifiSavedProfile(OLD_REVISION, networks)
            val initialWifi = Harness(initialState = WifiLocalState(lastApplied = previous))
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
            WifiProfileApplication(listOf(0, 1), failure = WifiReason.VERIFY_FAILED) to "VERIFY_FAILED",
        )
        for ((application, reason) in applications) {
            val initialWifi = Harness(
                initialState = WifiLocalState(
                    lastApplied = WifiSavedProfile(OLD_REVISION, networks),
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
        sameBoot.receive(JSONObject(payload.toString()).put("retry_id", RETRY_ID))
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
        packages.receive(JSONObject(payload.toString()).put("retry_id", RETRY_ID))
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
    fun `unreadable Wi-Fi state reports STORAGE_ERROR while applying other settings and blocks local Reapply`() {
        val wifi = Harness(failStoreRead = true)
        val packages = PackageHarness(wifi)
        val version = ConfigurationVersion(PACKAGE_ID, REVISION)

        packages.receive(packagePayload(listOf(network("office-a"))))
        wifi.worker.runAll()
        packages.worker.runAll()

        val attempt = requireNotNull(packages.controller.snapshot())
        assertEquals("interrupted", attempt.getString("status"))
        val results = attempt.getJSONObject("results")
        assertEquals("applied", results.getJSONObject("brightness").getString("status"))
        assertEquals("STORAGE_ERROR", results.getJSONObject("wifi").getString("reason"))
        assertFalse(requireNotNull(packages.controller.screenSnapshot()).canReapply)

        var started: Boolean? = null
        packages.controller.reapply(version) { started = it }
        packages.worker.runAll()
        assertEquals(false, started)
        assertEquals(1, packages.scalarWrites.count)
        assertTrue(wifi.adapter.applyCalls.isEmpty())
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
            assertTrue(packages.wifi.controller.canAcceptPolicy())

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
    fun `an uncached removal cannot clear a newer assignment when its release finishes`() {
        val preferred = network("office-a")
        val packages = PackageHarness(failedScalars = setOf("brightness"))
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

    @Test
    fun `Retry proceeds when assignment caching failed before any journal attempt`() {
        val packages = PackageHarness(failedScalars = setOf("brightness"))
        val previous = scalarPackagePayload()
        packages.receive(previous)
        packages.wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals("complete", packages.attempt()?.status)

        val policy = packagePayload(listOf(network("office-a")), packageId = PACKAGE_ID_2, revision = REVISION_2)
        packages.policyWritesToFail = 1

        packages.receive(policy)
        assertNull(packages.journal().find(ConfigurationVersion(PACKAGE_ID_2, REVISION_2)))

        packages.wifi.adapter.afterSelect = { selected ->
            packages.wifi.connectivity.update(103, selected.ssid)
        }
        packages.receive(JSONObject(policy.toString()).put("retry_id", RETRY_ID))
        packages.wifi.worker.runAll()
        packages.wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        packages.worker.runAll()

        assertEquals("complete", packages.attempt()?.status)
        assertEquals("applied", packages.attempt()?.results?.getJSONObject("wifi")?.getString("status"))
        assertTrue(checkNotNull(packages.attempt()).retryIds.contains(RETRY_ID))
    }

    @Test
    fun `returning to a package fences stale applying attempt and starts a fresh apply`() {
        val networkA = network("office-a")
        val networkB = network("office-b")
        val policyA = packagePayload(listOf(networkA))
        val policyB = packagePayload(listOf(networkB), packageId = PACKAGE_ID_2, revision = REVISION_2)
        val wifi = Harness()
        val packages = PackageHarness(wifi)

        packages.receive(policyA)
        wifi.worker.runAll()
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        assertEquals(listOf(listOf(networkA)), wifi.adapter.applyCalls)
        assertTrue(!wifi.controller.canAcceptPolicy())

        packages.receive(policyB)
        packages.receive(policyA)
        // A repeated full snapshot must keep the forced same-boot reapply attached
        // to the pending A assignment.
        packages.receive(policyA)
        wifi.connectivity.update(101, networkA.ssid)
        wifi.worker.runAll()
        packages.worker.runAll()
        wifi.worker.runAll()
        packages.worker.runAll()

        assertEquals(2, wifi.adapter.applyCalls.size)
        assertEquals(ConfigurationVersion(PACKAGE_ID, REVISION), packages.journal().latest?.version)
        assertEquals("complete", packages.attempt()?.status)
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

    companion object {
        private const val RETRY_ID = "11111111-1111-4111-8111-111111111111"
        private const val REVISION_2 = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        private const val PACKAGE_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        private const val PACKAGE_ID_2 = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

        private fun bootWithCachedPackage(
            policy: JSONObject,
            initialWifi: Harness = Harness(),
            failedScalars: Set<String> = emptySet(),
        ): PackageHarness {
            val before = PackageHarness(initialWifi, deferSdk = true, failedScalars = failedScalars)
            before.receive(policy)
            return before.restart(bootCount = 134, deferSdk = true)
        }

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
