package com.worldexecute.viewer

import android.content.Context

/**
 * The one place that owns the server instance.
 *
 * Both the WebView player and the browser hand-off need the same server, sometimes
 * at the same time, occasionally from different threads. Keeping a single
 * synchronized holder means neither one can start a second copy or shut down a
 * server the other is still using.
 *
 * It also owns the one piece of state that changes what the socket *is*:
 * [sharing]. Binding address and share token are fixed when the socket is
 * created, so switching between loopback and the network is a stop and a start
 * rather than a flag flip — which is why nobody else touches the server.
 */
object ServerHub {

    private var server: AssetServer? = null

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** True while the socket faces the network instead of only loopback. */
    @Volatile
    var sharing: Boolean = false
        private set

    /** The current share token, or null when not sharing. */
    @Volatile
    var token: String? = null
        private set

    val isRunning: Boolean get() = server?.isRunning == true

    /**
     * Starts the server if it is not already up, unpacking the film first.
     * Returns the port, or 0 when it could not be started — callers must treat
     * 0 as "use the file:// fallback", not as a fatal error.
     *
     * An already-running server is left exactly as it is, shared or not: this is
     * called from the player, which has no business revoking a share the user
     * turned on from the server screen.
     */
    @Synchronized
    fun ensure(context: Context): Int {
        server?.let { if (it.isRunning) return it.port }

        return try {
            val root = WebAssets.ensure(context.applicationContext)
            val started = AssetServer { root }
            port = started.start(Prefs(context).serverPort)
            server = started
            lastError = null
            port
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Diag.add("server", "启动失败: $lastError")
            server = null
            port = 0
            0
        }
    }

    /**
     * Puts the server into shared or loopback mode, restarting it if that is
     * what it takes. Returns false when sharing was asked for but this device
     * has no address another device could dial — a share nobody can reach is
     * worse than a refusal, because it looks like it worked.
     */
    @Synchronized
    fun setSharing(context: Context, on: Boolean): Boolean {
        if (on && LanAddress.find() == null) {
            lastError = context.getString(R.string.share_no_lan)
            Diag.add("server", "无法开启分享：没有局域网地址")
            return false
        }

        val current = server
        if (current != null && current.isRunning) {
            if (current.shared == on) return true
            current.stop()
            server = null
            port = 0
        }

        return try {
            val root = WebAssets.ensure(context.applicationContext)
            // A new token every time sharing goes on, so turning it off and on
            // again is a real revocation and not just a pause.
            val fresh = if (on) Share.newToken() else null
            val started = AssetServer { root }
            port = started.start(Prefs(context).serverPort, bindAll = on, shareToken = fresh)
            server = started
            sharing = on
            token = fresh
            lastError = null
            if (on) Diag.add("server", "已开启局域网分享，口令 ${fresh?.length ?: 0} 位")
            port > 0
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            Diag.add("server", "切换分享失败: $lastError")
            server = null
            port = 0
            sharing = false
            token = null
            false
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        port = 0
        sharing = false
        token = null
    }

    /** The URL to open on this device, or null when the server is not running. */
    fun url(path: String = "/index.html"): String? {
        if (port <= 0) return null
        return "http://127.0.0.1:$port" + pathUnderShare(path)
    }

    /**
     * The URL another device on the same network should open, or null when this
     * device is not sharing or has no reachable address.
     *
     * The address is looked up per call rather than remembered, because phones
     * change networks without telling anyone and a stale address in a QR code
     * fails in the least diagnosable way possible.
     */
    fun lanUrl(path: String = "/index.html"): String? {
        if (port <= 0 || !sharing) return null
        val host = LanAddress.find() ?: return null
        return "http://$host:$port" + pathUnderShare(path)
    }

    private fun pathUnderShare(path: String): String =
        Share.prefix(token) + path.removePrefix("/")
}
