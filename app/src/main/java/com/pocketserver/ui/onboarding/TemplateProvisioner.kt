package com.pocketserver.ui.onboarding

import android.content.Context
import com.pocketserver.storage.db.PocketDatabase
import com.pocketserver.storage.db.SandboxEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Result representing an instantiated and ready-to-run starter project.
 */
data class ProvisionedProject(
    val serviceId: String,
    val name: String,
    val assignedPort: Int,
    val projectDir: File,
    val rootfsDir: File,
    val startCommand: List<String>,
    val template: StarterTemplate
)

/**
 * Provisions 1-tap quick start templates into isolated non-root sandboxes
 * under context.filesDir/projects/<serviceId>.
 */
class TemplateProvisioner(
    private val context: Context,
    private val database: PocketDatabase
) {

    /**
     * Instantiates the given template onto internal disk, generates initial files,
     * updates environment variables, and persists the sandbox metadata in the database.
     */
    suspend fun provision(
        template: StarterTemplate,
        customName: String? = null,
        customPort: Int? = null
    ): ProvisionedProject = withContext(Dispatchers.IO) {
        val serviceId = UUID.randomUUID().toString()
        val projectName = customName ?: "${template.id.removePrefix("template-")}-${serviceId.take(4)}"
        val assignedPort = customPort ?: template.defaultPort

        val baseProjectsDir = File(context.filesDir, "projects")
        val projectDir = File(baseProjectsDir, serviceId).apply { mkdirs() }

        val baseSandboxesDir = File(context.filesDir, "sandboxes")
        val rootfsDir = File(baseSandboxesDir, serviceId).apply { mkdirs() }

        // Materialize template files
        for ((relPath, content) in template.templateFiles) {
            val targetFile = File(projectDir, relPath)
            targetFile.parentFile?.mkdirs()

            // Update port in .env if customized
            val finalContent = if (relPath == ".env" && customPort != null) {
                content.replace("PORT=${template.defaultPort}", "PORT=$assignedPort")
            } else {
                content
            }
            targetFile.writeText(finalContent)
        }

        // Initialize git markers
        val gitDir = File(projectDir, ".git").apply { mkdirs() }
        File(gitDir, "HEAD").writeText("ref: refs/heads/main\n")

        // Persist sandbox metadata
        val sandboxEntity = SandboxEntity(
            serviceId = serviceId,
            name = projectName,
            assignedPort = assignedPort,
            rootfsPath = rootfsDir.absolutePath,
            projectRepoPath = projectDir.absolutePath,
            status = "STOPPED",
            createdAtEpochMs = System.currentTimeMillis()
        )
        database.sandboxDao().insert(sandboxEntity)

        ProvisionedProject(
            serviceId = serviceId,
            name = projectName,
            assignedPort = assignedPort,
            projectDir = projectDir,
            rootfsDir = rootfsDir,
            startCommand = template.startCommand,
            template = template
        )
    }
}
