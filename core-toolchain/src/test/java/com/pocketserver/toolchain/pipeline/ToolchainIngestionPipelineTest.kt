package com.pocketserver.toolchain.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.luben.zstd.ZstdOutputStream
import com.google.common.truth.Truth.assertThat
import com.pocketserver.storage.cas.ContentAddressableStore
import com.pocketserver.storage.db.PocketDatabase
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolchainIngestionPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: PocketDatabase
    private lateinit var casStore: ContentAddressableStore
    private lateinit var pipeline: ToolchainIngestionPipeline

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = PocketDatabase.createInMemory(context)
        val storeRoot = tempFolder.newFolder("files", "store")
        casStore = ContentAddressableStore(storeRoot, database)
        pipeline = ToolchainIngestionPipeline(casStore)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `ingestArchive completes 4-stage pipeline successfully`() = runBlocking {
        val archiveBytes = createSampleTarZst("bin/bun", "#!/bin/sh\necho bun")
        val archiveFile = tempFolder.newFile("bun.tar.zst").apply { writeBytes(archiveBytes) }

        val expectedSha = calculateSha256(archiveBytes)
        val result = pipeline.ingestArchive(
            archiveFile = archiveFile,
            packageName = "bun",
            version = "1.2.0",
            expectedSha256 = expectedSha
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.packageId).isEqualTo(expectedSha)
        assertThat(result.storeDirectory).isNotNull()
        assertThat(result.storeDirectory?.exists()).isTrue()
        assertThat(result.storeDirectory?.name).isEqualTo("$expectedSha-bun")

        // Verify stored executable exists
        val binaryFile = File(result.storeDirectory, "bin/bun")
        assertThat(binaryFile.exists()).isTrue()
        assertThat(binaryFile.canExecute()).isTrue()

        // Verify Room Database entry
        val entity = database.toolchainDao().getById(expectedSha)
        assertThat(entity).isNotNull()
        assertThat(entity?.name).isEqualTo("bun")
        assertThat(entity?.is16KbAligned).isTrue()
    }

    @Test
    fun `ingestArchive aborts and cleans staging on checksum mismatch`() = runBlocking {
        val archiveBytes = createSampleTarZst("bin/python", "#!/bin/sh\necho python")
        val archiveFile = tempFolder.newFile("python.tar.zst").apply { writeBytes(archiveBytes) }

        val wrongSha = "0000000000000000000000000000000000000000000000000000000000000000"
        val result = pipeline.ingestArchive(
            archiveFile = archiveFile,
            packageName = "python",
            version = "3.12.7",
            expectedSha256 = wrongSha
        )

        assertThat(result.isSuccess).isFalse()
        assertThat(result.errorMessage).contains("Checksum mismatch")

        // Verify staging directory was purged
        val stagingDir = File(casStore.storeRootDir, ".staging")
        assertThat(stagingDir.listFiles()?.filter { it.name.startsWith("staging-") }).isEmpty()
    }

    private fun createSampleTarZst(path: String, content: String): ByteArray {
        val byteOut = ByteArrayOutputStream()
        ZstdOutputStream(byteOut).use { zstdOut ->
            TarArchiveOutputStream(zstdOut).use { tarOut ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                val entry = TarArchiveEntry(path).apply {
                    mode = 0b111_101_101 // 0755
                    size = bytes.size.toLong()
                }
                tarOut.putArchiveEntry(entry)
                tarOut.write(bytes)
                tarOut.closeArchiveEntry()
                tarOut.finish()
            }
        }
        return byteOut.toByteArray()
    }

    private fun calculateSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
