package com.styly.mdmclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives `ACTION_MY_PACKAGE_REPLACED`, delivered to the *new* APK right after it replaces
 * its own installed package. This is the first code that runs after an MDM self-update kills
 * the client process.
 *
 * Present in the manifest only when the build was made with `-PspikeMode=observe|act` (issue
 * #39): the `off` default measures whether the PICO keep-alive restarts the client with no
 * new code at all, so the receiver must be absent, not merely inert.
 */
class PackageReplacedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageReplacedReceiver"
        private const val MODE_ACT = "act"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.w(TAG, "Ignoring unexpected action: ${intent.action}")
            return
        }

        // Whether the service is already up decides how to read the FGS attempt below: a
        // running foreground service is not subject to the background-start restriction, so
        // an "ok" recorded while it is running proves nothing about the restriction.
        UpdateJournal.record(
            context,
            UpdateJournal.EVENT_PACKAGE_REPLACED,
            "version_code=${BuildConfig.VERSION_CODE} " +
                "spike_mode=${BuildConfig.SPIKE_MODE} " +
                "service_running=${MdmClientService.isRunning}"
        )

        if (BuildConfig.SPIKE_MODE != MODE_ACT) {
            UpdateJournal.record(
                context,
                UpdateJournal.EVENT_FGS_ATTEMPT,
                "skipped (spike_mode=${BuildConfig.SPIKE_MODE})"
            )
            return
        }

        // Android 12+ forbids starting a foreground service from the background. BOOT_COMPLETED
        // is a documented exemption; MY_PACKAGE_REPLACED is not documented either way, and PICO
        // devices span Android 10-14. Measure it rather than assume it.
        val serviceIntent = Intent(context, MdmClientService::class.java)
            .putExtra(MdmClientService.EXTRA_START_REASON, MdmClientService.REASON_PACKAGE_REPLACED)
        try {
            context.startForegroundService(serviceIntent)
            UpdateJournal.record(context, UpdateJournal.EVENT_FGS_ATTEMPT, "ok")
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException is API 31+ and extends IllegalStateException,
            // so the class name is recorded rather than caught by type (compileSdk is 33, but the
            // exact throw differs per OEM and API level and is precisely what the spike measures).
            UpdateJournal.record(
                context,
                UpdateJournal.EVENT_FGS_ATTEMPT,
                "threw ${e.javaClass.name}: ${e.message}"
            )
            Log.e(TAG, "startForegroundService failed", e)
        }
    }
}
