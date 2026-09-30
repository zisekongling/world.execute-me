package com.worldexecute.viewer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the built-in server alive while another app is on screen.
 *
 * In browser mode our activity is not the one being looked at — the browser is —
 * which makes this process a background process, and a background process with
 * nine megabytes of unpacked assets is exactly what the low-memory killer is
 * for. A foreground service with a notification is the honest way to say "this
 * is still running, and here is how to stop it".
 *
 * In WebView mode none of that applies, and [ServerHub] is used directly.
 */
class AssetServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(getString(R.string.notif_starting))
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )

        Thread({
            val port = ServerHub.ensure(applicationContext)
            // Re-read the share state rather than trusting the intent: this same
            // path is re-entered, with no extra action, purely to repaint the
            // notification after the user flips the share switch.
            val lan = ServerHub.lanUrl("/")
            val text = when {
                port <= 0 -> getString(R.string.notif_failed, ServerHub.lastError ?: "未知原因")
                lan != null -> getString(R.string.notif_ready_lan, lan)
                else -> getString(R.string.notif_ready,
                    ServerHub.url("/") ?: "http://127.0.0.1:$port/")
            }
            Handler(Looper.getMainLooper()).post {
                notify(buildNotification(text))
                sendBroadcast(Intent(ACTION_STATE).setPackage(packageName)
                    .putExtra(EXTRA_PORT, port))
            }
        }, "asset-server-start").start()

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        ServerHub.stop()
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_PORT, 0))
        super.onDestroy()
    }

    // -- notification --------------------------------------------------------

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ServeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AssetServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Diag.add("service", "通知失败: " + e.message)
        }
    }

    companion object {
        const val ACTION_STOP = "com.worldexecute.viewer.STOP_SERVER"
        const val ACTION_STATE = "com.worldexecute.viewer.SERVER_STATE"
        const val EXTRA_PORT = "port"
        const val CHANNEL_ID = "asset-server"
        const val NOTIFICATION_ID = 4711

        /** True while the foreground service owns the server. */
        @Volatile
        var running: Boolean = false
            private set

        /**
         * Starts the service, or — when it is already running — re-enters
         * `onStartCommand` to re-read the server's state and repaint the
         * notification. That second use is how the LAN share switch updates the
         * notification without needing a second entry point.
         */
        fun start(context: Context) {
            val intent = Intent(context, AssetServerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AssetServerService::class.java))
        }
    }
}
