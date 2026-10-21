package com.pocketserver.toolchain.elf

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ElfAlignmentEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val engine = ElfAlignmentEngine()

    @Test
    fun `validateFile confirms 16KB aligned ELF64 binary`() {
        val file = tempFolder.newFile("libvalid.so")
        writeSyntheticElf(file, pageSize = 16384L)

        val result = engine.validateFile(file)
        assertThat(result.isElf).isTrue()
        assertThat(result.is64Bit).isTrue()
        assertThat(result.is16KbAligned).isTrue()
        assertThat(result.loadSegments).isNotEmpty()
        assertThat(result.loadSegments.first().align).isEqualTo(16384L)
    }

    @Test
    fun `validateFile rejects 4KB aligned legacy ELF64 binary`() {
        val file = tempFolder.newFile("liblegacy.so")
        writeSyntheticElf(file, pageSize = 4096L)

        val result = engine.validateFile(file)
        assertThat(result.isElf).isTrue()
        assertThat(result.is64Bit).isTrue()
        assertThat(result.is16KbAligned).isFalse()
        assertThat(result.errorMessage).contains("4KB aligned")
    }

    @Test
    fun `scanDirectory detects non-compliant binaries in package directory`() {
        val packageDir = tempFolder.newFolder("toolchain-test")
        val binDir = File(packageDir, "bin").apply { mkdirs() }
        val libDir = File(packageDir, "lib").apply { mkdirs() }

        val goodBin = File(binDir, "node")
        val badLib = File(libDir, "libbroken.so")

        writeSyntheticElf(goodBin, pageSize = 16384L)
        writeSyntheticElf(badLib, pageSize = 4096L)

        val scan = engine.scanDirectory(packageDir)
        assertThat(scan.totalElfFilesScanned).isEqualTo(2)
        assertThat(scan.compliantCount).isEqualTo(1)
        assertThat(scan.nonCompliantFiles).containsExactly(badLib)
        assertThat(scan.isFully16KbCompliant).isFalse()
    }

    private fun writeSyntheticElf(destination: File, pageSize: Long) {
        val headerSize = 64
        val phdrSize = 56
        val phdrOffset = headerSize.toLong()

        RandomAccessFile(destination, "rw").use { raf ->
            val buf = ByteBuffer.allocate(headerSize + phdrSize).order(ByteOrder.LITTLE_ENDIAN)

            // ELF Identification
            buf.put(0x7F.toByte())
            buf.put('E'.code.toByte())
            buf.put('L'.code.toByte())
            buf.put('F'.code.toByte())
            buf.put(2.toByte()) // 64-bit
            buf.put(1.toByte()) // Little Endian
            buf.put(1.toByte()) // Version
            buf.put(0.toByte()) // System V
            buf.position(16)

            // ELF Header
            buf.putShort(3.toShort()) // ET_DYN
            buf.putShort(183.toShort()) // EM_AARCH64
            buf.putInt(1)
            buf.putLong(0x1000L)
            buf.putLong(phdrOffset)
            buf.putLong(0L)
            buf.putInt(0)
            buf.putShort(headerSize.toShort())
            buf.putShort(phdrSize.toShort())
            buf.putShort(1.toShort()) // 1 program header
            buf.putShort(0.toShort())
            buf.putShort(0.toShort())
            buf.putShort(0.toShort())

            // PT_LOAD Program Header
            buf.putInt(1) // PT_LOAD
            buf.putInt(5) // PF_R | PF_X
            buf.putLong(0L) // p_offset
            buf.putLong(0x10000L) // p_vaddr
            buf.putLong(0x10000L) // p_paddr
            buf.putLong(512L) // p_filesz
            buf.putLong(512L) // p_memsz
            buf.putLong(pageSize) // p_align

            raf.write(buf.array())
        }
    }
}
