package com.styly.mdmclient

import android.content.Context
import android.util.Log
import com.pvr.tobservice.ToBServiceHelper
import com.pvr.tobservice.interfaces.IToBServiceProxy
import java.util.Calendar

/**
 * Arms and disarms the one-shot shutdown/startup pair that brings the device back
 * after a self-update kills the client process (nothing else restarts it — measured
 * for #39). Owns the journal events and the persisted "armed" flag, because two
 * different processes touch the timers: the dying build arms them, and either the
 * same build (install failure) or the replacement build (recovery after boot)
 * disarms them.
 */
object PowerCycleTimers {

    private const val TAG = "PowerCycleTimers"

    /**
     * Returns false — with both timers closed again — when any call fails: a
     * self-update with no way back would strand the device. The read-back strings
     * are journalled so a wrong wall-clock schedule (time zone, 1-based month) is
     * diagnosable from the headset after the fact.
     */
    fun arm(context: Context, proxy: IToBServiceProxy): Boolean {
        val plan = PowerCycleSchedule.compute(Calendar.getInstance())
        val shutdownRet = try {
            proxy.openTimingShutdown(
                plan.shutdown.year, plan.shutdown.month, plan.shutdown.day,
                plan.shutdown.hour, plan.shutdown.minute,
            )
        } catch (e: Exception) {
            Log.e(TAG, "openTimingShutdown threw", e)
            Int.MIN_VALUE
        }
        val startupRet = try {
            proxy.openTimingStartup(
                plan.startup.year, plan.startup.month, plan.startup.day,
                plan.startup.hour, plan.startup.minute,
            )
        } catch (e: Exception) {
            Log.e(TAG, "openTimingStartup threw", e)
            Int.MIN_VALUE
        }
        if (shutdownRet != 0 || startupRet != 0) {
            Log.e(TAG, "Power-cycle scheduling failed: shutdown=$shutdownRet startup=$startupRet")
            disarm(context, "schedule_failed shutdown_ret=$shutdownRet startup_ret=$startupRet")
            return false
        }
        val shutdownStatus = try {
            proxy.pbsGetTimingShutDownStatusTwo(0)
        } catch (e: Exception) {
            "error ${e.javaClass.name}: ${e.message}"
        }
        val startupStatus = try {
            proxy.pbsGetTimingStartupStatusTwo(0)
        } catch (e: Exception) {
            "error ${e.javaClass.name}: ${e.message}"
        }
        UpdateJournal.record(
            context,
            UpdateJournal.EVENT_POWER_CYCLE_SCHEDULED,
            "shutdown=${plan.shutdown} startup=${plan.startup} " +
                "shutdown_status=$shutdownStatus startup_status=$startupStatus"
        )
        UpdateJournal.markPowerCycleTimersSet(context)
        return true
    }

    /**
     * Closes both timers. The armed flag is cleared only when neither close call
     * throws — a flag left set is retried on the next process start (see
     * MdmClientApplication), which beats an armed shutdown timer nobody owns.
     */
    fun disarm(context: Context, reason: String) {
        val proxy = ToBServiceHelper.getInstance().serviceBinder as? IToBServiceProxy
        if (proxy == null) {
            Log.e(TAG, "Cannot disarm power cycle: ToBService proxy unavailable")
            UpdateJournal.record(
                context,
                UpdateJournal.EVENT_POWER_CYCLE_CLOSED,
                "reason=$reason proxy_unavailable=true"
            )
            return
        }
        var threw = false
        val shutdownRet = try {
            proxy.closeTimingShutdown()
        } catch (e: Exception) {
            Log.e(TAG, "closeTimingShutdown threw", e)
            threw = true
            Int.MIN_VALUE
        }
        val startupRet = try {
            proxy.closeTimingStartup()
        } catch (e: Exception) {
            Log.e(TAG, "closeTimingStartup threw", e)
            threw = true
            Int.MIN_VALUE
        }
        if (!threw) {
            UpdateJournal.clearPowerCycleTimers(context)
        }
        UpdateJournal.record(
            context,
            UpdateJournal.EVENT_POWER_CYCLE_CLOSED,
            "reason=$reason close_shutdown=$shutdownRet close_startup=$startupRet"
        )
    }
}
