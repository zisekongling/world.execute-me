package com.worldexecute.viewer

import android.content.Context

/**
 * Everything the launcher screen can change.
 *
 * Deliberately plain [Context.getSharedPreferences] rather than DataStore: the
 * whole settings surface is six values read once at startup, and pulling in a
 * coroutine-backed store for that would be more machinery than the app has.
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("viewer", Context.MODE_PRIVATE)

    /** [ENGINE_WEBVIEW] or [ENGINE_BROWSER]. */
    var engine: String
        get() = sp.getString(KEY_ENGINE, ENGINE_WEBVIEW) ?: ENGINE_WEBVIEW
        set(v) = sp.edit().putString(KEY_ENGINE, v).apply()

    /** Package name of the browser chosen for [ENGINE_BROWSER], or null for "ask". */
    var browserPackage: String?
        get() = sp.getString(KEY_BROWSER_PKG, null)
        set(v) = sp.edit().putString(KEY_BROWSER_PKG, v).apply()

    var browserLabel: String?
        get() = sp.getString(KEY_BROWSER_LABEL, null)
        set(v) = sp.edit().putString(KEY_BROWSER_LABEL, v).apply()

    /** [QUALITY_AUTO], [QUALITY_LOW] or [QUALITY_HIGH]. */
    var quality: String
        get() = sp.getString(KEY_QUALITY, QUALITY_AUTO) ?: QUALITY_AUTO
        set(v) = sp.edit().putString(KEY_QUALITY, v).apply()

    var lockLandscape: Boolean
        get() = sp.getBoolean(KEY_LANDSCAPE, true)
        set(v) = sp.edit().putBoolean(KEY_LANDSCAPE, v).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean(KEY_KEEP_ON, true)
        set(v) = sp.edit().putBoolean(KEY_KEEP_ON, v).apply()

    /** Preferred port for the built-in server; 0 means "whatever is free". */
    var serverPort: Int
        get() = sp.getInt(KEY_PORT, DEFAULT_PORT)
        set(v) = sp.edit().putInt(KEY_PORT, v).apply()

    /**
     * Whether the server should be listening on the network as well as on
     * loopback. Off unless asked for: it is the one setting here that changes
     * who can reach this device.
     *
     * The share token is deliberately *not* persisted. Restarting the app
     * therefore restarts the share under a new address, which is the right
     * default for a secret — and the switch, not the token, is what the user
     * meant to keep.
     */
    var lanShare: Boolean
        get() = sp.getBoolean(KEY_LAN_SHARE, false)
        set(v) = sp.edit().putBoolean(KEY_LAN_SHARE, v).apply()

    /**
     * The query string handed to the film.
     *
     * The overlay reads `perf` and `dpr` from the URL before any engine script
     * runs, because the engine reads the reduced-motion media query and the
     * device pixel ratio exactly once, at parse time. Passing them as URL
     * parameters is therefore the only way to change them from outside without
     * editing the film.
     */
    fun query(): String = when (quality) {
        QUALITY_LOW -> "?perf=low"
        QUALITY_HIGH -> "?dpr=2"
        else -> ""
    }

    companion object {
        const val ENGINE_WEBVIEW = "webview"
        const val ENGINE_BROWSER = "browser"

        const val QUALITY_AUTO = "auto"
        const val QUALITY_LOW = "low"
        const val QUALITY_HIGH = "high"

        const val DEFAULT_PORT = 47821

        private const val KEY_ENGINE = "engine"
        private const val KEY_BROWSER_PKG = "browser.pkg"
        private const val KEY_BROWSER_LABEL = "browser.label"
        private const val KEY_QUALITY = "quality"
        private const val KEY_LANDSCAPE = "landscape"
        private const val KEY_KEEP_ON = "keepOn"
        private const val KEY_PORT = "port"
        private const val KEY_LAN_SHARE = "lanShare"

        fun qualityLabel(context: Context, quality: String): String = when (quality) {
            QUALITY_LOW -> context.getString(R.string.quality_low)
            QUALITY_HIGH -> context.getString(R.string.quality_high)
            else -> context.getString(R.string.quality_auto)
        }
    }
}
