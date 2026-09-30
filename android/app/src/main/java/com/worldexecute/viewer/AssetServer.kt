package com.worldexecute.viewer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The built-in server: the film, over HTTP, on loopback.
 *
 * This exists so a browser the app does not control can render the film. That
 * decision brings two requirements with it, and both are why this is not three
 * lines of `python -m http.server`:
 *
 *  * **Range requests.** `<audio>` scrubbing is byte-range requests. A server
 *    that answers a seek with the whole 8 MB file makes dragging the waveform
 *    unusable. Full `bytes=a-b`, `bytes=a-`, `bytes=-n`, `206`, `416` and
 *    `If-Modified-Since` are implemented below.
 *  * **A closed door.** By default it binds to 127.0.0.1 only, and every request
 *    path is resolved against the film's root with a canonical-path check, so
 *    `../` can never leave the directory.
 *
 * Sharing the film with another device is the one case that needs the socket to
 * face the network. That mode is a deliberate opt-in and comes with the token in
 * [Share]: the listener widens, but every request must still arrive under a path
 * segment that only the QR code knows.
 *
 * No third-party HTTP dependency: an HTTP/1.1 subset this small is easier to
 * trust than to add.
 */
class AssetServer(private val rootProvider: () -> File) {

    @Volatile
    private var socket: ServerSocket? = null

    /** The share token this instance was started under; null when loopback only. */
    @Volatile
    var token: String? = null
        private set

    /** True when the socket is bound to the network rather than to loopback. */
    @Volatile
    var shared: Boolean = false
        private set

    private val pool: ExecutorService = Executors.newFixedThreadPool(4)
    private val served = AtomicInteger(0)
    private val bytesSent = AtomicInteger(0)

    @Volatile
    var port: Int = 0
        private set

    val isRunning: Boolean get() = socket?.isClosed == false

    /**
     * Binds the socket, trying [preferred] first and then the twenty-four ports
     * after it, before falling back to whatever the OS hands out.
     *
     * [bindAll] widens the listener from loopback to every interface, which is
     * what sharing to another device requires — and is exactly why it is off
     * unless asked for. [shareToken] must then be non-null or the widened socket
     * would be a file server with no door at all.
     */
    @Synchronized
    fun start(
        preferred: Int = Prefs.DEFAULT_PORT,
        bindAll: Boolean = false,
        shareToken: String? = null,
    ): Int {
        if (isRunning) return port
        var last: Exception? = null
        val candidates = ArrayList<Int>(26)
        if (preferred > 0) {
            candidates.add(preferred)
            for (i in 1..24) candidates.add(preferred + i)
        }
        candidates.add(0)

        val address = InetAddress.getByName(if (bindAll) "0.0.0.0" else "127.0.0.1")

        for (candidate in candidates) {
            try {
                val s = ServerSocket(candidate, 64, address)
                socket = s
                port = s.localPort
                shared = bindAll
                token = shareToken
                Thread({ acceptLoop(s) }, "asset-server").apply {
                    isDaemon = true
                    start()
                }
                Diag.add("server", if (bindAll) {
                    "监听 0.0.0.0:$port（局域网分享，路径前缀 " + Share.prefix(shareToken) + "）"
                } else {
                    "监听 http://127.0.0.1:$port/  （根目录 $rootProvider()）"
                })
                return port
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("没有可用端口：" + (last?.message ?: "未知原因"))
    }

    @Synchronized
    fun stop() {
        val s = socket ?: return
        socket = null
        port = 0
        shared = false
        token = null
        try { s.close() } catch (e: Exception) { /* already gone */ }
        Diag.add("server", "已停止（本次共处理 $served 个请求，发出 " +
                (bytesSent.get() / 1048576) + " MB）")
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try {
                s.accept()
            } catch (e: Exception) {
                break
            }
            try {
                pool.execute { handle(client) }
            } catch (e: Exception) {
                try { client.close() } catch (ignored: Exception) { /* nothing to do */ }
            }
        }
    }

    // -- request ------------------------------------------------------------

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 10_000
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream(), 8192)

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return

            val method = parts[0].uppercase(Locale.US)
            val target = parts[1]

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                        line.substring(colon + 1).trim()
                }
            }

            val out = BufferedOutputStream(client.getOutputStream(), 64 * 1024)
            respond(out, method, target, headers)
            out.flush()
        } catch (e: Exception) {
            Diag.add("server", "请求出错: " + e.javaClass.simpleName + " " + e.message)
        } finally {
            try { client.close() } catch (e: Exception) { /* nothing to do */ }
        }
    }

    /** Reads one CRLF-terminated header line. Returns null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder(96)
        var b = input.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length >= 8192) break
            b = input.read()
        }
        return sb.toString()
    }

    // -- response -----------------------------------------------------------

    private fun respond(
        out: OutputStream,
        method: String,
        target: String,
        headers: Map<String, String>,
    ) {
        if (method != "GET" && method != "HEAD") {
            sendError(out, 405, "Method Not Allowed", "Only GET and HEAD are served.")
            return
        }

        var path = target.substringBefore('?').substringBefore('#')
        path = try {
            URLDecoder.decode(path, "UTF-8")
        } catch (e: Exception) {
            path
        }

        if (!path.startsWith("/")) {
            sendError(out, 400, "Bad Request", "Bad request target.")
            return
        }

        // While sharing, the token is the door. `strip` returning null is the
        // only correct response to a request that did not carry it: falling
        // through to the default would serve the film to anyone who guessed the
        // address, which is the situation the token exists to prevent.
        val ungated = Share.strip(token, path)
        if (ungated == null) {
            Diag.add("server", "拒绝未带口令的请求: $path")
            sendShareGate(out)
            return
        }
        path = ungated

        if (path.endsWith("/")) path += "index.html"

        val root = try { rootProvider().canonicalFile } catch (e: Exception) {
            sendError(out, 500, "Internal Error", "No document root.")
            return
        }
        val file = File(root, path.removePrefix("/")).canonicalFile
        if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) {
            Diag.add("server", "拒绝越界路径: $path")
            sendError(out, 403, "Forbidden", "Outside the document root.")
            return
        }
        if (!file.isFile) {
            sendError(out, 404, "Not Found", path)
            return
        }

        val total = file.length()
        val lastModified = file.lastModified()
        val mime = mimeOf(file.name)
        val rangeHeader = headers["range"]

        if (rangeHeader == null) {
            val since = parseHttpDate(headers["if-modified-since"])
            if (since != null && lastModified / 1000 <= since / 1000) {
                writeHead(out, 304, "Not Modified", mime, 0, lastModified, null)
                return
            }
        }

        var start = 0L
        var end = total - 1
        var status = 200
        var contentRange: String? = null

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").substringBefore(',')
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val first = spec.substring(0, dash).trim()
                val second = spec.substring(dash + 1).trim()
                if (first.isEmpty()) {
                    val suffix = second.toLongOrNull()
                    if (suffix != null && suffix > 0) {
                        start = maxOf(0L, total - suffix)
                        end = total - 1
                    }
                } else {
                    val s = first.toLongOrNull()
                    if (s != null && s >= 0) {
                        start = s
                        val e = if (second.isEmpty()) null else second.toLongOrNull()
                        end = if (e == null) total - 1 else minOf(e, total - 1)
                    }
                }
            }
            if (start > end || start >= total) {
                writeHead(out, 416, "Range Not Satisfiable", mime, 0, lastModified,
                    "Content-Range: bytes */$total")
                return
            }
            status = 206
            contentRange = "Content-Range: bytes $start-$end/$total"
        }

        val length = end - start + 1
        writeHead(out, status, if (status == 206) "Partial Content" else "OK", mime,
            length, lastModified, contentRange)
        if (method == "HEAD") return

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            while (remaining > 0) {
                val want = minOf(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, want)
                if (read <= 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
        }
        served.incrementAndGet()
        bytesSent.addAndGet(length.toInt())
        if (served.get() <= 40 || served.get() % 200 == 0) {
            Diag.add("server", "GET $path -> $status, $length B")
        }
    }

    private fun writeHead(
        out: OutputStream,
        status: Int,
        reason: String,
        mime: String,
        length: Long,
        lastModified: Long,
        extra: String?,
    ) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        sb.append("Content-Type: ").append(mime).append("\r\n")
        sb.append("Content-Length: ").append(length).append("\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        if (lastModified > 0) {
            sb.append("Last-Modified: ").append(formatHttpDate(lastModified)).append("\r\n")
        }
        // no-cache, not no-store: the browser may keep the bytes, but must ask
        // again every time. That way a rebuilt film shows up on a reload while
        // an 8 MB mp3 is still only fetched in the ranges the audio engine asks
        // for.
        sb.append("Cache-Control: no-cache\r\n")
        sb.append("Connection: close\r\n")
        if (extra != null) sb.append(extra).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    /**
     * What someone sees when they type this device's address by hand instead of
     * scanning. It should read as a door, not as a fault: the address is
     * supposed to be reachable, it just is not the address that was shared.
     * Nothing about the token is echoed back — not even its length.
     */
    private fun sendShareGate(out: OutputStream) {
        val body = """
            <!doctype html><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>world.execute(me);</title>
            <body style="margin:0;background:#03050a;color:#6b8598;
                         font:14px/1.7 -apple-system,system-ui,sans-serif;
                         display:flex;align-items:center;justify-content:center;
                         min-height:100vh;text-align:center">
            <div style="padding:32px;max-width:30em">
              <div style="color:#56d6e8;letter-spacing:.3em;font-size:12px">WORLD.EXECUTE(ME);</div>
              <h1 style="color:#dceaf2;font-size:17px;font-weight:400;margin:18px 0 10px">
                这个地址需要扫码才能打开</h1>
              <p style="margin:0">影片是分享出来的，不是公开的。<br>
                 请扫描发起分享的那台设备上显示的二维码。</p>
              <p style="margin-top:20px;font-size:12px;color:#2b7f8e">
                发起分享的人可以随时关闭分享，这个地址随即失效。</p>
            </div>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        writeHead(out, 403, "Forbidden", "text/html; charset=utf-8",
            body.size.toLong(), 0L, null)
        out.write(body)
    }

    private fun sendError(out: OutputStream, status: Int, reason: String, detail: String) {
        val body = ("<!doctype html><meta charset=\"utf-8\">" +
                "<title>" + status + " " + reason + "</title>" +
                "<body style=\"background:#03050a;color:#6b8598;font:13px monospace;padding:40px\">" +
                "<h1 style=\"color:#56d6e8;font-size:15px;letter-spacing:.2em\">" +
                status + " " + reason + "</h1><p>" + escape(detail) + "</p>")
            .toByteArray(Charsets.UTF_8)
        writeHead(out, status, reason, "text/html; charset=utf-8",
            body.size.toLong(), 0L, null)
        out.write(body)
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun mimeOf(name: String): String =
        when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "txt" -> "text/plain; charset=utf-8"
            "mp3" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "woff2" -> "font/woff2"
            "woff" -> "font/woff"
            else -> "application/octet-stream"
        }

    // -- dates --------------------------------------------------------------
    // SimpleDateFormat is not thread-safe and four worker threads share this
    // one, so every use is inside the lock.

    private val httpDate = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }

    private fun formatHttpDate(millis: Long): String =
        synchronized(httpDate) { httpDate.format(Date(millis)) }

    private fun parseHttpDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            synchronized(httpDate) { httpDate.parse(value)?.time }
        } catch (e: Exception) {
            null
        }
    }
}
