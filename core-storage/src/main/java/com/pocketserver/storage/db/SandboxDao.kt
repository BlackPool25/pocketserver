package com.pocketserver.storage.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SandboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sandbox: SandboxEntity)

    @Query("SELECT * FROM sandboxes WHERE serviceId = :serviceId")
    suspend fun getById(serviceId: String): SandboxEntity?

    @Query("SELECT * FROM sandboxes ORDER BY createdAtEpochMs DESC")
    suspend fun getAll(): List<SandboxEntity>

    @Query("UPDATE sandboxes SET status = :status WHERE serviceId = :serviceId")
    suspend fun updateStatus(serviceId: String, status: String)

    @Delete
    suspend fun delete(sandbox: SandboxEntity)
}
