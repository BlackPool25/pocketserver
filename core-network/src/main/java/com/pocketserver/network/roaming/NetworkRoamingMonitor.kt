package com.pocketserver.network.roaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Types of physical or virtual network interfaces active on the device.
 */
enum class NetworkInterfaceType {
    WIFI,
    CELLULAR,
    ETHERNET,
    LOOPBACK,
    UNKNOWN
}

/**
 * Snapshot of current network interface configuration and IP address.
 */
data class NetworkState(
    val ipAddress: String,
    val interfaceType: NetworkInterfaceType,
    val isConnected: Boolean,
    val isMetered: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Listener for IP migration events (e.g. Wi-Fi <-> Cellular handover).
 */
fun interface IpMigrationListener {
    fun onIpMigrated(oldIp: String, newIp: String, state: NetworkState)
}

/**
 * Monitors device network interfaces via ConnectivityManager.NetworkCallback.
 * Detects real-time IP migrations (e.g., Wi-Fi roaming or Cellular handover),
 * dispatches notifications to mDNS and QR engines, and maintains current IP state.
 */
class NetworkRoamingMonitor(
    private val context: Context? = null
) {
    private val listeners = CopyOnWriteArrayList<IpMigrationListener>()

    private val _networkState = MutableStateFlow(
        NetworkState(
            ipAddress = "127.0.0.1",
            interfaceType = NetworkInterfaceType.LOOPBACK,
            isConnected = false,
            isMetered = false
        )
    )
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        // Query initial IP
        val initialIp = resolveCurrentLocalIp()
        val initialType = if (initialIp == "127.0.0.1") NetworkInterfaceType.LOOPBACK else NetworkInterfaceType.WIFI
        _networkState.value = NetworkState(
            ipAddress = initialIp,
            interfaceType = initialType,
            isConnected = initialIp != "127.0.0.1",
            isMetered = false
        )
    }

    /**
     * Registers an IP migration listener.
     */
    fun addListener(listener: IpMigrationListener) {
        listeners.add(listener)
    }

    /**
     * Removes an IP migration listener.
     */
    fun removeListener(listener: IpMigrationListener) {
        listeners.remove(listener)
    }

    /**
     * Starts monitoring connectivity changes using ConnectivityManager.
     */
    @Synchronized
    fun startMonitoring(customContext: Context? = context) {
        val ctx = customContext ?: return
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateNetwork(cm, network)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                updateFromLinkProperties(cm, network, linkProperties)
            }

            override fun onLost(network: Network) {
                handleNetworkLost()
            }
        }

        networkCallback = callback
        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            // Permission or security exception fallback
        }
    }

    /**
     * Manually simulates or injects an IP migration event (used in tests or manual overrides).
     */
    fun updateIpAddress(newIp: String, interfaceType: NetworkInterfaceType, isMetered: Boolean = false) {
        val oldState = _networkState.value
        val oldIp = oldState.ipAddress

        val newState = NetworkState(
            ipAddress = newIp,
            interfaceType = interfaceType,
            isConnected = newIp != "127.0.0.1",
            isMetered = isMetered
        )
        _networkState.value = newState

        if (oldIp != newIp) {
            for (listener in listeners) {
                listener.onIpMigrated(oldIp, newIp, newState)
            }
        }
    }

    private fun updateNetwork(cm: ConnectivityManager, network: Network) {
        val lp = cm.getLinkProperties(network) ?: return
        updateFromLinkProperties(cm, network, lp)
    }

    private fun updateFromLinkProperties(
        cm: ConnectivityManager,
        network: Network,
        linkProperties: LinkProperties
    ) {
        val caps = cm.getNetworkCapabilities(network)
        val interfaceType = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> NetworkInterfaceType.WIFI
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> NetworkInterfaceType.CELLULAR
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> NetworkInterfaceType.ETHERNET
            else -> NetworkInterfaceType.UNKNOWN
        }
        val isMetered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false

        val ipv4 = linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress ?: resolveCurrentLocalIp()

        updateIpAddress(ipv4, interfaceType, isMetered)
    }

    private fun handleNetworkLost() {
        val fallbackIp = resolveCurrentLocalIp()
        val type = if (fallbackIp == "127.0.0.1") NetworkInterfaceType.LOOPBACK else NetworkInterfaceType.UNKNOWN
        updateIpAddress(fallbackIp, type, isMetered = false)
    }

    /**
     * Stops monitoring connectivity changes.
     */
    @Synchronized
    fun stopMonitoring() {
        val cm = connectivityManager
        val callback = networkCallback
        if (cm != null && callback != null) {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (ignored: Exception) {}
        }
        networkCallback = null
        connectivityManager = null
    }

    /**
     * Resolves the current local IPv4 address across available hardware network interfaces.
     */
    fun resolveCurrentLocalIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            var candidateIp: String? = null

            for (networkInterface in interfaces) {
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val name = networkInterface.name.lowercase()
                val isWifi = name.startsWith("wlan") || name.startsWith("ap")
                val isEth = name.startsWith("eth")

                for (inetAddress in networkInterface.inetAddresses) {
                    if (inetAddress is Inet4Address && !inetAddress.isLoopbackAddress) {
                        val ip = inetAddress.hostAddress
                        if (isWifi || isEth) {
                            return ip ?: "127.0.0.1" // Prioritize Wi-Fi or Ethernet
                        }
                        if (candidateIp == null) {
                            candidateIp = ip
                        }
                    }
                }
            }
            return candidateIp ?: "127.0.0.1"
        } catch (e: Exception) {
            return "127.0.0.1"
        }
    }
}
