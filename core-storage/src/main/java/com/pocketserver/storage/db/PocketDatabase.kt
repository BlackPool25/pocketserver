package com.pocketserver.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ToolchainEntity::class, SandboxEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PocketDatabase : RoomDatabase() {

    abstract fun toolchainDao(): ToolchainDao
    abstract fun sandboxDao(): SandboxDao

    companion object {
        private const val DB_NAME = "pocketserver.db"

        @Volatile
        private var INSTANCE: PocketDatabase? = null

        fun getInstance(context: Context): PocketDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PocketDatabase::class.java,
                    DB_NAME
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
        }

        fun createInMemory(context: Context): PocketDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                PocketDatabase::class.java
            )
            .allowMainThreadQueries()
            .build()
        }
    }
}
