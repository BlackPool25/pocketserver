package com.pocketserver.storage.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Record representing an isolated execution sandbox mapped to a specific hosted service.
 */
@Entity(tableName = "sandboxes")
data class SandboxEntity(
    @PrimaryKey
    val serviceId: String, // Canonical UUID
    val name: String, // User-assigned project identifier
    val assignedPort: Int, // Local TCP port (e.g. 8000, 3000)
    val rootfsPath: String, // Absolute path to isolated rootfs symlink farm
    val projectRepoPath: String, // Absolute path to mutable project git repo
    val status: String = "STOPPED", // STOPPED, ACTIVE, SLEEPING
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
