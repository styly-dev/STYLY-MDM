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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.pvr.tobservice.ToBServiceHelper
import com.pvr.tobservice.enums.PBS_DeviceControlEnum
import com.pvr.tobservice.enums.PBS_PackageControlEnum
import com.pvr.tobservice.enums.PBS_SwitchEnum
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
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
        private const val REASON_SYSTEM = "system"

        // Top-level shared-storage directories that full-mirror push-files sync must never
        // target: mirroring into them would delete unrelated user/media/app data. Matched
        // (case-insensitively) against the first path segment below the storage root.
        private val PROTECTED_TOPLEVEL_DIRS = setOf(
            "android", "download", "downloads", "dcim", "pictures", "movies", "music",
            "documents", "alarms", "notifications", "podcasts", "ringtones"
        )

        // Mutual watch with the guard app. The self-update gate blocks up to
        // GUARD_START_WAIT_MS for the guard's process to be confirmed before relying
        // on it for recovery; the periodic tick provisions the guard (installs the
        // embedded APK when missing or outdated) and restarts one that is installed
        // but down. The install cooldown keeps a failing install (e.g. a signature
        // conflict) from being retried every tick.
        private const val GUARD_START_WAIT_MS = 5_000L
        private const val GUARD_ENSURE_INTERVAL_MS = 60_000L
        private const val GUARD_INSTALL_COOLDOWN_MS = 300_000L

        // A retire (#49) uninstalls the guard before the client uninstalls itself.
        // Bounded wait for the guard uninstall's callback: generous for a local
        // package removal, but never a blocker — the retire proceeds regardless,
        // since a surviving guard self-destructs on its own once the client is gone.
        private const val GUARD_UNINSTALL_WAIT_MS = 15_000L

        // EXECUTE_UNINSTALL (#63) reports from the SDK callback, which — unlike the
        // self-uninstall — always fires because the client survives. This bounds
        // the wait anyway: without it a callback that never came would leave the
        // package blocked from every later attempt and the console with no result.
        private const val UNINSTALL_CALLBACK_WAIT_MS = 60_000L

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

    // Mutual watch: off the main thread because each tick is a binder round-trip to
    // TobService. Journal only the down→start transition, not every tick, so a guard
    // that cannot start does not churn the 80-line journal.
    private lateinit var guardEnsureThread: HandlerThread
    private lateinit var guardEnsure: Handler
    private var guardSeenDown = false
    // Guard provisioning state (all touched only on the guard-ensure thread).
    // The upgrade comparison runs once per process: a new embedded guard can only
    // arrive with a new client build, i.e. a new process.
    private var embeddedGuardChecked = false
    private var guardInstallInFlight = false
    private var lastGuardInstallAt = 0L
    private var guardInstallJournaled = false

    // A retire is underway: the guard has been (or is being) uninstalled on purpose,
    // so the mutual-watch tick must not reinstall or restart it. Cleared again only
    // when the retire fails, which re-opens guard provisioning on the next tick.
    @Volatile
    private var retireInProgress = false

    // Packages with an EXECUTE_UNINSTALL (#63) still running. A re-sent command
    // must not start a second uninstall of the same package while the first one
    // is between the startup-app clear and its callback, or the restore-on-failure
    // of one could undo the other. Deliberately separate from retireInProgress,
    // whose semantics (guard provisioning paused, client going away) are specific
    // to the retire ceremony.
    private val uninstallsInFlight =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

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

        webSocketManager = WebSocketManager(
            context = this,
            onCommand = ::handleCommand,
            onStatusChanged = ::handleStatusChanged
        )
        webSocketManager.connect()

        launchStartupAppIfConfigured()

        guardEnsureThread = HandlerThread("guard-ensure").apply { start() }
        guardEnsure = Handler(guardEnsureThread.looper)
        guardEnsure.postDelayed(::guardEnsureTick, GUARD_ENSURE_INTERVAL_MS)
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
        guardEnsureThread.quitSafely()
        webSocketManager.disconnect()
        super.onDestroy()
    }

    /**
     * The client's half of the mutual watch: install the embedded guard when it is
     * missing or outdated, and restart one that is installed but not running.
     * Fire-and-forget — the next tick is the confirmation. See GuardLink for why the
     * guard cannot rely on anything else to provision or start it.
     */
    private fun guardEnsureTick() {
        try {
            // The finally still reschedules, so a failed retire (which clears the
            // flag) resumes guard provisioning without further ceremony.
            if (retireInProgress) return
            ensureGuardProvisioned()
            val proxy = ToBServiceHelper.getInstance().serviceBinder as? IToBServiceProxy
            if (proxy != null && GuardLink.isInstalled(this)) {
                if (GuardLink.isRunning(proxy) == false) {
                    if (!guardSeenDown) {
                        guardSeenDown = true
                        UpdateJournal.record(
                            this,
                            UpdateJournal.EVENT_GUARD_STARTED,
                            "guard was not running"
                        )
                    }
                    GuardLink.ensureRunning(this, proxy)
                } else {
                    guardSeenDown = false
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Guard ensure tick failed", e)
        } finally {
            guardEnsure.postDelayed(::guardEnsureTick, GUARD_ENSURE_INTERVAL_MS)
        }
    }

    /**
     * Installs the guard APK bundled into this build (assets/guard.apk) when the
     * installed guard is missing or older than the embedded copy. The upgrade
     * comparison runs once per process; the missing case retries on a cooldown so a
     * persistently failing install (signature conflict and the like) is neither
     * hammered every tick nor allowed to churn the journal — the attempt and its
     * failure are journalled once per absence episode.
     */
    private fun ensureGuardProvisioned() {
        if (guardInstallInFlight) return
        val installedVc = GuardLink.installedVersionCode(this)
        if (installedVc != null) {
            guardInstallJournaled = false
            if (embeddedGuardChecked) return
        } else if (SystemClock.elapsedRealtime() - lastGuardInstallAt < GUARD_INSTALL_COOLDOWN_MS) {
            return
        }
        val binder = ToBServiceHelper.getInstance().serviceBinder ?: return
        val embedded = GuardLink.extractEmbedded(this) ?: return
        embeddedGuardChecked = true
        val reason = when {
            installedVc == null -> "missing"
            embedded.versionCode > installedVc ->
                "upgrade from=$installedVc to=${embedded.versionCode}"
            else -> {
                embedded.file.delete()
                return
            }
        }
        lastGuardInstallAt = SystemClock.elapsedRealtime()
        guardInstallInFlight = true
        Log.i(TAG, "Installing embedded guard (${embedded.versionCode}): $reason")
        if (!guardInstallJournaled) {
            guardInstallJournaled = true
            UpdateJournal.record(
                this,
                UpdateJournal.EVENT_GUARD_INSTALLED,
                "reason=$reason version_code=${embedded.versionCode}"
            )
        }
        // The staging directory is shared storage (the installer cannot read
        // app-private files), so re-check the bytes at the last moment before
        // handing the path to the privileged installer.
        if (!GuardLink.verifyStaged(embedded)) {
            guardInstallInFlight = false
            embedded.file.delete()
            Log.e(TAG, "Staged guard APK changed since extraction; refusing to install it")
            UpdateJournal.record(
                this,
                UpdateJournal.EVENT_GUARD_INSTALLED,
                "failed reason=staged file hash mismatch"
            )
            return
        }
        try {
            binder.pbsControlAPPManger(
                PBS_PackageControlEnum.PACKAGE_SILENCE_INSTALL,
                embedded.file.absolutePath,
                0,
                object : IIntCallback.Stub() {
                    override fun callback(result: Int) {
                        guardInstallInFlight = false
                        embedded.file.delete()
                        if (result == 0) {
                            Log.i(TAG, "Embedded guard installed")
                        } else {
                            Log.e(TAG, "Embedded guard install failed: $result (${installResultMessage(result)})")
                            UpdateJournal.record(
                                this@MdmClientService,
                                UpdateJournal.EVENT_GUARD_INSTALLED,
                                "failed result=$result (${installResultMessage(result)})"
                            )
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            guardInstallInFlight = false
            embedded.file.delete()
            Log.e(TAG, "Embedded guard install threw", e)
        }
    }

    private fun handleCommand(type: String, payload: JSONObject) {
        Log.i(TAG, "Handling command: $type")
        when (type) {
            "EXECUTE_LAUNCH" -> executeLaunch(payload)
            "EXECUTE_UNINSTALL" -> executeUninstall(payload)
            "EXECUTE_INSTALL" -> executeInstall(payload)
            "EXECUTE_PUSH_FILES" -> executePushFiles(payload)
            "EXECUTE_VERIFY_APK" -> executeVerifyApk(payload)
            "EXECUTE_VERIFY_DIR" -> executeVerifyDir(payload)
            "SET_STARTUP_APP" -> handleSetStartupApp(payload)
            "CLEAR_STARTUP_APP" -> handleClearStartupApp()
            "EXECUTE_SELF_UNINSTALL" -> executeSelfUninstall(payload)
            "EXECUTE_REBOOT" -> executePowerControl(
                PBS_DeviceControlEnum.DEVICE_CONTROL_REBOOT, "REBOOT_RESULT"
            )
            "EXECUTE_POWER_OFF" -> executePowerControl(
                PBS_DeviceControlEnum.DEVICE_CONTROL_SHUTDOWN, "POWER_OFF_RESULT"
            )
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

    /**
     * EXECUTE_UNINSTALL (#63): silently remove a content app the console pushed.
     *
     * Unlike the retire path this uninstalls someone *else's* package, so the
     * process survives whatever happens and the SDK callback always fires — the
     * result is reported from it exactly like a silent install, rather than
     * retire's "silence is success".
     *
     * The MDM client's own package and the guard are refused here as well as on
     * the server: removing either behind the console's back would leave the
     * device unmanaged, and the only sanctioned way out is the ordered retire
     * ceremony in [executeSelfUninstall].
     */
    private fun executeUninstall(payload: JSONObject) {
        val target = payload.optString("package_name", "").trim()
        if (target.isEmpty()) {
            Log.e(TAG, "EXECUTE_UNINSTALL missing package_name")
            sendUninstallResult(target, "fail", "Missing package_name")
            return
        }
        if (target == packageName || target == GuardLink.GUARD_PACKAGE) {
            Log.w(TAG, "Refusing to uninstall the STYLY-MDM package $target")
            sendUninstallResult(
                target, "fail",
                "$target belongs to STYLY-MDM itself; use Retire Device to remove the MDM client"
            )
            return
        }
        if (!uninstallsInFlight.add(target)) {
            Log.w(TAG, "Uninstall of $target already in flight; ignoring the duplicate")
            return
        }

        // Own thread: every step below is a binder call (PackageManager included)
        // and the WebSocket reader thread must stay free to carry every other
        // command and result.
        Thread {
            // Non-null once the startup app has been cleared for this uninstall,
            // holding what to put back if the uninstall then fails.
            var clearedStartup: Pair<String, String>? = null
            try {
                if (!isPackageInstalled(target)) {
                    finishUninstall(target, "fail", "Package not installed", null, false)
                    return@Thread
                }

                val binder = ToBServiceHelper.getInstance().serviceBinder
                if (binder == null) {
                    finishUninstall(target, "fail", "TobService not available", null, false)
                    return@Thread
                }

                // A package that is about to disappear must not stay the device's
                // startup app, or every boot would launch something missing. The
                // setting is cleared first and restored below if the uninstall
                // fails, so a failed operation changes nothing.
                val startupConfig = webSocketManager.getStartupAppConfig()
                if (startupConfig != null && startupConfig.first == target) {
                    clearStartupAppPrefs()
                    clearedStartup = startupConfig
                    Log.i(TAG, "Cleared the startup app before uninstalling $target")
                }

                Log.i(TAG, "Uninstalling package: $target")
                val done = CountDownLatch(1)
                binder.pbsControlAPPManger(
                    PBS_PackageControlEnum.PACKAGE_SILENCE_UNINSTALL,
                    target,
                    0,
                    object : IIntCallback.Stub() {
                        override fun callback(result: Int) {
                            Log.i(TAG, "pbsControlAPPManger uninstall result: $result for $target")
                            done.countDown()
                            val restore = clearedStartup
                            if (result != 0 && restore != null) {
                                setStartupAppPrefs(restore.first, restore.second)
                                Log.i(TAG, "Restored the startup app after a failed uninstall")
                            }
                            finishUninstall(
                                target,
                                if (result == 0) "success" else "fail",
                                if (result == 0) ""
                                else "pbsControlAPPManger returned $result: ${installResultMessage(result)}",
                                result,
                                restore != null && result == 0
                            )
                        }
                    }
                )
                // A callback that never comes would otherwise leave the package
                // blocked from every later attempt and the console waiting on a
                // result that cannot arrive. Time it out into an ordinary failure
                // — the package list is what the operator re-checks anyway.
                if (!done.await(UNINSTALL_CALLBACK_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "No uninstall callback for $target within ${UNINSTALL_CALLBACK_WAIT_MS}ms")
                    clearedStartup?.let { setStartupAppPrefs(it.first, it.second) }
                    finishUninstall(
                        target, "fail",
                        "No uninstall callback within ${UNINSTALL_CALLBACK_WAIT_MS / 1000}s",
                        null, false
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Uninstall of $target threw", e)
                clearedStartup?.let { setStartupAppPrefs(it.first, it.second) }
                finishUninstall(target, "fail", e.message ?: "Unknown error", null, false)
            }
        }.start()
    }

    /**
     * Report an uninstall exactly once. Leaving the in-flight set is the guard: a
     * callback that arrives just after the wait timed out finds the package gone
     * and stays quiet, so the console never sees two results for one command.
     */
    private fun finishUninstall(
        target: String,
        status: String,
        error: String,
        resultCode: Int?,
        startupAppCleared: Boolean
    ) {
        if (!uninstallsInFlight.remove(target)) {
            Log.i(TAG, "Uninstall of $target was already reported; dropping the late result")
            return
        }
        sendUninstallResult(target, status, error, resultCode, startupAppCleared)
    }

    private fun isPackageInstalled(target: String): Boolean {
        return try {
            packageManager.getPackageInfo(target, 0)
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Reboot or power off the device via the PICO advanced device-control API
     * (`pbsControlSetDeviceAction`), reachable on the same TobService binder the
     * silent-install path already uses and gated by the `pico_advance_interface`
     * manifest flag the client already declares.
     *
     * A successful reboot/shutdown tears down this process and the WebSocket
     * before any success frame could reach the server, so the client first
     * acknowledges receipt with a `*_RESULT: accepted` and flushes it to the wire
     * (on a worker thread) *before* invoking the SDK call. The real success
     * signal, server-side, is the device going offline (and, for reboot,
     * reconnecting). A `*_RESULT: fail` is only ever observed when the SDK
     * rejects the call — the device stays up, so that frame does flush.
     *
     * A PICO headset refuses a full power-off while a USB/charging cable is
     * connected unless the device-wide "power off with USB cable" setting is
     * enabled. Venue devices are typically on charge, so shutdown treats that
     * setting as a **precondition**: it is applied *before* the `accepted` ack,
     * and if it cannot be applied the client reports `fail` and does not proceed
     * — otherwise the console would show `accepted` while a cabled headset stays
     * online. Reboot is unaffected (it cycles regardless of charge) and keeps the
     * plain pre-ack model.
     */
    private fun executePowerControl(action: PBS_DeviceControlEnum, resultType: String) {
        Thread {
            try {
                val binder = ToBServiceHelper.getInstance().serviceBinder
                if (binder == null) {
                    Log.e(TAG, "TobService binder not available for $resultType")
                    sendPowerResult(resultType, "fail", "TobService not available")
                    return@Thread
                }
                // Shutdown precondition: allow power-off while charging BEFORE acking, so
                // a failure to apply it reports "fail" rather than a misleading "accepted"
                // on a cabled device that then stays online. The trailing int is the
                // standard TobService ext/process arg (0); the call returns void, so a
                // thrown RemoteException is the only failure signal.
                if (action == PBS_DeviceControlEnum.DEVICE_CONTROL_SHUTDOWN) {
                    try {
                        binder.pbsControlSetPowerOffwithUSBCable(PBS_SwitchEnum.S_ON, 0)
                        Log.i(TAG, "Enabled power-off-with-USB-cable before shutdown")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Could not allow power-off while charging; aborting shutdown", e)
                        sendPowerResult(
                            resultType, "fail",
                            "Could not enable power-off while charging: ${e.message ?: e.javaClass.simpleName}"
                        )
                        return@Thread
                    }
                }
                // Acknowledge (the precondition, if any, has now succeeded), then give the
                // ack a bounded chance to reach the wire before the reboot/shutdown takes
                // the connection down — sendMessage only enqueues.
                sendPowerResult(resultType, "accepted", "")
                webSocketManager.awaitOutboundFlush(2_000)
                Log.i(TAG, "Invoking pbsControlSetDeviceAction: $action")
                binder.pbsControlSetDeviceAction(action, object : IIntCallback.Stub() {
                    override fun callback(result: Int) {
                        // A callback running at all means the device did NOT power
                        // down (a real reboot/shutdown takes the process first), so a
                        // non-zero code is a rejection worth reporting. On 0 the
                        // action was accepted and the device is on its way down.
                        Log.i(TAG, "pbsControlSetDeviceAction result: $result for $action")
                        if (result != 0) {
                            sendPowerResult(
                                resultType, "fail",
                                "pbsControlSetDeviceAction returned $result"
                            )
                        }
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to run $resultType", e)
                sendPowerResult(resultType, "fail", e.message ?: "Unknown error")
            }
        }.start()
    }

    private fun sendPowerResult(resultType: String, status: String, error: String) {
        val result = JSONObject().apply {
            put("type", resultType)
            put("status", status)
            if (error.isNotEmpty()) {
                put("error", error)
            }
        }
        webSocketManager.sendMessage(result)
    }

    private fun executeInstall(payload: JSONObject) {
        val apkUrl = payload.optString("apk_url", "")
        val apkFilename = sanitizeApkFilename(payload.optString("apk_filename", ""))
        val expectedFullSha256 = payload.optString("full_sha256", "")

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
                val resultName = apkFilename.ifEmpty { downloadedApk.name }
                // Tell the server the network-heavy download is done so it can free
                // this device's transfer slot and dispatch the next queued device,
                // while the local (offline) hashing and install proceed below.
                sendDownloadComplete(task = "install", apkFilename = resultName)

                val archive = archiveIdentity(downloadedApk)
                val isSelfUpdate = archive != null && archive.packageName == packageName
                // The download travelled over plain LAN HTTP; this comparison against
                // the server-computed reference is its only integrity check.
                val actualSha256 =
                    if (expectedFullSha256.isNotEmpty()) fileSha256(downloadedApk) else null
                val refusal =
                    InstallPolicy.hashGateError(expectedFullSha256, actualSha256, isSelfUpdate)
                if (refusal != null) {
                    Log.e(TAG, "Refusing to install $resultName: $refusal")
                    UpdateJournal.record(
                        this,
                        UpdateJournal.EVENT_INSTALL_REFUSED,
                        "self_update=$isSelfUpdate file=$resultName reason=$refusal"
                    )
                    downloadedApk.delete()
                    sendInstallResult(resultName, "fail", refusal)
                    return@Thread
                }

                installApk(downloadedApk, resultName, archive, isSelfUpdate)
            } catch (e: Throwable) {
                // Throwable, not Exception: a self-update runs on this thread and can
                // hit linkage Errors from the ToBService SDK (e.g. NoClassDefFoundError
                // when a transitive dependency is missing). An uncaught Error here kills
                // the process before SELF_UPDATE_STARTING is ever sent, so the server
                // never learns the install failed. Catching it lets the failure surface.
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

    private fun installApk(
        apkFile: File,
        apkFilename: String,
        archive: ArchiveIdentity?,
        isSelfUpdate: Boolean,
    ) {
        val binder = ToBServiceHelper.getInstance().serviceBinder
        if (binder == null) {
            apkFile.delete()
            Log.e(TAG, "TobService binder not available")
            sendInstallResult(apkFilename, "fail", "TobService not available")
            return
        }

        // When the APK replaces our own package, Android kills this process and nothing
        // restarts it (measured: keep-alive does not survive, MY_PACKAGE_REPLACED is not
        // delivered on PICO). Recovery must therefore be armed *before* the installer
        // runs. Preferred: the guard app, a separate package the replace cannot kill,
        // which revives the client through TobService within seconds. Fallback: the
        // one-shot power-cycle timers (shutdown +2 min / startup +3 min) — kept for
        // devices where they work, though firmware whose SELinux denies the RTC alarm
        // HAL refuses them outright. Everything the replacement process needs is
        // committed to disk before the installer is invoked, and the server is told
        // last — ordering matters: if any earlier step fails, no state has leaked and
        // the install is simply refused: installing with no way back would strand the
        // device.
        if (isSelfUpdate) {
            val proxy = binder as? IToBServiceProxy
            if (proxy == null) {
                apkFile.delete()
                Log.e(TAG, "ToBService proxy unavailable; cannot arm self-update recovery")
                sendInstallResult(
                    apkFilename, "fail",
                    "ToBService proxy unavailable; self-update needs it for recovery"
                )
                return
            }
            val recovery: String
            if (GuardLink.ensureRunning(this, proxy, GUARD_START_WAIT_MS)) {
                recovery = "guard"
            } else {
                when (PowerCycleTimers.arm(this, proxy)) {
                    PowerCycleTimers.ArmOutcome.ARMED -> recovery = "power_cycle"
                    PowerCycleTimers.ArmOutcome.REFUSED_SAFE -> {
                        apkFile.delete()
                        sendInstallResult(
                            apkFilename, "fail",
                            "Self-update recovery unavailable: the guard app is not running " +
                                "and power-cycle scheduling failed; refusing an update with no way back"
                        )
                        return
                    }
                    PowerCycleTimers.ArmOutcome.REFUSED_UNSAFE -> {
                        apkFile.delete()
                        sendInstallResult(
                            apkFilename, "fail",
                            "UNSAFE: power-cycle scheduling failed and a shutdown timer may still be armed " +
                                "without a paired startup timer; the device may power off until it is manually restarted"
                        )
                        return
                    }
                }
            }
            val correlationId = UUID.randomUUID().toString()
            UpdateJournal.markSelfUpdateStarted(this, archive!!.versionCode, correlationId, recovery)
            sendSelfUpdateStarting(correlationId, archive.versionCode, apkFilename)
            // send() only enqueues; the installer is about to kill this process, so
            // give the announcement a bounded chance to reach the wire.
            webSocketManager.awaitOutboundFlush(2_000)
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
                        if (isSelfUpdate &&
                            UpdateJournal.powerCycleTimersSet(this@MdmClientService)
                        ) {
                            // The callback fired at all, which means the installer did NOT
                            // replace this process — a successful self-replace takes the
                            // process (and this binder) with it before any callback can run.
                            // So the process survived and the device must not power-cycle,
                            // regardless of the result code: disarm unconditionally rather
                            // than trusting result != 0. Gated on the persisted armed flag:
                            // on the guard recovery path no timers were ever opened, and a
                            // disarm of nothing would only journal noise.
                            PowerCycleTimers.disarm(
                                this@MdmClientService,
                                if (result == 0) "install_callback_survived" else "install_failed"
                            )
                        }
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
            if (isSelfUpdate && UpdateJournal.powerCycleTimersSet(this)) {
                PowerCycleTimers.disarm(this, "install_failed")
            }
            throw e
        }
    }

    /**
     * The client's last words before the installer kills it: tells the server to
     * treat the coming disconnect as an update in progress, not an outage, and
     * hands over the correlation id the re-registration will be matched against.
     */
    private fun sendSelfUpdateStarting(
        correlationId: String,
        targetVersionCode: Long,
        apkFilename: String,
    ) {
        webSocketManager.sendMessage(JSONObject().apply {
            put("type", "SELF_UPDATE_STARTING")
            put("correlation_id", correlationId)
            put("target_version_code", targetVersionCode)
            put("current_version_code", BuildConfig.VERSION_CODE)
            put("package_name", packageName)
            put("apk_filename", apkFilename)
        })
    }

    /**
     * EXECUTE_SELF_UNINSTALL (#49): remove the MDM client from the device for good.
     *
     * The venue-handover teardown, ordered so every step still makes sense when a
     * later one fails:
     *  1. Close guard provisioning (retireInProgress) and uninstall the guard first.
     *     Left behind, the guard would sit orphaned for up to its self-destruct grace
     *     after the client is gone, and could fire one futile revive into the kill
     *     window. Its outcome never blocks the retire: a surviving guard stands down
     *     and self-destructs on its own once the client package is missing.
     *  2. Deregister the ToBService keep-alive so nothing tries to resurrect a
     *     removed package.
     *  3. Announce SELF_UNINSTALL_STARTING and flush. The uninstall kills this
     *     process, so the announcement is the server's only signal — its silence
     *     afterwards is what the server reads as success.
     *  4. Invoke the self-uninstall. The callback can only ever report failure (a
     *     successful removal takes the process with it first); failure clears the
     *     flag so the next mutual-watch tick reinstalls the guard this retire
     *     already removed.
     *
     * Unlike a self-update, no recovery is armed and nothing is persisted: after a
     * successful retire nothing is supposed to come back.
     */
    private fun executeSelfUninstall(payload: JSONObject) {
        val correlationId = payload.optString("correlation_id")
        if (retireInProgress) {
            Log.w(TAG, "Retire already in progress; ignoring duplicate EXECUTE_SELF_UNINSTALL")
            return
        }
        retireInProgress = true
        UpdateJournal.record(
            this,
            UpdateJournal.EVENT_RETIRE_STARTED,
            "correlation_id=$correlationId"
        )
        // Own thread: the guard uninstall below blocks on its callback, and the
        // WebSocket reader thread must stay free to carry the announcement out.
        Thread {
            try {
                val binder = ToBServiceHelper.getInstance().serviceBinder
                if (binder == null) {
                    failRetire(correlationId, "ToBService not available", null)
                    return@Thread
                }

                if (GuardLink.isInstalled(this)) {
                    val done = CountDownLatch(1)
                    var guardResult = Int.MIN_VALUE
                    try {
                        binder.pbsControlAPPManger(
                            PBS_PackageControlEnum.PACKAGE_SILENCE_UNINSTALL,
                            GuardLink.GUARD_PACKAGE,
                            0,
                            object : IIntCallback.Stub() {
                                override fun callback(result: Int) {
                                    guardResult = result
                                    done.countDown()
                                }
                            }
                        )
                        done.await(GUARD_UNINSTALL_WAIT_MS, TimeUnit.MILLISECONDS)
                    } catch (e: Throwable) {
                        Log.w(TAG, "Guard uninstall threw; proceeding with the retire", e)
                    }
                    val outcome = when (guardResult) {
                        Int.MIN_VALUE -> "no callback within ${GUARD_UNINSTALL_WAIT_MS}ms"
                        0 -> "result=0"
                        else -> "result=$guardResult (${installResultMessage(guardResult)})"
                    }
                    Log.i(TAG, "Guard uninstall before retire: $outcome")
                    UpdateJournal.record(this, UpdateJournal.EVENT_RETIRE_GUARD_UNINSTALL, outcome)
                }

                try {
                    binder.pbsAppKeepAlive(packageName, false, 0)
                } catch (e: Throwable) {
                    Log.w(TAG, "Keep-alive deregistration failed; proceeding", e)
                }

                sendSelfUninstallStarting(correlationId)
                // send() only enqueues; the uninstall is about to kill this process,
                // so give the announcement a bounded chance to reach the wire.
                webSocketManager.awaitOutboundFlush(2_000)

                Log.i(TAG, "Uninstalling own package: $packageName")
                binder.pbsControlAPPManger(
                    PBS_PackageControlEnum.PACKAGE_SILENCE_UNINSTALL,
                    packageName,
                    0,
                    object : IIntCallback.Stub() {
                        override fun callback(result: Int) {
                            // A successful self-uninstall kills this process before the
                            // callback can run, so running at all means trouble.
                            if (result != 0) {
                                failRetire(
                                    correlationId,
                                    "pbsControlAPPManger returned $result: ${installResultMessage(result)}",
                                    result
                                )
                            } else {
                                // "Success" with a surviving process: death may simply
                                // lag the callback. If we are still here well past
                                // that, the uninstall did not happen — re-open guard
                                // provisioning; the server's still-connected check
                                // reports the failure on its side.
                                guardEnsure.postDelayed({
                                    if (retireInProgress) {
                                        Log.w(
                                            TAG,
                                            "Still alive after a 'successful' self-uninstall; treating the retire as failed"
                                        )
                                        restoreKeepAlive()
                                        retireInProgress = false
                                    }
                                }, GUARD_ENSURE_INTERVAL_MS)
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                failRetire(
                    correlationId,
                    e.message ?: "Self-uninstall threw ${e.javaClass.simpleName}",
                    null
                )
            }
        }.start()
    }

    /**
     * Terminal failure of a retire: report it and restore the recovery posture the
     * teardown dismantled — the guard (via the re-opened mutual-watch tick) and the
     * ToBService keep-alive it deregistered.
     */
    private fun failRetire(correlationId: String, detail: String, resultCode: Int?) {
        Log.e(TAG, "Self-uninstall failed: $detail")
        UpdateJournal.record(this, UpdateJournal.EVENT_RETIRE_FAILED, "detail=$detail")
        sendSelfUninstallResult(correlationId, detail, resultCode)
        restoreKeepAlive()
        // The guard was deliberately uninstalled first; clearing the flag lets the
        // next mutual-watch tick reinstall it, so a failed retire never leaves the
        // device unguarded.
        retireInProgress = false
    }

    /**
     * A failed retire leaves this process alive, so MdmClientApplication.onCreate
     * will not run again to re-register the keep-alive the teardown deregistered.
     * Re-register it here (best-effort; harmless when it was never deregistered)
     * so the device keeps the same ToBService recovery it had before the attempt.
     */
    private fun restoreKeepAlive() {
        try {
            ToBServiceHelper.getInstance().serviceBinder?.pbsAppKeepAlive(packageName, true, 0)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not re-register keep-alive after a failed retire", e)
        }
    }

    /**
     * The client's last words before the uninstall kills it: tells the server to
     * treat the coming disconnect as a retire in progress — permanent silence
     * afterwards is the success signal — echoing the correlation id the server
     * issued with EXECUTE_SELF_UNINSTALL.
     */
    private fun sendSelfUninstallStarting(correlationId: String) {
        webSocketManager.sendMessage(JSONObject().apply {
            put("type", "SELF_UNINSTALL_STARTING")
            put("correlation_id", correlationId)
            put("package_name", packageName)
            put("version_code", BuildConfig.VERSION_CODE)
        })
    }

    /**
     * Only ever a failure report: a successful self-uninstall leaves no process to
     * send anything.
     */
    private fun sendSelfUninstallResult(
        correlationId: String,
        detail: String,
        resultCode: Int?,
    ) {
        webSocketManager.sendMessage(JSONObject().apply {
            put("type", "SELF_UNINSTALL_RESULT")
            put("correlation_id", correlationId)
            put("status", "fail")
            put("detail", detail)
            if (resultCode != null) {
                put("result_code", resultCode)
            }
        })
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

        setStartupAppPrefs(packageName, extra)

        Log.i(TAG, "Startup app set: $packageName")
        sendStartupAppResult("success", "", packageName)
    }

    private fun handleClearStartupApp() {
        clearStartupAppPrefs()

        Log.i(TAG, "Startup app cleared")
        sendStartupAppResult("success", "")
    }

    /**
     * Persist the startup app without reporting it. Shared by SET_STARTUP_APP and
     * the uninstall path, which restores a startup app it cleared speculatively
     * and must not emit a STARTUP_APP_RESULT for a command nobody sent.
     */
    private fun setStartupAppPrefs(packageName: String, extra: String) {
        getSharedPreferences(WebSocketManager.PREF_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(WebSocketManager.PREF_STARTUP_APP_PACKAGE, packageName)
            .putString(WebSocketManager.PREF_STARTUP_APP_EXTRA, extra)
            .apply()
    }

    private fun clearStartupAppPrefs() {
        getSharedPreferences(WebSocketManager.PREF_NAME, android.content.Context.MODE_PRIVATE)
            .edit()
            .remove(WebSocketManager.PREF_STARTUP_APP_PACKAGE)
            .remove(WebSocketManager.PREF_STARTUP_APP_EXTRA)
            .apply()
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

    private fun sendUninstallResult(
        packageName: String,
        status: String,
        error: String,
        resultCode: Int? = null,
        startupAppCleared: Boolean = false
    ) {
        val result = JSONObject().apply {
            put("type", "DELETE_APP_RESULT")
            put("status", status)
            put("package_name", packageName)
            if (error.isNotEmpty()) {
                put("error", error)
            }
            if (resultCode != null) {
                put("result_code", resultCode)
            }
            if (startupAppCleared) {
                put("startup_app_cleared", true)
            }
        }
        webSocketManager.sendMessage(result)
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
