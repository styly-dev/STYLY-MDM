package com.styly.mdmclient

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
