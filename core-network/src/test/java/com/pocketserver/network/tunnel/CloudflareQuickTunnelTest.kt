package com.pocketserver.network.tunnel

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

class CloudflareQuickTunnelTest {

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `parseTunnelUrl accurately extracts trycloudflare com URL from terminal logs`() {
        val tunnel = CloudflareQuickTunnel()
        val sampleLog = """
            2026-10-21T14:30:10Z INF +--------------------------------------------------------------------------------------------+
            2026-10-21T14:30:10Z INF |  Your quick Tunnel has been created! Visit it at (it may take some time to be reachable):  |
            2026-10-21T14:30:10Z INF |  https://bold-river-428a.trycloudflare.com                                                |
            2026-10-21T14:30:10Z INF +--------------------------------------------------------------------------------------------+
        """.trimIndent()

        val parsedUrl = tunnel.parseTunnelUrl(sampleLog)
        assertThat(parsedUrl).isEqualTo("https://bold-river-428a.trycloudflare.com")
    }

    @Test
    fun `attachProcess detects public URL and transitions state to Active`() {
        val tunnel = CloudflareQuickTunnel(keepAliveIntervalMs = 200L)

        val logOutput = """
            INF Starting tunnel
            INF Connection established
            INF https://fastapi-demo-1234.trycloudflare.com
        """.trimIndent()

        val mockProcess = object : Process() {
            override fun getOutputStream() = throw UnsupportedOperationException()
            override fun getInputStream(): InputStream = ByteArrayInputStream(logOutput.toByteArray(StandardCharsets.UTF_8))
            override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun waitFor(): Int = 0
            override fun exitValue(): Int = 0
            override fun destroy() {}
        }

        tunnel.attachProcess(mockProcess, scope)

        // Wait briefly on real thread for stdout reader to parse output
        var attempts = 0
        while (tunnel.tunnelState.value !is TunnelState.Active && attempts < 40) {
            Thread.sleep(50L)
            attempts++
        }

        val activeState = tunnel.tunnelState.value as TunnelState.Active
        assertThat(activeState.publicUrl).isEqualTo("https://fastapi-demo-1234.trycloudflare.com")
        assertThat(activeState.hostname).isEqualTo("fastapi-demo-1234.trycloudflare.com")

        // Allow keep-alive heartbeat to trigger
        Thread.sleep(300L)
        assertThat(tunnel.keepAlivePingCount.get()).isGreaterThan(0L)

        tunnel.stop()
        assertThat(tunnel.tunnelState.value).isEqualTo(TunnelState.Disconnected)
    }
}
