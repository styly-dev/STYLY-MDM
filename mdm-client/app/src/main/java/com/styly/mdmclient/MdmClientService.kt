package com.styly.mdmclient

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import com.pvr.tobservice.ToBServiceHelper
import com.pvr.tobservice.enums.PBS_PackageControlEnum
import com.pvr.tobservice.interfaces.IIntCallback
import com.pvr.tobservice.interfaces.IToBServiceProxy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

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

        // Who asked for the service to start. Journalled so an unattended restart (keep-alive
        // or START_STICKY, which pass no extra) is distinguishable from a user opening the
        // launcher activity. See UpdateJournal.
        const val EXTRA_START_REASON = "start_reason"
        const val REASON_BOOT = "boot"
        const val REASON_SETTINGS = "settings"
        const val REASON_PACKAGE_REPLACED = "package_replaced"
        private const val REASON_SYSTEM = "system"

        /**
         * Read by PackageReplacedReceiver, which runs in the same freshly-started process, to
         * tell whether the foreground service was already up when the broadcast arrived.
         */
        @Volatile
        var isRunning = false
            private set

        // Top-level shared-storage directories that full-mirror push-files sync must never
        // target: mirroring into them would delete unrelated user/media/app data. Matched
        // (case-insensitively) against the first path segment below the storage root.
        private val PROTECTED_TOPLEVEL_DIRS = setOf(
            "android", "download", "downloads", "dcim", "pictures", "movies", "music",
            "documents", "alarms", "notifications", "podcasts", "ringtones"
        )

        // Integrity verification (issue #37). The ZIP End-Of-Central-Directory record is
        // the fixed 22-byte trailer plus an optional comment of up to 0xFFFF bytes.
        private const val EOCD_MIN = 22
        private const val MAX_EOCD_COMMENT = 0xFFFF
        // A CD offset of 0xFFFFFFFF signals ZIP64, which we do not parse (standard APKs
        // are not ZIP64); we return a clear error instead of hashing a wrong range.
        private const val ZIP64_SENTINEL = 0xFFFFFFFFL
        // Bound directory verification so a huge/hostile tree cannot exhaust the device.
        private const val MAX_VERIFY_DIR_ENTRIES = 50_000
        // Above this many files the per-file manifest is omitted (tree_hash still returned).
        private const val MAX_VERIFY_DIR_MANIFEST_ENTRIES = 2_000

        private val HEX = "0123456789abcdef".toCharArray()
    }

    private lateinit var webSocketManager: WebSocketManager

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MdmClientService created")
        UpdateJournal.record(
            this,
            UpdateJournal.EVENT_SERVICE_ONCREATE,
            "version_code=${BuildConfig.VERSION_CODE}"
        )

        createNotificationChannel()
        // startForeground() is the call that throws ForegroundServiceStartNotAllowedException on
        // Android 12+ when the start came from the background; startForegroundService() itself
        // can return cleanly first. Journalling only after it returns pins a failure to the
        // right call instead of leaving the two indistinguishable.
        startForeground(NOTIFICATION_ID, buildNotification("Initializing..."))
        UpdateJournal.record(this, UpdateJournal.EVENT_SERVICE_FOREGROUND_OK)
        isRunning = true

        webSocketManager = WebSocketManager(
            context = this,
            onCommand = ::handleCommand,
            onStatusChanged = ::handleStatusChanged
        )
        webSocketManager.connect()

        launchStartupAppIfConfigured()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent is a START_STICKY redelivery; a start with no reason extra came from
        // outside our own code — the PICO keep-alive being the case the spike is looking for.
        val reason = when {
            intent == null -> REASON_SYSTEM
            intent.action == "com.pvr.tobservice.SERVICE_AUTO_BOOT" -> "tob_auto_boot"
            else -> intent.getStringExtra(EXTRA_START_REASON) ?: REASON_SYSTEM
        }
        Log.i(TAG, "MdmClientService started (reason=$reason)")
        UpdateJournal.record(
            this,
            UpdateJournal.EVENT_SERVICE_START_COMMAND,
            "reason=$reason flags=$flags"
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "MdmClientService destroyed")
        UpdateJournal.record(this, UpdateJournal.EVENT_SERVICE_DESTROYED)
        isRunning = false
        webSocketManager.disconnect()
        super.onDestroy()
    }

    private fun handleCommand(type: String, payload: JSONObject) {
        Log.i(TAG, "Handling command: $type")
        when (type) {
            "EXECUTE_LAUNCH" -> executeLaunch(payload)
            "EXECUTE_INSTALL" -> executeInstall(payload)
            "EXECUTE_PUSH_FILES" -> executePushFiles(payload)
            "EXECUTE_VERIFY_APK" -> executeVerifyApk(payload)
            "EXECUTE_VERIFY_DIR" -> executeVerifyDir(payload)
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
                // Tell the server the network-heavy download is done so it can free
                // this device's transfer slot and dispatch the next queued device,
                // while the local (offline) install proceeds below.
                sendDownloadComplete(task = "install", apkFilename = apkFilename.ifEmpty { downloadedApk.name })
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

        // When the APK replaces our own package, Android kills this process: the IIntCallback
        // below never fires on success and the APK is left behind. Commit what we know to disk
        // first so the replacement process can tell "I was updated" from "I crashed".
        val archive = archiveIdentity(apkFile)
        val isSelfUpdate = archive != null && archive.packageName == packageName
        if (isSelfUpdate) {
            UpdateJournal.markSelfUpdateStarted(
                this,
                archive!!.versionCode,
                UUID.randomUUID().toString()
            )
        } else {
            UpdateJournal.record(
                this,
                UpdateJournal.EVENT_INSTALL_INVOKED,
                "package=${archive?.packageName ?: "unreadable"} " +
                    "target_version_code=${archive?.versionCode ?: -1} file=$apkFilename"
            )
        }

        Log.i(TAG, "Installing APK: ${apkFile.absolutePath} (self_update=$isSelfUpdate)")
        try {
            binder.pbsControlAPPManger(
                PBS_PackageControlEnum.PACKAGE_SILENCE_INSTALL,
                apkFile.absolutePath,
                0,
                object : IIntCallback.Stub() {
                    override fun callback(result: Int) {
                        val error = if (result == 0) "" else "pbsControlAPPManger returned $result: ${installResultMessage(result)}"
                        Log.i(TAG, "pbsControlAPPManger result: $result for $apkFilename")
                        // On a self-update this only runs when the install failed, since a
                        // successful replacement takes the process (and this binder) with it.
                        UpdateJournal.record(
                            this@MdmClientService,
                            UpdateJournal.EVENT_INSTALL_CALLBACK,
                            "result=$result (${installResultMessage(result)}) self_update=$isSelfUpdate"
                        )
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

    /**
     * The package name and versionCode declared inside a downloaded APK, or null when the
     * archive cannot be parsed. Used to recognise an update of our own package before the
     * installer is invoked.
     */
    private fun archiveIdentity(apkFile: File): ArchiveIdentity? {
        return try {
            val info = packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return null
            ArchiveIdentity(info.packageName, info.longVersionCode)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read package info from ${apkFile.absolutePath}", e)
            null
        }
    }

    private data class ArchiveIdentity(
        val packageName: String,
        val versionCode: Long
    )

    /**
     * Download a file/folder bundle and apply it to a destination directory.
     *
     * `delete_extras` picks the semantics: absent or false means push (copy and overwrite,
     * never delete); true means sync (full mirror, pruning anything at the destination that
     * is not in the bundle). It is read with a false default so a payload from a server that
     * predates the flag can only ever copy — a missing field must never delete.
     *
     * Destinations are limited to shared storage — the PICO ToBService has no privileged
     * file-copy API, so only /sdcard is reachable via java.io.File I/O.
     */
    private fun executePushFiles(payload: JSONObject) {
        val bundleUrl = payload.optString("bundle_url", "")
        val destPath = payload.optString("dest_path", "").trim()
        val deleteExtras = payload.optBoolean("delete_extras", false)

        if (bundleUrl.isEmpty()) {
            Log.e(TAG, "EXECUTE_PUSH_FILES missing bundle_url")
            sendPushFilesResult(destPath, "fail", 0, 0, 0, "Missing bundle_url")
            return
        }

        val destError = validateDestPath(destPath)
        if (destError != null) {
            Log.e(TAG, "EXECUTE_PUSH_FILES rejected destination '$destPath': $destError")
            sendPushFilesResult(destPath, "fail", 0, 0, 0, destError)
            return
        }

        if (!hasExternalStorageAccess()) {
            Log.e(TAG, "MANAGE_EXTERNAL_STORAGE not granted; cannot sync files")
            sendPushFilesResult(
                destPath, "fail", 0, 0, 0,
                "All files access (MANAGE_EXTERNAL_STORAGE) is not granted on this device"
            )
            return
        }

        Thread {
            var bundleFile: File? = null
            var staging: File? = null
            try {
                Log.i(TAG, "Downloading bundle: $bundleUrl")
                bundleFile = downloadBundle(bundleUrl)
                // Tell the server the network-heavy download is done so it can free this
                // device's transfer slot and dispatch the next queued device, while the
                // local (offline) unzip and mirror proceed below.
                sendDownloadComplete(task = "push", destPath = destPath, deleteExtras = deleteExtras)
                staging = unzipToStaging(bundleFile)
                val result = BundleSync.apply(staging, File(destPath), deleteExtras)
                Log.i(
                    TAG,
                    "Push-files ${if (deleteExtras) "sync" else "copy"} to $destPath: " +
                        "+${result.added} ~${result.updated} -${result.deleted}"
                )
                sendPushFilesResult(
                    destPath, "success", result.added, result.updated, result.deleted, ""
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push files to $destPath", e)
                sendPushFilesResult(destPath, "fail", 0, 0, 0, e.message ?: "Unknown error")
            } finally {
                bundleFile?.delete()
                staging?.deleteRecursively()
            }
        }.start()
    }

    private fun downloadBundle(bundleUrl: String): File {
        val url = URL(bundleUrl)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("Bundle download failed with HTTP $responseCode")
            }

            val outputFile = File(pushTempDir(), "${System.currentTimeMillis()}-bundle.zip")
            var bytesReadTotal = 0L
            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
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
                outputFile.delete()
                throw IllegalStateException("Downloaded bundle is empty")
            }

            Log.i(TAG, "Downloaded bundle to ${outputFile.absolutePath} ($bytesReadTotal bytes)")
            return outputFile
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Unzip a bundle into a fresh staging directory, rejecting any entry whose path would
     * escape the staging root (zip-slip guard).
     */
    private fun unzipToStaging(bundleFile: File): File {
        val staging = File(pushTempDir(), "staging-${System.currentTimeMillis()}")
        if (!staging.mkdirs()) {
            throw IllegalStateException("Failed to create staging directory")
        }

        val stagingRoot = staging.canonicalPath
        val stagingPrefix = stagingRoot + File.separator
        ZipFile(bundleFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val outFile = File(staging, entry.name)
                val outCanonical = outFile.canonicalPath
                if (outCanonical != stagingRoot && !outCanonical.startsWith(stagingPrefix)) {
                    throw IllegalStateException("Zip entry escapes staging dir: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output -> input.copyTo(output) }
                    }
                }
            }
        }
        return staging
    }

    private fun pushTempDir(): File {
        val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloadsRoot, "styly-mdm/.push-tmp")
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Failed to create push temp directory")
        }
        return dir
    }

    /**
     * Validate a device destination path. Applied to pushes as well as syncs: a sync deletes
     * extras, so a bad path could wipe unrelated data, and holding both to the same rule keeps
     * a push from quietly reaching somewhere a sync may not. The canonical path must live under
     * shared storage and be neither the storage root nor a protected top-level directory.
     * Returns an error message, or null if the path is safe.
     */
    private fun validateDestPath(destPath: String): String? {
        if (destPath.isEmpty()) return "Destination path is required"
        if (!destPath.startsWith("/")) return "Destination must be an absolute path"
        if (destPath.split("/").contains("..")) return "Destination path must not contain '..'"

        val storageRoot = Environment.getExternalStorageDirectory().canonicalFile
        val target: File = try {
            File(destPath).canonicalFile
        } catch (e: Exception) {
            return "Invalid destination path"
        }
        val rootPath = storageRoot.path
        if (target.path == rootPath) {
            return "Destination must be a subdirectory, not the shared-storage root"
        }
        if (!target.path.startsWith(rootPath + File.separator)) {
            return "Destination must be under shared storage ($rootPath)"
        }
        val firstSegment = target.path.substring(rootPath.length + 1).split("/")[0]
        if (firstSegment.lowercase() in PROTECTED_TOPLEVEL_DIRS) {
            return "Destination must not be inside the protected '$firstSegment' directory"
        }
        return null
    }


    private fun sendPushFilesResult(
        destPath: String,
        status: String,
        added: Int,
        updated: Int,
        deleted: Int,
        error: String
    ) {
        val result = JSONObject().apply {
            put("type", "PUSH_FILES_RESULT")
            put("status", status)
            put("dest_path", destPath)
            put("added", added)
            put("updated", updated)
            put("deleted", deleted)
            if (error.isNotEmpty()) {
                put("error", error)
            }
        }
        webSocketManager.sendMessage(result)
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

    /**
     * Signal that a download finished, freeing the server's transfer slot before the local
     * work (install, or unzip + mirror) begins.
     *
     * `task` names which job the download belonged to, since a device can be transferring for
     * an install and a push at once and each holds its own slot. A server that predates push
     * throttling ignores the field and reads `apk_filename` as before.
     */
    private fun sendDownloadComplete(
        task: String,
        apkFilename: String = "",
        destPath: String = "",
        deleteExtras: Boolean = false
    ) {
        val msg = JSONObject().apply {
            put("type", "DOWNLOAD_COMPLETE")
            put("task", task)
            put("apk_filename", apkFilename)
            if (destPath.isNotEmpty()) {
                put("dest_path", destPath)
                put("delete_extras", deleteExtras)
            }
        }
        webSocketManager.sendMessage(msg)
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

    // -----------------------------------------------------------------------
    // Integrity verification (issue #37). Computes the same size + ZIP
    // Central-Directory digest / directory tree hash that the console (pure JS)
    // and the `styly-mdm hash` CLI (Python) compute, so the browser can compare
    // a device against a local reference without any upload. Hashing runs on a
    // worker thread (a base.apk can be 1 GB+); the reply goes back over the same
    // WebSocket send helper the install flow uses.
    // -----------------------------------------------------------------------

    private fun executeVerifyApk(payload: JSONObject) {
        val pkg = payload.optString("package_name", "")
        if (pkg.isEmpty()) {
            Log.e(TAG, "EXECUTE_VERIFY_APK missing package_name")
            sendVerifyApkResult(pkg, found = false, error = "Missing package_name")
            return
        }
        Thread {
            try {
                val pm = packageManager
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val apkFile = File(appInfo.sourceDir)
                val size = apkFile.length()
                val cdSha256 = apkCentralDirectoryDigest(apkFile)
                val fullSha256 = fileSha256(apkFile)
                @Suppress("DEPRECATION")
                val pkgInfo = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                sendVerifyApkResult(
                    pkg,
                    found = true,
                    size = size,
                    cdSha256 = cdSha256,
                    fullSha256 = fullSha256,
                    versionCode = pkgInfo.longVersionCode,
                    versionName = pkgInfo.versionName ?: "",
                    signerSha256 = signerSha256(pkgInfo)
                )
            } catch (e: PackageManager.NameNotFoundException) {
                Log.i(TAG, "VERIFY_APK: package not installed: $pkg")
                sendVerifyApkResult(pkg, found = false)
            } catch (e: Exception) {
                Log.e(TAG, "VERIFY_APK failed for $pkg", e)
                sendVerifyApkResult(pkg, found = false, error = e.message ?: "Unknown error")
            }
        }.start()
    }

    private fun executeVerifyDir(payload: JSONObject) {
        val path = payload.optString("path", "")
        Thread {
            try {
                if (!hasExternalStorageAccess()) {
                    sendVerifyDirResult(
                        path, found = false,
                        error = "All files access (MANAGE_EXTERNAL_STORAGE) is not granted on this device"
                    )
                    return@Thread
                }
                val root = resolveVerifyDir(path)
                if (!root.exists()) {
                    sendVerifyDirResult(path, found = false)
                    return@Thread
                }
                if (!root.isDirectory) {
                    sendVerifyDirResult(path, found = false, error = "Path is not a directory")
                    return@Thread
                }
                sendVerifyDirResult(path, found = true, result = dirManifest(root))
            } catch (e: SecurityException) {
                Log.e(TAG, "VERIFY_DIR denied for $path", e)
                sendVerifyDirResult(path, found = false, error = e.message ?: "Path not permitted")
            } catch (e: Exception) {
                Log.e(TAG, "VERIFY_DIR failed for $path", e)
                sendVerifyDirResult(path, found = false, error = e.message ?: "Unknown error")
            }
        }.start()
    }

    /**
     * Canonicalize a device directory path and enforce that it stays within shared
     * external storage. Verification only reads, but bounding to shared storage keeps it
     * to the scope operators distribute content into and matches what MANAGE_EXTERNAL_STORAGE
     * actually grants. Throws for an invalid or out-of-scope path.
     */
    private fun resolveVerifyDir(path: String): File {
        if (path.isEmpty() || !path.startsWith("/")) {
            throw IllegalArgumentException("A valid absolute path is required")
        }
        val storageRoot = Environment.getExternalStorageDirectory()
            ?: throw IllegalStateException("Shared external storage is unavailable")
        val root = storageRoot.canonicalFile
        val target = File(path).canonicalFile
        if (target != root && !target.path.startsWith(root.path + File.separator)) {
            throw SecurityException("Path is outside shared storage (${root.path})")
        }
        return target
    }

    /**
     * SHA-256 of the ZIP Central-Directory region [CD_offset .. EOF]. Parses the EOCD to
     * find CD_offset (little-endian uint32) — this covers every entry's CRC-32 + sizes plus
     * the EOCD, while reading only a few hundred KB regardless of APK size.
     */
    private fun apkCentralDirectoryDigest(file: File): String {
        RandomAccessFile(file, "r").use { raf ->
            val fileLen = raf.length()
            val readLen = minOf(fileLen, (EOCD_MIN + MAX_EOCD_COMMENT).toLong()).toInt()
            val tailStart = fileLen - readLen
            val tail = ByteArray(readLen)
            raf.seek(tailStart)
            raf.readFully(tail)
            val eocdOff = findEocd(tail, fileLen, tailStart)
            if (eocdOff < 0) throw IllegalStateException("End Of Central Directory record not found")
            // ByteBuffer defaults to big-endian — ZIP fields are little-endian.
            val bb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            val cdOffset = bb.getInt(eocdOff + 16).toLong() and 0xFFFFFFFFL
            if (cdOffset == ZIP64_SENTINEL) throw IllegalStateException("zip64 not supported")
            val md = MessageDigest.getInstance("SHA-256")
            raf.seek(cdOffset)
            val buf = ByteArray(1 shl 20)
            var remaining = fileLen - cdOffset
            while (remaining > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                md.update(buf, 0, n)
                remaining -= n
            }
            return md.digest().toHex()
        }
    }

    private fun findEocd(tail: ByteArray, fileLen: Long, tailStart: Long): Int {
        val bb = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        for (i in tail.size - EOCD_MIN downTo 0) {
            if ((tail[i].toInt() and 0xff) == 0x50 && (tail[i + 1].toInt() and 0xff) == 0x4b &&
                (tail[i + 2].toInt() and 0xff) == 0x05 && (tail[i + 3].toInt() and 0xff) == 0x06
            ) {
                val commentLen = bb.getShort(i + 20).toInt() and 0xffff
                if (tailStart + i + EOCD_MIN + commentLen == fileLen) return i
            }
        }
        return -1
    }

    private fun fileSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    private fun signerSha256(pkgInfo: PackageInfo): String {
        val signers = pkgInfo.signingInfo?.apkContentsSigners ?: return ""
        if (signers.isEmpty()) return ""
        return MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()).toHex()
    }

    private class DirResult(
        val treeHash: String,
        val fileCount: Int,
        val totalSize: Long,
        val manifest: JSONArray?
    )

    private class DirEntry(val relativePath: String, val size: Long, val sha256: String)

    /**
     * Walk a directory into a manifest and tree hash. Policy (matches the console and CLI):
     * symlinks are not followed and excluded; empty directories are not represented; entries
     * are sorted by the UTF-8 byte order of their relative path.
     */
    private fun dirManifest(root: File): DirResult {
        val entries = ArrayList<DirEntry>()
        walkDir(root, "", entries)
        entries.sortWith(Comparator { a, b -> utf8Compare(a.relativePath, b.relativePath) })

        val md = MessageDigest.getInstance("SHA-256")
        var totalSize = 0L
        for (e in entries) {
            totalSize += e.size
            md.update("${e.relativePath}\n${e.size}\n${e.sha256}\n".toByteArray(Charsets.UTF_8))
        }
        val manifest = if (entries.size <= MAX_VERIFY_DIR_MANIFEST_ENTRIES) {
            JSONArray().apply {
                for (e in entries) {
                    put(JSONObject().apply {
                        put("relative_path", e.relativePath)
                        put("size", e.size)
                        put("sha256", e.sha256)
                    })
                }
            }
        } else null
        return DirResult(md.digest().toHex(), entries.size, totalSize, manifest)
    }

    private fun walkDir(dir: File, relBase: String, out: ArrayList<DirEntry>) {
        Files.newDirectoryStream(dir.toPath()).use { stream ->
            for (child in stream) {
                if (Files.isSymbolicLink(child)) continue
                val name = child.fileName.toString()
                val rel = if (relBase.isEmpty()) name else "$relBase/$name"
                val attr = Files.readAttributes(
                    child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
                )
                when {
                    attr.isDirectory -> walkDir(child.toFile(), rel, out)
                    attr.isRegularFile -> {
                        if (out.size >= MAX_VERIFY_DIR_ENTRIES) {
                            throw IllegalStateException("Directory has more than $MAX_VERIFY_DIR_ENTRIES files")
                        }
                        out.add(DirEntry(rel, attr.size(), fileSha256(child.toFile())))
                    }
                }
            }
        }
    }

    private fun utf8Compare(a: String, b: String): Int {
        val ea = a.toByteArray(Charsets.UTF_8)
        val eb = b.toByteArray(Charsets.UTF_8)
        val n = minOf(ea.size, eb.size)
        for (i in 0 until n) {
            val d = (ea[i].toInt() and 0xff) - (eb[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return ea.size - eb.size
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private fun sendVerifyApkResult(
        packageName: String,
        found: Boolean,
        size: Long? = null,
        cdSha256: String? = null,
        fullSha256: String? = null,
        versionCode: Long? = null,
        versionName: String? = null,
        signerSha256: String? = null,
        error: String? = null
    ) {
        val result = JSONObject().apply {
            put("type", "VERIFY_APK_RESULT")
            put("package_name", packageName)
            put("found", found)
            if (size != null) put("size", size)
            if (cdSha256 != null) put("cd_sha256", cdSha256)
            if (fullSha256 != null) put("full_sha256", fullSha256)
            if (versionCode != null) put("version_code", versionCode)
            if (versionName != null) put("version_name", versionName)
            if (!signerSha256.isNullOrEmpty()) put("signer_sha256", signerSha256)
            if (!error.isNullOrEmpty()) put("error", error)
        }
        webSocketManager.sendMessage(result)
    }

    private fun sendVerifyDirResult(
        path: String,
        found: Boolean,
        result: DirResult? = null,
        error: String? = null
    ) {
        val msg = JSONObject().apply {
            put("type", "VERIFY_DIR_RESULT")
            put("path", path)
            put("found", found)
            if (result != null) {
                put("tree_hash", result.treeHash)
                put("file_count", result.fileCount)
                put("total_size", result.totalSize)
                if (result.manifest != null) put("manifest", result.manifest)
            }
            if (!error.isNullOrEmpty()) put("error", error)
        }
        webSocketManager.sendMessage(msg)
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
