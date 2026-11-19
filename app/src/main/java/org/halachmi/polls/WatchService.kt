package org.halachmi.polls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Keeps the process, and with it the widget host, alive: a foreground service with a
 * minimum-importance notification (no sound, and on most phones no status-bar icon).
 *
 * A notification listener would keep the process bound without a notification of its own, but
 * Play Protect's enhanced fraud protection refuses to install a sideloaded app that declares one,
 * and this app is installed from a GitHub release.
 *
 * The widget reports its own changes; the minute tick re-reads as a safety net and runs the
 * [HealthMonitor] checks that a read cannot report itself.
 */
class WatchService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            WidgetWatcher.start(this@WatchService)
            HealthMonitor.check(this@WatchService)
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_running), NotificationManager.IMPORTANCE_MIN).apply {
                    description = getString(R.string.channel_running_description)
                    setShowBadge(false)
                },
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_poll)
            .setContentTitle(getString(R.string.waiting_for_polls))
            .setContentIntent(tap)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        running = true
        main.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        main.removeCallbacks(tick)
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WatchService"
        private const val CHANNEL = "running"
        private const val NOTIFICATION_ID = 7
        private const val TICK_MS = 60_000L

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, WatchService::class.java))
            } catch (e: Exception) {
                // Not allowed from the background right now; the app's screen or the next boot starts it.
                Log.w(TAG, "start refused", e)
            }
        }
    }
}

/** Starts the watch after a reboot and after the app is updated. */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> WatchService.start(context)
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                EventLog.add(context, context.getString(R.string.log_updated, Updater.installedVersion(context).toInt()))
                WatchService.start(context)
            }
        }
    }
}
