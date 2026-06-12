package com.styly.mdmclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import com.pvr.tobservice.ToBServiceHelper
import com.pvr.tobservice.enums.PBS_PackageControlEnum
import com.pvr.tobservice.interfaces.IIntCallback
import com.pvr.tobservice.interfaces.IToBServiceProxy
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Foreground service that maintains the WebSocket connection to the STYLY-MDM server
 * and executes app launch commands received from the server.
 */
class MdmClientService : Service() {

    companion object {
        private const val TAG = "MdmClientService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "mdmclient_channel"

        // Broadcast action for UI status updates
        const val ACTION_STATUS_UPDATE = "com.styly.mdmclient.STATUS_UPDATE"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_MESSAGE = "message"
    }

    private lateinit var webSocketManager: WebSocketManager

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MdmClientService created")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Initializing..."))

        webSocketManager = WebSocketManager(
            context = this,
            onCommand = ::handleCommand,
            onStatusChanged = ::handleStatusChanged
        )
        webSocketManager.connect()

        launchStartupAppIfConfigured()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "MdmClientService started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "MdmClientService destroyed")
        webSocketManager.disconnect()
        super.onDestroy()
    }

    private fun handleCommand(type: String, payload: JSONObject) {
        Log.i(TAG, "Handling command: $type")
        when (type) {
            "EXECUTE_LAUNCH" -> executeLaunch(payload)
            "EXECUTE_INSTALL" -> executeInstall(payload)
            "SET_STARTUP_APP" -> handleSetStartupApp(payload)
            "CLEAR_STARTUP_APP" -> handleClearStartupApp()
            else -> Log.w(TAG, "Unknown command type: $type")
        }
    }

    private fun executeLaunch(payload: JSONObject) {
        val packageName = payload.optString("package_name", "")
        if (packageName.isEmpty()) {
            Log.e(TAG, "EXECUTE_LAUNCH missing package_name")
            sendLaunchResult(packageName, "fail", "Missing package_name")
            return
        }

        val extra = payload.optString("extra", "")
        doLaunchApp(packageName, extra, killForeground = true) { status, error ->
            sendLaunchResult(packageName, status, error)
        }
    }

    /**
     * Shared launch logic used by both remote EXECUTE_LAUNCH and startup app launch.
     */
    private fun doLaunchApp(
        packageName: String,
        extra: String,
        killForeground: Boolean,
        onResult: (status: String, error: String) -> Unit
    ) {
        Log.i(TAG, "Launching app: $packageName (killForeground=$killForeground)")

        try {
            val binder = ToBServiceHelper.getInstance().serviceBinder
            if (binder == null) {
                Log.e(TAG, "TobService binder not available")
                onResult("fail", "TobService not available")
                return
            }

            if (killForeground) {
                // Force-stop the current foreground app before launching the new one
                try {
                    val focusedApp = (binder as? IToBServiceProxy)?.getFocusedApp()
                    val focusedPkg = focusedApp?.packageName
                    if (focusedPkg != null
                        && focusedPkg != packageName
                        && focusedPkg != applicationContext.packageName) {
                        Log.i(TAG, "Force-stopping foreground app: $focusedPkg")
                        binder.pbsKillAppsByPidOrPackageName(null, arrayOf(focusedPkg), 0)
                        Log.i(TAG, "Successfully force-stopped: $focusedPkg")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to stop foreground app, proceeding with launch", e)
                }
            }

            val result = binder.pbsStartActivity(
                packageName,
                "",       // className - empty to use default launcher activity
                "",       // action
                extra,    // extra data as JSON string
                null,     // categories
                intArrayOf(Intent.FLAG_ACTIVITY_NEW_TASK),
                0         // ext
            )

            Log.i(TAG, "pbsStartActivity result: $result for $packageName")
            if (result == 0) {
                onResult("success", "")
            } else {
                onResult("fail", "pbsStartActivity returned $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $packageName", e)
            onResult("fail", e.message ?: "Unknown error")
        }
    }

    private fun executeInstall(payload: JSONObject) {
        val apkUrl = payload.optString("apk_url", "")
        val apkFilename = sanitizeApkFilename(payload.optString("apk_filename", ""))

        if (apkUrl.isEmpty()) {
            Log.e(TAG, "EXECUTE_INSTALL missing apk_url")
            sendInstallResult(apkFilename, "fail", "Missing apk_url")
            return
        }

        if (!hasExternalStorageAccess()) {
            Log.e(TAG, "MANAGE_EXTERNAL_STORAGE not granted; ToBService cannot read the APK")
            sendInstallResult(
                apkFilename.ifEmpty { apkUrl },
                "fail",
                "All files access (MANAGE_EXTERNAL_STORAGE) is not granted on this device"
            )
            return
        }

        Thread {
            Log.i(TAG, "Downloading APK: $apkUrl")
            try {
                val downloadedApk = downloadApk(apkUrl, apkFilename)
                installApk(downloadedApk, apkFilename.ifEmpty { downloadedApk.name })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install APK from $apkUrl", e)
                sendInstallResult(apkFilename.ifEmpty { apkUrl }, "fail", e.message ?: "Unknown error")
            }
        }.start()
    }

    private fun downloadApk(apkUrl: String, apkFilename: String): File {
        val url = URL(apkUrl)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("APK download failed with HTTP $responseCode")
            }

            val resolvedFilename = apkFilename.ifEmpty {
                sanitizeApkFilename(url.path.substringAfterLast('/')).ifEmpty { "download.apk" }
            }
            // Write to shared public storage (not app-scoped) so the PICO ToBService, which
            // runs as a separate system-user process, can read the file at install time.
            // App-scoped external storage (Android/data/<pkg>) is isolated per-UID by scoped
            // storage and causes pbsControlAPPManger to fail with 102 "APK does not exist".
            val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val apkDir = File(downloadsRoot, "styly-mdm")
            if (!apkDir.exists() && !apkDir.mkdirs()) {
                throw IllegalStateException("Failed to create APK download directory")
            }

            val outputFile = File(apkDir, "${System.currentTimeMillis()}-$resolvedFilename")
            val partialFile = File(apkDir, "${outputFile.name}.part")
            var bytesReadTotal = 0L

            connection.inputStream.use { input ->
                FileOutputStream(partialFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        bytesReadTotal += read
                    }
                }
            }

            if (bytesReadTotal == 0L) {
                partialFile.delete()
                throw IllegalStateException("Downloaded APK is empty")
            }

            if (outputFile.exists() && !outputFile.delete()) {
                partialFile.delete()
                throw IllegalStateException("Failed to replace existing APK file")
            }
            if (!partialFile.renameTo(outputFile)) {
                partialFile.delete()
                throw IllegalStateException("Failed to finalize APK download")
            }

            Log.i(TAG, "Downloaded APK to ${outputFile.absolutePath} ($bytesReadTotal bytes)")
            return outputFile
        } finally {
            connection.disconnect()
        }
    }

    private fun installApk(apkFile: File, apkFilename: String) {
        val binder = ToBServiceHelper.getInstance().serviceBinder
        if (binder == null) {
            apkFile.delete()
            Log.e(TAG, "TobService binder not available")
            sendInstallResult(apkFilename, "fail", "TobService not available")
            return
        }

        Log.i(TAG, "Installing APK: ${apkFile.absolutePath}")
        try {
            binder.pbsControlAPPManger(
                PBS_PackageControlEnum.PACKAGE_SILENCE_INSTALL,
                apkFile.absolutePath,
                0,
                object : IIntCallback.Stub() {
                    override fun callback(result: Int) {
                        val error = if (result == 0) "" else "pbsControlAPPManger returned $result: ${installResultMessage(result)}"
                        Log.i(TAG, "pbsControlAPPManger result: $result for $apkFilename")
                        sendInstallResult(
                            apkFilename,
                            if (result == 0) "success" else "fail",
                            error,
                            result
                        )
                        if (!apkFile.delete()) {
                            Log.w(TAG, "Failed to delete downloaded APK: ${apkFile.absolutePath}")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            apkFile.delete()
            throw e
        }
    }

    private fun hasExternalStorageAccess(): Boolean {
        // MANAGE_EXTERNAL_STORAGE ("All files access") is an appop granted per device, not a
        // runtime permission. On API < R the legacy storage model applies and no check is needed.
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    }

    private fun sanitizeApkFilename(rawFilename: String): String {
        val filename = rawFilename.substringAfterLast('/').substringBefore('?')
        val sanitized = filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return when {
            sanitized.isEmpty() -> ""
            sanitized.endsWith(".apk", ignoreCase = true) -> sanitized
            else -> "$sanitized.apk"
        }
    }

    private fun handleSetStartupApp(payload: JSONObject) {
        val packageName = payload.optString("package_name", "")
        val extra = payload.optString("extra", "")

        if (packageName.isEmpty()) {
            Log.e(TAG, "SET_STARTUP_APP missing package_name")
            sendStartupAppResult("fail", "Missing package_name")
            return
        }

        val prefs = getSharedPreferences(
            WebSocketManager.PREF_NAME, android.content.Context.MODE_PRIVATE
        )
        prefs.edit()
            .putString(WebSocketManager.PREF_STARTUP_APP_PACKAGE, packageName)
            .putString(WebSocketManager.PREF_STARTUP_APP_EXTRA, extra)
            .apply()

        Log.i(TAG, "Startup app set: $packageName")
        sendStartupAppResult("success", "", packageName)
    }

    private fun handleClearStartupApp() {
        val prefs = getSharedPreferences(
            WebSocketManager.PREF_NAME, android.content.Context.MODE_PRIVATE
        )
        prefs.edit()
            .remove(WebSocketManager.PREF_STARTUP_APP_PACKAGE)
            .remove(WebSocketManager.PREF_STARTUP_APP_EXTRA)
            .apply()

        Log.i(TAG, "Startup app cleared")
        sendStartupAppResult("success", "")
    }

    private fun sendStartupAppResult(status: String, error: String, packageName: String = "") {
        val result = JSONObject().apply {
            put("type", "STARTUP_APP_RESULT")
            put("status", status)
            if (packageName.isNotEmpty()) {
                put("package_name", packageName)
            }
            if (error.isNotEmpty()) {
                put("error", error)
            }
        }
        webSocketManager.sendMessage(result)
    }

    private fun launchStartupAppIfConfigured() {
        val config = webSocketManager.getStartupAppConfig() ?: return
        val packageName = config.first
        val extra = config.second

        Log.i(TAG, "Startup app configured: $packageName, launching after delay")

        // Delay to allow TobService binder to initialize
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            doLaunchApp(packageName, extra, killForeground = false) { status, error ->
                Log.i(TAG, "Startup app launch result: $status ${if (error.isNotEmpty()) "- $error" else ""}")
            }
        }, 3000L)
    }

    private fun sendLaunchResult(packageName: String, status: String, error: String) {
        val result = JSONObject().apply {
            put("type", "LAUNCH_RESULT")
            put("status", status)
            put("package_name", packageName)
            if (error.isNotEmpty()) {
                put("error", error)
            }
        }
        webSocketManager.sendMessage(result)
    }

    private fun sendInstallResult(
        apkFilename: String,
        status: String,
        error: String,
        resultCode: Int? = null
    ) {
        val result = JSONObject().apply {
            put("type", "INSTALL_RESULT")
            put("status", status)
            put("apk_filename", apkFilename)
            if (error.isNotEmpty()) {
                put("error", error)
            }
            if (resultCode != null) {
                put("result_code", resultCode)
            }
        }
        webSocketManager.sendMessage(result)
    }

    private fun installResultMessage(result: Int): String {
        return when (result) {
            0 -> "Success"
            1 -> "Failure"
            2 -> "Unauthorized"
            101 -> "Incorrect APK path"
            102 -> "APK does not exist"
            103 -> "Installation blocked"
            104 -> "Installation aborted"
            105 -> "Invalid or corrupt APK"
            106 -> "Package conflict"
            107 -> "Storage failure"
            108 -> "Incompatible APK"
            else -> "Unknown install error"
        }
    }

    private fun handleStatusChanged(connected: Boolean, message: String) {
        Log.i(TAG, "Connection status: connected=$connected, message=$message")
        updateNotification(if (connected) "Connected" else message)

        // Broadcast status for SettingsActivity
        val intent = Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_CONNECTED, connected)
            putExtra(EXTRA_MESSAGE, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "STYLY-MDM Client",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "STYLY-MDM Client background service"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("STYLY-MDM Client")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }
}
