package com.pocketserver.network.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

/**
 * Model representing local LAN and global public endpoints with security context notices.
 */
data class ShareEndpoint(
    val serviceName: String,
    val lanUrl: String,
    val globalUrl: String?,
    val isPlainHttpLanInsecure: Boolean = true,
    val secureContextNotice: String = DEFAULT_SECURE_CONTEXT_NOTICE
) {
    companion object {
        const val DEFAULT_SECURE_CONTEXT_NOTICE =
            "Modern browsers restrict WebCrypto, Clipboard, and Service Workers on plain HTTP LAN. " +
            "Use the Global Public Link (HTTPS) tab for secure context compliance."
    }
}

/**
 * Portable 2D bit matrix representation of a QR code.
 */
data class QrMatrix(
    val width: Int,
    val height: Int,
    val bits: Array<BooleanArray>
) {
    fun get(x: Int, y: Int): Boolean = bits[y][x]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as QrMatrix
        return width == other.width && height == other.height && bits.contentDeepEquals(other.bits)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + bits.contentDeepHashCode()
        return result
    }
}

/**
 * Engine for generating dual QR codes (Local LAN & Global HTTPS) matching
 * the Tailwind Zinc 950 approachable dark mode design system.
 */
class DualQrCodeEngine {

    companion object {
        // Human-Centric Zinc 950 Theme Tokens
        const val COLOR_ZINC_950 = 0xFF09090B.toInt() // #09090B Canvas Background
        const val COLOR_ZINC_100 = 0xFFF4F4F5.toInt() // #F4F4F5 High-Contrast Foreground
        const val COLOR_INDIGO_ACCENT = 0xFF6366F1.toInt() // #6366F1 Brand Accent
        const val COLOR_EMERALD_ACCENT = 0xFF10B981.toInt() // #10B981 Live Accent
    }

    /**
     * Constructs a ShareEndpoint object with LAN and Global HTTPS endpoints.
     */
    fun createShareEndpoint(
        serviceName: String,
        localIp: String,
        port: Int,
        globalTunnelUrl: String? = null
    ): ShareEndpoint {
        val lanUrl = "http://$localIp:$port"
        return ShareEndpoint(
            serviceName = serviceName,
            lanUrl = lanUrl,
            globalUrl = globalTunnelUrl,
            isPlainHttpLanInsecure = lanUrl.startsWith("http://")
        )
    }

    /**
     * Generates a 2D bit matrix representation of the content QR code.
     */
    fun generateQrMatrix(
        content: String,
        size: Int = 256,
        margin: Int = 1
    ): QrMatrix {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            put(EncodeHintType.MARGIN, margin)
        }

        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height

        val bits = Array(height) { y ->
            BooleanArray(width) { x ->
                bitMatrix.get(x, y)
            }
        }

        return QrMatrix(width, height, bits)
    }

    /**
     * Renders a Zinc 950 Dark Mode Bitmap from a QrMatrix.
     */
    fun renderBitmap(
        matrix: QrMatrix,
        foregroundColor: Int = COLOR_ZINC_100,
        backgroundColor: Int = COLOR_ZINC_950
    ): Bitmap {
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) foregroundColor else backgroundColor
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Directly generates and renders a Zinc 950 Dark Mode Bitmap for the provided string content.
     */
    fun renderQrBitmap(
        content: String,
        size: Int = 256,
        foregroundColor: Int = COLOR_ZINC_100,
        backgroundColor: Int = COLOR_ZINC_950
    ): Bitmap {
        val matrix = generateQrMatrix(content, size)
        return renderBitmap(matrix, foregroundColor, backgroundColor)
    }
}
