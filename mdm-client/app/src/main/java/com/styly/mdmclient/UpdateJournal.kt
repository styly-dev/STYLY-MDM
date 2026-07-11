package com.styly.mdmclient

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small persistent event log that survives the client killing itself.
 *
 * When the MDM installs an APK whose package is our own, Android kills this process as part
 * of the package replacement. Nothing held in memory survives, and the `IIntCallback` that
 * normally reports the install result dies with the binder. The journal is therefore the
 * only post-mortem available: it is written to disk before the installer is invoked and read
 * back by the replacement process.
 *
 * Every write uses `commit()`, not `apply()`. `apply()` hands the write to a background
 * thread, which is exactly the thread that will not survive the package replacement.
 *
 * Serialization lives in [UpdateJournalCodec], which is unit-tested on the host JVM.
 */
object UpdateJournal {

    private const val TAG = "UpdateJournal"

    private const val PREF_EVENTS = "update_journal"
    private const val PREF_UPDATE_IN_PROGRESS = "update_in_progress"
    private const val PREF_TARGET_VERSION_CODE = "update_target_version_code"
    private const val PREF_CORRELATION_ID = "update_correlation_id"
    private const val PREF_POWER_CYCLE_TIMERS = "power_cycle_timers_set"
    private const val PREF_POWER_CYCLE_DISARM_ATTEMPTS = "power_cycle_disarm_attempts"

    private val lock = Any()

    // Recorded by the app process on startup.
    const val EVENT_APP_ONCREATE = "APP_ONCREATE"
    const val EVENT_KEEP_ALIVE = "KEEP_ALIVE"

    // Recorded by MdmClientService around the two calls that can independently fail.
    const val EVENT_SERVICE_ONCREATE = "SERVICE_ONCREATE"
    const val EVENT_SERVICE_FOREGROUND_OK = "SERVICE_FOREGROUND_OK"
    const val EVENT_SERVICE_START_COMMAND = "SERVICE_START_COMMAND"
    const val EVENT_SERVICE_DESTROYED = "SERVICE_DESTROYED"

    // Recorded by SettingsActivity, so a keep-alive relaunch of the LAUNCHER activity is not
    // mistaken for a user opening the app by hand.
    const val EVENT_ACTIVITY_ONCREATE = "ACTIVITY_ONCREATE"

    // Recorded around the silent install.
    const val EVENT_INSTALL_INVOKED = "INSTALL_INVOKED"
    const val EVENT_SELF_INSTALL_INVOKED = "SELF_INSTALL_INVOKED"
    const val EVENT_INSTALL_CALLBACK = "INSTALL_CALLBACK"
    const val EVENT_SELF_UPDATE_CONFIRMED = "SELF_UPDATE_CONFIRMED"
    const val EVENT_INSTALL_REFUSED = "INSTALL_REFUSED"

    // Recorded around the power-cycle timers that bring the device back after a
    // self-update kills this process (nothing else restarts the client — measured).
    const val EVENT_POWER_CYCLE_SCHEDULED = "POWER_CYCLE_SCHEDULED"
    const val EVENT_POWER_CYCLE_CLOSED = "POWER_CYCLE_CLOSED"

    /**
     * Appends an event. Safe to call from any thread and from a BroadcastReceiver, and returns
     * only once the entry is on disk.
     */
    fun record(context: Context, event: String, detail: String = "") {
        synchronized(lock) {
            val prefs = prefs(context)
            val updated = UpdateJournalCodec.append(
                existing = prefs.getString(PREF_EVENTS, "") ?: "",
                timestampMillis = System.currentTimeMillis(),
                event = event,
                detail = detail
            )
            prefs.edit().putString(PREF_EVENTS, updated).commit()
        }
        Log.i(TAG, if (detail.isEmpty()) event else "$event: $detail")
    }

    /**
     * Persists the fact that a self-update is under way, before the installer is invoked and
     * the process dies. The replacement process reads this back to tell "I was updated" from
     * "I crashed and restarted".
     */
    fun markSelfUpdateStarted(context: Context, targetVersionCode: Long, correlationId: String) {
        synchronized(lock) {
            prefs(context).edit()
                .putBoolean(PREF_UPDATE_IN_PROGRESS, true)
                .putLong(PREF_TARGET_VERSION_CODE, targetVersionCode)
                .putString(PREF_CORRELATION_ID, correlationId)
                .commit()
        }
        record(
            context,
            EVENT_SELF_INSTALL_INVOKED,
            "target_version_code=$targetVersionCode correlation_id=$correlationId " +
                "running_version_code=${BuildConfig.VERSION_CODE}"
        )
    }

    /**
     * Persists that the one-shot shutdown/startup timers are armed, before the
     * installer is invoked. The replacement process reads this back to know it must
     * disarm them once the ToBService binder is available — a timer left armed
     * would power the device off again on the next matching wall-clock minute.
     */
    fun markPowerCycleTimersSet(context: Context) {
        synchronized(lock) {
            // A fresh arm starts a new disarm episode, so reset the failure count
            // any earlier, abandoned episode left behind.
            prefs(context).edit()
                .putBoolean(PREF_POWER_CYCLE_TIMERS, true)
                .remove(PREF_POWER_CYCLE_DISARM_ATTEMPTS)
                .commit()
        }
    }

    /**
     * Records a disarm attempt that did not confirm both timers closed. Keeps the
     * armed flag set so the next process start retries, and returns the running
     * count of consecutive failures so the caller can stop an unbounded boot-time
     * retry loop: a close call may report failure for a one-shot timer that already
     * fired (a past-due timer can no longer fire), in which case retrying forever
     * would be noise, not safety.
     */
    fun recordPowerCycleDisarmFailure(context: Context): Int {
        synchronized(lock) {
            val prefs = prefs(context)
            val attempts = prefs.getInt(PREF_POWER_CYCLE_DISARM_ATTEMPTS, 0) + 1
            prefs.edit()
                .putBoolean(PREF_POWER_CYCLE_TIMERS, true)
                .putInt(PREF_POWER_CYCLE_DISARM_ATTEMPTS, attempts)
                .commit()
            return attempts
        }
    }

    fun clearPowerCycleTimers(context: Context) {
        synchronized(lock) {
            prefs(context).edit()
                .remove(PREF_POWER_CYCLE_TIMERS)
                .remove(PREF_POWER_CYCLE_DISARM_ATTEMPTS)
                .commit()
        }
    }

    fun powerCycleTimersSet(context: Context): Boolean =
        prefs(context).getBoolean(PREF_POWER_CYCLE_TIMERS, false)

    /** The pending self-update, or null when no update is in flight. */
    fun pendingSelfUpdate(context: Context): PendingUpdate? {
        val prefs = prefs(context)
        if (!prefs.getBoolean(PREF_UPDATE_IN_PROGRESS, false)) return null
        return PendingUpdate(
            targetVersionCode = prefs.getLong(PREF_TARGET_VERSION_CODE, 0L),
            correlationId = prefs.getString(PREF_CORRELATION_ID, "") ?: ""
        )
    }

    /**
     * Closes out a self-update once the process comes back running the build it targeted.
     * The journal keeps the correlation id and the target versionCode; only the "in flight"
     * marker is retired, so the viewer stops claiming an update is still pending.
     *
     * A marker left behind means the replacement never ran the new build — which is the
     * outcome worth seeing.
     */
    fun confirmSelfUpdateIfLanded(context: Context) {
        val pending = pendingSelfUpdate(context) ?: return
        if (BuildConfig.VERSION_CODE.toLong() < pending.targetVersionCode) return

        record(
            context,
            EVENT_SELF_UPDATE_CONFIRMED,
            "target_version_code=${pending.targetVersionCode} " +
                "running_version_code=${BuildConfig.VERSION_CODE} " +
                "correlation_id=${pending.correlationId}"
        )
        synchronized(lock) {
            prefs(context).edit()
                .remove(PREF_UPDATE_IN_PROGRESS)
                .remove(PREF_TARGET_VERSION_CODE)
                .remove(PREF_CORRELATION_ID)
                .commit()
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            prefs(context).edit()
                .remove(PREF_EVENTS)
                .remove(PREF_UPDATE_IN_PROGRESS)
                .remove(PREF_TARGET_VERSION_CODE)
                .remove(PREF_CORRELATION_ID)
                .remove(PREF_POWER_CYCLE_TIMERS)
                .remove(PREF_POWER_CYCLE_DISARM_ATTEMPTS)
                .commit()
        }
    }

    /** Human-readable dump for the in-headset viewer, newest last. */
    fun format(context: Context): String {
        val prefs = prefs(context)
        val entries = UpdateJournalCodec.parse(prefs.getString(PREF_EVENTS, "") ?: "")

        val out = StringBuilder()
        out.append("running: versionCode=${BuildConfig.VERSION_CODE} ")
            .append("versionName=${BuildConfig.VERSION_NAME}\n")
        val pending = pendingSelfUpdate(context)
        if (pending != null) {
            out.append(
                "pending self-update: target=${pending.targetVersionCode} " +
                    "correlation_id=${pending.correlationId}\n"
            )
        }
        out.append('\n')

        if (entries.isEmpty()) {
            return out.append("(no events recorded)").toString()
        }

        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        for (entry in entries) {
            out.append(stamp.format(Date(entry.timestampMillis)))
                .append("  ")
                .append(entry.event)
                .append('\n')
            if (entry.detail.isNotEmpty()) {
                out.append("    ").append(entry.detail).append('\n')
            }
        }
        return out.toString()
    }

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext
            .getSharedPreferences(WebSocketManager.PREF_NAME, Context.MODE_PRIVATE)
    }

    data class PendingUpdate(
        val targetVersionCode: Long,
        val correlationId: String
    )
}
