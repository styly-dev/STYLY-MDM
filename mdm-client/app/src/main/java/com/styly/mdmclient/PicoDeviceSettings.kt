package com.styly.mdmclient

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import com.pvr.tobservice.enums.PBS_ScreenOffDelayTimeEnum
import com.pvr.tobservice.enums.PBS_SleepDelayTimeEnum
import com.pvr.tobservice.enums.PBS_SwitchEnum.S_OFF
import com.pvr.tobservice.enums.PBS_SwitchEnum.S_ON
import com.pvr.tobservice.enums.PBS_SystemFunctionSwitchEnum.*
import com.pvr.tobservice.interfaces.IIntCallback
import com.pvr.tobservice.interfaces.IToBServiceProxy
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class PicoShutdownSchedule(
    val enabled: Boolean,
    val hour: Int = 0,
    val minute: Int = 0,
    val repeat: Int = 0,
) {
    fun toJson() = JSONObject()
        .put("enabled", enabled)
        .put("hour", hour)
        .put("minute", minute)
        .put("repeat", repeat)

    companion object {
        fun requested(json: JSONObject): PicoShutdownSchedule {
            if (!json.getBoolean("enabled")) return PicoShutdownSchedule(false)
            return PicoShutdownSchedule(true, json.getInt("hour"), json.getInt("minute"), json.getInt("repeat"))
                .also { require(it.hour in 0..23 && it.minute in 0..59 && it.repeat in 1..127) }
        }

        fun read(service: IToBServiceProxy): PicoShutdownSchedule {
            val state = JSONObject(service.pbsGetTimingShutDownStatusTwo(0))
            if (!state.getBoolean("open")) return PicoShutdownSchedule(false)
            val (hour, minute) = state.getString("time").split(':', limit = 2)
            return PicoShutdownSchedule(true, hour.toInt(), minute.toInt(), state.getInt("repeatMode"))
        }
    }
}

internal fun settingOutcome(
    status: String,
    value: Any? = null,
    reason: String? = null,
) = JSONObject().apply {
    put("status", status)
    if (value != null) put("value", value)
    if (reason != null) put("reason", reason)
}

/** Applies persistent PUI settings off the main thread and reads them back after all writes. */
internal class PicoDeviceSettings(private val context: Context, private val service: IToBServiceProxy) {
    companion object {
        private val switches = linkedMapOf(
            "autoSleep" to SFS_AUTOSLEEP,
            "bluetooth" to SFS_BLUE_TOOTH,
            "wifiPower" to SFS_POWER_CTRL_WIFI_ENABLE,
            "controllerUI" to SFS_CONTROLLER_UI,
            "gesture" to SFS_GESTURE_RECOGNITION,
            "gestureHome" to SFS_GESTURE_RECOGNITION_HOME_ENABLE,
            "gestureReset" to SFS_GESTURE_RECOGNITION_RESET_ENABLE,
            "volumeUI" to SFS_VOLUME_UI,
            "securityZone" to SFS_SECURITY_ZONE_PERMANENTLY,
            "proximity" to SFS_PSENSOR,
            "seeThrough" to SFS_QUICK_SEETHROUGH_MODE,
            "usb" to SFS_USB,
            "wirelessAdb" to SFS_WIRELESS_USB_ADB,
            "usbCopy" to SFS_AUTO_COPY_FILES_FROM_USB_DEVICE,
        )
        private val sleepDelays = PBS_SleepDelayTimeEnum.values().associateBy { it.index }
        private val screenOffDelays = PBS_ScreenOffDelayTimeEnum.values().associateBy { it.index }
        private const val DISABLE_DOZE = "pico_tob_disable_doze"
        private const val DEVELOPER_MENU_VISIBLE = "tab_key_developerextra_item_visible"
    }

    private class SettingFailure(val reason: String) : Exception()

    fun apply(values: JSONObject): JSONObject {
        val results = JSONObject()
        val written = linkedMapOf<String, Any>()

        fun configure(key: String) {
            if (!values.has(key)) return
            var error = "READ_FAILED"
            try {
                val target = requested(key, values)
                val current = read(key)
                if (!matches(key, current, target)) {
                    error = "WRITE_FAILED"
                    write(key, target)
                    // Dependent settings such as screen-off delay read the current
                    // sleep delay, so let the firmware finish each changed value.
                    awaitValue(key, target)
                }
                written[key] = target
            } catch (failure: Exception) {
                results.put(key, failureOutcome(failure, error))
            }
        }

        switches.keys.forEach(::configure)
        configure("brightness")
        configure("developerMenu")

        // Increase sleep first when extending the window. If reducing it below
        // the current screen-off delay, reduce the screen-off delay first.
        val screenFirst = values.has("sleepDelay") && values.has("screenOff") &&
            runCatching { duration(values.getInt("sleepDelay")) < duration(readSleepDelay()) &&
                duration(values.getInt("sleepDelay")) < duration(readScreenOffDelay()) }.getOrDefault(false)
        if (screenFirst) configure("screenOff")
        configure("sleepDelay")
        if (!screenFirst) configure("screenOff")
        configure("shutdown")

        for ((key, target) in written) {
            results.put(key, try {
                val actual = read(key)
                val value = if (actual is PicoShutdownSchedule) actual.toJson() else actual
                if (matches(key, actual, target)) settingOutcome("applied", value)
                else settingOutcome("failed", value, "READBACK_MISMATCH")
            } catch (failure: Exception) {
                failureOutcome(failure, "READ_FAILED")
            })
        }
        return results
    }

    private fun requested(key: String, values: JSONObject): Any = when (key) {
        "brightness" -> values.getInt(key).also { require(it in 0..100) }
        "sleepDelay" -> values.getInt(key).also { require(it in sleepDelays) }
        "screenOff" -> values.getInt(key).also { require(it in screenOffDelays) }
        "shutdown" -> PicoShutdownSchedule.requested(values.getJSONObject(key))
        else -> values.getBoolean(key)
    }

    private fun read(key: String): Any = when (key) {
        "brightness" -> {
            val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            (raw * 100 + 127) / 255
        }
        "sleepDelay" -> readSleepDelay()
        "screenOff" -> readScreenOffDelay()
        "shutdown" -> synchronized(PowerCycleTimers) { PicoShutdownSchedule.read(service) }
        "bluetooth" -> readBluetooth()
        "developerMenu" -> {
            if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                throw SettingFailure("PERMISSION_DENIED")
            }
            when (Settings.Global.getInt(context.contentResolver, DEVELOPER_MENU_VISIBLE, -1)) {
                0 -> false
                1 -> true
                else -> throw SettingFailure("UNSUPPORTED")
            }
        }
        else -> when (awaitCallback { service.pbsGetSwitchSystemFunctionStatus(switches.getValue(key), it, 0) }) {
            0 -> false
            1 -> true
            -2 -> throw SettingFailure("UNSUPPORTED")
            else -> throw SettingFailure("READ_FAILED")
        }
    }

    private fun write(key: String, target: Any) {
        when (key) {
            "brightness" -> {
                val raw = ((target as Int) * 255 + 50) / 100
                checkWrite(service.setScreenBrightness(raw, 0))
            }
            "sleepDelay" -> {
                val seconds = target as Int
                if (duration(seconds) < duration(readScreenOffDelay())) throw SettingFailure("DELAY_CONFLICT")
                // This overload sends -1 for Never, which also disables PICO doze.
                checkWrite(service.setSystemAutoSleepTime(sleepDelays.getValue(seconds)))
            }
            "screenOff" -> {
                val seconds = target as Int
                if (duration(seconds) > duration(readSleepDelay())) throw SettingFailure("DELAY_CONFLICT")
                checkWrite(awaitCallback { service.pbsPropertySetScreenOffDelay(screenOffDelays.getValue(seconds), it) })
            }
            "shutdown" -> synchronized(PowerCycleTimers) {
                if (UpdateJournal.powerCycleTimersSet(context)) throw SettingFailure("UPDATE_TIMER_ACTIVE")
                val schedule = target as PicoShutdownSchedule
                checkWrite(if (schedule.enabled) {
                    service.openTimingShutdown(schedule.hour, schedule.minute, schedule.repeat)
                } else {
                    service.closeTimingShutdown()
                })
            }
            "developerMenu" -> {
                if (!Settings.Global.putInt(context.contentResolver, DEVELOPER_MENU_VISIBLE, if (target as Boolean) 1 else 0)) {
                    throw SettingFailure("WRITE_FAILED")
                }
            }
            else -> service.pbsSwitchSystemFunction(switches.getValue(key), if (target as Boolean) S_ON else S_OFF, 0)
        }
    }

    private fun readSleepDelay(): Int = service.pbsPropertyGetSleepDelay(0).index
    private fun readScreenOffDelay(): Int = service.pbsPropertyGetScreenOffDelay(0).index

    private fun readBluetooth(): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw SettingFailure("UNSUPPORTED")
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        do {
            when (adapter.state) {
                BluetoothAdapter.STATE_OFF -> return false
                BluetoothAdapter.STATE_ON -> return true
            }
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw SettingFailure("READ_FAILED")
    }

    private fun awaitValue(key: String, target: Any) {
        val deadline = SystemClock.elapsedRealtime() + 2_000L
        while (!matches(key, read(key), target) && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100)
        }
    }

    private fun matches(key: String, actual: Any, target: Any): Boolean =
        actual == target && (key != "sleepDelay" || target != -1 ||
            Settings.Global.getInt(context.contentResolver, DISABLE_DOZE, 0) == 1)

    private fun duration(seconds: Int): Int = if (seconds == -1) Int.MAX_VALUE else seconds

    private fun awaitCallback(register: (IIntCallback) -> Unit): Int {
        val result = CompletableFuture<Int>()
        register(object : IIntCallback.Stub() {
            override fun callback(value: Int) { result.complete(value) }
        })
        return result.get(2, TimeUnit.SECONDS)
    }

    private fun checkWrite(code: Int) {
        if (code != 0) throw SettingFailure(if (code == -2) "UNSUPPORTED" else "WRITE_FAILED")
    }

    private fun failureOutcome(failure: Exception, fallback: String): JSONObject {
        val reason = when (failure) {
            is SettingFailure -> failure.reason
            is SecurityException -> "PERMISSION_DENIED"
            is TimeoutException -> "CALLBACK_TIMEOUT"
            is IllegalArgumentException -> "INVALID_VALUE"
            else -> fallback
        }
        return settingOutcome(if (reason == "UNSUPPORTED") "unsupported" else "failed", reason = reason)
    }
}
