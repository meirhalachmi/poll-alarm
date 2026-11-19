package org.halachmi.polls

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.database.DataSetObserver
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.Log
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.Adapter
import android.widget.AdapterView
import android.widget.RemoteViews
import android.widget.TextView
import org.halachmi.polls.core.ChatNames
import org.halachmi.polls.core.Polls
import org.halachmi.polls.core.WidgetRows
import org.json.JSONObject
import java.util.Collections
import java.util.WeakHashMap

/**
 * Hosts WhatsApp's home-screen widget inside this app and reads it.
 *
 * The app is an [AppWidgetHost], like a launcher: the user binds WhatsApp's widget to it once, and
 * from then on Android delivers the widget's updates here. The host view is never attached to a
 * window, so it is measured and laid out by hand before every read, and the rows of its list are
 * fetched through the list's adapter, visible or not.
 *
 * One host and one view serve the whole process: Android keeps a single listener per host id.
 * Every settled read goes to [Polls.check]; a new poll in a followed chat rings ([PollAlarm]), and
 * whether the read made sense goes to [HealthMonitor].
 */
object WidgetWatcher {
    const val REQ_BIND = 41
    const val REQ_CONFIGURE = 42
    val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")

    private const val TAG = "WidgetWatcher"
    private const val HOST_ID = 0x5011
    // The storage name of the first versions, kept so that updating keeps the binding and the chats.
    private const val PREFS = "poll_watch"
    private const val KEY_ID = "widget_id"
    private const val KEY_PENDING = "pending_id"
    private const val KEY_SEEN = "seen"
    private const val KEY_FOLLOW = "follow"
    private const val KEY_CHATS = "chats"
    private const val KEY_READ_AT = "read_at"
    private const val MAX_CHATS = 80
    private const val MAX_RETRIES = 4
    private const val RETRY_MS = 1_500L

    // A tall phone widget, so the whole list is laid out.
    private const val W_DP = 360
    private const val H_DP = 520

    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val readNow = Runnable { read() }
    private val watched: MutableSet<Adapter> = Collections.newSetFromMap(WeakHashMap<Adapter, Boolean>())
    private var host: Host? = null
    private var view: AppWidgetHostView? = null
    private var app: Context? = null
    private var reading = false
    private var retries = 0

    private class Host(context: Context, private val onUpdate: () -> Unit) : AppWidgetHost(context, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            WidgetView(context, onUpdate)
    }

    private class WidgetView(context: Context, private val onUpdate: () -> Unit) : AppWidgetHostView(context) {
        override fun updateAppWidget(remoteViews: RemoteViews?) {
            super.updateAppWidget(remoteViews)
            onUpdate()
        }
    }

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** WhatsApp's widgets installed on the phone. */
    fun providers(context: Context): List<AppWidgetProviderInfo> =
        AppWidgetManager.getInstance(context).installedProviders.filter { it.provider.packageName in WHATSAPP }

    /** The bound widget, or null when none is bound (or the binding was lost). */
    fun widgetInfo(context: Context): AppWidgetProviderInfo? {
        val id = prefs(context).getInt(KEY_ID, -1)
        return if (id >= 0) AppWidgetManager.getInstance(context).getAppWidgetInfo(id) else null
    }

    private fun host(context: Context): Host {
        app = context.applicationContext
        return host ?: Host(context.applicationContext) { onWidgetChanged() }.also { host = it }
    }

    /** Starts listening to the widget and schedules a read. Idempotent. */
    fun start(context: Context) {
        try {
            host(context).startListening()
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
        }
        if (view == null) createView(context)
        readSoon(300)
    }

    private fun createView(context: Context) {
        val id = prefs(context).getInt(KEY_ID, -1)
        val info = widgetInfo(context)
        if (info == null) {
            view = null
            return
        }
        val v = host(context).createView(context.applicationContext, id, info)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.updateAppWidgetSize(Bundle(), listOf(SizeF(W_DP.toFloat(), H_DP.toFloat())))
        } else {
            @Suppress("DEPRECATION")
            v.updateAppWidgetSize(null, W_DP, H_DP, W_DP, H_DP)
        }
        view = v
        layout(v)
    }

    // ---- Binding ----

    /** Binds [info] to this host: at once if Android already allows it, otherwise through its dialog. */
    fun bind(activity: Activity, info: AppWidgetProviderInfo) {
        release(activity)
        val id = host(activity).allocateAppWidgetId()
        prefs(activity).edit().putInt(KEY_PENDING, id).apply()
        if (AppWidgetManager.getInstance(activity).bindAppWidgetIdIfAllowed(id, info.provider)) {
            bound(activity, id)
        } else {
            activity.startActivityForResult(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider),
                REQ_BIND,
            )
        }
    }

    private fun bound(activity: Activity, id: Int) {
        prefs(activity).edit().putInt(KEY_ID, id).remove(KEY_PENDING).apply()
        HealthMonitor.onBound(activity)
        val info = AppWidgetManager.getInstance(activity).getAppWidgetInfo(id)
        if (info?.configure != null) {
            try {
                host(activity).startAppWidgetConfigureActivityForResult(activity, id, 0, REQ_CONFIGURE, null)
                return
            } catch (e: Exception) {
                Log.w(TAG, "the widget's configuration screen did not open", e)
            }
        }
        view = null
        start(activity)
        notifyListeners()
    }

    /** Handles the results of [bind]; returns false for a request code that is not ours. */
    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {
            REQ_BIND -> {
                val p = prefs(activity)
                val id = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)?.takeIf { it >= 0 }
                    ?: p.getInt(KEY_PENDING, -1)
                if (resultCode == Activity.RESULT_OK && id >= 0) {
                    bound(activity, id)
                } else {
                    if (id >= 0) host(activity).deleteAppWidgetId(id)
                    p.edit().remove(KEY_PENDING).apply()
                    notifyListeners()
                }
            }
            REQ_CONFIGURE -> {
                view = null
                start(activity)
                notifyListeners()
            }
            else -> return false
        }
        return true
    }

    private fun release(context: Context) {
        val id = prefs(context).getInt(KEY_ID, -1)
        if (id >= 0) host(context).deleteAppWidgetId(id)
        prefs(context).edit().remove(KEY_ID).apply()
        view = null
    }

    // ---- Reading ----

    private fun onWidgetChanged() {
        if (reading) return
        retries = 0
        readSoon(400)
    }

    // A Handler, not View.post: a view with no window holds its posts until it is attached.
    fun readSoon(delayMs: Long) {
        main.removeCallbacks(readNow)
        main.postDelayed(readNow, delayMs)
    }

    /** A view with no window is never measured by Android, so it is done here before every read. */
    private fun layout(v: View) {
        val d = v.resources.displayMetrics.density
        val w = (W_DP * d).toInt()
        val h = (H_DP * d).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    /** One row: what it says, the view the adapter made for it, and where it sits. */
    private class Row(val list: AdapterView<*>, val position: Int, val item: View, val message: WidgetRows.Message?)

    /** One pass over the widget: the lists found, the rows still loading, and the view tree as text. */
    private class Pass(val lists: Int, val loading: Int, val dump: String)

    private fun readRows(v: View, out: MutableList<Row>): Pass {
        layout(v)
        val res = try {
            (v as? AppWidgetHostView)?.appWidgetInfo?.provider?.packageName?.let { v.context.packageManager.getResourcesForApplication(it) }
        } catch (_: Exception) {
            null
        }
        val dump = StringBuilder()
        val lists = mutableListOf<AdapterView<*>>()
        findLists(v, lists, dump, res, 0)
        var loading = 0
        for (list in lists) {
            val adapter = list.adapter
            dump.append("\n-- list: ")
            if (adapter == null) {
                dump.append("no adapter yet\n")
                loading++
                continue
            }
            watch(adapter)
            dump.append(adapter.count).append(" rows --\n")
            val width = View.MeasureSpec.makeMeasureSpec((W_DP * list.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY)
            for (i in 0 until adapter.count) {
                val item = try {
                    adapter.getView(i, null, list)
                } catch (_: Exception) {
                    null
                } ?: continue
                item.measure(width, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val fields = mutableListOf<WidgetRows.Field>()
                collect(item, fields, res)
                val m = WidgetRows.parse(fields)
                if (m == null) loading++
                out += Row(list, i, item, m)
                dump.append("[").append(i).append("] ").append(if (m == null) "?? " else "ok ")
                    .append(fields.joinToString(" | ") { (it.id?.let { id -> "$id=" } ?: "") + it.text.replace("\n", "⏎").take(40) })
                    .append('\n')
            }
        }
        return Pass(lists.size, loading, dump.toString())
    }

    private fun read() {
        val v = view ?: return
        val context = app ?: return
        reading = true
        try {
            val rows = mutableListOf<Row>()
            val pass = readRows(v, rows)
            val messages = rows.mapNotNull { it.message }
            onMessages(context, messages)
            // A remote list hands out its rows as they arrive: read again until they have.
            if (pass.loading > 0 && retries < MAX_RETRIES) {
                retries++
                readSoon(RETRY_MS)
            } else {
                retries = 0
                HealthMonitor.afterRead(context, pass.lists, rows.size, messages.size, pass.dump)
            }
        } catch (e: Exception) {
            Log.w(TAG, "read failed", e)
            HealthMonitor.afterRead(context, -1, 0, 0, "read failed: $e")
        } finally {
            reading = false
        }
        notifyListeners()
    }

    private fun findLists(v: View, out: MutableList<AdapterView<*>>, dump: StringBuilder, res: Resources?, depth: Int) {
        dump.append("  ".repeat(depth)).append(v.javaClass.simpleName)
        idName(v, res)?.let { dump.append(" #").append(it) }
        dump.append('\n')
        if (v is AdapterView<*>) {
            out += v
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) findLists(v.getChildAt(i), out, dump, res, depth + 1)
    }

    private fun collect(v: View, out: MutableList<WidgetRows.Field>, res: Resources?) {
        if (v.visibility != View.VISIBLE) return
        if (v is TextView) {
            val t = v.text
            if (!t.isNullOrEmpty()) out += WidgetRows.Field(idName(v, res), t.toString(), boldOf(t))
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out, res)
    }

    private fun boldOf(t: CharSequence): List<IntRange> {
        if (t !is Spanned) return emptyList()
        return t.getSpans(0, t.length, StyleSpan::class.java)
            .filter { it.style and Typeface.BOLD != 0 }
            .map { t.getSpanStart(it) until t.getSpanEnd(it) }
            .sortedBy { it.first }
    }

    private fun idName(v: View, res: Resources?): String? {
        val id = v.id
        if (id == View.NO_ID || id == 0) return null
        return try {
            res?.getResourceEntryName(id)
        } catch (_: Resources.NotFoundException) {
            null
        }
    }

    private fun watch(adapter: Adapter) {
        if (!watched.add(adapter)) return
        adapter.registerDataSetObserver(object : DataSetObserver() {
            override fun onChanged() = onWidgetChanged()
            override fun onInvalidated() = onWidgetChanged()
        })
    }

    private fun onMessages(context: Context, messages: List<WidgetRows.Message>) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val seenJson = json(p.getString(KEY_SEEN, null))
        val seen = seenJson.keys().asSequence().associateWith { seenJson.optLong(it) }
        val check = Polls.check(seen, messages, followed(context), now)
        val chats = json(p.getString(KEY_CHATS, null))
        for (m in messages) chats.put(m.chat, now)
        val keptChats = JSONObject()
        chats.keys().asSequence().map { it to chats.optLong(it) }.sortedByDescending { it.second }.take(MAX_CHATS)
            .forEach { (c, t) -> keptChats.put(c, t) }
        p.edit()
            .putString(KEY_SEEN, JSONObject(check.seen).toString())
            .putString(KEY_CHATS, keptChats.toString())
            .putLong(KEY_READ_AT, now)
            .apply()
        // One ring per chat, with its newest question.
        for ((chat, polls) in check.polls.groupBy { it.chat }) {
            PollAlarm.ring(context, chat, Polls.question(polls.last().text), polls.size)
            EventLog.add(context, context.getString(R.string.log_poll, chat))
        }
    }

    fun lastReadAt(context: Context): Long = prefs(context).getLong(KEY_READ_AT, 0L)

    // ---- Opening a chat ----

    /**
     * Clicks [chat]'s row in the widget, preferring a poll row: WhatsApp set that click to open the
     * chat. Returns false when no row of the chat is in the widget. The click itself reports
     * nothing back (Android's widget code sends it), so the caller checks that WhatsApp came up.
     */
    fun clickChatRow(context: Context, chat: String): Boolean {
        start(context)
        val v = view ?: return false
        val rows = mutableListOf<Row>()
        try {
            readRows(v, rows)
        } catch (e: Exception) {
            Log.w(TAG, "read before click failed", e)
            return false
        }
        val mine = rows.filter { r -> r.message?.let { ChatNames.same(it.chat, chat) } == true }
        val row = mine.firstOrNull { Polls.isPoll(it.message!!.text) } ?: mine.firstOrNull() ?: return false
        return try {
            row.list.performItemClick(row.item, row.position, row.list.adapter?.getItemId(row.position) ?: 0L)
        } catch (e: Exception) {
            Log.w(TAG, "row click failed", e)
            false
        }
    }

    /** Opens WhatsApp on its chat list. */
    fun openWhatsApp(context: Context): Boolean {
        for (pkg in WHATSAPP) {
            val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        return false
    }

    // ---- Followed chats ----

    fun followed(context: Context): Set<String> = prefs(context).getStringSet(KEY_FOLLOW, emptySet()).orEmpty().toSet()

    fun setFollowed(context: Context, chat: String, on: Boolean) {
        val now = followed(context).toMutableSet()
        // Off drops every spelling of the chat: the name as shown and a name typed for it.
        if (on) now += chat else now.removeAll { ChatNames.same(it, chat) }
        prefs(context).edit().putStringSet(KEY_FOLLOW, now).apply()
    }

    /** Every chat seen in the widget, most recent first: name → when it was last there. */
    fun chatsSeen(context: Context): List<Pair<String, Long>> {
        val o = json(prefs(context).getString(KEY_CHATS, null))
        return o.keys().asSequence().map { it to o.optLong(it) }.sortedByDescending { it.second }.toList()
    }

    private fun json(s: String?): JSONObject = try {
        JSONObject(s ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    // ---- Observers ----

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    private fun notifyListeners() {
        main.post { listeners.toList().forEach { it() } }
    }
}
