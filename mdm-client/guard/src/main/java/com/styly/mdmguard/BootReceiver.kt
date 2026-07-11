package com.styly.mdmguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts GuardService after boot, mirroring the client's own BootReceiver, so the
 * watch over the client does not depend on anything starting the guard by hand.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GuardBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received broadcast: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "com.pvr.tobservice.SERVICE_AUTO_BOOT"
        ) {
            Log.i(TAG, "Starting GuardService")
            context.startForegroundService(Intent(context, GuardService::class.java))
        }
    }
}
