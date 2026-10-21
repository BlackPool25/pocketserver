package com.pocketserver.ui.logs

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HumanLogViewerTest {

    private lateinit var logViewer: HumanLogViewer

    @Before
    fun setUp() {
        logViewer = HumanLogViewer("test-service-1", maxBufferLines = 100)
    }

    @Test
    fun testAutoScrollPausesOnUserTouchAndResumesOnJump() {
        assertThat(logViewer.isAutoScrollPaused.value).isFalse()

        // User touches or scrolls up in log view
        logViewer.onUserTouchOrScroll()
        assertThat(logViewer.isAutoScrollPaused.value).isTrue()

        // User taps "Jump to Latest (Auto-Scroll Paused)"
        logViewer.jumpToLatest()
        assertThat(logViewer.isAutoScrollPaused.value).isFalse()
    }

    @Test
    fun testLogFilteringByLevel() {
        logViewer.append(LogLevel.INFO, "Uvicorn running on http://0.0.0.0:8000")
        logViewer.append(LogLevel.INFO, "Application startup complete.")
        logViewer.append(LogLevel.HTTP, "GET /api/v1/health - 200 OK (1.2ms)")
        logViewer.append(LogLevel.ERROR, "Traceback (most recent call last): ZeroDivisionError")

        assertThat(logViewer.filteredLogs.value).hasSize(4)
        assertThat(logViewer.errorCount.value).isEqualTo(1)

        // Switch filter to ERRORS_ONLY
        logViewer.setFilter(LogFilter.ERRORS_ONLY)
        assertThat(logViewer.filteredLogs.value).hasSize(1)
        assertThat(logViewer.filteredLogs.value[0].level).isEqualTo(LogLevel.ERROR)
        assertThat(logViewer.filteredLogs.value[0].message).contains("ZeroDivisionError")

        // Switch filter to HTTP_REQUESTS
        logViewer.setFilter(LogFilter.HTTP_REQUESTS)
        assertThat(logViewer.filteredLogs.value).hasSize(1)
        assertThat(logViewer.filteredLogs.value[0].level).isEqualTo(LogLevel.HTTP)
        assertThat(logViewer.filteredLogs.value[0].message).contains("GET /api/v1/health")

        // Switch back to ALL
        logViewer.setFilter(LogFilter.ALL)
        assertThat(logViewer.filteredLogs.value).hasSize(4)
    }

    @Test
    fun testCopyErrorTracesAndFormatting() {
        logViewer.append(LogLevel.INFO, "Startup OK")
        logViewer.append(LogLevel.ERROR, "Database connection timeout at 10.0.0.1:5432")

        val traces = logViewer.copyErrorTraces()
        assertThat(traces).contains("Database connection timeout at 10.0.0.1:5432")

        val allLogs = logViewer.copyCurrentLogsFormatted()
        assertThat(allLogs).contains("INFO: Startup OK")
        assertThat(allLogs).contains("ERROR: Database connection timeout")
    }

    @Test
    fun testBufferCapacityEviction() {
        val tinyViewer = HumanLogViewer("service-tiny", maxBufferLines = 5)
        for (i in 1..10) {
            tinyViewer.append(LogLevel.INFO, "Line $i")
        }

        assertThat(tinyViewer.filteredLogs.value).hasSize(5)
        val firstMessage = tinyViewer.filteredLogs.value[0].message
        val lastMessage = tinyViewer.filteredLogs.value[4].message

        assertThat(firstMessage).isEqualTo("Line 6")
        assertThat(lastMessage).isEqualTo("Line 10")
    }
}
