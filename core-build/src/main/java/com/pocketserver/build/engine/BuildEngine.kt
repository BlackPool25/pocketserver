package com.pocketserver.build.engine

import com.pocketserver.build.governor.CpuPinningGovernor
import com.pocketserver.build.tmpfs.RamTmpfsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Mobile-Optimized Build Engine.
 *
 * Orchestrates compilation and dependency installation tasks under:
 * 1. LITTLE Core CPU Pinning (taskset -c 0-3 nice -n 19) to prevent phone overheating and thermal throttling.
 * 2. RAM-backed tmpfs (/dev/shm) to protect physical UFS flash storage endurance.
 * 3. Streaming log output and thermal budget verification.
 */
class BuildEngine(
    val cpuGovernor: CpuPinningGovernor = CpuPinningGovernor(),
    val ramTmpfsManager: RamTmpfsManager = RamTmpfsManager()
) {

    data class BuildOptions(
        val useLittleCorePinning: Boolean = true,
        val useRamTmpfs: Boolean = true,
        val autoEvaporateOnCompletion: Boolean = false,
        val nicePriority: Int = 19,
        val timeoutSeconds: Long = 300,
        val customEnv: Map<String, String> = emptyMap()
    )

    data class BuildResult(
        val projectId: String,
        val exitCode: Int,
        val isSuccess: Boolean,
        val buildDurationMillis: Long,
        val logOutput: List<String>,
        val scratchpadDir: File?,
        val thermalMetrics: CpuPinningGovernor.ThermalMetrics,
        val errorMessage: String? = null
    )

    /**
     * Executes a build or compilation command under mobile thermal constraints.
     */
    suspend fun executeBuild(
        projectId: String,
        projectDir: File,
        command: List<String>,
        options: BuildOptions = BuildOptions(),
        fallbackTmpfsBase: File = projectDir
    ): BuildResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val logs = mutableListOf<String>()

        var scratchpadDir: File? = null
        val effectiveEnv = mutableMapOf<String, String>()
        effectiveEnv.putAll(options.customEnv)

        if (options.useRamTmpfs) {
            val scratchpad = ramTmpfsManager.allocateScratchpad(projectId, fallbackTmpfsBase)
            scratchpadDir = scratchpad
            val tmpfsEnv = ramTmpfsManager.prepareBuildCacheEnv(scratchpad)
            effectiveEnv.putAll(tmpfsEnv)
        }

        // Determine effective command: wrapped with taskset and nice if CPU pinning is requested
        val effectiveCommand = if (options.useLittleCorePinning) {
            cpuGovernor.wrapCommand(command, options.nicePriority)
        } else {
            command
        }

        var exitCode = -1
        var errorMessage: String? = null

        try {
            val processBuilder = ProcessBuilder(effectiveCommand)
                .directory(projectDir)
                .redirectErrorStream(true)

            val procEnv = processBuilder.environment()
            for ((k, v) in effectiveEnv) {
                procEnv[k] = v
            }

            val process = processBuilder.start()

            // Apply native CPU pinning directly to pid
            val pid = try {
                val method = Process::class.java.getMethod("pid")
                (method.invoke(process) as Number).toInt()
            } catch (e: Exception) {
                -1
            }

            if (pid > 0 && options.useLittleCorePinning) {
                cpuGovernor.applyPinning(pid, nicePriority = options.nicePriority)
            }

            // Stream process logs
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    logs.add(line ?: "")
                }
            }

            val finishedInTime = process.waitFor(options.timeoutSeconds, TimeUnit.SECONDS)
            if (!finishedInTime) {
                process.destroyForcibly()
                exitCode = -124 // Timed out
                errorMessage = "Build execution timed out after ${options.timeoutSeconds} seconds"
            } else {
                exitCode = process.exitValue()
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "Failed to execute build process"
            exitCode = -1
        } finally {
            if (options.autoEvaporateOnCompletion && scratchpadDir != null) {
                ramTmpfsManager.evaporateScratchpad(scratchpadDir)
            }
        }

        val durationMillis = System.currentTimeMillis() - startTime
        val thermalMetrics = cpuGovernor.evaluateThermalBudget(
            isLittleCorePinned = options.useLittleCorePinning,
            buildDurationMillis = durationMillis
        )

        BuildResult(
            projectId = projectId,
            exitCode = exitCode,
            isSuccess = exitCode == 0,
            buildDurationMillis = durationMillis,
            logOutput = logs,
            scratchpadDir = scratchpadDir,
            thermalMetrics = thermalMetrics,
            errorMessage = errorMessage
        )
    }
}
