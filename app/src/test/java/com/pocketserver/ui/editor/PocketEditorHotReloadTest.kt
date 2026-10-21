package com.pocketserver.ui.editor

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PocketEditorHotReloadTest {

    private lateinit var context: Context
    private lateinit var projectDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        projectDir = File(context.cacheDir, "editor-test-project-" + System.currentTimeMillis()).apply { mkdirs() }
    }

    @After
    fun tearDown() {
        projectDir.deleteRecursively()
    }

    @Test
    fun testAtomicFileSaverPreservesContentAndReplacesAtomically() {
        val targetFile = File(projectDir, "config.json")
        targetFile.writeText("{\"version\": 1}")

        val updatedContent = "{\"version\": 2, \"active\": true}"
        val success = AtomicFileSaver.saveAtomic(targetFile, updatedContent)

        assertThat(success).isTrue()
        assertThat(targetFile.exists()).isTrue()
        assertThat(targetFile.readText()).isEqualTo(updatedContent)

        // Verify temporary files are cleaned up
        val tmpFiles = projectDir.listFiles { _, name -> name.contains(".tmp.") } ?: emptyArray()
        assertThat(tmpFiles).isEmpty()
    }

    @Test
    fun testPocketEditorSessionDirtyStateAndLanguageDetection() {
        val pyFile = File(projectDir, "main.py")
        pyFile.writeText("from fastapi import FastAPI\napp = FastAPI()")

        val session = PocketEditorSession("service-123")
        session.openFile(pyFile, "main.py")

        assertThat(session.state.value.activeFile).isEqualTo(pyFile)
        assertThat(session.state.value.language).isEqualTo("python")
        assertThat(session.state.value.isDirty).isFalse()

        // Modify content
        session.onContentChanged("from fastapi import FastAPI\napp = FastAPI()\n@app.get('/')\ndef root(): pass")
        assertThat(session.state.value.isDirty).isTrue()

        session.onCursorChanged(3, 10)
        assertThat(session.state.value.cursorLine).isEqualTo(3)
        assertThat(session.state.value.cursorCol).isEqualTo(10)

        // Mark saved
        session.markClean()
        assertThat(session.state.value.isDirty).isFalse()
    }

    @Test
    fun testProjectFileTreeHierarchy() {
        val srcDir = File(projectDir, "src").apply { mkdirs() }
        File(srcDir, "app.py").writeText("print('hello')")
        File(projectDir, ".env").writeText("PORT=8000")
        val gitDir = File(projectDir, ".git").apply { mkdirs() }
        File(gitDir, "HEAD").writeText("ref: refs/heads/main")

        val treeManager = ProjectFileTree(projectDir)
        val rootNode = treeManager.buildTree()

        assertThat(rootNode.isDirectory).isTrue()
        // .git folder should be ignored
        val childrenNames = rootNode.children.map { it.name }
        assertThat(childrenNames).contains(".env")
        assertThat(childrenNames).contains("src")
        assertThat(childrenNames).doesNotContain(".git")
    }

    @Test
    fun testHotReloadUnder500Milliseconds() {
        runBlocking {
            val envFile = File(projectDir, ".env")
            envFile.writeText("PORT=8000\nDEBUG=True\nDATABASE_URL=sqlite:///./dev.sqlite\n")

            val session = PocketEditorSession("test-service-456")
            session.openFile(envFile, ".env")
            val coordinator = HotReloadCoordinator(session)

            val newEnvContent = """
                PORT=8080
                DEBUG=False
                DATABASE_URL=sqlite:///./prod.sqlite
                RELOAD_TOKEN=active
            """.trimIndent()

            var restartInvoked = false

            // Action simulating process supervisor reload & port rebind
            val mockRestartAction: suspend (String) -> Unit = { serviceId ->
                restartInvoked = true
                // Simulate supervisor config reload
                delay(15) // 15ms simulated restart
            }

            // Execute [Save & Restart]
            val result = coordinator.saveAndRestart(
                serviceId = "test-service-456",
                targetFile = envFile,
                newContent = newEnvContent,
                restartAction = mockRestartAction
            )

            // Acceptance criteria verification
            assertThat(result.success).isTrue()
            assertThat(result.isSavedAtomically).isTrue()
            assertThat(restartInvoked).isTrue()
            assertThat(envFile.readText()).isEqualTo(newEnvContent)

            // Protocol Acceptance Requirement: Roundtrip < 500ms
            assertThat(result.reloadLatencyMillis).isLessThan(500.0)
            assertThat(result.isUnder500ms).isTrue()
        }
    }
}
