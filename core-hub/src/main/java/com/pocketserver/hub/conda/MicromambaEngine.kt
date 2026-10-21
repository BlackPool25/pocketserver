package com.pocketserver.hub.conda

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Rootless Micromamba Process Runner for Conda-Forge.
 *
 * Implements Tier 3 (25,000+ AI, Data Science & Complex C++ Packages):
 *   - Bundles rootless aarch64 `micromamba` runtime engine.
 *   - Enforces Zstandard metadata decompression (`repodata_use_zst: true`)
 *     and JLAP incremental diffing (`use_jlap: true`) for < 2s solving times.
 *   - Manages hermetic environment prefixes under `$FILES_DIR/conda/envs/<name>`.
 */
class MicromambaEngine(
    val micromambaBinary: File,
    val rootPrefixDir: File
) {

    val envsDir: File = File(rootPrefixDir, "envs")
    val configFile: File = File(rootPrefixDir, ".mambarc")

    init {
        rootPrefixDir.mkdirs()
        envsDir.mkdirs()
        writeMambarcConfig()
    }

    data class MicromambaResult(
        val isSuccess: Boolean,
        val exitCode: Int,
        val standardOutput: String,
        val errorOutput: String,
        val envPrefixDir: File?
    )

    private fun writeMambarcConfig() {
        val configYaml = """
            # PocketServer Micromamba Configuration for ARM64 Android
            channels:
              - conda-forge
            channel_priority: strict
            repodata_use_zst: true
            use_jlap: true
            max_parallel_downloads: 6
            always_yes: true
            extract_threads: 4
            ssl_no_revoke: true
        """.trimIndent() + "\n"

        FileOutputStream(configFile).use { fos ->
            fos.write(configYaml.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Resolves the canonical prefix directory for a named Conda environment.
     */
    fun getEnvironmentPrefix(envName: String): File {
        return File(envsDir, envName)
    }

    /**
     * Creates a new Conda environment with the requested packages.
     */
    suspend fun createEnvironment(
        envName: String,
        packages: List<String> = emptyList()
    ): MicromambaResult = withContext(Dispatchers.IO) {
        if (!micromambaBinary.exists()) {
            return@withContext MicromambaResult(
                isSuccess = false,
                exitCode = -1,
                standardOutput = "",
                errorOutput = "micromamba binary not found at ${micromambaBinary.absolutePath}",
                envPrefixDir = null
            )
        }

        micromambaBinary.setExecutable(true, false)
        val prefix = getEnvironmentPrefix(envName)

        val command = mutableListOf(
            micromambaBinary.absolutePath,
            "create",
            "--root-prefix", rootPrefixDir.absolutePath,
            "--prefix", prefix.absolutePath,
            "--rc-file", configFile.absolutePath,
            "-y"
        )
        command.addAll(packages)

        executeProcess(command, prefix)
    }

    /**
     * Installs one or more packages into an existing Conda environment.
     */
    suspend fun installPackage(
        envName: String,
        packageName: String
    ): MicromambaResult = withContext(Dispatchers.IO) {
        if (!micromambaBinary.exists()) {
            return@withContext MicromambaResult(
                isSuccess = false,
                exitCode = -1,
                standardOutput = "",
                errorOutput = "micromamba binary not found at ${micromambaBinary.absolutePath}",
                envPrefixDir = null
            )
        }

        micromambaBinary.setExecutable(true, false)
        val prefix = getEnvironmentPrefix(envName)

        val command = listOf(
            micromambaBinary.absolutePath,
            "install",
            "--root-prefix", rootPrefixDir.absolutePath,
            "--prefix", prefix.absolutePath,
            "--rc-file", configFile.absolutePath,
            "-y",
            packageName
        )

        executeProcess(command, prefix)
    }

    /**
     * Builds a [ProcessBuilder] configured to execute commands within the isolated Conda prefix.
     */
    fun buildProcessInCondaEnv(
        envName: String,
        command: List<String>,
        extraEnv: Map<String, String> = emptyMap()
    ): ProcessBuilder {
        val prefix = getEnvironmentPrefix(envName)
        val prefixBin = File(prefix, "bin")
        val prefixLib = File(prefix, "lib")

        val pb = ProcessBuilder(command)
        pb.directory(prefix)

        val env = pb.environment()
        env["CONDA_PREFIX"] = prefix.absolutePath
        env["PATH"] = "${prefixBin.absolutePath}:${env["PATH"] ?: ""}"
        env["LD_LIBRARY_PATH"] = "${prefixLib.absolutePath}:${env["LD_LIBRARY_PATH"] ?: ""}"

        for ((k, v) in extraEnv) {
            env[k] = v
        }

        return pb
    }

    private fun executeProcess(command: List<String>, prefix: File): MicromambaResult {
        return try {
            val pb = ProcessBuilder(command)
                .directory(rootPrefixDir)
                .redirectErrorStream(false)

            pb.environment()["MAMBA_ROOT_PREFIX"] = rootPrefixDir.absolutePath

            val process = pb.start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            MicromambaResult(
                isSuccess = exitCode == 0,
                exitCode = exitCode,
                standardOutput = stdout,
                errorOutput = stderr,
                envPrefixDir = if (exitCode == 0) prefix else null
            )
        } catch (e: Exception) {
            MicromambaResult(
                isSuccess = false,
                exitCode = -1,
                standardOutput = "",
                errorOutput = "Exception invoking micromamba: ${e.message}",
                envPrefixDir = null
            )
        }
    }
}
