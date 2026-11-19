package com.styly.mdmclient

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException

internal data class ConfigurationPackageState(val lastPolicy: String? = null, val journal: String? = null)

/** The cached assignment (including a release) and its journal share one durable record. */
internal class ConfigurationPackageStore(
    private val file: File,
    private val syncDirectory: (File) -> Unit,
    private val readLegacy: () -> ConfigurationPackageState,
    private val clearLegacy: () -> Unit,
) {
    fun read(): ConfigurationPackageState {
        val encoded = try {
            Files.readAllBytes(file.toPath()).toString(Charsets.UTF_8)
        } catch (_: NoSuchFileException) {
            return readLegacy()
        }
        val data = JSONObject(encoded)
        return ConfigurationPackageState(
            lastPolicy = data.get("last_policy").takeUnless { it === JSONObject.NULL } as String?,
            journal = data.get("journal").takeUnless { it === JSONObject.NULL } as String?,
        )
    }

    fun save(state: ConfigurationPackageState) {
        val encoded = JSONObject()
            .put("last_policy", state.lastPolicy ?: JSONObject.NULL)
            .put("journal", state.journal ?: JSONObject.NULL)
            .toString()
        writeAtomicStateFile(file, encoded, syncDirectory)
        // A legacy cache can be removed independently of the committed record.
        runCatching(clearLegacy)
    }
}
