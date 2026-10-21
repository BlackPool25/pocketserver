package com.pocketserver.proxy.supervisor

import com.google.common.truth.Truth.assertThat
import com.pocketserver.nativeengine.NativeEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessSupervisorTest {

    @Test
    fun `injectEnvironment populates resilient database pre-ping pool parameters`() {
        val supervisor = ProcessSupervisor(serviceId = "srv-test-1")
        val baseEnv = mapOf("PORT" to "8000", "DEBUG" to "false")

        val result = supervisor.injectEnvironment(baseEnv)

        assertThat(result["PORT"]).isEqualTo("8000")
        assertThat(result["DEBUG"]).isEqualTo("false")
        assertThat(result["SQLALCHEMY_POOL_PRE_PING"]).isEqualTo("true")
        assertThat(result["SQLALCHEMY_POOL_RECYCLE"]).isEqualTo("30")
        assertThat(result["PGCONNECT_TIMEOUT_MILLIS"]).isEqualTo("5000")
        assertThat(result["PGIDLE_TIMEOUT_MILLIS"]).isEqualTo("20000")
        assertThat(result["PRISMA_CONNECT_TIMEOUT"]).isEqualTo("5")
        assertThat(result["PRISMA_POOL_TIMEOUT"]).isEqualTo("20")
    }

    @Test
    fun `freeze and wake transition process state and benchmark wake latency`() {
        val supervisor = ProcessSupervisor(serviceId = "srv-sleep-wake")
        val process = ProcessBuilder("sleep", "30").start()
        val pid = extractPid(process).toInt()

        try {
            supervisor.attach(pid = pid, pgid = pid, process = process)
            assertThat(supervisor.state.value).isInstanceOf(ServiceProcessState.Running::class.java)

            // Freeze via SIGSTOP
            val frozen = supervisor.freeze()
            assertThat(frozen).isTrue()
            assertThat(supervisor.state.value).isInstanceOf(ServiceProcessState.Sleeping::class.java)

            // Verify process state in procfs is 'T' (stopped)
            val procState = NativeEngine.getProcessState(pid)
            assertThat(procState).isEqualTo("T")

            // Wake via SIGCONT
            val wakeLatency = supervisor.wake()
            assertThat(wakeLatency).isGreaterThan(0L)
            assertThat(supervisor.state.value).isInstanceOf(ServiceProcessState.Running::class.java)
            assertThat(supervisor.wakeCount).isEqualTo(1L)
            assertThat(supervisor.lastWakeLatencyNanos).isGreaterThan(0L)

            // Verify process resumed in procfs ('S' or 'R')
            val resumedState = NativeEngine.getProcessState(pid)
            assertThat(listOf("S", "R")).contains(resumedState)
        } finally {
            supervisor.terminate()
            process.destroyForcibly()
        }
    }

    @Test
    fun `inactivity timer automatically freezes active process after idle timeout`() = runTest {
        var simulatedTime = 100_000L
        val supervisor = ProcessSupervisor(
            serviceId = "srv-auto-idle",
            inactivityTimeoutMs = 1500L,
            clock = { simulatedTime }
        )
        val process = ProcessBuilder("sleep", "30").start()
        val pid = extractPid(process).toInt()

        try {
            supervisor.attach(pid = pid, pgid = pid, scope = this)
            assertThat(supervisor.state.value).isInstanceOf(ServiceProcessState.Running::class.java)

            // Advance simulated time past inactivity timeout
            simulatedTime += 2000L
            delay(600L) // Allow coroutine delay loop to tick

            assertThat(supervisor.state.value).isInstanceOf(ServiceProcessState.Sleeping::class.java)
        } finally {
            supervisor.terminate()
            process.destroyForcibly()
        }
    }

    @Test
    fun `terminate halts process cleanly with SIGTERM and transitions to Stopped`() {
        val supervisor = ProcessSupervisor(serviceId = "srv-term")
        val process = ProcessBuilder("sleep", "30").start()
        val pid = extractPid(process).toInt()

        supervisor.attach(pid = pid, pgid = pid, process = process)
        supervisor.terminate(graceTimeoutMs = 1000L)

        assertThat(supervisor.state.value).isEqualTo(ServiceProcessState.Stopped)
        assertThat(process.isAlive).isFalse()
    }

    private fun extractPid(process: Process): Long {
        return try {
            val method = Process::class.java.getMethod("pid")
            (method.invoke(process) as Number).toLong()
        } catch (e: Exception) {
            val toHandle = Process::class.java.getMethod("toHandle")
            val handle = toHandle.invoke(process)
            val pidMethod = handle.javaClass.getMethod("pid")
            (pidMethod.invoke(handle) as Number).toLong()
        }
    }
}
