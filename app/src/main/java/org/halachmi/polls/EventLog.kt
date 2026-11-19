package org.halachmi.polls

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last few things the app did that matter to the user (a poll rang, a problem was raised, an
 * update was installed), shown on the main screen, so "it did not ring" can be told apart from "it
 * rang and was muted elsewhere".
 */
object EventLog {
    private const val KEY = "event_log"
    private const val MAX = 20

    data class Event(val at: Long, val text: String)

    fun add(context: Context, text: String) {
        val p = WidgetWatcher.prefs(context)
        val all = load(context) + Event(System.currentTimeMillis(), text)
        val arr = JSONArray()
        all.takeLast(MAX).forEach { arr.put(JSONObject().put("at", it.at).put("text", it.text)) }
        p.edit().putString(KEY, arr.toString()).apply()
    }

    /** Oldest first. */
    fun load(context: Context): List<Event> = try {
        val arr = JSONArray(WidgetWatcher.prefs(context).getString(KEY, "[]"))
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { Event(it.optLong("at"), it.optString("text")) }
    } catch (_: Exception) {
        emptyList()
    }
}
