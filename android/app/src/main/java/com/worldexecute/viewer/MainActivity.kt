package com.worldexecute.viewer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * The launcher: choose an engine, and choose how the film should be rendered.
 *
 * This screen exists because the two engines are genuinely different experiences
 * and the choice is worth one deliberate tap rather than a hidden setting:
 * the WebView path is instant and offline, while the browser path hands the
 * rendering to an engine we do not control and needs a local server to feed it.
 * Everything on this screen is persisted, so the second launch is one tap.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var rgEngine: RadioGroup
    private lateinit var rgQuality: RadioGroup
    private lateinit var rowBrowser: ViewGroup
    private lateinit var tvBrowser: TextView
    private lateinit var tvFooter: TextView
    private lateinit var swLandscape: MaterialSwitch
    private lateinit var swKeepOn: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)

        rgEngine = findViewById(R.id.rgEngine)
        rgQuality = findViewById(R.id.rgQuality)
        rowBrowser = findViewById(R.id.rowBrowser)
        tvBrowser = findViewById(R.id.tvBrowser)
        tvFooter = findViewById(R.id.tvFooter)
        swLandscape = findViewById(R.id.swLandscape)
        swKeepOn = findViewById(R.id.swKeepOn)

        // -- engine ----------------------------------------------------------
        rgEngine.check(
            if (prefs.engine == Prefs.ENGINE_BROWSER) R.id.rbBrowser else R.id.rbWebView,
        )
        rgEngine.setOnCheckedChangeListener { _, checked ->
            prefs.engine = if (checked == R.id.rbBrowser) {
                Prefs.ENGINE_BROWSER
            } else {
                Prefs.ENGINE_WEBVIEW
            }
            // Picking the browser engine and having no browser chosen yet is a
            // dead end; offer the first one instead of failing at start time.
            if (prefs.engine == Prefs.ENGINE_BROWSER &&
                Browsers.find(this, prefs.browserPackage) == null
            ) {
                Browsers.list(this).firstOrNull()?.let { adopt(it) }
            }
            syncBrowserRow()
        }

        findViewById<View>(R.id.btnPickBrowser).setOnClickListener { pickBrowser() }
        rowBrowser.setOnClickListener { pickBrowser() }

        // -- quality ---------------------------------------------------------
        rgQuality.check(
            when (prefs.quality) {
                Prefs.QUALITY_LOW -> R.id.rbLow
                Prefs.QUALITY_HIGH -> R.id.rbHigh
                else -> R.id.rbAuto
            },
        )
        rgQuality.setOnCheckedChangeListener { _, checked ->
            prefs.quality = when (checked) {
                R.id.rbLow -> Prefs.QUALITY_LOW
                R.id.rbHigh -> Prefs.QUALITY_HIGH
                else -> Prefs.QUALITY_AUTO
            }
            syncFooter()
        }

        // -- options ---------------------------------------------------------
        swLandscape.isChecked = prefs.lockLandscape
        swLandscape.setOnCheckedChangeListener { _, checked -> prefs.lockLandscape = checked }
        swKeepOn.isChecked = prefs.keepScreenOn
        swKeepOn.setOnCheckedChangeListener { _, checked -> prefs.keepScreenOn = checked }

        findViewById<View>(R.id.btnStart).setOnClickListener { start() }

        syncBrowserRow()
        syncFooter()
    }

    override fun onResume() {
        super.onResume()
        // A browser may have been uninstalled while we were away.
        syncBrowserRow()
        syncFooter()
    }

    // -- browser picker ------------------------------------------------------

    private fun adopt(browser: Browsers.Browser) {
        prefs.browserPackage = browser.packageName
        prefs.browserLabel = browser.label
    }

    private fun syncBrowserRow() {
        val isBrowser = prefs.engine == Prefs.ENGINE_BROWSER
        rowBrowser.visibility = if (isBrowser) View.VISIBLE else View.GONE

        val browser = Browsers.find(this, prefs.browserPackage)
        tvBrowser.text = when {
            browser != null -> browser.label
            prefs.browserPackage.isNullOrBlank() -> getString(R.string.browser_none)
            else -> getString(R.string.browser_gone, prefs.browserLabel ?: "?")
        }
    }

    /**
     * The browser list, with icons and a one-line note about the engine.
     *
     * The note is the only information that helps a choice here — a user seeing
     * "Firefox" and "Chrome" side by side has no way to know that only one of
     * them is a Chromium build of the engine the film was written against.
     */
    private fun pickBrowser() {
        val candidates = Browsers.list(this)
        if (candidates.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.browser_dialog_title)
                .setMessage(R.string.browser_empty)
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }

        val current = candidates.indexOfFirst { it.packageName == prefs.browserPackage }
        val adapter = object : android.widget.BaseAdapter() {
            private val inflater = LayoutInflater.from(this@MainActivity)

            override fun getCount(): Int = candidates.size
            override fun getItem(position: Int): Any = candidates[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = convertView ?: inflater.inflate(R.layout.row_browser, parent, false)
                val browser = candidates[position]
                row.findViewById<TextView>(R.id.tvLabel).text = browser.label
                row.findViewById<TextView>(R.id.tvSub).text =
                    if (position == current) {
                        getString(R.string.browser_current_note, browser.note)
                    } else {
                        browser.note
                    }
                row.findViewById<ImageView>(R.id.ivIcon).setImageDrawable(browser.icon)
                return row
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.browser_dialog_title)
            .setAdapter(adapter) { dialog, which ->
                adopt(candidates[which])
                syncBrowserRow()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    // -- start ---------------------------------------------------------------

    private fun start() {
        if (prefs.engine == Prefs.ENGINE_WEBVIEW) {
            startActivity(Intent(this, PlayerActivity::class.java))
            return
        }

        val browser = Browsers.find(this, prefs.browserPackage)
        if (browser == null) {
            // Nothing picked, or it disappeared: ask rather than guess.
            val candidates = Browsers.list(this)
            if (candidates.isEmpty()) {
                Toast.makeText(this, R.string.browser_empty, Toast.LENGTH_LONG).show()
                return
            }
            adopt(candidates.first())
        }

        // The server has to outlive this activity, so a foreground service owns
        // it before the browser is brought to the front.
        AssetServerService.start(this)
        ensureNotificationPermission()
        startActivity(Intent(this, ServeActivity::class.java))
    }

    /**
     * API 33 gates the foreground-service notification behind a runtime
     * permission. Denying it does not break playback — the service still runs —
     * so the refusal is only logged, never insisted on.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            Diag.add("main", "通知权限: " + if (granted) "已授予" else "被拒绝（服务仍会运行）")
        }
    }

    // -- footer --------------------------------------------------------------

    /** Build facts, in the film's own monospace voice. */
    private fun syncFooter() {
        val id = WebAssets.packagedId(this)?.take(12) ?: "未知"
        tvFooter.typeface = Typeface.MONOSPACE
        tvFooter.text = buildString {
            append("影片资源 ").append(id).append('\n')
            append("质量 ").append(Prefs.qualityLabel(this@MainActivity, prefs.quality))
                .append(" · 引擎 ").append(
                    if (prefs.engine == Prefs.ENGINE_BROWSER) "其他浏览器" else "系统 WebView",
                ).append('\n')
            append("内建服务器端口 ").append(prefs.serverPort)
        }
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 101
    }
}
