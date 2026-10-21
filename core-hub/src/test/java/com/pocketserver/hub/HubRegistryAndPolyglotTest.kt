package com.pocketserver.hub

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pocketserver.hub.ingestion.HubIngestionEngine
import com.pocketserver.hub.model.PolyglotRuntime
import com.pocketserver.hub.model.RuntimeCategory
import com.pocketserver.hub.polyglot.PolyglotSmokeRunner
import com.pocketserver.hub.registry.HubPackageRegistry
import com.pocketserver.storage.cas.ContentAddressableStore
import com.pocketserver.storage.db.PocketDatabase
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
class HubRegistryAndPolyglotTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var database: PocketDatabase
    private lateinit var casRootDir: File
    private lateinit var casStore: ContentAddressableStore
    private lateinit var ingestionEngine: HubIngestionEngine
    private lateinit var polyglotRunner: PolyglotSmokeRunner

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PocketDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        casRootDir = tempFolder.newFolder("cas_store")
        casStore = ContentAddressableStore(casRootDir, database)
        ingestionEngine = HubIngestionEngine(casStore, database)
        polyglotRunner = PolyglotSmokeRunner()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `HubPackageRegistry defines all 10 language runtimes with stripped sizes and binary paths`() {
        val languages = HubPackageRegistry.getLanguageRuntimes()

        // Must define all 10 major developer language runtimes
        assertThat(languages.size).isEqualTo(10)

        val runtimes = languages.map { it.runtime }.toSet()
        assertThat(runtimes).containsExactly(
            PolyglotRuntime.PYTHON,
            PolyglotRuntime.JAVASCRIPT,
            PolyglotRuntime.GO,
            PolyglotRuntime.RUST,
            PolyglotRuntime.CPP,
            PolyglotRuntime.JAVA_KOTLIN,
            PolyglotRuntime.PHP,
            PolyglotRuntime.RUBY,
            PolyglotRuntime.DOTNET,
            PolyglotRuntime.ZIG
        )

        for (pkg in languages) {
            assertThat(pkg.category).isEqualTo(RuntimeCategory.LANGUAGE_RUNTIME)
            assertThat(pkg.strippedSizeBytes).isLessThan(pkg.upstreamSizeBytes)
            assertThat(pkg.primaryBinaryRelPath).startsWith("bin/")
            assertThat(pkg.defaultPort).isGreaterThan(0)
            assertThat(pkg.compressionRatio).isGreaterThan(50.0) // At least 50% stripped size reduction
        }
    }

    @Test
    fun `HubPackageRegistry defines all 4 infrastructure services Redis SQLite DuckDB Caddy`() {
        val infra = HubPackageRegistry.getInfrastructureServices()
        assertThat(infra.size).isEqualTo(4)

        val runtimes = infra.map { it.runtime }.toSet()
        assertThat(runtimes).containsExactly(
            PolyglotRuntime.REDIS,
            PolyglotRuntime.SQLITE,
            PolyglotRuntime.DUCKDB,
            PolyglotRuntime.CADDY
        )
    }

    @Test
    fun `HubIngestionEngine successfully installs package into CAS and updates Room catalog`() = runBlocking {
        val pythonDesc = HubPackageRegistry.findByRuntime(PolyglotRuntime.PYTHON)!!

        // Create a synthetic 16KB aligned binary archive
        val elfFile = tempFolder.newFile("python3")
        writeSynthetic16KbElf(elfFile)

        val archiveFile = tempFolder.newFile("cpython-test.tar.zst")
        createZstdTarArchive(archiveFile, "bin/python3", elfFile.readBytes())

        val result = ingestionEngine.ingestPackage(
            descriptor = pythonDesc,
            archiveFile = archiveFile,
            skipShaCheck = true
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.canonicalDir).isNotNull()
        assertThat(result.is16KbCompliant).isTrue()

        // Verify Room database entry
        val installed = ingestionEngine.isPackageInstalled(pythonDesc)
        assertThat(installed).isTrue()

        val entity = database.toolchainDao().getAll().find { it.name == pythonDesc.packageName }
        assertThat(entity).isNotNull()
        assertThat(entity?.is16KbAligned).isTrue()
    }

    @Test
    fun `PolyglotSmokeRunner verifies HTTP 200 response and RSS memory below 45MB`() = runBlocking {
        // Spin up a lightweight Python HTTP microservice
        val serverPort = findFreePort()
        val serverDir = tempFolder.newFolder("smoke_server")

        val scriptFile = File(serverDir, "server.py").apply {
            writeText(
                """
                import sys
                from http.server import HTTPServer, BaseHTTPRequestHandler

                class Handler(BaseHTTPRequestHandler):
                    def do_GET(self):
                        self.send_response(200)
                        self.send_header('Content-Type', 'text/plain')
                        self.end_headers()
                        self.wfile.write(b"Hello Polyglot\n")
                    def log_message(self, format, *args): pass

                server = HTTPServer(('127.0.0.1', $serverPort), Handler)
                server.serve_forever()
                """.trimIndent()
            )
        }

        val result = polyglotRunner.runSmokeTest(
            runtime = PolyglotRuntime.PYTHON,
            command = listOf("python3", scriptFile.absolutePath),
            workingDir = serverDir,
            port = serverPort,
            timeoutSeconds = 5,
            warmupMillis = 500L
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.httpStatusCode).isEqualTo(200)
        assertThat(result.responseBody).contains("Hello Polyglot")

        // Acceptance criteria: RSS memory < 45 MB
        assertThat(result.rssMemoryMb).isLessThan(45.0)
        assertThat(result.isRssCompliant).isTrue()
    }

    private fun findFreePort(): Int {
        ServerSocket(0).use { socket ->
            return socket.localPort
        }
    }

    private fun writeSynthetic16KbElf(file: File) {
        val pageSize = 16384L
        RandomAccessFile(file, "rw").use { raf ->
            val buf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(0x7F.toByte())
            buf.put('E'.code.toByte())
            buf.put('L'.code.toByte())
            buf.put('F'.code.toByte())
            buf.put(2) // ELFCLASS64
            buf.put(1) // ELFDATA2LSB
            buf.put(1) // EV_CURRENT
            buf.put(0) // ELFOSABI_NONE
            for (i in 8 until 16) buf.put(0)

            buf.putShort(2) // ET_EXEC
            buf.putShort(183) // EM_AARCH64
            buf.putInt(1) // EV_CURRENT
            buf.putLong(0x10000L) // e_entry
            buf.putLong(64L) // e_phoff = 64
            buf.putLong(0L) // e_shoff
            buf.putInt(0) // e_flags
            buf.putShort(64) // e_ehsize
            buf.putShort(56) // e_phentsize
            buf.putShort(1) // e_phnum
            buf.putShort(0)
            buf.putShort(0)
            buf.putShort(0)

            // Program Header (PT_LOAD)
            buf.putInt(1) // PT_LOAD
            buf.putInt(5) // PF_R | PF_X
            buf.putLong(0L) // p_offset
            buf.putLong(0x10000L) // p_vaddr
            buf.putLong(0x10000L) // p_paddr
            buf.putLong(1024L) // p_filesz
            buf.putLong(1024L) // p_memsz
            buf.putLong(pageSize) // p_align = 16384

            raf.write(buf.array())
        }
    }

    private fun createZstdTarArchive(outFile: File, internalPath: String, content: ByteArray) {
        val tarBytes = ByteArrayOutputStream().use { baos ->
            TarArchiveOutputStream(baos).use { tos ->
                val entry = TarArchiveEntry(internalPath).apply {
                    size = content.size.toLong()
                    mode = 493 // 0755
                }
                tos.putArchiveEntry(entry)
                tos.write(content)
                tos.closeArchiveEntry()
            }
            baos.toByteArray()
        }

        // Compress with Zstd
        val compressed = com.github.luben.zstd.Zstd.compress(tarBytes)
        FileOutputStream(outFile).use { it.write(compressed) }
    }
}
