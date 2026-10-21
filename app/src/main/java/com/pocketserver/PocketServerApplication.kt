package com.pocketserver

import android.app.Application
import androidx.room.Room
import com.pocketserver.hub.forge.PocketForgeManager
import com.pocketserver.hub.registry.HubPackageRegistry
import com.pocketserver.hub.search.UnifiedPackageSearchDb
import com.pocketserver.hub.shim.BinaryShimGenerator
import com.pocketserver.persistence.host.PocketServerHostService
import com.pocketserver.storage.cas.ContentAddressableStore
import com.pocketserver.storage.db.PocketDatabase
import com.pocketserver.ui.monorepo.MonorepoWakeCoordinator
import java.io.File

/**
 * PocketServer Application entry point.
 * Initializes the Room database, monorepo wake coordinator, host persistence shields,
 * and the 70,000+ universal package search engine with PocketForge community repositories.
 */
class PocketServerApplication : Application() {

    lateinit var database: PocketDatabase
        private set

    lateinit var searchDb: UnifiedPackageSearchDb
        private set

    lateinit var pocketForgeManager: PocketForgeManager
        private set

    val wakeCoordinator = MonorepoWakeCoordinator()

    companion object {
        lateinit var instance: PocketServerApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize persistent Room manifest database
        database = Room.databaseBuilder(
            applicationContext,
            PocketDatabase::class.java,
            "pocketserver.db"
        ).fallbackToDestructiveMigration().build()

        // Initialize Unified FTS Package Search DB and PocketForge Manager
        searchDb = UnifiedPackageSearchDb(this)
        val binDir = File(filesDir, "bin")
        val shimGenerator = BinaryShimGenerator(binDir)
        val casStore = ContentAddressableStore(File(filesDir, "store"), database)
        pocketForgeManager = PocketForgeManager(searchDb, casStore, shimGenerator)

        // Seed Curated Core Packages into SQLite FTS index
        try {
            val coreRecords = HubPackageRegistry.getAllPackages().map { it.toUnifiedRecord() }
            searchDb.indexPackages(coreRecords)
        } catch (ignored: Exception) {}

        // Priming host persistence service (VpnService loopback shield + dual Wi-Fi locks)
        try {
            PocketServerHostService.start(this)
        } catch (ignored: Exception) {
            // Handled gracefully in constrained test environments
        }
    }
}
