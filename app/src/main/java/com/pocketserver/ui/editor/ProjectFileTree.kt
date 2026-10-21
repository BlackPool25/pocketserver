package com.pocketserver.ui.editor

import java.io.File

/**
 * Node in the project file explorer tree.
 */
data class ProjectFileNode(
    val name: String,
    val file: File,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val depth: Int = 0,
    var isExpanded: Boolean = false,
    val children: MutableList<ProjectFileNode> = mutableListOf()
)

/**
 * Manages hierarchical file browsing, directory tree building, and file operations
 * within an isolated project sandbox.
 */
class ProjectFileTree(val rootDir: File) {

    /**
     * Builds the complete project tree, ignoring VCS metadata and bulky internal caches.
     */
    fun buildTree(
        maxDepth: Int = 5,
        ignoredFolders: Set<String> = setOf(".git", "__pycache__", ".pytest_cache", "target", "node_modules")
    ): ProjectFileNode {
        return buildNode(rootDir, rootDir, 0, maxDepth, ignoredFolders)
    }

    private fun buildNode(
        currentFile: File,
        root: File,
        depth: Int,
        maxDepth: Int,
        ignoredFolders: Set<String>
    ): ProjectFileNode {
        val relPath = currentFile.relativeTo(root).path
        val isDir = currentFile.isDirectory
        val node = ProjectFileNode(
            name = if (currentFile == root) root.name else currentFile.name,
            file = currentFile,
            relativePath = if (relPath.isEmpty()) "/" else relPath,
            isDirectory = isDir,
            sizeBytes = if (isDir) 0L else currentFile.length(),
            depth = depth,
            isExpanded = depth == 0
        )

        if (isDir && depth < maxDepth) {
            val children = currentFile.listFiles { f ->
                !ignoredFolders.contains(f.name)
            }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()

            for (child in children) {
                node.children.add(buildNode(child, root, depth + 1, maxDepth, ignoredFolders))
            }
        }

        return node
    }

    /**
     * Creates a new file inside the sandbox.
     */
    fun createFile(relativePath: String, initialContent: String = ""): File {
        val target = File(rootDir, relativePath)
        target.parentFile?.mkdirs()
        target.writeText(initialContent)
        return target
    }

    /**
     * Deletes a file or directory within the project tree.
     */
    fun delete(relativePath: String): Boolean {
        val target = File(rootDir, relativePath)
        return if (target.isDirectory) {
            target.deleteRecursively()
        } else {
            target.delete()
        }
    }
}
