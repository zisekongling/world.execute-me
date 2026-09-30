package com.worldexecute.viewer

import android.app.Application
import android.os.Build

/**
 * Unpacks the film before anyone asks for it.
 *
 * The 8.7 MB of assets are copied from the APK to internal storage on first run.
 * Doing that when the play button is pressed would put a visible pause between
 * the tap and the film; doing it here, off the main thread, means the copy is
 * usually finished by the time the user has chosen an engine. The work is
 * idempotent and guarded by the manifest id, so a second call is a file read.
 */
class ViewerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        Diag.add("app", "启动 v" + BuildConfig.VERSION_NAME +
                " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")" +
                " · " + Build.MANUFACTURER + " " + Build.MODEL)

        // Never keep the whole log in logcat only: on a phone, a bug report is a
        // screenshot, and the log screen has to have something in it.
        //
        // Only the unpacking happens here. The server is left to whoever first
        // needs it — starting a listening socket for a user who never leaves the
        // launcher screen would be work for nobody.
        Thread({
            try {
                val root = WebAssets.ensure(this)
                Diag.add("app", "影片已解包：" + root.absolutePath)
            } catch (e: Exception) {
                Diag.add("app", "预解包失败: " + e.message)
            }
        }, "asset-warmup").start()
    }
}
