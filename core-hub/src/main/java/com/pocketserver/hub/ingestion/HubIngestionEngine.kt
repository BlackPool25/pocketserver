package com.pocketserver.hub.ingestion

import com.pocketserver.hub.model.HubPackageDescriptor
import com.pocketserver.storage.cas.ContentAddressableStore
import com.pocketserver.storage.db.PocketDatabase
import com.pocketserver.storage.db.ToolchainEntity
import com.pocketserver.toolchain.pipeline.ToolchainIngestionPipeline
import java.io.File

/**
 * Curated Hub Ingestion Engine.
 *
 * Coordinates atomic downloading, SHA-256 verification, Zstd-JNI decompression,
 * 16 KB ELF boundary validation, and Room database catalog registration for curated hub runtimes.
 */
class HubIngestionEngine(
    private val casStore: ContentAddressableStore,
    private val database: PocketDatabase,
    private val pipeline: ToolchainIngestionPipeline = ToolchainIngestionPipeline(casStore)
) {

    data class IngestionResult(
        val isSuccess: Boolean,
        val canonicalDir: File?,
        val descriptor: HubPackageDescriptor,
        val totalBytesExtracted: Long,
        val is16KbCompliant: Boolean,
        val errorMessage: String? = null
    )

    /**
     * Ingests a curated Hub package archive into Content-Addressable Storage (CAS)
     * and records its metadata into Room.
     */
    suspend fun ingestPackage(
        descriptor: HubPackageDescriptor,
        archiveFile: File,
        skipShaCheck: Boolean = false
    ): IngestionResult {
        val expectedSha = if (skipShaCheck) null else descriptor.sha256Checksum

        val pipelineResult = pipeline.ingestArchive(
            archiveFile = archiveFile,
            packageName = descriptor.packageName,
            version = descriptor.engineVersion,
            expectedSha256 = expectedSha
        )

        if (!pipelineResult.isSuccess || pipelineResult.storeDirectory == null) {
            return IngestionResult(
                isSuccess = false,
                canonicalDir = null,
                descriptor = descriptor,
                totalBytesExtracted = 0,
                is16KbCompliant = false,
                errorMessage = pipelineResult.errorMessage ?: "Pipeline ingestion failed"
            )
        }

        // Record metadata in Room database
        val storeDir = pipelineResult.storeDirectory
        val entity = ToolchainEntity(
            packageId = pipelineResult.packageId,
            name = descriptor.packageName,
            version = descriptor.engineVersion,
            sizeBytes = pipelineResult.totalBytesExtracted,
            is16KbAligned = pipelineResult.is16KbCompliant,
            installedAtEpochMs = System.currentTimeMillis()
        )
        database.toolchainDao().insert(entity)

        return IngestionResult(
            isSuccess = true,
            canonicalDir = storeDir,
            descriptor = descriptor,
            totalBytesExtracted = pipelineResult.totalBytesExtracted,
            is16KbCompliant = pipelineResult.is16KbCompliant
        )
    }

    /**
     * Checks if a curated package is already installed and registered in the database.
     */
    suspend fun isPackageInstalled(descriptor: HubPackageDescriptor): Boolean {
        val allToolchains = database.toolchainDao().getAll()
        val match = allToolchains.find {
            it.name.equals(descriptor.packageName, ignoreCase = true) &&
                    it.version.equals(descriptor.engineVersion, ignoreCase = true)
        }
        if (match != null) {
            val storeDir = File(casStore.storeRootDir, "${match.packageId}-${match.name}")
            return storeDir.exists() && storeDir.isDirectory
        }
        return false
    }
}
