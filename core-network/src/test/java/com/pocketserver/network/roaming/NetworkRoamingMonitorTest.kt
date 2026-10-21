package com.pocketserver.network.roaming

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NetworkRoamingMonitorTest {

    @Test
    fun `resolveCurrentLocalIp returns valid non-empty IP address`() {
        val monitor = NetworkRoamingMonitor()
        val ip = monitor.resolveCurrentLocalIp()

        assertThat(ip).isNotEmpty()
        assertThat(ip.split(".").size).isEqualTo(4)
    }

    @Test
    fun `updateIpAddress notifies registered listener of IP migration`() {
        val monitor = NetworkRoamingMonitor()

        var notifiedOldIp: String? = null
        var notifiedNewIp: String? = null
        var notifiedState: NetworkState? = null

        monitor.addListener { oldIp, newIp, state ->
            notifiedOldIp = oldIp
            notifiedNewIp = newIp
            notifiedState = state
        }

        // Simulate Wi-Fi to Cellular IP handover
        val initialIp = monitor.networkState.value.ipAddress
        val handoverIp = "10.155.42.88"

        monitor.updateIpAddress(
            newIp = handoverIp,
            interfaceType = NetworkInterfaceType.CELLULAR,
            isMetered = true
        )

        assertThat(notifiedOldIp).isEqualTo(initialIp)
        assertThat(notifiedNewIp).isEqualTo(handoverIp)
        assertThat(notifiedState?.interfaceType).isEqualTo(NetworkInterfaceType.CELLULAR)
        assertThat(notifiedState?.isMetered).isTrue()
        assertThat(monitor.networkState.value.ipAddress).isEqualTo(handoverIp)
    }
}
