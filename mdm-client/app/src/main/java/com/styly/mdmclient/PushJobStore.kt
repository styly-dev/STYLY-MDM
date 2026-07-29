package com.styly.mdmclient

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Atomic persistence for active execution, terminal outbox, and dedupe receipts. */
class PushJobStore(
    context: Context,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        private const val TAG = "PushJobStore"
        private const val MAX_RECEIPTS = 256
        private const val RECEIPT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val directory = File(context.filesDir, "push-jobs")
    private val atomicFile = AtomicFile(File(directory, "state.json"))

    @Synchronized
    fun load(): PushProtocol.State {
        if (!atomicFile.baseFile.exists()) return emptyState()
        return try {
            val text = atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            trim(PushProtocol.stateFromJson(JSONObject(text)))
        } catch (error: Exception) {
            // A corrupt state file is serious, but crashing the foreground service would
            // prevent registration and operational recovery. Keep the file for diagnosis.
            Log.e(TAG, "Could not parse durable Push/Sync state", error)
            emptyState()
        }
    }

    @Synchronized
    fun save(state: PushProtocol.State): PushProtocol.State {
        val normalized = trim(state)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Failed to create push job state directory")
        }
        val stream = atomicFile.startWrite()
        try {
            val writer = stream.writer(Charsets.UTF_8)
            writer.write(PushProtocol.stateToJson(normalized).toString())
            writer.flush()
            stream.fd.sync()
            atomicFile.finishWrite(stream)
            return normalized
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    fun emptyState() = PushProtocol.State(null, emptyList(), emptyList())

    private fun trim(state: PushProtocol.State): PushProtocol.State {
        val cutoff = clock() - RECEIPT_RETENTION_MS
        val retained = state.completedReceipts
            .filter { it.result.completedAt <= 0L || it.result.completedAt >= cutoff }
            .takeLast(MAX_RECEIPTS)
        return state.copy(completedReceipts = retained)
    }
}
