package com.pocketserver.ui.editor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Result of the PocketEditor [Save & Restart] hot reload operation.
 */
data class HotReloadResult(
    val serviceId: String,
    val filePath: String,
    val isSavedAtomically: Boolean,
    val reloadLatencyMillis: Double,
    val isUnder500ms: Boolean,
    val success: Boolean,
    val errorMessage: String? = null
)

/**
 * Coordinates atomic configuration/source code saving and service hot reload/restart.
 *
 * Verifies Automated Testing Protocol Requirement:
 * "PocketEditor Hot Reload: Edit .env file via PocketEditor and tap [Save & Restart].
 *  File saved atomically; service reloads configuration and responds on port within < 500ms."
 */
class HotReloadCoordinator(
    private val editorSession: PocketEditorSession? = null
) {

    /**
     * Atomically commits file modifications and invokes service restart,
     * measuring the roundtrip latency.
     */
    suspend fun saveAndRestart(
        serviceId: String,
        targetFile: File,
        newContent: String,
        restartAction: suspend (String) -> Unit
    ): HotReloadResult = withContext(Dispatchers.IO) {
        val startNanos = System.nanoTime()

        // 1. Atomic write with fsync barrier
        val saved = AtomicFileSaver.saveAtomic(targetFile, newContent)
        if (!saved) {
            return@withContext HotReloadResult(
                serviceId = serviceId,
                filePath = targetFile.absolutePath,
                isSavedAtomically = false,
                reloadLatencyMillis = (System.nanoTime() - startNanos) / 1_000_000.0,
                isUnder500ms = false,
                success = false,
                errorMessage = "Failed to atomically save file ${targetFile.name}"
            )
        }

        editorSession?.markClean()

        // 2. Restart or reload service supervisor
        try {
            restartAction(serviceId)
        } catch (e: Exception) {
            val totalLatency = (System.nanoTime() - startNanos) / 1_000_000.0
            return@withContext HotReloadResult(
                serviceId = serviceId,
                filePath = targetFile.absolutePath,
                isSavedAtomically = true,
                reloadLatencyMillis = totalLatency,
                isUnder500ms = totalLatency < 500.0,
                success = false,
                errorMessage = "Service restart failed: ${e.message}"
            )
        }

        val totalDurationNanos = System.nanoTime() - startNanos
        val latencyMillis = totalDurationNanos / 1_000_000.0

        HotReloadResult(
            serviceId = serviceId,
            filePath = targetFile.absolutePath,
            isSavedAtomically = true,
            reloadLatencyMillis = latencyMillis,
            isUnder500ms = latencyMillis < 500.0,
            success = true
        )
    }
}
