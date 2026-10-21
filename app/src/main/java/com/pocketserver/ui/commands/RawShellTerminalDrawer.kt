package com.pocketserver.ui.commands

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Output line from the raw shell drawer.
 */
data class ShellOutputLine(
    val text: String,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * State of the raw shell terminal drawer.
 */
data class RawShellState(
    val isRunning: Boolean = false,
    val lastExitCode: Int? = null,
    val lines: List<ShellOutputLine> = emptyList(),
    val currentWorkingDir: String = ""
)

/**
 * Terminal drawer runner for advanced users who want to run custom shell scripts
 * with live streaming output.
 */
class RawShellTerminalDrawer(
    private val workingDir: File,
    private val scope: CoroutineScope
) {

    private val _state = MutableStateFlow(RawShellState(currentWorkingDir = workingDir.absolutePath))
    val state: StateFlow<RawShellState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private var activeProcess: Process? = null

    /**
     * Executes a command line with live output streaming.
     */
    fun runCommandLine(commandLine: String, environment: Map<String, String> = emptyMap()) {
        if (_state.value.isRunning) return

        _state.value = _state.value.copy(
            isRunning = true,
            lastExitCode = null,
            lines = _state.value.lines + ShellOutputLine("$ $commandLine", isError = false)
        )

        activeJob = scope.launch(Dispatchers.IO) {
            try {
                val parts = listOf("sh", "-c", commandLine)
                val pb = ProcessBuilder(parts)
                    .directory(workingDir)
                    .redirectErrorStream(false)

                val env = pb.environment()
                for ((k, v) in environment) {
                    env[k] = v
                }

                val process = pb.start()
                activeProcess = process

                val stdoutReader = process.inputStream.bufferedReader()
                val stderrReader = process.errorStream.bufferedReader()

                // Read output
                var line: String? = stdoutReader.readLine()
                while (line != null) {
                    val outLine = ShellOutputLine(line, isError = false)
                    _state.value = _state.value.copy(lines = _state.value.lines + outLine)
                    line = stdoutReader.readLine()
                }

                var errLine: String? = stderrReader.readLine()
                while (errLine != null) {
                    val outLine = ShellOutputLine(errLine, isError = true)
                    _state.value = _state.value.copy(lines = _state.value.lines + outLine)
                    errLine = stderrReader.readLine()
                }

                val exitCode = process.waitFor()
                _state.value = _state.value.copy(
                    isRunning = false,
                    lastExitCode = exitCode,
                    lines = _state.value.lines + ShellOutputLine("[Process completed with exit code $exitCode]", isError = exitCode != 0)
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isRunning = false,
                    lastExitCode = -1,
                    lines = _state.value.lines + ShellOutputLine("[Error: ${e.message}]", isError = true)
                )
            } finally {
                activeProcess = null
            }
        }
    }

    /**
     * Waits for active command execution to complete.
     */
    suspend fun awaitCompletion() {
        activeJob?.join()
    }

    /**
     * Clears terminal history.
     */
    fun clear() {
        _state.value = _state.value.copy(lines = emptyList(), lastExitCode = null)
    }

    /**
     * Terminate active running command.
     */
    fun terminate() {
        activeProcess?.destroyForcibly()
        activeJob?.cancel()
        _state.value = _state.value.copy(isRunning = false)
    }
}
