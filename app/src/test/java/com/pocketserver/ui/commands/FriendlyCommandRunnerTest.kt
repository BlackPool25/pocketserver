package com.pocketserver.ui.commands

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FriendlyCommandRunnerTest {

    private lateinit var context: Context
    private lateinit var testDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDir = File(context.cacheDir, "cmd-test-" + System.currentTimeMillis()).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        testDir.deleteRecursively()
    }

    @Test
    fun testRecommendedActionsForPython() {
        File(testDir, "requirements.txt").writeText("fastapi\n")
        val runner = FriendlyCommandRunner(testDir)
        val actions = runner.getRecommendedActions("FastAPI")

        assertThat(actions).hasSize(3)
        assertThat(actions[0].id).isEqualTo("git-pull")
        assertThat(actions[1].id).isEqualTo("deps-python")
        assertThat(actions[2].id).isEqualTo("clean-cache")
    }

    @Test
    fun testRecommendedActionsForBun() {
        File(testDir, "package.json").writeText("{\"name\":\"app\"}")
        val runner = FriendlyCommandRunner(testDir)
        val actions = runner.getRecommendedActions("Vite")

        assertThat(actions).hasSize(3)
        assertThat(actions[1].id).isEqualTo("deps-bun")
        assertThat(actions[1].command).contains("bun")
    }

    @Test
    fun testExecuteEchoCommand() {
        val runner = FriendlyCommandRunner(testDir)
        val result = runner.executeCommand(listOf("sh", "-c", "echo 'Hello PocketServer'"))

        assertThat(result.isSuccess).isTrue()
        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout.trim()).isEqualTo("Hello PocketServer")
    }

    @Test
    fun testRawShellTerminalDrawerStreaming() = runTest {
        val drawer = RawShellTerminalDrawer(testDir, this)
        drawer.runCommandLine("echo 'stream test line 1' && echo 'stream test line 2'")

        drawer.awaitCompletion()

        val state = drawer.state.value
        assertThat(state.isRunning).isFalse()
        assertThat(state.lastExitCode).isEqualTo(0)

        val outputTexts = state.lines.map { it.text }
        assertThat(outputTexts).contains("stream test line 1")
        assertThat(outputTexts).contains("stream test line 2")
    }
}
