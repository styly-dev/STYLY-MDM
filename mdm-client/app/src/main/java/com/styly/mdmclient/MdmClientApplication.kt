package com.styly.mdmclient

import android.app.Application
import android.os.Environment
import android.util.Log
import com.pvr.tobservice.ToBServiceHelper
import java.io.File

/**
 * Application class that initializes PICO Enterprise SDK (TobService) and owns
 * the process-wide Push/Sync coordinator.
 */
class MdmClientApplication : Application() {

    companion object {
        private const val TAG = "MdmClientApplication"

        @Volatile
        private var coordinator: PushJobCoordinator? = null

        fun pushJobCoordinator(): PushJobCoordinator =
            coordinator ?: throw IllegalStateException("PushJobCoordinator is not initialized")
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Initializing STYLY-MDM Client")
        // The coordinator is Application-scoped: Service recreation or a transient
        // WebSocket reconnect cannot create a second Push/Sync worker.
        coordinator = PushJobCoordinator(this)
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
        if (UpdateJournal.confirmSelfUpdateIfLanded(this)) {
            // First run of the replacement build: sweep the downloaded APK the dead
            // process could never delete. This must not depend on the power-cycle armed
            // flag — the guard recovery path never sets it (PR #51 review) — and needs
            // no binder, so it runs here rather than in the bind callback.
            cleanupDownloadedApks()
        }
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
     * Power-cycle-fallback housekeeping that needs the ToBService binder, so it runs
     * in the bind callback: disarm the timers the previous build armed (a timer left
     * armed would power the device off again at the next matching wall-clock time).
     * Gated on the persisted armed flag, so an ordinary start — and the guard
     * recovery path, which opens no timers — touches nothing; if disarming fails the
     * flag survives and the next start retries. The sweep also runs here (not only on
     * a confirmed landing in onCreate) because an armed flag with no landing means a
     * failed install whose process died before the callback could delete its download.
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
