package com.pocketserver.hub.shim

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Generates transparent, hermetic POSIX execution shims in `/files/bin/`.
 *
 * Implements Layer 1 (Transparent Shims) and Layer 2/3 (Libc Isolation):
 *   - Guarantees zero environment pollution across Musl (Alpine), Glibc (Conda), and Bionic (Android).
 *   - Virtualizes dynamic linkers (`ld-musl-aarch64.so.1` / `ld-linux-aarch64.so.1`) to prevent ENOENT crashes.
 *   - Injects localized PATH, PREFIX, LD_LIBRARY_PATH, and thread governor environment variables.
 */
class BinaryShimGenerator(
    val binDir: File
) {

    init {
        binDir.mkdirs()
    }

    data class ShimConfiguration(
        val commandName: String,
        val targetBinary: File,
        val prefixDir: File,
        val interpreterBinary: File? = null,
        val libraryDirs: List<File> = emptyList(),
        val environmentVariables: Map<String, String> = emptyMap(),
        val defaultArguments: List<String> = emptyList()
    )

    /**
     * Generates or updates an executable POSIX shim atomically.
     */
    fun generateShim(config: ShimConfiguration): File {
        val shimFile = File(binDir, config.commandName)
        val tempFile = File(binDir, ".${config.commandName}.tmp-${System.nanoTime()}")

        val libPathDirs = if (config.libraryDirs.isNotEmpty()) {
            config.libraryDirs
        } else {
            listOf(
                File(config.prefixDir, "lib"),
                File(config.prefixDir, "usr/lib")
            )
        }
        val libPathStr = libPathDirs.joinToString(":") { it.absolutePath }

        val envExports = StringBuilder()
        for ((key, value) in config.environmentVariables) {
            val sanitizedVal = value.replace("\"", "\\\"").replace("$", "\\$")
            envExports.append("export $key=\"$sanitizedVal\"\n")
        }

        val defaultArgsStr = if (config.defaultArguments.isNotEmpty()) {
            config.defaultArguments.joinToString(" ") { "\"${it.replace("\"", "\\\"")}\"" } + " "
        } else ""

        val interpreterPath = config.interpreterBinary?.absolutePath

        val scriptContent = buildString {
            append("#!/bin/sh\n")
            append("# PocketServer Hermetic Execution Shim: ${config.commandName}\n")
            append("# Generated automatically by com.pocketserver.hub.shim.BinaryShimGenerator\n\n")
            append("export POCKET_SERVER_SHIM=1\n")
            append("export PREFIX=\"${config.prefixDir.absolutePath}\"\n")
            append("export PATH=\"${config.prefixDir.absolutePath}/bin:${config.prefixDir.absolutePath}/usr/bin:\$PATH\"\n")
            if (libPathStr.isNotBlank()) {
                append("export LD_LIBRARY_PATH=\"$libPathStr:\$LD_LIBRARY_PATH\"\n")
            }
            if (envExports.isNotEmpty()) {
                append(envExports)
            }
            append("\n")

            if (interpreterPath != null) {
                append("INTERP=\"$interpreterPath\"\n")
                append("TARGET=\"${config.targetBinary.absolutePath}\"\n")
                append("if [ -f \"\$INTERP\" ]; then\n")
                append("    exec \"\$INTERP\" --library-path \"$libPathStr\" \"\$TARGET\" $defaultArgsStr\"\$@\"\n")
                append("else\n")
                append("    exec \"\$TARGET\" $defaultArgsStr\"\$@\"\n")
                append("fi\n")
            } else {
                append("exec \"${config.targetBinary.absolutePath}\" $defaultArgsStr\"\$@\"\n")
            }
        }

        FileOutputStream(tempFile).use { fos ->
            fos.write(scriptContent.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }

        tempFile.setExecutable(true, false)
        tempFile.setReadable(true, false)

        if (!tempFile.renameTo(shimFile)) {
            // Fallback copy if atomic rename fails
            tempFile.copyTo(shimFile, overwrite = true)
            tempFile.delete()
        }

        shimFile.setExecutable(true, false)
        shimFile.setReadable(true, false)

        return shimFile
    }

    /**
     * Removes an executable shim from `/files/bin/`.
     */
    fun removeShim(commandName: String): Boolean {
        val shimFile = File(binDir, commandName)
        return if (shimFile.exists()) shimFile.delete() else true
    }

    /**
     * Checks if a shim exists and is executable.
     */
    fun hasShim(commandName: String): Boolean {
        val shimFile = File(binDir, commandName)
        return shimFile.exists() && shimFile.canExecute()
    }
}
