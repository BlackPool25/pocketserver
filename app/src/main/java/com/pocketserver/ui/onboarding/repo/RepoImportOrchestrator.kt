package com.pocketserver.ui.onboarding.repo

import android.content.Context
import com.pocketserver.storage.db.PocketDatabase
import com.pocketserver.storage.db.SandboxEntity
import com.pocketserver.ui.monorepo.MonorepoWakeCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Result of a repository onboarding operation.
 */
data class RepoImportResult(
    val repoUrl: String,
    val repoDir: File,
    val provisionedServices: List<ImportedServiceInfo>,
    val monorepoLink: MonorepoLink?
)

data class ImportedServiceInfo(
    val serviceId: String,
    val name: String,
    val port: Int,
    val framework: String,
    val role: ServiceRole,
    val workingDir: File,
    val startCommand: List<String>
)

/**
 * Orchestrates 3-tap repository onboarding:
 * 1. Clones/locates repository on disk inside context.filesDir/projects.
 * 2. Runs MonorepoDetector to detect backend, frontend, or microservices.
 * 3. Provisions sandboxes in Room DB and links multi-service monorepos for coordinated wake.
 */
class RepoImportOrchestrator(
    private val context: Context,
    private val database: PocketDatabase,
    private val detector: MonorepoDetector = MonorepoDetector(),
    private val wakeCoordinator: MonorepoWakeCoordinator
) {

    /**
     * Imports a project repository from a given URL or existing local directory,
     * detects runnable services, provisions sandbox records, and wires monorepo links.
     */
    suspend fun importProject(
        repoUrl: String,
        sourceDir: File? = null
    ): RepoImportResult = withContext(Dispatchers.IO) {
        val repoName = repoUrl.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
        val projectsBaseDir = File(context.filesDir, "projects")
        val targetRepoDir = sourceDir ?: File(projectsBaseDir, repoName).apply { mkdirs() }

        // Detect all runnable services in repo
        val detected = detector.detect(targetRepoDir, repoName)
        if (detected.isEmpty()) {
            throw IllegalArgumentException("No runnable services detected in repository: $repoUrl")
        }

        val sandboxesBaseDir = File(context.filesDir, "sandboxes")
        val provisionedList = mutableListOf<ImportedServiceInfo>()

        var backendServiceId: String? = null
        var frontendServiceId: String? = null
        val allServiceIds = mutableListOf<String>()

        for (service in detected) {
            val serviceId = UUID.randomUUID().toString()
            val rootfsDir = File(sandboxesBaseDir, serviceId).apply { mkdirs() }

            val sandboxEntity = SandboxEntity(
                serviceId = serviceId,
                name = service.suggestedName,
                assignedPort = service.suggestedPort,
                rootfsPath = rootfsDir.absolutePath,
                projectRepoPath = service.fullPath.absolutePath,
                status = "STOPPED",
                createdAtEpochMs = System.currentTimeMillis()
            )
            database.sandboxDao().insert(sandboxEntity)

            val info = ImportedServiceInfo(
                serviceId = serviceId,
                name = service.suggestedName,
                port = service.suggestedPort,
                framework = service.framework,
                role = service.role,
                workingDir = service.fullPath,
                startCommand = service.startCommand
            )
            provisionedList.add(info)
            allServiceIds.add(serviceId)

            if (service.role == ServiceRole.BACKEND && backendServiceId == null) {
                backendServiceId = serviceId
            } else if (service.role == ServiceRole.FRONTEND && frontendServiceId == null) {
                frontendServiceId = serviceId
            }
        }

        val monorepoLink = if (provisionedList.size > 1) {
            val link = MonorepoLink(
                monorepoId = UUID.randomUUID().toString(),
                repoUrl = repoUrl,
                backendServiceId = backendServiceId,
                frontendServiceId = frontendServiceId,
                allServiceIds = allServiceIds
            )
            wakeCoordinator.registerLink(link)
            link
        } else {
            null
        }

        RepoImportResult(
            repoUrl = repoUrl,
            repoDir = targetRepoDir,
            provisionedServices = provisionedList,
            monorepoLink = monorepoLink
        )
    }
}
