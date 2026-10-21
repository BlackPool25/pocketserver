package com.pocketserver.service

import android.content.Context
import com.pocketserver.network.mdns.MdnsDiscoveryManager
import com.pocketserver.network.roaming.IpMigrationListener
import com.pocketserver.network.roaming.NetworkRoamingMonitor
import com.pocketserver.network.roaming.NetworkState
import com.pocketserver.network.tunnel.CloudflareQuickTunnel
import com.pocketserver.network.tunnel.TunnelState
import com.pocketserver.proxy.activation.SocketActivationProxy
import com.pocketserver.proxy.supervisor.ProcessSupervisor
import com.pocketserver.proxy.supervisor.ServiceProcessState
import com.pocketserver.storage.db.PocketDatabase
import com.pocketserver.storage.db.SandboxEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Real-time operational metrics for a hosted service.
 */
data class ServiceMetrics(
    val serviceId: String,
    val ramUsageMb: Long = 0L,
    val cpuPercent: Double = 0.0,
    val pid: Int = -1,
    val status: String = "STOPPED",
    val tunnelUrl: String? = null
)

/**
 * Core domain orchestrator that binds UI lifecycles to real execution subsystems:
 * - [ProcessSupervisor] for POSIX SIGSTOP/SIGCONT process freezing & wakeups
 * - [SocketActivationProxy] for non-blocking NIO ingress & wake-on-connect
 * - [MdnsDiscoveryManager] for zero-conf .local DNS broadcasting
 * - [NetworkRoamingMonitor] for real device IP detection & Wi-Fi migration
 * - [CloudflareQuickTunnel] & [CloudflaredProvisioner] for public HTTPS trycloudflare.com tunnels
 * - Real /proc/<pid>/statm & /proc/<pid>/stat resource monitoring
 */
class ServiceOrchestrator(
    private val context: Context,
    private val database: PocketDatabase,
    val networkMonitor: NetworkRoamingMonitor = NetworkRoamingMonitor(context),
    val mdnsManager: MdnsDiscoveryManager = MdnsDiscoveryManager(context),
    val cloudflaredProvisioner: CloudflaredProvisioner = CloudflaredProvisioner(context),
    private val autoStartMetricsPolling: Boolean = true,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val orchestratorScope = CoroutineScope(SupervisorJob() + ioDispatcher)

    // Active supervisors, proxies, and tunnels keyed by serviceId
    private val supervisors = ConcurrentHashMap<String, ProcessSupervisor>()
    private val proxies = ConcurrentHashMap<String, SocketActivationProxy>()
    private val tunnels = ConcurrentHashMap<String, CloudflareQuickTunnel>()

    // Live metrics per service
    private val _metrics = MutableStateFlow<Map<String, ServiceMetrics>>(emptyMap())
    val metrics: StateFlow<Map<String, ServiceMetrics>> = _metrics.asStateFlow()

    // Logging callback hook: (serviceId, logLine, isError) -> Unit
    var logCallback: ((String, String, Boolean) -> Unit)? = null

    // Polling job for /proc resource sampling
    private var metricsPollingJob: Job? = null

    // CPU sampling state: Map<Pid, CpuSample>
    private data class CpuSample(val ticks: Long, val timestampMs: Long)
    private val cpuSamples = ConcurrentHashMap<Int, CpuSample>()

    init {
        networkMonitor.startMonitoring()
        networkMonitor.addListener(object : IpMigrationListener {
            override fun onIpMigrated(oldIp: String, newIp: String, state: NetworkState) {
                mdnsManager.reRegisterAll(newIp)
                logCallback?.invoke("system", "Network migrated: $oldIp -> $newIp (${state.interfaceType})", false)
            }
        })
        if (autoStartMetricsPolling) {
            startMetricsPolling()
        }
    }

    /**
     * Resolves the current local IP (e.g. 192.168.x.x on Wi-Fi, or 127.0.0.1).
     */
    val currentLocalIp: String
        get() = networkMonitor.networkState.value.ipAddress

    /**
     * Gets or creates a [ProcessSupervisor] for the given service.
     */
    fun getSupervisor(serviceId: String): ProcessSupervisor {
        return supervisors.getOrPut(serviceId) { ProcessSupervisor(serviceId) }
    }

    /**
     * Gets an active [CloudflareQuickTunnel] for the service if running.
     */
    fun getTunnel(serviceId: String): CloudflareQuickTunnel? = tunnels[serviceId]

    /**
     * Starts or launches a service with its configured command line.
     */
    suspend fun startService(
        entity: SandboxEntity,
        customCommand: List<String>? = null
    ): Result<Unit> = withContext(ioDispatcher) {
        val serviceId = entity.serviceId
        val supervisor = getSupervisor(serviceId)

        val workingDir = File(entity.projectRepoPath)
        val command = customCommand ?: inferStartCommand(workingDir, entity.assignedPort)

        logCallback?.invoke(serviceId, "Starting service '${entity.name}' with: ${command.joinToString(" ")}", false)

        try {
            // Environment configuration: prepend filesDir/bin to PATH
            val binDir = File(context.filesDir, "bin")
            val baseEnv = System.getenv().toMutableMap()
            val existingPath = baseEnv["PATH"] ?: "/system/bin:/system/xbin"
            baseEnv["PATH"] = "${binDir.absolutePath}:$existingPath"
            baseEnv["HOME"] = File(context.filesDir, "home").absolutePath
            baseEnv["TMPDIR"] = File(context.filesDir, "tmp").absolutePath
            baseEnv["PORT"] = entity.assignedPort.toString()

            val process = supervisor.start(
                command = command,
                workingDir = workingDir,
                customEnv = baseEnv,
                scope = orchestratorScope
            )

            // Pipe output to log viewers
            pipeProcessOutput(serviceId, process.inputStream, isError = false)
            pipeProcessOutput(serviceId, process.errorStream, isError = true)

            // Register mDNS discovery
            mdnsManager.registerService(entity.name, entity.assignedPort)

            // Update database status
            database.sandboxDao().updateStatus(serviceId, "LIVE")

            // Monitor state changes in background
            orchestratorScope.launch {
                supervisor.state.collect { state ->
                    handleSupervisorStateChange(entity, state)
                }
            }

            logCallback?.invoke(serviceId, "Service '${entity.name}' is now LIVE on port ${entity.assignedPort}", false)
            Result.success(Unit)
        } catch (e: Exception) {
            logCallback?.invoke(serviceId, "Failed to start service: ${e.message}", true)
            database.sandboxDao().updateStatus(serviceId, "STOPPED")
            Result.failure(e)
        }
    }

    /**
     * Freezes the service process via SIGSTOP (sleep state).
     */
    suspend fun sleepService(serviceId: String): Result<Unit> = withContext(ioDispatcher) {
        val supervisor = supervisors[serviceId] ?: return@withContext Result.failure(
            IllegalStateException("No supervisor active for $serviceId")
        )
        try {
            supervisor.freeze()
            database.sandboxDao().updateStatus(serviceId, "SLEEPING")
            logCallback?.invoke(serviceId, "Service entered SLEEPING state (SIGSTOP). Memory preserved.", false)
            Result.success(Unit)
        } catch (e: Exception) {
            logCallback?.invoke(serviceId, "Failed to freeze service: ${e.message}", true)
            Result.failure(e)
        }
    }

    /**
     * Resumes the service process via SIGCONT (wake state).
     */
    suspend fun wakeService(serviceId: String): Result<Unit> = withContext(ioDispatcher) {
        val supervisor = supervisors[serviceId] ?: return@withContext Result.failure(
            IllegalStateException("No supervisor active for $serviceId")
        )
        try {
            supervisor.wake()
            database.sandboxDao().updateStatus(serviceId, "LIVE")
            logCallback?.invoke(serviceId, "Service resumed LIVE state (SIGCONT).", false)
            Result.success(Unit)
        } catch (e: Exception) {
            logCallback?.invoke(serviceId, "Failed to wake service: ${e.message}", true)
            Result.failure(e)
        }
    }

    /**
     * Terminates the service process and tears down proxies and mDNS registrations.
     */
    suspend fun stopService(serviceId: String): Result<Unit> = withContext(ioDispatcher) {
        val supervisor = supervisors[serviceId]
        val entity = database.sandboxDao().getById(serviceId)

        try {
            supervisor?.terminate()
            proxies.remove(serviceId)?.stop()
            stopTunnel(serviceId)

            if (entity != null) {
                mdnsManager.unregisterService(entity.name)
            }

            database.sandboxDao().updateStatus(serviceId, "STOPPED")
            logCallback?.invoke(serviceId, "Service stopped.", false)
            Result.success(Unit)
        } catch (e: Exception) {
            logCallback?.invoke(serviceId, "Error stopping service: ${e.message}", true)
            Result.failure(e)
        }
    }

    /**
     * Starts a Cloudflare Quick Tunnel forwarding to the service port.
     */
    suspend fun startTunnel(
        serviceId: String,
        localPort: Int,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): Result<CloudflareQuickTunnel> = withContext(ioDispatcher) {
        try {
            val provisionResult = cloudflaredProvisioner.ensureProvisioned(onProgress)
            if (provisionResult.isFailure) {
                val err = provisionResult.exceptionOrNull() ?: Exception("Failed to provision cloudflared")
                return@withContext Result.failure(err)
            }

            val binaryFile = provisionResult.getOrThrow()
            val tunnel = tunnels.getOrPut(serviceId) { CloudflareQuickTunnel() }
            tunnel.start(localPort = localPort, scope = orchestratorScope, binaryPath = binaryFile.absolutePath)

            logCallback?.invoke(serviceId, "Cloudflare Quick Tunnel initiated for port $localPort", false)
            Result.success(tunnel)
        } catch (e: Exception) {
            logCallback?.invoke(serviceId, "Failed to start tunnel: ${e.message}", true)
            Result.failure(e)
        }
    }

    /**
     * Stops the Cloudflare Quick Tunnel for the given service.
     */
    fun stopTunnel(serviceId: String) {
        tunnels.remove(serviceId)?.stop()
    }

    private fun handleSupervisorStateChange(entity: SandboxEntity, state: ServiceProcessState) {
        val statusString = when (state) {
            is ServiceProcessState.Running -> "LIVE"
            is ServiceProcessState.Sleeping -> "SLEEPING"
            is ServiceProcessState.Starting -> "STARTING"
            is ServiceProcessState.Waking -> "STARTING"
            is ServiceProcessState.Terminating -> "STOPPING"
            is ServiceProcessState.Stopped -> "STOPPED"
            is ServiceProcessState.Failed -> "STOPPED"
        }

        orchestratorScope.launch {
            database.sandboxDao().updateStatus(entity.serviceId, statusString)
        }

        if (state is ServiceProcessState.Failed) {
            logCallback?.invoke(
                entity.serviceId,
                "Process exited unexpectedly: ${state.errorMessage} (code: ${state.exitCode})",
                true
            )
        }
    }

    private fun pipeProcessOutput(serviceId: String, stream: InputStream, isError: Boolean) {
        orchestratorScope.launch(Dispatchers.IO) {
            try {
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { logCallback?.invoke(serviceId, it, isError) }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Starts continuous /proc polling loop for real memory and CPU metrics.
     */
    private fun startMetricsPolling() {
        metricsPollingJob?.cancel()
        metricsPollingJob = orchestratorScope.launch {
            while (isActive) {
                delay(2000L) // Poll every 2 seconds
                sampleActiveProcessMetrics()
            }
        }
    }

    private fun sampleActiveProcessMetrics() {
        val currentMetrics = mutableMapOf<String, ServiceMetrics>()

        for ((serviceId, supervisor) in supervisors) {
            val state = supervisor.state.value
            val pid = when (state) {
                is ServiceProcessState.Running -> state.pid
                is ServiceProcessState.Sleeping -> state.pid
                else -> -1
            }

            val status = when (state) {
                is ServiceProcessState.Running -> "LIVE"
                is ServiceProcessState.Sleeping -> "SLEEPING"
                is ServiceProcessState.Starting -> "STARTING"
                else -> "STOPPED"
            }

            val tunnelUrl = (tunnels[serviceId]?.tunnelState?.value as? TunnelState.Active)?.publicUrl

            if (pid > 0) {
                val ramMb = readProcRssMb(pid)
                val cpu = if (state is ServiceProcessState.Running) readProcCpuPercent(pid) else 0.0
                currentMetrics[serviceId] = ServiceMetrics(
                    serviceId = serviceId,
                    ramUsageMb = ramMb,
                    cpuPercent = cpu,
                    pid = pid,
                    status = status,
                    tunnelUrl = tunnelUrl
                )
            } else {
                currentMetrics[serviceId] = ServiceMetrics(
                    serviceId = serviceId,
                    ramUsageMb = 0L,
                    cpuPercent = 0.0,
                    pid = -1,
                    status = status,
                    tunnelUrl = tunnelUrl
                )
            }
        }

        _metrics.value = currentMetrics
    }

    /**
     * Reads RSS (Resident Set Size) from /proc/<pid>/statm in MB.
     */
    fun readProcRssMb(pid: Int): Long {
        if (pid <= 0) return 0L
        val statm = File("/proc/$pid/statm")
        if (!statm.exists() || !statm.canRead()) return 0L
        return try {
            val text = statm.readText().trim()
            val parts = text.split("\\s+".toRegex())
            if (parts.size >= 2) {
                val residentPages = parts[1].toLongOrNull() ?: 0L
                (residentPages * 4096L) / (1024L * 1024L)
            } else 0L
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Calculates CPU percentage by comparing elapsed jiffies from /proc/<pid>/stat.
     */
    fun readProcCpuPercent(pid: Int): Double {
        if (pid <= 0) return 0.0
        val stat = File("/proc/$pid/stat")
        if (!stat.exists() || !stat.canRead()) return 0.0
        return try {
            val content = stat.readText()
            val afterComm = content.substringAfterLast(')').trim()
            val fields = afterComm.split("\\s+".toRegex())
            if (fields.size >= 13) {
                val utime = fields[11].toLongOrNull() ?: 0L
                val stime = fields[12].toLongOrNull() ?: 0L
                val totalTicks = utime + stime
                val now = System.currentTimeMillis()

                val prev = cpuSamples[pid]
                cpuSamples[pid] = CpuSample(totalTicks, now)

                if (prev != null && now > prev.timestampMs) {
                    val deltaTicks = (totalTicks - prev.ticks).coerceAtLeast(0)
                    val deltaSec = (now - prev.timestampMs) / 1000.0
                    val percent = (deltaTicks / (100.0 * deltaSec)) * 100.0
                    percent.coerceIn(0.0, 100.0)
                } else {
                    0.0
                }
            } else 0.0
        } catch (_: Exception) {
            0.0
        }
    }

    /**
     * Infers appropriate start command for a project repository.
     */
    fun inferStartCommand(repoDir: File, port: Int): List<String> {
        val binDir = File(context.filesDir, "bin")

        val pythonBin = File(binDir, "python").takeIf { it.exists() }?.absolutePath ?: "python"
        val bunBin = File(binDir, "bun").takeIf { it.exists() }?.absolutePath ?: "bun"
        val nodeBin = File(binDir, "node").takeIf { it.exists() }?.absolutePath ?: "node"

        return when {
            File(repoDir, "main.py").exists() -> listOf(
                pythonBin, "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", port.toString()
            )
            File(repoDir, "package.json").exists() -> listOf(
                bunBin, "run", "dev", "--port", port.toString(), "--host", "0.0.0.0"
            )
            File(repoDir, "go.mod").exists() -> listOf("go", "run", "main.go")
            File(repoDir, "Cargo.toml").exists() -> listOf("cargo", "run")
            else -> listOf("sh", "-c", "echo 'No start script found' && sleep 3600")
        }
    }

    /**
     * Stops all active services, tunnels, and proxies cleanly.
     */
    fun shutdownAll() {
        metricsPollingJob?.cancel()
        metricsPollingJob = null
        orchestratorScope.cancel()
        for ((serviceId, supervisor) in supervisors) {
            try {
                supervisor.terminate()
            } catch (_: Exception) {}
            mdnsManager.unregisterService(serviceId)
        }
        for (proxy in proxies.values) {
            try {
                proxy.stop()
            } catch (_: Exception) {}
        }
        for (tunnel in tunnels.values) {
            try {
                tunnel.stop()
            } catch (_: Exception) {}
        }
        supervisors.clear()
        proxies.clear()
        tunnels.clear()
        cpuSamples.clear()
    }
}
