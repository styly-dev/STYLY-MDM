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
     * How many process starts may retry a disarm that never confirms both timers
     * closed before the armed flag is retired anyway. Bounds the boot-time retry
     * loop: a close call that keeps reporting failure is most likely acting on a
     * one-shot timer that already fired (a past-due timer cannot fire again), so
     * retrying past this point is noise rather than protection.
     */
    private const val MAX_DISARM_ATTEMPTS = 3

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
     * Closes both timers. The armed flag is cleared only when both close calls
     * confirm success — neither throwing nor returning a non-zero code, since
     * closeTimingShutdown/closeTimingStartup are int-returning APIs that can report
     * failure without throwing. Any unconfirmed close keeps the flag set (and the
     * failure count climbing) so the next process start retries, which beats an
     * armed shutdown timer nobody owns; after MAX_DISARM_ATTEMPTS the flag is
     * retired anyway so a past-due timer that can no longer be closed does not
     * loop the recovery on every boot.
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
        val confirmedClosed = !threw && shutdownRet == 0 && startupRet == 0
        val outcome: String
        if (confirmedClosed) {
            UpdateJournal.clearPowerCycleTimers(context)
            outcome = "cleared"
        } else {
            val attempts = UpdateJournal.recordPowerCycleDisarmFailure(context)
            if (attempts >= MAX_DISARM_ATTEMPTS) {
                UpdateJournal.clearPowerCycleTimers(context)
                outcome = "gave_up attempts=$attempts"
            } else {
                outcome = "retry_pending attempts=$attempts"
            }
        }
        UpdateJournal.record(
            context,
            UpdateJournal.EVENT_POWER_CYCLE_CLOSED,
            "reason=$reason close_shutdown=$shutdownRet close_startup=$startupRet " +
                "threw=$threw $outcome"
        )
    }
}
