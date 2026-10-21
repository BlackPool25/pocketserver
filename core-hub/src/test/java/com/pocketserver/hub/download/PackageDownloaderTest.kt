package com.pocketserver.hub.download

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class PackageDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var mockServer: MockWebServer
    private lateinit var downloader: PackageDownloader
    private lateinit var targetFile: File

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        downloader = PackageDownloader()
        targetFile = File(tempFolder.root, "test-pkg.tar.zst")
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `downloadArchive streams content and verifies SHA-256 successfully`() = runTest {
        val payload = "Hello PocketServer Package Stream".toByteArray()
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(payload))
        )

        val progressList = mutableListOf<InstallProgress>()
        val result = downloader.downloadArchive(
            downloadUrl = mockServer.url("/pkg.tar.zst").toString(),
            destinationFile = targetFile,
            expectedSha256 = sha256,
            onProgress = { progressList.add(it) }
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(targetFile.exists()).isTrue()
        assertThat(targetFile.readBytes()).isEqualTo(payload)
        assertThat(progressList.any { it.stage == InstallStage.DOWNLOADING }).isTrue()
        assertThat(progressList.any { it.stage == InstallStage.VERIFYING }).isTrue()
    }

    @Test
    fun `downloadArchive fails on checksum mismatch and deletes temp file`() = runTest {
        val payload = "Corrupted Payload".toByteArray()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(payload))
        )

        val result = downloader.downloadArchive(
            downloadUrl = mockServer.url("/pkg.tar.zst").toString(),
            destinationFile = targetFile,
            expectedSha256 = "0".repeat(64)
        )

        assertThat(result.isFailure).isTrue()
        assertThat(targetFile.exists()).isFalse()
    }
}
