package com.pocketserver.hub.conda

import com.pocketserver.hub.governor.ArmThreadGovernor
import com.pocketserver.hub.search.UnifiedPackageSearchDb
import com.pocketserver.hub.shim.BinaryShimGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * High-level coordinator for Conda environments, package installation,
 * and hermetic POSIX execution shims.
 *
 * Couples Conda-Forge runtime with:
 *   - [ArmThreadGovernor]: hardware-tuned compute threads to eliminate DynamIQ straggler bottlenecks.
 *   - [BinaryShimGenerator]: isolates Glibc C-runtime prefix from Musl (Alpine) and Bionic (Android).
 */
class CondaEnvironmentManager(
    val micromambaEngine: MicromambaEngine,
    private val shimGenerator: BinaryShimGenerator,
    private val governor: ArmThreadGovernor = ArmThreadGovernor(),
    private val searchDb: UnifiedPackageSearchDb? = null
) {

    data class InstallResult(
        val isSuccess: Boolean,
        val packageName: String,
        val envPrefixDir: File?,
        val binaryFile: File?,
        val shimFile: File?,
        val errorMessage: String? = null
    )

    /**
     * Installs a Conda package into the specified environment prefix and generates an accelerated execution shim.
     */
    suspend fun installPackage(
        envName: String = "base",
        packageName: String,
        primaryBinaryName: String = packageName
    ): InstallResult = withContext(Dispatchers.IO) {
        val mambaResult = micromambaEngine.installPackage(envName, packageName)
        if (!mambaResult.isSuccess || mambaResult.envPrefixDir == null) {
            return@withContext InstallResult(
                isSuccess = false,
                packageName = packageName,
                envPrefixDir = null,
                binaryFile = null,
                shimFile = null,
                errorMessage = mambaResult.errorOutput.ifBlank { "Failed to install $packageName via micromamba" }
            )
        }

        val prefixDir = mambaResult.envPrefixDir
        val targetBinary = File(prefixDir, "bin/$primaryBinaryName")
        val glibcInterpreter = File(prefixDir, "lib/ld-linux-aarch64.so.1")

        // Gather hardware-tuned compute & Python memory optimizations
        val combinedEnv = mutableMapOf<String, String>()
        combinedEnv.putAll(governor.getComputeEnvironmentVariables())
        combinedEnv.putAll(governor.getPythonOptimizationEnvironment())

        // Generate hermetic execution shim in /files/bin/
        val shim = shimGenerator.generateShim(
            BinaryShimGenerator.ShimConfiguration(
                commandName = primaryBinaryName,
                targetBinary = targetBinary,
                prefixDir = prefixDir,
                interpreterBinary = if (glibcInterpreter.exists()) glibcInterpreter else null,
                libraryDirs = listOf(
                    File(prefixDir, "lib"),
                    File(prefixDir, "lib/python3.12/site-packages")
                ),
                environmentVariables = combinedEnv
            )
        )

        searchDb?.setInstalled("conda-$packageName", true)

        InstallResult(
            isSuccess = true,
            packageName = packageName,
            envPrefixDir = prefixDir,
            binaryFile = targetBinary,
            shimFile = shim
        )
    }
}
