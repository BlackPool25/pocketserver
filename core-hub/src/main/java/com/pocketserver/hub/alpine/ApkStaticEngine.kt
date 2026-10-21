package com.pocketserver.hub.alpine

import com.pocketserver.hub.search.PackageTier
import com.pocketserver.hub.search.UnifiedPackageSearchDb
import com.pocketserver.hub.shim.BinaryShimGenerator
import com.pocketserver.toolchain.elf.ElfAlignmentEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Alpine Linux Engine powered by standalone `apk.static` binary.
 *
 * Implements Tier 2 (Universal 36,000+ Linux Packages):
 *   - Orchestrates multi-mirror repositories (`main`, `community`, `testing`).
 *   - Executes rootless `apk.static` to resolve all transitive shared libraries (.so).
 *   - Hardens against DNS stalls with [AlpineDnsConfigurator].
 *   - Validates 16KB page alignment on extracted ELF binaries.
 *   - Generates transparent Musl-sysroot shims in `/files/bin/`.
 */
class ApkStaticEngine(
    val apkStaticBinary: File,
    val alpineSysroot: File,
    private val shimGenerator: BinaryShimGenerator,
    private val searchDb: UnifiedPackageSearchDb? = null,
    private val elfAlignmentEngine: ElfAlignmentEngine = ElfAlignmentEngine(),
    private val dnsConfigurator: AlpineDnsConfigurator = AlpineDnsConfigurator(File(alpineSysroot, "etc"))
) {

    companion object {
        val DEFAULT_MIRRORS = listOf(
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/main",
            "https://dl-cdn.alpinelinux.org/alpine/v3.21/community"
        )
    }

    private val mirrors = mutableListOf<String>().apply { addAll(DEFAULT_MIRRORS) }
    private val etcApkDir = File(alpineSysroot, "etc/apk")

    init {
        alpineSysroot.mkdirs()
        etcApkDir.mkdirs()
        updateRepositoriesFile()
        dnsConfigurator.writeResolvConf()
    }

    fun getMirrors(): List<String> = mirrors.toList()

    fun addMirror(mirrorUrl: String) {
        if (!mirrors.contains(mirrorUrl)) {
            mirrors.add(mirrorUrl)
            updateRepositoriesFile()
        }
    }

    private fun updateRepositoriesFile() {
        val reposFile = File(etcApkDir, "repositories")
        val content = mirrors.joinToString("\n") + "\n"
        FileOutputStream(reposFile).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    data class InstallResult(
        val isSuccess: Boolean,
        val packageName: String,
        val canonicalBinary: File?,
        val shimFile: File?,
        val standardOutput: String,
        val errorOutput: String,
        val is16KbCompliant: Boolean
    )

    /**
     * Executes rootless `apk.static` to install package and transitive dependencies into [alpineSysroot].
     */
    suspend fun installPackage(
        packageName: String,
        primaryBinaryName: String = packageName
    ): InstallResult = withContext(Dispatchers.IO) {
        if (!apkStaticBinary.exists()) {
            return@withContext InstallResult(
                isSuccess = false,
                packageName = packageName,
                canonicalBinary = null,
                shimFile = null,
                standardOutput = "",
                errorOutput = "apk.static executable not found at ${apkStaticBinary.absolutePath}",
                is16KbCompliant = false
            )
        }

        apkStaticBinary.setExecutable(true, false)
        dnsConfigurator.writeResolvConf()

        val reposFile = File(etcApkDir, "repositories")
        val command = mutableListOf(
            apkStaticBinary.absolutePath,
            "--root", alpineSysroot.absolutePath,
            "--repositories-file", reposFile.absolutePath,
            "--allow-untrusted",
            "--initdb",
            "add",
            "--no-cache",
            packageName
        )

        return@withContext try {
            val process = ProcessBuilder(command)
                .directory(alpineSysroot)
                .redirectErrorStream(false)
                .start()

            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                return@withContext InstallResult(
                    isSuccess = false,
                    packageName = packageName,
                    canonicalBinary = null,
                    shimFile = null,
                    standardOutput = stdout,
                    errorOutput = stderr,
                    is16KbCompliant = false
                )
            }

            // Audit 16KB ELF alignment
            val scanResult = elfAlignmentEngine.scanDirectory(alpineSysroot)

            // Locate primary binary in sysroot (usr/bin or bin or usr/sbin)
            val candidatePaths = listOf(
                File(alpineSysroot, "usr/bin/$primaryBinaryName"),
                File(alpineSysroot, "bin/$primaryBinaryName"),
                File(alpineSysroot, "usr/sbin/$primaryBinaryName")
            )
            val realBinary = candidatePaths.firstOrNull { it.exists() } ?: candidatePaths[0]

            // Dynamic linker for Musl
            val muslInterpreter = File(alpineSysroot, "lib/ld-musl-aarch64.so.1")

            // Generate hermetic execution shim
            val shim = shimGenerator.generateShim(
                BinaryShimGenerator.ShimConfiguration(
                    commandName = primaryBinaryName,
                    targetBinary = realBinary,
                    prefixDir = alpineSysroot,
                    interpreterBinary = if (muslInterpreter.exists()) muslInterpreter else null,
                    libraryDirs = listOf(
                        File(alpineSysroot, "lib"),
                        File(alpineSysroot, "usr/lib")
                    ),
                    environmentVariables = dnsConfigurator.getNetworkEnvironmentVariables()
                )
            )

            searchDb?.setInstalled("alpine-$packageName", true)

            InstallResult(
                isSuccess = true,
                packageName = packageName,
                canonicalBinary = realBinary,
                shimFile = shim,
                standardOutput = stdout,
                errorOutput = stderr,
                is16KbCompliant = scanResult.isFully16KbCompliant
            )
        } catch (e: Exception) {
            InstallResult(
                isSuccess = false,
                packageName = packageName,
                canonicalBinary = null,
                shimFile = null,
                standardOutput = "",
                errorOutput = "Exception during apk.static invocation: ${e.message}",
                is16KbCompliant = false
            )
        }
    }
}
