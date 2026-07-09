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
        // The first thing the replacement process does after a self-update. Recording it
        // separates "the process was started" from "the service was started".
        UpdateJournal.record(
            this,
            UpdateJournal.EVENT_APP_ONCREATE,
            "version_code=${BuildConfig.VERSION_CODE} version_name=${BuildConfig.VERSION_NAME}"
        )
        // Running the build a pending self-update targeted proves the replacement landed.
        // Reporting the outcome to the server is issue #39's follow-up; this only retires
        // the marker so the journal viewer stops showing an update that already completed.
        UpdateJournal.confirmSelfUpdateIfLanded(this)
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
                        UpdateJournal.record(this, UpdateJournal.EVENT_KEEP_ALIVE, "registered")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to set keep-alive", e)
                        UpdateJournal.record(
                            this,
                            UpdateJournal.EVENT_KEEP_ALIVE,
                            "failed ${e.javaClass.name}: ${e.message}"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind TobService", e)
            UpdateJournal.record(
                this,
                UpdateJournal.EVENT_KEEP_ALIVE,
                "bind failed ${e.javaClass.name}: ${e.message}"
            )
        }
    }
}
