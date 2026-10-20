package com.styly.mdmclient

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal interface WifiLocalStore {
    fun read(): WifiLocalState
    fun write(state: WifiLocalState)
}

internal class WifiSettingsStore(
    private val file: File,
    private val syncDirectory: (File) -> Unit,
) : WifiLocalStore {
    @Synchronized
    override fun read(): WifiLocalState {
        if (!file.exists()) return WifiLocalState()
        return WifiSettingsCodec.decode(file.readText(Charsets.UTF_8))
    }

    @Synchronized
    override fun write(state: WifiLocalState) {
        val parent = file.parentFile ?: throw IllegalStateException("Wi-Fi state directory is unavailable")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("Wi-Fi state directory could not be created")
        }
        val temporary = File(parent, "${file.name}.tmp")
        val bytes = WifiSettingsCodec.encode(state).toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            syncDirectory(parent)
        } finally {
            temporary.delete()
        }
    }
}
