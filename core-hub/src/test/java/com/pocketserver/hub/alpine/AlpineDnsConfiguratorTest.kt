package com.pocketserver.hub.alpine

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AlpineDnsConfiguratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var etcDir: File
    private lateinit var configurator: AlpineDnsConfigurator

    @Before
    fun setUp() {
        etcDir = tempFolder.newFolder("etc")
        configurator = AlpineDnsConfigurator(etcDir)
    }

    @Test
    fun testGenerateResolvConfContent() {
        val servers = listOf("1.1.1.1", "8.8.8.8")
        val content = configurator.generateResolvConf(servers)

        assertThat(content).contains("nameserver 1.1.1.1")
        assertThat(content).contains("nameserver 8.8.8.8")
        // Critical requirement: options timeout:1 attempts:2 to prevent 5-second Musl hang
        assertThat(content).contains("options timeout:1 attempts:2")
    }

    @Test
    fun testWriteResolvConfAtomically() {
        val resolvConf = configurator.writeResolvConf(listOf("1.1.1.1", "8.8.8.8"))
        assertThat(resolvConf.exists()).isTrue()
        assertThat(resolvConf.isFile).isTrue()

        val text = resolvConf.readText()
        assertThat(text).contains("nameserver 1.1.1.1")
        assertThat(text).contains("timeout:1 attempts:2")
    }

    @Test
    fun testNodeAndPythonDnsFlags() {
        val nodeFlags = configurator.getNodeDnsArguments()
        assertThat(nodeFlags).contains("--dns-result-order=ipv4first")

        val envVars = configurator.getNetworkEnvironmentVariables()
        assertThat(envVars["RES_OPTIONS"]).contains("timeout:1 attempts:2")
        assertThat(envVars["CURL_RESOLV_OPT"]).isEqualTo("ipv4")
    }
}
