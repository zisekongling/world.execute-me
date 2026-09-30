package com.worldexecute.viewer

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * A QR code is either readable or it is not, and there is no middle ground to
 * eyeball. So this encodes the real share URL and decodes it again with zxing's
 * own reader — the same algorithm a phone's camera app runs.
 *
 * It is not a camera test: it cannot tell us the code is big enough or bright
 * enough. It can tell us the modules are in the order a scanner expects, which
 * is the failure that looks like a working QR code on screen and does nothing.
 */
class QrCodeTest {

    /** The shape a shared address actually takes: host, port, token, file. */
    private val url = "http://192.168.1.234:47821/s/AB12CD34/index.html"

    private fun decode(px: IntArray, width: Int, height: Int): String? {
        val source = RGBLuminanceSource(width, height, px)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return MultiFormatReader().decode(bitmap).text
    }

    @Test
    fun `the share url survives a round trip`() {
        val modules = QrCode.matrix(url, 480)
        assertNotNull("no matrix was produced", modules)

        val pixels = QrCode.pixels(modules!!, BLACK, WHITE)
        assertEquals(url, decode(pixels, modules.width, modules.height))
    }

    @Test
    fun `it still decodes in the app's own palette`() {
        // 0xFF04070C on 0xFFE8F2F7 — the colours ServeActivity actually paints,
        // which are not pure black and white.
        val modules = QrCode.matrix(url, 480)!!
        val pixels = QrCode.pixels(modules, 0xFF04070C.toInt(), 0xFFE8F2F7.toInt())
        assertEquals(url, decode(pixels, modules.width, modules.height))
    }

    @Test
    fun `it decodes at every size the view might ask for`() {
        // ServeActivity generates at 168dp, which spans this range across the
        // density buckets, and clamps to the ends of it.
        for (size in listOf(200, 240, 320, 462, 528, 720)) {
            val modules = QrCode.matrix(url, size)
            assertNotNull("no matrix at ${size}px", modules)
            val pixels = QrCode.pixels(modules!!, BLACK, WHITE)
            assertEquals("failed at ${size}px", url, decode(pixels, modules.width, modules.height))
        }
    }

    @Test
    fun `a quiet zone is present`() {
        // The margin is what a scanner uses to find the symbol's edges. A dark
        // panel around the code is not a quiet zone unless the code brings its
        // own, so the outermost ring of modules must be light.
        val modules = QrCode.matrix(url, 480)!!
        for (x in 0 until modules.width) {
            assertEquals("top edge at $x", false, modules.get(x, 0))
            assertEquals("bottom edge at $x", false, modules.get(x, modules.height - 1))
        }
        for (y in 0 until modules.height) {
            assertEquals("left edge at $y", false, modules.get(0, y))
            assertEquals("right edge at $y", false, modules.get(modules.width - 1, y))
        }
    }

    @Test
    fun `the shortest and longest urls in play both encode`() {
        val cases = listOf(
            // A loopback address, no token: sharing off.
            "http://127.0.0.1:47821/index.html",
            // A LAN address with a token, no quality query.
            "http://10.0.0.1:47821/s/AB12CD34/index.html",
            // The longest form: high quality adds ?dpr=2.
            "http://192.168.100.200:47845/s/ABCDEFGH/index.html?dpr=2",
        )
        for (text in cases) {
            val modules = QrCode.matrix(text, 480)
            assertNotNull("no matrix for $text", modules)
            val pixels = QrCode.pixels(modules!!, BLACK, WHITE)
            assertEquals(text, decode(pixels, modules.width, modules.height))
        }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
