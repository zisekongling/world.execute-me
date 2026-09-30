package com.worldexecute.viewer

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Turns a URL into something a camera can read off a screen.
 *
 * The encoding is zxing's, and this file has no Android types in it at all —
 * [matrix] and [pixels] are the whole of the logic, which is what lets a JVM
 * test decode the result and prove the modules come out in an order a scanner
 * agrees with, instead of proving it by holding a phone up to a monitor. The
 * one Android call, `Bitmap.createBitmap`, lives at the call site in
 * `ServeActivity`.
 */
object QrCode {

    /**
     * Four blank modules of quiet zone, which the specification asks for and
     * camera apps rely on to find the symbol's edges. The panel it sits on is
     * dark, and a dark background is not a quiet zone.
     */
    private const val QUIET_ZONE_MODULES = 4

    /**
     * Error correction level M (about 15% recoverable) at a screen-to-camera
     * distance with a reflection in between. H recovers more but makes the
     * modules smaller for the same URL, and the URL here is around 50
     * characters.
     */
    private val LEVEL = ErrorCorrectionLevel.M

    /** The modules, or null when the text cannot be encoded at this size. */
    fun matrix(text: String, sizePx: Int): BitMatrix? = try {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
            EncodeHintType.ERROR_CORRECTION to LEVEL,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    } catch (e: Exception) {
        Diag.add("qr", "二维码生成失败（$sizePx px）：" + e.message)
        null
    }

    /**
     * The matrix as ARGB pixels, dark modules [dark] and light ones [light].
     *
     * Light modules on a dark field would sit better in this palette and fail on
     * a good share of phone cameras, so a shared code is dark on light; the
     * colours come from the caller so this stays a plain integer array.
     */
    fun pixels(modules: BitMatrix, dark: Int, light: Int): IntArray {
        val width = modules.width
        val height = modules.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (modules.get(x, y)) dark else light
            }
        }
        return pixels
    }
}
