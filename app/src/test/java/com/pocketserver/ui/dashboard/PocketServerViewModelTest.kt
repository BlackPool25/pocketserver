package com.pocketserver.ui.dashboard

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pocketserver.storage.db.PocketDatabase
import com.pocketserver.ui.onboarding.StarterTemplate
import com.pocketserver.ui.system.PocketServerTileService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PocketServerViewModelTest {

    private lateinit var context: Context
    private lateinit var database: PocketDatabase
    private lateinit var searchDb: com.pocketserver.hub.search.UnifiedPackageSearchDb
    private lateinit var viewModel: PocketServerViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PocketDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        searchDb = com.pocketserver.hub.search.UnifiedPackageSearchDb()
        viewModel = PocketServerViewModel(
            context = context,
            database = database,
            searchDb = searchDb,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        searchDb.close()
        database.close()
    }

    @Test
    fun testZeroDataStateInitiallyTrue() = runTest(testDispatcher) {
        viewModel.loadServicesSync()
        assertThat(viewModel.services.value).isEmpty()
        assertThat(viewModel.isZeroDataState.value).isTrue()
    }

    @Test
    fun testProvisionTemplateAndProgressiveDisclosure() = runTest(testDispatcher) {
        // 1. User taps 1-tap starter template
        viewModel.provisionTemplateSync(StarterTemplate.FASTAPI_HELLO_WORLD)

        val services = viewModel.services.value
        assertThat(services).hasSize(1)
        assertThat(viewModel.isZeroDataState.value).isFalse()

        val service = services[0]
        assertThat(service.assignedPort).isEqualTo(8000)
        assertThat(service.isExpanded).isFalse() // Surface only

        // 2. User taps "Details & Tools" (Progressive disclosure)
        viewModel.toggleServiceExpansion(service.serviceId)
        val expanded = viewModel.services.value[0]
        assertThat(expanded.isExpanded).isTrue()
    }

    @Test
    fun testFilteringAndSearchQuery() = runTest(testDispatcher) {
        viewModel.provisionTemplateSync(StarterTemplate.FASTAPI_HELLO_WORLD)
        viewModel.provisionTemplateSync(StarterTemplate.REACT_VITE_FRONTEND)

        assertThat(viewModel.services.value).hasSize(2)

        // Search by port 3000
        viewModel.setSearchQuery("3000")
        assertThat(viewModel.filteredServices.value).hasSize(1)
        assertThat(viewModel.filteredServices.value[0].assignedPort).isEqualTo(3000)

        // Reset query and filter by LIVE (both initially STOPPED)
        viewModel.setSearchQuery("")
        viewModel.setFilter(DashboardFilter.LIVE)
        assertThat(viewModel.filteredServices.value).isEmpty()

        // Filter STOPPED
        viewModel.setFilter(DashboardFilter.STOPPED)
        assertThat(viewModel.filteredServices.value).hasSize(2)
    }

    @Test
    fun testToggleServiceSleepState() = runTest(testDispatcher) {
        viewModel.provisionTemplateSync(StarterTemplate.FASTAPI_HELLO_WORLD)

        val serviceId = viewModel.services.value[0].serviceId

        // Initially STOPPED -> toggle turns to LIVE
        viewModel.toggleServiceSleepStateSync(serviceId)
        assertThat(viewModel.services.value[0].status).isEqualTo("LIVE")
        assertThat(PocketServerTileService.isServerActive.value).isTrue()

        // LIVE -> toggle turns to SLEEPING
        viewModel.toggleServiceSleepStateSync(serviceId)
        assertThat(viewModel.services.value[0].status).isEqualTo("SLEEPING")
        assertThat(PocketServerTileService.isServerActive.value).isFalse()
    }

    @Test
    fun testUniversalPackageSearchAcrossTiers() = runTest(testDispatcher) {
        val sample = com.pocketserver.hub.search.UnifiedPackageRecord(
            id = "pkg-sample",
            name = "whisper-test",
            version = "1.0",
            tier = com.pocketserver.hub.search.PackageTier.POCKETFORGE,
            category = "AI",
            description = "Test speech model",
            sizeCompressedBytes = 1000,
            installedSizeBytes = 3000,
            sourceRepo = "Test Forge",
            downloadUrl = "https://example.com/test.tar.zst",
            sha256Checksum = "0".repeat(64),
            primaryBinary = "bin/whisper"
        )
        viewModel.searchDb.indexPackages(listOf(sample))

        viewModel.searchPackagesSync("whisper")
        assertThat(viewModel.packageSearchResults.value).hasSize(1)
        assertThat(viewModel.packageSearchResults.value[0].name).isEqualTo("whisper-test")
        assertThat(viewModel.packageSearchResults.value[0].tierBadge).isEqualTo("[PocketForge]")
    }

    @Test
    fun testCustomPocketForgeRepositoryManagement() = runTest(testDispatcher) {
        viewModel.loadCustomRepositories()
        val initialCount = viewModel.customRepositories.value.size
        assertThat(initialCount).isAtLeast(2)

        val verifier = com.pocketserver.hub.forge.PocketForgeVerifier()
        val keyPair = verifier.generateKeyPairForTesting()

        val newRepo = com.pocketserver.hub.forge.PocketForgeRepository(
            id = "custom-test-repo",
            name = "Custom User Repo",
            description = "Community tools",
            manifestUrl = "https://example.com/repo/index.json",
            signatureUrl = "https://example.com/repo/index.json.minisig",
            publicKeyBase64 = keyPair.publicKeyBase64
        )

        val result = viewModel.addCustomRepository(newRepo)
        assertThat(result.isSuccess).isTrue()
        assertThat(viewModel.customRepositories.value.size).isEqualTo(initialCount + 1)

        val removed = viewModel.removeCustomRepository("custom-test-repo")
        assertThat(removed).isTrue()
        assertThat(viewModel.customRepositories.value.size).isEqualTo(initialCount)
    }

    @Test
    fun testInstallPackageCompletesFullPipeline() = runTest(testDispatcher) {
        val archiveBytes = java.io.ByteArrayOutputStream().apply {
            java.util.zip.ZipOutputStream(this).use { zos ->
                val entry = java.util.zip.ZipEntry("bun-linux-aarch64-android/bun")
                zos.putNextEntry(entry)
                zos.write("#!/bin/sh\necho bun".toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()

        val mockDownloader = object : com.pocketserver.hub.download.PackageDownloader() {
            override suspend fun downloadArchive(
                downloadUrl: String,
                destinationFile: File,
                expectedSha256: String?,
                onProgress: (com.pocketserver.hub.download.InstallProgress) -> Unit
            ): Result<File> {
                destinationFile.parentFile?.mkdirs()
                destinationFile.writeBytes(archiveBytes)
                onProgress(
                    com.pocketserver.hub.download.InstallProgress(
                        stage = com.pocketserver.hub.download.InstallStage.VERIFYING,
                        progressPercent = 100
                    )
                )
                return Result.success(destinationFile)
            }
        }

        val vm = PocketServerViewModel(
            context = context,
            database = database,
            searchDb = searchDb,
            downloader = mockDownloader,
            ioDispatcher = testDispatcher
        )

        val archiveSha256 = java.security.MessageDigest.getInstance("SHA-256")
            .digest(archiveBytes)
            .joinToString("") { "%02x".format(it) }

        val bunRecord = com.pocketserver.hub.registry.HubPackageRegistry.findByRuntime(
            com.pocketserver.hub.model.PolyglotRuntime.JAVASCRIPT
        )!!.toUnifiedRecord().copy(sha256Checksum = archiveSha256)

        vm.searchDb.indexPackages(listOf(bunRecord))
        vm.installPackage(bunRecord).join()

        // Verify progress is COMPLETED
        val progress = vm.installProgress.value[bunRecord.id]
        assertThat(progress).isNotNull()
        assertThat(progress?.stage).isEqualTo(com.pocketserver.hub.download.InstallStage.COMPLETED)

        // Verify marked as installed in search DB
        val updated = vm.searchDb.getPackageById(bunRecord.id)
        assertThat(updated?.isInstalled).isTrue()

        // Verify shim generated in files/bin
        val binDir = File(context.filesDir, "bin")
        val shimFile = File(binDir, "bun")
        assertThat(shimFile.exists()).isTrue()
        assertThat(shimFile.canExecute()).isTrue()
    }
}
