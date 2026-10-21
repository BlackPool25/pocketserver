package com.pocketserver.hub.conda

import com.pocketserver.hub.search.PackageTier
import com.pocketserver.hub.search.UnifiedPackageRecord
import org.json.JSONObject

/**
 * Metadata descriptor for a package hosted on the Conda-Forge channel.
 */
data class CondaPackageDescriptor(
    val name: String,
    val version: String,
    val build: String,
    val buildNumber: Int,
    val subdir: String = "linux-aarch64",
    val sizeBytes: Long,
    val sha256: String,
    val depends: List<String> = emptyList(),
    val constrains: List<String> = emptyList(),
    val tarballFileName: String,
    val downloadBaseUrl: String = "https://conda.anaconda.org/conda-forge"
) {
    val downloadUrl: String get() = "$downloadBaseUrl/$subdir/$tarballFileName"

    fun toUnifiedRecord(): UnifiedPackageRecord {
        return UnifiedPackageRecord(
            id = "conda-$name",
            name = name,
            version = version,
            tier = PackageTier.CONDA,
            category = categorizePackage(name),
            description = "Conda-Forge $name $version ($build for $subdir)",
            sizeCompressedBytes = sizeBytes,
            installedSizeBytes = (sizeBytes * 3.2).toLong(), // Typical expansion ratio for pre-compiled wheels
            sourceRepo = "conda-forge",
            downloadUrl = downloadUrl,
            sha256Checksum = sha256,
            primaryBinary = "bin/$name",
            isInstalled = false,
            dependencies = depends
        )
    }

    companion object {
        fun categorizePackage(name: String): String {
            val lower = name.lowercase()
            return when {
                lower.contains("torch") || lower.contains("tensor") || lower.contains("onnx") ||
                        lower.contains("cv2") || lower.contains("opencv") || lower.contains("whisper") ||
                        lower.contains("transformers") || lower.contains("scikit") -> "AI & Computer Vision"
                lower.contains("numpy") || lower.contains("scipy") || lower.contains("pandas") ||
                        lower.contains("polars") || lower.contains("julia") || lower.contains("blas") -> "Scientific Computing & Math"
                lower.contains("arrow") || lower.contains("duckdb") || lower.contains("parquet") -> "Data & Analytics"
                else -> "C++ & Scientific"
            }
        }

        /**
         * Parses Conda-Forge `repodata.json` string into a list of descriptors.
         */
        fun parseRepodata(jsonContent: String): List<CondaPackageDescriptor> {
            val root = JSONObject(jsonContent)
            val packages = mutableListOf<CondaPackageDescriptor>()

            val packagesConda = root.optJSONObject("packages.conda")
            if (packagesConda != null) {
                parsePackagesObject(packagesConda, packages)
            }

            val packagesTarBz2 = root.optJSONObject("packages")
            if (packagesTarBz2 != null) {
                parsePackagesObject(packagesTarBz2, packages)
            }

            return packages
        }

        private fun parsePackagesObject(
            obj: JSONObject,
            output: MutableList<CondaPackageDescriptor>
        ) {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val tarballName = keys.next()
                val pkgObj = obj.getJSONObject(tarballName)

                val name = pkgObj.optString("name", "")
                val version = pkgObj.optString("version", "")
                if (name.isBlank() || version.isBlank()) continue

                val build = pkgObj.optString("build", "")
                val buildNum = pkgObj.optInt("build_number", 0)
                val subdir = pkgObj.optString("subdir", "linux-aarch64")
                val size = pkgObj.optLong("size", 0L)
                val sha256 = pkgObj.optString("sha256", "")

                val depsList = mutableListOf<String>()
                val depsArr = pkgObj.optJSONArray("depends")
                if (depsArr != null) {
                    for (i in 0 until depsArr.length()) {
                        depsList.add(depsArr.getString(i))
                    }
                }

                output.add(
                    CondaPackageDescriptor(
                        name = name,
                        version = version,
                        build = build,
                        buildNumber = buildNum,
                        subdir = subdir,
                        sizeBytes = size,
                        sha256 = sha256,
                        depends = depsList,
                        tarballFileName = tarballName
                    )
                )
            }
        }
    }
}
