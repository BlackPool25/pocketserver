package com.pocketserver.toolchain.pipeline

import com.pocketserver.storage.cas.ContentAddressableStore
import com.pocketserver.toolchain.elf.ElfAlignmentEngine
import com.pocketserver.toolchain.zstd.ZstdExtractor
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Orchestrates the Atomic 4-Stage Toolchain Ingestion Pipeline:
 *
 * Stage 1: Stream archive into memory while computing streaming SHA-256 digest.
 * Stage 2: Verify checksum against expected canonical hash.
 * Stage 3: Zstd-JNI streaming decompression into isolated staging folder (.staging-<uuid>).
 * Stage 4: 16 KB page-alignment ELF audit across all extracted binaries.
 * Stage 5: Atomic POSIX directory commit into canonical CAS store (<hash>-<name>).
 */
class ToolchainIngestionPipeline(
    private val casStore: ContentAddressableStore,
    private val zstdExtractor: ZstdExtractor = ZstdExtractor(),
    private val elfAlignmentEngine: ElfAlignmentEngine = ElfAlignmentEngine()
) {

    data class PipelineResult(
        val isSuccess: Boolean,
        val packageId: String,
        val storeDirectory: File?,
        val totalBytesExtracted: Long,
        val is16KbCompliant: Boolean,
        val errorMessage: String? = null
    )

    /**
     * Ingests a .tar.zst toolchain archive into the Content-Addressable Store.
     */
    suspend fun ingestArchive(
        archiveFile: File,
        packageName: String,
        version: String,
        expectedSha256: String? = null
    ): PipelineResult {
        if (!archiveFile.exists() || !archiveFile.isFile) {
            return PipelineResult(
                isSuccess = false,
                packageId = "",
                storeDirectory = null,
                totalBytesExtracted = 0,
                is16KbCompliant = false,
                errorMessage = "Archive file not found: ${archiveFile.absolutePath}"
            )
        }

        // Stage 1 & 2: Calculate and verify SHA-256
        val digest = MessageDigest.getInstance("SHA-256")
        archiveFile.inputStream().use { fis ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }

        if (expectedSha256 != null && !computedSha256.equals(expectedSha256, ignoreCase = true)) {
            return PipelineResult(
                isSuccess = false,
                packageId = computedSha256,
                storeDirectory = null,
                totalBytesExtracted = 0,
                is16KbCompliant = false,
                errorMessage = "Checksum mismatch! Expected: $expectedSha256, Computed: $computedSha256"
            )
        }

        // Stage 3: Allocate staging directory and extract archive stream
        val stagingDir = casStore.beginStaging(packageName)
        val extractionResult = try {
            zstdExtractor.extractArchiveFile(archiveFile, stagingDir)
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            return PipelineResult(
                isSuccess = false,
                packageId = computedSha256,
                storeDirectory = null,
                totalBytesExtracted = 0,
                is16KbCompliant = false,
                errorMessage = "Archive extraction failed: ${e.message}"
            )
        }

        // Stage 4: Verify 16 KB ELF Alignment across all extracted binaries
        val scanResult = elfAlignmentEngine.scanDirectory(stagingDir)
        if (!scanResult.isFully16KbCompliant) {
            val badFiles = scanResult.nonCompliantFiles.joinToString { it.name }
            stagingDir.deleteRecursively()
            return PipelineResult(
                isSuccess = false,
                packageId = computedSha256,
                storeDirectory = null,
                totalBytesExtracted = extractionResult.totalBytesUncompressed,
                is16KbCompliant = false,
                errorMessage = "16KB Page Alignment check failed on: $badFiles"
            )
        }

        // Stage 5: Atomic POSIX commit into canonical CAS store
        val commitResult = casStore.commitStagedPackage(
            stagedDir = stagingDir,
            expectedHash = computedSha256,
            packageName = packageName,
            version = version,
            is16KbAligned = true
        )

        return if (commitResult.isSuccess) {
            PipelineResult(
                isSuccess = true,
                packageId = computedSha256,
                storeDirectory = commitResult.getOrNull(),
                totalBytesExtracted = extractionResult.totalBytesUncompressed,
                is16KbCompliant = true
            )
        } else {
            stagingDir.deleteRecursively()
            PipelineResult(
                isSuccess = false,
                packageId = computedSha256,
                storeDirectory = null,
                totalBytesExtracted = extractionResult.totalBytesUncompressed,
                is16KbCompliant = true,
                errorMessage = commitResult.exceptionOrNull()?.message
            )
        }
    }
}
