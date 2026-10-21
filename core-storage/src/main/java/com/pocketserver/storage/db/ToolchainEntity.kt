package com.pocketserver.storage.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Metadata record for a toolchain installed in the Content-Addressable Storage (CAS).
 */
@Entity(tableName = "toolchains")
data class ToolchainEntity(
    @PrimaryKey
    val packageId: String, // Canonical SHA-256 hash
    val name: String, // e.g. "python", "bun", "rust", "go"
    val version: String, // e.g. "3.12.7", "1.2.0"
    val sizeBytes: Long, // Total uncompressed size on disk
    val refCount: Int = 0, // Number of active sandboxes linking this package
    val installedAtEpochMs: Long = System.currentTimeMillis(),
    val is16KbAligned: Boolean = true, // Verified 16KB ELF boundary
    val rpath: String = "\$ORIGIN/../lib" // Embedded ELF runtime search path
)
