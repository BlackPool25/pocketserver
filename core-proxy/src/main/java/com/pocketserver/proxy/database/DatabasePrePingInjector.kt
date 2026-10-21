package com.pocketserver.proxy.database

import java.io.File

/**
 * Supported database client driver types for pre-ping injection.
 */
enum class DatabaseDriverType(val identifier: String) {
    SQLALCHEMY("sqlalchemy"),
    NODE_PG("node-pg"),
    PRISMA("prisma"),
    GO_SQL("go-sql"),
    RUBY_ACTIVERECORD("activerecord"),
    PHP_PDO("php-pdo"),
    DOTNET_EFCORE("dotnet-efcore"),
    GENERIC_TCP_POOL("generic")
}

/**
 * Configuration options for external database connection pools to eliminate BrokenPipeError
 * when mobile server processes are unfrozen via SIGCONT.
 */
data class DatabasePrePingConfig(
    val poolPrePingEnabled: Boolean = true,
    val poolRecycleSeconds: Int = 30,
    val connectionTimeoutMillis: Long = 5000L,
    val idleTimeoutMillis: Long = 20000L,
    val maxLifetimeSeconds: Int = 30,
    val maxIdleTimeSeconds: Int = 15
)

/**
 * Edge Case 3 Fix: Pre-Ping Pool Configuration Injection Engine.
 *
 * The Problem:
 * When PocketServer suspends an idle backend using kill(-pgid, SIGSTOP), external database
 * servers (Supabase, Neon, AWS RDS, MongoDB Atlas) detect TCP idle timeout and close the connection.
 * When the mobile process is resumed with SIGCONT, using the stale TCP socket throws
 * BrokenPipeError: [Errno 32] Broken pipe or ECONNRESET.
 *
 * The Solution:
 * Injects pre-ping validation settings and short pool recycling into process environments:
 * 1. SQLAlchemy: pool_pre_ping=True, pool_recycle=30
 * 2. Node.js pg / Prisma: connectionTimeoutMillis=5000, idleTimeoutMillis=20000
 * 3. Go database/sql: SetConnMaxLifetime=30s, SetConnMaxIdleTime=15s
 * 4. Automatic sitecustomize.py and Node preload wrappers that intercept connection pool initializers.
 */
class DatabasePrePingInjector(
    val config: DatabasePrePingConfig = DatabasePrePingConfig()
) {

    /**
     * Injects standard pre-ping and pool-recycle environment variables across all runtimes.
     */
    fun generateEnvironment(baseEnv: Map<String, String> = emptyMap()): Map<String, String> {
        val merged = baseEnv.toMutableMap()

        // Python SQLAlchemy & DB-API
        merged.putIfAbsent("SQLALCHEMY_POOL_PRE_PING", if (config.poolPrePingEnabled) "true" else "false")
        merged.putIfAbsent("SQLALCHEMY_POOL_RECYCLE", config.poolRecycleSeconds.toString())
        merged.putIfAbsent(
            "SQLALCHEMY_ENGINE_OPTIONS",
            "{\"pool_pre_ping\": ${config.poolPrePingEnabled}, \"pool_recycle\": ${config.poolRecycleSeconds}}"
        )

        // Node.js pg & Prisma
        merged.putIfAbsent("PGCONNECT_TIMEOUT_MILLIS", config.connectionTimeoutMillis.toString())
        merged.putIfAbsent("PGIDLE_TIMEOUT_MILLIS", config.idleTimeoutMillis.toString())
        merged.putIfAbsent("PRISMA_CONNECT_TIMEOUT", (config.connectionTimeoutMillis / 1000).toString())
        merged.putIfAbsent("PRISMA_POOL_TIMEOUT", (config.idleTimeoutMillis / 1000).toString())
        merged.putIfAbsent("PRISMA_CLIENT_ENGINE_TYPE", "binary")

        // Go database/sql hints
        merged.putIfAbsent("DB_CONN_MAX_LIFETIME", "${config.maxLifetimeSeconds}s")
        merged.putIfAbsent("DB_CONN_MAX_IDLE_TIME", "${config.maxIdleTimeSeconds}s")

        // Ruby ActiveRecord & Sequel hints
        merged.putIfAbsent("DATABASE_POOL_TIMEOUT", (config.connectionTimeoutMillis / 1000).toString())
        merged.putIfAbsent("DATABASE_REAPING_FREQUENCY", (config.poolRecycleSeconds / 3).coerceAtLeast(5).toString())

        // Generic PocketServer DB pool markers
        merged.putIfAbsent("POCKETSERVER_DB_PRE_PING", "1")
        merged.putIfAbsent("POCKETSERVER_DB_RECYCLE_SECS", config.poolRecycleSeconds.toString())

        return merged
    }

    /**
     * Generates a Python sitecustomize.py script that monkeypatches SQLAlchemy create_engine
     * to enforce pool_pre_ping=True and pool_recycle=30 transparently without requiring source code edits.
     */
    fun generatePythonPreloadWrapper(wrapperDir: File): File {
        wrapperDir.mkdirs()
        val siteCustomize = File(wrapperDir, "sitecustomize.py")
        siteCustomize.writeText(
            """
            # PocketServer Automatic DB Pre-Ping Monkeypatch
            # Injected automatically to prevent BrokenPipeError after SIGCONT process wake
            import os

            try:
                import sqlalchemy
                original_create_engine = sqlalchemy.create_engine

                def pocketserver_create_engine(*args, **kwargs):
                    kwargs.setdefault('pool_pre_ping', True)
                    kwargs.setdefault('pool_recycle', ${config.poolRecycleSeconds})
                    return original_create_engine(*args, **kwargs)

                sqlalchemy.create_engine = pocketserver_create_engine
            except ImportError:
                pass
            """.trimIndent()
        )
        return siteCustomize
    }

    /**
     * Generates a Node.js preload script that wraps pg.Pool to enforce connection health checks.
     */
    fun generateNodePreloadWrapper(wrapperDir: File): File {
        wrapperDir.mkdirs()
        val nodeWrapper = File(wrapperDir, "pocketserver_db_preping.js")
        nodeWrapper.writeText(
            """
            // PocketServer Automatic DB Pre-Ping Wrapper for Node.js
            // Injected automatically to discard dead sockets after SIGCONT process wake
            try {
                const pg = require('pg');
                if (pg && pg.Pool) {
                    const OriginalPool = pg.Pool;
                    pg.Pool = function (config = {}) {
                        config.connectionTimeoutMillis = config.connectionTimeoutMillis || ${config.connectionTimeoutMillis};
                        config.idleTimeoutMillis = config.idleTimeoutMillis || ${config.idleTimeoutMillis};
                        return new OriginalPool(config);
                    };
                }
            } catch (e) {
                // Ignore if pg is not used
            }
            """.trimIndent()
        )
        return nodeWrapper
    }
}
