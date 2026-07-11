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
     * Bounded in-process retry budget for closing timers when arming partially
     * fails. `arm()` runs off the main thread (see MdmClientService, inside a
     * Thread), so a short blocking backoff between attempts is safe; kept small so
     * the total wait stays well under an ANR even if it ever moved.
     */
    private const val MAX_ARM_CLOSE_ATTEMPTS = 4
    private const val ARM_CLOSE_BACKOFF_MS = 150L

    /** Outcome of [arm]. A refusal is safe only once no shutdown timer can remain armed alone. */
    enum class ArmOutcome { ARMED, REFUSED_SAFE, REFUSED_UNSAFE }

    /** Whether a single close pass confirmed each timer closed (returned 0, no throw). */
    data class CloseResult(val shutdownClosed: Boolean, val startupClosed: Boolean)

    /**
     * Returns ARMED once both one-shot timers are scheduled. On any scheduling
     * failure it refuses the self-update (installing with no way back would strand
     * the device) but does not return until the dangerous shape — a shutdown timer
     * armed without its paired startup, which would power the device off with no
     * scheduled return — has been closed out or, if it cannot be, surfaced:
     * REFUSED_UNSAFE tells the caller to report an unsafe state to the server
     * rather than treating the refused install as enough recovery. The read-back
     * strings are journalled so a wrong wall-clock schedule (time zone, 1-based
     * month) is diagnosable from the headset after the fact.
     */
    fun arm(context: Context, proxy: IToBServiceProxy): ArmOutcome {
        val plan = PowerCycleSchedule.compute(Calendar.getInstance())
        // These SDK calls are guarded with catch(Throwable), not catch(Exception): the
        // timing APIs resolve com.google.gson.JsonObject internally, and a missing Gson
        // raises NoClassDefFoundError (an Error). Treating any failure as a non-zero
        // return keeps arm()'s "refuse safely on a scheduling failure" contract intact
        // instead of letting the Error escape and kill the install thread.
        val shutdownRet = try {
            proxy.openTimingShutdown(
                plan.shutdown.year, plan.shutdown.month, plan.shutdown.day,
                plan.shutdown.hour, plan.shutdown.minute,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "openTimingShutdown threw", e)
            Int.MIN_VALUE
        }
        val startupRet = try {
            proxy.openTimingStartup(
                plan.startup.year, plan.startup.month, plan.startup.day,
                plan.startup.hour, plan.startup.minute,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "openTimingStartup threw", e)
            Int.MIN_VALUE
        }
        if (shutdownRet != 0 || startupRet != 0) {
            Log.e(TAG, "Power-cycle scheduling failed: shutdown=$shutdownRet startup=$startupRet")
            return refuseAndCloseOut(context, proxy, shutdownRet, startupRet)
        }
        val shutdownStatus = try {
            proxy.pbsGetTimingShutDownStatusTwo(0)
        } catch (e: Throwable) {
            "error ${e.javaClass.name}: ${e.message}"
        }
        val startupStatus = try {
            proxy.pbsGetTimingStartupStatusTwo(0)
        } catch (e: Throwable) {
            "error ${e.javaClass.name}: ${e.message}"
        }
        UpdateJournal.record(
            context,
            UpdateJournal.EVENT_POWER_CYCLE_SCHEDULED,
            "shutdown=${plan.shutdown} startup=${plan.startup} " +
                "shutdown_status=$shutdownStatus startup_status=$startupStatus"
        )
        UpdateJournal.markPowerCycleTimersSet(context)
        return ArmOutcome.ARMED
    }

    /**
     * Handles a partial/total scheduling failure without ever leaving a shutdown
     * timer armed alone if it can be helped. Closes *both* timers (the open return
     * codes are not trusted to say what actually armed — same conservative stance
     * as [disarm]) and retries within a bounded window, since the close itself can
     * fail transiently. The persisted armed flag is set whenever anything may still
     * be armed, so a reboot's recovery retries; the outcome is REFUSED_SAFE once
     * the shutdown timer is confirmed closed (a lone startup timer is benign — it
     * only powers an already-on device on) and REFUSED_UNSAFE otherwise.
     */
    private fun refuseAndCloseOut(
        context: Context,
        proxy: IToBServiceProxy,
        shutdownRet: Int,
        startupRet: Int,
    ): ArmOutcome {
        // Sticky across passes: a timer that any pass confirmed closed stays closed
        // (re-closing an already-closed one-shot may return non-zero, which must not
        // flip a safe state back to unsafe).
        var shutdownEverClosed = false
        var startupEverClosed = false
        var attempt = 0
        val shutdownWasArmed = shutdownRet == 0
        val outcome = resolvePartialArm(MAX_ARM_CLOSE_ATTEMPTS, shutdownWasArmed) {
            if (attempt++ > 0) {
                try {
                    Thread.sleep(ARM_CLOSE_BACKOFF_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            val shutdownClosed = try {
                proxy.closeTimingShutdown() == 0
            } catch (e: Throwable) {
                Log.e(TAG, "closeTimingShutdown threw during refuse-close", e)
                false
            }
            val startupClosed = try {
                proxy.closeTimingStartup() == 0
            } catch (e: Throwable) {
                Log.e(TAG, "closeTimingStartup threw during refuse-close", e)
                false
            }
            if (shutdownClosed) shutdownEverClosed = true
            if (startupClosed) startupEverClosed = true
            CloseResult(shutdownClosed, startupClosed)
        }
        // Only a timer that actually opened (ret == 0) and was not since confirmed
        // closed may still be armed; trust the same open==0 signal arm() uses on its
        // success path. A total open failure leaves nothing armed, so no retry flag.
        val shutdownMaybeArmed = shutdownWasArmed && !shutdownEverClosed
        val startupMaybeArmed = startupRet == 0 && !startupEverClosed
        if (shutdownMaybeArmed || startupMaybeArmed) {
            // Something may still be armed; a reboot's recovery must retry the close.
            UpdateJournal.markPowerCycleTimersSet(context)
        }
        UpdateJournal.record(
            context,
            UpdateJournal.EVENT_POWER_CYCLE_CLOSED,
            "reason=schedule_failed shutdown_ret=$shutdownRet startup_ret=$startupRet " +
                "attempts=$attempt shutdown_was_armed=$shutdownWasArmed " +
                "close_shutdown=$shutdownEverClosed close_startup=$startupEverClosed outcome=$outcome"
        )
        if (outcome == ArmOutcome.REFUSED_UNSAFE) {
            Log.e(TAG, "Power-cycle arming left an unsafe state: shutdown may be armed without a paired startup")
        }
        return outcome
    }

    /**
     * Pure decision for a partial arming failure: replays up to [maxAttempts] close
     * passes, tracking per timer whether *any* pass confirmed it closed (a closed
     * one-shot stays closed even if a later re-close reports non-zero). The only
     * unsafe outcome is a shutdown timer that [shutdownWasArmed] and was never
     * confirmed closed — that alone can power the device off with no scheduled
     * return; when shutdown never armed there is nothing to strand the device, so
     * the refusal is safe regardless of what the close of a never-armed timer
     * reports. A lone startup timer is likewise benign (it only powers an
     * already-on device on). Kept free of I/O so the control flow is unit-tested on
     * the host JVM.
     */
    fun resolvePartialArm(
        maxAttempts: Int,
        shutdownWasArmed: Boolean,
        closeAttempt: () -> CloseResult,
    ): ArmOutcome {
        var shutdownEverClosed = false
        var startupEverClosed = false
        repeat(maxAttempts) {
            val pass = closeAttempt()
            if (pass.shutdownClosed) shutdownEverClosed = true
            if (pass.startupClosed) startupEverClosed = true
            val shutdownSafe = !shutdownWasArmed || shutdownEverClosed
            if (shutdownSafe && startupEverClosed) return ArmOutcome.REFUSED_SAFE
        }
        return if (!shutdownWasArmed || shutdownEverClosed) ArmOutcome.REFUSED_SAFE
        else ArmOutcome.REFUSED_UNSAFE
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
        } catch (e: Throwable) {
            Log.e(TAG, "closeTimingShutdown threw", e)
            threw = true
            Int.MIN_VALUE
        }
        val startupRet = try {
            proxy.closeTimingStartup()
        } catch (e: Throwable) {
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
