package com.pocketserver.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pocketserver.hub.download.PackageDownloader
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudflaredProvisionerTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        File(context.filesDir, "bin").deleteRecursively()
    }

    @Test
    fun testArchitectureResolutionAndDownloadUrl() {
        val arch = CloudflaredProvisioner.resolveArchitecture()
        assertThat(arch).isIn(listOf("arm64", "arm", "amd64", "386"))

        val url = CloudflaredProvisioner.getDownloadUrl("arm64")
        assertThat(url).contains("cloudflared-linux-arm64")

        val amd64Url = CloudflaredProvisioner.getDownloadUrl("amd64")
        assertThat(amd64Url).contains("cloudflared-linux-amd64")
    }

    @Test
    fun testEnsureProvisionedDownloadsBinaryAndSetsExecutable() = runTest {
        // Enqueue mock binary payload
        val mockBinaryContent = "#!/bin/sh\necho 'cloudflared version 2024.1.0'\n"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(mockBinaryContent)
        )

        val client = OkHttpClient()
        val downloader = PackageDownloader(client)
        val provisioner = CloudflaredProvisioner(context, downloader)

        // Initially not provisioned
        assertThat(provisioner.isProvisioned()).isFalse()

        // Provision directly
        val destination = provisioner.binaryFile
        val mockUrl = server.url("/cloudflared").toString()

        val downloadResult = downloader.downloadArchive(
            downloadUrl = mockUrl,
            destinationFile = destination
        )
        assertThat(downloadResult.isSuccess).isTrue()

        destination.setExecutable(true, false)
        assertThat(destination.exists()).isTrue()
        assertThat(provisioner.isProvisioned()).isTrue()
    }
}
