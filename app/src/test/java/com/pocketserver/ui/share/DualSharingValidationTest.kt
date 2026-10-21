package com.pocketserver.ui.share

import android.app.Activity
import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DualSharingValidationTest {

    private lateinit var activity: Activity
    private lateinit var controller: QrSharingController
    private var serverSocket: ServerSocket? = null
    private var serverPort: Int = 0
    @Volatile
    private var serverRunning: Boolean = true

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()

        serverSocket = ServerSocket(0)
        serverPort = serverSocket!!.localPort
        serverRunning = true

        thread(isDaemon = true) {
            while (serverRunning) {
                try {
                    val client: Socket = serverSocket?.accept() ?: break
                    thread(isDaemon = true) {
                        try {
                            val reader = client.getInputStream().bufferedReader()
                            val line = reader.readLine()
                            val output = client.getOutputStream()
                            val body = "PocketServer Live"
                            val response = "HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: text/plain\r\n" +
                                    "Content-Length: ${body.length}\r\n" +
                                    "Connection: close\r\n\r\n$body"
                            output.write(response.toByteArray(Charsets.UTF_8))
                            output.flush()
                            client.close()
                        } catch (ignored: Exception) {
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }

        controller = QrSharingController(
            serviceName = "fullstack-app-backend",
            localWifiUrl = "http://127.0.0.1:$serverPort",
            mDnsUrl = "http://backend.local:$serverPort",
            publicTunnelUrl = "http://127.0.0.1:$serverPort/tunnel"
        )
    }

    @After
    fun tearDown() {
        serverRunning = false
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {
        }
    }

    @Test
    fun testScreenBrightnessBoostAndRestoration() {
        val window = activity.window
        val initialBrightness = window.attributes.screenBrightness

        // When QR modal is presented
        controller.onModalShown(window)

        assertThat(window.attributes.screenBrightness).isEqualTo(1.0f)
        val hasKeepScreenOn = (window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        assertThat(hasKeepScreenOn).isTrue()
        assertThat(controller.state.value.isBrightnessBoosted).isTrue()

        // When QR modal is dismissed
        controller.onModalDismissed(window)

        assertThat(window.attributes.screenBrightness).isEqualTo(initialBrightness)
        assertThat(controller.state.value.isBrightnessBoosted).isFalse()
    }

    @Test
    fun testTabSwitchingLocalAndPublic() {
        assertThat(controller.state.value.activeTab).isEqualTo(SharingTab.LOCAL_WIFI)
        assertThat(controller.state.value.currentUrl).isEqualTo("http://127.0.0.1:$serverPort")

        controller.selectTab(SharingTab.INTERNET_PUBLIC)
        assertThat(controller.state.value.activeTab).isEqualTo(SharingTab.INTERNET_PUBLIC)
        assertThat(controller.state.value.currentUrl).isEqualTo("http://127.0.0.1:$serverPort/tunnel")

        controller.selectTab(SharingTab.LOCAL_WIFI)
        assertThat(controller.state.value.activeTab).isEqualTo(SharingTab.LOCAL_WIFI)
    }

    @Test
    fun testQrCodeMatrixGeneration() {
        val matrix = controller.generateActiveQrMatrix(256)
        assertThat(matrix.width).isEqualTo(256)
        assertThat(matrix.height).isEqualTo(256)

        // Switch to public tunnel and generate
        controller.selectTab(SharingTab.INTERNET_PUBLIC)
        val publicMatrix = controller.generateActiveQrMatrix(256)
        assertThat(publicMatrix.width).isEqualTo(256)
        assertThat(publicMatrix.height).isEqualTo(256)
    }

    @Test
    fun testDualSharingEndpointValidationReturnsHttp200() {
        // Acceptance Protocol: Both endpoints respond with HTTP 200
        val localStatus = controller.validateEndpoint("http://127.0.0.1:$serverPort/")
        assertThat(localStatus).isEqualTo(200)

        val tunnelStatus = controller.validateEndpoint("http://127.0.0.1:$serverPort/tunnel")
        assertThat(tunnelStatus).isEqualTo(200)
    }
}
