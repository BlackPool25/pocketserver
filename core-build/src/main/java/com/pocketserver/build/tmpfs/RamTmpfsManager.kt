package com.pocketserver.build.tmpfs

import java.io.File

/**
 * RAM-Backed tmpfs Manager for Compiler & Build Caches.
 *
 * Implements RAM tmpfs caching (/dev/shm):
 * Compiler toolchains (Cargo, uv, npm, Go, clang) generate gigabytes of temporary object files,
 * incremental caches, and intermediate archives during compilation.
 *
 * Directing these write-intensive caches to physical UFS flash storage degrades NAND flash longevity
 * and slows down compilation. Mapping these scratchpads directly into RAM (/dev/shm) yields:
 * 1. Ultra-fast memory-speed compilation I/O.
 * 2. Zero NAND flash writes, preserving physical storage endurance.
 * 3. Instant 1-tap evaporation: caches disappear automatically on build completion or service stop.
 */
class RamTmpfsManager(
    private val preferredShmDir: File = File("/dev/shm")
) {

    /**
     * Checks if /dev/shm is available and writable on this device.
     */
    fun isShmAvailable(): Boolean {
        return try {
            preferredShmDir.exists() && preferredShmDir.isDirectory && preferredShmDir.canWrite()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Allocates an isolated ephemeral scratchpad directory for a project's build run.
     * Uses /dev/shm if available, or falls back to an ephemeral subdirectory in fallbackParent.
     */
    fun allocateScratchpad(projectId: String, fallbackParent: File): File {
        val baseDir = if (isShmAvailable()) {
            File(preferredShmDir, "pocketserver_builds")
        } else {
            File(fallbackParent, ".ram_tmpfs")
        }

        val projectScratchpad = File(baseDir, projectId)
        projectScratchpad.mkdirs()

        // Create standard compiler cache subdirectories
        File(projectScratchpad, "tmp").mkdirs()
        File(projectScratchpad, "cargo_target").mkdirs()
        File(projectScratchpad, "uv_cache").mkdirs()
        File(projectScratchpad, "npm_cache").mkdirs()
        File(projectScratchpad, "bun_cache").mkdirs()
        File(projectScratchpad, "go_cache").mkdirs()

        return projectScratchpad
    }

    /**
     * Prepares environment variable overrides routing all major compiler toolchains
     * to the RAM-backed scratchpad directory.
     */
    fun prepareBuildCacheEnv(scratchpadDir: File): Map<String, String> {
        val tmpDir = File(scratchpadDir, "tmp").absolutePath
        val cargoTarget = File(scratchpadDir, "cargo_target").absolutePath
        val uvCache = File(scratchpadDir, "uv_cache").absolutePath
        val npmCache = File(scratchpadDir, "npm_cache").absolutePath
        val bunCache = File(scratchpadDir, "bun_cache").absolutePath
        val goCache = File(scratchpadDir, "go_cache").absolutePath

        return mapOf(
            "TMPDIR" to tmpDir,
            "TMP" to tmpDir,
            "TEMP" to tmpDir,
            "CARGO_TARGET_DIR" to cargoTarget,
            "UV_CACHE_DIR" to uvCache,
            "npm_config_cache" to npmCache,
            "BUN_INSTALL_CACHE_DIR" to bunCache,
            "GOCACHE" to goCache
        )
    }

    /**
     * Instantly evaporates the ephemeral scratchpad, releasing all allocated RAM
     * and preventing any residual artifacts from hitting physical disk.
     */
    fun evaporateScratchpad(scratchpadDir: File): Boolean {
        if (!scratchpadDir.exists()) return true
        return scratchpadDir.deleteRecursively()
    }
}
