package com.pocketserver.toolchain.apk

import com.pocketserver.toolchain.elf.ElfAlignmentEngine
import java.io.File
import java.io.IOException

/**
 * Manages the unrooted Alpine Linux `apk.static` standalone package manager.
 *
 * For on-demand installation of specialized packages (e.g. niche database drivers or utilities),
 * PocketServer uses `apk.static` to automatically resolve, fetch, and extract all transitive `.so`
 * dependencies into an isolated CAS store directory without root privileges.
 */
class ApkStaticManager(
    val apkStaticBinary: File,
    private val repositoriesFile: File,
    private val keysDir: File,
    private val elfAlignmentEngine: ElfAlignmentEngine = ElfAlignmentEngine()
) {

    data class ApkResult(
        val isSuccess: Boolean,
        val exitCode: Int,
        val standardOutput: String,
        val errorOutput: String,
        val is16KbCompliant: Boolean
    )

    /**
     * Installs an Alpine package and all its transitive dependencies into the target CAS store root.
     */
    fun installPackage(
        packageName: String,
        targetRoot: File,
        extraArgs: List<String> = emptyList()
    ): ApkResult {
        if (!apkStaticBinary.exists()) {
            return ApkResult(
                isSuccess = false,
                exitCode = -1,
                standardOutput = "",
                errorOutput = "apk.static binary not found at ${apkStaticBinary.absolutePath}",
                is16KbCompliant = false
            )
        }

        apkStaticBinary.setExecutable(true, false)
        targetRoot.mkdirs()

        val command = mutableListOf(
            apkStaticBinary.absolutePath,
            "--root", targetRoot.absolutePath,
            "--initdb"
        )

        if (repositoriesFile.exists()) {
            command.addAll(listOf("--repositories-file", repositoriesFile.absolutePath))
        }

        if (keysDir.exists()) {
            command.addAll(listOf("--keys-dir", keysDir.absolutePath))
        }

        command.addAll(listOf("add", "--no-cache", packageName))
        command.addAll(extraArgs)

        return try {
            val process = ProcessBuilder(command)
                .directory(targetRoot)
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            val isSuccess = exitCode == 0
            val scanResult = if (isSuccess) {
                elfAlignmentEngine.scanDirectory(targetRoot)
            } else {
                null
            }

            ApkResult(
                isSuccess = isSuccess,
                exitCode = exitCode,
                standardOutput = stdout,
                errorOutput = stderr,
                is16KbCompliant = scanResult?.isFully16KbCompliant ?: true
            )
        } catch (e: Exception) {
            ApkResult(
                isSuccess = false,
                exitCode = -1,
                standardOutput = "",
                errorOutput = "Exception during apk.static execution: ${e.message}",
                is16KbCompliant = false
            )
        }
    }
}
