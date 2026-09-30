package com.worldexecute.viewer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.Socket

/**
 * Drives the real [AssetServer] over a real socket.
 *
 * Everything the app does when it shares the film — the door, the byte ranges,
 * the path check — happens in this class, and none of it needs a device: the
 * server talks to a `File` and to a `ServerSocket`. Two things are deliberately
 * not covered here — the `Context` that decides *which* directory to serve, and
 * the notification — because those are the parts that genuinely are Android's.
 *
 * Requests are written by hand rather than through `HttpURLConnection` for one
 * reason: `URL` normalises `..` out of a path before it ever reaches the wire,
 * which is exactly the request this file has to send.
 */
class AssetServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: AssetServer
    private var port = 0

    private val token = "AB12CD34"

    private lateinit var root: File
    private lateinit var outside: File
    private lateinit var audio: ByteArray

    @Before
    fun setUp() {
        root = tmp.newFolder("web")
        outside = tmp.newFolder("private")
        File(outside, "secret.txt").writeText("outside the root")

        File(root, "index.html").writeText("<html>the film</html>")
        File(root, "assets").mkdirs()
        audio = ByteArray(1000) { (it % 251).toByte() }
        File(root, "assets/audio.mp3").writeBytes(audio)
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    // -- harness -------------------------------------------------------------

    private fun start(bindAll: Boolean = false, shareToken: String? = null) {
        server = AssetServer { root }
        port = server.start(preferred = 0, bindAll = bindAll, shareToken = shareToken)
    }

    private class Response(val status: Int, val headers: String, val body: String) {
        val bodyBytes: ByteArray get() = body.toByteArray(Charsets.ISO_8859_1)
    }

    /** One request, verbatim, then read to EOF — the server closes every reply. */
    private fun request(path: String, extraHeaders: String = ""): Response {
        Socket("127.0.0.1", port).use { socket ->
            val head = "GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n$extraHeaders\r\n"
            socket.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            val raw = socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)

            val split = raw.indexOf("\r\n\r\n")
            val headText = if (split < 0) raw else raw.substring(0, split)
            val body = if (split < 0) "" else raw.substring(split + 4)
            val status = headText.lineSequence().firstOrNull()
                ?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: -1
            return Response(status, headText, body)
        }
    }

    // -- loopback ------------------------------------------------------------

    @Test
    fun `loopback mode serves the film straight off the root`() {
        start()
        val response = request("/index.html")
        assertEquals(200, response.status)
        assertTrue(response.body.contains("the film"))
    }

    @Test
    fun `loopback mode treats the root as the film`() {
        start()
        assertEquals(200, request("/").status)
    }

    @Test
    fun `a port is actually bound`() {
        start()
        assertTrue(port > 0)
        assertTrue(server.isRunning)
        assertFalse(server.shared)
    }

    // -- the share gate ------------------------------------------------------

    @Test
    fun `sharing mode refuses a request that carries no token`() {
        start(bindAll = true, shareToken = token)

        for (path in listOf("/", "/index.html", "/assets/audio.mp3")) {
            val response = request(path)
            assertEquals("$path should not be served", 403, response.status)
            assertFalse("the film leaked: $path", response.body.contains("the film"))
        }
    }

    @Test
    fun `the refusal does not hand back the token`() {
        // A refusal that echoes the secret is worse than no refusal at all.
        start(bindAll = true, shareToken = token)
        val response = request("/index.html")
        assertFalse(response.body.contains(token))
        assertFalse(response.headers.contains(token))
    }

    @Test
    fun `sharing mode serves the film under the token`() {
        start(bindAll = true, shareToken = token)

        val response = request("/s/$token/index.html")
        assertEquals(200, response.status)
        assertTrue(response.body.contains("the film"))
    }

    @Test
    fun `a hand-typed share address with no trailing slash still lands on the film`() {
        start(bindAll = true, shareToken = token)
        assertEquals(200, request("/s/$token").status)
    }

    @Test
    fun `sharing mode refuses the wrong token`() {
        start(bindAll = true, shareToken = token)

        for (wrong in listOf("AB12CD35", "ZZZZZZZZ", "ab12cd34", "AB12CD", "AB12CD345")) {
            assertEquals("$wrong was accepted", 403, request("/s/$wrong/index.html").status)
        }
    }

    @Test
    fun `turning the switch off closes the door again`() {
        // The whole reason a token is generated per share session: after sharing
        // ends, the address that was photographed stops working.
        start(bindAll = true, shareToken = token)
        assertEquals(200, request("/s/$token/index.html").status)
        server.stop()

        // A fresh server, not sharing, on the same root: the old path must now
        // be an ordinary miss rather than a way in.
        val after = AssetServer { root }
        try {
            val newPort = after.start(preferred = 0)
            Socket("127.0.0.1", newPort).use { socket ->
                val head = "GET /s/$token/index.html HTTP/1.1\r\nHost: x\r\n\r\n"
                socket.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1))
                socket.getOutputStream().flush()
                val raw = socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
                assertTrue("the old share address still works: $raw", raw.startsWith("HTTP/1.1 404"))
            }
        } finally {
            after.stop()
        }
    }

    // -- traversal -----------------------------------------------------------

    @Test
    fun `a valid token is not a way out of the root`() {
        start(bindAll = true, shareToken = token)

        // Sent raw, because a normalising client would rewrite this before it
        // reached the server and the test would prove nothing.
        val response = request("/s/$token/../private/secret.txt")
        assertEquals(403, response.status)
        assertFalse(response.body.contains("outside the root"))
    }

    @Test
    fun `an encoded traversal is not a way out either`() {
        start(bindAll = true, shareToken = token)
        val response = request("/s/$token/%2e%2e/private/secret.txt")
        assertTrue(response.status == 403 || response.status == 404)
        assertFalse(response.body.contains("outside the root"))
    }

    @Test
    fun `a miss inside the share is a 404, not a 403`() {
        start(bindAll = true, shareToken = token)
        assertEquals(404, request("/s/$token/nope.html").status)
    }

    // -- ranges --------------------------------------------------------------

    @Test
    fun `a byte range comes back as 206 with exactly those bytes`() {
        // This is what dragging the film's waveform does, and getting it wrong
        // is what makes scrubbing unusable on a phone.
        start(bindAll = true, shareToken = token)

        val response = request("/s/$token/assets/audio.mp3", "Range: bytes=10-19\r\n")
        assertEquals(206, response.status)
        assertTrue(response.headers.contains("Content-Range: bytes 10-19/1000"))
        assertArrayEquals(audio.copyOfRange(10, 20), response.bodyBytes)
    }

    @Test
    fun `an open-ended range runs to the end`() {
        start(bindAll = true, shareToken = token)

        val response = request("/s/$token/assets/audio.mp3", "Range: bytes=990-\r\n")
        assertEquals(206, response.status)
        assertArrayEquals(audio.copyOfRange(990, 1000), response.bodyBytes)
    }

    @Test
    fun `a suffix range counts back from the end`() {
        start(bindAll = true, shareToken = token)

        val response = request("/s/$token/assets/audio.mp3", "Range: bytes=-10\r\n")
        assertEquals(206, response.status)
        assertArrayEquals(audio.copyOfRange(990, 1000), response.bodyBytes)
    }

    @Test
    fun `a range past the end is refused`() {
        start(bindAll = true, shareToken = token)

        val response = request("/s/$token/assets/audio.mp3", "Range: bytes=5000-6000\r\n")
        assertEquals(416, response.status)
        assertTrue(response.headers.contains("Content-Range: bytes */1000"))
    }

    @Test
    fun `a whole-file request returns the whole file`() {
        start(bindAll = true, shareToken = token)

        val response = request("/s/$token/assets/audio.mp3")
        assertEquals(200, response.status)
        assertArrayEquals(audio, response.bodyBytes)
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals("length", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("byte $i", expected[i], actual[i])
        }
    }
}
