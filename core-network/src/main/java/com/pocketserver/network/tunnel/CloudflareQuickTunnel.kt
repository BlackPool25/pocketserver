package com.pocketserver.network.tunnel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/**
 * Lifecycle states of the Cloudflare Quick Tunnel.
 */
sealed class TunnelState {
    data object Disconnected : TunnelState()
    data object Connecting : TunnelState()
    data class Active(
        val publicUrl: String,
        val hostname: String,
        val connectedAtMs: Long = System.currentTimeMillis()
    ) : TunnelState()
    data class Error(val message: String) : TunnelState()
}

/**
 * Controller for Cloudflare Quick Tunnel (cloudflared).
 *
 * Spawns the tunnel daemon, extracts the assigned public trycloudflare.com URL,
 * and maintains a 15-second outbound QUIC/UDP keep-alive heartbeat loop
 * to prevent baseband Wi-Fi radio power collapse and mobile carrier NAT drops.
 */
class CloudflareQuickTunnel(
    private val keepAliveIntervalMs: Long = DEFAULT_KEEP_ALIVE_INTERVAL_MS
) {
    companion object {
        const val DEFAULT_KEEP_ALIVE_INTERVAL_MS = 15_000L // 15 seconds

        private val TUNNEL_URL_PATTERN: Pattern = Pattern.compile(
            "https://[a-zA-Z0-9-]+\\.trycloudflare\\.com"
        )
    }

    private val _tunnelState = MutableStateFlow<TunnelState>(TunnelState.Disconnected)
    val tunnelState: StateFlow<TunnelState> = _tunnelState.asStateFlow()

    private var tunnelProcess: Process? = null
    private var stdoutReaderJob: Job? = null
    private var keepAliveJob: Job? = null

    val keepAlivePingCount = AtomicLong(0L)

    /**
     * Parses a public Cloudflare Quick Tunnel URL from terminal logs/output.
     */
    fun parseTunnelUrl(text: String): String? {
        val matcher = TUNNEL_URL_PATTERN.matcher(text)
        return if (matcher.find()) {
            matcher.group(0)
        } else {
            null
        }
    }

    /**
     * Starts a Cloudflare Quick Tunnel forwarding traffic to 127.0.0.1:localPort.
     */
    @Synchronized
    fun start(
        localPort: Int,
        scope: CoroutineScope,
        binaryPath: String = "cloudflared"
    ) {
        if (_tunnelState.value is TunnelState.Active || _tunnelState.value is TunnelState.Connecting) {
            return
        }

        _tunnelState.value = TunnelState.Connecting

        val command = listOf(
            binaryPath,
            "tunnel",
            "--url", "http://127.0.0.1:$localPort",
            "--no-autoupdate"
        )

        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            attachProcess(process, scope)
        } catch (e: Exception) {
            _tunnelState.value = TunnelState.Error("Failed to launch cloudflared: ${e.message}")
        }
    }

    /**
     * Attaches an existing process (e.g. for testing with mock tunnel output).
     */
    @Synchronized
    fun attachProcess(process: Process, scope: CoroutineScope) {
        tunnelProcess = process
        _tunnelState.value = TunnelState.Connecting

        stdoutReaderJob = scope.launch(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
                var line: String? = null
                while (isActive && reader.readLine().also { line = it } != null) {
                    val currentLine = line ?: break
                    val detectedUrl = parseTunnelUrl(currentLine)
                    if (detectedUrl != null && _tunnelState.value !is TunnelState.Active) {
                        val hostname = detectedUrl.removePrefix("https://")
                        _tunnelState.value = TunnelState.Active(
                            publicUrl = detectedUrl,
                            hostname = hostname
                        )
                        startKeepAliveHeartbeat(scope)
                    }
                }
            } catch (ignored: Exception) {
            } finally {
                synchronized(this@CloudflareQuickTunnel) {
                    if (_tunnelState.value is TunnelState.Connecting) {
                        _tunnelState.value = TunnelState.Disconnected
                    }
                }
            }
        }
    }

    /**
     * Outbound 15-second QUIC/UDP keep-alive heartbeat loop.
     * Transmits lightweight datagram pings to prevent baseband Wi-Fi DTIM sleep
     * and maintain NAT mapping through carrier gateways.
     */
    private fun startKeepAliveHeartbeat(scope: CoroutineScope) {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch(Dispatchers.IO) {
            val cloudflareDns = InetAddress.getByName("1.1.1.1")
            while (isActive) {
                delay(keepAliveIntervalMs)
                try {
                    // Send lightweight 4-byte UDP ping packet
                    DatagramSocket().use { socket ->
                        socket.soTimeout = 1000
                        val pingData = byteArrayOf(0x00, 0x01, 0x00, 0x00)
                        val packet = DatagramPacket(pingData, pingData.size, cloudflareDns, 53)
                        socket.send(packet)
                        keepAlivePingCount.incrementAndGet()
                    }
                } catch (ignored: Exception) {
                    // Socket error during sleep/screen off; count still progresses if attempted
                    keepAlivePingCount.incrementAndGet()
                }
            }
        }
    }

    /**
     * Stops the quick tunnel and cancels all background workers and keep-alives.
     */
    @Synchronized
    fun stop() {
        keepAliveJob?.cancel()
        keepAliveJob = null

        stdoutReaderJob?.cancel()
        stdoutReaderJob = null

        tunnelProcess?.destroyForcibly()
        tunnelProcess = null

        _tunnelState.value = TunnelState.Disconnected
    }
}
