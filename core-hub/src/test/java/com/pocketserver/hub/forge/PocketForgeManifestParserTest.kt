package com.pocketserver.hub.forge

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PocketForgeManifestParserTest {

    private lateinit var parser: PocketForgeManifestParser

    @Before
    fun setUp() {
        parser = PocketForgeManifestParser()
    }

    @Test
    fun testParseValidManifest() {
        val validJson = """
            {
              "repository": "Pocket-AI-Tools",
              "maintainer": "PocketServer Community",
              "version": 1,
              "description": "Pre-compiled ARM64 machine learning and audio models",
              "packages": [
                {
                  "id": "whisper-cpp",
                  "name": "Whisper.cpp Speech-to-Text",
                  "version": "1.7.1",
                  "category": "AI / Audio",
                  "description": "High performance inference of OpenAI Whisper in C/C++",
                  "size_compressed": 14200000,
                  "installed_size": 38000000,
                  "download_url": "https://hub.pocketforge.org/whisper-cpp-1.7.1-arm64.tar.zst",
                  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                  "primary_binary": "bin/whisper",
                  "dependencies": ["libopenblas"],
                  "default_port": 8080,
                  "environment": {
                    "WHISPER_THREADS": "4"
                  }
                }
              ]
            }
        """.trimIndent()

        val manifest = parser.parse(validJson)
        assertThat(manifest.repository).isEqualTo("Pocket-AI-Tools")
        assertThat(manifest.maintainer).isEqualTo("PocketServer Community")
        assertThat(manifest.packages.size).isEqualTo(1)

        val pkg = manifest.packages[0]
        assertThat(pkg.id).isEqualTo("whisper-cpp")
        assertThat(pkg.name).isEqualTo("Whisper.cpp Speech-to-Text")
        assertThat(pkg.version).isEqualTo("1.7.1")
        assertThat(pkg.category).isEqualTo("AI / Audio")
        assertThat(pkg.sizeCompressedBytes).isEqualTo(14200000L)
        assertThat(pkg.sha256Checksum).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertThat(pkg.primaryBinaryRelPath).isEqualTo("bin/whisper")
        assertThat(pkg.dependencies).containsExactly("libopenblas")
        assertThat(pkg.environmentVariables["WHISPER_THREADS"]).isEqualTo("4")
    }

    @Test
    fun testRejectPathTraversalPackageId() {
        val maliciousJson = """
            {
              "repository": "Evil-Repo",
              "packages": [
                {
                  "id": "../../etc/shadow",
                  "version": "1.0.0",
                  "download_url": "https://evil.com/payload.tar.zst",
                  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                }
              ]
            }
        """.trimIndent()

        val exception = assertThrows(IllegalArgumentException::class.java) {
            parser.parse(maliciousJson)
        }
        assertThat(exception.message).contains("contains illegal characters or path traversal elements")
    }

    @Test
    fun testRejectAbsolutePathBinary() {
        val maliciousJson = """
            {
              "repository": "Evil-Repo",
              "packages": [
                {
                  "id": "bad-pkg",
                  "version": "1.0.0",
                  "download_url": "https://evil.com/payload.tar.zst",
                  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                  "primary_binary": "/bin/sh"
                }
              ]
            }
        """.trimIndent()

        val exception = assertThrows(IllegalArgumentException::class.java) {
            parser.parse(maliciousJson)
        }
        assertThat(exception.message).contains("cannot be absolute or contain '..'")
    }

    @Test
    fun testRejectInvalidSha256Checksum() {
        val badChecksumJson = """
            {
              "repository": "Bad-Checksum-Repo",
              "packages": [
                {
                  "id": "test-pkg",
                  "version": "1.0.0",
                  "download_url": "https://example.com/payload.tar.zst",
                  "sha256": "not-a-valid-sha256-hex"
                }
              ]
            }
        """.trimIndent()

        val exception = assertThrows(IllegalArgumentException::class.java) {
            parser.parse(badChecksumJson)
        }
        assertThat(exception.message).contains("has invalid SHA-256 checksum")
    }
}
