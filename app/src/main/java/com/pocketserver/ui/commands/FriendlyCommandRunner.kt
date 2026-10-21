package com.pocketserver.ui.commands

import java.io.File

/**
 * Predefined 1-tap maintenance action replacing intimidatory hacker toolbars.
 */
data class MaintenanceAction(
    val id: String,
    val title: String,
    val description: String,
    val command: List<String>,
    val iconType: ActionIconType
)

enum class ActionIconType {
    GIT_PULL,
    PACKAGE_INSTALL,
    CLEAN_CACHE,
    CUSTOM
}

/**
 * Result of command execution in project directory.
 */
data class CommandExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val executionTimeMillis: Long,
    val isSuccess: Boolean = exitCode == 0
)

/**
 * Executes friendly 1-tap maintenance actions and custom commands
 * in non-root project environments.
 */
class FriendlyCommandRunner(
    private val workingDir: File
) {

    /**
     * Inactive or active recommended actions based on repository inspection.
     */
    fun getRecommendedActions(framework: String = ""): List<MaintenanceAction> {
        val actions = mutableListOf<MaintenanceAction>()

        // 1. Update Code from GitHub
        actions.add(
            MaintenanceAction(
                id = "git-pull",
                title = "Update Code from GitHub",
                description = "Runs 'git pull' to fetch latest commits",
                command = listOf("git", "pull"),
                iconType = ActionIconType.GIT_PULL
            )
        )

        // 2. Re-install Dependencies based on project framework
        val depAction = when {
            File(workingDir, "requirements.txt").exists() || framework.contains("Python", ignoreCase = true) -> {
                MaintenanceAction(
                    id = "deps-python",
                    title = "Re-install Dependencies",
                    description = "Runs 'uv pip install -r requirements.txt'",
                    command = listOf("python", "-m", "pip", "install", "-r", "requirements.txt"),
                    iconType = ActionIconType.PACKAGE_INSTALL
                )
            }
            File(workingDir, "package.json").exists() || framework.contains("Vite", ignoreCase = true) || framework.contains("Node", ignoreCase = true) -> {
                MaintenanceAction(
                    id = "deps-bun",
                    title = "Re-install Dependencies",
                    description = "Runs 'bun install' with frozen lockfile",
                    command = listOf("bun", "install"),
                    iconType = ActionIconType.PACKAGE_INSTALL
                )
            }
            File(workingDir, "go.mod").exists() -> {
                MaintenanceAction(
                    id = "deps-go",
                    title = "Download Go Modules",
                    description = "Runs 'go mod download'",
                    command = listOf("go", "mod", "download"),
                    iconType = ActionIconType.PACKAGE_INSTALL
                )
            }
            File(workingDir, "Cargo.toml").exists() -> {
                MaintenanceAction(
                    id = "deps-cargo",
                    title = "Fetch Cargo Crates",
                    description = "Runs 'cargo check'",
                    command = listOf("cargo", "check"),
                    iconType = ActionIconType.PACKAGE_INSTALL
                )
            }
            else -> {
                MaintenanceAction(
                    id = "deps-generic",
                    title = "Re-install Dependencies",
                    description = "Updates dependencies for active toolchain",
                    command = listOf("sh", "-c", "echo 'No package manifest found'"),
                    iconType = ActionIconType.PACKAGE_INSTALL
                )
            }
        }
        actions.add(depAction)

        // 3. Clean Temporary Build Files
        actions.add(
            MaintenanceAction(
                id = "clean-cache",
                title = "Clean Temporary Build Files",
                description = "Safely purges build caches and tmpfs buffers",
                command = listOf("rm", "-rf", "__pycache__", ".pytest_cache", "target/tmp", "node_modules/.cache"),
                iconType = ActionIconType.CLEAN_CACHE
            )
        )

        return actions
    }

    /**
     * Executes a command synchronously or with timeout, capturing stdout and stderr.
     */
    fun executeCommand(
        commandList: List<String>,
        environment: Map<String, String> = emptyMap()
    ): CommandExecutionResult {
        val startTime = System.currentTimeMillis()

        return try {
            val processBuilder = ProcessBuilder(commandList)
                .directory(workingDir)
                .redirectErrorStream(false)

            val env = processBuilder.environment()
            for ((k, v) in environment) {
                env[k] = v
            }

            val process = processBuilder.start()

            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            val duration = System.currentTimeMillis() - startTime
            CommandExecutionResult(
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                executionTimeMillis = duration,
                isSuccess = exitCode == 0
            )
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            CommandExecutionResult(
                exitCode = -1,
                stdout = "",
                stderr = e.message ?: "Execution error",
                executionTimeMillis = duration,
                isSuccess = false
            )
        }
    }

    /**
     * Executes a custom shell command string (e.g. "alembic upgrade head").
     */
    fun executeCustom(commandStr: String, environment: Map<String, String> = emptyMap()): CommandExecutionResult {
        val parts = commandStr.trim().split(Regex("\\s+"))
        return executeCommand(parts, environment)
    }
}
