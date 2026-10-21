package com.pocketserver.service

import android.content.Context
import android.os.Build
import com.pocketserver.hub.download.PackageDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * On-demand provisioner for the cloudflared binary.
 *
 * Resolves the host device architecture (arm64, arm, amd64, 386),
 * downloads the official binary from Cloudflare into context.filesDir/bin,
 * and sets executable permissions (chmod 0755).
 */
class CloudflaredProvisioner(
    private val context: Context,
    private val downloader: PackageDownloader = PackageDownloader()
) {
    companion object {
        const val CLOUDFLARED_BIN_NAME = "cloudflared"

        fun resolveArchitecture(): String {
            val abi = try {
                Build.SUPPORTED_ABIS.firstOrNull()?.lowercase() ?: ""
            } catch (_: Throwable) {
                ""
            }
            val osArch = System.getProperty("os.arch")?.lowercase() ?: ""

            return when {
                abi.contains("arm64") || osArch.contains("aarch64") -> "arm64"
                abi.contains("armeabi") || osArch.contains("arm") -> "arm"
                abi.contains("x86_64") || osArch.contains("amd64") || osArch.contains("x86_64") -> "amd64"
                abi.contains("x86") || osArch.contains("i386") || osArch.contains("i686") -> "386"
                else -> "arm64"
            }
        }

        fun getDownloadUrl(arch: String = resolveArchitecture()): String {
            return "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-$arch"
        }
    }

    val binaryFile: File
        get() = File(File(context.filesDir, "bin"), CLOUDFLARED_BIN_NAME)

    /**
     * Checks if cloudflared binary is installed and executable.
     */
    fun isProvisioned(): Boolean {
        return binaryFile.exists() && binaryFile.canExecute()
    }

    /**
     * Ensures cloudflared is downloaded and ready for execution.
     */
    suspend fun ensureProvisioned(
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        val bin = binaryFile
        if (isProvisioned()) {
            return@withContext Result.success(bin)
        }

        bin.parentFile?.mkdirs()
        val downloadUrl = getDownloadUrl()

        val downloadResult = downloader.downloadArchive(
            downloadUrl = downloadUrl,
            destinationFile = bin,
            expectedSha256 = null,
            onProgress = { progress ->
                onProgress(progress.progressPercent, progress.detailMessage)
            }
        )

        if (downloadResult.isFailure) {
            return@withContext Result.failure(
                downloadResult.exceptionOrNull() ?: IOException("Failed to download cloudflared")
            )
        }

        // Set executable permissions
        bin.setExecutable(true, false)
        bin.setReadable(true, false)

        if (!bin.canExecute()) {
            // Attempt shell chmod fallback
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", bin.absolutePath)).waitFor()
            } catch (_: Throwable) {}
        }

        Result.success(bin)
    }
}
