package org.halachmi.polls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.halachmi.polls.core.Polls
import org.halachmi.polls.core.Polls.Problem

/**
 * Says out loud when the app no longer understands WhatsApp's widget.
 *
 * The widget is not an API: if WhatsApp changes it, the app would keep running and simply never
 * ring. So a broken read raises a notification and a red line on the main screen, with a button
 * that shares a report (the problem, the device, and the last read's view tree) with the developer.
 *
 * A problem is: [BAD_READS] settled reads in a row that [Polls.diagnose] rejects; a widget that was
 * bound and no longer is; or no good read for [STALE_MS]. A good read clears it. What this cannot
 * see: if WhatsApp stopped marking polls with "📊", rows would still parse and nothing would ring.
 */
object HealthMonitor {
    private const val CHANNEL = "problems"
    private const val NOTIFICATION_ID = 31
    private const val BAD_READS = 3
    private const val STALE_MS = 3L * 3600 * 1000
    private const val KEY_EVER_BOUND = "health_ever_bound"
    private const val KEY_GOOD_AT = "health_good_at"
    private const val KEY_BAD = "health_bad_reads"
    private const val KEY_PROBLEM = "health_problem"
    private const val KEY_SINCE = "health_since"
    private const val KEY_DUMP = "health_raw"
    private const val REPORT_DUMP_CHARS = 6000

    private fun prefs(context: Context) = WidgetWatcher.prefs(context)

    fun onBound(context: Context) {
        prefs(context).edit().putBoolean(KEY_EVER_BOUND, true).apply()
    }

    /** After every settled read; [lists] is -1 when the read threw. */
    fun afterRead(context: Context, lists: Int, rows: Int, parsed: Int, dump: String) {
        val p = prefs(context)
        val problem = Polls.diagnose(lists, rows, parsed)
        if (problem == null) {
            p.edit()
                .putLong(KEY_GOOD_AT, System.currentTimeMillis())
                .putInt(KEY_BAD, 0)
                .putString(KEY_DUMP, dump)
                .putBoolean(KEY_EVER_BOUND, true)
                .apply()
            clear(context)
            return
        }
        val bad = p.getInt(KEY_BAD, 0) + 1
        p.edit().putInt(KEY_BAD, bad).putString(KEY_DUMP, dump).apply()
        if (bad >= BAD_READS) raise(context, problem)
    }

    /** The problems a read cannot report itself; from the service's periodic tick. */
    fun check(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_EVER_BOUND, false)) return
        if (WidgetWatcher.widgetInfo(context) == null) {
            raise(context, Problem.UNBOUND)
            return
        }
        if (problem(context) == Problem.UNBOUND) clear(context)
        val good = p.getLong(KEY_GOOD_AT, 0L)
        if (good > 0 && System.currentTimeMillis() - good > STALE_MS) raise(context, Problem.STALE)
    }

    fun problem(context: Context): Problem? =
        prefs(context).getString(KEY_PROBLEM, null)?.let { name -> Problem.values().firstOrNull { it.name == name } }

    fun since(context: Context): Long = prefs(context).getLong(KEY_SINCE, 0L)

    fun describe(context: Context, problem: Problem): String = context.getString(
        when (problem) {
            Problem.UNBOUND -> R.string.problem_unbound
            Problem.NO_LIST -> R.string.problem_no_list
            Problem.UNREADABLE -> R.string.problem_unreadable
            Problem.FAILING -> R.string.problem_failing
            Problem.STALE -> R.string.problem_stale
        },
    )

    private fun raise(context: Context, problem: Problem) {
        if (problem(context) == problem) return
        prefs(context).edit().putString(KEY_PROBLEM, problem.name).putLong(KEY_SINCE, System.currentTimeMillis()).apply()
        EventLog.add(context, context.getString(R.string.log_problem, problem.name))
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_problems), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.channel_problems_description)
                },
            )
        }
        val tap = PendingIntent.getActivity(
            context, 30, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = describe(context, problem)
        try {
            nm.notify(
                NOTIFICATION_ID,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_poll)
                    .setContentTitle(context.getString(R.string.problem_title, context.getString(R.string.app_name)))
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setContentIntent(tap)
                    .build(),
            )
        } catch (_: SecurityException) {
        }
    }

    private fun clear(context: Context) {
        if (problem(context) == null) return
        prefs(context).edit().remove(KEY_PROBLEM).remove(KEY_SINCE).apply()
        EventLog.add(context, context.getString(R.string.log_problem_cleared))
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /** A plain-text report for the developer. Row texts in the dump are cut to 40 characters. */
    fun report(context: Context): String = buildString {
        val p = prefs(context)
        append("Poll Alarm problem report\n")
        append("Problem: ").append(problem(context)?.name ?: "none").append('\n')
        append("Version: ").append(Updater.installedVersion(context))
            .append(" · Android ").append(Build.VERSION.RELEASE)
            .append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("Widget: ").append(WidgetWatcher.widgetInfo(context)?.loadLabel(context.packageManager) ?: "not bound").append('\n')
        append("Bad reads in a row: ").append(p.getInt(KEY_BAD, 0)).append('\n')
        append("\nLast read:\n").append(p.getString(KEY_DUMP, "").orEmpty().take(REPORT_DUMP_CHARS))
    }
}
