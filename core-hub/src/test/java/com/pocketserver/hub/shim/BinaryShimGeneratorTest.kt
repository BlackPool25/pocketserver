package com.pocketserver.hub.shim

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BinaryShimGeneratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var binDir: File
    private lateinit var generator: BinaryShimGenerator

    @Before
    fun setUp() {
        binDir = tempFolder.newFolder("bin")
        generator = BinaryShimGenerator(binDir)
    }

    @Test
    fun testGenerateHermeticShimWithoutInterpreter() {
        val prefixDir = tempFolder.newFolder("pkg_prefix")
        val targetBinary = File(prefixDir, "bin/mytool").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh\necho 'hello'")
        }

        val shim = generator.generateShim(
            BinaryShimGenerator.ShimConfiguration(
                commandName = "mytool",
                targetBinary = targetBinary,
                prefixDir = prefixDir,
                environmentVariables = mapOf(
                    "FOO" to "bar",
                    "OPENBLAS_NUM_THREADS" to "4"
                )
            )
        )

        assertThat(shim.exists()).isTrue()
        assertThat(shim.canExecute()).isTrue()

        val text = shim.readText()
        assertThat(text).contains("export POCKET_SERVER_SHIM=1")
        assertThat(text).contains("export PREFIX=\"${prefixDir.absolutePath}\"")
        assertThat(text).contains("export FOO=\"bar\"")
        assertThat(text).contains("export OPENBLAS_NUM_THREADS=\"4\"")
        assertThat(text).contains("exec \"${targetBinary.absolutePath}\" \"\$@\"")
    }

    @Test
    fun testGenerateHermeticShimWithDynamicLinkerVirtualization() {
        val prefixDir = tempFolder.newFolder("musl_prefix")
        val targetBinary = File(prefixDir, "bin/ffmpeg").apply {
            parentFile?.mkdirs()
            writeBytes("ELF MUSL BINARY".toByteArray())
        }
        val muslInterp = File(prefixDir, "lib/ld-musl-aarch64.so.1").apply {
            parentFile?.mkdirs()
            writeBytes("INTERPRETER".toByteArray())
        }

        val shim = generator.generateShim(
            BinaryShimGenerator.ShimConfiguration(
                commandName = "ffmpeg",
                targetBinary = targetBinary,
                prefixDir = prefixDir,
                interpreterBinary = muslInterp,
                libraryDirs = listOf(File(prefixDir, "lib"))
            )
        )

        val text = shim.readText()
        // Must contain dynamic linker virtualization to prevent ENOENT traps on unrooted Android
        assertThat(text).contains("INTERP=\"${muslInterp.absolutePath}\"")
        assertThat(text).contains("exec \"\$INTERP\" --library-path")
    }

    @Test
    fun testRemoveAndCheckShim() {
        val prefixDir = tempFolder.newFolder("prefix")
        val targetBinary = File(prefixDir, "bin/tool").apply {
            parentFile?.mkdirs()
            writeText("dummy")
        }

        generator.generateShim(
            BinaryShimGenerator.ShimConfiguration(
                commandName = "test-tool",
                targetBinary = targetBinary,
                prefixDir = prefixDir
            )
        )

        assertThat(generator.hasShim("test-tool")).isTrue()
        assertThat(generator.removeShim("test-tool")).isTrue()
        assertThat(generator.hasShim("test-tool")).isFalse()
    }
}
