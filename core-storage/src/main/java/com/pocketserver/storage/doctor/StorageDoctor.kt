package com.pocketserver.storage.doctor

import com.pocketserver.storage.cas.ContentAddressableStore
import com.pocketserver.storage.db.PocketDatabase
import java.io.File

/**
 * Storage Doctor: Plain-English Disk Reclaimer & Build Cache Purger.
 *
 * Implements the 1-Tap Deep Purge for the "Hidden 5" mobile compiler caches:
 * 1. ~/.cargo/registry/src/ (Cargo crate sources)
 * 2. ~/.cache/uv/ (Python uv wheels and build cache)
 * 3. ~/.npm/_cacache/ & ~/.bun/install/cache/ (Node & Bun package tarballs)
 * 4. Incremental object files (target/debug/incremental, __pycache__, .pytest_cache)
 * 5. Git packfile temp chunks (.git/objects/tmp_*)
 *
 * Guaranteed Safety Contract:
 * - NEVER deletes canonical CAS store packages (/files/store/<hash>-<name>).
 * - NEVER deletes user source files (.py, .ts, .rs, .go, .c, .json, etc.).
 * - Preserves Room database and active configurations.
 */
class StorageDoctor(
    private val casStore: ContentAddressableStore? = null,
    private val database: PocketDatabase? = null
) {

    /**
     * Inspects storage usage across the app and identifies all reclaimable compiler caches.
     */
    fun analyze(
        baseDir: File,
        userHomeDir: File? = null,
        projectDirs: List<File> = emptyList()
    ): StorageBreakdown {
        val homeDir = userHomeDir ?: File(baseDir, "home").takeIf { it.exists() } ?: baseDir

        // 1. Installed CAS Runtimes
        val storeDir = casStore?.storeRootDir ?: File(baseDir, "store")
        val installedRuntimesBytes = calculateCasStoreSize(storeDir)

        // 2. Scan Reclaimable Caches (The "Hidden 5")
        val cacheItems = mutableListOf<CacheItem>()

        // Cache 1: Cargo Registry Sources
        scanDirectory(File(homeDir, ".cargo/registry/src"), CacheCategory.CARGO_REGISTRY)?.let {
            cacheItems.add(it)
        }
        scanDirectory(File(homeDir, ".cargo/git/checkouts"), CacheCategory.CARGO_REGISTRY)?.let {
            cacheItems.add(it)
        }

        // Cache 2: Python uv Cache
        scanDirectory(File(homeDir, ".cache/uv"), CacheCategory.UV_CACHE)?.let {
            cacheItems.add(it)
        }

        // Cache 3: npm / bun Caches
        scanDirectory(File(homeDir, ".npm/_cacache"), CacheCategory.NPM_BUN_CACHE)?.let {
            cacheItems.add(it)
        }
        scanDirectory(File(homeDir, ".bun/install/cache"), CacheCategory.NPM_BUN_CACHE)?.let {
            cacheItems.add(it)
        }

        // Aggregate project search directories
        val projectsToScan = mutableListOf<File>()
        projectsToScan.addAll(projectDirs)
        val defaultProjectsDir = File(baseDir, "projects")
        if (defaultProjectsDir.exists() && defaultProjectsDir.isDirectory) {
            defaultProjectsDir.listFiles()?.filter { it.isDirectory }?.let { projectsToScan.addAll(it) }
        }
        val defaultSandboxesDir = File(baseDir, "sandboxes")
        if (defaultSandboxesDir.exists() && defaultSandboxesDir.isDirectory) {
            defaultSandboxesDir.listFiles()?.filter { it.isDirectory }?.let { projectsToScan.addAll(it) }
        }

        // Cache 4: Incremental Builds in project folders
        for (project in projectsToScan) {
            if (!project.exists() || !project.isDirectory) continue

            // Rust target incremental
            scanDirectory(File(project, "target/debug/incremental"), CacheCategory.INCREMENTAL_BUILDS)?.let {
                cacheItems.add(it)
            }
            scanDirectory(File(project, "target/release/incremental"), CacheCategory.INCREMENTAL_BUILDS)?.let {
                cacheItems.add(it)
            }

            // Python __pycache__ & pytest cache
            findSubdirectoriesNamed(project, "__pycache__").forEach { pycacheDir ->
                scanDirectory(pycacheDir, CacheCategory.INCREMENTAL_BUILDS)?.let { cacheItems.add(it) }
            }
            scanDirectory(File(project, ".pytest_cache"), CacheCategory.INCREMENTAL_BUILDS)?.let {
                cacheItems.add(it)
            }
            scanDirectory(File(project, ".mypy_cache"), CacheCategory.INCREMENTAL_BUILDS)?.let {
                cacheItems.add(it)
            }

            // Cache 5: Git Temporary Objects
            val gitObjectsDir = File(project, ".git/objects")
            if (gitObjectsDir.exists() && gitObjectsDir.isDirectory) {
                val tmpGitFiles = gitObjectsDir.listFiles { file -> file.name.startsWith("tmp_") }
                if (!tmpGitFiles.isNullOrEmpty()) {
                    val size = tmpGitFiles.sumOf { it.length() }
                    cacheItems.add(
                        CacheItem(
                            category = CacheCategory.GIT_TEMP_OBJECTS,
                            path = gitObjectsDir,
                            sizeBytes = size,
                            fileCount = tmpGitFiles.size,
                            description = "${tmpGitFiles.size} loose git packfile temp objects"
                        )
                    )
                }
            }
            val gitIndexLock = File(project, ".git/index.lock")
            if (gitIndexLock.exists()) {
                cacheItems.add(
                    CacheItem(
                        category = CacheCategory.GIT_TEMP_OBJECTS,
                        path = gitIndexLock,
                        sizeBytes = gitIndexLock.length(),
                        fileCount = 1,
                        description = "Stale git index lock"
                    )
                )
            }
        }

        val reclaimableCacheBytes = cacheItems.sumOf { it.sizeBytes }

        // 3. User Project Code (excluding discovered cache items to avoid double counting)
        val projectCodeBytes = projectsToScan.sumOf { projectDir ->
            calculateProjectCodeSize(projectDir, cacheItems)
        }

        val totalAppUsedBytes = installedRuntimesBytes + projectCodeBytes + reclaimableCacheBytes
        val freeDiskSpaceBytes = baseDir.freeSpace
        val totalDiskSpaceBytes = baseDir.totalSpace

        return StorageBreakdown(
            totalAppUsedBytes = totalAppUsedBytes,
            installedRuntimesBytes = installedRuntimesBytes,
            projectCodeBytes = projectCodeBytes,
            reclaimableCacheBytes = reclaimableCacheBytes,
            freeDiskSpaceBytes = freeDiskSpaceBytes,
            totalDiskSpaceBytes = totalDiskSpaceBytes,
            cacheItems = cacheItems
        )
    }

    /**
     * Executes the 1-Tap Deep Purge safely, reclaiming storage space.
     */
    fun cleanReclaimableCaches(
        breakdown: StorageBreakdown? = null,
        baseDir: File,
        userHomeDir: File? = null,
        projectDirs: List<File> = emptyList()
    ): StorageReclaimResult {
        val effectiveBreakdown = breakdown ?: analyze(baseDir, userHomeDir, projectDirs)
        var bytesFreed = 0L
        var itemsRemoved = 0
        val categoriesCleaned = mutableSetOf<CacheCategory>()
        val failedPaths = mutableListOf<Pair<File, String>>()

        val storeDir = casStore?.storeRootDir ?: File(baseDir, "store")
        val canonicalStorePath = storeDir.canonicalPath

        for (item in effectiveBreakdown.cacheItems) {
            val targetFile = item.path

            // Safety Guard: Under no circumstances delete anything inside the CAS store!
            if (targetFile.canonicalPath.startsWith(canonicalStorePath)) {
                failedPaths.add(targetFile to "Protected CAS store path")
                continue
            }

            try {
                if (targetFile.isFile) {
                    val size = targetFile.length()
                    if (targetFile.delete()) {
                        bytesFreed += size
                        itemsRemoved++
                        categoriesCleaned.add(item.category)
                    } else {
                        failedPaths.add(targetFile to "Could not delete file")
                    }
                } else if (targetFile.isDirectory) {
                    val size = item.sizeBytes
                    if (targetFile.deleteRecursively()) {
                        bytesFreed += size
                        itemsRemoved += item.fileCount
                        categoriesCleaned.add(item.category)
                    } else {
                        failedPaths.add(targetFile to "Could not delete directory recursively")
                    }
                }
            } catch (e: Exception) {
                failedPaths.add(targetFile to (e.message ?: "Unknown error"))
            }
        }

        return StorageReclaimResult(
            isSuccess = failedPaths.isEmpty(),
            bytesFreed = bytesFreed,
            itemsRemovedCount = itemsRemoved,
            categoriesCleaned = categoriesCleaned.toList(),
            failedPaths = failedPaths
        )
    }

    private fun calculateCasStoreSize(storeDir: File): Long {
        if (!storeDir.exists() || !storeDir.isDirectory) return 0L
        return storeDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".staging") }
            ?.sumOf { dir -> calculateDirSize(dir) } ?: 0L
    }

    private fun calculateProjectCodeSize(projectDir: File, excludedCaches: List<CacheItem>): Long {
        if (!projectDir.exists()) return 0L
        val excludedCanonicalPaths = excludedCaches.map { it.path.canonicalPath }.toSet()

        var totalSize = 0L
        projectDir.walkTopDown()
            .onEnter { dir ->
                !excludedCanonicalPaths.contains(dir.canonicalPath)
            }
            .forEach { file ->
                if (file.isFile && !excludedCanonicalPaths.contains(file.canonicalPath)) {
                    totalSize += file.length()
                }
            }
        return totalSize
    }

    private fun scanDirectory(dir: File, category: CacheCategory): CacheItem? {
        if (!dir.exists()) return null
        var totalBytes = 0L
        var totalFiles = 0

        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                totalBytes += file.length()
                totalFiles++
            }
        }

        if (totalBytes <= 0 && totalFiles == 0) return null

        return CacheItem(
            category = category,
            path = dir,
            sizeBytes = totalBytes,
            fileCount = totalFiles,
            description = category.safeDescription
        )
    }

    private fun findSubdirectoriesNamed(rootDir: File, targetName: String): List<File> {
        val matches = mutableListOf<File>()
        rootDir.walkTopDown().forEach { file ->
            if (file.isDirectory && file.name == targetName) {
                matches.add(file)
            }
        }
        return matches
    }

    private fun calculateDirSize(dir: File): Long {
        var size = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                size += file.length()
            }
        }
        return size
    }
}
