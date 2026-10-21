package com.pocketserver.hub.forge

import com.pocketserver.hub.search.PackageTier
import com.pocketserver.hub.search.UnifiedPackageRecord

/**
 * Represents a community package source in PocketForge (COPR / AUR / TUR equivalent).
 */
data class PocketForgeRepository(
    val id: String,
    val name: String,
    val description: String,
    val manifestUrl: String,
    val signatureUrl: String,
    val publicKeyBase64: String,
    val isEnabled: Boolean = true,
    val isVerified: Boolean = false,
    val lastSyncedEpochMs: Long = 0L,
    val packageCount: Int = 0
)

/**
 * Package metadata published within a PocketForge `index.json` manifest.
 */
data class PocketForgePackage(
    val id: String,
    val name: String,
    val version: String,
    val category: String,
    val description: String,
    val sizeCompressedBytes: Long,
    val installedSizeBytes: Long,
    val downloadUrl: String,
    val sha256Checksum: String,
    val primaryBinaryRelPath: String,
    val dependencies: List<String> = emptyList(),
    val defaultPort: Int = 0,
    val environmentVariables: Map<String, String> = emptyMap()
) {
    fun toUnifiedRecord(repoName: String): UnifiedPackageRecord {
        return UnifiedPackageRecord(
            id = id,
            name = name,
            version = version,
            tier = PackageTier.POCKETFORGE,
            category = category,
            description = description,
            sizeCompressedBytes = sizeCompressedBytes,
            installedSizeBytes = installedSizeBytes,
            sourceRepo = repoName,
            downloadUrl = downloadUrl,
            sha256Checksum = sha256Checksum,
            primaryBinary = primaryBinaryRelPath,
            isInstalled = false,
            dependencies = dependencies,
            environmentVariables = environmentVariables
        )
    }
}

/**
 * Decoded and verified `index.json` catalog root.
 */
data class PocketForgeManifest(
    val repository: String,
    val maintainer: String,
    val version: Int = 1,
    val description: String = "",
    val packages: List<PocketForgePackage>
)
