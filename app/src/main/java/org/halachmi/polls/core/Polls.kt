package org.halachmi.polls.core

/**
 * Poll detection, and whether a read of the widget made sense at all.
 *
 * WhatsApp's widget shows a poll as a row whose text starts with "📊", followed by the question,
 * sometimes after a "POLL:" label.
 */
object Polls {
    const val MARK = "📊"
    private val LABEL = Regex("^(poll|סקר)\\s*[:：]\\s*", RegexOption.IGNORE_CASE)

    fun isPoll(text: String): Boolean = text.trimStart().startsWith(MARK)

    /** "📊 POLL: Who's coming?" → "Who's coming?"; empty when the row shows no question. */
    fun question(text: String): String =
        text.trimStart().removePrefix(MARK).trim().replaceFirst(LABEL, "").trim()

    data class Check(val polls: List<WidgetRows.Message>, val seen: Map<String, Long>)

    /**
     * One read of the widget ([newestFirst]) against what earlier reads saw → the new polls in
     * [followed] chats, oldest first, and what to remember. A poll that was already unread when its
     * chat was followed is not new; one that stays unread is reported once.
     */
    fun check(seen: Map<String, Long>, newestFirst: List<WidgetRows.Message>, followed: Set<String>, now: Long): Check {
        val r = NewRows.update(seen, NewRows.rows(newestFirst), now)
        val polls = r.fresh.map { it.message }.filter { isPoll(it.text) && ChatNames.isFollowed(followed, it.chat) }
        return Check(polls, r.seen)
    }

    /** Why a read of the widget was not understood. */
    enum class Problem { UNBOUND, NO_LIST, UNREADABLE, FAILING, STALE }

    /**
     * One settled read → the problem it shows, or null. [lists] is -1 when the read threw. An empty
     * list is fine (nothing is unread), and so is a list where some rows cannot be parsed (a header,
     * a "more" row); a list where none can means the widget's layout changed.
     */
    fun diagnose(lists: Int, rows: Int, parsed: Int): Problem? = when {
        lists < 0 -> Problem.FAILING
        lists == 0 -> Problem.NO_LIST
        rows > 0 && parsed == 0 -> Problem.UNREADABLE
        else -> null
    }
}
