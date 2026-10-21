package com.pocketserver.hub.model

/**
 * Category of a software package hosted in the Curated Package Hub.
 */
enum class RuntimeCategory(val label: String) {
    LANGUAGE_RUNTIME("Language Runtime"),
    DATABASE_ENGINE("Database Engine"),
    CACHE_SERVICE("In-Memory Cache"),
    WEB_INFRASTRUCTURE("Web Infrastructure")
}

/**
 * The 10 major developer language runtimes + 4 data/infrastructure components.
 */
enum class PolyglotRuntime(val identifier: String, val displayName: String) {
    PYTHON("python", "Python 3.12 + uv"),
    JAVASCRIPT("bun", "Bun 1.2 / TypeScript (Node 22 opt)"),
    GO("go", "Go 1.23 ARM64 Toolchain"),
    RUST("rust", "Rustc 1.82 + Cargo"),
    CPP("clang", "Clang 18 + Make/CMake"),
    JAVA_KOTLIN("openjdk", "OpenJDK 21 Headless"),
    PHP("php", "PHP 8.3 CLI + OPcache"),
    RUBY("ruby", "CRuby 3.3 Headless"),
    DOTNET("dotnet", "ASP.NET Core 9 Linux"),
    ZIG("zig", "Zig 0.13 Standalone"),
    REDIS("redis", "Redis 7.2 Standalone"),
    SQLITE("sqlite", "SQLite 3.46 CLI"),
    DUCKDB("duckdb", "DuckDB 1.0 Embedded"),
    CADDY("caddy", "Caddy 2.8 Proxy / Static")
}

/**
 * Package metadata specification in the Curated Hub.
 */
data class HubPackageDescriptor(
    val runtime: PolyglotRuntime,
    val category: RuntimeCategory,
    val packageName: String,
    val engineVersion: String,
    val upstreamSizeBytes: Long,
    val strippedSizeBytes: Long,
    val sha256Checksum: String,
    val archiveFileName: String,
    val primaryBinaryRelPath: String,
    val strippingMethod: String,
    val defaultPort: Int,
    val environmentVariables: Map<String, String> = emptyMap(),
    val helloWorldScript: String = "",
    val helloWorldCommand: List<String> = emptyList()
) {
    val upstreamSizeMb: Double get() = upstreamSizeBytes.toDouble() / (1024.0 * 1024.0)
    val strippedSizeMb: Double get() = strippedSizeBytes.toDouble() / (1024.0 * 1024.0)
    val compressionRatio: Double
        get() = if (upstreamSizeBytes > 0) {
            (1.0 - (strippedSizeBytes.toDouble() / upstreamSizeBytes.toDouble())) * 100.0
        } else 0.0
}
