package com.pocketserver.hub.alpine

import com.google.common.truth.Truth.assertThat
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ApkIndexAndResolverTest {

    private lateinit var parser: ApkIndexParser

    @Before
    fun setUp() {
        parser = ApkIndexParser()
    }

    private val sampleApkIndexText = """
        C:Q1abc123
        P:musl
        V:1.2.5-r0
        A:aarch64
        S:400000
        I:900000
        T:Standard C library for Alpine
        p:so:libc.musl-aarch64.so.1=1

        C:Q1def456
        P:openssl
        V:3.3.2-r0
        A:aarch64
        S:1500000
        I:4200000
        T:Toolkit for SSL and TLS protocols
        D:so:libc.musl-aarch64.so.1
        p:so:libssl.so.3=3 so:libcrypto.so.3=3 cmd:openssl

        C:Q1ghi789
        P:ffmpeg
        V:6.1.1-r0
        A:aarch64
        S:18000000
        I:52000000
        T:Complete solution to record and convert audio and video
        D:so:libc.musl-aarch64.so.1 so:libssl.so.3 so:libcrypto.so.3
        p:cmd:ffmpeg cmd:ffprobe
    """.trimIndent()

    @Test
    fun testParsePlainTextApkIndex() {
        val stream = ByteArrayInputStream(sampleApkIndexText.toByteArray(Charsets.UTF_8))
        val packages = parser.parseText(stream)

        assertThat(packages.size).isEqualTo(3)

        val ffmpeg = packages.find { it.packageName == "ffmpeg" }
        assertThat(ffmpeg).isNotNull()
        assertThat(ffmpeg!!.version).isEqualTo("6.1.1-r0")
        assertThat(ffmpeg.compressedSizeBytes).isEqualTo(18000000L)
        assertThat(ffmpeg.dependencies).contains("so:libssl.so.3")
        assertThat(ffmpeg.provides).contains("cmd:ffmpeg")
    }

    @Test
    fun testParseGzippedTarApkIndex() {
        // Compress the APKINDEX into an in-memory .tar.gz archive
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzipOut ->
            TarArchiveOutputStream(gzipOut).use { tarOut ->
                val bytes = sampleApkIndexText.toByteArray(Charsets.UTF_8)
                val entry = TarArchiveEntry("APKINDEX")
                entry.size = bytes.size.toLong()
                tarOut.putArchiveEntry(entry)
                tarOut.write(bytes)
                tarOut.closeArchiveEntry()
            }
        }

        val parsedPackages = parser.parseArchive(ByteArrayInputStream(baos.toByteArray()))
        assertThat(parsedPackages.size).isEqualTo(3)
        assertThat(parsedPackages.map { it.packageName }).containsExactly("musl", "openssl", "ffmpeg")
    }

    @Test
    fun testDependencyResolverTransitiveClosure() {
        val stream = ByteArrayInputStream(sampleApkIndexText.toByteArray(Charsets.UTF_8))
        val packages = parser.parseText(stream)
        val resolver = ApkDependencyResolver(packages)

        // Resolve ffmpeg (should pull openssl because ffmpeg requires so:libssl.so.3)
        val result = resolver.resolve("ffmpeg")
        assertThat(result.isResolved).isTrue()
        assertThat(result.missingDependencies).isEmpty()

        val planNames = result.orderedInstallPlan.map { it.packageName }
        // openssl must be installed before ffmpeg
        assertThat(planNames).containsExactly("openssl", "ffmpeg").inOrder()
    }

    @Test
    fun testDependencyResolverMissingDependency() {
        val packages = listOf(
            AlpinePackageRecord(
                packageName = "standalone-tool",
                version = "1.0.0",
                architecture = "aarch64",
                description = "Tool with missing dependency",
                compressedSizeBytes = 1000,
                installedSizeBytes = 3000,
                checksum = "dummy",
                dependencies = listOf("non-existent-lib")
            )
        )
        val resolver = ApkDependencyResolver(packages)
        val result = resolver.resolve("standalone-tool")

        assertThat(result.isResolved).isFalse()
        assertThat(result.missingDependencies).contains("non-existent-lib")
    }
}
