package com.pocketserver.ui.share

import android.graphics.Bitmap
import android.view.Window
import android.view.WindowManager
import com.pocketserver.network.qr.DualQrCodeEngine
import com.pocketserver.network.qr.QrMatrix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.HttpURLConnection
import java.net.URL

enum class SharingTab {
    LOCAL_WIFI,
    INTERNET_PUBLIC
}

data class QrSharingState(
    val serviceName: String,
    val localWifiUrl: String,
    val mDnsUrl: String,
    val publicTunnelUrl: String?,
    val activeTab: SharingTab = SharingTab.LOCAL_WIFI,
    val isBrightnessBoosted: Boolean = false,
    val currentUrl: String = localWifiUrl
)

/**
 * Controller for High-Fidelity QR Sharing with Auto-Brightness Boost.
 *
 * Implements Flow 6 & Automated Verification Protocol:
 * - Local Wi-Fi and Global HTTPS QR views.
 * - Automatic brightness boost to 100% (screenBrightness = 1.0f) and FLAG_KEEP_SCREEN_ON
 *   so other phone cameras scan the code effortlessly.
 * - Clean restoration of previous window brightness upon modal dismissal.
 * - Dual Sharing validation across LAN and Cloudflare tunnel endpoints.
 */
class QrSharingController(
    val serviceName: String,
    val localWifiUrl: String,
    val mDnsUrl: String,
    val publicTunnelUrl: String? = null,
    private val qrEngine: DualQrCodeEngine = DualQrCodeEngine()
) {

    private val _state = MutableStateFlow(
        QrSharingState(
            serviceName = serviceName,
            localWifiUrl = localWifiUrl,
            mDnsUrl = mDnsUrl,
            publicTunnelUrl = publicTunnelUrl,
            activeTab = SharingTab.LOCAL_WIFI,
            currentUrl = localWifiUrl
        )
    )
    val state: StateFlow<QrSharingState> = _state.asStateFlow()

    private var originalBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var originalKeepScreenOn: Boolean = false

    /**
     * Switches the active tab between LOCAL_WIFI and INTERNET_PUBLIC.
     */
    fun selectTab(tab: SharingTab) {
        val url = when (tab) {
            SharingTab.LOCAL_WIFI -> localWifiUrl
            SharingTab.INTERNET_PUBLIC -> publicTunnelUrl ?: localWifiUrl
        }
        _state.value = _state.value.copy(
            activeTab = tab,
            currentUrl = url
        )
    }

    /**
     * Boosts window screen brightness to 100% and sets FLAG_KEEP_SCREEN_ON.
     */
    fun onModalShown(window: Window) {
        val layoutParams = window.attributes
        originalBrightness = layoutParams.screenBrightness
        originalKeepScreenOn = (layoutParams.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

        layoutParams.screenBrightness = 1.0f // 100% maximum brightness
        window.attributes = layoutParams
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        _state.value = _state.value.copy(isBrightnessBoosted = true)
    }

    /**
     * Restores previous brightness and screen flags when modal is dismissed.
     */
    fun onModalDismissed(window: Window) {
        val layoutParams = window.attributes
        layoutParams.screenBrightness = originalBrightness
        window.attributes = layoutParams

        if (!originalKeepScreenOn) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        _state.value = _state.value.copy(isBrightnessBoosted = false)
    }

    /**
     * Generates a 2D bit matrix for testing or canvas rendering.
     */
    fun generateActiveQrMatrix(sizePx: Int = 256): QrMatrix {
        return qrEngine.generateQrMatrix(_state.value.currentUrl, sizePx)
    }

    /**
     * Generates a QR code bitmap for the currently active tab.
     */
    fun generateActiveQrBitmap(sizePx: Int = 512): Bitmap {
        val matrix = generateActiveQrMatrix(sizePx)
        return qrEngine.renderBitmap(matrix)
    }

    /**
     * Validates that both endpoints respond with HTTP 200.
     */
    fun validateEndpoint(urlString: String, timeoutMs: Int = 3000): Int {
        return try {
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.responseCode
        } catch (e: Exception) {
            -1
        }
    }
}
