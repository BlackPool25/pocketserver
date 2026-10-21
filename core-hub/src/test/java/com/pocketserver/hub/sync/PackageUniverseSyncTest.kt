package com.pocketserver.hub.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pocketserver.hub.forge.PocketForgeManager
import com.pocketserver.hub.search.PackageTier
import com.pocketserver.hub.search.UnifiedPackageSearchDb
import com.pocketserver.hub.shim.BinaryShimGenerator
import com.pocketserver.storage.cas.ContentAddressableStore
import com.pocketserver.storage.db.PocketDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PackageUniverseSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var searchDb: UnifiedPackageSearchDb
    private lateinit var database: PocketDatabase
    private lateinit var pocketForgeManager: PocketForgeManager
    private lateinit var sync: PackageUniverseSync

    @Before
    fun setUp() {
        searchDb = UnifiedPackageSearchDb()
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PocketDatabase::class.java
        ).allowMainThreadQueries().build()
        val storeDir = tempFolder.newFolder("store")
        val binDir = tempFolder.newFolder("bin")
        val casStore = ContentAddressableStore(storeDir, database)
        val shimGen = BinaryShimGenerator(binDir)
        pocketForgeManager = PocketForgeManager(searchDb, casStore, shimGen)
        sync = PackageUniverseSync(searchDb, pocketForgeManager)
    }

    @After
    fun tearDown() {
        searchDb.close()
        database.close()
    }

    @Test
    fun `seedCuratedPackagesIfNeeded populates 14 Tier 1 packages`() {
        assertThat(searchDb.getTotalPackageCount()).isEqualTo(0)
        sync.seedCuratedPackagesIfNeeded()
        assertThat(searchDb.getTotalPackageCount()).isEqualTo(14)
        assertThat(searchDb.getTierCounts()[PackageTier.CORE]).isEqualTo(14)
    }

    @Test
    fun `syncAlpineIndexFromStream parses raw text APKINDEX into FTS database`() = runTest {
        val rawApkIndex = """
            C:Q1xyz
            P:postgresql
            V:16.4-r0
            A:aarch64
            S:15000000
            I:45000000
            T:Sophisticated Object-Relational DBMS
            U:https://www.postgresql.org/
            L:PostgreSQL
            D:so:libc.musl-aarch64.so.1 so:libssl.so.3

            C:Q2abc
            P:nginx
            V:1.26.1-r0
            A:aarch64
            S:800000
            I:2400000
            T:High performance reverse proxy & web server
            U:https://nginx.org/
            L:BSD-2-Clause
            D:so:libc.musl-aarch64.so.1
        """.trimIndent()

        val count = sync.syncAlpineIndexFromStream(
            inputStream = ByteArrayInputStream(rawApkIndex.toByteArray()),
            isCompressedArchive = false
        )

        assertThat(count).isEqualTo(2)
        assertThat(searchDb.getTotalPackageCount()).isEqualTo(2)
        val results = searchDb.search("postgres")
        assertThat(results).hasSize(1)
        assertThat(results[0].name).isEqualTo("postgresql")
        assertThat(results[0].tier).isEqualTo(PackageTier.ALPINE)
    }

    @Test
    fun `syncCondaRepodata parses JSON into FTS database`() = runTest {
        val repodataJson = """
            {
              "packages.conda": {
                "pytorch-2.4.0-cpu.conda": {
                  "name": "pytorch",
                  "version": "2.4.0",
                  "build": "cpu_mkl_py312",
                  "build_number": 0,
                  "size": 65000000,
                  "sha256": "abcdef123456",
                  "depends": ["python >=3.12", "numpy >=1.26"]
                }
              }
            }
        """.trimIndent()

        val count = sync.syncCondaRepodata(repodataJson)
        assertThat(count).isEqualTo(1)
        val results = searchDb.search("pytorch")
        assertThat(results).hasSize(1)
        assertThat(results[0].name).isEqualTo("pytorch")
        assertThat(results[0].tier).isEqualTo(PackageTier.CONDA)
    }
}
