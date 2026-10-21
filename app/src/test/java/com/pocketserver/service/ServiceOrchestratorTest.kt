package com.pocketserver.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pocketserver.proxy.supervisor.ServiceProcessState
import com.pocketserver.storage.db.PocketDatabase
import com.pocketserver.storage.db.SandboxEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class ServiceOrchestratorTest {

    private lateinit var context: Context
    private lateinit var database: PocketDatabase
    private lateinit var orchestrator: ServiceOrchestrator
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var tempProjectDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PocketDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        tempProjectDir = File(context.filesDir, "test_project").apply { mkdirs() }
        File(tempProjectDir, "main.py").writeText("print('PocketServer test service')\n")

        orchestrator = ServiceOrchestrator(
            context = context,
            database = database,
            autoStartMetricsPolling = false,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        orchestrator.shutdownAll()
        database.close()
        tempProjectDir.deleteRecursively()
    }

    @Test
    fun testRealLocalIpResolution() {
        val ip = orchestrator.currentLocalIp
        assertThat(ip).isNotEmpty()
        // Baseline fallback or resolved IP
        assertThat(ip).matches("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
    }

    @Test
    fun testInferStartCommand() {
        val pyDir = File(context.filesDir, "py_proj").apply { mkdirs() }
        File(pyDir, "main.py").writeText("# python code")
        val cmd = orchestrator.inferStartCommand(pyDir, 8080)
        assertThat(cmd).contains("uvicorn")
        assertThat(cmd).contains("8080")
        pyDir.deleteRecursively()

        val nodeDir = File(context.filesDir, "node_proj").apply { mkdirs() }
        File(nodeDir, "package.json").writeText("{}")
        val nodeCmd = orchestrator.inferStartCommand(nodeDir, 3000)
        assertThat(nodeCmd).contains("bun")
        assertThat(nodeCmd).contains("3000")
        nodeDir.deleteRecursively()
    }

    @Test
    fun testStartFreezeWakeStopLifecycle() = runTest(testDispatcher) {
        val entity = SandboxEntity(
            serviceId = "svc-test-1",
            name = "TestApi",
            assignedPort = 8080,
            rootfsPath = tempProjectDir.absolutePath,
            projectRepoPath = tempProjectDir.absolutePath,
            status = "STOPPED"
        )
        database.sandboxDao().insert(entity)

        val process = ProcessBuilder("sleep", "10").start()
        val pid = try {
            val f = process.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            f.getLong(process).toInt()
        } catch (_: Throwable) {
            12345
        }

        val supervisor = orchestrator.getSupervisor("svc-test-1")
        supervisor.attach(pid = pid, pgid = pid, process = process)
        database.sandboxDao().updateStatus("svc-test-1", "LIVE")

        assertThat(supervisor.state.value).isInstanceOf(ServiceProcessState.Running::class.java)

        // 1. Freeze service (SIGSTOP -> SLEEPING)
        val sleepResult = orchestrator.sleepService("svc-test-1")
        assertThat(sleepResult.isSuccess).isTrue()
        val dbAfterSleep = database.sandboxDao().getById("svc-test-1")
        assertThat(dbAfterSleep?.status).isEqualTo("SLEEPING")

        // 2. Wake service (SIGCONT -> LIVE)
        val wakeResult = orchestrator.wakeService("svc-test-1")
        assertThat(wakeResult.isSuccess).isTrue()
        val dbAfterWake = database.sandboxDao().getById("svc-test-1")
        assertThat(dbAfterWake?.status).isEqualTo("LIVE")

        // 3. Terminate service -> STOPPED
        val stopResult = orchestrator.stopService("svc-test-1")
        assertThat(stopResult.isSuccess).isTrue()
        val dbAfterStop = database.sandboxDao().getById("svc-test-1")
        assertThat(dbAfterStop?.status).isEqualTo("STOPPED")

        process.destroyForcibly()
    }

    @Test
    fun testProcRssAndCpuMetricParsing() {
        // Read self process /proc RSS
        val selfPid = android.os.Process.myPid()
        val rssMb = orchestrator.readProcRssMb(selfPid)
        assertThat(rssMb).isAtLeast(0L)

        // CPU percent sampling
        val cpu = orchestrator.readProcCpuPercent(selfPid)
        assertThat(cpu).isAtLeast(0.0)

        // Invalid PID returns 0
        assertThat(orchestrator.readProcRssMb(-1)).isEqualTo(0L)
        assertThat(orchestrator.readProcCpuPercent(-1)).isEqualTo(0.0)
    }
}
