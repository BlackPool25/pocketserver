package com.pocketserver.ui.onboarding

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pocketserver.storage.db.PocketDatabase
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
class TemplateProvisionerTest {

    private lateinit var context: Context
    private lateinit var database: PocketDatabase
    private lateinit var provisioner: TemplateProvisioner

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PocketDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        provisioner = TemplateProvisioner(context, database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testProvisionFastApiTemplate() = runBlocking {
        val result = provisioner.provision(
            template = StarterTemplate.FASTAPI_HELLO_WORLD,
            customName = "my-fastapi-api",
            customPort = 8000
        )

        assertThat(result.name).isEqualTo("my-fastapi-api")
        assertThat(result.assignedPort).isEqualTo(8000)
        assertThat(result.projectDir.exists()).isTrue()

        // Verify main.py and .env files
        val mainPy = File(result.projectDir, "main.py")
        assertThat(mainPy.exists()).isTrue()
        assertThat(mainPy.readText()).contains("FastAPI(title=\"PocketServer Sample API\")")

        val envFile = File(result.projectDir, ".env")
        assertThat(envFile.exists()).isTrue()
        assertThat(envFile.readText()).contains("PORT=8000")

        // Verify Room persistence
        val saved = database.sandboxDao().getById(result.serviceId)
        assertThat(saved).isNotNull()
        assertThat(saved?.name).isEqualTo("my-fastapi-api")
        assertThat(saved?.assignedPort).isEqualTo(8000)
    }

    @Test
    fun testProvisionViteTemplateWithCustomPort() = runBlocking {
        val result = provisioner.provision(
            template = StarterTemplate.REACT_VITE_FRONTEND,
            customName = "frontend-landing",
            customPort = 3005
        )

        assertThat(result.name).isEqualTo("frontend-landing")
        assertThat(result.assignedPort).isEqualTo(3005)

        val pkgJson = File(result.projectDir, "package.json")
        assertThat(pkgJson.exists()).isTrue()
        assertThat(pkgJson.readText()).contains("vite-pocket-frontend")

        val envFile = File(result.projectDir, ".env")
        assertThat(envFile.readText()).contains("PORT=3005")
    }

    @Test
    fun testAllTemplatesAvailableInCatalog() {
        val templates = StarterTemplate.ALL_TEMPLATES
        assertThat(templates).hasSize(5)
        assertThat(templates.map { it.id }).containsExactly(
            "template-fastapi",
            "template-vite",
            "template-go",
            "template-elysia",
            "template-rust"
        )
    }
}
