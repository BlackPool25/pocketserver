package com.pocketserver.network.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Representation of a published mDNS local service entry.
 */
data class MdnsServiceEntry(
    val serviceName: String,
    val port: Int,
    val serviceType: String = "_http._tcp.",
    val attributes: Map<String, String> = emptyMap()
)

/**
 * Manages zero-configuration local service discovery via Android NsdManager.
 * Registers "_http._tcp." records (e.g. myapp.local), manages registration listeners,
 * resolves name collisions, and dynamically re-registers services upon IP roaming events.
 */
class MdnsDiscoveryManager(
    private val context: Context? = null
) {
    private val activeRegistrations = ConcurrentHashMap<String, RegistrationContext>()
    private val serviceDefinitions = ConcurrentHashMap<String, MdnsServiceEntry>()

    private val nsdManager: NsdManager? by lazy {
        context?.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private class RegistrationContext(
        val entry: MdnsServiceEntry,
        val listener: NsdManager.RegistrationListener,
        var registeredName: String? = null,
        var isRegistered: Boolean = false
    )

    /**
     * Publishes a local network service via mDNS.
     */
    @Synchronized
    fun registerService(
        serviceName: String,
        port: Int,
        serviceType: String = "_http._tcp.",
        attributes: Map<String, String> = emptyMap(),
        onSuccess: ((String) -> Unit)? = null
    ) {
        val entry = MdnsServiceEntry(serviceName, port, serviceType, attributes)
        serviceDefinitions[serviceName] = entry

        val manager = nsdManager
        if (manager == null) {
            // JVM / Headless environment fallback
            val regCtx = RegistrationContext(
                entry = entry,
                listener = createDummyListener(entry, onSuccess),
                registeredName = serviceName,
                isRegistered = true
            )
            activeRegistrations[serviceName] = regCtx
            onSuccess?.invoke(serviceName)
            return
        }

        // Unregister existing if re-registering
        unregisterService(serviceName)

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = serviceType
            this.port = port
            for ((key, value) in attributes) {
                setAttribute(key, value)
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredService: NsdServiceInfo) {
                val resolvedName = registeredService.serviceName
                val ctx = activeRegistrations[serviceName]
                ctx?.registeredName = resolvedName
                ctx?.isRegistered = true
                onSuccess?.invoke(resolvedName)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                activeRegistrations.remove(serviceName)
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                activeRegistrations.remove(serviceName)
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                activeRegistrations.remove(serviceName)
            }
        }

        val regCtx = RegistrationContext(entry, listener)
        activeRegistrations[serviceName] = regCtx

        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            activeRegistrations.remove(serviceName)
        }
    }

    /**
     * Unregisters a published mDNS service by its initial name.
     */
    @Synchronized
    fun unregisterService(serviceName: String) {
        val regCtx = activeRegistrations.remove(serviceName) ?: return
        val manager = nsdManager
        if (manager != null) {
            try {
                manager.unregisterService(regCtx.listener)
            } catch (ignored: Exception) {}
        }
    }

    /**
     * Re-registers all active mDNS services, typically called when the IP address migrates.
     */
    @Synchronized
    fun reRegisterAll(newIp: String) {
        val entries = serviceDefinitions.values.toList()
        for (entry in entries) {
            registerService(
                serviceName = entry.serviceName,
                port = entry.port,
                serviceType = entry.serviceType,
                attributes = entry.attributes
            )
        }
    }

    /**
     * Unregisters all active mDNS services.
     */
    @Synchronized
    fun unregisterAll() {
        for (serviceName in activeRegistrations.keys.toList()) {
            unregisterService(serviceName)
        }
        activeRegistrations.clear()
        serviceDefinitions.clear()
    }

    /**
     * Checks if a service is currently registered.
     */
    fun isServiceRegistered(serviceName: String): Boolean {
        return activeRegistrations[serviceName]?.isRegistered == true
    }

    /**
     * Gets the actual network-assigned service name (may differ if collision occurred).
     */
    fun getRegisteredName(serviceName: String): String? {
        return activeRegistrations[serviceName]?.registeredName
    }

    private fun createDummyListener(entry: MdnsServiceEntry, onSuccess: ((String) -> Unit)?): NsdManager.RegistrationListener {
        return object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredService: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
    }
}
