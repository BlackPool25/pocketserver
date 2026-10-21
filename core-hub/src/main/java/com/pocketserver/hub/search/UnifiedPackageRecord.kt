package com.pocketserver.hub.search

/**
 * Tier classification for the 70,000+ universal package ecosystem.
 */
enum class PackageTier(val displayName: String, val badgeColorHex: String) {
    CORE("Curated Core", "#6366F1"),            // Tier 1: Fast-track runtimes (<50MB, Zstd)
    ALPINE("Alpine Linux", "#0284C7"),          // Tier 2: apk.static sysroot (36k+ packages, Musl)
    CONDA("Conda-Forge", "#10B981"),           // Tier 3: Micromamba ARM64 (25k+ packages, Glibc)
    POCKETFORGE("PocketForge", "#F59E0B")       // Tier 4: Decentralized Community Repos (COPR/AUR/TUR)
}

/**
 * Unified record schema federating packages across Curated Core, Alpine Linux,
 * Conda-Forge, and PocketForge community repositories.
 */
data class UnifiedPackageRecord(
    val id: String,
    val name: String,
    val version: String,
    val tier: PackageTier,
    val category: String,
    val description: String,
    val sizeCompressedBytes: Long,
    val installedSizeBytes: Long,
    val sourceRepo: String,
    val downloadUrl: String,
    val sha256Checksum: String,
    val primaryBinary: String,
    val isInstalled: Boolean = false,
    val dependencies: List<String> = emptyList(),
    val environmentVariables: Map<String, String> = emptyMap()
) {
    val sizeCompressedMb: Double get() = sizeCompressedBytes.toDouble() / (1024.0 * 1024.0)
    val installedSizeMb: Double get() = installedSizeBytes.toDouble() / (1024.0 * 1024.0)

    val tierBadge: String
        get() = when (tier) {
            PackageTier.CORE -> "[Core]"
            PackageTier.ALPINE -> "[Alpine]"
            PackageTier.CONDA -> "[Conda]"
            PackageTier.POCKETFORGE -> "[PocketForge]"
        }
}
