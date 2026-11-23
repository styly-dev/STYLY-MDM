package com.styly.mdmclient

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException

internal data class ConfigurationPackageState(val lastPolicy: String? = null, val journal: String? = null)

/** The cached assignment (including a release) and its journal share one durable record. */
internal class ConfigurationPackageStore(
    private val file: File,
    private val syncDirectory: (File) -> Unit,
) {
    fun read(): ConfigurationPackageState {
        val encoded = try {
            Files.readAllBytes(file.toPath()).toString(Charsets.UTF_8)
        } catch (_: NoSuchFileException) {
            return ConfigurationPackageState()
        }
        val data = JSONObject(encoded)
        return ConfigurationPackageState(
            lastPolicy = data.savedString("last_policy"),
            journal = data.savedString("journal"),
        )
    }

    private fun JSONObject.savedString(key: String): String? = when (val value = get(key)) {
        JSONObject.NULL -> null
        is String -> value
        else -> throw JSONException("Invalid type for $key in configuration package state")
    }

    fun savePolicy(lastPolicy: String, journal: String?) {
        // If the journal could not be loaded, preserve the on-disk value or fail the write.
        save(ConfigurationPackageState(lastPolicy, journal ?: read().journal))
    }

    fun save(state: ConfigurationPackageState) {
        val encoded = JSONObject()
            .put("last_policy", state.lastPolicy ?: JSONObject.NULL)
            .put("journal", state.journal ?: JSONObject.NULL)
            .toString()
        writeAtomicStateFile(file, encoded, syncDirectory)
    }
}
