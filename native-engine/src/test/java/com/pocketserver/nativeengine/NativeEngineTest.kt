package com.pocketserver.nativeengine

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NativeEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `is16KbAligned correctly identifies 16KB aligned ELF64 binary`() {
        val elfFile = tempFolder.newFile("test_16kb.so")
        writeSyntheticElf64(elfFile, pageSize = 16384L)

        val isAligned = NativeEngine.is16KbAligned(elfFile)
        assertThat(isAligned).isTrue()
    }

    @Test
    fun `is16KbAligned rejects 4KB aligned legacy ELF64 binary`() {
        val elfFile = tempFolder.newFile("test_4kb.so")
        writeSyntheticElf64(elfFile, pageSize = 4096L)

        val isAligned = NativeEngine.is16KbAligned(elfFile)
        assertThat(isAligned).isFalse()
    }

    @Test
    fun `createRelativeSymlink creates valid relative symlink`() {
        val targetDir = tempFolder.newFolder("store", "pkg-1", "bin")
        val targetFile = File(targetDir, "python").apply { writeText("#!/bin/sh\necho ok") }

        val sandboxDir = tempFolder.newFolder("sandbox", "usr", "bin")
        val linkFile = File(sandboxDir, "python")

        val result = NativeEngine.createRelativeSymlink("../../../store/pkg-1/bin/python", linkFile)
        assertThat(result).isEqualTo(0)
        assertThat(linkFile.exists()).isTrue()
        assertThat(linkFile.readText()).isEqualTo("#!/bin/sh\necho ok")
    }

    @Test
    fun `atomicRename moves directory atomically`() {
        val staging = tempFolder.newFolder(".staging-test-hash")
        File(staging, "manifest.json").writeText("{\"status\":\"ready\"}")

        val destination = File(tempFolder.root, "store/live-pkg")
        destination.parentFile?.mkdirs()

        val result = NativeEngine.atomicRename(staging, destination)
        assertThat(result).isEqualTo(0)
        assertThat(staging.exists()).isFalse()
        assertThat(destination.exists()).isTrue()
        assertThat(File(destination, "manifest.json").readText()).contains("ready")
    }

    private fun writeSyntheticElf64(destination: File, pageSize: Long) {
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
            buf.put(2.toByte()) // EI_CLASS = 64-bit
            buf.put(1.toByte()) // EI_DATA = Little Endian
            buf.put(1.toByte()) // EI_VERSION = 1
            buf.put(0.toByte()) // EI_OSABI = System V
            buf.position(16) // Padding

            // ELF Header fields
            buf.putShort(3.toShort()) // e_type = ET_DYN (Shared Object)
            buf.putShort(183.toShort()) // e_machine = EM_AARCH64 (ARM64)
            buf.putInt(1) // e_version
            buf.putLong(0x1000L) // e_entry
            buf.putLong(phdrOffset) // e_phoff
            buf.putLong(0L) // e_shoff
            buf.putInt(0) // e_flags
            buf.putShort(headerSize.toShort()) // e_ehsize
            buf.putShort(phdrSize.toShort()) // e_phentsize
            buf.putShort(1.toShort()) // e_phnum = 1 program header
            buf.putShort(0.toShort()) // e_shentsize
            buf.putShort(0.toShort()) // e_shnum
            buf.putShort(0.toShort()) // e_shstrndx

            // Program Header (PT_LOAD)
            buf.putInt(1) // p_type = PT_LOAD
            buf.putInt(5) // p_flags = PF_R | PF_X
            buf.putLong(0L) // p_offset
            buf.putLong(0x10000L) // p_vaddr
            buf.putLong(0x10000L) // p_paddr
            buf.putLong(1024L) // p_filesz
            buf.putLong(1024L) // p_memsz
            buf.putLong(pageSize) // p_align (e.g. 16384 for 16KB, 4096 for 4KB)

            raf.write(buf.array())
        }
    }
}
