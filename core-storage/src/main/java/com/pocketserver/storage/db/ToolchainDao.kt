package com.pocketserver.storage.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ToolchainDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(toolchain: ToolchainEntity)

    @Query("SELECT * FROM toolchains WHERE packageId = :packageId")
    suspend fun getById(packageId: String): ToolchainEntity?

    @Query("SELECT * FROM toolchains ORDER BY name ASC")
    suspend fun getAll(): List<ToolchainEntity>

    @Query("UPDATE toolchains SET refCount = refCount + :delta WHERE packageId = :packageId")
    suspend fun updateRefCount(packageId: String, delta: Int)

    @Query("SELECT * FROM toolchains WHERE refCount <= 0")
    suspend fun getUnreferenced(): List<ToolchainEntity>

    @Delete
    suspend fun delete(toolchain: ToolchainEntity)
}
