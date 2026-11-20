package com.styly.mdmclient

/** Firmware-specific native state; unknown values must never become a blind toggle. */
internal enum class RecordingState(val wire: String) {
    RECORDING("recording"), STOPPED("stopped"), UNKNOWN("unknown");

    companion object {
        fun fromProperties(properties: String, recorderVersion: Long): RecordingState {
            val lines = properties.lineSequence().toList()
            val flag = lines.filter { it.startsWith("[pvr.screen.action]:") }
            if (flag == listOf("[pvr.screen.action]: [record]")) return RECORDING
            if (flag == listOf("[pvr.screen.action]: []")) return STOPPED
            if (flag.isEmpty() && lines.contains("[ro.pui.build.version]: [5.15.7]") &&
                recorderVersion == 100004072L) return STOPPED
            return UNKNOWN
        }
    }
}

internal data class RecordingResult(
    val status: String,
    val state: RecordingState = RecordingState.UNKNOWN,
    val changed: Boolean = false,
    val detail: String = "",
)

internal interface RecordingReceipts {
    fun find(requestId: String): Pair<String, RecordingResult>?
    fun save(requestId: String, action: String, result: RecordingResult): Boolean
    fun reserveToggle(atMillis: Long): Boolean
}

/** One caller at a time. Persist before the vendor toggle, and never retry it. */
internal class RecordingControl(
    private val receipts: RecordingReceipts,
    private val readState: () -> RecordingState,
    private val toggle: () -> Unit,
    private val active: () -> Boolean,
    private val now: () -> Long,
    private val sleep: (Long) -> Unit,
) {
    fun execute(requestId: String, action: String): RecordingResult {
        if (action !in setOf("status", "start", "stop")) return RecordingResult("fail", detail = "Invalid recording action")
        if (action == "status") return status()
        val previous = receipts.find(requestId)
        if (previous != null) return if (previous.first == action) previous.second
            else RecordingResult("fail", detail = "Request ID already used for another action")
        val interrupted = RecordingResult("fail", detail = "Previous recording request was unconfirmed; refresh state before another action")
        if (!receipts.save(requestId, action, interrupted)) {
            return RecordingResult("fail", detail = "Unable to save recording request receipt")
        }
        val result = try {
            val state = readState()
            val desired = if (action == "start") RecordingState.RECORDING else RecordingState.STOPPED
            when {
                !active() -> RecordingResult("fail", detail = "Command connection is no longer active")
                state == RecordingState.UNKNOWN -> RecordingResult("fail", detail = "Native recording state unavailable; no change sent")
                state == desired -> RecordingResult("success", state)
                else -> {
                    if (!receipts.reserveToggle(now())) {
                        return RecordingResult("fail", detail = "Recording change is still settling or could not be saved; wait eight seconds and refresh state")
                    }
                    if (!active()) return RecordingResult("fail", detail = "Command connection is no longer active")
                    toggle()
                    val started = now()
                    var observed = RecordingState.UNKNOWN
                    while (now() - started < 8_000) {
                        sleep(250)
                        observed = readState()
                        if (now() - started >= 3_000 && observed == desired) break
                    }
                    if (observed == desired) RecordingResult("success", observed, changed = true)
                    else RecordingResult("fail", detail = "Recording change could not be confirmed; refresh state and check the headset")
                }
            }
        } catch (_: Exception) {
            RecordingResult("fail", detail = "Recording control failed; refresh state and check the headset")
        }
        // If this save fails, the durable interrupted receipt still prevents replay.
        receipts.save(requestId, action, result)
        return result
    }

    private fun status(): RecordingResult = try {
        val state = readState()
        if (state == RecordingState.UNKNOWN) RecordingResult("fail", detail = "Native recording state unavailable on this firmware")
        else RecordingResult("success", state)
    } catch (_: Exception) {
        RecordingResult("fail", detail = "Could not read native recording state")
    }
}

/** elapsedRealtime survives process restart; a negative delta means a device reboot. */
internal fun recordingCooldownActive(previous: Long, current: Long): Boolean =
    previous != 0L && current >= previous && current - previous < 8_000
