package com.pocketserver.ui.dashboard

import com.pocketserver.ui.theme.PocketDesignTokens

enum class DashboardFilter {
    ALL,
    LIVE,
    SLEEPING,
    STOPPED
}

/**
 * Presentation model for a hosted service card following progressive disclosure.
 *
 * - The Surface (what 90% of users see):
 *   Project name, framework icon, live URL, plain human status, Monorepo Link badge,
 *   battery impact, and primary quick actions.
 *
 * - The Layer Beneath (accessible when expanded):
 *   Live RAM/CPU meters, environment editor, logs, and command runner.
 */
data class ServiceCardModel(
    val serviceId: String,
    val name: String,
    val framework: String,
    val assignedPort: Int,
    val localUrl: String,
    val mDnsUrl: String,
    val publicTunnelUrl: String? = null,
    val status: String = "STOPPED", // LIVE, SLEEPING, STOPPED
    val batteryImpact: String = "Minimal (<1%)",
    val monorepoBadge: String? = null, // e.g. "[Linked to Frontend]"
    val isExpanded: Boolean = false,
    val ramUsageMb: Long = 0L,
    val cpuPercent: Double = 0.0
) {
    val humanStatusDescription: String
        get() = PocketDesignTokens.getHumanStatusDescription(status)

    val statusColorHex: String
        get() = when (status.uppercase()) {
            "LIVE", "RUNNING", "ACTIVE" -> PocketDesignTokens.COLOR_STATUS_LIVE_HEX
            "SLEEPING", "FROZEN" -> PocketDesignTokens.COLOR_STATUS_SLEEPING_HEX
            "ATTENTION", "THROTTLED" -> PocketDesignTokens.COLOR_STATUS_ATTENTION_HEX
            else -> PocketDesignTokens.COLOR_STATUS_STOPPED_HEX
        }

    val displayAddress: String
        get() = "$localUrl  •  $mDnsUrl"
}
