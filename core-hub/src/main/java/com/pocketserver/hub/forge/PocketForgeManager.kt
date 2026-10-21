package com.pocketserver.hub.forge

import com.pocketserver.hub.search.PackageTier
import com.pocketserver.hub.search.UnifiedPackageSearchDb
import com.pocketserver.hub.shim.BinaryShimGenerator
import com.pocketserver.storage.cas.ContentAddressableStore
import com.pocketserver.toolchain.pipeline.ToolchainIngestionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrator for PocketForge decentralized community package repositories
 * (Fedora COPR / Arch AUR / TUR mirror equivalent).
 *
 * Coordinates custom repository subscriptions, Minisign ed25519 signature checks,
 * full-text index synchronization, and secure CAS package installation with hermetic shims.
 */
class PocketForgeManager(
    private val searchDb: UnifiedPackageSearchDb,
    private val casStore: ContentAddressableStore,
    private val shimGenerator: BinaryShimGenerator,
    private val pipeline: ToolchainIngestionPipeline = ToolchainIngestionPipeline(casStore),
    private val verifier: PocketForgeVerifier = PocketForgeVerifier(),
    private val manifestParser: PocketForgeManifestParser = PocketForgeManifestParser()
) {

    private val repositories = ConcurrentHashMap<String, PocketForgeRepository>()

    init {
        // Seed default community repositories
        seedDefaultRepositories()
    }

    private fun seedDefaultRepositories() {
        val turMirror = PocketForgeRepository(
            id = "tur-mirror",
            name = "Termux User Repository (TUR Mirror)",
            description = "Community packages ported for rootless ARM64 Android (Chromium headless, GCC, LLVM, Llama.cpp)",
            manifestUrl = "https://tur.pocketforge.org/index.json",
            signatureUrl = "https://tur.pocketforge.org/index.json.minisig",
            publicKeyBase64 = "RWT1CQgEBwYFA01VTVBVT0tFVEZPUkdFVFVSVFVSTUlSUk9SRURD",
            isEnabled = true,
            isVerified = true
        )

        val localAiForge = PocketForgeRepository(
            id = "local-ai-forge",
            name = "Local-AI Community Forge",
            description = "High-performance on-device AI inference runtimes (Whisper.cpp, Llama.cpp ARM64, Ollama mobile)",
            manifestUrl = "https://hub.pocketforge.org/ai/index.json",
            signatureUrl = "https://hub.pocketforge.org/ai/index.json.minisig",
            publicKeyBase64 = "RWT1CQgEBwYFA0xPQ0FMQUlGT1JHRUNPTU1VTklUWUVEQ0NBQUFB",
            isEnabled = true,
            isVerified = true
        )

        repositories[turMirror.id] = turMirror
        repositories[localAiForge.id] = localAiForge
    }

    /**
     * Lists all registered repositories.
     */
    fun getRepositories(): List<PocketForgeRepository> {
        return repositories.values.toList()
    }

    /**
     * Retrieves a repository by ID.
     */
    fun getRepository(id: String): PocketForgeRepository? {
        return repositories[id]
    }

    /**
     * Registers a new custom community repository.
     */
    fun addRepository(repository: PocketForgeRepository): Result<PocketForgeRepository> {
        if (repository.manifestUrl.isBlank() || repository.publicKeyBase64.isBlank()) {
            return Result.failure(IllegalArgumentException("Manifest URL and Minisign public key are required"))
        }

        // Validate public key format
        try {
            verifier.parsePublicKey(repository.publicKeyBase64)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Invalid Minisign public key: ${e.message}", e))
        }

        repositories[repository.id] = repository
        return Result.success(repository)
    }

    /**
     * Unregisters a repository and removes its indexed packages from the search database.
     */
    fun removeRepository(id: String): Boolean {
        val removed = repositories.remove(id)
        if (removed != null) {
            searchDb.removePackagesBySource(removed.name)
            return true
        }
        return false
    }

    /**
     * Toggles whether a repository is enabled.
     */
    fun toggleRepository(id: String, isEnabled: Boolean): Boolean {
        val repo = repositories[id] ?: return false
        repositories[id] = repo.copy(isEnabled = isEnabled)
        if (!isEnabled) {
            searchDb.removePackagesBySource(repo.name)
        }
        return true
    }

    /**
     * Synchronizes a repository using provided manifest and signature payload strings.
     * Validates cryptographic signature, parses metadata, and updates FTS5 database.
     */
    fun syncRepository(
        id: String,
        manifestContent: String,
        signatureContent: String
    ): Result<Int> {
        val repo = repositories[id]
            ?: return Result.failure(IllegalArgumentException("Repository not found: $id"))

        if (!repo.isEnabled) {
            return Result.failure(IllegalStateException("Repository '$id' is disabled"))
        }

        // 1. Verify Minisign signature
        val isSignatureValid = verifier.verifyManifestSignature(
            manifestContent = manifestContent,
            signatureContent = signatureContent,
            publicKeyBase64 = repo.publicKeyBase64
        )

        if (!isSignatureValid) {
            return Result.failure(SecurityException("Cryptographic verification failed: invalid Minisign signature for repository '${repo.name}'"))
        }

        // 2. Parse and validate manifest schema
        val manifest = try {
            manifestParser.parse(manifestContent)
        } catch (e: Exception) {
            return Result.failure(e)
        }

        // 3. Convert to unified records and index in SQLite FTS5 database
        val unifiedRecords = manifest.packages.map { it.toUnifiedRecord(repo.name) }
        searchDb.removePackagesBySource(repo.name)
        searchDb.indexPackages(unifiedRecords)

        // 4. Update repository metadata
        repositories[id] = repo.copy(
            isVerified = true,
            lastSyncedEpochMs = System.currentTimeMillis(),
            packageCount = unifiedRecords.size
        )

        return Result.success(unifiedRecords.size)
    }

    /**
     * Downloads, validates, and installs a PocketForge package into CAS storage,
     * emitting an executable POSIX shim in `/files/bin/`.
     */
    suspend fun installForgePackage(
        pkg: PocketForgePackage,
        archiveFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        if (!archiveFile.exists() || !archiveFile.isFile) {
            return@withContext Result.failure(IllegalArgumentException("Archive file not found: ${archiveFile.absolutePath}"))
        }

        // 1. Cryptographic SHA-256 verification (Anti-Tampering Gate)
        val isHashValid = verifier.verifySha256(archiveFile, pkg.sha256Checksum)
        if (!isHashValid) {
            archiveFile.delete() // Quarantine cleanup
            return@withContext Result.failure(
                SecurityException("Security violation: Package '${pkg.id}' checksum mismatch. Archive quarantined and deleted.")
            )
        }

        // 2. Ingestion into Content-Addressable Storage (CAS) with 16KB alignment audit
        val pipelineResult = pipeline.ingestArchive(
            archiveFile = archiveFile,
            packageName = pkg.id,
            version = pkg.version,
            expectedSha256 = pkg.sha256Checksum
        )

        val storeDir = pipelineResult.storeDirectory
            ?: return@withContext Result.failure(
                IllegalStateException(pipelineResult.errorMessage ?: "CAS ingestion pipeline failed")
            )

        // 3. Generate hermetic POSIX execution shim in /files/bin/
        val targetBinary = File(storeDir, pkg.primaryBinaryRelPath)
        val shimName = pkg.id.split("-").firstOrNull() ?: pkg.id

        shimGenerator.generateShim(
            BinaryShimGenerator.ShimConfiguration(
                commandName = shimName,
                targetBinary = targetBinary,
                prefixDir = storeDir,
                environmentVariables = pkg.environmentVariables
            )
        )

        // 4. Update database installation flag
        searchDb.setInstalled(pkg.id, true)

        Result.success(storeDir)
    }
}
