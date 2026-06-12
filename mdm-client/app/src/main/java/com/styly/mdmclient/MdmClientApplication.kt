package com.styly.mdmclient

import android.app.Application
import android.util.Log
import com.pvr.tobservice.ToBServiceHelper

/**
 * Application class that initializes PICO Enterprise SDK (TobService).
 * Binds TobService and registers app keep-alive on startup.
 */
class MdmClientApplication : Application() {

    companion object {
        private const val TAG = "MdmClientApplication"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Initializing STYLY-MDM Client")
        bindTobService()
    }

    private fun bindTobService() {
        try {
            ToBServiceHelper.getInstance().bindTobService(this) { status ->
                Log.i(TAG, "TobService bind status: $status")
                if (status) {
                    try {
                        ToBServiceHelper.getInstance().serviceBinder?.pbsAppKeepAlive(packageName, true, 0)
                        Log.i(TAG, "Keep-alive registered")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set keep-alive", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind TobService", e)
        }
    }
}
