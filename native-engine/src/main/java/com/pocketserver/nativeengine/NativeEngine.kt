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

    // POSIX Signal Constants
    const val SIGTERM = 15
    const val SIGKILL = 9
    const val SIGSTOP = 19
    const val SIGCONT = 18

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

    /**
     * Sends a POSIX signal to a process group (kill(-pgid, signal)).
     * Returns 0 on success, or negative errno on failure.
     */
    fun sendSignalToProcessGroup(pgid: Int, signal: Int): Int {
        if (pgid <= 0) return -1
        if (isNativeLoaded) {
            return nativeSendSignalToProcessGroup(pgid, signal)
        }
        // JVM fallback for Linux/macOS
        return try {
            val process = ProcessBuilder("kill", "-$signal", "-$pgid").start()
            val exitCode = process.waitFor()
            if (exitCode == 0) 0 else -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Sends a POSIX signal to an individual process (kill(pid, signal)).
     * Returns 0 on success, or negative errno on failure.
     */
    fun sendSignalToProcess(pid: Int, signal: Int): Int {
        if (pid <= 0) return -1
        if (isNativeLoaded) {
            return nativeSendSignalToProcess(pid, signal)
        }
        // JVM fallback for Linux/macOS
        return try {
            val process = ProcessBuilder("kill", "-$signal", "$pid").start()
            val exitCode = process.waitFor()
            if (exitCode == 0) 0 else -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Reads the Linux process state for the given pid from /proc/<pid>/stat.
     * Returns state character string ("R", "S", "D", "Z", "T", "t") or null if process not found.
     * Note: "T" indicates the process is stopped by signal (SIGSTOP/SIGTSTP).
     */
    fun getProcessState(pid: Int): String? {
        if (pid <= 0) return null
        if (isNativeLoaded) {
            return nativeGetProcessState(pid)
        }
        // JVM fallback
        return try {
            val statFile = File("/proc/$pid/stat")
            if (!statFile.exists()) return null
            val line = statFile.readText().trim()
            val lastParen = line.lastIndexOf(')')
            if (lastParen != -1 && lastParen + 2 < line.length) {
                line.substring(lastParen + 2, lastParen + 3)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Binds a process to specific CPU cores using a bitmask (1 = CPU0, 2 = CPU1, 4 = CPU2, etc.).
     * Returns 0 on success, or negative errno on failure.
     */
    fun setCpuAffinity(pid: Int, cpuMask: Long): Int {
        if (pid <= 0) return -1
        if (isNativeLoaded) {
            return nativeSetCpuAffinity(pid, cpuMask)
        }
        // JVM/Linux CLI fallback using taskset
        return try {
            val hexMask = "0x" + java.lang.Long.toHexString(cpuMask)
            val process = ProcessBuilder("taskset", "-p", hexMask, pid.toString()).start()
            val exitCode = process.waitFor()
            if (exitCode == 0) 0 else -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Sets the scheduling priority (nice value) of a process (-20 to 19, where 19 is lowest priority).
     * Returns 0 on success, or negative errno on failure.
     */
    fun setProcessNice(pid: Int, niceValue: Int): Int {
        if (pid <= 0) return -1
        val clampedNice = niceValue.coerceIn(-20, 19)
        if (isNativeLoaded) {
            return nativeSetProcessNice(pid, clampedNice)
        }
        // JVM/Linux CLI fallback using renice
        return try {
            val process = ProcessBuilder("renice", "-n", clampedNice.toString(), "-p", pid.toString()).start()
            val exitCode = process.waitFor()
            if (exitCode == 0) 0 else -1
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Pins a process to LITTLE efficiency cores (typically cores 0..maxLittleCore, default 0..3)
     * and deprioritizes CPU scheduling (nice 19) to prevent battery drain and thermal throttling.
     */
    fun pinToLittleCores(pid: Int, maxLittleCore: Int = 3, niceValue: Int = 19): Boolean {
        if (pid <= 0) return false
        val availableCores = Runtime.getRuntime().availableProcessors()
        val effectiveMax = maxLittleCore.coerceAtMost(availableCores - 1).coerceAtLeast(0)
        val mask = (1L shl (effectiveMax + 1)) - 1L

        val affinityResult = setCpuAffinity(pid, mask)
        val niceResult = setProcessNice(pid, niceValue)
        return affinityResult == 0 && niceResult == 0
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
    private external fun nativeSendSignalToProcessGroup(pgid: Int, sig: Int): Int
    private external fun nativeSendSignalToProcess(pid: Int, sig: Int): Int
    private external fun nativeGetProcessState(pid: Int): String?
    private external fun nativeSetCpuAffinity(pid: Int, cpuMask: Long): Int
    private external fun nativeSetProcessNice(pid: Int, niceValue: Int): Int
}
