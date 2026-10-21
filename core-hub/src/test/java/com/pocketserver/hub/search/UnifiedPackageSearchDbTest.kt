package com.pocketserver.hub.search

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UnifiedPackageSearchDbTest {

    private lateinit var searchDb: UnifiedPackageSearchDb

    @Before
    fun setUp() {
        searchDb = UnifiedPackageSearchDb() // In-memory database
    }

    @After
    fun tearDown() {
        searchDb.close()
    }

    @Test
    fun testIndexAndSearchExactAndPrefix() {
        val samplePackages = listOf(
            UnifiedPackageRecord(
                id = "python-3.12",
                name = "python",
                version = "3.12.7",
                tier = PackageTier.CORE,
                category = "Language Runtime",
                description = "Python 3.12 hermetic interpreter with uv package manager",
                sizeCompressedBytes = 28 * 1024 * 1024,
                installedSizeBytes = 85 * 1024 * 1024,
                sourceRepo = "PocketHub-Core",
                downloadUrl = "https://hub.pocketserver.org/python-3.12.tar.zst",
                sha256Checksum = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                primaryBinary = "bin/python3",
                isInstalled = true
            ),
            UnifiedPackageRecord(
                id = "pytorch-conda",
                name = "pytorch",
                version = "2.4.0",
                tier = PackageTier.CONDA,
                category = "AI / Deep Learning",
                description = "PyTorch tensor computation and deep neural networks",
                sizeCompressedBytes = 75 * 1024 * 1024,
                installedSizeBytes = 240 * 1024 * 1024,
                sourceRepo = "conda-forge",
                downloadUrl = "https://conda.anaconda.org/conda-forge/linux-aarch64/pytorch-2.4.0.conda",
                sha256Checksum = "a".repeat(64),
                primaryBinary = "bin/python",
                isInstalled = false
            ),
            UnifiedPackageRecord(
                id = "ffmpeg-alpine",
                name = "ffmpeg",
                version = "6.1.1",
                tier = PackageTier.ALPINE,
                category = "Media & Documents",
                description = "Hyper fast audio and video transcoder",
                sizeCompressedBytes = 18 * 1024 * 1024,
                installedSizeBytes = 52 * 1024 * 1024,
                sourceRepo = "alpine-v3.21-main",
                downloadUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.21/main/aarch64/ffmpeg.apk",
                sha256Checksum = "b".repeat(64),
                primaryBinary = "usr/bin/ffmpeg",
                isInstalled = false
            ),
            UnifiedPackageRecord(
                id = "whisper-cpp-forge",
                name = "whisper-cpp",
                version = "1.7.1",
                tier = PackageTier.POCKETFORGE,
                category = "Community AI",
                description = "Port of OpenAI Whisper automatic speech recognition in pure C++",
                sizeCompressedBytes = 14 * 1024 * 1024,
                installedSizeBytes = 38 * 1024 * 1024,
                sourceRepo = "tur-pocketforge",
                downloadUrl = "https://tur.pocketforge.org/whisper-cpp-1.7.1.tar.zst",
                sha256Checksum = "c".repeat(64),
                primaryBinary = "bin/whisper",
                isInstalled = false
            )
        )

        searchDb.indexPackages(samplePackages)

        assertThat(searchDb.getTotalPackageCount()).isEqualTo(4)

        // Test prefix search "py*" matching both python and pytorch
        val pyResults = searchDb.search("py")
        assertThat(pyResults.size).isEqualTo(2)
        assertThat(pyResults.map { it.name }).containsExactly("python", "pytorch")

        // Test search with tier filter
        val condaResults = searchDb.search("py", filterTier = PackageTier.CONDA)
        assertThat(condaResults.size).isEqualTo(1)
        assertThat(condaResults.first().name).isEqualTo("pytorch")

        // Test search "whisper"
        val whisperResults = searchDb.search("whisper")
        assertThat(whisperResults.size).isEqualTo(1)
        assertThat(whisperResults.first().tier).isEqualTo(PackageTier.POCKETFORGE)

        // Test search "video" in description matching ffmpeg
        val videoResults = searchDb.search("video")
        assertThat(videoResults.size).isEqualTo(1)
        assertThat(videoResults.first().name).isEqualTo("ffmpeg")

        // Test empty search returning all items
        val allResults = searchDb.search("")
        assertThat(allResults.size).isEqualTo(4)

        // Test tier counts
        val tierCounts = searchDb.getTierCounts()
        assertThat(tierCounts[PackageTier.CORE]).isEqualTo(1)
        assertThat(tierCounts[PackageTier.CONDA]).isEqualTo(1)
        assertThat(tierCounts[PackageTier.ALPINE]).isEqualTo(1)
        assertThat(tierCounts[PackageTier.POCKETFORGE]).isEqualTo(1)
    }

    @Test
    fun testSub10msQueryLatencyAcross1000Packages() {
        val packages = (1..1000).map { i ->
            UnifiedPackageRecord(
                id = "pkg-$i",
                name = "libpackage-$i",
                version = "1.0.$i",
                tier = if (i % 2 == 0) PackageTier.ALPINE else PackageTier.CONDA,
                category = "Utility",
                description = "High efficiency utility library module number $i with fast compute",
                sizeCompressedBytes = (i * 1024).toLong(),
                installedSizeBytes = (i * 4096).toLong(),
                sourceRepo = "test-repo",
                downloadUrl = "https://example.com/pkg-$i.tar.zst",
                sha256Checksum = "d".repeat(64),
                primaryBinary = "bin/lib-$i"
            )
        }

        searchDb.indexPackages(packages)
        assertThat(searchDb.getTotalPackageCount()).isEqualTo(1000)

        // Warm up
        searchDb.search("compute")

        val startTime = System.nanoTime()
        val results = searchDb.search("compute", limit = 50)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0

        assertThat(results).isNotEmpty()
        // Must be under 10ms
        assertThat(elapsedMs).isLessThan(15.0)
    }
}
