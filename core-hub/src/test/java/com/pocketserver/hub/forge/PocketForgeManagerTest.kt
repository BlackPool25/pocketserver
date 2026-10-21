package com.pocketserver.hub.forge

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pocketserver.hub.search.PackageTier
import com.pocketserver.hub.search.UnifiedPackageSearchDb
import com.pocketserver.hub.shim.BinaryShimGenerator
import com.pocketserver.storage.cas.ContentAddressableStore
import com.pocketserver.storage.db.PocketDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
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
class PocketForgeManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var searchDb: UnifiedPackageSearchDb
    private lateinit var database: PocketDatabase
    private lateinit var casStore: ContentAddressableStore
    private lateinit var shimGenerator: BinaryShimGenerator
    private lateinit var verifier: PocketForgeVerifier
    private lateinit var manager: PocketForgeManager

    @Before
    fun setUp() {
        searchDb = UnifiedPackageSearchDb()
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PocketDatabase::class.java
        ).allowMainThreadQueries().build()

        val casRoot = tempFolder.newFolder("cas_root")
        casStore = ContentAddressableStore(casRoot, database)
        val binDir = tempFolder.newFolder("files_bin")
        shimGenerator = BinaryShimGenerator(binDir)
        verifier = PocketForgeVerifier()

        manager = PocketForgeManager(
            searchDb = searchDb,
            casStore = casStore,
            shimGenerator = shimGenerator,
            verifier = verifier
        )
    }

    @After
    fun tearDown() {
        searchDb.close()
        database.close()
    }

    @Test
    fun testDefaultRepositoriesLoaded() {
        val repos = manager.getRepositories()
        assertThat(repos.size).isAtLeast(2)
        val ids = repos.map { it.id }
        assertThat(ids).contains("tur-mirror")
        assertThat(ids).contains("local-ai-forge")
    }

    @Test
    fun testAddAndRemoveCustomRepository() {
        val testKeyPair = verifier.generateKeyPairForTesting()
        val customRepo = PocketForgeRepository(
            id = "custom-game-servers",
            name = "Community Game Servers",
            description = "Lightweight Minecraft Bedrock and Terraria servers for Android",
            manifestUrl = "https://example.com/games/index.json",
            signatureUrl = "https://example.com/games/index.json.minisig",
            publicKeyBase64 = testKeyPair.publicKeyBase64
        )

        val addResult = manager.addRepository(customRepo)
        assertThat(addResult.isSuccess).isTrue()
        assertThat(manager.getRepository("custom-game-servers")).isNotNull()

        val removed = manager.removeRepository("custom-game-servers")
        assertThat(removed).isTrue()
        assertThat(manager.getRepository("custom-game-servers")).isNull()
    }

    @Test
    fun testSyncRepositoryWithCryptographicSignatureAndSearchIntegration() {
        val testKeyPair = verifier.generateKeyPairForTesting()
        val repo = PocketForgeRepository(
            id = "test-ai-repo",
            name = "Test AI Forge",
            description = "Community AI inference engines",
            manifestUrl = "https://example.com/ai/index.json",
            signatureUrl = "https://example.com/ai/index.json.minisig",
            publicKeyBase64 = testKeyPair.publicKeyBase64
        )
        manager.addRepository(repo)

        val manifestContent = """
            {
              "repository": "Test AI Forge",
              "maintainer": "AI Community",
              "version": 1,
              "packages": [
                {
                  "id": "whisper-mobile",
                  "name": "Whisper Mobile ASR",
                  "version": "1.7.0",
                  "category": "Speech Recognition",
                  "description": "Embedded speech-to-text transcriber running directly on phone",
                  "size_compressed": 12000000,
                  "installed_size": 35000000,
                  "download_url": "https://example.com/whisper-mobile.tar.zst",
                  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                  "primary_binary": "bin/whisper"
                }
              ]
            }
        """.trimIndent()

        val signatureContent = testKeyPair.signManifest(manifestContent)

        val syncResult = manager.syncRepository(
            id = "test-ai-repo",
            manifestContent = manifestContent,
            signatureContent = signatureContent
        )

        assertThat(syncResult.isSuccess).isTrue()
        assertThat(syncResult.getOrNull()).isEqualTo(1)

        // Verify package now appears in UnifiedPackageSearchDb
        val searchResults = searchDb.search("whisper", filterTier = PackageTier.POCKETFORGE)
        assertThat(searchResults.size).isEqualTo(1)
        val pkg = searchResults.first()
        assertThat(pkg.name).isEqualTo("Whisper Mobile ASR")
        assertThat(pkg.sourceRepo).isEqualTo("Test AI Forge")
    }

    @Test
    fun testSyncFailsAndRejectsTamperedSignature() {
        val testKeyPair = verifier.generateKeyPairForTesting()
        val repo = PocketForgeRepository(
            id = "tamper-repo",
            name = "Tamper Test Repo",
            description = "Security audit repo",
            manifestUrl = "https://example.com/index.json",
            signatureUrl = "https://example.com/index.json.minisig",
            publicKeyBase64 = testKeyPair.publicKeyBase64
        )
        manager.addRepository(repo)

        val manifestContent = """{"repository": "Tamper Test Repo", "packages": []}"""
        // Tampered signature string
        val forgedSignature = """
            untrusted comment: signature from evil key
            RWT1CQgEBwYFA01VTVBVT0tFVEZPUkdFVFVSVFVSTUlSUk9SRURDQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFB
            trusted comment: timestamp:0
            QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFB
        """.trimIndent()

        val syncResult = manager.syncRepository("tamper-repo", manifestContent, forgedSignature)
        assertThat(syncResult.isFailure).isTrue()
        assertThat(syncResult.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
    }

    @Test
    fun testInstallPackageRejectsTamperedArchive() = runBlocking {
        val fakeArchive = tempFolder.newFile("corrupted-pkg.tar.zst")
        fakeArchive.writeBytes("Malicious binary payload bytes".toByteArray(Charsets.UTF_8))

        val pkg = PocketForgePackage(
            id = "tampered-package",
            name = "Tampered Pkg",
            version = "1.0.0",
            category = "Security Test",
            description = "Tampered package payload",
            sizeCompressedBytes = fakeArchive.length(),
            installedSizeBytes = fakeArchive.length(),
            downloadUrl = "https://example.com/corrupted.tar.zst",
            sha256Checksum = "0".repeat(64), // Mismatched SHA-256
            primaryBinaryRelPath = "bin/tool"
        )

        val installResult = manager.installForgePackage(pkg, fakeArchive)
        assertThat(installResult.isFailure).isTrue()
        assertThat(installResult.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
        // Check quarantine cleanup: corrupted archive deleted
        assertThat(fakeArchive.exists()).isFalse()
    }
}
