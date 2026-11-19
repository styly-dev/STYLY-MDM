package com.styly.mdmclient

import android.content.Context
import android.os.SystemClock
import com.pvr.tobservice.ToBServiceHelper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Runs bounded native-property reads and recorder confirmation off the main thread. */
internal class NativeRecordingControl(private val context: Context) {
    companion object {
        const val CAPABILITY = "recording_control_v1"
        private val REQUEST_ID = Regex("[a-f0-9]{32}")
        private val busy = AtomicBoolean(false)
    }

    fun execute(payload: JSONObject, active: () -> Boolean, reply: (JSONObject) -> Unit) {
        val id = payload.opt("request_id") as? String ?: ""
        val action = payload.opt("action") as? String ?: ""
        fun result(value: RecordingResult) = JSONObject().apply {
            put("type", "RECORDING_RESULT")
            put("request_id", id)
            put("action", action)
            put("status", value.status)
            put("state", value.state.wire)
            put("changed", value.changed)
            put("detail", value.detail)
        }
        if (!REQUEST_ID.matches(id) || action !in setOf("status", "start", "stop")) {
            reply(result(RecordingResult("fail", detail = "Invalid recording request")))
            return
        }
        if (!busy.compareAndSet(false, true)) {
            reply(result(RecordingResult("fail", detail = "Another recording request is in progress")))
            return
        }
        Thread({
            try {
                val control = RecordingControl(
                    PreferenceRecordingReceipts(context), ::readState,
                    {
                        val binder = ToBServiceHelper.getInstance().serviceBinder
                            ?: throw IllegalStateException("TobService unavailable")
                        binder.pbsRecord()
                    }, active, SystemClock::elapsedRealtime, Thread::sleep,
                )
                reply(result(control.execute(id, action)))
            } catch (_: Exception) {
                reply(result(RecordingResult("fail", detail = "Recording control unavailable")))
            } finally {
                busy.set(false)
            }
        }, "mdm-recording-control").start()
    }

    private fun readState(): RecordingState {
        // Redirect to a private file so a full stdout pipe cannot block getprop.
        val output = File.createTempFile("recording-properties-", ".txt", context.cacheDir)
        try {
            val process = ProcessBuilder("/system/bin/getprop").redirectErrorStream(true)
                .redirectOutput(output).start()
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) return RecordingState.UNKNOWN
                if (process.exitValue() != 0 || output.length() > 262_144) return RecordingState.UNKNOWN
                val version = try {
                    context.packageManager.getPackageInfo("com.bytedance.pico.screencapture", 0).longVersionCode
                } catch (_: Exception) { -1L }
                return RecordingState.fromProperties(output.readText(), version)
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } finally {
            output.delete()
        }
    }
}

/** Preserve the latest mutating request across service/process restart. */
private class PreferenceRecordingReceipts(context: Context) : RecordingReceipts {
    private val prefs = context.getSharedPreferences("recording-requests", Context.MODE_PRIVATE)

    override fun find(requestId: String): Pair<String, RecordingResult>? {
        if (prefs.getString("request_id", null) != requestId) return null
        val raw = prefs.getString("result", null) ?: return null
        val value = JSONObject(raw)
        return value.getString("action") to RecordingResult(
            value.getString("status"),
            RecordingState.values().firstOrNull { it.wire == value.getString("state") } ?: RecordingState.UNKNOWN,
            value.getBoolean("changed"), value.getString("detail"),
        )
    }

    override fun save(requestId: String, action: String, result: RecordingResult): Boolean {
        return prefs.edit().putString("request_id", requestId).putString("result", JSONObject().apply {
            put("action", action)
            put("status", result.status)
            put("state", result.state.wire)
            put("changed", result.changed)
            put("detail", result.detail)
        }.toString()).commit()
    }

    override fun reserveToggle(atMillis: Long): Boolean {
        val previous = prefs.getLong("toggle_elapsed", 0)
        if (recordingCooldownActive(previous, atMillis)) return false
        return prefs.edit().putLong("toggle_elapsed", atMillis).commit()
    }
}
