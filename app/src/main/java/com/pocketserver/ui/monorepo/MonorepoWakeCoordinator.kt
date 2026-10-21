package com.pocketserver.ui.monorepo

import com.pocketserver.ui.onboarding.repo.MonorepoLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of a coordinated multi-service unfreeze operation.
 */
data class CoordinatedWakeResult(
    val sourceServiceId: String,
    val awakenedServiceIds: List<String>,
    val latenciesNanos: Map<String, Long>,
    val totalLatencyNanos: Long
) {
    val totalLatencyMillis: Double
        get() = totalLatencyNanos / 1_000_000.0
}

/**
 * Coordinates wake events between linked services in a monorepo (e.g. Frontend <-> Backend).
 * Ensures that when a user accesses a frontend service, the associated backend service
 * is unfrozen in rapid sequence (target < 2ms total unfreeze latency).
 */
class MonorepoWakeCoordinator {

    private val linksByMonorepoId = ConcurrentHashMap<String, MonorepoLink>()
    private val monorepoIdByServiceId = ConcurrentHashMap<String, String>()

    /**
     * Registers a monorepo relationship between services.
     */
    fun registerLink(link: MonorepoLink) {
        linksByMonorepoId[link.monorepoId] = link
        for (serviceId in link.allServiceIds) {
            monorepoIdByServiceId[serviceId] = link.monorepoId
        }
    }

    /**
     * Retrieves all sibling services linked to the given service.
     */
    fun getLinkedServices(serviceId: String): List<String> {
        val monorepoId = monorepoIdByServiceId[serviceId] ?: return emptyList()
        val link = linksByMonorepoId[monorepoId] ?: return emptyList()
        return link.allServiceIds.filter { it != serviceId }
    }

    /**
     * Returns the monorepo link for the given serviceId, if any.
     */
    fun getLinkForService(serviceId: String): MonorepoLink? {
        val monorepoId = monorepoIdByServiceId[serviceId] ?: return null
        return linksByMonorepoId[monorepoId]
    }

    /**
     * Performs coordinated unfreezing: unfreezes the source service, then immediately
     * unfreezes all linked partner services in sequence.
     *
     * @param sourceServiceId The service triggered by incoming request or user action.
     * @param unfreezeAction Function that takes a serviceId and unfreezes it, returning latency in nanoseconds.
     */
    suspend fun wakeCoordinated(
        sourceServiceId: String,
        unfreezeAction: suspend (String) -> Long
    ): CoordinatedWakeResult = withContext(Dispatchers.Default) {
        val startTime = System.nanoTime()
        val latencies = mutableMapOf<String, Long>()
        val awakened = mutableListOf<String>()

        // 1. Unfreeze the primary source service
        val primaryLatency = unfreezeAction(sourceServiceId)
        latencies[sourceServiceId] = primaryLatency
        awakened.add(sourceServiceId)

        // 2. Unfreeze linked services (e.g. backend API)
        val linkedServices = getLinkedServices(sourceServiceId)
        for (linkedId in linkedServices) {
            val linkedLatency = unfreezeAction(linkedId)
            latencies[linkedId] = linkedLatency
            awakened.add(linkedId)
        }

        val totalDurationNanos = System.nanoTime() - startTime
        CoordinatedWakeResult(
            sourceServiceId = sourceServiceId,
            awakenedServiceIds = awakened,
            latenciesNanos = latencies,
            totalLatencyNanos = totalDurationNanos
        )
    }

    /**
     * Clears all registered monorepo links.
     */
    fun clear() {
        linksByMonorepoId.clear()
        monorepoIdByServiceId.clear()
    }
}
