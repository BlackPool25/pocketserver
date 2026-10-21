package com.pocketserver.ui.onboarding.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pocketserver.storage.db.PocketDatabase
import com.pocketserver.ui.monorepo.MonorepoWakeCoordinator
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
class MonorepoDetectorTest {

    private lateinit var context: Context
    private lateinit var tempRepoDir: File
    private lateinit var database: PocketDatabase
    private lateinit var wakeCoordinator: MonorepoWakeCoordinator
    private lateinit var orchestrator: RepoImportOrchestrator
    private val detector = MonorepoDetector()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        tempRepoDir = File(context.cacheDir, "test-monorepo-" + System.currentTimeMillis()).apply { mkdirs() }
        database = Room.inMemoryDatabaseBuilder(context, PocketDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        wakeCoordinator = MonorepoWakeCoordinator()
        orchestrator = RepoImportOrchestrator(context, database, detector, wakeCoordinator)
    }

    @After
    fun tearDown() {
        tempRepoDir.deleteRecursively()
        database.close()
    }

    @Test
    fun testDetectMonorepoBackendAndFrontend() {
        // Create /backend with FastAPI
        val backendDir = File(tempRepoDir, "backend").apply { mkdirs() }
        File(backendDir, "main.py").writeText("from fastapi import FastAPI\napp = FastAPI()")
        File(backendDir, "requirements.txt").writeText("fastapi\nuvicorn")

        // Create /frontend with Vite
        val frontendDir = File(tempRepoDir, "frontend").apply { mkdirs() }
        File(frontendDir, "package.json").writeText("{\"name\":\"web\",\"devDependencies\":{\"vite\":\"^5.0.0\"}}")

        val detected = detector.detect(tempRepoDir, "fullstack-app")
        assertThat(detected).hasSize(2)

        val backend = detected.first { it.role == ServiceRole.BACKEND }
        assertThat(backend.framework).isEqualTo("FastAPI")
        assertThat(backend.suggestedPort).isEqualTo(8000)
        assertThat(backend.suggestedName).isEqualTo("fullstack-app-backend")

        val frontend = detected.first { it.role == ServiceRole.FRONTEND }
        assertThat(frontend.framework).isEqualTo("Vite")
        assertThat(frontend.suggestedPort).isEqualTo(3000)
        assertThat(frontend.suggestedName).isEqualTo("fullstack-app-frontend")
    }

    @Test
    fun testDetectSingleRootService() {
        // Create single Go project in root
        File(tempRepoDir, "go.mod").writeText("module pocket/api\ngo 1.23")
        File(tempRepoDir, "main.go").writeText("package main\nfunc main() {}")

        val detected = detector.detect(tempRepoDir, "go-single")
        assertThat(detected).hasSize(1)
        assertThat(detected[0].framework).isEqualTo("Go")
        assertThat(detected[0].suggestedPort).isEqualTo(8080)
        assertThat(detected[0].suggestedName).isEqualTo("go-single")
    }

    @Test
    fun testOrchestratorImportAndLinkMonorepo() = runBlocking {
        // Setup monorepo structure
        val backendDir = File(tempRepoDir, "backend").apply { mkdirs() }
        File(backendDir, "main.py").writeText("from fastapi import FastAPI\napp = FastAPI()")
        val frontendDir = File(tempRepoDir, "frontend").apply { mkdirs() }
        File(frontendDir, "package.json").writeText("{\"devDependencies\":{\"vite\":\"^5.0.0\"}}")

        val result = orchestrator.importProject(
            repoUrl = "https://github.com/developer/my-fullstack-app.git",
            sourceDir = tempRepoDir
        )

        assertThat(result.provisionedServices).hasSize(2)
        assertThat(result.monorepoLink).isNotNull()

        val link = result.monorepoLink!!
        assertThat(link.backendServiceId).isNotNull()
        assertThat(link.frontendServiceId).isNotNull()
        assertThat(link.allServiceIds).hasSize(2)

        // Verify linked services in wakeCoordinator
        val linkedToFrontend = wakeCoordinator.getLinkedServices(link.frontendServiceId!!)
        assertThat(linkedToFrontend).containsExactly(link.backendServiceId)

        // Verify Room persistence
        val allSandboxes = database.sandboxDao().getAll()
        assertThat(allSandboxes).hasSize(2)
    }
}
