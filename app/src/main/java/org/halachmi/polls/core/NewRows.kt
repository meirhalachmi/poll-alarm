package org.halachmi.polls.core

/**
 * Which rows of the widget are new since the last read.
 *
 * The widget is re-read on every update, so the same unread message is seen many times. A row is
 * new the first time its exact content is read: chat, sender, time label and text, plus which
 * occurrence of that content it is (counted from the oldest), since "thanks" at 14:22 can arrive
 * twice. What was seen is remembered for [KEEP_MS].
 */
object NewRows {
    const val KEEP_MS = 14L * 24 * 3600 * 1000

    data class Row(val message: WidgetRows.Message, val occurrence: Int) {
        val id: String get() = message.key + "\u0002" + occurrence
    }

    /** [fresh]: the rows not seen before. [seen]: row id → when first seen, to keep for next time. */
    data class Result(val fresh: List<Row>, val seen: Map<String, Long>)

    /** One read's rows, oldest first, each with its occurrence. The widget lists the newest first. */
    fun rows(newestFirst: List<WidgetRows.Message>): List<Row> {
        val counts = HashMap<String, Int>()
        return newestFirst.asReversed().map { m ->
            val n = (counts[m.key] ?: 0) + 1
            counts[m.key] = n
            Row(m, n)
        }
    }

    fun update(seen: Map<String, Long>, rows: List<Row>, now: Long): Result {
        val kept = seen.filterValues { now - it < KEEP_MS }.toMutableMap()
        val fresh = rows.filter { it.id !in kept }
        for (r in fresh) kept[r.id] = now
        return Result(fresh, kept)
    }
}
