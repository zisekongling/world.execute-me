package com.worldexecute.viewer

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * The screen that owns the server while a browser is doing the rendering.
 *
 * This is a waiting room, not a player: the film is on screen in another app.
 * What it is for is the things the user cannot otherwise do — see that the
 * server is up and on which port, re-open the browser if they swiped it away,
 * hand the film to another device on the network, and stop the server (and its
 * notification) when they are done.
 */
class ServeActivity : AppCompatActivity() {

    private lateinit var tvUrl: TextView
    private lateinit var tvBrowser: TextView
    private lateinit var btnOpen: View
    private lateinit var btnStop: View
    private lateinit var swShare: MaterialSwitch
    private lateinit var tvShareHint: TextView
    private lateinit var sharePanel: View
    private lateinit var imgQr: ImageView
    private lateinit var tvQrNote: TextView
    private lateinit var tvLanUrl: TextView
    private lateinit var btnCopyShare: View

    /** The address openable on this device. */
    private var url: String = ""

    /** The address openable on another device, or null when not sharing. */
    private var shareUrl: String? = null

    /**
     * Set while the code, rather than the user, moves the switch. Without it the
     * first [refresh] after a programmatic `isChecked` reads as a fresh request
     * to share and starts a second server.
     */
    private var movingSwitch = false

    /** The URL the current bitmap encodes, so a refresh does not rebuild it. */
    private var qrFor: String? = null

    /**
     * The service starts the server on a worker thread, so this screen can be
     * on top before there is a port to show. Watching for the service's own
     * state broadcast fills the address in as soon as it is real, instead of
     * leaving the user staring at "starting…".
     */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AssetServerService.ACTION_STATE) return
            refresh()
        }
    }

    private var receiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_serve)

        // A browser hand-off is inherently a landscape film on a phone; asking
        // for it here means the browser comes back to a sensible orientation if
        // the user returns to this screen.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        tvUrl = findViewById(R.id.tvUrl)
        tvBrowser = findViewById(R.id.tvBrowser)
        btnOpen = findViewById(R.id.btnOpen)
        btnStop = findViewById(R.id.btnStop)
        swShare = findViewById(R.id.swShare)
        tvShareHint = findViewById(R.id.tvShareHint)
        sharePanel = findViewById(R.id.sharePanel)
        imgQr = findViewById(R.id.imgQr)
        tvQrNote = findViewById(R.id.tvQrNote)
        tvLanUrl = findViewById(R.id.tvLanUrl)
        btnCopyShare = findViewById(R.id.btnCopyShare)

        url = intent?.getStringExtra(EXTRA_URL) ?: ServerHub.url().orEmpty()

        findViewById<View>(R.id.btnCopy).setOnClickListener {
            copy(url, getString(R.string.serve_copied))
        }
        btnCopyShare.setOnClickListener {
            shareUrl?.let { copy(it, getString(R.string.share_copied)) }
        }
        btnOpen.setOnClickListener { openInBrowser() }
        btnStop.setOnClickListener { confirmStop() }
        swShare.setOnCheckedChangeListener { _, checked -> onShareToggled(checked) }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                stateReceiver,
                IntentFilter(AssetServerService.ACTION_STATE),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver)
            } catch (e: Exception) {
                Diag.add("serve", "注销接收器失败: " + e.message)
            }
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        // Only hold the display on while the QR code is actually on screen:
        // once it is off, this screen is a waiting room nobody is looking at.
        keepScreenOn(false)
        super.onPause()
    }

    // -- state ---------------------------------------------------------------

    private fun refresh() {
        val prefs = Prefs(this)

        if (ServerHub.isRunning && ServerHub.port > 0) {
            url = ServerHub.url().orEmpty()
            tvUrl.text = url
            btnOpen.isEnabled = true
        } else {
            // Either the service is still starting, or it failed and left a note.
            if (url.isNotBlank()) {
                tvUrl.text = url
            } else {
                tvUrl.text = getString(R.string.notif_failed, ServerHub.lastError ?: "未知原因")
            }
            btnOpen.isEnabled = false
        }

        val browser = Browsers.find(this, prefs.browserPackage)
        tvBrowser.text = when {
            browser != null -> getString(R.string.serve_browser_line, browser.label, browser.packageName)
            prefs.browserPackage.isNullOrBlank() -> getString(R.string.browser_none)
            else -> getString(R.string.browser_gone, prefs.browserLabel ?: prefs.browserPackage!!)
        }

        renderShare(prefs)

        // The preference outlives the server: a process death or a stopped
        // service leaves the switch in the user's chosen position, and this is
        // what makes the server match it again.
        if (prefs.lanShare && !ServerHub.sharing) {
            switchSharing(on = true, persist = false)
        }
    }

    private fun renderShare(prefs: Prefs) {
        movingSwitch = true
        swShare.isChecked = ServerHub.sharing
        movingSwitch = false

        // The quality query travels with the QR: the other device would
        // otherwise get the automatic default rather than what was chosen here.
        val lan = if (ServerHub.sharing) {
            ServerHub.lanUrl("/index.html" + prefs.query())
        } else {
            null
        }
        shareUrl = lan

        if (lan == null) {
            sharePanel.visibility = View.GONE
            tvShareHint.setText(R.string.share_hint)
            keepScreenOn(false)
            return
        }

        sharePanel.visibility = View.VISIBLE
        tvShareHint.setText(R.string.share_hint_on)
        tvLanUrl.text = lan
        keepScreenOn(true)

        if (qrFor == lan && imgQr.drawable != null) return
        qrFor = lan

        val bitmap = qrBitmap(lan)
        if (bitmap == null) {
            imgQr.setImageDrawable(null)
            tvQrNote.setText(R.string.share_qr_failed)
        } else {
            imgQr.setImageBitmap(bitmap)
            tvQrNote.setText(R.string.share_scan_note)
        }
    }

    /**
     * The only Android-specific part of drawing a QR code: everything that
     * decides what the pixels are lives in [QrCode], where a JVM test can read
     * it back.
     */
    private fun qrBitmap(text: String): Bitmap? {
        // Matched to the view's 168dp so the bitmap is never scaled: a QR code
        // resampled with antialiasing is a QR code with soft edges.
        val size = (QR_DP * resources.displayMetrics.density).toInt().coerceIn(200, 720)
        val modules = QrCode.matrix(text, size) ?: return null
        return try {
            Bitmap.createBitmap(
                QrCode.pixels(
                    modules,
                    ContextCompat.getColor(this, R.color.qr_dark),
                    ContextCompat.getColor(this, R.color.qr_light),
                ),
                modules.width,
                modules.height,
                Bitmap.Config.ARGB_8888,
            )
        } catch (e: Exception) {
            Diag.add("qr", "二维码位图创建失败: " + e.message)
            null
        }
    }

    // -- sharing -------------------------------------------------------------

    private fun onShareToggled(on: Boolean) {
        if (movingSwitch) return
        switchSharing(on, persist = true)
    }

    /**
     * Moves the server into [on] mode off the main thread — binding a socket and
     * unpacking nine megabytes is not something to do between two frames — then
     * repaints from whatever actually happened.
     *
     * [persist] is false only for the reconcile pass in [refresh], which is
     * restoring a choice the user already made and must not write it again.
     */
    private fun switchSharing(on: Boolean, persist: Boolean) {
        val prefs = Prefs(this)
        if (persist) prefs.lanShare = on
        swShare.isEnabled = false

        Thread({
            val ok = ServerHub.setSharing(applicationContext, on)
            // The address in the notification just changed; re-entering the
            // service is how it gets repainted.
            if (ok) AssetServerService.start(applicationContext)

            Handler(Looper.getMainLooper()).post {
                swShare.isEnabled = true
                if (!ok) {
                    // Roll the preference back rather than leave the switch
                    // claiming a share that does not exist.
                    prefs.lanShare = false
                    Toast.makeText(
                        this,
                        getString(R.string.share_failed,
                            ServerHub.lastError ?: getString(R.string.share_no_lan)),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        if (on) R.string.share_on else R.string.share_off,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                refresh()
            }
        }, "share-toggle").start()
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // -- actions -------------------------------------------------------------

    private fun copy(value: String, toast: String) {
        if (value.isBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("world.execute(me)", value))
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }

    private fun openInBrowser() {
        if (url.isBlank()) return
        val prefs = Prefs(this)
        val pkg = prefs.browserPackage
        val opened = if (!pkg.isNullOrBlank()) Browsers.open(this, pkg, url) else false
        if (!opened) {
            // Picked browser is gone, or none was ever picked: let the system ask.
            Browsers.openChooser(this, url)
        }
    }

    private fun confirmStop() {
        AlertDialog.Builder(this)
            .setTitle(R.string.serve_stop_confirm_title)
            .setMessage(R.string.serve_stop_confirm_body)
            .setNegativeButton(R.string.exit_no, null)
            .setPositiveButton(R.string.serve_stop) { _, _ ->
                AssetServerService.stop(this)
                ServerHub.stop()
                finish()
            }
            .show()
    }

    companion object {
        const val EXTRA_URL = "url"

        /** The QR view is 168dp square; the bitmap is generated to match. */
        private const val QR_DP = 168
    }
}
