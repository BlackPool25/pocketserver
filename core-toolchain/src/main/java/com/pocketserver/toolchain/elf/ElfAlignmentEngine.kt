package com.pocketserver.toolchain.elf

import com.pocketserver.nativeengine.NativeEngine
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Validates ELF binaries and shared libraries for 16 KB page-size compliance
 * on Android 15/16 (Linux ARM64 / x86_64).
 *
 * Android 15 requires that all ELF LOAD segments satisfy:
 *   p_align >= 16384 (16 KB)
 *   (p_vaddr % p_align) == (p_offset % p_align)
 */
class ElfAlignmentEngine {

    data class ProgramHeader(
        val type: Int,
        val flags: Int,
        val offset: Long,
        val vaddr: Long,
        val paddr: Long,
        val fileSize: Long,
        val memSize: Long,
        val align: Long
    )

    data class ValidationResult(
        val file: File,
        val isElf: Boolean,
        val is64Bit: Boolean,
        val is16KbAligned: Boolean,
        val loadSegments: List<ProgramHeader>,
        val errorMessage: String? = null
    )

    data class DirectoryScanResult(
        val totalElfFilesScanned: Int,
        val compliantCount: Int,
        val nonCompliantFiles: List<File>,
        val isFully16KbCompliant: Boolean
    )

    companion object {
        const val REQUIRED_PAGE_SIZE = 16384L // 16 KB
        const val PT_LOAD = 1
        const val ELFCLASS32 = 1
        const val ELFCLASS64 = 2
    }

    /**
     * Inspects a single file to verify 16 KB page alignment.
     */
    fun validateFile(file: File): ValidationResult {
        if (!file.exists() || !file.isFile || file.length() < 64) {
            return ValidationResult(
                file = file,
                isElf = false,
                is64Bit = false,
                is16KbAligned = false,
                loadSegments = emptyList(),
                errorMessage = "File does not exist or is too small to be an ELF binary."
            )
        }

        return RandomAccessFile(file, "r").use { raf ->
            val headerBytes = ByteArray(64)
            raf.readFully(headerBytes)
            val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

            // Verify ELF Magic: 0x7F, 'E', 'L', 'F'
            if (buf.get(0) != 0x7F.toByte() ||
                buf.get(1) != 'E'.code.toByte() ||
                buf.get(2) != 'L'.code.toByte() ||
                buf.get(3) != 'F'.code.toByte()
            ) {
                return@use ValidationResult(
                    file = file,
                    isElf = false,
                    is64Bit = false,
                    is16KbAligned = false,
                    loadSegments = emptyList(),
                    errorMessage = "Not an ELF binary (magic mismatch)."
                )
            }

            val elfClass = buf.get(4).toInt()
            if (elfClass == ELFCLASS32) {
                // 32-bit ELF is not subject to 16KB ARM64 requirements
                return@use ValidationResult(
                    file = file,
                    isElf = true,
                    is64Bit = false,
                    is16KbAligned = true,
                    loadSegments = emptyList(),
                    errorMessage = null
                )
            } else if (elfClass != ELFCLASS64) {
                return@use ValidationResult(
                    file = file,
                    isElf = true,
                    is64Bit = false,
                    is16KbAligned = false,
                    loadSegments = emptyList(),
                    errorMessage = "Unsupported ELF class: $elfClass"
                )
            }

            val ePhOff = buf.getLong(32)
            val ePhEntSize = buf.getShort(54).toInt()
            val ePhNum = buf.getShort(56).toInt()

            if (ePhOff <= 0 || ePhNum <= 0 || ePhEntSize < 56) {
                return@use ValidationResult(
                    file = file,
                    isElf = true,
                    is64Bit = true,
                    is16KbAligned = false,
                    loadSegments = emptyList(),
                    errorMessage = "Corrupted or missing program header table."
                )
            }

            raf.seek(ePhOff)
            val phdrBytes = ByteArray(ePhEntSize)
            val loadSegments = mutableListOf<ProgramHeader>()
            var isAligned = true
            var failureReason: String? = null

            for (i in 0 until ePhNum) {
                raf.readFully(phdrBytes)
                val phBuf = ByteBuffer.wrap(phdrBytes).order(ByteOrder.LITTLE_ENDIAN)
                val pType = phBuf.getInt(0)

                if (pType == PT_LOAD) {
                    val pFlags = phBuf.getInt(4)
                    val pOffset = phBuf.getLong(8)
                    val pVaddr = phBuf.getLong(16)
                    val pPaddr = phBuf.getLong(24)
                    val pFilesz = phBuf.getLong(32)
                    val pMemsz = phBuf.getLong(40)
                    val pAlign = phBuf.getLong(48)

                    val ph = ProgramHeader(
                        type = pType,
                        flags = pFlags,
                        offset = pOffset,
                        vaddr = pVaddr,
                        paddr = pPaddr,
                        fileSize = pFilesz,
                        memSize = pMemsz,
                        align = pAlign
                    )
                    loadSegments.add(ph)

                    if (pAlign < REQUIRED_PAGE_SIZE) {
                        isAligned = false
                        failureReason = "Segment $i has p_align=$pAlign < 16384 (4KB aligned)"
                    }

                    if ((pVaddr % pAlign) != (pOffset % pAlign)) {
                        isAligned = false
                        failureReason = "Segment $i vaddr/offset alignment mismatch: vaddr=0x${pVaddr.toString(16)} offset=0x${pOffset.toString(16)}"
                    }
                }
            }

            if (loadSegments.isEmpty()) {
                isAligned = false
                failureReason = "No PT_LOAD segments found in binary."
            }

            ValidationResult(
                file = file,
                isElf = true,
                is64Bit = true,
                is16KbAligned = isAligned,
                loadSegments = loadSegments,
                errorMessage = failureReason
            )
        }
    }

    /**
     * Recursively scans an extracted toolchain directory to ensure all binaries and .so files
     * meet 16 KB page alignment.
     */
    fun scanDirectory(dir: File): DirectoryScanResult {
        var totalElfScanned = 0
        var compliantCount = 0
        val nonCompliant = mutableListOf<File>()

        dir.walkTopDown().forEach { file ->
            if (file.isFile && (file.parentFile?.name == "bin" || file.name.endsWith(".so") || file.canExecute())) {
                val result = validateFile(file)
                if (result.isElf && result.is64Bit) {
                    totalElfScanned++
                    if (result.is16KbAligned) {
                        compliantCount++
                    } else {
                        nonCompliant.add(file)
                    }
                }
            }
        }

        return DirectoryScanResult(
            totalElfFilesScanned = totalElfScanned,
            compliantCount = compliantCount,
            nonCompliantFiles = nonCompliant,
            isFully16KbCompliant = nonCompliant.isEmpty()
        )
    }
}
