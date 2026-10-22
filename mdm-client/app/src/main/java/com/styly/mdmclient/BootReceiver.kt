package com.styly.mdmclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives BOOT_COMPLETED and SERVICE_AUTO_BOOT broadcasts
 * to start MdmClientService automatically after device boot.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Received broadcast: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "com.pvr.tobservice.SERVICE_AUTO_BOOT"
        ) {
            Log.i(TAG, "Starting MdmClientService")
            val serviceIntent = Intent(context, MdmClientService::class.java)
                .putExtra(MdmClientService.EXTRA_START_REASON, MdmClientService.REASON_BOOT)
            context.startForegroundService(serviceIntent)
        }
    }
}
