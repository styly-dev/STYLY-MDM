package com.styly.mdmclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WifiRollbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WifiSettingsController.ACTION_ROLLBACK) return
        MdmClientApplication.wifiSettingsController().onRollbackAlarm(intent)
    }
}
