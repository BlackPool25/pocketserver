package com.pocketserver.storage.sandbox

import com.pocketserver.nativeengine.NativeEngine
import com.pocketserver.storage.cas.ContentAddressableStore
import com.pocketserver.storage.db.PocketDatabase
import com.pocketserver.storage.db.SandboxEntity
import java.io.File
import java.io.IOException
import java.nio.file.Paths

/**
 * Generates and maintains isolated virtual filesystem views (rootfs) for hosted services
 * using relative POSIX symlink farms.
 *
 * This design avoids container virtualization overhead (PRoot, QEMU, Docker namespaces)
 * while ensuring clean dependency isolation without duplicating 100MB+ toolchains.
 */
class SymlinkFarmManager(
    val sandboxesRootDir: File,
    private val casStore: ContentAddressableStore,
    private val database: PocketDatabase
) {

    init {
        sandboxesRootDir.mkdirs()
    }

    /**
     * Initializes an isolated sandbox rootfs directory for a new service.
     */
    suspend fun createSandboxRootfs(
        serviceId: String,
        serviceName: String,
        assignedPort: Int
    ): File {
        val sandboxBaseDir = File(sandboxesRootDir, serviceId)
        val rootfsDir = File(sandboxBaseDir, "rootfs")
        val appDir = File(sandboxBaseDir, "app")
        val tmpDir = File(sandboxBaseDir, "tmp")

        // Create standard POSIX directory hierarchy inside rootfs
        File(rootfsDir, "bin").mkdirs()
        File(rootfsDir, "lib").mkdirs()
        File(rootfsDir, "usr/bin").mkdirs()
        File(rootfsDir, "usr/lib").mkdirs()
        File(rootfsDir, "etc").mkdirs()
        File(rootfsDir, "tmp").mkdirs()

        // Create project app workspace and base ephemeral tmp scratchpad
        appDir.mkdirs()
        tmpDir.mkdirs()

        val entity = SandboxEntity(
            serviceId = serviceId,
            name = serviceName,
            assignedPort = assignedPort,
            rootfsPath = rootfsDir.absolutePath,
            projectRepoPath = appDir.absolutePath,
            status = "STOPPED"
        )
        database.sandboxDao().insert(entity)

        return rootfsDir
    }

    /**
     * Links a toolchain from the CAS store into the sandbox's virtual rootfs using relative symlinks.
     */
    suspend fun linkToolchainIntoSandbox(serviceId: String, packageId: String, packageName: String) {
        val toolchainDir = casStore.getPackageDir(packageId, packageName)
        if (!toolchainDir.exists() || !toolchainDir.isDirectory) {
            throw IOException("Toolchain $packageName ($packageId) is not installed in the CAS store.")
        }

        val sandboxBaseDir = File(sandboxesRootDir, serviceId)
        val rootfsDir = File(sandboxBaseDir, "rootfs")
        if (!rootfsDir.exists()) {
            throw IOException("Sandbox rootfs does not exist for service $serviceId")
        }

        val usrBin = File(rootfsDir, "usr/bin")
        val bin = File(rootfsDir, "bin")
        val usrLib = File(rootfsDir, "usr/lib")
        val lib = File(rootfsDir, "lib")

        // Link bin/ files
        val sourceBinDir = File(toolchainDir, "bin")
        if (sourceBinDir.exists() && sourceBinDir.isDirectory) {
            sourceBinDir.listFiles()?.forEach { binaryFile ->
                if (binaryFile.isFile) {
                    val targetLinkUsrBin = File(usrBin, binaryFile.name)
                    val relPathToTarget = computeRelativePath(targetLinkUsrBin, binaryFile)
                    NativeEngine.createRelativeSymlink(relPathToTarget, targetLinkUsrBin)

                    val targetLinkBin = File(bin, binaryFile.name)
                    val relPathBin = computeRelativePath(targetLinkBin, binaryFile)
                    NativeEngine.createRelativeSymlink(relPathBin, targetLinkBin)
                }
            }
        }

        // Link lib/ files
        val sourceLibDir = File(toolchainDir, "lib")
        if (sourceLibDir.exists() && sourceLibDir.isDirectory) {
            sourceLibDir.listFiles()?.forEach { libFile ->
                if (libFile.isFile) {
                    val targetLinkUsrLib = File(usrLib, libFile.name)
                    val relPathUsrLib = computeRelativePath(targetLinkUsrLib, libFile)
                    NativeEngine.createRelativeSymlink(relPathUsrLib, targetLinkUsrLib)

                    val targetLinkLib = File(lib, libFile.name)
                    val relPathLib = computeRelativePath(targetLinkLib, libFile)
                    NativeEngine.createRelativeSymlink(relPathLib, targetLinkLib)
                }
            }
        }

        // Increment CAS reference count
        casStore.incrementRefCount(packageId)
    }

    /**
     * Teardown an isolated sandbox directory and decrement toolchain reference counts.
     */
    suspend fun teardownSandbox(serviceId: String, linkedPackageIds: List<String> = emptyList()): Boolean {
        val sandboxBaseDir = File(sandboxesRootDir, serviceId)
        val deleted = if (sandboxBaseDir.exists()) {
            sandboxBaseDir.deleteRecursively()
        } else {
            true
        }

        for (pkgId in linkedPackageIds) {
            casStore.decrementRefCount(pkgId)
        }

        database.sandboxDao().getById(serviceId)?.let {
            database.sandboxDao().delete(it)
        }

        return deleted
    }

    /**
     * Computes the relative POSIX path from the symlink location to the target file.
     * Example: from /sandboxes/uuid/rootfs/usr/bin/python to /store/hash-python/bin/python
     * Result: ../../../../../store/hash-python/bin/python
     */
    private fun computeRelativePath(linkFile: File, targetFile: File): String {
        val parent = linkFile.parentFile ?: linkFile
        val linkParentPath = Paths.get(parent.absolutePath)
        val targetPath = Paths.get(targetFile.absolutePath)
        return linkParentPath.relativize(targetPath).toString()
    }
}
