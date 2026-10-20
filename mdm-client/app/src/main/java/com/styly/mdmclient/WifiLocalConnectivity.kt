package com.styly.mdmclient

import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.content.pm.PackageManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

internal data class WifiLocalNetworkObservation(
    val networkHandle: Long,
    val ssid: String?,
    val hasUsableIp: Boolean,
    val sequence: Long,
)

internal sealed interface WifiLocalConnectivityEvent {
    val sequence: Long

    data class Updated(val observation: WifiLocalNetworkObservation) : WifiLocalConnectivityEvent {
        override val sequence: Long get() = observation.sequence
    }

    data class Lost(val networkHandle: Long, override val sequence: Long) : WifiLocalConnectivityEvent
}

internal interface WifiLocalConnectivitySession : AutoCloseable {
    fun snapshot(): List<WifiLocalNetworkObservation>
    fun lastSequence(): Long
}

internal interface WifiLocalConnectivitySource {
    /** Fail before touching configured networks when SSID observation cannot be trusted. */
    fun preflight(): WifiReason?
    fun observe(listener: (WifiLocalConnectivityEvent) -> Unit): WifiLocalConnectivitySession
}

/** Accessed only while the owning session lock is held. */
internal class WifiNetworkHandleTombstones {
    private val handles = HashSet<Long>()

    fun markLost(networkHandle: Long) {
        handles.add(networkHandle)
    }

    fun markAvailable(networkHandle: Long) {
        handles.remove(networkHandle)
    }

    fun isLost(networkHandle: Long): Boolean = networkHandle in handles
}

/** Tracks only Wi-Fi networks and derives SSID and addresses from each callback's arguments. */
internal class AndroidWifiLocalConnectivitySource(context: Context) : WifiLocalConnectivitySource {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    override fun preflight(): WifiReason? {
        if (appContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) return WifiReason.PERMISSION_DENIED
        if (locationManager?.isLocationEnabled != true) return WifiReason.PERMISSION_DENIED
        if (connectivityManager == null) return WifiReason.UNSUPPORTED
        return null
    }

    override fun observe(listener: (WifiLocalConnectivityEvent) -> Unit): WifiLocalConnectivitySession {
        val manager = connectivityManager ?: throw IllegalStateException("Connectivity service unavailable")
        val session = AndroidSession(manager, wifiManager, listener)
        try {
            session.register()
            return session
        } catch (failure: Exception) {
            session.close()
            throw failure
        }
    }

    private class AndroidSession(
        private val manager: ConnectivityManager,
        private val wifiManager: WifiManager?,
        private val listener: (WifiLocalConnectivityEvent) -> Unit,
    ) : WifiLocalConnectivitySession {
        private data class MutableObservation(
            var ssid: String? = null,
            var hasUsableIp: Boolean = false,
            var addresses: Set<java.net.InetAddress> = emptySet(),
            var sequence: Long = 0,
        )

        private val lock = Any()
        private val closed = AtomicBoolean(false)
        private val observations = LinkedHashMap<Long, MutableObservation>()
        // A seed read can race a callback delivered after registration. Retain lost handles
        // so a stale allNetworks snapshot cannot recreate an observation after onLost.
        private val lostNetworkHandles = WifiNetworkHandleTombstones()
        private val legacySsidExecutor = Executors.newSingleThreadExecutor()
        private var sequence = 0L

        private val callback: ConnectivityManager.NetworkCallback = if (networkCallbackNeedsLocationInfo(Build.VERSION.SDK_INT)) {
            LocationAwareWifiCallback(this)
        } else {
            WifiCallback(this)
        }

        private fun onAvailable(network: Network) = emitUpdated(network)

        private fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                networkCapabilities.transportInfo as? WifiInfo
            } else {
                null
            }
            val event = synchronized(lock) {
                if (lostNetworkHandles.isLost(network.networkHandle)) return
                val current = observations.getOrPut(network.networkHandle) { MutableObservation() }
                current.ssid = info?.ssid?.normalizeSsid()
                current.sequence = ++sequence
                WifiLocalConnectivityEvent.Updated(current.toObservation(network.networkHandle))
            }
            if (!closed.get()) listener(event)
        }

        private fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val addresses = linkProperties.linkAddresses.map { it.address }.toSet()
            val event = synchronized(lock) {
                if (lostNetworkHandles.isLost(network.networkHandle)) return
                val current = observations.getOrPut(network.networkHandle) { MutableObservation() }
                current.addresses = addresses
                current.hasUsableIp = addresses.any { it.isUsableLocalAddress() }
                current.sequence = ++sequence
                WifiLocalConnectivityEvent.Updated(current.toObservation(network.networkHandle))
            }
            if (!closed.get()) listener(event)
            if (!closed.get() && usesLegacyWifiSsidLookup(Build.VERSION.SDK_INT)) {
                try {
                    legacySsidExecutor.execute { updateLegacySsid(network.networkHandle) }
                } catch (_: RejectedExecutionException) {
                    // Completion may close the session after the callback's open check.
                }
            }
        }

        @Suppress("DEPRECATION")
        private fun updateLegacySsid(networkHandle: Long) {
            if (closed.get()) return
            val info = try {
                wifiManager?.connectionInfo
            } catch (_: SecurityException) {
                null
            } ?: return
            val address = info.ipAddress.toIpv4Address() ?: return
            val ssid = info.ssid?.normalizeSsid() ?: return
            val event = synchronized(lock) {
                // Before Android 12, WifiInfo is device-global. Correlate it only when
                // its IPv4 address identifies the sole observed Wi-Fi Network.
                val current = observations[networkHandle] ?: return
                if (!current.hasUsableIp ||
                    !legacyWifiSsidCanIdentifyNetwork(observations.size, address in current.addresses)
                ) return
                current.ssid = ssid
                current.sequence = ++sequence
                WifiLocalConnectivityEvent.Updated(current.toObservation(networkHandle))
            }
            if (!closed.get()) listener(event)
        }

        private fun onLost(network: Network) {
            val event = synchronized(lock) {
                lostNetworkHandles.markLost(network.networkHandle)
                observations.remove(network.networkHandle)
                WifiLocalConnectivityEvent.Lost(network.networkHandle, ++sequence)
            }
            if (!closed.get()) listener(event)
        }

        fun register() {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            manager.registerNetworkCallback(request, callback)
            seedExistingWifiNetworks()
        }

        private fun seedExistingWifiNetworks() {
            // A Wi-Fi network may be non-default while cellular or a VPN is the default.
            // Seed every existing physical Wi-Fi handle so delayed callback delivery cannot
            // make a pre-change association look fresh after the reconnect call.
            for (network in manager.allNetworks) {
                val capabilities = manager.getNetworkCapabilities(network) ?: continue
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) continue
                synchronized(lock) {
                    if (!lostNetworkHandles.isLost(network.networkHandle)) {
                        val current = observations.getOrPut(network.networkHandle) { MutableObservation() }
                        current.sequence = ++sequence
                    }
                }
            }
        }

        private fun emitUpdated(network: Network) {
            val event = synchronized(lock) {
                // A fresh availability notification permits a new observation for this handle.
                lostNetworkHandles.markAvailable(network.networkHandle)
                val current = observations.getOrPut(network.networkHandle) { MutableObservation() }
                current.sequence = ++sequence
                WifiLocalConnectivityEvent.Updated(current.toObservation(network.networkHandle))
            }
            if (!closed.get()) listener(event)
        }

        override fun snapshot(): List<WifiLocalNetworkObservation> = synchronized(lock) {
            observations.map { (handle, value) -> value.toObservation(handle) }
        }

        override fun lastSequence(): Long = synchronized(lock) { sequence }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            legacySsidExecutor.shutdownNow()
            try {
                manager.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
                // Registration may not have completed if the platform is shutting down.
            }
        }

        private fun MutableObservation.toObservation(handle: Long) = WifiLocalNetworkObservation(
            networkHandle = handle,
            ssid = ssid,
            hasUsableIp = hasUsableIp,
            sequence = sequence,
        )

        private class WifiCallback(private val session: AndroidSession) : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = session.onAvailable(network)
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                session.onCapabilitiesChanged(network, networkCapabilities)
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                session.onLinkPropertiesChanged(network, linkProperties)
            override fun onLost(network: Network) = session.onLost(network)
        }

        private class LocationAwareWifiCallback(private val session: AndroidSession) :
            ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) = session.onAvailable(network)
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                session.onCapabilitiesChanged(network, networkCapabilities)
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                session.onLinkPropertiesChanged(network, linkProperties)
            override fun onLost(network: Network) = session.onLost(network)
        }
    }
}

internal fun networkCallbackNeedsLocationInfo(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.S

internal fun usesLegacyWifiSsidLookup(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.S

internal fun legacyWifiSsidCanIdentifyNetwork(activeWifiNetworkCount: Int, ipv4Matches: Boolean): Boolean =
    activeWifiNetworkCount == 1 && ipv4Matches

private fun String.normalizeSsid(): String? = takeIf {
    it.isNotEmpty() && it != "<unknown ssid>" && it != "unknown ssid"
}?.removeSurrounding("\"")

private fun Int.toIpv4Address(): java.net.InetAddress? {
    if (this == 0) return null
    return try {
        java.net.InetAddress.getByAddress(
            byteArrayOf(toByte(), (this shr 8).toByte(), (this shr 16).toByte(), (this shr 24).toByte()),
        )
    } catch (_: Exception) {
        null
    }
}

private fun java.net.InetAddress.isUsableLocalAddress(): Boolean =
    !isAnyLocalAddress && !isLoopbackAddress && !isLinkLocalAddress && !isMulticastAddress
