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
    fun `a saved release wins on restart`() {
        val file = File(temporary.root, "configuration-packages.json")
        fun openStore() = ConfigurationPackageStore(file, syncDirectory = {})

        val first = openStore()
        assertEquals(ConfigurationPackageState(), first.read())
        first.save(ConfigurationPackageState(lastPolicy = "old policy", journal = "[]"))

        val release = JSONObject().put("package_id", JSONObject.NULL).put("revision", JSONObject.NULL)
            .put("settings", JSONObject()).toString()
        first.save(first.read().copy(lastPolicy = release))

        val restored = openStore().read()
        val assignment = JSONObject(requireNotNull(restored.lastPolicy))
        assertTrue(assignment.isNull("package_id"))
        assertEquals(0, assignment.getJSONObject("settings").length())
        assertEquals("[]", restored.journal)
    }

    @Test
    fun `a policy write preserves the journal when it was not loaded`() {
        val file = File(temporary.root, "configuration-packages.json")
        val original = ConfigurationPackageState("old policy", "saved journal")
        val store = ConfigurationPackageStore(file, syncDirectory = {})
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
        )

        assertThrows(IOException::class.java) {
            store.savePolicy("new policy", journal = null)
        }
        assertTrue(directoryAtFilePath.isDirectory)
    }
}
