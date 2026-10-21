package com.pocketserver.storage.doctor

import java.io.File
import java.text.DecimalFormat

/**
 * Categorization of reclaimable developer build and package caches.
 */
enum class CacheCategory(val displayName: String, val safeDescription: String) {
    CARGO_REGISTRY(
        "Cargo Registry Sources",
        "Extracted Rust crate sources in ~/.cargo/registry/src. Cargo automatically re-fetches or re-extracts these on build."
    ),
    UV_CACHE(
        "Python uv Cache",
        "Cached Python wheel downloads and pre-built binaries in ~/.cache/uv. Safe to purge."
    ),
    NPM_BUN_CACHE(
        "Node / Bun Cache",
        "Package tarball archives in ~/.npm/_cacache and ~/.bun/install/cache. Re-downloadable on demand."
    ),
    INCREMENTAL_BUILDS(
        "Incremental Build Artifacts",
        "Intermediate object files (__pycache__, target/*/incremental, .pytest_cache). Safe to clean."
    ),
    GIT_TEMP_OBJECTS(
        "Git Temporary Packfiles",
        "Dangling git packfile chunks and stale index locks. Safe to prune."
    )
}

/**
 * A discrete reclaimable cache target located on disk.
 */
data class CacheItem(
    val category: CacheCategory,
    val path: File,
    val sizeBytes: Long,
    val fileCount: Int,
    val description: String
)

/**
 * Plain-English storage breakdown modeled after modern cloud console dashboards (Vercel / Railway).
 */
data class StorageBreakdown(
    val totalAppUsedBytes: Long,
    val installedRuntimesBytes: Long,
    val projectCodeBytes: Long,
    val reclaimableCacheBytes: Long,
    val freeDiskSpaceBytes: Long,
    val totalDiskSpaceBytes: Long,
    val cacheItems: List<CacheItem>
) {
    val installedRuntimesFormatted: String get() = humanReadableSize(installedRuntimesBytes)
    val projectCodeFormatted: String get() = humanReadableSize(projectCodeBytes)
    val reclaimableCacheFormatted: String get() = humanReadableSize(reclaimableCacheBytes)
    val totalAppUsedFormatted: String get() = humanReadableSize(totalAppUsedBytes)
    val freeDiskSpaceFormatted: String get() = humanReadableSize(freeDiskSpaceBytes)
    val totalDiskSpaceFormatted: String get() = humanReadableSize(totalDiskSpaceBytes)

    val reclaimPercentage: Double
        get() = if (totalAppUsedBytes > 0) {
            (reclaimableCacheBytes.toDouble() / totalAppUsedBytes.toDouble()) * 100.0
        } else 0.0

    companion object {
        private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")
        private val DECIMAL_FORMAT = DecimalFormat("#,##0.#")

        fun humanReadableSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val unitIndex = digitGroups.coerceIn(0, UNITS.size - 1)
            val value = bytes / Math.pow(1024.0, unitIndex.toDouble())
            return "${DECIMAL_FORMAT.format(value)} ${UNITS[unitIndex]}"
        }
    }
}

/**
 * Result returned after performing a 1-tap cache purge.
 */
data class StorageReclaimResult(
    val isSuccess: Boolean,
    val bytesFreed: Long,
    val itemsRemovedCount: Int,
    val categoriesCleaned: List<CacheCategory>,
    val failedPaths: List<Pair<File, String>> = emptyList()
) {
    val bytesFreedFormatted: String get() = StorageBreakdown.humanReadableSize(bytesFreed)
}
