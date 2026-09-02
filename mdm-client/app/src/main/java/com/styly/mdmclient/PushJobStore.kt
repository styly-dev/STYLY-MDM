package com.styly.mdmclient

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

internal sealed class PushStateLoadResult {
    data class Valid(val state: PushProtocol.State) : PushStateLoadResult()
    object Missing : PushStateLoadResult()
    data class Corrupt(val error: Exception) : PushStateLoadResult()
}

internal fun loadPushState(
    readText: () -> String,
    normalize: (PushProtocol.State) -> PushProtocol.State,
): PushStateLoadResult = try {
    PushStateLoadResult.Valid(
        normalize(PushProtocol.stateFromJsonStrict(JSONObject(readText()))),
    )
} catch (_: FileNotFoundException) {
    PushStateLoadResult.Missing
} catch (error: Exception) {
    PushStateLoadResult.Corrupt(error)
}

internal fun normalizePushState(
    state: PushProtocol.State,
    cutoff: Long,
    maxReceipts: Int,
): PushProtocol.State {
    val pending = state.pendingResults.takeLast(maxReceipts)
    val completed = state.completedReceipts
        .filter { it.result.completedAt <= 0L || it.result.completedAt >= cutoff }
        .takeLast(maxReceipts)
    return state.copy(pendingResults = pending, completedReceipts = completed)
}

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
    internal fun load(): PushStateLoadResult = loadPushState(
        readText = {
            atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        },
        normalize = ::trim,
    )

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
        if (state.pendingResults.size > MAX_RECEIPTS) {
            Log.w(
                TAG,
                "Dropping ${state.pendingResults.size - MAX_RECEIPTS} oldest pending " +
                    "Push/Sync result(s) to keep the durable outbox bounded",
            )
        }
        return normalizePushState(state, cutoff, MAX_RECEIPTS)
    }
}
