package com.pocketserver.proxy.port

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Data model representing the virtual-to-physical port mapping for a hosted service.
 */
data class PortAllocation(
    val serviceId: String,
    val virtualPort: Int,
    val physicalPort: Int,
    val isRemapped: Boolean,
    val notice: String?
)

/**
 * Thread-safe Virtual Port Allocator that dynamically resolves port collisions (EADDRINUSE)
 * by finding the next sequential available port and maintaining routing mappings.
 */
class VirtualPortAllocator(
    private val minPort: Int = 1024,
    private val maxPort: Int = 65535
) {
    // Map of serviceId -> PortAllocation
    private val allocationsByService = ConcurrentHashMap<String, PortAllocation>()
    // Set of reserved physical ports currently assigned
    private val reservedPhysicalPorts = ConcurrentHashMap.newKeySet<Int>()

    /**
     * Checks if a given port is available for binding on the specified interface.
     */
    fun isPortAvailable(port: Int, host: String = "127.0.0.1"): Boolean {
        if (port < minPort || port > maxPort) return false
        if (reservedPhysicalPorts.contains(port)) return false

        return try {
            ServerSocket(port, 1, InetAddress.getByName(host)).use { true }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Allocates a physical port for the service. If requestedPort is available, it is allocated directly.
     * If requestedPort is occupied (EADDRINUSE), it sequentially scans for the next free port.
     */
    @Synchronized
    fun allocate(
        serviceId: String,
        requestedPort: Int,
        host: String = "127.0.0.1"
    ): PortAllocation {
        // If already allocated for this service, return existing allocation
        allocationsByService[serviceId]?.let { existing ->
            if (existing.virtualPort == requestedPort) {
                return existing
            }
            // Release existing if requested port changed
            release(serviceId)
        }

        require(requestedPort in minPort..maxPort) {
            "Requested port $requestedPort is outside allowed range [$minPort, $maxPort]"
        }

        val physicalPort: Int
        val isRemapped: Boolean
        val notice: String?

        if (isPortAvailable(requestedPort, host)) {
            physicalPort = requestedPort
            isRemapped = false
            notice = null
        } else {
            // Find next sequential free port
            physicalPort = findNextFreePort(requestedPort, host)
            isRemapped = true
            notice = "Port $requestedPort in use; automatically routed to $physicalPort"
        }

        reservedPhysicalPorts.add(physicalPort)
        val allocation = PortAllocation(
            serviceId = serviceId,
            virtualPort = requestedPort,
            physicalPort = physicalPort,
            isRemapped = isRemapped,
            notice = notice
        )
        allocationsByService[serviceId] = allocation
        return allocation
    }

    /**
     * Releases any port allocated to the given service.
     */
    @Synchronized
    fun release(serviceId: String): PortAllocation? {
        val removed = allocationsByService.remove(serviceId)
        if (removed != null) {
            reservedPhysicalPorts.remove(removed.physicalPort)
        }
        return removed
    }

    /**
     * Gets the active port allocation for the service, or null if unallocated.
     */
    fun getAllocation(serviceId: String): PortAllocation? {
        return allocationsByService[serviceId]
    }

    /**
     * Finds an allocation matching a physical port.
     */
    fun getAllocationByPhysicalPort(physicalPort: Int): PortAllocation? {
        return allocationsByService.values.find { it.physicalPort == physicalPort }
    }

    /**
     * Lists all active port allocations.
     */
    fun listAllocations(): List<PortAllocation> {
        return allocationsByService.values.toList()
    }

    /**
     * Clears all allocations and reservations.
     */
    @Synchronized
    fun clear() {
        allocationsByService.clear()
        reservedPhysicalPorts.clear()
    }

    private fun findNextFreePort(startPort: Int, host: String): Int {
        var candidate = startPort + 1
        while (candidate <= maxPort) {
            if (isPortAvailable(candidate, host)) {
                return candidate
            }
            candidate++
        }

        // Wrap around from minPort up to startPort if needed
        candidate = minPort
        while (candidate < startPort) {
            if (isPortAvailable(candidate, host)) {
                return candidate
            }
            candidate++
        }

        throw IllegalStateException("Exhausted all available ports in range [$minPort, $maxPort]")
    }
}
