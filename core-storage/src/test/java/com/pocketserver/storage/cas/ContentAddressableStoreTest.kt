package com.pocketserver.storage.cas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
@Config(sdk = [34])
class ContentAddressableStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: PocketDatabase
    private lateinit var casStore: ContentAddressableStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = PocketDatabase.createInMemory(context)
        val storeRoot = tempFolder.newFolder("files", "store")
        casStore = ContentAddressableStore(storeRoot, database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `commitStagedPackage atomically moves package to hash directory and records in DB`() = runBlocking {
        val stagingDir = casStore.beginStaging("python")
        val binDir = File(stagingDir, "bin").apply { mkdirs() }
        File(binDir, "python3").writeText("#!/bin/sh\necho 'Python 3.12'")

        val hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val result = casStore.commitStagedPackage(
            stagedDir = stagingDir,
            expectedHash = hash,
            packageName = "python",
            version = "3.12.7",
            is16KbAligned = true
        )

        assertThat(result.isSuccess).isTrue()
        val committedDir = result.getOrThrow()
        assertThat(committedDir.exists()).isTrue()
        assertThat(committedDir.name).isEqualTo("$hash-python")
        assertThat(stagingDir.exists()).isFalse()

        // Verify record in Database
        val entity = database.toolchainDao().getById(hash)
        assertThat(entity).isNotNull()
        assertThat(entity?.name).isEqualTo("python")
        assertThat(entity?.is16KbAligned).isTrue()
        assertThat(entity?.refCount).isEqualTo(0)
    }

    @Test
    fun `pruneUnreferencedPackages removes zero-ref packages and preserves referenced ones`() = runBlocking {
        // Package 1: Unreferenced
        val staging1 = casStore.beginStaging("bun")
        File(staging1, "bin").mkdirs()
        File(staging1, "bin/bun").writeText("#!/bin/sh")
        val hash1 = "1111111111111111111111111111111111111111111111111111111111111111"
        casStore.commitStagedPackage(staging1, hash1, "bun", "1.2.0", true)

        // Package 2: Referenced
        val staging2 = casStore.beginStaging("rust")
        File(staging2, "bin").mkdirs()
        File(staging2, "bin/rustc").writeText("#!/bin/sh")
        val hash2 = "2222222222222222222222222222222222222222222222222222222222222222"
        casStore.commitStagedPackage(staging2, hash2, "rust", "1.82.0", true)
        casStore.incrementRefCount(hash2)

        // Run Garbage Collector
        val pruned = casStore.pruneUnreferencedPackages()
        assertThat(pruned).isEqualTo(1)

        // Verify Bun was deleted from disk and database
        val bunDir = casStore.getPackageDir(hash1, "bun")
        assertThat(bunDir.exists()).isFalse()
        assertThat(database.toolchainDao().getById(hash1)).isNull()

        // Verify Rust remains on disk and in database
        val rustDir = casStore.getPackageDir(hash2, "rust")
        assertThat(rustDir.exists()).isTrue()
        assertThat(database.toolchainDao().getById(hash2)).isNotNull()
    }
}
