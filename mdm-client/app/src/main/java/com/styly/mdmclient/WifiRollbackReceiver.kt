package com.styly.mdmclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WifiRollbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WifiSettingsController.ACTION_ROLLBACK) return
        val controller = MdmClientApplication.wifiSettingsController()
        controller.onRollbackAlarm(intent)
        if (!controller.startupCompletion().isDone) {
            // A cold alarm starts the service first; startup recovery owns the durable journal.
            context.startForegroundService(Intent(context, MdmClientService::class.java)
                .putExtra(MdmClientService.EXTRA_START_REASON, "wifi_rollback"))
        }
    }
}
