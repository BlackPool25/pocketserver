package com.pocketserver

import android.app.Application
import androidx.room.Room
import com.pocketserver.persistence.host.PocketServerHostService
import com.pocketserver.storage.db.PocketDatabase
import com.pocketserver.ui.monorepo.MonorepoWakeCoordinator

/**
 * PocketServer Application entry point.
 * Initializes the Room database, monorepo wake coordinator, and host persistence shields.
 */
class PocketServerApplication : Application() {

    lateinit var database: PocketDatabase
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

        // Priming host persistence service (VpnService loopback shield + dual Wi-Fi locks)
        try {
            PocketServerHostService.start(this)
        } catch (ignored: Exception) {
            // Handled gracefully in constrained test environments
        }
    }
}
