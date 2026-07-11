package com.styly.mdmguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Target of the guard's self-chaining deadman alarm (armed by GuardService with
 * setAndAllowWhileIdle + WAKEUP). The watchdog's Handler ticks only run while the
 * guard's process is alive and the device is awake; this alarm survives both — an
 * ordinary process death does not cancel alarms (only force-stop or uninstall does),
 * and the WAKEUP variant fires through sleep. Starting the service both resumes the
 * watch when the guard died with the client still installed, and lets the persisted
 * absence clock reach the self-destruct grace when the client is gone — the client
 * is the guard's usual resurrector, so after its uninstall this alarm is the only
 * thing left that can bring the guard up to clean itself off the device.
 * The alarm delivery puts the app on the temporary allowlist, which is what makes
 * the startForegroundService call from here legal.
 */
class DeadmanReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GuardDeadman"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Deadman alarm fired; starting GuardService")
        context.startForegroundService(
            Intent(context, GuardService::class.java).putExtra("cmd", "deadman")
        )
    }
}
