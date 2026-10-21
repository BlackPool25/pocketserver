package com.pocketserver.storage.doctor

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageDoctorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var appBaseDir: File
    private lateinit var fakeHomeDir: File
    private lateinit var fakeProjectDir: File
    private lateinit var storageDoctor: StorageDoctor

    @Before
    fun setUp() {
        appBaseDir = tempFolder.newFolder("app_files")
        fakeHomeDir = tempFolder.newFolder("fake_home")
        fakeProjectDir = tempFolder.newFolder("projects", "fastapi-backend")
        storageDoctor = StorageDoctor()
    }

    @Test
    fun `StorageBreakdown calculates human readable size correctly`() {
        assertThat(StorageBreakdown.humanReadableSize(0L)).isEqualTo("0 B")
        assertThat(StorageBreakdown.humanReadableSize(512L)).isEqualTo("512 B")
        assertThat(StorageBreakdown.humanReadableSize(1024L)).isEqualTo("1 KB")
        assertThat(StorageBreakdown.humanReadableSize(1024L * 1024L * 76L)).isEqualTo("76 MB")
        assertThat(StorageBreakdown.humanReadableSize(1024L * 1024L * 1024L * 2L)).isEqualTo("2 GB")
    }

    @Test
    fun `analyze detects installed CAS runtimes, project code, and all Hidden 5 compiler caches`() {
        // 1. Setup CAS store with a fake Python runtime (2 MB)
        val storeDir = File(appBaseDir, "store/hash123-cpython-3.12")
        storeDir.mkdirs()
        File(storeDir, "bin/python3").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(2 * 1024 * 1024)) // 2 MB
        }

        // 2. Setup project source code (500 KB)
        File(fakeProjectDir, "main.py").writeBytes(ByteArray(500 * 1024))

        // 3. Setup Hidden 5 Caches:
        // Cache 1: Cargo registry
        val cargoSrc = File(fakeHomeDir, ".cargo/registry/src/github.com/tokio")
        cargoSrc.mkdirs()
        File(cargoSrc, "lib.rs").writeBytes(ByteArray(100 * 1024))

        // Cache 2: uv cache
        val uvCache = File(fakeHomeDir, ".cache/uv/wheels")
        uvCache.mkdirs()
        File(uvCache, "numpy.whl").writeBytes(ByteArray(300 * 1024))

        // Cache 3: npm / bun cache
        val npmCache = File(fakeHomeDir, ".npm/_cacache/content-v2")
        npmCache.mkdirs()
        File(npmCache, "package.tgz").writeBytes(ByteArray(200 * 1024))

        // Cache 4: Incremental builds & __pycache__
        val pycache = File(fakeProjectDir, "__pycache__")
        pycache.mkdirs()
        File(pycache, "main.cpython-312.pyc").writeBytes(ByteArray(50 * 1024))

        val cargoIncr = File(fakeProjectDir, "target/debug/incremental/app-xyz")
        cargoIncr.mkdirs()
        File(cargoIncr, "query-cache.bin").writeBytes(ByteArray(150 * 1024))

        // Cache 5: Git temporary packfiles
        val gitObjects = File(fakeProjectDir, ".git/objects")
        gitObjects.mkdirs()
        File(gitObjects, "tmp_pack_1234").writeBytes(ByteArray(80 * 1024))

        // Run analysis
        val breakdown = storageDoctor.analyze(
            baseDir = appBaseDir,
            userHomeDir = fakeHomeDir,
            projectDirs = listOf(fakeProjectDir)
        )

        assertThat(breakdown.installedRuntimesBytes).isAtLeast(2 * 1024 * 1024)
        assertThat(breakdown.projectCodeBytes).isAtLeast(500 * 1024)

        val totalExpectedCache = (100 + 300 + 200 + 50 + 150 + 80) * 1024L
        assertThat(breakdown.reclaimableCacheBytes).isAtLeast(totalExpectedCache)
        assertThat(breakdown.cacheItems.size).isAtLeast(5)

        assertThat(breakdown.reclaimPercentage).isGreaterThan(0.0)
    }

    @Test
    fun `cleanReclaimableCaches purges caches while strictly preserving project source and CAS store`() {
        // Setup CAS store
        val storeDir = File(appBaseDir, "store/hash123-bun-1.2")
        storeDir.mkdirs()
        val bunBinary = File(storeDir, "bin/bun").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh\necho bun")
        }

        // Setup Project source
        val userSource = File(fakeProjectDir, "index.ts").apply {
            writeText("console.log('hello world');")
        }

        // Setup Reclaimable Caches
        val uvCache = File(fakeHomeDir, ".cache/uv/builds").apply {
            mkdirs()
            File(this, "artifact.o").writeBytes(ByteArray(5000))
        }
        val pycache = File(fakeProjectDir, "__pycache__").apply {
            mkdirs()
            File(this, "index.pyc").writeBytes(ByteArray(2000))
        }

        val breakdown = storageDoctor.analyze(
            baseDir = appBaseDir,
            userHomeDir = fakeHomeDir,
            projectDirs = listOf(fakeProjectDir)
        )
        assertThat(breakdown.reclaimableCacheBytes).isGreaterThan(0L)

        // Perform 1-Tap Deep Purge
        val result = storageDoctor.cleanReclaimableCaches(
            breakdown = breakdown,
            baseDir = appBaseDir,
            userHomeDir = fakeHomeDir,
            projectDirs = listOf(fakeProjectDir)
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.bytesFreed).isAtLeast(7000L)
        assertThat(result.categoriesCleaned).contains(CacheCategory.UV_CACHE)
        assertThat(result.categoriesCleaned).contains(CacheCategory.INCREMENTAL_BUILDS)

        // Verify caches are gone
        assertThat(uvCache.exists()).isFalse()
        assertThat(pycache.exists()).isFalse()

        // Critical Safety Verification: CAS runtime and user source files MUST be preserved!
        assertThat(bunBinary.exists()).isTrue()
        assertThat(bunBinary.readText()).contains("bun")
        assertThat(userSource.exists()).isTrue()
        assertThat(userSource.readText()).contains("hello world")
    }
}
