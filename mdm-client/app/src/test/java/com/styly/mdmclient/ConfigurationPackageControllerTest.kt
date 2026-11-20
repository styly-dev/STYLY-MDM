package com.styly.mdmclient

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConfigurationPackageControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `a scalar writer exception ends the claim and permits Retry on the same boot`() {
        val packages = PackageHarness()
        val policy = packagePayload(listOf(network("office-a")), prioritizeNetworks = false)
        packages.scalarWrites.failNext = true

        packages.receive(policy)

        val storedAttempt = JSONArray(checkNotNull(packages.encodedJournal)).getJSONObject(0)
        assertEquals("interrupted", storedAttempt.getString("status"))
        assertEquals("interrupted", packages.controller.resultSnapshot()?.getString("status"))
        assertTrue(packages.wifi.adapter.applyCalls.isEmpty())

        packages.receive(JSONObject(policy.toString()).put("retry_id", RETRY_ID))
        packages.wifi.worker.runAll()
        packages.worker.runAll()

        assertEquals("complete", packages.attempt()?.status)
        assertEquals(setOf(RETRY_ID), packages.attempt()?.retryIds)
        assertEquals(2, packages.scalarWrites.count)
        assertEquals(1, packages.wifi.adapter.applyCalls.size)
    }

    @Test
    fun `an unhandled reapply failure settles and leaves the same package eligible for manual reapply`() {
        val packages = PackageHarness()
        packages.receive(scalarPackagePayload())
        packages.wifi.worker.runAll()
        packages.worker.runAll()
        val version = checkNotNull(packages.attempt()).version
        packages.scalarWrites.failNext = true
        var started = false

        packages.controller.reapply(version) { started = it }
        packages.worker.runAll()

        assertTrue(started)
        assertEquals("interrupted", packages.controller.resultSnapshot()?.getString("status"))
        assertTrue(checkNotNull(packages.controller.screenSnapshot()).canReapply)
        packages.controller.reapply(version) { started = it }
        packages.worker.runAll()
        assertTrue(started)
        assertEquals("complete", packages.attempt()?.status)
    }

    @Test
    fun `a same boot restart omits the report until restored and removal explicitly clears it`() {
        val previous = PackageHarness()
        previous.receive(scalarPackagePayload())
        previous.wifi.worker.runAll()
        previous.worker.runAll()
        val restarted = previous.restart(bootCount = 133, deferSdk = true)
        val updates = mutableListOf<Any?>()
        restarted.controller.addStateListener { updates.add(restarted.controller.reportValue()) }

        assertFalse(JSONObject().put("configuration_state", restarted.controller.reportValue())
            .has("configuration_state"))
        restarted.wifi.worker.runAll()
        restarted.worker.runAll()

        assertEquals("complete", (updates.single() as JSONObject).getString("status"))
        restarted.receive(noPackagePayload())
        restarted.wifi.worker.runAll()
        restarted.worker.runAll()

        assertEquals(2, updates.size)
        assertSame(JSONObject.NULL, updates.last())
        assertSame(JSONObject.NULL, JSONObject().put("configuration_state", restarted.controller.reportValue())
            .get("configuration_state"))
    }

    @Test
    fun `first authoritative removal can clear a report unknown to this process`() {
        val packages = PackageHarness(deferSdk = true)
        val updates = mutableListOf<Any?>()
        packages.controller.addStateListener { updates.add(packages.controller.reportValue()) }
        assertNull(packages.controller.reportValue())

        repeat(2) {
            packages.receive(noPackagePayload())
            packages.wifi.worker.runAll()
            packages.worker.runAll()
        }

        assertSame(JSONObject.NULL, updates.single())
        assertSame(JSONObject.NULL, packages.controller.reportValue())
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
    fun `all writes failing preserves the applied profile and the next Retry can verify the same credential`() {
        val primary = network("office-a")
        val previouslySaved = primary.copy(hidden = true)
        val priorProfile = WifiSavedProfile(OLD_REVISION, listOf(previouslySaved))
        val wifi = Harness(
            initialState = WifiLocalState(lastApplied = priorProfile),
            initialNetworks = listOf(previouslySaved),
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
    fun `Retry reloads Wi-Fi state after a transient local write failure`() {
        val primary = network("office-a")
        val wifi = Harness()
        val packages = PackageHarness(wifi)
        val policy = packagePayload(listOf(primary), prioritizeNetworks = false)
        wifi.store.writeFailuresRemaining = 1

        packages.receive(policy)
        wifi.worker.runAll()
        packages.worker.runAll()

        val failed = requireNotNull(packages.controller.resultSnapshot())
        assertEquals("interrupted", failed.getString("status"))
        assertEquals(
            "STORAGE_ERROR",
            failed.getJSONObject("results").getJSONObject("wifi").getString("reason"),
        )

        packages.receive(JSONObject(policy.toString()).put("retry_id", RETRY_ID))
        wifi.worker.runAll()
        packages.worker.runAll()

        val retried = requireNotNull(packages.controller.resultSnapshot())
        assertEquals("complete", retried.getString("status"))
        assertEquals("applied", retried.getJSONObject("results").getJSONObject("wifi").getString("status"))
        assertEquals(2, wifi.adapter.applyCalls.size)
        assertEquals(REVISION, wifi.store.state.lastApplied?.revision)
    }

    @Test
    fun `failed scalar result is retried across same boot package switches`() {
        val packages = PackageHarness(failedScalars = setOf("brightness"))
        val first = scalarPackagePayload()
        val second = JSONObject(first.toString())
            .put("package_id", PACKAGE_ID_2)
            .put("revision", REVISION_2)
        fun deliver(policy: JSONObject) {
            packages.receive(policy)
            packages.wifi.worker.runAll()
            packages.worker.runAll()
        }

        deliver(first)
        deliver(second)
        assertEquals(2, packages.scalarWrites.count)
        assertEquals("failed", packages.attempt()?.results?.getJSONObject("brightness")?.getString("status"))

        deliver(first)

        assertEquals(3, packages.scalarWrites.count)
        assertEquals(ConfigurationVersion(PACKAGE_ID, REVISION), packages.attempt()?.version)
        assertEquals("failed", packages.attempt()?.results?.getJSONObject("brightness")?.getString("status"))
    }

    @Test
    fun `initial journal reset and policy cache fail atomically`() {
        val previous = ConfigurationVersion(PACKAGE_ID_2, REVISION_2)
        val oldJournal = ConfigurationJournal()
            .withAttempt(ConfigurationAttempt(previous, 133, "complete"))
            .encode()
        val oldPolicy = noPackagePayload().toString()
        val packages = PackageHarness(encodedJournal = oldJournal, initialPolicy = oldPolicy)
        val policy = scalarPackagePayload()
        packages.policyWritesToFail = 1

        packages.receive(policy)

        assertEquals(oldPolicy, packages.persistedPolicy)
        assertEquals(oldJournal, packages.encodedJournal)
        assertEquals("STORAGE_ERROR", packages.controller.resultSnapshot()
            ?.getJSONObject("results")?.getJSONObject("brightness")?.getString("reason"))

        packages.receive(policy)
        packages.wifi.worker.runAll()
        packages.worker.runAll()
        assertNotNull(packages.persistedPolicy)
        assertNotNull(packages.encodedJournal)
        assertNull(ConfigurationJournal.restore(requireNotNull(packages.encodedJournal)).find(previous))
        assertEquals("complete", packages.attempt()?.status)
    }

    @Test
    fun `saving the first assignment clears its previous storage error while the SDK waits`() {
        val packages = PackageHarness(deferSdk = true)
        val policy = scalarPackagePayload()
        packages.policyWritesToFail = 1
        packages.receive(policy)
        assertEquals("STORAGE_ERROR", packages.controller.resultSnapshot()
            ?.getJSONObject("results")?.getJSONObject("brightness")?.getString("reason"))

        packages.receive(policy)
        packages.wifi.worker.runAll()
        packages.worker.runAll()

        assertSame(JSONObject.NULL, packages.controller.reportValue())
        assertEquals(ConfigurationApplyStatus.WAITING, packages.controller.screenSnapshot()?.applyStatus)
        assertEquals(0, packages.scalarWrites.count)

        packages.enableSdk()
        assertEquals("complete", packages.attempt()?.status)
        assertEquals(1, packages.scalarWrites.count)
    }

    @Test
    fun `unreadable journal is retried on delivery and Reapply`() {
        val receiving = PackageHarness(deferSdk = true, journalReadFailuresRemaining = 1)
        receiving.receive(scalarPackagePayload())
        receiving.wifi.worker.runAll()
        receiving.worker.runAll()
        assertEquals(0, receiving.scalarWrites.count)
        receiving.enableSdk()
        assertEquals("complete", receiving.attempt()?.status)
        assertEquals(1, receiving.scalarWrites.count)

        val seeded = PackageHarness()
        seeded.receive(scalarPackagePayload())
        val reapplying = PackageHarness(
            encodedJournal = seeded.encodedJournal,
            initialPolicy = seeded.persistedPolicy,
            deferSdk = true,
            journalReadFailuresRemaining = 2,
        )
        reapplying.controller.onSdkReady(true)
        reapplying.worker.runAll()
        reapplying.wifi.worker.runAll()
        reapplying.worker.runAll()
        assertTrue(requireNotNull(reapplying.controller.screenSnapshot()).canReapply)
        var started: Boolean? = null
        reapplying.controller.reapply(ConfigurationVersion(PACKAGE_ID, REVISION)) { started = it }
        reapplying.worker.runAll()
        assertEquals(true, started)
        assertEquals(1, reapplying.scalarWrites.count)
        assertEquals("complete", reapplying.attempt()?.status)
    }

    @Test
    fun `a delivery recovers a malformed file and a loaded journal prevents duplicate application`() {
        val file = temporary.newFile()
        val store = newPackageStore(file, "{")
        val packages = PackageHarness(packageStore = store)
        val policy = scalarPackagePayload()
        packages.policyWritesToFail = 1

        packages.receiveAndRunWorkers(policy)
        assertEquals("{", file.readText())
        assertEquals(0, packages.scalarWrites.count)

        packages.receiveAndRunWorkers(policy)
        assertEquals("complete", packages.attempt()?.status)
        assertEquals(1, packages.scalarWrites.count)

        file.writeText("{")
        packages.receiveAndRunWorkers(policy)
        val recovered = store.read()
        assertEquals(REVISION, JSONObject(requireNotNull(recovered.lastPolicy)).getString("revision"))
        assertEquals("complete", ConfigurationJournal.restore(requireNotNull(recovered.journal)).latest?.status)
        assertEquals(1, packages.scalarWrites.count)
    }

    @Test
    fun `a release replaces corrupt storage and survives an offline restart`() {
        val store = newPackageStore(temporary.newFile(), "{")
        val packages = PackageHarness(packageStore = store)

        packages.receiveAndRunWorkers(noPackagePayload())
        val release = store.read()
        assertTrue(JSONObject(requireNotNull(release.lastPolicy)).isNull("package_id"))
        assertEquals("[]", release.journal)

        val restarted = packages.restart()
        restarted.wifi.worker.runAll()
        restarted.worker.runAll()
        assertSame(JSONObject.NULL, restarted.controller.reportValue())
        assertEquals(0, restarted.scalarWrites.count)
    }

    @Test
    fun `a Retry with a corrupt inner journal applies the delivered policy once`() {
        val policy = scalarPackagePayload().put("boot_count", 133)
        val damaged = JSONObject().put("last_policy", policy.toString()).put("journal", "invalid").toString()
        val store = newPackageStore(temporary.newFile(), damaged)
        val packages = PackageHarness(packageStore = store, failedScalars = setOf("brightness"))
        packages.wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals(0, packages.scalarWrites.count)

        packages.receiveAndRunWorkers(policy.put("retry_id", RETRY_ID))
        packages.receiveAndRunWorkers(policy)
        assertEquals(1, packages.scalarWrites.count)
        val result = requireNotNull(ConfigurationJournal.restore(requireNotNull(store.read().journal)).latest)
        assertEquals("complete", result.status)
        assertEquals(setOf(RETRY_ID), result.retryIds)
        assertTrue(result.needsRetry)
    }

    @Test
    fun `false SDK readiness waits for settings then true readiness applies them`() {
        val packages = PackageHarness(deferSdk = true)
        packages.controller.onSdkReady(false)
        packages.worker.runAll()
        packages.receive(scalarPackagePayload())
        packages.wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals(0, packages.scalarWrites.count)

        packages.controller.onSdkReady(true) { false }
        packages.worker.runAll()
        assertEquals(0, packages.scalarWrites.count)

        packages.enableSdk()

        assertEquals(1, packages.scalarWrites.count)
        assertEquals("complete", packages.attempt()?.status)
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

            val result = requireNotNull(packages.controller.resultSnapshot())
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

        val result = requireNotNull(packages.controller.resultSnapshot())
            .getJSONObject("results").getJSONObject("wifi")
        assertEquals(listOf(fallback), wifi.adapter.selectCalls)
        assertEquals(fallback.ssid, wifi.connectivity.currentUsableSsid())
        assertEquals("failed", result.getString("status"))
        assertEquals("VERIFY_FAILED", result.getString("reason"))
    }

    @Test
    fun `new revision rechecks all scalar settings and keeps matching Wi-Fi connected`() {
        val preferred = network("office-a")
        val fallback = network("office-b")
        val wifi = Harness()
        wifi.adapter.afterSelect = { selected ->
            if (selected == preferred) wifi.connectivity.update(40, selected.ssid)
        }
        val packages = PackageHarness(wifi)
        val initial = packagePayload(listOf(preferred, fallback)).also {
            it.getJSONObject("settings").put("autoSleep", true).put("usbBoot", true)
        }
        packages.receive(initial)
        wifi.worker.runAll()
        wifi.expireNext(2_000L)
        packages.worker.runAll()
        val applyCalls = wifi.adapter.applyCalls.size
        val selectCalls = wifi.adapter.selectCalls.size
        val disconnectCalls = wifi.adapter.disconnectCalls

        packages.scalarWrites.currentValues["autoSleep"] = false
        val changed = JSONObject(initial.toString()).put("revision", REVISION_2)
            .also { it.getJSONObject("settings").put("brightness", 65) }
        packages.receive(changed)
        wifi.worker.runAll()
        packages.worker.runAll()

        assertEquals(applyCalls + 1, wifi.adapter.applyCalls.size)
        assertTrue(wifi.adapter.rewrittenNetworkCalls.last().isEmpty())
        assertEquals(selectCalls, wifi.adapter.selectCalls.size)
        assertEquals(disconnectCalls, wifi.adapter.disconnectCalls)
        assertEquals(preferred.ssid, wifi.connectivity.currentUsableSsid())
        assertEquals(setOf("brightness", "autoSleep", "usbBoot"), packages.scalarWrites.calls.last())
        assertEquals(setOf("brightness", "autoSleep"), packages.scalarWrites.mutations.last())
        assertEquals(2, packages.scalarWrites.count)
        assertTrue(packages.attempt()?.results?.getJSONObject("autoSleep")?.getBoolean("value") == true)
        assertTrue(packages.attempt()?.results?.getJSONObject("usbBoot")?.getBoolean("value") == true)
        assertEquals(REVISION_2, wifi.store.state.lastApplied?.revision)
        assertEquals("complete", packages.controller.resultSnapshot()?.getString("status"))
    }

    @Test
    fun `new Deploy retries unchanged Wi-Fi after permission recovery and its redelivery does not run again`() {
        val preferred = network("office-a")
        val wifi = Harness(initialWifi = listOf(observation(40, preferred.ssid)))
        val packages = PackageHarness(wifi)
        val initial = packagePayload(listOf(preferred))
        wifi.adapter.application = WifiProfileApplication(emptyList(), WifiReason.PERMISSION_DENIED)
        packages.receive(initial)
        wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals("PERMISSION_DENIED", packages.attempt()?.results?.getJSONObject("wifi")?.getString("reason"))

        wifi.adapter.application = null
        val newDeployment = JSONObject(initial.toString()).put("revision", REVISION_2)
        packages.receive(newDeployment)
        wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals(REVISION_2, packages.attempt()?.version?.revision)
        assertEquals("SAVED", packages.attempt()?.results?.getJSONObject("wifi")?.getString("reason"))
        assertEquals(2, wifi.adapter.applyCalls.size)
        assertEquals(2, packages.scalarWrites.count)
        assertEquals(emptySet<String>(), packages.scalarWrites.mutations.last())
        assertEquals(0, wifi.adapter.disconnectCalls)

        val journalWrites = packages.journalWriteCalls
        packages.receive(newDeployment)
        wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals(2, wifi.adapter.applyCalls.size)
        assertEquals(2, packages.scalarWrites.count)
        assertEquals(journalWrites, packages.journalWriteCalls)
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
        wifi.advanceBy(24_000L)
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
        val results = requireNotNull(packages.controller.resultSnapshot()).getJSONObject("results")
        assertEquals(65, results.getJSONObject("brightness").getInt("value"))
        assertEquals("applied", results.getJSONObject("wifi").getString("status"))
        assertEquals(applyCalls + 1, wifi.adapter.applyCalls.size)
        assertTrue(wifi.adapter.rewrittenNetworkCalls.last().isEmpty())
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

        assertTrue(JSONObject(requireNotNull(packages.persistedPolicy)).isNull("package_id"))
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
        assertEquals(listOf(false), boot.scalarWrites.overwriteModes)
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
    fun `boot reports Wi-Fi saved before association and does not change it on timeout or late connection`() {
        val primary = network("office-a")
        val boot = bootWithCachedPackage(packagePayload(listOf(primary)))
        val wifi = boot.wifi
        boot.enableSdk()
        wifi.worker.runAll()
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        boot.worker.runAll()

        val saved = requireNotNull(boot.attempt())
        assertEquals("applied", saved.results.getJSONObject("wifi").getString("status"))
        assertEquals("SAVED", saved.results.getJSONObject("wifi").getString("reason"))
        assertFalse(wifi.controller.canAcceptPolicy())
        assertTrue(boot.appliedNotices.single().bootReapply)

        wifi.expireNext(12_000L)
        wifi.connectivity.update(21, primary.ssid)
        wifi.worker.runAll()
        boot.worker.runAll()
        assertEquals(saved.report().toString(), boot.attempt()?.report().toString())
        assertEquals(1, boot.appliedNotices.size)

        val restarted = boot.restart(bootCount = 134)
        restarted.wifi.worker.runAll()
        restarted.worker.runAll()
        assertEquals("applied", restarted.attempt()?.results?.getJSONObject("wifi")?.getString("status"))
        assertEquals(1, restarted.wifi.adapter.applyCalls.size)
    }

    @Test
    fun `a scalar-only deployment rechecks saved Wi-Fi after selection times out`() {
        val networks = listOf(network("office-a"), network("office-b"))
        val packages = PackageHarness(Harness())
        val wifi = packages.wifi
        packages.receive(packagePayload(networks))
        wifi.worker.runAll()
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        repeat(2) { wifi.expireNext(12_000L) }
        packages.worker.runAll()
        assertEquals("SAVED", packages.attempt()?.results?.getJSONObject("wifi")?.getString("reason"))

        val update = packagePayload(networks, revision = REVISION_2)
        update.getJSONObject("settings").put("brightness", 65)
        packages.receive(update)
        wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals(REVISION_2, packages.attempt()?.version?.revision)
        assertEquals("SAVED", packages.attempt()?.results?.getJSONObject("wifi")?.getString("reason"))
        assertEquals(65, packages.attempt()?.results?.getJSONObject("brightness")?.getInt("value"))
        assertEquals(2, wifi.adapter.applyCalls.size)
        assertTrue(wifi.adapter.rewrittenNetworkCalls.last().isEmpty())
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
            null to "SAVED",
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
            assertEquals(reason, result?.getString("reason"))
        }
    }

    @Test
    fun `a late association cannot restore the report after No managed settings`() {
        val primary = network("office-a")
        val boot = bootWithCachedPackage(packagePayload(listOf(primary)))
        val wifi = boot.wifi
        boot.enableSdk()
        wifi.worker.runAll()
        wifi.expireNext(WIFI_PRIOR_CONNECTION_OBSERVATION_MS)
        wifi.expireNext(12_000L)
        boot.worker.runAll()
        wifi.worker.runAll()

        assertNotNull(boot.controller.resultSnapshot())
        val reported = mutableListOf<JSONObject?>()
        boot.controller.addStateListener { reported.add(boot.controller.resultSnapshot()) }
        boot.controller.receive(noPackagePayload(), isCurrent = { true })
        wifi.connectivity.update(20, primary.ssid)
        wifi.worker.runAll()
        boot.worker.runAll()
        wifi.worker.runAll()
        boot.worker.runAll()
        assertNull(boot.controller.resultSnapshot())
        assertEquals(listOf<JSONObject?>(null), reported)
        assertEquals("applied", boot.attempt()?.results?.getJSONObject("wifi")?.getString("status"))
        assertEquals(0, wifi.connectivity.openSessionCount)
    }

    @Test
    fun `No managed settings survives a reboot before or after the Wi-Fi worker records release`() {
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
            assertTrue(JSONObject(requireNotNull(after.persistedPolicy)).isNull("package_id"))
            assertEquals(preferred.ssid, after.wifi.connectivity.currentUsableSsid())
            assertEquals(1, after.wifi.adapter.applyCalls.size)
            assertEquals(0, after.scalarWrites.count)
            assertSame(JSONObject.NULL, after.controller.reportValue())
        }
    }

    @Test
    fun `an offline restart honors a saved release after a Wi-Fi state write failure`() {
        val preferred = network("office-a")
        val packages = PackageHarness()
        val wifi = packages.wifi
        packages.receive(packagePayload(listOf(preferred), prioritizeNetworks = false))
        wifi.worker.runAll()
        packages.worker.runAll()
        assertTrue(wifi.store.state.managedWifi)

        wifi.store.writeFailuresRemaining = 1
        packages.receive(noPackagePayload())
        wifi.worker.runAll()
        packages.worker.runAll()

        assertTrue(wifi.store.state.managedWifi)
        assertTrue(JSONObject(requireNotNull(packages.persistedPolicy)).isNull("package_id"))
        assertSame(JSONObject.NULL, packages.controller.reportValue())

        val restarted = packages.restart()
        restarted.wifi.worker.runAll()
        restarted.worker.runAll()

        assertFalse(restarted.wifi.store.state.managedWifi)
        assertEquals(1, restarted.wifi.adapter.applyCalls.size)
        assertEquals(0, restarted.scalarWrites.count)
        assertSame(JSONObject.NULL, restarted.controller.reportValue())
    }

    @Test
    fun `an unsaved release stops current Wi-Fi work and keeps the report until the release is durable`() {
        val preferred = network("office-a")
        val packages = PackageHarness()
        val wifi = packages.wifi
        packages.receive(packagePayload(listOf(preferred), prioritizeNetworks = false))
        wifi.worker.runAll()
        packages.worker.runAll()
        val cachedPackage = requireNotNull(packages.persistedPolicy)
        val report = requireNotNull(packages.controller.resultSnapshot())

        packages.policyWritesToFail = 2
        repeat(2) {
            packages.receive(noPackagePayload())
            wifi.worker.runAll()
            packages.worker.runAll()

            assertEquals(cachedPackage, packages.persistedPolicy)
            assertFalse(wifi.store.state.managedWifi)
            assertEquals(ConfigurationApplyStatus.RELEASE_UNSAVED, packages.controller.screenSnapshot()?.applyStatus)
            assertEquals(report.toString(), packages.controller.resultSnapshot()?.toString())
        }

        packages.receive(noPackagePayload())
        wifi.worker.runAll()
        packages.worker.runAll()
        assertTrue(JSONObject(requireNotNull(packages.persistedPolicy)).isNull("package_id"))
        assertNull(packages.controller.screenSnapshot()?.applyStatus)
        assertSame(JSONObject.NULL, packages.controller.reportValue())
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
    fun `saved Wi-Fi skips same boot Retry and is written again on the next OS boot`() {
        val primary = network("office-a")
        val wifi = Harness(initialNetworks = listOf(primary), initialWifi = listOf(observation(10, primary.ssid)))
        wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
        wifi.adapter.afterSelect = { wifi.connectivity.update(11, it.ssid) }
        val packages = PackageHarness(wifi)
        val payload = packagePayload(listOf(primary))
        packages.receive(payload)
        wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals("SAVED", packages.attempt()?.results?.getJSONObject("wifi")?.getString("reason"))

        val sameBoot = packages.restart(bootCount = 133)
        sameBoot.receive(JSONObject(payload.toString()).put("retry_id", RETRY_ID))
        sameBoot.wifi.worker.runAll()
        sameBoot.worker.runAll()
        assertEquals(0, sameBoot.scalarWrites.count)
        assertEquals(1, wifi.adapter.applyCalls.size)
        assertEquals(0, wifi.adapter.disconnectCalls)
        assertEquals("SAVED", sameBoot.attempt()?.results?.getJSONObject("wifi")?.getString("reason"))

        val nextBoot = sameBoot.restart(bootCount = 134)
        nextBoot.wifi.adapter.afterDisconnect = { nextBoot.wifi.connectivity.loseAll() }
        nextBoot.wifi.adapter.afterSelect = { nextBoot.wifi.connectivity.update(91, it.ssid) }
        nextBoot.wifi.worker.runAll()
        nextBoot.worker.runAll()
        assertEquals(2, wifi.adapter.applyCalls.size)
        assertEquals("applied", nextBoot.attempt()?.results?.getJSONObject("wifi")?.getString("status"))
    }

    @Test
    fun `switching to Android selection rewrites the auto join settings`() {
        val primary = network("office-a")
        val wifi = Harness(initialNetworks = listOf(primary), initialWifi = listOf(observation(10, primary.ssid)))
        wifi.adapter.afterDisconnect = { wifi.connectivity.loseAll() }
        wifi.adapter.afterSelect = { wifi.connectivity.update(11, it.ssid) }
        val packages = PackageHarness(wifi)
        packages.receive(packagePayload(listOf(primary)))
        wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals("SAVED", packages.attempt()?.results?.getJSONObject("wifi")?.getString("reason"))

        packages.receive(packagePayload(listOf(primary), revision = REVISION_2, prioritizeNetworks = false))
        wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals("SAVED", packages.attempt()?.results?.getJSONObject("wifi")?.getString("reason"))
        assertEquals(2, wifi.adapter.applyCalls.size)
        assertEquals(0, wifi.adapter.disconnectCalls)
    }

    @Test
    fun `Retry for another setting keeps saved Wi-Fi through a journal write failure`() {
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
        assertEquals("interrupted", packages.controller.resultSnapshot()?.getString("status"))
        assertEquals("SAVED", packages.controller.resultSnapshot()?.getJSONObject("results")
            ?.getJSONObject("wifi")?.getString("reason"))

        failedScalars.clear()
        packages.receive(JSONObject(payload.toString()).put("retry_id", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"))
        wifi.worker.runAll()
        packages.worker.runAll()
        val results = requireNotNull(packages.attempt()).results
        assertEquals(2, packages.scalarWrites.count)
        assertEquals("applied", results.getJSONObject("brightness").getString("status"))
        assertEquals("SAVED", results.getJSONObject("wifi").getString("reason"))
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
    fun `unreadable Wi-Fi state reports STORAGE_ERROR and Reapply retries it`() {
        val wifi = Harness(failStoreRead = true)
        val packages = PackageHarness(wifi)
        val version = ConfigurationVersion(PACKAGE_ID, REVISION)

        packages.receive(packagePayload(listOf(network("office-a")), prioritizeNetworks = false))
        wifi.worker.runAll()
        packages.worker.runAll()

        val attempt = requireNotNull(packages.controller.resultSnapshot())
        assertEquals("interrupted", attempt.getString("status"))
        val results = attempt.getJSONObject("results")
        assertEquals("applied", results.getJSONObject("brightness").getString("status"))
        assertEquals("STORAGE_ERROR", results.getJSONObject("wifi").getString("reason"))
        assertTrue(requireNotNull(packages.controller.screenSnapshot()).canReapply)

        var started: Boolean? = null
        packages.controller.reapply(version) { started = it }
        packages.worker.runAll()
        wifi.worker.runAll()
        packages.worker.runAll()
        assertEquals(true, started)
        assertEquals(2, packages.scalarWrites.count)
        assertEquals("interrupted", packages.attempt()?.status)
        assertEquals(
            "STORAGE_ERROR",
            packages.attempt()?.results?.getJSONObject("wifi")?.getString("reason"),
        )
        assertTrue(wifi.adapter.applyCalls.isEmpty())

        wifi.store.failRead = false
        started = null
        packages.controller.reapply(version) { started = it }
        packages.worker.runAll()
        wifi.worker.runAll()
        packages.worker.runAll()

        assertEquals(true, started)
        assertEquals(3, packages.scalarWrites.count)
        assertEquals("complete", packages.attempt()?.status)
        assertEquals("applied", packages.attempt()?.results?.getJSONObject("wifi")?.getString("status"))
        assertEquals(1, wifi.adapter.applyCalls.size)
    }

    @Test
    fun `journal claim and checkpoint failures report STORAGE_ERROR and recover on Retry`() {
        val preferred = network("office-a")
        val policy = packagePayload(listOf(preferred))

        for (failedWrite in 2..3) {
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
            assertEquals(if (failedWrite == 2) 0 else 1, packages.scalarWrites.count)
            val failed = requireNotNull(packages.controller.resultSnapshot())
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
        assertEquals(REVISION, packages.controller.resultSnapshot()?.optString("revision"))
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
    fun `Retry reapplies a returning assignment after its cache write failed`() {
        val packages = PackageHarness()
        val first = scalarPackagePayload()
        val second = JSONObject()
            .put("package_id", PACKAGE_ID_2)
            .put("revision", REVISION_2)
            .put("settings", JSONObject().put("brightness", 35))
        packages.scalarWrites.failNext = true
        packages.receiveAndRunWorkers(first)
        val oldRetryId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        val oldRetry = JSONObject(first.toString()).put("retry_id", oldRetryId)
        packages.receiveAndRunWorkers(oldRetry)
        assertEquals(60, packages.scalarWrites.currentValues["brightness"])
        packages.receiveAndRunWorkers(second)
        assertEquals(35, packages.scalarWrites.currentValues["brightness"])

        packages.policyWritesToFail = 1
        packages.receiveAndRunWorkers(first)
        assertEquals("interrupted", packages.controller.resultSnapshot()?.getString("status"))
        packages.receiveAndRunWorkers(oldRetry)
        assertEquals("interrupted", packages.controller.resultSnapshot()?.getString("status"))
        assertEquals(35, packages.scalarWrites.currentValues["brightness"])

        val retry = JSONObject(first.toString()).put("retry_id", RETRY_ID)
        packages.receiveAndRunWorkers(retry)
        assertEquals(60, packages.scalarWrites.currentValues["brightness"])
        assertEquals(ConfigurationVersion(PACKAGE_ID, REVISION), packages.attempt()?.version)
        assertEquals(setOf(oldRetryId, RETRY_ID), packages.attempt()?.retryIds)

        packages.receiveAndRunWorkers(retry)
        assertEquals(4, packages.scalarWrites.count)
    }

    @Test
    fun `returning to a saved package reuses it when the intervening policy never applied`() {
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
        // A repeated full snapshot must preserve the saved result.
        packages.receive(policyA)
        wifi.connectivity.update(101, networkA.ssid)
        wifi.worker.runAll()
        packages.worker.runAll()
        wifi.worker.runAll()
        packages.worker.runAll()

        assertEquals(1, wifi.adapter.applyCalls.size)
        assertEquals(ConfigurationVersion(PACKAGE_ID, REVISION), packages.journal().latest?.version)
        assertEquals("complete", packages.attempt()?.status)
    }

    private data class AppliedNotice(val bootReapply: Boolean, val isCurrent: () -> Boolean)

    private fun ConfigurationPackageController.resultSnapshot(): JSONObject? = reportValue() as? JSONObject

    private fun newPackageStore(file: File, encoded: String): ConfigurationPackageStore {
        file.writeText(encoded)
        return ConfigurationPackageStore(
            file,
            syncDirectory = {},
            readLegacy = { ConfigurationPackageState() },
            clearLegacy = {},
        )
    }

    private class PackageHarness(
        val wifi: Harness = Harness(),
        var encodedJournal: String? = null,
        initialPolicy: String? = null,
        private val bootCount: Int = 133,
        deferSdk: Boolean = false,
        failedScalars: Set<String> = emptySet(),
        var journalReadFailuresRemaining: Int = 0,
        private val packageStore: ConfigurationPackageStore? = null,
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
                readJournal = {
                    if (journalReadFailuresRemaining > 0) {
                        journalReadFailuresRemaining--
                        throw IllegalStateException("temporary journal read failure")
                    }
                    if (packageStore == null) encodedJournal else packageStore.read().journal
                },
                writeJournal = {
                    journalWriteCalls++
                    if (journalWriteCalls == journalWriteToFail) {
                        false
                    } else {
                        packageStore?.save(packageStore.read().copy(journal = it))
                        encodedJournal = it
                        true
                    }
                },
                readLastPolicy = {
                    if (packageStore == null) persistedPolicy else packageStore.read().lastPolicy
                },
                writePolicy = { lastPolicy, journal ->
                    val journalChanged = journal != null && journal != encodedJournal
                    if (journalChanged) journalWriteCalls++
                    val journalSaved = !journalChanged || journalWriteCalls != journalWriteToFail
                    val policySaved = if (policyWritesToFail > 0) {
                        policyWritesToFail--
                        false
                    } else true
                    if (journalSaved && policySaved) {
                        packageStore?.savePolicy(lastPolicy, journal)
                        persistedPolicy = lastPolicy
                        if (journal != null) encodedJournal = journal
                        true
                    } else {
                        false
                    }
                },
                scalarWriter = { { values, overwrite -> scalarWrites.apply(values, overwrite) } },
                onApplied = { bootReapply, isCurrent -> appliedNotices += AppliedNotice(bootReapply, isCurrent) },
            ),
            wifi.controller,
        )

        init { if (!deferSdk) enableSdk() }

        fun enableSdk() { controller.onSdkReady(true); worker.runAll() }

        fun receive(payload: JSONObject) {
            controller.receive(payload, supportsApply = true, isCurrent = { true })
            worker.runAll()
        }

        fun receiveAndRunWorkers(payload: JSONObject) {
            receive(payload)
            wifi.worker.runAll()
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
                packageStore = packageStore,
            )
        }

        fun attempt(): ConfigurationAttempt? = encodedJournal?.let(ConfigurationJournal::restore)?.latest

        fun journal(): ConfigurationJournal = encodedJournal?.let(ConfigurationJournal::restore)
            ?: ConfigurationJournal()
    }

    private class ScalarWrites(val failedKeys: Set<String> = emptySet()) {
        var count = 0
            private set
        var failNext = false
        val calls = mutableListOf<Set<String>>()
        val overwriteModes = mutableListOf<Boolean>()
        val currentValues = mutableMapOf<String, Any>()
        val mutations = mutableListOf<Set<String>>()

        fun apply(values: JSONObject, overwrite: Boolean): JSONObject {
            count++
            if (failNext) {
                failNext = false
                throw IllegalStateException("scalar writer failed during application")
            }
            val keys = values.keys().asSequence().toSet()
            calls.add(keys)
            overwriteModes.add(overwrite)
            val changed = mutableSetOf<String>()
            mutations.add(changed)
            return JSONObject().apply {
                for (key in keys) {
                    val outcome = if (key in failedKeys) settingOutcome("failed", reason = "UPDATE_FAILED")
                    else {
                        val requested = values.get(key)
                        if (overwrite || currentValues[key] != requested) {
                            currentValues[key] = requested
                            changed.add(key)
                        }
                        settingOutcome("applied", currentValues.getValue(key))
                    }
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
