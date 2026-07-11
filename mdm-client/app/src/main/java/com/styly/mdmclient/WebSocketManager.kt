package com.styly.mdmclient

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * Manages WebSocket connection to the STYLY-MDM server.
 * Handles auto-reconnect with exponential backoff, device registration,
 * and dispatching received commands.
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
        // Last-resort fallback used only when discovery fails and no URL is saved.
        // The port is flavor-aware (BuildConfig.DEFAULT_WS_PORT) so the dev build
        // never falls back to the production port and accidentally connects to a
        // production server. The IP is just a placeholder; discovery is the real path.
        val DEFAULT_SERVER_URL = "ws://192.168.1.100:${BuildConfig.DEFAULT_WS_PORT}/ws/device"
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
        private const val DISCOVERY_INTERVAL_MS = 15000L
        private const val DISCOVERY_FAILURE_THRESHOLD = 3
        private const val PING_INTERVAL_SECONDS = 15L
        private const val BATTERY_UPDATE_INTERVAL_MS = 5 * 60 * 1000L

        const val PREF_STARTUP_APP_PACKAGE = "startup_app_package"
        const val PREF_STARTUP_APP_EXTRA = "startup_app_extra"
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
    private var isRunning = false
    private var consecutiveFailures = 0
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnectToken = Object()
    private val batteryTelemetryToken = Object()

    fun getServerUrl(): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    fun setServerUrl(url: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_SERVER_URL, url).apply()
    }

    fun connect() {
        isRunning = true
        // If no URL has been explicitly saved, try auto-discovery first
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(PREF_SERVER_URL)) {
            onStatusChanged(false, "Discovering server...")
            Thread {
                val discovered = ServerDiscovery.discover()
                if (discovered != null && isValidWsUrl(discovered)) {
                    Log.i(TAG, "Auto-discovered server: $discovered")
                    setServerUrl(discovered)
                } else {
                    Log.i(TAG, "Auto-discovery failed, using default URL")
                }
                reconnectHandler.post { doConnect() }
            }.start()
        } else {
            doConnect()
        }
    }

    fun disconnect() {
        isRunning = false
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

    private fun doConnect() {
        if (!isRunning) return

        val url = getServerUrl()
        Log.i(TAG, "Connecting to $url")
        onStatusChanged(false, "Connecting to $url...")

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                reconnectHandler.post {
                    reconnectDelay = INITIAL_RECONNECT_DELAY_MS
                    consecutiveFailures = 0
                }
                onStatusChanged(true, "Connected")
                sendRegistration()
                startBatteryTelemetry()
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
                onStatusChanged(false, "Disconnected: $reason")
                stopBatteryTelemetry()
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                onStatusChanged(false, "Connection failed: ${t.message}")
                stopBatteryTelemetry()
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        // Post to main looper to ensure all reconnect state is accessed from a single thread
        reconnectHandler.post {
            if (!isRunning) return@post

            // Cancel any previously scheduled reconnect to prevent duplicate attempts
            reconnectHandler.removeCallbacksAndMessages(reconnectToken)

            consecutiveFailures++

            if (consecutiveFailures >= DISCOVERY_FAILURE_THRESHOLD) {
                // Saved URL isn't working — switch to discovery mode.
                // Once in this branch, discovery runs on every reconnect cycle
                // (every DISCOVERY_INTERVAL_MS) until a connection succeeds.
                Log.i(TAG, "Connection failed $consecutiveFailures times, attempting server discovery")
                onStatusChanged(false, "Discovering server...")

                reconnectHandler.postAtTime({
                    Thread {
                        val discovered = ServerDiscovery.discover()
                        reconnectHandler.post {
                            if (discovered != null && isValidWsUrl(discovered)) {
                                Log.i(TAG, "Discovered server at: $discovered")
                                setServerUrl(discovered)
                                consecutiveFailures = 0
                                reconnectDelay = INITIAL_RECONNECT_DELAY_MS
                            } else {
                                Log.i(TAG, "Server discovery failed, will retry")
                            }
                            doConnect()
                        }
                    }.start()
                }, reconnectToken, SystemClock.uptimeMillis() + DISCOVERY_INTERVAL_MS)
            } else {
                // First few failures — retry saved URL with exponential backoff
                Log.i(TAG, "Reconnecting in ${reconnectDelay}ms")
                onStatusChanged(false, "Reconnecting in ${reconnectDelay / 1000}s...")

                reconnectHandler.postAtTime({
                    doConnect()
                }, reconnectToken, SystemClock.uptimeMillis() + reconnectDelay)

                reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            }
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

    private fun isValidWsUrl(url: String): Boolean {
        return url.isNotBlank() && (url.startsWith("ws://") || url.startsWith("wss://"))
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
