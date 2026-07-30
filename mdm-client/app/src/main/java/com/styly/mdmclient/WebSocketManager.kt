package com.styly.mdmclient

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.pvr.tobservice.ToBServiceHelper
import com.pvr.tobservice.enums.PBS_SystemInfoEnum
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * Manages WebSocket connection to the STYLY-MDM server: device registration,
 * dispatching received commands, and the bounded connection window (#67).
 *
 * Connection attempts are allowed only inside a window that opens on each
 * network-available transition (which also covers process restart) and on an
 * established connection dropping. A manually configured URL is attempted first;
 * subsequent attempts use UDP discovery, retrying on a fixed interval. When the
 * window expires without a connection the client goes fully
 * silent until the next network transition. Window duration and retry interval
 * come from [ClientConfig] (defaults overridable via /sdcard/styly-mdm/config.json).
 * The state logic lives in [ConnectionScheduler]; all of it runs on the main
 * looper via [reconnectHandler].
 */
class WebSocketManager(
    private val context: Context,
    private val onCommand: (type: String, payload: JSONObject) -> Unit,
    private val onStatusChanged: (connected: Boolean, message: String) -> Unit
) {

    companion object {
        private const val TAG = "WebSocketManager"
        const val PREF_NAME = "stylymdm_prefs"
        private const val PREF_SERVER_URL = "server_url"
        // Kept separate from PREF_SERVER_URL, which is only a discovery cache.
        private const val PREF_MANUAL_SERVER_URL = "manual_server_url_value"
        // Last-resort fallback used only when discovery fails and no URL is saved.
        // The port is flavor-aware (BuildConfig.DEFAULT_WS_PORT) so the dev build
        // never falls back to the production port and accidentally connects to a
        // production server. The IP is just a placeholder; discovery is the real path.
        val DEFAULT_SERVER_URL = "ws://192.168.1.100:${BuildConfig.DEFAULT_WS_PORT}/ws/device"
        // Short enough that a single black-holed connect attempt cannot consume
        // the whole connection window (OkHttp's default would be 10 s).
        private const val CONNECT_TIMEOUT_SECONDS = 3L
        private const val PING_INTERVAL_SECONDS = 15L
        private const val BATTERY_UPDATE_INTERVAL_MS = 5 * 60 * 1000L

        const val PREF_STARTUP_APP_PACKAGE = "startup_app_package"
        const val PREF_STARTUP_APP_EXTRA = "startup_app_extra"

        internal fun saveManualServerUrl(context: Context, url: String): Boolean {
            val normalized = url.trim()
            if (!isValidWsUrl(normalized)) return false
            preferences(context)
                .edit()
                .putString(PREF_MANUAL_SERVER_URL, normalized)
                .apply()
            return true
        }

        internal fun clearManualServerUrl(context: Context) {
            preferences(context).edit().remove(PREF_MANUAL_SERVER_URL).apply()
        }

        internal fun getManualServerUrl(context: Context): String? {
            return preferences(context)
                .getString(PREF_MANUAL_SERVER_URL, null)
                ?.takeIf(::isValidWsUrl)
        }

        private fun getCachedServerUrl(context: Context): String {
            return preferences(context)
                .getString(PREF_SERVER_URL, null)
                ?.takeIf(::isValidWsUrl)
                ?: DEFAULT_SERVER_URL
        }

        private fun preferences(context: Context) =
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        private fun isValidWsUrl(url: String): Boolean {
            if (!url.startsWith("ws://") && !url.startsWith("wss://")) return false
            return try {
                // OkHttp accepts WebSocket URLs here by normalizing ws/wss to http/https.
                Request.Builder().url(url).build()
                true
            } catch (_: IllegalArgumentException) {
                false
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isRunning = false
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectToken = Object()
    private val batteryTelemetryToken = Object()

    private val scheduler = ConnectionScheduler {
        ClientConfig.load(File(Environment.getExternalStorageDirectory(), ClientConfig.CONFIG_RELATIVE_PATH))
    }

    // Invalidates the in-flight discovery thread when its attempt is cancelled.
    private var attemptGeneration = 0
    private val manualUrlAttempt = ManualServerUrlAttempt()

    // registerDefaultNetworkCallback can report the new network's onAvailable
    // before the old network's onLost when the default network switches; only
    // the loss of the network we currently consider active may close the window.
    private var activeNetwork: Network? = null
    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            reconnectHandler.post {
                if (!isRunning) return@post
                activeNetwork = network
                dispatch(scheduler.onNetworkAvailable(SystemClock.uptimeMillis()))
            }
        }

        override fun onLost(network: Network) {
            reconnectHandler.post {
                if (!isRunning || network != activeNetwork) return@post
                activeNetwork = null
                onStatusChanged(false, "Waiting for network...")
                dispatch(scheduler.onNetworkLost())
            }
        }
    }

    private fun saveDiscoveredServerUrl(url: String) {
        preferences(context).edit().putString(PREF_SERVER_URL, url).apply()
    }

    fun connect() {
        isRunning = true
        onStatusChanged(false, "Waiting for network...")
        // The connection window is anchored on network availability, not on
        // service start: the callback fires immediately when a network is
        // already up, and again on every later network-available transition.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(networkCallback)
        networkCallbackRegistered = true
    }

    fun disconnect() {
        isRunning = false
        if (networkCallbackRegistered) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
            networkCallbackRegistered = false
        }
        reconnectHandler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "Client disconnecting")
        webSocket = null
    }

    fun sendMessage(json: JSONObject) {
        val text = json.toString()
        Log.d(TAG, "Sending: $text")
        webSocket?.send(text)
    }

    /**
     * Best-effort wait for the outbound queue to drain. OkHttp's send() only
     * enqueues; a self-updating client is about to be killed by the installer,
     * so its last message needs a bounded chance to reach the wire first. Call
     * from a worker thread only.
     */
    fun awaitOutboundFlush(timeoutMillis: Long) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val ws = webSocket ?: return
            if (ws.queueSize() == 0L) return
            Thread.sleep(50)
        }
    }

    /** Executes the side effects the scheduler decided on. Main looper only. */
    private fun dispatch(actions: List<ConnectionScheduler.Action>) {
        for (action in actions) {
            when (action) {
                is ConnectionScheduler.Action.WindowOpened ->
                    manualUrlAttempt.onWindowOpened(getManualServerUrl(context))
                is ConnectionScheduler.Action.StartAttempt -> startAttempt()
                is ConnectionScheduler.Action.ScheduleRetry -> {
                    onStatusChanged(false, "Reconnecting in ${action.delayMs / 1000}s...")
                    reconnectHandler.postAtTime(
                        { dispatch(scheduler.onRetryElapsed()) },
                        reconnectToken,
                        SystemClock.uptimeMillis() + action.delayMs
                    )
                }
                is ConnectionScheduler.Action.ScheduleWindowExpiry -> {
                    reconnectHandler.postAtTime(
                        { dispatch(scheduler.onWindowExpired()) },
                        reconnectToken,
                        SystemClock.uptimeMillis() + action.delayMs
                    )
                }
                is ConnectionScheduler.Action.CancelTimers ->
                    reconnectHandler.removeCallbacksAndMessages(reconnectToken)
                is ConnectionScheduler.Action.CancelAttempt -> cancelAttempt()
                is ConnectionScheduler.Action.EnterSilence -> {
                    Log.i(TAG, "No server found within the connection window, going silent")
                    onStatusChanged(false, "Standby (no server found)")
                }
            }
        }
    }

    /** Attempts a manual URL once, then falls back to UDP discovery within the window. */
    private fun startAttempt() {
        val manualUrl = manualUrlAttempt.take()
        if (manualUrl != null) {
            doConnect(manualUrl)
            return
        }

        val generation = ++attemptGeneration
        onStatusChanged(false, "Discovering server...")
        Thread {
            val discovered = ServerDiscovery.discover()
            reconnectHandler.post {
                if (!isRunning || generation != attemptGeneration ||
                    scheduler.state != ConnectionScheduler.State.WINDOW_OPEN
                ) return@post
                if (discovered != null && isValidWsUrl(discovered)) {
                    Log.i(TAG, "Discovered server at: $discovered")
                    saveDiscoveredServerUrl(discovered)
                    doConnect(discovered)
                } else {
                    doConnect(getCachedServerUrl(context))
                }
            }
        }.start()
    }

    private fun cancelAttempt() {
        attemptGeneration++
        // cancel() aborts without a close handshake — the window expired or the
        // network vanished, so there is nothing to say goodbye to.
        webSocket?.cancel()
        webSocket = null
    }

    private fun doConnect(url: String) {
        if (!isRunning) return

        Log.i(TAG, "Connecting to $url")
        onStatusChanged(false, "Connecting to $url...")

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                reconnectHandler.post {
                    if (this@WebSocketManager.webSocket !== webSocket) return@post
                    dispatch(scheduler.onConnected())
                    onStatusChanged(true, "Connected")
                    sendRegistration()
                    startBatteryTelemetry()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")
                    if (type.isNotEmpty()) {
                        onCommand(type, json)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse message", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code $reason")
                onSocketGone(webSocket, "Disconnected: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                onSocketGone(webSocket, "Connection failed: ${t.message}")
            }
        })
    }

    /** Routes a dead socket into the scheduler, ignoring cancelled/stale sockets. */
    private fun onSocketGone(deadSocket: WebSocket, message: String) {
        reconnectHandler.post {
            if (webSocket !== deadSocket) return@post
            webSocket = null
            stopBatteryTelemetry()
            onStatusChanged(false, message)
            dispatch(scheduler.onSocketDisconnected(SystemClock.uptimeMillis()))
        }
    }

    /**
     * Returns the startup app config from SharedPreferences, or null if not set.
     */
    fun getStartupAppConfig(): Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val pkg = prefs.getString(PREF_STARTUP_APP_PACKAGE, null) ?: return null
        val extra = prefs.getString(PREF_STARTUP_APP_EXTRA, "") ?: ""
        return Pair(pkg, extra)
    }

    private fun sendRegistration() {
        val registration = JSONObject().apply {
            put("type", "REGISTER")
            put("device_id", getDeviceSerialNumber())
            put("model", Build.MODEL)
            put("ip", getDeviceIpAddress())
            // Lets the server confirm which build re-registered after a self-update (#39).
            put("version_code", BuildConfig.VERSION_CODE)
            put("version_name", BuildConfig.VERSION_NAME)

            val startupConfig = getStartupAppConfig()
            if (startupConfig != null) {
                put("startup_app", JSONObject().apply {
                    put("package_name", startupConfig.first)
                    put("extra", startupConfig.second)
                })
            } else {
                put("startup_app", JSONObject.NULL)
            }
        }
        sendMessage(registration)
    }

    private fun startBatteryTelemetry() {
        stopBatteryTelemetry()
        sendBatteryUpdate()
        scheduleNextBatteryUpdate()
    }

    private fun stopBatteryTelemetry() {
        reconnectHandler.removeCallbacksAndMessages(batteryTelemetryToken)
    }

    private fun scheduleNextBatteryUpdate() {
        reconnectHandler.postAtTime({
            if (isRunning && webSocket != null) {
                sendBatteryUpdate()
                scheduleNextBatteryUpdate()
            }
        }, batteryTelemetryToken, SystemClock.uptimeMillis() + BATTERY_UPDATE_INTERVAL_MS)
    }

    private fun sendBatteryUpdate() {
        val snapshot = readBatterySnapshot()
        if (snapshot == null) {
            Log.w(TAG, "Battery state is unavailable")
            return
        }
        val update = JSONObject().apply {
            put("type", "BATTERY_UPDATE")
            put("device_id", getDeviceSerialNumber())
            put("level", snapshot.level)
            put("charging", snapshot.charging)
            put("timestamp", System.currentTimeMillis() / 1000)
        }
        sendMessage(update)
    }

    private fun readBatterySnapshot(): BatterySnapshot? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            return null
        }
        val percent = ((level * 100f) / scale).toInt().coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return BatterySnapshot(percent, charging)
    }

    private fun getDeviceSerialNumber(): String {
        return try {
            val binder = ToBServiceHelper.getInstance().serviceBinder
            binder?.pbsStateGetDeviceInfo(PBS_SystemInfoEnum.EQUIPMENT_SN, 0) ?: Build.SERIAL
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get serial number from TobService", e)
            Build.SERIAL
        }
    }

    private fun getDeviceIpAddress(): String {
        try {
            // Try WifiManager first
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            if (wifiInfo != null && wifiInfo.ipAddress != 0) {
                val ip = wifiInfo.ipAddress
                return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
            }

            // Fallback to NetworkInterface enumeration
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IP address", e)
        }
        return "0.0.0.0"
    }

    private data class BatterySnapshot(
        val level: Int,
        val charging: Boolean
    )
}
