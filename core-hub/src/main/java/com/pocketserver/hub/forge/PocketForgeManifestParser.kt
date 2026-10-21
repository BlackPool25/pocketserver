package com.pocketserver.hub.forge

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses and strictly validates PocketForge community repository `index.json` manifests.
 * Prevents path traversal, corrupted checksums, and schema violations.
 */
class PocketForgeManifestParser {

    companion object {
        private val SAFE_ID_REGEX = "^[a-zA-Z0-9_.-]+$".toRegex()
        private val SHA256_REGEX = "^[a-fA-F0-9]{64}$".toRegex()
    }

    /**
     * Parses manifest JSON string into a structured, validated [PocketForgeManifest].
     * Throws [IllegalArgumentException] on schema or security validation errors.
     */
    fun parse(jsonContent: String): PocketForgeManifest {
        val root = try {
            JSONObject(jsonContent)
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed JSON in PocketForge manifest: ${e.message}", e)
        }

        val repositoryName = root.optString("repository", "").trim()
        if (repositoryName.isBlank()) {
            throw IllegalArgumentException("PocketForge manifest missing required field: 'repository'")
        }

        val maintainer = root.optString("maintainer", "Community").trim()
        val version = root.optInt("version", 1)
        val description = root.optString("description", "")

        val packagesArray = root.optJSONArray("packages")
            ?: throw IllegalArgumentException("PocketForge manifest missing 'packages' array")

        val packagesList = mutableListOf<PocketForgePackage>()
        for (i in 0 until packagesArray.length()) {
            val pkgObj = packagesArray.getJSONObject(i)
            packagesList.add(parseAndValidatePackage(pkgObj, repositoryName))
        }

        return PocketForgeManifest(
            repository = repositoryName,
            maintainer = maintainer,
            version = version,
            description = description,
            packages = packagesList
        )
    }

    private fun parseAndValidatePackage(pkgObj: JSONObject, repoName: String): PocketForgePackage {
        val id = pkgObj.optString("id", "").trim()
        if (!SAFE_ID_REGEX.matches(id)) {
            throw IllegalArgumentException("Security violation: Package id '$id' in repo '$repoName' contains illegal characters or path traversal elements.")
        }

        val name = pkgObj.optString("name", id).trim()
        val version = pkgObj.optString("version", "").trim()
        if (version.isBlank()) {
            throw IllegalArgumentException("Package '$id' is missing required field: 'version'")
        }

        val category = pkgObj.optString("category", "General").trim()
        val description = pkgObj.optString("description", "").trim()

        val sizeCompressed = pkgObj.optLong("size_compressed", 0L)
        val installedSize = pkgObj.optLong("installed_size", 0L)

        val downloadUrl = pkgObj.optString("download_url", "").trim()
        if (!downloadUrl.startsWith("http://") && !downloadUrl.startsWith("https://") && !downloadUrl.startsWith("file://")) {
            throw IllegalArgumentException("Package '$id' has invalid download URL scheme: '$downloadUrl'")
        }

        val sha256 = pkgObj.optString("sha256", "").trim()
        if (!SHA256_REGEX.matches(sha256)) {
            throw IllegalArgumentException("Package '$id' has invalid SHA-256 checksum: '$sha256'")
        }

        val binaryPath = pkgObj.optString("primary_binary", "bin/$id").trim()
        if (binaryPath.startsWith("/") || binaryPath.contains("..")) {
            throw IllegalArgumentException("Security violation: Package '$id' primary binary '$binaryPath' cannot be absolute or contain '..'")
        }

        val depsList = mutableListOf<String>()
        val depsArray = pkgObj.optJSONArray("dependencies")
        if (depsArray != null) {
            for (j in 0 until depsArray.length()) {
                val dep = depsArray.getString(j).trim()
                if (dep.isNotBlank()) {
                    depsList.add(dep)
                }
            }
        }

        val defaultPort = pkgObj.optInt("default_port", 0)

        val envMap = mutableMapOf<String, String>()
        val envObj = pkgObj.optJSONObject("environment")
        if (envObj != null) {
            val keys = envObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                envMap[key] = envObj.optString(key, "")
            }
        }

        return PocketForgePackage(
            id = id,
            name = name,
            version = version,
            category = category,
            description = description,
            sizeCompressedBytes = sizeCompressed,
            installedSizeBytes = installedSize,
            downloadUrl = downloadUrl,
            sha256Checksum = sha256.lowercase(),
            primaryBinaryRelPath = binaryPath,
            dependencies = depsList,
            defaultPort = defaultPort,
            environmentVariables = envMap
        )
    }
}
