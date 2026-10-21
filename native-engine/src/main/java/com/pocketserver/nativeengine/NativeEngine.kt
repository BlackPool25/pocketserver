package com.pocketserver.nativeengine

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Native bridge providing low-level POSIX filesystem operations,
 * atomic directory rotations, and 16 KB ELF boundary validation.
 *
 * All C++ components in this module are linked with:
 *   -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
 */
object NativeEngine {

    private val isNativeLoaded: Boolean = try {
        System.loadLibrary("pocketserver_native")
        true
    } catch (e: UnsatisfiedLinkError) {
        // Fallback for host JVM unit test runners where Android NDK libs are not loaded
        false
    }

    const val REQUIRED_PAGE_SIZE = 16384L // 16 KB boundary for Android 15/16

    /**
     * Inspects a 64-bit ELF binary or shared object to verify that all PT_LOAD
     * segments meet the 16 KB page alignment requirement (p_align >= 16384).
     */
    fun is16KbAligned(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false

        if (isNativeLoaded) {
            return nativeIs16KbAligned(file.absolutePath)
        }

        // JVM/Robolectric fallback ELF64 header inspection
        return verify16KbAlignmentJvm(file)
    }

    /**
     * Creates a relative POSIX symlink pointing to target at linkPath.
     */
    fun createRelativeSymlink(targetRelativePath: String, linkFile: File): Int {
        linkFile.parentFile?.mkdirs()
        if (isNativeLoaded) {
            return nativeCreateRelativeSymlink(targetRelativePath, linkFile.absolutePath)
        }

        return try {
            val linkPath = Paths.get(linkFile.absolutePath)
            val targetPath = Paths.get(targetRelativePath)
            Files.deleteIfExists(linkPath)
            Files.createSymbolicLink(linkPath, targetPath)
            0
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Atomically rotates or renames a directory using POSIX renameat/rename semantics.
     */
    fun atomicRename(sourceDir: File, destinationDir: File): Int {
        if (isNativeLoaded) {
            return nativeAtomicRename(sourceDir.absolutePath, destinationDir.absolutePath)
        }

        return try {
            Files.move(
                sourceDir.toPath(),
                destinationDir.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
            0
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Flushes directory metadata changes to physical storage via fsync().
     */
    fun fsyncDirectory(dir: File): Int {
        if (!dir.exists() || !dir.isDirectory) return -1
        if (isNativeLoaded) {
            return nativeFsyncDirectory(dir.absolutePath)
        }
        return 0
    }

    /**
     * Returns the memory page size of the underlying operating system.
     */
    fun getSystemPageSize(): Long {
        if (isNativeLoaded) {
            return nativeGetSystemPageSize()
        }
        return 4096L // Default assumed baseline in host JVM
    }

    // --- Pure JVM ELF64 Parser for Test Verification & Host Execution ---

    private fun verify16KbAlignmentJvm(file: File): Boolean {
        if (file.length() < 64) return false

        RandomAccessFile(file, "r").use { raf ->
            val headerBytes = ByteArray(64)
            raf.readFully(headerBytes)
            val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

            // Verify ELF Magic: 0x7F, 'E', 'L', 'F'
            if (buffer.get(0) != 0x7F.toByte() ||
                buffer.get(1) != 'E'.code.toByte() ||
                buffer.get(2) != 'L'.code.toByte() ||
                buffer.get(3) != 'F'.code.toByte()
            ) {
                return false
            }

            // Class: 1 = 32-bit, 2 = 64-bit
            val elfClass = buffer.get(4).toInt()
            if (elfClass == 1) {
                // 32-bit binaries do not enforce 16KB alignment on 64-bit ARM
                return true
            } else if (elfClass != 2) {
                return false
            }

            val ePhOff = buffer.getLong(32)
            val ePhEntSize = buffer.getShort(54).toInt()
            val ePhNum = buffer.getShort(56).toInt()

            if (ePhOff <= 0 || ePhNum <= 0 || ePhEntSize < 56) {
                return false
            }

            raf.seek(ePhOff)
            val phdrBytes = ByteArray(ePhEntSize)
            var hasLoad = false

            for (i in 0 until ePhNum) {
                raf.readFully(phdrBytes)
                val phdrBuf = ByteBuffer.wrap(phdrBytes).order(ByteOrder.LITTLE_ENDIAN)
                val pType = phdrBuf.getInt(0)

                // PT_LOAD = 1
                if (pType == 1) {
                    hasLoad = true
                    val pOffset = phdrBuf.getLong(8)
                    val pVaddr = phdrBuf.getLong(16)
                    val pAlign = phdrBuf.getLong(48)

                    if (pAlign < REQUIRED_PAGE_SIZE) {
                        return false
                    }

                    if ((pVaddr % pAlign) != (pOffset % pAlign)) {
                        return false
                    }
                }
            }

            return hasLoad
        }
    }

    // --- JNI External Signatures ---

    private external fun nativeIs16KbAligned(filePath: String): Boolean
    private external fun nativeCreateRelativeSymlink(target: String, linkPath: String): Int
    private external fun nativeAtomicRename(oldPath: String, newPath: String): Int
    private external fun nativeFsyncDirectory(dirPath: String): Int
    private external fun nativeGetSystemPageSize(): Long
}
