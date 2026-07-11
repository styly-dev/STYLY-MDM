package com.styly.mdmclient

import android.app.Application
import android.os.Environment
import android.util.Log
import com.pvr.tobservice.ToBServiceHelper
import java.io.File

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
        // Running the build a pending self-update targeted proves the replacement landed;
        // this retires the marker. The server learns the outcome from the version_code in
        // the REGISTER that follows once the service connects.
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
                    recoverFromSelfUpdate()
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

    /**
     * Post-self-update housekeeping that needs the ToBService binder, so it runs in
     * the bind callback: disarm the power-cycle timers the previous build armed (a
     * timer left armed would power the device off again at the next matching
     * wall-clock time) and sweep the downloaded APK the dead process could never
     * delete. Gated on the persisted armed flag, so an ordinary start touches
     * neither; if disarming fails the flag survives and the next start retries.
     */
    private fun recoverFromSelfUpdate() {
        if (!UpdateJournal.powerCycleTimersSet(this)) return
        PowerCycleTimers.disarm(this, "recovery")
        cleanupDownloadedApks()
    }

    private fun cleanupDownloadedApks() {
        try {
            val downloadsRoot =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val apkDir = File(downloadsRoot, "styly-mdm")
            // Top level only: push-files staging lives in a subdirectory and cleans
            // itself up. Nothing can be mid-install at process start, so every APK
            // (or interrupted .part) here is a leftover.
            val stale = apkDir.listFiles { f ->
                f.isFile && (f.name.endsWith(".apk") || f.name.endsWith(".part"))
            } ?: return
            var removed = 0
            for (f in stale) {
                if (f.delete()) removed++ else Log.w(TAG, "Could not delete ${f.absolutePath}")
            }
            if (removed > 0) {
                Log.i(TAG, "Removed $removed stale download(s) after the self-update")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stale APK cleanup failed", e)
        }
    }
}
