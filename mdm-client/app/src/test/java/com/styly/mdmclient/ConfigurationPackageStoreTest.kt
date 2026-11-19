package com.styly.mdmclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ConfigurationPackageStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `a saved release wins on restart even while old preferences cannot be removed`() {
        val file = File(temporary.root, "configuration-packages.json")
        val legacy = ConfigurationPackageState(lastPolicy = "old policy", journal = "[]")
        var legacyReads = 0
        var failedCleanups = 0
        fun openStore() = ConfigurationPackageStore(file, syncDirectory = {}, readLegacy = {
            legacyReads++
            legacy
        }, clearLegacy = {
            failedCleanups++
            throw IOException("Legacy preferences could not be saved")
        })

        val first = openStore()
        assertEquals(legacy, first.read())
        first.save(legacy)

        val release = JSONObject().put("package_id", JSONObject.NULL).put("revision", JSONObject.NULL)
            .put("settings", JSONObject()).toString()
        first.save(first.read().copy(lastPolicy = release))

        val restored = openStore().read()
        val assignment = JSONObject(requireNotNull(restored.lastPolicy))
        assertTrue(assignment.isNull("package_id"))
        assertEquals(0, assignment.getJSONObject("settings").length())
        assertEquals(legacy.journal, restored.journal)
        assertEquals(2, failedCleanups)
        assertEquals(1, legacyReads)
    }

    @Test
    fun `a policy write preserves the journal when it was not loaded`() {
        val file = File(temporary.root, "configuration-packages.json")
        val original = ConfigurationPackageState("old policy", "saved journal")
        val store = ConfigurationPackageStore(file, syncDirectory = {}, readLegacy = { original }, clearLegacy = {})
        store.save(original)

        store.savePolicy("new policy", journal = null)

        assertEquals(ConfigurationPackageState("new policy", "saved journal"), store.read())
    }

    @Test
    fun `a filesystem read failure does not replace the saved package state`() {
        val directoryAtFilePath = File(temporary.root, "configuration-packages.json")
        assertTrue(directoryAtFilePath.mkdir())
        val store = ConfigurationPackageStore(
            directoryAtFilePath,
            syncDirectory = {},
            readLegacy = { ConfigurationPackageState("legacy policy", "legacy journal") },
            clearLegacy = {},
        )

        assertThrows(IOException::class.java) {
            store.savePolicy("new policy", journal = null)
        }
        assertTrue(directoryAtFilePath.isDirectory)
    }
}
