package com.pocketserver.proxy.activation

import com.google.common.truth.Truth.assertThat
import com.pocketserver.proxy.supervisor.ProcessSupervisor
import com.pocketserver.proxy.supervisor.ServiceProcessState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class SocketActivationProxyTest {

    private lateinit var testScope: CoroutineScope
    private var mockBackendServer: ServerSocket? = null
    private var mockBackendJob: Job? = null
    private var dummyProcess: Process? = null
    private var supervisor: ProcessSupervisor? = null
    private var proxy: SocketActivationProxy? = null

    @Before
    fun setUp() {
        testScope = CoroutineScope(Dispatchers.IO)
    }

    @After
    fun tearDown() {
        proxy?.stop()
        mockBackendJob?.cancel()
        try {
            mockBackendServer?.close()
        } catch (ignored: Exception) {}
        supervisor?.terminate()
        dummyProcess?.destroyForcibly()
        testScope.cancel()
    }

    @Test
    fun `proxy automatically unfreezes sleeping supervisor and tunnels HTTP traffic`() {
        // 1. Start a simple mock HTTP backend on localhost:0 (ephemeral port)
        val backendServer = ServerSocket(0)
        mockBackendServer = backendServer
        val backendPort = backendServer.localPort

        mockBackendJob = testScope.launch {
            while (!backendServer.isClosed) {
                try {
                    val socket = backendServer.accept()
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    val out = socket.getOutputStream()

                    // Read request line
                    val line = reader.readLine()
                    if (line != null) {
                        val responseBody = "{\"status\":\"ok\",\"service\":\"mock-backend\"}"
                        val httpResponse = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/json\r\n" +
                                "Content-Length: ${responseBody.length}\r\n" +
                                "\r\n" +
                                responseBody
                        out.write(httpResponse.toByteArray(StandardCharsets.UTF_8))
                        out.flush()
                    }
                    socket.close()
                } catch (ignored: Exception) {
                    break
                }
            }
        }

        // 2. Start a dummy process and attach to supervisor
        val process = ProcessBuilder("sleep", "30").start()
        dummyProcess = process
        val pid = extractPid(process).toInt()

        val sup = ProcessSupervisor(serviceId = "mock-srv")
        supervisor = sup
        sup.attach(pid = pid, pgid = pid)

        // Freeze service via SIGSTOP
        val frozen = sup.freeze()
        assertThat(frozen).isTrue()
        assertThat(sup.state.value).isInstanceOf(ServiceProcessState.Sleeping::class.java)

        // 3. Start SocketActivationProxy targeting the backend port
        val actProxy = SocketActivationProxy(
            bindHost = "127.0.0.1",
            listenPort = 0, // ephemeral port
            targetHost = "127.0.0.1",
            targetPort = backendPort,
            supervisor = sup
        )
        proxy = actProxy
        actProxy.start(testScope)

        val proxyPort = actProxy.actualListenPort
        assertThat(proxyPort).isGreaterThan(0)

        // 4. Connect a client to the proxy port
        val clientSocket = Socket("127.0.0.1", proxyPort)
        val out: OutputStream = clientSocket.getOutputStream()
        val request = "GET /api/v1/health HTTP/1.1\r\nHost: localhost\r\n\r\n"
        out.write(request.toByteArray(StandardCharsets.UTF_8))
        out.flush()

        val inReader = BufferedReader(InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8))
        val statusLine = inReader.readLine()
        assertThat(statusLine).isEqualTo("HTTP/1.1 200 OK")

        var foundBody = false
        var line: String?
        while (inReader.readLine().also { line = it } != null) {
            if (line!!.contains("mock-backend")) {
                foundBody = true
                break
            }
        }
        clientSocket.close()

        assertThat(foundBody).isTrue()

        // 5. Verify the supervisor was woken up from Sleeping to Running by the proxy
        assertThat(sup.state.value).isInstanceOf(ServiceProcessState.Running::class.java)
        assertThat(sup.wakeCount).isGreaterThan(0L)
        assertThat(actProxy.totalConnectionsServed.get()).isGreaterThan(0L)
        assertThat(actProxy.bytesReceived.get()).isGreaterThan(0L)
        assertThat(actProxy.bytesSent.get()).isGreaterThan(0L)
    }

    private fun extractPid(process: Process): Long {
        return try {
            val method = Process::class.java.getMethod("pid")
            (method.invoke(process) as Number).toLong()
        } catch (e: Exception) {
            val toHandle = Process::class.java.getMethod("toHandle")
            val handle = toHandle.invoke(process)
            val pidMethod = handle.javaClass.getMethod("pid")
            (pidMethod.invoke(handle) as Number).toLong()
        }
    }
}
