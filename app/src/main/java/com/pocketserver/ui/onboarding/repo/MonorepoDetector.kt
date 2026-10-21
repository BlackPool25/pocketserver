package com.pocketserver.ui.onboarding.repo

import java.io.File

/**
 * Service detected within a project directory or repository.
 */
data class DetectedService(
    val subdir: String, // e.g. "", "/backend", "/frontend"
    val fullPath: File,
    val framework: String,
    val role: ServiceRole,
    val suggestedPort: Int,
    val startCommand: List<String>,
    val suggestedName: String
)

enum class ServiceRole {
    BACKEND,
    FRONTEND,
    STANDALONE
}

/**
 * Monorepo relationship link connecting backend and frontend services.
 */
data class MonorepoLink(
    val monorepoId: String,
    val repoUrl: String,
    val backendServiceId: String?,
    val frontendServiceId: String?,
    val allServiceIds: List<String>
)

/**
 * Inspects a repository folder structure to detect monorepos, microservices,
 * and standalone services, automatically inferring runtime, ports, and commands.
 */
class MonorepoDetector {

    companion object {
        private val COMMON_SUBDIRS = listOf(
            "backend", "frontend", "api", "web", "server", "client", "ui", "app"
        )
    }

    /**
     * Inspects the given root directory and determines all runnable services.
     */
    fun detect(repoDir: File, repoName: String = repoDir.name): List<DetectedService> {
        val detected = mutableListOf<DetectedService>()

        // 1. Check if subdirectories contain distinct services
        val candidateDirs = repoDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: emptyArray()
        for (sub in candidateDirs) {
            val subNameLower = sub.name.lowercase()
            if (COMMON_SUBDIRS.contains(subNameLower) || isServiceDirectory(sub)) {
                val service = inspectDirectory(sub, repoDir, repoName)
                if (service != null) {
                    detected.add(service)
                }
            }
        }

        // 2. If no sub-services detected, inspect the root directory itself
        if (detected.isEmpty()) {
            val rootService = inspectDirectory(repoDir, repoDir, repoName)
            if (rootService != null) {
                detected.add(rootService)
            }
        }

        return detected
    }

    private fun isServiceDirectory(dir: File): Boolean {
        return File(dir, "package.json").exists() ||
                File(dir, "main.py").exists() ||
                File(dir, "requirements.txt").exists() ||
                File(dir, "go.mod").exists() ||
                File(dir, "Cargo.toml").exists()
    }

    private fun inspectDirectory(targetDir: File, repoRoot: File, repoName: String): DetectedService? {
        val relSubdir = if (targetDir == repoRoot) "" else "/" + targetDir.name
        val nameSuffix = if (targetDir == repoRoot) "" else "-${targetDir.name.lowercase()}"
        val suggestedName = "$repoName$nameSuffix"

        // Check for Python Backend
        val hasMainPy = File(targetDir, "main.py").exists()
        val hasReqs = File(targetDir, "requirements.txt").exists()
        val hasPyProject = File(targetDir, "pyproject.toml").exists()

        if (hasMainPy || hasReqs || hasPyProject) {
            val isFastApi = hasMainPy && File(targetDir, "main.py").readText().contains("FastAPI")
            val framework = if (isFastApi) "FastAPI" else "Python"
            return DetectedService(
                subdir = relSubdir,
                fullPath = targetDir,
                framework = framework,
                role = ServiceRole.BACKEND,
                suggestedPort = 8000,
                startCommand = listOf("python", "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"),
                suggestedName = suggestedName
            )
        }

        // Check for Node / Bun / Frontend
        val pkgJsonFile = File(targetDir, "package.json")
        if (pkgJsonFile.exists()) {
            val content = pkgJsonFile.readText()
            val isVite = content.contains("\"vite\"")
            val isReact = content.contains("\"react\"")
            val isNext = content.contains("\"next\"")
            val isElysia = content.contains("\"elysia\"")

            return if (isElysia) {
                DetectedService(
                    subdir = relSubdir,
                    fullPath = targetDir,
                    framework = "Elysia",
                    role = ServiceRole.BACKEND,
                    suggestedPort = 3001,
                    startCommand = listOf("bun", "run", "src/index.ts"),
                    suggestedName = suggestedName
                )
            } else {
                val framework = when {
                    isVite -> "Vite"
                    isNext -> "Next.js"
                    isReact -> "React"
                    else -> "Node.js"
                }
                DetectedService(
                    subdir = relSubdir,
                    fullPath = targetDir,
                    framework = framework,
                    role = ServiceRole.FRONTEND,
                    suggestedPort = 3000,
                    startCommand = listOf("bun", "run", "dev", "--port", "3000", "--host", "0.0.0.0"),
                    suggestedName = suggestedName
                )
            }
        }

        // Check for Go Microservice
        if (File(targetDir, "go.mod").exists() || File(targetDir, "main.go").exists()) {
            return DetectedService(
                subdir = relSubdir,
                fullPath = targetDir,
                framework = "Go",
                role = ServiceRole.BACKEND,
                suggestedPort = 8080,
                startCommand = listOf("go", "run", "main.go"),
                suggestedName = suggestedName
            )
        }

        // Check for Rust Service
        if (File(targetDir, "Cargo.toml").exists()) {
            return DetectedService(
                subdir = relSubdir,
                fullPath = targetDir,
                framework = "Rust",
                role = ServiceRole.BACKEND,
                suggestedPort = 8081,
                startCommand = listOf("cargo", "run", "--release"),
                suggestedName = suggestedName
            )
        }

        return null
    }
}
