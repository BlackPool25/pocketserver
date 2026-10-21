package com.pocketserver.hub.alpine

import com.pocketserver.hub.search.PackageTier
import com.pocketserver.hub.search.UnifiedPackageRecord
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Represents a parsed package entry from an Alpine Linux `APKINDEX`.
 */
data class AlpinePackageRecord(
    val packageName: String,
    val version: String,
    val architecture: String,
    val description: String,
    val compressedSizeBytes: Long,
    val installedSizeBytes: Long,
    val checksum: String,
    val projectUrl: String = "",
    val license: String = "",
    val dependencies: List<String> = emptyList(),
    val provides: List<String> = emptyList()
) {
    fun toUnifiedRecord(mirrorName: String, mirrorBaseUrl: String): UnifiedPackageRecord {
        val apkFileName = "$packageName-$version.apk"
        val downloadUrl = "$mirrorBaseUrl/$architecture/$apkFileName"

        return UnifiedPackageRecord(
            id = "alpine-$packageName",
            name = packageName,
            version = version,
            tier = PackageTier.ALPINE,
            category = categorizePackage(packageName),
            description = description.ifBlank { "Alpine Linux package $packageName" },
            sizeCompressedBytes = compressedSizeBytes,
            installedSizeBytes = installedSizeBytes,
            sourceRepo = mirrorName,
            downloadUrl = downloadUrl,
            sha256Checksum = checksum,
            primaryBinary = "usr/bin/$packageName",
            isInstalled = false,
            dependencies = dependencies
        )
    }

    companion object {
        fun categorizePackage(name: String): String {
            val lower = name.lowercase()
            return when {
                lower.contains("postgres") || lower.contains("maria") || lower.contains("mongo") ||
                        lower.contains("redis") || lower.contains("sqlite") || lower.contains("db") -> "Databases & Storage"
                lower.contains("ffmpeg") || lower.contains("image") || lower.contains("pandoc") ||
                        lower.contains("pdf") || lower.contains("video") || lower.contains("sound") -> "Media & Documents"
                lower.contains("nginx") || lower.contains("caddy") || lower.contains("traefik") ||
                        lower.contains("wireguard") || lower.contains("net") || lower.contains("curl") -> "Networking & Infrastructure"
                lower.contains("python") || lower.contains("ruby") || lower.contains("lua") ||
                        lower.contains("go") || lower.contains("rust") || lower.contains("elixir") -> "Programming Languages"
                else -> "Utilities"
            }
        }
    }
}

/**
 * High-performance parser for Alpine Linux `APKINDEX.tar.gz` and raw `APKINDEX` files.
 * Accurately extracts dependencies, shared library capabilities (`so:...`), and package metadata.
 */
class ApkIndexParser {

    /**
     * Parses an `APKINDEX.tar.gz` compressed stream.
     */
    fun parseArchive(inputStream: InputStream): List<AlpinePackageRecord> {
        GZIPInputStream(inputStream).use { gzipIn ->
            TarArchiveInputStream(gzipIn).use { tarIn ->
                var entry = tarIn.nextTarEntry
                while (entry != null) {
                    if (entry.name == "APKINDEX") {
                        return parseText(tarIn)
                    }
                    entry = tarIn.nextTarEntry
                }
            }
        }
        return emptyList()
    }

    /**
     * Parses an uncompressed `APKINDEX` text stream.
     */
    fun parseText(inputStream: InputStream): List<AlpinePackageRecord> {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val packages = mutableListOf<AlpinePackageRecord>()

        var currentChecksum = ""
        var currentPkg = ""
        var currentVer = ""
        var currentArch = "aarch64"
        var currentDesc = ""
        var currentSize = 0L
        var currentInstalledSize = 0L
        var currentUrl = ""
        var currentLicense = ""
        var currentDeps = mutableListOf<String>()
        var currentProvides = mutableListOf<String>()

        fun flushPackage() {
            if (currentPkg.isNotBlank() && currentVer.isNotBlank()) {
                packages.add(
                    AlpinePackageRecord(
                        packageName = currentPkg,
                        version = currentVer,
                        architecture = currentArch,
                        description = currentDesc,
                        compressedSizeBytes = currentSize,
                        installedSizeBytes = currentInstalledSize,
                        checksum = currentChecksum,
                        projectUrl = currentUrl,
                        license = currentLicense,
                        dependencies = currentDeps.toList(),
                        provides = currentProvides.toList()
                    )
                )
            }
            currentChecksum = ""
            currentPkg = ""
            currentVer = ""
            currentArch = "aarch64"
            currentDesc = ""
            currentSize = 0L
            currentInstalledSize = 0L
            currentUrl = ""
            currentLicense = ""
            currentDeps = mutableListOf()
            currentProvides = mutableListOf()
        }

        var line: String? = reader.readLine()
        while (line != null) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                flushPackage()
            } else if (trimmed.length >= 2 && trimmed[1] == ':') {
                val key = trimmed[0]
                val value = trimmed.substring(2).trim()
                when (key) {
                    'C' -> currentChecksum = value
                    'P' -> currentPkg = value
                    'V' -> currentVer = value
                    'A' -> currentArch = value
                    'S' -> currentSize = value.toLongOrNull() ?: 0L
                    'I' -> currentInstalledSize = value.toLongOrNull() ?: 0L
                    'T' -> currentDesc = value
                    'U' -> currentUrl = value
                    'L' -> currentLicense = value
                    'D' -> {
                        if (value.isNotBlank()) {
                            currentDeps.addAll(value.split("\\s+".toRegex()).filter { it.isNotBlank() })
                        }
                    }
                    'p' -> {
                        if (value.isNotBlank()) {
                            currentProvides.addAll(value.split("\\s+".toRegex()).filter { it.isNotBlank() })
                        }
                    }
                }
            }
            line = reader.readLine()
        }
        flushPackage()

        return packages
    }
}
