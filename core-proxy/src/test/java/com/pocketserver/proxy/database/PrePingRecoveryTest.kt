package com.pocketserver.proxy.database

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.SocketException

class PrePingRecoveryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var wrapperDir: File
    private lateinit var injector: DatabasePrePingInjector
    private lateinit var recoveryEngine: PrePingRecoveryEngine

    @Before
    fun setUp() {
        wrapperDir = tempFolder.newFolder("wrappers")
        injector = DatabasePrePingInjector()
        recoveryEngine = PrePingRecoveryEngine(injector)
    }

    @Test
    fun `DatabasePrePingInjector generates comprehensive environment across all runtimes`() {
        val baseEnv = mapOf("APP_PORT" to "8000")
        val injectedEnv = injector.generateEnvironment(baseEnv)

        // Python SQLAlchemy
        assertThat(injectedEnv["SQLALCHEMY_POOL_PRE_PING"]).isEqualTo("true")
        assertThat(injectedEnv["SQLALCHEMY_POOL_RECYCLE"]).isEqualTo("30")
        assertThat(injectedEnv["SQLALCHEMY_ENGINE_OPTIONS"]).contains("\"pool_pre_ping\": true")

        // Node.js pg & Prisma
        assertThat(injectedEnv["PGCONNECT_TIMEOUT_MILLIS"]).isEqualTo("5000")
        assertThat(injectedEnv["PGIDLE_TIMEOUT_MILLIS"]).isEqualTo("20000")
        assertThat(injectedEnv["PRISMA_CONNECT_TIMEOUT"]).isEqualTo("5")
        assertThat(injectedEnv["PRISMA_CLIENT_ENGINE_TYPE"]).isEqualTo("binary")

        // Go database/sql
        assertThat(injectedEnv["DB_CONN_MAX_LIFETIME"]).isEqualTo("30s")
        assertThat(injectedEnv["DB_CONN_MAX_IDLE_TIME"]).isEqualTo("15s")

        // Preserves custom user variables
        assertThat(injectedEnv["APP_PORT"]).isEqualTo("8000")
    }

    @Test
    fun `generatePythonPreloadWrapper creates valid sitecustomize script`() {
        val siteCustomize = injector.generatePythonPreloadWrapper(wrapperDir)
        assertThat(siteCustomize.exists()).isTrue()
        val content = siteCustomize.readText()
        assertThat(content).contains("import sqlalchemy")
        assertThat(content).contains("kwargs.setdefault('pool_pre_ping', True)")
        assertThat(content).contains("kwargs.setdefault('pool_recycle', 30)")
    }

    @Test
    fun `generateNodePreloadWrapper creates valid pg pool interceptor script`() {
        val nodeScript = injector.generateNodePreloadWrapper(wrapperDir)
        assertThat(nodeScript.exists()).isTrue()
        val content = nodeScript.readText()
        assertThat(content).contains("require('pg')")
        assertThat(content).contains("config.connectionTimeoutMillis")
        assertThat(content).contains("config.idleTimeoutMillis")
    }

    @Test
    fun `isBrokenPipeException recognizes POSIX and socket broken pipe errors`() {
        assertThat(recoveryEngine.isBrokenPipeException(SocketException("Broken pipe"))).isTrue()
        assertThat(recoveryEngine.isBrokenPipeException(SocketException("Connection reset by peer"))).isTrue()
        assertThat(recoveryEngine.isBrokenPipeException(IOException("BrokenPipeError: [Errno 32] Broken pipe"))).isTrue()
        assertThat(recoveryEngine.isBrokenPipeException(RuntimeException("EPIPE error during socket write"))).isTrue()
        assertThat(recoveryEngine.isBrokenPipeException(IllegalArgumentException("Invalid SQL query"))).isFalse()
    }

    @Test
    fun `executeWithPrePingRecovery discards dead socket after SIGCONT sleep and retries cleanly`() {
        // Acceptance Criteria from plan.md:
        // Connect to remote PostgreSQL, idle 10m (SIGSTOP), wake with HTTP request.
        // Pre-ping discards dead socket; query succeeds without BrokenPipeError.
        var connectionSimState = "STALE_AFTER_SIGCONT_FREEZE"
        var discardCount = 0

        val executionResult = recoveryEngine.executeWithPrePingRecovery(
            driver = DatabaseDriverType.SQLALCHEMY,
            maxRetries = 2,
            onDiscardDeadSocket = {
                discardCount++
                // After detecting dead socket, the pool discards it and reconnects
                connectionSimState = "RECONNECTED_FRESH_SOCKET"
            }
        ) {
            if (connectionSimState == "STALE_AFTER_SIGCONT_FREEZE") {
                // Remote database terminated idle socket during SIGSTOP; write attempt fails
                throw SocketException("Broken pipe (Write failed)")
            } else {
                // Reconnected fresh socket serves query successfully
                "SELECT * FROM users -> 200 OK"
            }
        }

        assertThat(executionResult.isSuccess).isTrue()
        assertThat(executionResult.wasRecoveredFromBrokenPipe).isTrue()
        assertThat(executionResult.retryCount).isEqualTo(1)
        assertThat(discardCount).isEqualTo(1)
        assertThat(executionResult.value).isEqualTo("SELECT * FROM users -> 200 OK")
    }
}
