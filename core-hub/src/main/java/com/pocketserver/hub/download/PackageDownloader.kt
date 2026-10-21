package com.pocketserver.hub.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Named lifecycle stages for package installation progress.
 */
enum class InstallStage {
    IDLE,
    DOWNLOADING,
    VERIFYING,
    EXTRACTING,
    LINKING,
    COMPLETED,
    FAILED
}

data class InstallProgress(
    val stage: InstallStage,
    val progressPercent: Int = 0,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val detailMessage: String = ""
)

/**
 * Streaming HTTP package downloader using OkHttp.
 *
 * Streams package archives (.tar.zst) into a temporary download cache, computes
 * streaming SHA-256 digests, and dispatches fine-grained stage progress callbacks.
 */
class PackageDownloader(
    private val client: OkHttpClient = defaultClient()
) {
    companion object {
        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }

    /**
     * Downloads an archive file from [downloadUrl] into [destinationFile].
     * Dispatches progress updates via [onProgress].
     */
    suspend fun downloadArchive(
        downloadUrl: String,
        destinationFile: File,
        expectedSha256: String? = null,
        onProgress: (InstallProgress) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        destinationFile.parentFile?.mkdirs()
        val tempFile = File(destinationFile.parentFile, "${destinationFile.name}.tmp")

        try {
            onProgress(
                InstallProgress(
                    stage = InstallStage.DOWNLOADING,
                    progressPercent = 0,
                    detailMessage = "Connecting to repository..."
                )
            )

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "PocketServer-Android/1.0.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    IOException("HTTP download failed with status ${response.code}: ${response.message}")
                )
            }

            val body = response.body
                ?: return@withContext Result.failure(IOException("Empty response body from $downloadUrl"))

            val contentLength = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var totalRead = 0L
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        if (!coroutineContext.isActive) {
                            tempFile.delete()
                            throw IOException("Download cancelled by user")
                        }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        totalRead += read

                        val percent = if (contentLength > 0) {
                            ((totalRead * 100) / contentLength).toInt().coerceIn(0, 100)
                        } else 0

                        onProgress(
                            InstallProgress(
                                stage = InstallStage.DOWNLOADING,
                                progressPercent = percent,
                                bytesDownloaded = totalRead,
                                totalBytes = contentLength,
                                detailMessage = "Downloading package ($percent%)..."
                            )
                        )
                    }
                }
            }

            // Verify SHA-256
            onProgress(
                InstallProgress(
                    stage = InstallStage.VERIFYING,
                    progressPercent = 100,
                    detailMessage = "Verifying package integrity..."
                )
            )

            val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            if (expectedSha256 != null && !computedSha256.equals(expectedSha256, ignoreCase = true)) {
                tempFile.delete()
                return@withContext Result.failure(
                    SecurityException("Integrity check failed: Expected $expectedSha256, computed $computedSha256")
                )
            }

            if (destinationFile.exists()) destinationFile.delete()
            if (!tempFile.renameTo(destinationFile)) {
                tempFile.copyTo(destinationFile, overwrite = true)
                tempFile.delete()
            }

            Result.success(destinationFile)
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            onProgress(
                InstallProgress(
                    stage = InstallStage.FAILED,
                    detailMessage = e.message ?: "Download failed"
                )
            )
            Result.failure(e)
        }
    }
}
