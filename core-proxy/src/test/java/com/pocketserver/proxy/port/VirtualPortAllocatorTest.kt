package com.pocketserver.proxy.port

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class VirtualPortAllocatorTest {

    private lateinit var allocator: VirtualPortAllocator

    @Before
    fun setUp() {
        allocator = VirtualPortAllocator(minPort = 10000, maxPort = 20000)
    }

    @After
    fun tearDown() {
        allocator.clear()
    }

    @Test
    fun `allocate assigns requested port when available without remapping`() {
        val targetPort = 14500
        val allocation = allocator.allocate(serviceId = "fastapi-service", requestedPort = targetPort)

        assertThat(allocation.serviceId).isEqualTo("fastapi-service")
        assertThat(allocation.virtualPort).isEqualTo(targetPort)
        assertThat(allocation.physicalPort).isEqualTo(targetPort)
        assertThat(allocation.isRemapped).isFalse()
        assertThat(allocation.notice).isNull()
    }

    @Test
    fun `allocate resolves collision by incrementing to next free port and generating notice`() {
        val contestedPort = 14600

        // Simulate an existing process occupying the port on localhost
        val occupier = ServerSocket(contestedPort, 1, InetAddress.getByName("127.0.0.1"))

        try {
            val allocation = allocator.allocate(serviceId = "django-service", requestedPort = contestedPort)

            assertThat(allocation.serviceId).isEqualTo("django-service")
            assertThat(allocation.virtualPort).isEqualTo(contestedPort)
            assertThat(allocation.physicalPort).isEqualTo(contestedPort + 1)
            assertThat(allocation.isRemapped).isTrue()
            assertThat(allocation.notice).isEqualTo("Port 14600 in use; automatically routed to 14601")
        } finally {
            occupier.close()
        }
    }

    @Test
    fun `allocate resolves multiple overlapping reservations across services`() {
        val basePort = 14700

        val alloc1 = allocator.allocate(serviceId = "service-alpha", requestedPort = basePort)
        assertThat(alloc1.physicalPort).isEqualTo(basePort)
        assertThat(alloc1.isRemapped).isFalse()

        // Second service asks for same port
        val alloc2 = allocator.allocate(serviceId = "service-beta", requestedPort = basePort)
        assertThat(alloc2.physicalPort).isEqualTo(basePort + 1)
        assertThat(alloc2.isRemapped).isTrue()

        // Third service asks for same port
        val alloc3 = allocator.allocate(serviceId = "service-gamma", requestedPort = basePort)
        assertThat(alloc3.physicalPort).isEqualTo(basePort + 2)
        assertThat(alloc3.isRemapped).isTrue()
    }

    @Test
    fun `release frees reserved physical port for subsequent allocation`() {
        val port = 14800
        allocator.allocate(serviceId = "transient-service", requestedPort = port)

        val released = allocator.release(serviceId = "transient-service")
        assertThat(released).isNotNull()
        assertThat(released?.physicalPort).isEqualTo(port)

        // Now new service can allocate that port cleanly
        val nextAlloc = allocator.allocate(serviceId = "successor-service", requestedPort = port)
        assertThat(nextAlloc.physicalPort).isEqualTo(port)
        assertThat(nextAlloc.isRemapped).isFalse()
    }
}
