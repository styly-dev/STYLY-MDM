package com.styly.mdmclient

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
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
        private const val DEFAULT_SERVER_URL = "ws://192.168.1.100:7070/ws/device"
        private const val INITIAL_RECONNECT_DELAY_MS = 1000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L
        private const val DISCOVERY_INTERVAL_MS = 15000L
        private const val DISCOVERY_FAILURE_THRESHOLD = 3
        private const val PING_INTERVAL_SECONDS = 15L

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
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reconnectToken = Object()

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
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                onStatusChanged(false, "Connection failed: ${t.message}")
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
                }, reconnectToken, android.os.SystemClock.uptimeMillis() + DISCOVERY_INTERVAL_MS)
            } else {
                // First few failures — retry saved URL with exponential backoff
                Log.i(TAG, "Reconnecting in ${reconnectDelay}ms")
                onStatusChanged(false, "Reconnecting in ${reconnectDelay / 1000}s...")

                reconnectHandler.postAtTime({
                    doConnect()
                }, reconnectToken, android.os.SystemClock.uptimeMillis() + reconnectDelay)

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
}
