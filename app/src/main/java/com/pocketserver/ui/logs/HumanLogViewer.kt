package com.pocketserver.ui.logs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel {
    INFO,
    WARN,
    ERROR,
    HTTP,
    DEBUG
}

enum class LogFilter {
    ALL,
    ERRORS_ONLY,
    HTTP_REQUESTS
}

/**
 * Individual log entry in the Human Log Viewer.
 */
data class LogEntry(
    val id: Long,
    val timestampMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    val formattedTimestamp: String
        get() = timeFormatter.format(Date(timestampMs))

    val displayLine: String
        get() = "[$formattedTimestamp] ${level.name}: $message"
}

/**
 * Human Log Viewer with anti-jerk auto-scroll pausing and 1-tap clipboard sharing.
 *
 * Implements Flow 7:
 * - Touching/scrolling logs automatically pauses auto-scroll to prevent sudden screen jerks.
 * - Jump to Latest button unpauses auto-scroll.
 * - Dynamic filters: All, Errors Only, HTTP Requests.
 * - 1-tap Copy Error Trace and Copy All.
 */
class HumanLogViewer(
    val serviceId: String,
    private val maxBufferLines: Int = 5000
) {

    private val idCounter = AtomicLong(0L)
    private val allEntries = mutableListOf<LogEntry>()

    private val _isAutoScrollPaused = MutableStateFlow(false)
    val isAutoScrollPaused: StateFlow<Boolean> = _isAutoScrollPaused.asStateFlow()

    private val _currentFilter = MutableStateFlow(LogFilter.ALL)
    val currentFilter: StateFlow<LogFilter> = _currentFilter.asStateFlow()

    private val _filteredLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    val filteredLogs: StateFlow<List<LogEntry>> = _filteredLogs.asStateFlow()

    private val _errorCount = MutableStateFlow(0)
    val errorCount: StateFlow<Int> = _errorCount.asStateFlow()

    /**
     * Appends a structured log entry.
     */
    @Synchronized
    fun append(level: LogLevel, message: String, tag: String = "App", timestamp: Long = System.currentTimeMillis()) {
        val entry = LogEntry(
            id = idCounter.incrementAndGet(),
            timestampMs = timestamp,
            level = level,
            tag = tag,
            message = message
        )
        allEntries.add(entry)
        if (allEntries.size > maxBufferLines) {
            allEntries.removeAt(0)
        }

        if (level == LogLevel.ERROR) {
            _errorCount.value += 1
        }

        refreshFilteredList()
    }

    /**
     * Ingests a raw output line from process stdout/stderr, automatically inferring log level.
     */
    @Synchronized
    fun appendRaw(rawLine: String, isStderr: Boolean = false) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return

        val level = when {
            isStderr -> LogLevel.ERROR
            trimmed.contains("ERROR", ignoreCase = true) || trimmed.contains("Exception") || trimmed.contains("Traceback") -> LogLevel.ERROR
            trimmed.contains("WARN", ignoreCase = true) -> LogLevel.WARN
            trimmed.contains("GET ") || trimmed.contains("POST ") || trimmed.contains("PUT ") || trimmed.contains("DELETE ") -> LogLevel.HTTP
            trimmed.contains("DEBUG", ignoreCase = true) -> LogLevel.DEBUG
            else -> LogLevel.INFO
        }

        append(level, trimmed)
    }

    /**
     * Invoked when user touches the screen or scrolls upward.
     * Pauses auto-scrolling to prevent screen jerking while user is inspecting logs.
     */
    fun onUserTouchOrScroll() {
        _isAutoScrollPaused.value = true
    }

    /**
     * Resumes auto-scrolling to the latest live log output.
     */
    fun jumpToLatest() {
        _isAutoScrollPaused.value = false
    }

    /**
     * Changes active filter chip.
     */
    @Synchronized
    fun setFilter(filter: LogFilter) {
        _currentFilter.value = filter
        refreshFilteredList()
    }

    @Synchronized
    private fun refreshFilteredList() {
        val filter = _currentFilter.value
        val result = when (filter) {
            LogFilter.ALL -> allEntries.toList()
            LogFilter.ERRORS_ONLY -> allEntries.filter { it.level == LogLevel.ERROR }
            LogFilter.HTTP_REQUESTS -> allEntries.filter { it.level == LogLevel.HTTP }
        }
        _filteredLogs.value = result
    }

    /**
     * Formats all currently displayed logs for clipboard copying.
     */
    @Synchronized
    fun copyCurrentLogsFormatted(): String {
        return _filteredLogs.value.joinToString("\n") { it.displayLine }
    }

    /**
     * Extracts only error traces and stack traces for 1-tap bug reporting.
     */
    @Synchronized
    fun copyErrorTraces(): String {
        val errors = allEntries.filter { it.level == LogLevel.ERROR }
        if (errors.isEmpty()) return "No errors recorded."
        return errors.joinToString("\n") { "[${it.formattedTimestamp}] ${it.message}" }
    }

    /**
     * Clears all log entries from the buffer.
     */
    @Synchronized
    fun clear() {
        allEntries.clear()
        _errorCount.value = 0
        refreshFilteredList()
    }
}
