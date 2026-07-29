package com.styly.mdmclient

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Atomic persistence for active execution, terminal outbox, and dedupe receipts. */
class PushJobStore(context: Context) {
    companion object {
        private const val MAX_RECEIPTS = 256
    }

    private val directory = File(context.filesDir, "push-jobs")
    private val atomicFile = AtomicFile(File(directory, "state.json"))

    @Synchronized
    fun load(): PushProtocol.State {
        if (!atomicFile.baseFile.exists()) return emptyState()
        return try {
            val text = atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            PushProtocol.stateFromJson(JSONObject(text))
        } catch (_: Exception) {
            emptyState()
        }
    }

    @Synchronized
    fun save(state: PushProtocol.State) {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Failed to create push job state directory")
        }
        val stream = atomicFile.startWrite()
        try {
            val writer = stream.writer(Charsets.UTF_8)
            writer.write(PushProtocol.stateToJson(trim(state)).toString())
            writer.flush()
            stream.fd.sync()
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    fun emptyState() = PushProtocol.State(null, emptyList(), emptyList())

    private fun trim(state: PushProtocol.State): PushProtocol.State {
        val receipts = if (state.completedReceipts.size <= MAX_RECEIPTS) state.completedReceipts
        else state.completedReceipts.takeLast(MAX_RECEIPTS)
        return state.copy(completedReceipts = receipts)
    }
}
