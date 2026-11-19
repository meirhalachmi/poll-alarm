package org.halachmi.polls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * The ring: a full-screen notification that shows [AlertActivity] over the lock screen (and a
 * heads-up while the phone is in use), on the alarm stream so it is heard on silent.
 *
 * Three notifications per chat:
 * - the ring, on the phone only: insistent (its sound loops), withdrawn after [RING_MS];
 * - a pulse for a paired watch: an insistent notification reaches a watch as a single vibration,
 *   so a vibrate-only notification is re-posted every [PULSE_MS] until the ring ends or is answered;
 * - a quiet copy that stays until the chat is opened, so a missed ring is still there.
 */
object PollAlarm {
    const val EXTRA_CHAT = "chat"
    const val EXTRA_QUESTION = "question"
    const val EXTRA_OPEN = "open"

    private const val RING_CHANNEL = "poll_ring_v1"
    private const val QUIET_CHANNEL = "poll_quiet_v1"
    private const val PULSE_CHANNEL = "poll_pulse_v1"
    private const val RING_MS = 60_000L
    private const val PULSE_MS = 2_000L

    private val main = Handler(Looper.getMainLooper())
    private val pulses = HashMap<String, Runnable>()

    private fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(RING_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(RING_CHANNEL, context.getString(R.string.channel_ring), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_ring_description)
                    val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    setSound(
                        sound,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 800, 600, 800, 600, 800)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
        if (nm.getNotificationChannel(PULSE_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(PULSE_CHANNEL, context.getString(R.string.channel_pulse), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_pulse_description)
                    setSound(null, null)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 700)
                },
            )
        }
        if (nm.getNotificationChannel(QUIET_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(QUIET_CHANNEL, context.getString(R.string.channel_waiting), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.channel_waiting_description)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }

    private fun ringId(chat: String) = chat.hashCode() and 0x3fffffff
    private fun quietId(chat: String) = ringId(chat) or 0x40000000
    private fun pulseId(chat: String) = ringId(chat) xor 0x20000000

    private fun alertIntent(context: Context, chat: String, question: String, open: Boolean): Intent =
        Intent(context, AlertActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_CHAT, chat)
            .putExtra(EXTRA_QUESTION, question)
            .putExtra(EXTRA_OPEN, open)

    private fun pendingAlert(context: Context, chat: String, question: String, open: Boolean): PendingIntent =
        PendingIntent.getActivity(
            context,
            ringId(chat) * 2 + (if (open) 1 else 0),
            alertIntent(context, chat, question, open),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Rings for [count] new polls in [chat], showing the newest [question]. */
    fun ring(context: Context, chat: String, question: String, count: Int) {
        createChannels(context)
        val title = context.getString(R.string.poll_in, chat)
        val body = when {
            question.isEmpty() -> context.getString(R.string.tap_to_open)
            count > 1 -> context.getString(R.string.poll_more, question, count - 1)
            else -> question
        }
        val show = pendingAlert(context, chat, question, open = false)
        val open = pendingAlert(context, chat, question, open = true)
        val ring = Notification.Builder(context, RING_CHANNEL)
            .setSmallIcon(R.drawable.ic_poll)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(show, true)
            .setContentIntent(show)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.open_poll), open).build())
            .setAutoCancel(true)
            .setTimeoutAfter(RING_MS)
            .setLocalOnly(true)
            .build()
        ring.flags = ring.flags or Notification.FLAG_INSISTENT
        val quiet = Notification.Builder(context, QUIET_CHANNEL)
            .setSmallIcon(R.drawable.ic_poll)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val pulse = Notification.Builder(context, PULSE_CHANNEL)
            .setSmallIcon(R.drawable.ic_poll)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open)
            .build()
        val nm = context.getSystemService(NotificationManager::class.java)
        try {
            nm.notify(quietId(chat), quiet)
            nm.notify(ringId(chat), ring)
        } catch (_: SecurityException) {
            // Notifications not allowed: the main screen shows that step as missing.
            return
        }
        startPulses(context.applicationContext, chat, pulse)
    }

    /** Re-posts [pulse] (cancel, then post: an update alone may not alert again) until [RING_MS]. */
    private fun startPulses(context: Context, chat: String, pulse: Notification) {
        stopPulses(context, chat)
        val nm = context.getSystemService(NotificationManager::class.java)
        val until = System.currentTimeMillis() + RING_MS
        val task = object : Runnable {
            override fun run() {
                nm.cancel(pulseId(chat))
                if (System.currentTimeMillis() >= until) {
                    pulses.remove(chat)
                    return
                }
                try {
                    nm.notify(pulseId(chat), pulse)
                } catch (_: SecurityException) {
                    return
                }
                main.postDelayed(this, PULSE_MS)
            }
        }
        pulses[chat] = task
        main.post(task)
    }

    private fun stopPulses(context: Context, chat: String) {
        pulses.remove(chat)?.let { main.removeCallbacks(it) }
        context.getSystemService(NotificationManager::class.java).cancel(pulseId(chat))
    }

    /** Stops the ringing; [all] also clears the quiet copy (the chat was opened). */
    fun stop(context: Context, chat: String, all: Boolean) {
        stopPulses(context, chat)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(ringId(chat))
        if (all) nm.cancel(quietId(chat))
    }

    fun canUseFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
}
