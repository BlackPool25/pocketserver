package com.pocketserver.proxy.supervisor

import com.pocketserver.nativeengine.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Supervised process orchestrator that controls backend execution states,
 * dispatches SIGSTOP/SIGCONT process group signals, and injects external DB
 * pre-ping pool settings to prevent broken pipe disconnects.
 */
class ProcessSupervisor(
    val serviceId: String,
    private val inactivityTimeoutMs: Long = DEFAULT_INACTIVITY_TIMEOUT_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val DEFAULT_INACTIVITY_TIMEOUT_MS = 60_000L // 60s idle before sleep

        // Edge Case 3: Injected environment defaults for external DB pools
        val DB_PREPING_ENV = mapOf(
            "SQLALCHEMY_POOL_PRE_PING" to "true",
            "SQLALCHEMY_POOL_RECYCLE" to "30",
            "PGCONNECT_TIMEOUT_MILLIS" to "5000",
            "PGIDLE_TIMEOUT_MILLIS" to "20000",
            "PRISMA_CONNECT_TIMEOUT" to "5",
            "PRISMA_POOL_TIMEOUT" to "20"
        )
    }

    private val _state = MutableStateFlow<ServiceProcessState>(ServiceProcessState.Stopped)
    val state: StateFlow<ServiceProcessState> = _state.asStateFlow()

    private var activeProcess: Process? = null
    private var processPid: Int = -1
    private var processPgid: Int = -1

    private val lastActivityTimestamp = AtomicLong(clock())
    private var inactivityJob: Job? = null
    private var monitorJob: Job? = null

    var lastWakeLatencyNanos: Long = 0L
        private set

    var wakeCount: Long = 0L
        private set

    /**
     * Injects database pool resilience environment variables into the process environment.
     */
    fun injectEnvironment(baseEnv: Map<String, String>): Map<String, String> {
        val merged = baseEnv.toMutableMap()
        for ((key, value) in DB_PREPING_ENV) {
            merged.putIfAbsent(key, value)
        }
        return merged
    }

    /**
     * Spawns a new backend service process with injected DB pre-ping pool variables
     * and initializes supervisory monitoring.
     */
    @Synchronized
    fun start(
        command: List<String>,
        workingDir: File,
        customEnv: Map<String, String> = emptyMap(),
        scope: CoroutineScope
    ): Process {
        if (_state.value is ServiceProcessState.Running || _state.value is ServiceProcessState.Sleeping) {
            throw IllegalStateException("Service $serviceId is already running or sleeping")
        }

        _state.value = ServiceProcessState.Starting

        val effectiveEnv = injectEnvironment(customEnv)
        val processBuilder = ProcessBuilder(command)
            .directory(workingDir)

        val env = processBuilder.environment()
        for ((k, v) in effectiveEnv) {
            env[k] = v
        }

        val process = processBuilder.start()
        activeProcess = process

        val pid = extractPid(process).toInt()
        processPid = pid
        processPgid = pid // In non-root Android subshells, pgid is initially equal to pid

        _state.value = ServiceProcessState.Running(pid, processPgid)
        recordActivity()

        // Launch exit monitor
        monitorJob = scope.launch {
            try {
                val exitCode = process.waitFor()
                synchronized(this@ProcessSupervisor) {
                    if (_state.value !is ServiceProcessState.Terminating && _state.value !is ServiceProcessState.Stopped) {
                        _state.value = ServiceProcessState.Failed(
                            exitCode = exitCode,
                            errorMessage = "Process $serviceId exited with code $exitCode"
                        )
                    } else {
                        _state.value = ServiceProcessState.Stopped
                    }
                }
            } catch (e: InterruptedException) {
                // Supervisor cancelled
            }
        }

        // Launch inactivity timer
        startInactivityTimer(scope)

        return process
    }

    /**
     * Attaches an existing process PID/PGID for testing and external supervision.
     */
    @Synchronized
    fun attach(pid: Int, pgid: Int = pid, scope: CoroutineScope? = null) {
        processPid = pid
        processPgid = pgid
        _state.value = ServiceProcessState.Running(pid, pgid)
        recordActivity()

        if (scope != null) {
            startInactivityTimer(scope)
        }
    }

    /**
     * Records network activity or inbound connection, resetting the idle sleep timer.
     */
    fun recordActivity(timestamp: Long = clock()) {
        lastActivityTimestamp.set(timestamp)
    }

    /**
     * Sends SIGSTOP to the process group, transitioning the process to SLEEPING.
     * CPU usage drops to 0.00% while RAM remains intact.
     */
    @Synchronized
    fun freeze(): Boolean {
        val currentState = _state.value
        if (currentState !is ServiceProcessState.Running) {
            return false
        }

        val pgid = processPgid
        val pid = processPid
        val res = if (pgid > 0) {
            val groupRes = NativeEngine.sendSignalToProcessGroup(pgid, NativeEngine.SIGSTOP)
            if (groupRes != 0 && pid > 0) {
                NativeEngine.sendSignalToProcess(pid, NativeEngine.SIGSTOP)
            } else groupRes
        } else if (pid > 0) {
            NativeEngine.sendSignalToProcess(pid, NativeEngine.SIGSTOP)
        } else {
            -1
        }

        if (res == 0) {
            _state.value = ServiceProcessState.Sleeping(pid, pgid)
            return true
        }

        return false
    }

    /**
     * Sends SIGCONT to the process group, waking the process instantly.
     * Benchmarks wake duration (< 1ms target).
     */
    @Synchronized
    fun wake(): Long {
        val currentState = _state.value
        if (currentState !is ServiceProcessState.Sleeping) {
            return 0L
        }

        val pid = processPid
        val pgid = processPgid
        _state.value = ServiceProcessState.Waking(pid, pgid)

        val startTime = System.nanoTime()

        val res = if (pgid > 0) {
            val groupRes = NativeEngine.sendSignalToProcessGroup(pgid, NativeEngine.SIGCONT)
            if (groupRes != 0 && pid > 0) {
                NativeEngine.sendSignalToProcess(pid, NativeEngine.SIGCONT)
            } else groupRes
        } else if (pid > 0) {
            NativeEngine.sendSignalToProcess(pid, NativeEngine.SIGCONT)
        } else {
            -1
        }

        val elapsedNanos = System.nanoTime() - startTime
        lastWakeLatencyNanos = elapsedNanos
        wakeCount++

        if (res == 0) {
            _state.value = ServiceProcessState.Running(pid, pgid)
            recordActivity()
        } else {
            _state.value = ServiceProcessState.Failed(
                exitCode = -1,
                errorMessage = "Failed to send SIGCONT to process group $pgid (errno $res)"
            )
        }

        return elapsedNanos
    }

    /**
     * Terminates the process gracefully with SIGTERM, escalating to SIGKILL after graceTimeoutMs.
     */
    @Synchronized
    fun terminate(graceTimeoutMs: Long = 3000L) {
        val pid = processPid
        val pgid = processPgid

        if (pid <= 0 && activeProcess == null) {
            _state.value = ServiceProcessState.Stopped
            return
        }

        _state.value = ServiceProcessState.Terminating(pid, pgid)
        inactivityJob?.cancel()
        inactivityJob = null

        // If process is sleeping, wake it before SIGTERM so it can handle clean termination
        if (NativeEngine.getProcessState(pid) == "T") {
            NativeEngine.sendSignalToProcess(pid, NativeEngine.SIGCONT)
        }

        // Send SIGTERM
        if (pgid > 0) {
            NativeEngine.sendSignalToProcessGroup(pgid, NativeEngine.SIGTERM)
        }
        if (pid > 0) {
            NativeEngine.sendSignalToProcess(pid, NativeEngine.SIGTERM)
        }

        val proc = activeProcess
        if (proc != null) {
            val terminated = try {
                proc.waitFor(graceTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                false
            }

            if (!terminated) {
                // Escalate to SIGKILL
                if (pgid > 0) {
                    NativeEngine.sendSignalToProcessGroup(pgid, NativeEngine.SIGKILL)
                }
                if (pid > 0) {
                    NativeEngine.sendSignalToProcess(pid, NativeEngine.SIGKILL)
                }
                proc.destroyForcibly()
            }
        }

        activeProcess = null
        processPid = -1
        processPgid = -1
        _state.value = ServiceProcessState.Stopped
    }

    private fun startInactivityTimer(scope: CoroutineScope) {
        inactivityJob?.cancel()
        inactivityJob = scope.launch {
            while (isActive) {
                delay(500L) // Poll idle delta every 500ms
                val current = clock()
                val idleTime = current - lastActivityTimestamp.get()

                if (idleTime >= inactivityTimeoutMs) {
                    synchronized(this@ProcessSupervisor) {
                        if (_state.value is ServiceProcessState.Running) {
                            freeze()
                        }
                    }
                }
            }
        }
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
