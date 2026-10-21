package com.pocketserver.proxy.database

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

/**
 * Result of a TCP probe against an external database server.
 */
data class ProbeResult(
    val isHealthy: Boolean,
    val latencyMillis: Long,
    val errorMessage: String? = null
)

/**
 * Result of an executed database query with pre-ping wake recovery.
 */
data class PrePingExecutionResult<T>(
    val value: T?,
    val wasRecoveredFromBrokenPipe: Boolean,
    val retryCount: Int,
    val isSuccess: Boolean,
    val error: Throwable? = null
)

/**
 * Pre-Ping Recovery Engine.
 *
 * Implements the runtime verification protocol for DB Pool Wake Recovery:
 * When an application executes database queries after being unfrozen from SIGSTOP,
 * stale sockets are validated. If a BrokenPipeError / ECONNRESET occurs, the dead
 * connection is automatically discarded from the pool, and the query is re-executed
 * on a fresh socket without surfacing an error to the user request.
 */
class PrePingRecoveryEngine(
    private val injector: DatabasePrePingInjector = DatabasePrePingInjector()
) {

    /**
     * Actively tests whether an external database port responds within timeout.
     */
    fun probeDatabaseEndpoint(host: String, port: Int, timeoutMillis: Int = 2000): ProbeResult {
        val startTime = System.currentTimeMillis()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
                val latency = System.currentTimeMillis() - startTime
                ProbeResult(isHealthy = true, latencyMillis = latency)
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            ProbeResult(
                isHealthy = false,
                latencyMillis = latency,
                errorMessage = e.message ?: "Connection failed"
            )
        }
    }

    /**
     * Executes an operation with automatic dead socket detection, discard, and retry.
     */
    fun <T> executeWithPrePingRecovery(
        driver: DatabaseDriverType = DatabaseDriverType.SQLALCHEMY,
        maxRetries: Int = 2,
        onDiscardDeadSocket: (() -> Unit)? = null,
        operation: () -> T
    ): PrePingExecutionResult<T> {
        var attempts = 0
        var recovered = false

        while (attempts <= maxRetries) {
            try {
                val result = operation()
                return PrePingExecutionResult(
                    value = result,
                    wasRecoveredFromBrokenPipe = recovered,
                    retryCount = attempts,
                    isSuccess = true
                )
            } catch (e: Throwable) {
                if (isBrokenPipeException(e) && attempts < maxRetries) {
                    recovered = true
                    attempts++
                    // Signal the pool to discard the dead socket
                    onDiscardDeadSocket?.invoke()
                    continue
                } else {
                    return PrePingExecutionResult(
                        value = null,
                        wasRecoveredFromBrokenPipe = recovered,
                        retryCount = attempts,
                        isSuccess = false,
                        error = e
                    )
                }
            }
        }

        return PrePingExecutionResult(
            value = null,
            wasRecoveredFromBrokenPipe = recovered,
            retryCount = attempts,
            isSuccess = false,
            error = IOException("Pre-ping recovery failed after $attempts retries")
        )
    }

    /**
     * Checks if an exception represents a broken pipe or stale socket reset caused by process sleep.
     */
    fun isBrokenPipeException(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            val message = current.message?.lowercase() ?: ""
            if (current is SocketException && (message.contains("broken pipe") || message.contains("reset"))) {
                return true
            }
            if (message.contains("broken pipe") ||
                message.contains("connection reset") ||
                message.contains("errno 32") ||
                message.contains("epipe") ||
                message.contains("econnreset")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
