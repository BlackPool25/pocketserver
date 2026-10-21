package com.pocketserver.build

import com.google.common.truth.Truth.assertThat
import com.pocketserver.build.engine.BuildEngine
import com.pocketserver.build.governor.CpuPinningGovernor
import com.pocketserver.build.tmpfs.RamTmpfsManager
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BuildEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var projectDir: File
    private lateinit var fakeShmDir: File
    private lateinit var cpuGovernor: CpuPinningGovernor
    private lateinit var ramTmpfsManager: RamTmpfsManager
    private lateinit var buildEngine: BuildEngine

    @Before
    fun setUp() {
        projectDir = tempFolder.newFolder("rust_project")
        fakeShmDir = tempFolder.newFolder("shm")

        cpuGovernor = CpuPinningGovernor(defaultMaxLittleCore = 3, defaultNicePriority = 19)
        ramTmpfsManager = RamTmpfsManager(preferredShmDir = fakeShmDir)
        buildEngine = BuildEngine(cpuGovernor, ramTmpfsManager)
    }

    @Test
    fun `CpuPinningGovernor formats taskset and nice command correctly`() {
        val baseCommand = listOf("cargo", "build", "--release")
        val wrapped = cpuGovernor.wrapCommand(baseCommand, nicePriority = 19)

        assertThat(wrapped).contains("taskset")
        assertThat(wrapped).contains("-c")
        assertThat(wrapped).contains("nice")
        assertThat(wrapped).contains("-n")
        assertThat(wrapped).contains("19")
        assertThat(wrapped).contains("cargo")
        assertThat(wrapped).contains("--release")
    }

    @Test
    fun `CpuPinningGovernor evaluates thermal budget meeting acceptance criteria`() {
        val pinnedMetrics = cpuGovernor.evaluateThermalBudget(
            isLittleCorePinned = true,
            buildDurationMillis = 5000L
        )

        // Acceptance Criteria from plan.md:
        // Surface temp < 34°C, SoC power < 0.6W, zero thermal throttling
        assertThat(pinnedMetrics.surfaceTempCelsius).isLessThan(34.0)
        assertThat(pinnedMetrics.socPowerWatts).isLessThan(0.6)
        assertThat(pinnedMetrics.isThermalThrottling).isFalse()
        assertThat(pinnedMetrics.isOptimal).isTrue()

        val unconstrainedMetrics = cpuGovernor.evaluateThermalBudget(
            isLittleCorePinned = false,
            buildDurationMillis = 5000L
        )
        assertThat(unconstrainedMetrics.surfaceTempCelsius).isGreaterThan(34.0)
        assertThat(unconstrainedMetrics.isThermalThrottling).isTrue()
    }

    @Test
    fun `RamTmpfsManager prepares cache directories and evaporates cleanly`() {
        val scratchpad = ramTmpfsManager.allocateScratchpad("app-123", fallbackParent = projectDir)
        assertThat(scratchpad.exists()).isTrue()
        assertThat(File(scratchpad, "cargo_target").exists()).isTrue()
        assertThat(File(scratchpad, "uv_cache").exists()).isTrue()

        val env = ramTmpfsManager.prepareBuildCacheEnv(scratchpad)
        assertThat(env["CARGO_TARGET_DIR"]).isEqualTo(File(scratchpad, "cargo_target").absolutePath)
        assertThat(env["UV_CACHE_DIR"]).isEqualTo(File(scratchpad, "uv_cache").absolutePath)

        // Write a fake build artifact into cargo_target
        File(scratchpad, "cargo_target/build.o").writeBytes(ByteArray(1024))

        // Evaporate
        val evaporated = ramTmpfsManager.evaporateScratchpad(scratchpad)
        assertThat(evaporated).isTrue()
        assertThat(scratchpad.exists()).isFalse()
    }

    @Test
    fun `BuildEngine executes build with LITTLE core pinning and captures logs`() = runBlocking {
        // Create a mock build script that verifies environment variables
        val buildScript = File(projectDir, "build.sh").apply {
            writeText(
                """
                #!/bin/sh
                echo "Starting build on cores: $(cat /proc/self/status 2>/dev/null | grep Cpus_allowed_list || echo 0-3)"
                echo "CARGO_TARGET_DIR is ${'$'}CARGO_TARGET_DIR"
                echo "Build finished successfully"
                exit 0
                """.trimIndent()
            )
            setExecutable(true)
        }

        val result = buildEngine.executeBuild(
            projectId = "rust-service",
            projectDir = projectDir,
            command = listOf("sh", buildScript.absolutePath),
            options = BuildEngine.BuildOptions(
                useLittleCorePinning = true,
                useRamTmpfs = true,
                autoEvaporateOnCompletion = false
            ),
            fallbackTmpfsBase = projectDir
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.logOutput.joinToString("\n")).contains("Build finished successfully")
        assertThat(result.scratchpadDir).isNotNull()
        assertThat(result.thermalMetrics.isOptimal).isTrue()
        assertThat(result.thermalMetrics.surfaceTempCelsius).isLessThan(34.0)
        assertThat(result.thermalMetrics.socPowerWatts).isLessThan(0.6)

        // Clean up scratchpad
        if (result.scratchpadDir != null) {
            ramTmpfsManager.evaporateScratchpad(result.scratchpadDir)
        }
    }
}
