package com.pocketserver.ui.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * State representing the active PocketEditor mobile code inspection/editing session.
 */
data class EditorState(
    val serviceId: String,
    val activeFile: File? = null,
    val relativePath: String = "",
    val content: String = "",
    val isDirty: Boolean = false,
    val cursorLine: Int = 1,
    val cursorCol: Int = 1,
    val language: String = "plaintext",
    val statusMessage: String = ""
)

/**
 * Manages active code editing session, syntax inference, dirty buffer state,
 * and line/column telemetry for mobile code viewing.
 */
class PocketEditorSession(val serviceId: String) {

    private val _state = MutableStateFlow(EditorState(serviceId = serviceId))
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private var originalContent: String = ""

    /**
     * Loads a file from disk into the editor buffer.
     */
    fun openFile(file: File, relativePath: String = file.name) {
        val text = if (file.exists() && file.isFile) file.readText() else ""
        originalContent = text
        val lang = detectLanguage(file)

        _state.value = EditorState(
            serviceId = serviceId,
            activeFile = file,
            relativePath = relativePath,
            content = text,
            isDirty = false,
            cursorLine = 1,
            cursorCol = 1,
            language = lang,
            statusMessage = "Opened ${file.name}"
        )
    }

    /**
     * Updates buffer contents and computes dirty state.
     */
    fun onContentChanged(newContent: String) {
        val current = _state.value
        _state.value = current.copy(
            content = newContent,
            isDirty = newContent != originalContent
        )
    }

    /**
     * Updates cursor position coordinates.
     */
    fun onCursorChanged(line: Int, col: Int) {
        _state.value = _state.value.copy(
            cursorLine = line,
            cursorCol = col
        )
    }

    /**
     * Marks changes as committed/saved.
     */
    fun markClean() {
        originalContent = _state.value.content
        _state.value = _state.value.copy(isDirty = false, statusMessage = "Saved")
    }

    companion object {
        fun detectLanguage(file: File): String {
            return when (file.extension.lowercase()) {
                "py" -> "python"
                "js", "mjs", "cjs" -> "javascript"
                "ts", "mts", "cts" -> "typescript"
                "json" -> "json"
                "html", "htm" -> "html"
                "css" -> "css"
                "go" -> "go"
                "rs" -> "rust"
                "toml" -> "toml"
                "yaml", "yml" -> "yaml"
                "env" -> "properties"
                "md" -> "markdown"
                "sh", "bash" -> "shell"
                else -> if (file.name.startsWith(".env")) "properties" else "plaintext"
            }
        }
    }
}
