package com.pocketserver.toolchain.zstd

import com.github.luben.zstd.ZstdOutputStream
import com.google.common.truth.Truth.assertThat
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class ZstdExtractorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val extractor = ZstdExtractor()

    @Test
    fun `extractTarZstStream extracts files and preserves permissions`() {
        val destDir = tempFolder.newFolder("extracted")

        // Create in-memory .tar.zst
        val tarZstBytes = createSyntheticTarZst(
            listOf(
                TarItem("bin/sample-app", "#!/bin/sh\necho sample", mode = 0b111_101_101), // 0755
                TarItem("etc/config.json", "{\"key\":\"val\"}", mode = 0b110_100_100)      // 0644
            )
        )

        val result = extractor.extractTarZstStream(ByteArrayInputStream(tarZstBytes), destDir)

        assertThat(result.totalFilesExtracted).isEqualTo(2)
        assertThat(result.totalBytesUncompressed).isGreaterThan(0L)

        val sampleApp = File(destDir, "bin/sample-app")
        assertThat(sampleApp.exists()).isTrue()
        assertThat(sampleApp.readText()).contains("sample")
        assertThat(sampleApp.canExecute()).isTrue()

        val configJson = File(destDir, "etc/config.json")
        assertThat(configJson.exists()).isTrue()
        assertThat(configJson.readText()).contains("val")
    }

    @Test
    fun `extractArchiveFile extracts zip archives correctly`() {
        val destDir = tempFolder.newFolder("extracted_zip")
        val zipFile = tempFolder.newFile("sample.zip")

        java.util.zip.ZipOutputStream(zipFile.outputStream()).use { zos ->
            val entry = java.util.zip.ZipEntry("bun-linux-aarch64-android/bun")
            zos.putNextEntry(entry)
            zos.write("#!/bin/sh\necho bun".toByteArray())
            zos.closeEntry()
        }

        val result = extractor.extractArchiveFile(zipFile, destDir)
        assertThat(result.totalFilesExtracted).isEqualTo(1)

        val extractedBun = File(destDir, "bun-linux-aarch64-android/bun")
        assertThat(extractedBun.exists()).isTrue()
        assertThat(extractedBun.readText()).contains("bun")
        assertThat(extractedBun.canExecute()).isTrue()
    }

    @Test
    fun `extractArchiveFile extracts targz archives correctly`() {
        val destDir = tempFolder.newFolder("extracted_targz")
        val tarGzFile = tempFolder.newFile("sample.tar.gz")

        val byteOut = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(byteOut).use { gzOut ->
            TarArchiveOutputStream(gzOut).use { tarOut ->
                val bytes = "#!/bin/sh\necho caddy".toByteArray()
                val entry = TarArchiveEntry("caddy").apply {
                    size = bytes.size.toLong()
                    mode = 0b111_101_101
                }
                tarOut.putArchiveEntry(entry)
                tarOut.write(bytes)
                tarOut.closeArchiveEntry()
                tarOut.finish()
            }
        }
        tarGzFile.writeBytes(byteOut.toByteArray())

        val result = extractor.extractArchiveFile(tarGzFile, destDir)
        assertThat(result.totalFilesExtracted).isEqualTo(1)

        val extractedCaddy = File(destDir, "caddy")
        assertThat(extractedCaddy.exists()).isTrue()
        assertThat(extractedCaddy.readText()).contains("caddy")
        assertThat(extractedCaddy.canExecute()).isTrue()
    }

    private data class TarItem(val path: String, val content: String, val mode: Int)

    private fun createSyntheticTarZst(items: List<TarItem>): ByteArray {
        val byteOut = ByteArrayOutputStream()
        ZstdOutputStream(byteOut).use { zstdOut ->
            TarArchiveOutputStream(zstdOut).use { tarOut ->
                for (item in items) {
                    val bytes = item.content.toByteArray(Charsets.UTF_8)
                    val entry = TarArchiveEntry(item.path).apply {
                        mode = item.mode
                        size = bytes.size.toLong()
                    }
                    tarOut.putArchiveEntry(entry)
                    tarOut.write(bytes)
                    tarOut.closeArchiveEntry()
                }
                tarOut.finish()
            }
        }
        return byteOut.toByteArray()
    }
}
