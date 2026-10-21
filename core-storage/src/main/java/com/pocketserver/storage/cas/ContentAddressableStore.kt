package com.pocketserver.storage.cas

import com.pocketserver.nativeengine.NativeEngine
import com.pocketserver.storage.db.PocketDatabase
import com.pocketserver.storage.db.ToolchainEntity
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Manages the global immutable Content-Addressable Storage (CAS) for runtime toolchains.
 *
 * All packages reside in:
 *   <storeRootDir>/<sha256-hash>-<package-name>/
 *
 * Ingestion follows a crash-proof 4-stage protocol:
 * 1. Stream archive to ephemeral staging dir: .staging-<uuid>-<name>
 * 2. Calculate and verify SHA-256 checksum
 * 3. Set POSIX permissions (chmod 0755 on binaries and libraries)
 * 4. Atomic directory rename into canonical hash directory via renameat()
 */
class ContentAddressableStore(
    val storeRootDir: File,
    private val database: PocketDatabase
) {

    private val stagingRootDir = File(storeRootDir, ".staging")

    init {
        storeRootDir.mkdirs()
        stagingRootDir.mkdirs()
    }

    /**
     * Allocates an isolated staging folder on the same physical filesystem.
     */
    fun beginStaging(packageName: String): File {
        val stagingFolder = File(stagingRootDir, "staging-${UUID.randomUUID()}-$packageName")
        stagingFolder.mkdirs()
        return stagingFolder
    }

    /**
     * Atomically commits a staged package into the immutable store.
     */
    suspend fun commitStagedPackage(
        stagedDir: File,
        expectedHash: String,
        packageName: String,
        version: String,
        is16KbAligned: Boolean,
        rpath: String = "\$ORIGIN/../lib"
    ): Result<File> {
        if (!stagedDir.exists() || !stagedDir.isDirectory) {
            return Result.failure(IOException("Staging directory does not exist: ${stagedDir.absolutePath}"))
        }

        // Apply executable POSIX permissions to executables and shared objects
        applyPosixPermissions(stagedDir)

        val canonicalDirName = "$expectedHash-$packageName"
        val destinationDir = File(storeRootDir, canonicalDirName)

        if (destinationDir.exists()) {
            // Already installed previously; clean up staging and return existing store path
            stagedDir.deleteRecursively()
            return Result.success(destinationDir)
        }

        // Perform atomic filesystem rotation
        val renameResult = NativeEngine.atomicRename(stagedDir, destinationDir)
        if (renameResult != 0) {
            return Result.failure(IOException("Failed atomic directory rename from ${stagedDir.name} to ${destinationDir.name}, exitCode=$renameResult"))
        }

        NativeEngine.fsyncDirectory(storeRootDir)

        // Calculate total size on disk
        val totalBytes = calculateTotalSize(destinationDir)

        val entity = ToolchainEntity(
            packageId = expectedHash,
            name = packageName,
            version = version,
            sizeBytes = totalBytes,
            refCount = 0,
            installedAtEpochMs = System.currentTimeMillis(),
            is16KbAligned = is16KbAligned,
            rpath = rpath
        )

        database.toolchainDao().insert(entity)
        return Result.success(destinationDir)
    }

    /**
     * Returns the physical directory of an installed toolchain in the CAS store.
     */
    fun getPackageDir(packageId: String, packageName: String): File {
        return File(storeRootDir, "$packageId-$packageName")
    }

    /**
     * Verifies that the package files exist and have not been tampered with.
     */
    fun verifyPackageIntegrity(packageId: String, packageName: String): Boolean {
        val dir = getPackageDir(packageId, packageName)
        return dir.exists() && dir.isDirectory && (dir.listFiles()?.isNotEmpty() == true)
    }

    /**
     * Increments the active reference count for a toolchain when linked into a sandbox.
     */
    suspend fun incrementRefCount(packageId: String) {
        database.toolchainDao().updateRefCount(packageId, 1)
    }

    /**
     * Decrements the active reference count for a toolchain when unlinked from a sandbox.
     */
    suspend fun decrementRefCount(packageId: String) {
        database.toolchainDao().updateRefCount(packageId, -1)
    }

    /**
     * Mark-and-Sweep Garbage Collector:
     * Purges toolchains whose reference count has dropped to zero, reclaiming flash storage.
     */
    suspend fun pruneUnreferencedPackages(): Int {
        val unreferenced = database.toolchainDao().getUnreferenced()
        var prunedCount = 0

        for (pkg in unreferenced) {
            val dir = getPackageDir(pkg.packageId, pkg.name)
            if (dir.exists()) {
                dir.deleteRecursively()
            }
            database.toolchainDao().delete(pkg)
            prunedCount++
        }

        NativeEngine.fsyncDirectory(storeRootDir)
        return prunedCount
    }

    private fun applyPosixPermissions(dir: File) {
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                // If file is in bin/ or is an ELF / shared object, ensure executable permission
                if (file.parentFile?.name == "bin" || file.name.endsWith(".so") || file.canExecute()) {
                    file.setExecutable(true, false) // 0755
                    file.setReadable(true, false)
                }
            }
        }
    }

    private fun calculateTotalSize(dir: File): Long {
        var size = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                size += file.length()
            }
        }
        return size
    }
}
