package com.styly.mdmguard

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.pvr.tobservice.ToBServiceHelper
import com.pvr.tobservice.enums.PBS_PackageControlEnum
import com.pvr.tobservice.interfaces.IIntCallback
import com.pvr.tobservice.interfaces.IToBServiceProxy

/**
 * Watches the mdm-client and starts it back up when it is not running.
 *
 * A self-update replaces the client's own package: the installer kills its process
 * and nothing on PICO restarts it — keep-alive does not survive the replace and
 * MY_PACKAGE_REPLACED is never delivered (measured for #39). The scheduled
 * power-cycle fallback is dead on current firmware (SELinux denies the poweroffalarm
 * app the RTC alarm HAL). This service lives in a separate package, so the client's
 * self-replace cannot take it down, and it restarts the client through the
 * privileged TobService launch APIs, which are exempt from the background-start
 * restrictions that apply to a plain startForegroundService() from here.
 *
 * The guard's lifecycle is coupled to the client's: it has no launcher entry, so when
 * the client is *uninstalled* (package missing — a replace never reads as missing) the
 * guard first stands down and then, once SELF_DESTRUCT_GRACE_MS of absence
 * accumulates, silently uninstalls itself rather than squatting invisibly on the
 * device. The absence clock is persisted (wall clock) and a self-chaining deadman
 * alarm restarts this service periodically, so the destruct fires even when this
 * process dies or the device sleeps before the grace elapses — after the client's
 * uninstall nothing else can bring the guard up to clean itself off.
 *
 * Diagnostics: every state change and revival attempt is logged under the
 * GuardService tag, and specific revival methods can be forced over adb:
 *   adb shell am start-foreground-service -n com.styly.mdmguard/.GuardService -e cmd revive_fgs
 * with cmd one of: check, revive_fgs, revive_service, revive_startapp,
 * revive_keepalive, revive_local. (Forcing a start of the client is nothing another
 * app could not already do — MdmClientService is exported — so this surface grants
 * no new capability; there is deliberately no command that pauses the watch.)
 */
class GuardService : Service() {

    companion object {
        private const val TAG = "GuardService"
        // Overridable at build time so the self-destruct path is testable (see build.gradle).
        private val CLIENT_PACKAGE = BuildConfig.CLIENT_PACKAGE
        private val CLIENT_SERVICE = "${BuildConfig.CLIENT_PACKAGE}.MdmClientService"
        // Key the client's onStartCommand reads; shows up in its journal/log as reason=guard.
        private const val CLIENT_EXTRA_START_REASON = "start_reason"
        private const val START_REASON_GUARD = "guard"

        private const val CHANNEL_ID = "guard"
        private const val NOTIFICATION_ID = 1

        private const val PREFS = "guard_prefs"
        // Wall-clock time the client's package was first seen missing; persisted so
        // the absence accumulates across process deaths and reboots instead of
        // resetting with every restart.
        private const val PREF_CLIENT_MISSING_SINCE = "client_missing_since"
        private const val DEADMAN_REQUEST_CODE = 1

        private const val CHECK_INTERVAL_MS = 10_000L
        // A revived client needs time to come up before a second attempt is warranted;
        // also keeps a broken revival path from being hammered every tick.
        private const val REVIVE_COOLDOWN_MS = 30_000L
    }

    private lateinit var watchdogThread: HandlerThread
    private lateinit var watchdog: Handler

    @Volatile private var watching = true
    private var lastReviveAt = 0L

    private val proxy: IToBServiceProxy?
        get() = ToBServiceHelper.getInstance().serviceBinder as? IToBServiceProxy

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "GuardService created")
        startForeground(NOTIFICATION_ID, buildNotification())
        bindTobService()
        watchdogThread = HandlerThread("guard-watchdog").apply { start() }
        watchdog = Handler(watchdogThread.looper)
        watchdog.postDelayed(::watchdogTick, CHECK_INTERVAL_MS)
        armDeadman()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cmd = intent?.getStringExtra("cmd") ?: "watch"
        Log.i(TAG, "onStartCommand cmd=$cmd flags=$flags")
        armDeadman()
        when (cmd) {
            "watch", "deadman" -> Unit // watchdog runs since onCreate
            "check" -> watchdog.post { logLiveness() }
            "revive_fgs", "revive_service", "revive_startapp", "revive_keepalive",
            "revive_local" -> watchdog.post { revive(cmd) }
            else -> Log.w(TAG, "Unknown cmd: $cmd")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "GuardService destroyed")
        watchdogThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun bindTobService() {
        try {
            ToBServiceHelper.getInstance().bindTobService(this) { status ->
                Log.i(TAG, "TobService bind status: $status")
                if (status) {
                    try {
                        ToBServiceHelper.getInstance().serviceBinder
                            ?.pbsAppKeepAlive(packageName, true, 0)
                        Log.i(TAG, "Guard keep-alive registered")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to register guard keep-alive", e)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to bind TobService", e)
        }
    }

    private fun watchdogTick() {
        try {
            if (watching) {
                if (!isClientInstalled()) {
                    // A missing package is a deliberate uninstall, not a crash: stand
                    // down, and once the grace passes uninstall ourselves — nothing
                    // else would ever clean up a guard with no launcher entry. The
                    // absence clock is persisted wall-clock time, so it accumulates
                    // across process deaths and reboots; a clock that jumped backwards
                    // is rebased rather than trusted.
                    val now = System.currentTimeMillis()
                    val since = prefs().getLong(PREF_CLIENT_MISSING_SINCE, 0L)
                    if (since == 0L || now < since) {
                        prefs().edit().putLong(PREF_CLIENT_MISSING_SINCE, now).commit()
                        Log.w(
                            TAG,
                            "Client package not installed; standing down " +
                                "(self-destruct in ${BuildConfig.SELF_DESTRUCT_GRACE_MS / 1000}s " +
                                "unless it comes back)"
                        )
                    } else if (now - since >= BuildConfig.SELF_DESTRUCT_GRACE_MS) {
                        selfDestruct()
                    }
                } else {
                    if (prefs().getLong(PREF_CLIENT_MISSING_SINCE, 0L) != 0L) {
                        prefs().edit().remove(PREF_CLIENT_MISSING_SINCE).commit()
                    }
                    val alive = isClientAlive()
                    if (alive == false) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReviveAt >= REVIVE_COOLDOWN_MS) {
                            Log.w(TAG, "Client is DOWN; attempting revival")
                            lastReviveAt = now
                            revive("revive_fgs")
                        } else {
                            Log.i(TAG, "Client is DOWN; within revive cooldown, waiting")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Watchdog tick failed", e)
        } finally {
            watchdog.postDelayed(::watchdogTick, CHECK_INTERVAL_MS)
        }
    }

    /** true/false when TobService answered, null when liveness is unknowable right now. */
    private fun isClientAlive(): Boolean? {
        val p = proxy
        if (p == null) {
            Log.w(TAG, "Liveness UNKNOWN: TobService proxy not bound")
            return null
        }
        return try {
            val processes = p.runningAppProcesses
            if (processes == null) {
                Log.w(TAG, "Liveness UNKNOWN: getRunningAppProcesses returned null")
                null
            } else {
                processes.any { it.processName == CLIENT_PACKAGE }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Liveness UNKNOWN: getRunningAppProcesses threw", e)
            null
        }
    }

    private fun logLiveness() {
        Log.i(TAG, "Liveness check: client alive=${isClientAlive()}")
    }

    private fun isClientInstalled(): Boolean = try {
        packageManager.getPackageInfo(CLIENT_PACKAGE, 0)
        true
    } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        false
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    /**
     * (Re)arms the self-chaining deadman alarm. The watchdog's Handler ticks run only
     * while this process is alive and the device is awake; the alarm survives both —
     * an ordinary process death does not cancel alarms (only force-stop or uninstall
     * does) and the WAKEUP variant fires through sleep — and its delivery restarts
     * this service (see DeadmanReceiver), which re-arms the next link here. After the
     * client's uninstall this chain is the only thing left that can bring the guard
     * up to finish its self-destruct: the client, its usual resurrector, is gone.
     * Uninstalling the guard removes its alarms with it, so the chain cannot outlive
     * the package.
     */
    private fun armDeadman() {
        try {
            val alarmManager = getSystemService(AlarmManager::class.java)
            val pending = PendingIntent.getBroadcast(
                this,
                DEADMAN_REQUEST_CODE,
                Intent(this, DeadmanReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + BuildConfig.DEADMAN_INTERVAL_MS,
                pending
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to arm the deadman alarm", e)
        }
    }

    /**
     * Uninstalls this guard through TobService's silent uninstall. The guard exists
     * only to serve the client: with the client gone past the grace period, an
     * invisible package with no launcher entry would otherwise squat on the device
     * forever. The uninstall kills this process, so a callback only ever reports
     * failure; on failure the grace clock restarts rather than hammering every tick.
     */
    private fun selfDestruct() {
        Log.w(TAG, "Client gone for ${BuildConfig.SELF_DESTRUCT_GRACE_MS / 1000}s; uninstalling self")
        val p = proxy
        if (p == null) {
            Log.e(TAG, "Cannot self-destruct: TobService proxy not bound; will retry")
            prefs().edit().putLong(PREF_CLIENT_MISSING_SINCE, System.currentTimeMillis()).commit()
            return
        }
        try {
            p.pbsControlAPPManger(
                PBS_PackageControlEnum.PACKAGE_SILENCE_UNINSTALL,
                packageName,
                0,
                object : IIntCallback.Stub() {
                    override fun callback(result: Int) {
                        Log.e(TAG, "Self-uninstall did not remove the process; result=$result")
                    }
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Self-uninstall threw; will retry after another grace period", e)
        }
        // Reached only if the uninstall did not (yet) kill us: restart the clock so a
        // rejected uninstall retries once per grace period instead of every tick.
        prefs().edit().putLong(PREF_CLIENT_MISSING_SINCE, System.currentTimeMillis()).commit()
    }

    private fun clientServiceIntent(): Intent = Intent()
        .setClassName(CLIENT_PACKAGE, CLIENT_SERVICE)
        .putExtra(CLIENT_EXTRA_START_REASON, START_REASON_GUARD)

    /**
     * One revival attempt via the named method. Everything is caught and logged:
     * the spike's job is to report which methods work, not to die trying.
     */
    private fun revive(method: String) {
        val p = proxy
        if (p == null && method != "revive_local") {
            Log.e(TAG, "Cannot revive via $method: TobService proxy not bound")
            return
        }
        Log.i(TAG, "Reviving client via $method")
        try {
            when (method) {
                "revive_fgs" -> {
                    val result = p!!.startForegroundService(clientServiceIntent())
                    Log.i(TAG, "startForegroundService result: $result")
                }
                "revive_service" -> {
                    val result = p!!.startService(clientServiceIntent())
                    Log.i(TAG, "startService result: $result")
                }
                "revive_startapp" -> {
                    val launch = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(CLIENT_PACKAGE)
                    val result = p!!.startApp(0, launch)
                    Log.i(TAG, "startApp result: $result")
                }
                "revive_keepalive" -> {
                    p!!.pbsAppKeepAlive(CLIENT_PACKAGE, true, 0)
                    Log.i(TAG, "pbsAppKeepAlive($CLIENT_PACKAGE) issued")
                }
                "revive_local" -> {
                    // Baseline: a plain cross-app start from this (foreground) service,
                    // to measure what the platform allows without TobService.
                    val result = startForegroundService(clientServiceIntent())
                    Log.i(TAG, "local startForegroundService result: $result")
                }
                else -> Log.w(TAG, "Unknown revival method: $method")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Revival via $method threw", e)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Guard", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("STYLY-MDM Guard")
            .setContentText("Watching the MDM client")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .build()
    }
}
