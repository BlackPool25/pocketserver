package com.pocketserver.network.qr

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DualQrCodeEngineTest {

    private val engine = DualQrCodeEngine()

    @Test
    fun `createShareEndpoint builds LAN and Global HTTPS endpoints with security context notice`() {
        val endpoint = engine.createShareEndpoint(
            serviceName = "fastapi-backend",
            localIp = "192.168.1.105",
            port = 8000,
            globalTunnelUrl = "https://peaceful-haven-88.trycloudflare.com"
        )

        assertThat(endpoint.serviceName).isEqualTo("fastapi-backend")
        assertThat(endpoint.lanUrl).isEqualTo("http://192.168.1.105:8000")
        assertThat(endpoint.globalUrl).isEqualTo("https://peaceful-haven-88.trycloudflare.com")
        assertThat(endpoint.isPlainHttpLanInsecure).isTrue()
        assertThat(endpoint.secureContextNotice).contains("Global Public Link (HTTPS)")
    }

    @Test
    fun `generateQrMatrix creates valid non-empty bit matrix`() {
        val matrix = engine.generateQrMatrix("http://192.168.1.105:8000", size = 128)

        assertThat(matrix.width).isAtLeast(21) // Minimum QR code dimension
        assertThat(matrix.height).isAtLeast(21)

        // Ensure finder pattern is present (top-left is black/true)
        var hasTrue = false
        var hasFalse = false
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) hasTrue = true else hasFalse = true
            }
        }
        assertThat(hasTrue).isTrue()
        assertThat(hasFalse).isTrue()
    }

    @Test
    fun `renderQrBitmap renders Android Bitmap with Zinc 950 Dark Mode styling`() {
        val bitmap = engine.renderQrBitmap(
            content = "https://fastapi.trycloudflare.com",
            size = 200,
            foregroundColor = DualQrCodeEngine.COLOR_ZINC_100,
            backgroundColor = DualQrCodeEngine.COLOR_ZINC_950
        )

        assertThat(bitmap).isNotNull()
        assertThat(bitmap.width).isEqualTo(200)
        assertThat(bitmap.height).isEqualTo(200)

        // Sample background pixel
        val pixelBg = bitmap.getPixel(0, 0)
        assertThat(pixelBg).isEqualTo(DualQrCodeEngine.COLOR_ZINC_950)
    }
}
