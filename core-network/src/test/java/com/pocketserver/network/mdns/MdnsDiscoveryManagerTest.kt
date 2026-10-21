package com.pocketserver.network.mdns

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MdnsDiscoveryManagerTest {

    @Test
    fun `registerService records active registration entry`() {
        val manager = MdnsDiscoveryManager()

        var registeredName: String? = null
        manager.registerService(
            serviceName = "fastapi-backend",
            port = 8000,
            attributes = mapOf("version" to "1.0", "framework" to "fastapi")
        ) { name ->
            registeredName = name
        }

        assertThat(registeredName).isEqualTo("fastapi-backend")
        assertThat(manager.isServiceRegistered("fastapi-backend")).isTrue()
        assertThat(manager.getRegisteredName("fastapi-backend")).isEqualTo("fastapi-backend")
    }

    @Test
    fun `unregisterService halts active registration`() {
        val manager = MdnsDiscoveryManager()
        manager.registerService("node-api", 3000)

        assertThat(manager.isServiceRegistered("node-api")).isTrue()

        manager.unregisterService("node-api")
        assertThat(manager.isServiceRegistered("node-api")).isFalse()
    }

    @Test
    fun `reRegisterAll re-publishes services with new IP configuration`() {
        val manager = MdnsDiscoveryManager()
        manager.registerService("go-microservice", 8080)
        manager.registerService("rust-proxy", 9000)

        assertThat(manager.isServiceRegistered("go-microservice")).isTrue()
        assertThat(manager.isServiceRegistered("rust-proxy")).isTrue()

        // Simulate network IP migration
        manager.reRegisterAll("10.0.0.55")

        assertThat(manager.isServiceRegistered("go-microservice")).isTrue()
        assertThat(manager.isServiceRegistered("rust-proxy")).isTrue()

        manager.unregisterAll()
        assertThat(manager.isServiceRegistered("go-microservice")).isFalse()
        assertThat(manager.isServiceRegistered("rust-proxy")).isFalse()
    }
}
