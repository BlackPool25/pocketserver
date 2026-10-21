package com.pocketserver.proxy.activation

import com.pocketserver.proxy.supervisor.ProcessSupervisor
import com.pocketserver.proxy.supervisor.ServiceProcessState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance non-blocking epoll/NIO socket activation reverse proxy.
 *
 * Listens on an ingress port and transparently proxies traffic to a local backend service.
 * If the backend process is in a frozen (SIGSTOP) state, the proxy immediately unfreezes
 * it via SIGCONT (< 1ms wake latency) and buffers incoming packets while forwarding.
 */
class SocketActivationProxy(
    val bindHost: String = "0.0.0.0",
    val listenPort: Int,
    val targetHost: String = "127.0.0.1",
    val targetPort: Int,
    val supervisor: ProcessSupervisor? = null,
    private val bufferSize: Int = 16384 // 16 KB buffer aligned
) {
    private val isRunning = AtomicBoolean(false)
    private var serverChannel: ServerSocketChannel? = null
    private var selector: Selector? = null
    private var proxyJob: Job? = null

    val activeConnections = AtomicInteger(0)
    val totalConnectionsServed = AtomicLong(0)
    val bytesReceived = AtomicLong(0)
    val bytesSent = AtomicLong(0)

    val actualListenPort: Int
        get() = serverChannel?.socket()?.localPort ?: listenPort

    /**
     * Internal state representation for a bidirectional connection pair.
     */
    private class ChannelPair(
        val clientChannel: SocketChannel,
        var backendChannel: SocketChannel? = null,
        val pendingClientData: MutableList<ByteBuffer> = mutableListOf(),
        var isConnectingToBackend: Boolean = false
    )

    private val sessions = ConcurrentHashMap<SocketChannel, ChannelPair>()

    /**
     * Starts the non-blocking proxy event loop on the specified coroutine scope.
     */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (isRunning.get()) return

        val sel = Selector.open()
        selector = sel

        val ssc = ServerSocketChannel.open()
        ssc.configureBlocking(false)
        ssc.setOption(StandardSocketOptions.SO_REUSEADDR, true)
        ssc.bind(InetSocketAddress(bindHost, listenPort))
        ssc.register(sel, SelectionKey.OP_ACCEPT)
        serverChannel = ssc

        isRunning.set(true)

        proxyJob = scope.launch(Dispatchers.IO) {
            runEventLoop(sel, ssc)
        }
    }

    /**
     * Main non-blocking epoll selector event loop.
     */
    private fun runEventLoop(sel: Selector, ssc: ServerSocketChannel) {
        val buffer = ByteBuffer.allocateDirect(bufferSize)

        while (isRunning.get() && sel.isOpen) {
            try {
                val selectCount = sel.select(200)
                if (selectCount == 0) continue

                val selectedKeys = sel.selectedKeys()
                val iterator = selectedKeys.iterator()

                while (iterator.hasNext()) {
                    val key = iterator.next()
                    iterator.remove()

                    if (!key.isValid) continue

                    when {
                        key.isAcceptable -> {
                            handleAccept(ssc, sel)
                        }
                        key.isConnectable -> {
                            handleConnect(key, sel)
                        }
                        key.isReadable -> {
                            handleRead(key, buffer)
                        }
                    }
                }
            } catch (e: Exception) {
                if (!isRunning.get()) break
            }
        }
    }

    private fun handleAccept(ssc: ServerSocketChannel, sel: Selector) {
        val clientChannel = ssc.accept() ?: return
        clientChannel.configureBlocking(false)
        clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, true)

        activeConnections.incrementAndGet()
        totalConnectionsServed.incrementAndGet()

        // Socket Activation: Check if backend is sleeping, wake immediately
        supervisor?.let { sup ->
            if (sup.state.value is ServiceProcessState.Sleeping) {
                sup.wake()
            }
            sup.recordActivity()
        }

        val pair = ChannelPair(clientChannel = clientChannel)
        sessions[clientChannel] = pair

        // Connect to backend
        try {
            val backendChannel = SocketChannel.open()
            backendChannel.configureBlocking(false)
            backendChannel.setOption(StandardSocketOptions.TCP_NODELAY, true)
            pair.backendChannel = backendChannel
            sessions[backendChannel] = pair

            val targetAddr = InetSocketAddress(targetHost, targetPort)
            val connected = backendChannel.connect(targetAddr)

            if (connected) {
                // Already connected immediately (e.g. localhost fast-path)
                clientChannel.register(sel, SelectionKey.OP_READ, pair)
                backendChannel.register(sel, SelectionKey.OP_READ, pair)
            } else {
                pair.isConnectingToBackend = true
                clientChannel.register(sel, SelectionKey.OP_READ, pair)
                backendChannel.register(sel, SelectionKey.OP_CONNECT, pair)
            }
        } catch (e: IOException) {
            closeSession(pair)
        }
    }

    private fun handleConnect(key: SelectionKey, sel: Selector) {
        val channel = key.channel() as? SocketChannel ?: return
        val pair = key.attachment() as? ChannelPair ?: return

        try {
            if (channel.finishConnect()) {
                pair.isConnectingToBackend = false
                key.interestOps(SelectionKey.OP_READ)

                // Flush any buffered client data sent while waiting for backend connect
                synchronized(pair.pendingClientData) {
                    val it = pair.pendingClientData.iterator()
                    while (it.hasNext()) {
                        val buf = it.next()
                        channel.write(buf)
                        if (!buf.hasRemaining()) {
                            it.remove()
                        }
                    }
                }
            }
        } catch (e: IOException) {
            closeSession(pair)
        }
    }

    private fun handleRead(key: SelectionKey, buffer: ByteBuffer) {
        val channel = key.channel() as? SocketChannel ?: return
        val pair = key.attachment() as? ChannelPair ?: return

        buffer.clear()
        val bytesRead: Int
        try {
            bytesRead = channel.read(buffer)
        } catch (e: IOException) {
            closeSession(pair)
            return
        }

        if (bytesRead < 0) {
            // EOF reached
            closeSession(pair)
            return
        }

        if (bytesRead == 0) return

        buffer.flip()

        supervisor?.recordActivity()

        val isFromClient = (channel == pair.clientChannel)
        val destination = if (isFromClient) pair.backendChannel else pair.clientChannel

        if (isFromClient) {
            bytesReceived.addAndGet(bytesRead.toLong())
        } else {
            bytesSent.addAndGet(bytesRead.toLong())
        }

        if (destination == null || pair.isConnectingToBackend) {
            // Queue data until backend is connected
            val copy = ByteBuffer.allocate(buffer.remaining())
            copy.put(buffer)
            copy.flip()
            synchronized(pair.pendingClientData) {
                pair.pendingClientData.add(copy)
            }
        } else {
            try {
                while (buffer.hasRemaining()) {
                    destination.write(buffer)
                }
            } catch (e: IOException) {
                closeSession(pair)
            }
        }
    }

    private fun closeSession(pair: ChannelPair) {
        try {
            pair.clientChannel.close()
        } catch (ignored: Exception) {}

        try {
            pair.backendChannel?.close()
        } catch (ignored: Exception) {}

        sessions.remove(pair.clientChannel)
        pair.backendChannel?.let { sessions.remove(it) }

        activeConnections.decrementAndGet()
    }

    /**
     * Gracefully stops the proxy server, closes open sockets, and cancels the event loop.
     */
    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        proxyJob?.cancel()
        proxyJob = null

        // Close all active sessions
        for (pair in sessions.values) {
            closeSession(pair)
        }
        sessions.clear()

        try {
            serverChannel?.close()
        } catch (ignored: Exception) {}
        serverChannel = null

        try {
            selector?.close()
        } catch (ignored: Exception) {}
        selector = null
    }
}
