package com.styly.mdmclient

import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean

internal data class WifiLocalNetworkObservation(
    val networkHandle: Long,
    val ssid: String?,
    val hasUsableIp: Boolean,
    val sequence: Long,
    val receivedElapsedMillis: Long,
    val security: String? = null,
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
    fun observe(listener: (WifiLocalConnectivitySession, WifiLocalConnectivityEvent) -> Unit): WifiLocalConnectivitySession
}

/** A synchronous seed can fill fields whose callbacks have not arrived yet. */
internal class WifiLocalObservationState {
    var sequence: Long = 0
    var receivedElapsedMillis: Long = 0
    var ssid: String? = null
        private set
    var security: String? = null
        private set
    var hasUsableIp: Boolean = false
        private set
    private var hasCapabilitiesCallback = false
    private var hasLinksCallback = false

    fun updateCapabilities(ssid: String?, security: String?) {
        this.ssid = ssid
        this.security = security
        hasCapabilitiesCallback = true
    }

    fun updateLinks(hasUsableIp: Boolean) {
        this.hasUsableIp = hasUsableIp
        hasLinksCallback = true
    }

    fun seed(ssid: String?, security: String?, hasUsableIp: Boolean) {
        if (!hasCapabilitiesCallback) {
            this.ssid = ssid
            this.security = security
        }
        if (!hasLinksCallback) this.hasUsableIp = hasUsableIp
    }
}

/** Tracks only Wi-Fi networks and derives SSID and addresses from each callback's arguments. */
internal class AndroidWifiLocalConnectivitySource(context: Context) : WifiLocalConnectivitySource {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override fun preflight(): WifiReason? {
        if (!hasManagedWifiLocationPermissions(appContext)) return WifiReason.PERMISSION_DENIED
        if (locationManager?.isLocationEnabled != true) return WifiReason.PERMISSION_DENIED
        if (connectivityManager == null) return WifiReason.UNSUPPORTED
        return null
    }

    override fun observe(
        listener: (WifiLocalConnectivitySession, WifiLocalConnectivityEvent) -> Unit,
    ): WifiLocalConnectivitySession {
        if (!supportsManagedWifi()) throw IllegalStateException("Managed Wi-Fi is unsupported")
        val manager = connectivityManager ?: throw IllegalStateException("Connectivity service unavailable")
        val session = AndroidSession(manager, listener)
        try {
            session.register()
            return session
        } catch (failure: Exception) {
            session.close()
            throw failure
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private class AndroidSession(
        private val manager: ConnectivityManager,
        private val listener: (WifiLocalConnectivitySession, WifiLocalConnectivityEvent) -> Unit,
    ) : WifiLocalConnectivitySession {
        private val lock = Any()
        private val closed = AtomicBoolean(false)
        private val observations = LinkedHashMap<Long, WifiLocalObservationState>()
        // Seed reads race callbacks only during registration. Later callbacks update
        // existing handles, so lost handles need no bookkeeping after the seed.
        private var lostDuringSeed: MutableSet<Long>? = mutableSetOf()
        private var sequence = 0L

        private val callback = LocationAwareWifiCallback(this)

        private fun onAvailable(network: Network) = updateObservation(network, available = true)

        private fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = updateObservation(network) {
            val info = networkCapabilities.transportInfo as? WifiInfo
            updateCapabilities(info?.ssid?.normalizeSsid(), info?.currentNetworkSecurity())
        }

        private fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = updateObservation(network) {
            updateLinks(linkProperties.linkAddresses.any { it.address.isUsableLocalAddress() })
        }

        private fun updateObservation(
            network: Network,
            available: Boolean = false,
            update: WifiLocalObservationState.() -> Unit = {},
        ) {
            val received = SystemClock.elapsedRealtime()
            val handle = network.networkHandle
            val event = synchronized(lock) {
                val current = if (available) {
                    lostDuringSeed?.remove(handle)
                    observations.getOrPut(handle, ::WifiLocalObservationState)
                } else observations[handle] ?: return
                current.update()
                current.sequence = ++sequence
                current.receivedElapsedMillis = received
                WifiLocalConnectivityEvent.Updated(current.toObservation(handle))
            }
            if (!closed.get()) listener(this, event)
        }

        private fun onLost(network: Network) {
            val event = synchronized(lock) {
                lostDuringSeed?.add(network.networkHandle)
                observations.remove(network.networkHandle)
                WifiLocalConnectivityEvent.Lost(network.networkHandle, ++sequence)
            }
            if (!closed.get()) listener(this, event)
        }

        fun register() {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            manager.registerNetworkCallback(request, callback)
            seedExistingWifiNetworks()
            synchronized(lock) { lostDuringSeed = null }
        }

        private fun seedExistingWifiNetworks() {
            // A Wi-Fi network may be non-default while cellular or a VPN is the default.
            // Seed every existing physical Wi-Fi handle so delayed callback delivery cannot
            // make a pre-change association look fresh after the reconnect call.
            for (network in manager.allNetworks) {
                val received = SystemClock.elapsedRealtime()
                val beforeRead = synchronized(lock) { observations[network.networkHandle]?.sequence }
                val capabilities = manager.getNetworkCapabilities(network) ?: continue
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                ) continue
                val info = capabilities.transportInfo as? WifiInfo
                val links = manager.getLinkProperties(network)
                synchronized(lock) {
                    // Never overwrite newer callback data with a synchronous seed read.
                    if (lostDuringSeed?.contains(network.networkHandle) != true &&
                        observations[network.networkHandle]?.sequence == beforeRead
                    ) {
                        val observation = observations.getOrPut(network.networkHandle, ::WifiLocalObservationState)
                        observation.seed(
                            info?.ssid?.normalizeSsid(),
                            info?.currentNetworkSecurity(),
                            links?.linkAddresses?.any { it.address.isUsableLocalAddress() } == true,
                        )
                        if (beforeRead == null) {
                            observation.sequence = ++sequence
                            observation.receivedElapsedMillis = received
                        }
                    }
                }
            }
        }

        override fun snapshot(): List<WifiLocalNetworkObservation> = synchronized(lock) {
            observations.map { (handle, value) -> value.toObservation(handle) }
        }

        override fun lastSequence(): Long = synchronized(lock) { sequence }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                manager.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
                // Registration may not have completed if the platform is shutting down.
            }
        }

        private fun WifiLocalObservationState.toObservation(handle: Long) = WifiLocalNetworkObservation(
            networkHandle = handle,
            ssid = ssid,
            hasUsableIp = hasUsableIp,
            sequence = sequence,
            receivedElapsedMillis = receivedElapsedMillis,
            security = security,
        )

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

private fun String.normalizeSsid(): String? = takeIf {
    it.isNotEmpty() && it != "<unknown ssid>" && it != "unknown ssid"
}?.removeSurrounding("\"")

@RequiresApi(Build.VERSION_CODES.S)
private fun WifiInfo.currentNetworkSecurity(): String? = when (currentSecurityType) {
    WifiInfo.SECURITY_TYPE_OPEN -> "OPEN"
    WifiInfo.SECURITY_TYPE_PSK -> "WPA_PSK"
    WifiInfo.SECURITY_TYPE_SAE -> "SAE"
    WifiInfo.SECURITY_TYPE_WEP -> "WEP"
    WifiInfo.SECURITY_TYPE_EAP,
    WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE,
    WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT -> "EAP"
    else -> null
}

private fun java.net.InetAddress.isUsableLocalAddress(): Boolean =
    !isAnyLocalAddress && !isLoopbackAddress && !isLinkLocalAddress && !isMulticastAddress
