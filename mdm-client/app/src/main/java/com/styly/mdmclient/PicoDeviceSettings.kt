package com.styly.mdmclient

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.pvr.tobservice.enums.PBS_ScreenOffDelayTimeEnum
import com.pvr.tobservice.enums.PBS_SleepDelayTimeEnum
import com.pvr.tobservice.enums.PBS_SwitchEnum.S_OFF
import com.pvr.tobservice.enums.PBS_SwitchEnum.S_ON
import com.pvr.tobservice.enums.PBS_SystemFunctionSwitchEnum.*
import com.pvr.tobservice.interfaces.IBoolCallback
import com.pvr.tobservice.interfaces.IIntCallback
import com.pvr.tobservice.interfaces.IStringCallback
import com.pvr.tobservice.interfaces.IToBServiceProxy
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Consumer
import java.math.BigDecimal

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

internal fun normalizeIpd(value: Any?): Double {
    val number = value as? Number ?: throw IllegalArgumentException("IPD must be numeric")
    val decimal = try {
        BigDecimal(number.toString())
    } catch (_: NumberFormatException) {
        throw IllegalArgumentException("IPD must be finite")
    }
    if (decimal < BigDecimal("58") || decimal > BigDecimal("72") ||
        decimal.movePointRight(1).stripTrailingZeros().scale() > 0
    ) throw IllegalArgumentException("IPD must be 58.0–72.0 in 0.1 steps")
    return decimal.toDouble()
}

internal enum class LbeMapImportResult {
    ACCEPTED, FAILED, CALLBACK_TIMEOUT,
}

internal enum class ActiveLbeMap { MATCH, DIFFERENT, MODE_OFF, UNKNOWN }

internal fun inspectActiveLbeMap(readMode: () -> Boolean, compareZip: () -> Int): ActiveLbeMap = try {
    if (!readMode()) ActiveLbeMap.MODE_OFF else when (compareZip()) {
        0 -> ActiveLbeMap.MATCH
        1 -> ActiveLbeMap.DIFFERENT
        else -> ActiveLbeMap.UNKNOWN
    }
} catch (_: Exception) {
    ActiveLbeMap.UNKNOWN
} catch (_: LinkageError) {
    ActiveLbeMap.UNKNOWN
}

internal fun awaitStableAvailability(
    isAvailable: () -> Boolean,
    nowElapsed: () -> Long = SystemClock::elapsedRealtime,
    sleep: (Long) -> Unit = Thread::sleep,
    stableMillis: Long = 800L,
    timeoutMillis: Long = 10_000L,
    pollMillis: Long = 100L,
): Boolean {
    require(stableMillis > 0 && timeoutMillis >= stableMillis && pollMillis > 0)
    val deadline = nowElapsed() + timeoutMillis
    var stableSince: Long? = null
    while (true) {
        val available = try {
            isAvailable()
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw failure
        } catch (failure: PicoDeviceSettings.SettingFailure) {
            if (failure.reason != "READ_FAILED") throw failure
            false
        } catch (_: TimeoutException) {
            false
        }
        val observedAt = nowElapsed()
        if (observedAt > deadline) return false
        if (available) {
            val since = stableSince ?: observedAt.also { stableSince = it }
            if (observedAt - since >= stableMillis) return true
        } else {
            stableSince = null
        }
        val remaining = deadline - nowElapsed()
        if (remaining <= 0) return false
        val stableRemaining = stableSince?.let { stableMillis - (nowElapsed() - it) } ?: pollMillis
        sleep(minOf(pollMillis, remaining, stableRemaining.coerceAtLeast(1L)))
    }
}

internal fun settingsFailureOutcome(failure: Throwable, fallback: String): JSONObject {
    val reason = when (failure) {
        is PicoDeviceSettings.SettingFailure -> failure.reason
        is SecurityException -> "PERMISSION_DENIED"
        is TimeoutException -> "CALLBACK_TIMEOUT"
        is IllegalArgumentException -> "INVALID_VALUE"
        else -> fallback
    }
    return settingOutcome(if (reason == "UNSUPPORTED") "unsupported" else "failed", reason = reason)
}

internal fun applyPicoLauncher(
    packageName: String?,
    appEnabled: (String) -> Boolean?,
    setLauncher: (String?) -> Int,
): JSONObject {
    if (packageName == null || packageName.length > 255) return settingOutcome("failed", reason = "INVALID_VALUE")
    return try {
        var missing = false
        if (packageName.isNotEmpty()) {
            if (!packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
                return settingOutcome("failed", reason = "INVALID_VALUE")
            }
            when (appEnabled(packageName)) {
                null -> missing = true
                false -> return settingOutcome("failed", packageName, "APP_DISABLED")
                true -> Unit
            }
        }
        when (setLauncher(packageName.takeUnless { missing || it.isEmpty() })) {
            0 -> if (missing) settingOutcome("waiting", packageName, "APP_NOT_INSTALLED")
                else settingOutcome("applied", packageName)
            -2 -> settingOutcome("unsupported", reason = "UNSUPPORTED")
            else -> settingOutcome("failed", reason = "WRITE_FAILED")
        }
    } catch (_: LinkageError) {
        settingOutcome("unsupported", reason = "UNSUPPORTED")
    } catch (failure: Exception) {
        settingsFailureOutcome(failure, "WRITE_FAILED")
    }
}

internal fun picoHomeAppEnabled(context: Context, packageName: String): Boolean? = try {
    context.packageManager.getApplicationInfo(packageName, 0).enabled
} catch (_: PackageManager.NameNotFoundException) {
    null
}

internal fun pollBluetoothState(
    target: Boolean?,
    nowElapsed: () -> Long = SystemClock::elapsedRealtime,
    sleep: (Long) -> Unit = Thread::sleep,
    readState: () -> Boolean?,
): Boolean? {
    val deadline = nowElapsed() + 2_000L
    var actual: Boolean? = null
    while (nowElapsed() < deadline) {
        actual = readState()
        if (actual != null && (target == null || actual == target)) return actual
        val remaining = deadline - nowElapsed()
        if (remaining > 0) sleep(minOf(remaining, 100L))
    }
    return actual
}

/** Blocking adapter for the two asynchronous Large Space mode calls on TobService. */
internal class PicoLbeMode(
    val readStatus: () -> String,
    val setEnabled: (Boolean) -> Boolean,
) {
    constructor(service: IToBServiceProxy) : this(
        readStatus = {
            val result = CompletableFuture<String>()
            service.pbsGetSwitchLargeSpaceStatus(object : IStringCallback.Stub() {
                override fun callback(value: String) { result.complete(value) }
            }, 0)
            result.get(2, TimeUnit.SECONDS)
        },
        setEnabled = { target ->
            val result = CompletableFuture<Boolean>()
            service.pbsSwitchLargeSpaceScene(object : IBoolCallback.Stub() {
                override fun callBack(value: Boolean) { result.complete(value) }
            }, target, 0)
            result.get(30, TimeUnit.SECONDS)
        },
    )

    fun isEnabled(): Boolean = when (readStatus()) {
        "0" -> false
        "1" -> true
        else -> throw PicoDeviceSettings.SettingFailure("READ_FAILED")
    }
}

/** Applies persistent PUI settings off the main thread and reads them back after all writes. */
internal class PicoDeviceSettings(
    private val context: Context,
    private val service: IToBServiceProxy,
    private val lbeMode: PicoLbeMode = PicoLbeMode(service),
    private val nowElapsed: () -> Long = SystemClock::elapsedRealtime,
) {
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
            "usbBoot" to SFS_USB_BOOT,
            "usb" to SFS_USB,
            "wirelessAdb" to SFS_WIRELESS_USB_ADB,
            "usbCopy" to SFS_AUTO_COPY_FILES_FROM_USB_DEVICE,
        )
        private val sleepDelays = PBS_SleepDelayTimeEnum.values().associateBy { it.index }
        private val screenOffDelays = PBS_ScreenOffDelayTimeEnum.values().associateBy { it.index }
        private const val DISABLE_DOZE = "pico_tob_disable_doze"
        private const val DEVELOPER_MENU_VISIBLE = "tab_key_developerextra_item_visible"
    }

    fun apply(
        values: JSONObject,
        overwrite: Boolean,
    ): JSONObject {
        val results = JSONObject()
        val written = linkedMapOf<String, Any>()

        fun configure(key: String) {
            if (!values.has(key)) return
            var error = "READ_FAILED"
            try {
                val target = requested(key, values)
                if (key == "ipd") {
                    results.put(key, applyIpd(target as Double))
                    return
                }
                val current = read(key)
                // Repeating the LBE switch can bring the PICO app to the foreground.
                if ((overwrite && key != "lbeMode") || !matches(key, current, target)) {
                    error = "WRITE_FAILED"
                    write(key, target)
                    // Dependent settings such as screen-off delay read the current
                    // sleep delay, so let the firmware finish each write.
                    if (key != "lbeMode" || target != true) awaitValue(key, target)
                }
                written[key] = target
            } catch (failure: LinkageError) {
                results.put(key, settingsFailureOutcome(failure, error))
            } catch (failure: Exception) {
                results.put(key, settingsFailureOutcome(failure, error))
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
        configure("ipd")
        configure("lbeMode")

        if (values.has("startupApp")) {
            results.put("startupApp", applyPicoLauncher(
                values.opt("startupApp") as? String,
                appEnabled = { packageName -> picoHomeAppEnabled(context, packageName) },
                setLauncher = service::setLauncher,
            ))
        }

        for ((key, target) in written) {
            results.put(key, try {
                if (key == "lbeMode" && target == true) {
                    val stable = awaitStableAvailability(isAvailable = lbeMode::isEnabled, nowElapsed = nowElapsed)
                    val actual = lbeMode.isEnabled()
                    if (stable && actual) settingOutcome("applied", true)
                    else settingOutcome("failed", actual, "READBACK_MISMATCH")
                } else {
                    val actual = read(key)
                    val value = if (actual is PicoShutdownSchedule) actual.toJson() else actual
                    if (matches(key, actual, target)) settingOutcome("applied", value)
                    else settingOutcome("failed", value, "READBACK_MISMATCH")
                }
            } catch (failure: LinkageError) {
                settingsFailureOutcome(failure, "READ_FAILED")
            } catch (failure: Exception) {
                settingsFailureOutcome(failure, "READ_FAILED")
            })
        }
        return results
    }

    private fun requested(key: String, values: JSONObject): Any = when (key) {
        "brightness" -> values.getInt(key).also { require(it in 1..100) }
        "sleepDelay" -> values.getInt(key).also { require(it in sleepDelays) }
        "screenOff" -> values.getInt(key).also { require(it in screenOffDelays) }
        "shutdown" -> PicoShutdownSchedule.requested(values.getJSONObject(key))
        "ipd" -> normalizeIpd(values.opt(key))
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
        "lbeMode" -> lbeMode.isEnabled()
        "bluetooth" -> awaitBluetooth()
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
            2, -2 -> throw SettingFailure("UNSUPPORTED")
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
            "lbeMode" -> {
                val opened = lbeMode.setEnabled(target as Boolean)
                if (!opened) throw SettingFailure("WRITE_FAILED")
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

    private fun applyIpd(target: Double): JSONObject = try {
        val result = awaitIpdCallback { callback -> service.pbsSetIPD(target.toFloat(), callback) }
        when (result) {
            0 -> settingOutcome("applied", target, "ACCEPTED_UNVERIFIED")
            -2 -> settingOutcome("unsupported", reason = "UNSUPPORTED")
            else -> settingOutcome("failed", target, "WRITE_FAILED")
        }
    } catch (failure: Exception) {
        settingsFailureOutcome(failure, "WRITE_FAILED")
    } catch (failure: LinkageError) {
        settingOutcome("unsupported", reason = "UNSUPPORTED")
    }

    fun importLbeMap(path: String): LbeMapImportResult = try {
        val result = awaitMapImportCallback { callback -> service.importMapByPath(path, callback, 0) }
        Log.i("ConfigurationPackages", "LBE map import callback code=$result")
        if (result == 0) LbeMapImportResult.ACCEPTED else LbeMapImportResult.FAILED
    } catch (_: TimeoutException) {
        LbeMapImportResult.CALLBACK_TIMEOUT
    } catch (_: LinkageError) {
        LbeMapImportResult.FAILED
    } catch (_: Exception) {
        LbeMapImportResult.FAILED
    }

    fun activeLbeMap(path: String): ActiveLbeMap = inspectActiveLbeMap(lbeMode::isEnabled) {
        awaitCallback { service.isMapInEffect(path, it, 0) }
    }

    private fun awaitIpdCallback(register: (Consumer<Int>) -> Unit): Int {
        val result = CompletableFuture<Int>()
        register(Consumer { value -> result.complete(value) })
        return result.get(15, TimeUnit.SECONDS)
    }

    private fun awaitMapImportCallback(register: (IIntCallback) -> Unit): Int {
        val result = CompletableFuture<Int>()
        register(object : IIntCallback.Stub() {
            override fun callback(value: Int) { result.complete(value) }
        })
        return result.get(60, TimeUnit.SECONDS)
    }

    private fun awaitBluetooth(target: Boolean? = null): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw SettingFailure("UNSUPPORTED")
        return pollBluetoothState(target) {
            when (adapter.state) {
                BluetoothAdapter.STATE_OFF -> false
                BluetoothAdapter.STATE_ON -> true
                else -> null
            }
        } ?: throw SettingFailure("READ_FAILED")
    }

    private fun awaitValue(key: String, target: Any) {
        if (key == "bluetooth") {
            awaitBluetooth(target as Boolean)
            return
        }
        val deadline = nowElapsed() + 2_000L
        while (!matches(key, read(key), target) && nowElapsed() < deadline) {
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

    internal class SettingFailure(val reason: String) : Exception()
}
