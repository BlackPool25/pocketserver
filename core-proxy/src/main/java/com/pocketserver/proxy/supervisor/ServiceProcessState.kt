package com.pocketserver.proxy.supervisor

/**
 * Lifecycle states of a hosted backend service process supervised by PocketServer.
 */
sealed class ServiceProcessState {
    /**
     * Service is completely stopped; no process is running.
     */
    data object Stopped : ServiceProcessState()

    /**
     * Service process is starting up and initializing runtime environments.
     */
    data object Starting : ServiceProcessState()

    /**
     * Service is actively running and scheduled on the CPU, serving TCP traffic.
     */
    data class Running(
        val pid: Int,
        val pgid: Int,
        val startTimeMs: Long = System.currentTimeMillis()
    ) : ServiceProcessState()

    /**
     * Service is frozen via SIGSTOP. CPU usage is strictly 0.00%, memory stays resident in RAM.
     */
    data class Sleeping(
        val pid: Int,
        val pgid: Int,
        val frozenTimeMs: Long = System.currentTimeMillis()
    ) : ServiceProcessState()

    /**
     * Service is being unfrozen via SIGCONT after an inbound connection.
     */
    data class Waking(
        val pid: Int,
        val pgid: Int,
        val wakeRequestedMs: Long = System.currentTimeMillis()
    ) : ServiceProcessState()

    /**
     * Service is shutting down gracefully via SIGTERM.
     */
    data class Terminating(
        val pid: Int,
        val pgid: Int
    ) : ServiceProcessState()

    /**
     * Service exited unexpectedly or failed to launch.
     */
    data class Failed(
        val exitCode: Int?,
        val errorMessage: String
    ) : ServiceProcessState()
}
