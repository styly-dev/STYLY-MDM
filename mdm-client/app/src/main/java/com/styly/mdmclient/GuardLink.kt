package com.styly.mdmclient

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import com.pvr.tobservice.interfaces.IToBServiceProxy

/**
 * The client's side of the mutual watch with the guard app (com.styly.mdmguard).
 *
 * The guard revives this client after a self-update replaces its package: the
 * installer kills the process and nothing else on PICO restarts it (measured for
 * #39), and the scheduled power-cycle fallback is dead on firmware whose SELinux
 * policy denies the poweroffalarm app the RTC alarm HAL. In return the client
 * starts the guard whenever it is installed but not running — which is also how
 * the guard first comes up after being deployed over MDM (a fresh install is in
 * the stopped state and receives no boot broadcast until a reboot), and how it
 * comes back after an update of its own package.
 *
 * All starts go through TobService's privileged startForegroundService, the same
 * primitive the guard uses for revival: it is exempt from background-start
 * restrictions and punches through the stopped state (verified on device).
 */
object GuardLink {

    private const val TAG = "GuardLink"

    const val GUARD_PACKAGE = "com.styly.mdmguard"
    private const val GUARD_SERVICE = "com.styly.mdmguard.GuardService"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(GUARD_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** true/false when TobService answered, null when liveness is unknowable right now. */
    fun isRunning(proxy: IToBServiceProxy): Boolean? = try {
        proxy.runningAppProcesses?.any { it.processName == GUARD_PACKAGE }
    } catch (e: Throwable) {
        Log.e(TAG, "getRunningAppProcesses threw", e)
        null
    }

    /**
     * Makes sure the guard is up, starting it when it is not, and returns true only
     * once its process is confirmed running. With [waitMs] > 0 the confirmation is
     * polled until the deadline; the self-update gate uses this because handing
     * recovery to a guard that never came up would strand the device. With the
     * default fire-and-forget the caller's next periodic check is the confirmation.
     */
    fun ensureRunning(context: Context, proxy: IToBServiceProxy, waitMs: Long = 0): Boolean {
        if (!isInstalled(context)) return false
        if (isRunning(proxy) == true) return true
        try {
            val started = proxy.startForegroundService(
                Intent().setClassName(GUARD_PACKAGE, GUARD_SERVICE)
            )
            Log.i(TAG, "Requested guard start: $started")
        } catch (e: Throwable) {
            Log.e(TAG, "Starting the guard threw", e)
            return false
        }
        val deadline = SystemClock.elapsedRealtime() + waitMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isRunning(proxy) == true) return true
            SystemClock.sleep(500)
        }
        return isRunning(proxy) == true
    }
}
