package com.pocketserver.storage.sandbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
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
@Config(sdk = [34])
class SymlinkFarmManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: PocketDatabase
    private lateinit var casStore: ContentAddressableStore
    private lateinit var farmManager: SymlinkFarmManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = PocketDatabase.createInMemory(context)
        val storeRoot = tempFolder.newFolder("files", "store")
        val sandboxesRoot = tempFolder.newFolder("files", "sandboxes")
        casStore = ContentAddressableStore(storeRoot, database)
        farmManager = SymlinkFarmManager(sandboxesRoot, casStore, database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `createSandboxRootfs initializes directory structure and database entry`() = runBlocking {
        val rootfs = farmManager.createSandboxRootfs("srv-1", "fastapi-demo", 8000)

        assertThat(rootfs.exists()).isTrue()
        assertThat(File(rootfs, "usr/bin").exists()).isTrue()
        assertThat(File(rootfs, "usr/lib").exists()).isTrue()
        assertThat(File(rootfs, "tmp").exists()).isTrue()

        val entity = database.sandboxDao().getById("srv-1")
        assertThat(entity).isNotNull()
        assertThat(entity?.name).isEqualTo("fastapi-demo")
        assertThat(entity?.assignedPort).isEqualTo(8000)
    }

    @Test
    fun `linkToolchainIntoSandbox creates relative symlinks into CAS store`() = runBlocking {
        // 1. Commit Python into CAS
        val staging = casStore.beginStaging("python")
        File(staging, "bin").mkdirs()
        File(staging, "lib").mkdirs()
        File(staging, "bin/python3").writeText("#!/bin/sh\necho python")
        File(staging, "lib/libpython3.so").writeText("fake-so")
        val hash = "3333333333333333333333333333333333333333333333333333333333333333"
        casStore.commitStagedPackage(staging, hash, "python", "3.12.7", true)

        // 2. Create sandbox and link toolchain
        farmManager.createSandboxRootfs("srv-python", "py-app", 8080)
        farmManager.linkToolchainIntoSandbox("srv-python", hash, "python")

        // 3. Verify symlinks exist in sandbox rootfs
        val sandboxUsrBinPython = File(farmManager.sandboxesRootDir, "srv-python/rootfs/usr/bin/python3")
        assertThat(sandboxUsrBinPython.exists()).isTrue()
        assertThat(sandboxUsrBinPython.readText()).contains("python")

        val sandboxUsrLibPython = File(farmManager.sandboxesRootDir, "srv-python/rootfs/usr/lib/libpython3.so")
        assertThat(sandboxUsrLibPython.exists()).isTrue()
        assertThat(sandboxUsrLibPython.readText()).isEqualTo("fake-so")

        // 4. Verify CAS reference count was incremented
        val entity = database.toolchainDao().getById(hash)
        assertThat(entity?.refCount).isEqualTo(1)
    }

    @Test
    fun `teardownSandbox removes sandbox without deleting CAS store`() = runBlocking {
        val staging = casStore.beginStaging("bun")
        File(staging, "bin").mkdirs()
        File(staging, "bin/bun").writeText("#!/bin/sh")
        val hash = "4444444444444444444444444444444444444444444444444444444444444444"
        casStore.commitStagedPackage(staging, hash, "bun", "1.2.0", true)

        farmManager.createSandboxRootfs("srv-bun", "bun-app", 3000)
        farmManager.linkToolchainIntoSandbox("srv-bun", hash, "bun")

        // Teardown sandbox
        farmManager.teardownSandbox("srv-bun", listOf(hash))

        val sandboxDir = File(farmManager.sandboxesRootDir, "srv-bun")
        assertThat(sandboxDir.exists()).isFalse()
        assertThat(database.sandboxDao().getById("srv-bun")).isNull()

        // Verify CAS store is still intact!
        val casBun = casStore.getPackageDir(hash, "bun")
        assertThat(casBun.exists()).isTrue()

        // Reference count decremented to 0
        val entity = database.toolchainDao().getById(hash)
        assertThat(entity?.refCount).isEqualTo(0)
    }
}
