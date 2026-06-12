package com.styly.mdmclient

import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Discovers STYLY-MDM servers on the local network using UDP broadcast.
 * Sends a discovery request to the broadcast address on port 7071
 * and waits for a JSON response containing the server's WebSocket URL.
 */
object ServerDiscovery {

    private const val TAG = "ServerDiscovery"
    private const val DISCOVERY_PORT = 7071
    private const val DISCOVERY_MESSAGE = "STYLYMDM_DISCOVER"
    private const val TIMEOUT_MS = 3000

    /**
     * Sends a UDP broadcast discovery request and returns the server's
     * WebSocket URL if found, or null if no server responds within the timeout.
     * Must be called from a background thread.
     */
    fun discover(): String? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = TIMEOUT_MS
            }

            // Send discovery broadcast
            val message = DISCOVERY_MESSAGE.toByteArray(Charsets.UTF_8)
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val packet = DatagramPacket(message, message.size, broadcastAddr, DISCOVERY_PORT)
            socket.send(packet)
            Log.i(TAG, "Discovery request sent to broadcast:$DISCOVERY_PORT")

            // Wait for response
            val buf = ByteArray(1024)
            val response = DatagramPacket(buf, buf.size)
            socket.receive(response)

            val responseText = String(response.data, 0, response.length, Charsets.UTF_8)
            Log.i(TAG, "Discovery response from ${response.address.hostAddress}: $responseText")

            val json = JSONObject(responseText)
            if (json.optString("service") == "stylymdm") {
                return json.optString("ws_url", null)
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.i(TAG, "Discovery timed out, no server found")
        } catch (e: Exception) {
            Log.e(TAG, "Discovery failed", e)
        } finally {
            socket?.close()
        }
        return null
    }
}
