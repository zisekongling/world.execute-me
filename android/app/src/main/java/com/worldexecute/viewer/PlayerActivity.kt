package com.worldexecute.viewer

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The film, on the phone's own engine.
 *
 * The WebView is deliberately kept as close to a plain browser as possible. The
 * film is a self-contained page whose whole job is to draw to a canvas from
 * `audio.currentTime`, so the interesting parts of this class are not rendering
 * but the four things a raw WebView gets wrong on a phone:
 *
 *  1. **Autoplay.** The page starts on the first tap, but a WebView also needs
 *     [WebSettings.setMediaPlaybackRequiresUserGesture] off, or the audio element
 *     silently refuses to start.
 *  2. **Fullscreen and the notch.** Immersive sticky hides the bars; the cutout
 *     mode is set in the theme. Neither is optional in landscape on a modern
 *     phone, where the bars otherwise overlap the HUD.
 *  3. **Origin.** `file://` origins cannot use `localStorage` reliably and cannot
 *     range-request the audio, so the film is served from [ServerHub] over
 *     loopback when that works. The `file:///android_asset` path stays as a
 *     fallback so the app still plays if the server cannot bind a port.
 *  4. **Being killed.** A WebView renderer that dies takes the page with it;
 *     [onRenderProcessGone] reloads instead of letting the app die.
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var spinner: View
    private lateinit var errorBox: View
    private lateinit var tvError: android.widget.TextView
    private lateinit var btnRetry: View
    private lateinit var prefs: Prefs

    private var serverPort = 0
    private var currentUrl: String = ""
    private var failed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        prefs = Prefs(this)
        web = findViewById(R.id.web)
        spinner = findViewById(R.id.spinner)
        errorBox = findViewById(R.id.errorBox)
        tvError = findViewById(R.id.tvError)
        btnRetry = findViewById(R.id.btnRetry)

        if (prefs.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        applyOrientation()
        Diag.add("player", "打开播放器：engine=${prefs.engine}, quality=${prefs.quality}")

        setUpWebView()

        findViewById<View>(R.id.btnMenu).setOnClickListener { showMenu(it) }
        btnRetry.setOnClickListener {
            errorBox.visibility = View.GONE
            failed = false
            loadFilm()
        }

        loadFilm()
    }

    // -- web view ------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        val settings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadWithOverviewMode = false
        settings.useWideViewPort = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.textZoom = 100
        settings.mediaPlaybackRequiresUserGesture = false

        web.setBackgroundColor(0xFF03050A.toInt())
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.addJavascriptInterface(AndroidHost(), "AndroidHost")

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                spinner.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                spinner.visibility = View.GONE
                if (!failed) errorBox.visibility = View.GONE
                Diag.add("player", "页面载入完成: $url")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                // Sub-resource failures are noise; only a failed main frame is
                // worth putting on screen.
                if (request?.isForMainFrame != true) return
                val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    error?.description?.toString() ?: "未知错误"
                } else {
                    "未知错误"
                }
                showError(description)
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                // Returning true claims the crash. Without this the whole app is
                // torn down because a renderer ran out of memory.
                Diag.add("player", "WebView 渲染进程被系统回收，正在重建")
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.player_error, "渲染进程被系统回收"),
                    Toast.LENGTH_LONG,
                ).show()
                recreateWebView()
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                message ?: return false
                // Only surface the interesting levels: the film logs nothing on
                // a healthy run, so warnings and errors are signal.
                if (message.messageLevel() >= ConsoleMessage.MessageLevel.WARNING) {
                    Diag.add("console", message.message() + " (${message.lineNumber()})")
                }
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                super.onShowCustomView(view, callback)
            }
        }
    }

    /**
     * Rebuilds the WebView in place. A WebView whose renderer died cannot be
     * reused, so it is removed from the layout and a fresh one is constructed
     * with the same settings.
     */
    private fun recreateWebView() {
        val parent = web.parent as? android.view.ViewGroup ?: return
        val index = parent.indexOfChild(web)
        val params = web.layoutParams
        parent.removeView(web)
        web.destroy()
        web = WebView(this)
        web.layoutParams = params
        web.id = R.id.web
        parent.addView(web, index)
        setUpWebView()
        loadFilm()
    }

    // -- loading -------------------------------------------------------------

    private fun loadFilm() {
        spinner.visibility = View.VISIBLE
        serverPort = ServerHub.ensure(this)
        val query = prefs.query()
        // Built through ServerHub rather than assembled here: when sharing is on
        // the server insists on the share prefix for every request, and a
        // hardcoded loopback URL would be answered with a 403.
        val served = ServerHub.url("/index.html$query")
        val url = if (serverPort > 0 && served != null) {
            served
        } else {
            Diag.add("player", "内建服务器不可用（" + (ServerHub.lastError ?: "?") +
                    "），改用 file:// 后备")
            "file:///android_asset/web/index.html$query"
        }
        currentUrl = url
        web.loadUrl(url)
    }

    private fun showError(detail: String) {
        failed = true
        spinner.visibility = View.GONE
        errorBox.visibility = View.VISIBLE
        tvError.text = getString(R.string.player_error, detail)
        Diag.add("player", "主框架载入失败: $detail")
    }

    // -- menu ----------------------------------------------------------------

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_RELOAD, 0, getString(R.string.player_reload))
        popup.menu.add(0, MENU_QUALITY, 1, getString(R.string.player_quality))
        popup.menu.add(0, MENU_UI, 2, getString(R.string.player_hide_ui))
        popup.menu.add(0, MENU_BROWSER, 3, getString(R.string.player_open_in_browser))
        popup.menu.add(0, MENU_LOG, 4, getString(R.string.player_log))
        popup.menu.add(0, MENU_EXIT, 5, getString(R.string.player_exit))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RELOAD -> {
                    loadFilm()
                    true
                }
                MENU_QUALITY -> {
                    cycleQuality()
                    true
                }
                MENU_UI -> {
                    // The overlay owns the UI toggle; ask it rather than reaching
                    // into the page's own class names from here.
                    web.evaluateJavascript(
                        "window.DA && window.DA.poke && window.DA.poke();" +
                            "document.body.classList.toggle('hide-ui');",
                        null,
                    )
                    true
                }
                MENU_BROWSER -> {
                    openInBrowser()
                    true
                }
                MENU_LOG -> {
                    showLog()
                    true
                }
                MENU_EXIT -> {
                    finish()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun cycleQuality() {
        val next = when (prefs.quality) {
            Prefs.QUALITY_AUTO -> Prefs.QUALITY_LOW
            Prefs.QUALITY_LOW -> Prefs.QUALITY_HIGH
            else -> Prefs.QUALITY_AUTO
        }
        prefs.quality = next
        Toast.makeText(this, getString(R.string.player_quality) + "：" +
                Prefs.qualityLabel(this, next), Toast.LENGTH_SHORT).show()
        loadFilm()
    }

    private fun openInBrowser() {
        val port = ServerHub.ensure(this)
        val url = ServerHub.url()
        if (port <= 0 || url == null) {
            Toast.makeText(this, getString(R.string.player_error,
                "内建服务器未能启动"), Toast.LENGTH_LONG).show()
            return
        }
        // The browser cannot reach our activity's process unless it keeps
        // running, so the server is handed to a foreground service first.
        AssetServerService.start(this)
        startActivity(Intent(this, ServeActivity::class.java).putExtra(ServeActivity.EXTRA_URL, url))
    }

    private fun showLog() {
        // Named `body` rather than `text`: inside the TextView's `apply` block a
        // local called `text` would be shadowed by the view's own property.
        val body = Diag.dump().ifBlank { "（暂无日志）" }
        val view = android.widget.ScrollView(this).apply {
            val tv = android.widget.TextView(this@PlayerActivity).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 11f
                setTextIsSelectable(true)
                setPadding(40, 40, 40, 40)
                text = body
            }
            addView(tv)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.log_title, Diag.count()))
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.log_copy) { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("world.execute(me) log", body),
                )
                Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // -- window --------------------------------------------------------------

    private fun applyOrientation() {
        requestedOrientation = if (prefs.lockLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        web.onResume()
        // Coming back from an external browser: the page kept running, but a
        // paused audio element needs a nudge to keep in step with the canvas.
        web.evaluateJavascript(
            "(function(){try{var a=document.getElementById('audio');" +
                "if(a&&!a.paused)return;var app=window.EM&&window.EM.__app;" +
                "if(app&&app.now().running)app.play();}catch(e){}})();",
            null,
        )
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web.removeJavascriptInterface("AndroidHost")
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web.destroy()
        // The server is deliberately left running: if the user reached the film
        // through a browser, that browser is still showing it. ServerHub is
        // stopped when the process is torn down or from the serve screen.
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        goImmersive()
    }

    // -- native bridge -------------------------------------------------------

    /**
     * The page's only view of the native side. Everything here is called from
     * the WebView's JavaScript thread, so nothing touches views directly.
     */
    inner class AndroidHost {

        @JavascriptInterface
        fun onReady() {
            Diag.add("bridge", "页面已就绪，内建服务器端口 $serverPort")
        }

        @JavascriptInterface
        fun onLog(message: String?) {
            message ?: return
            if (message.length > 4000) {
                Diag.add("js", message.substring(0, 4000) + "…")
            } else {
                Diag.add("js", message)
            }
        }

        @JavascriptInterface
        fun exit() {
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun port(): Int = serverPort
    }

    private companion object {
        const val MENU_RELOAD = 1
        const val MENU_QUALITY = 2
        const val MENU_UI = 3
        const val MENU_BROWSER = 4
        const val MENU_LOG = 5
        const val MENU_EXIT = 6
    }
}
