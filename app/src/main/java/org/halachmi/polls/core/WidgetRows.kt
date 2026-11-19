package org.halachmi.polls.core

/**
 * Parses one row of WhatsApp's home-screen widget from the text views it renders.
 *
 * The widget lists unread messages, newest first. Rows seen in practice:
 * - a private chat: title "Dana", time "09:25", body "📷 Photo";
 * - a group: title "Book club", time "08:44", body "~ Alex: see you there", where the sender's
 *   name is bold and "~" marks a sender who is not in the phone's contacts (a saved contact has
 *   no "~", and is recognised by the bold span alone);
 * - older rows show a day instead of a time: "YESTERDAY", "10/27/26".
 *
 * View order is the fallback; a field whose resource name says "time" or "date" is preferred for
 * the time, when the resource names are known.
 */
object WidgetRows {
    /** One TextView of a row: its resource name (if known), its text, and its bold stretches. */
    data class Field(val id: String?, val text: String, val bold: List<IntRange> = emptyList())

    /** One unread message. [sender] is set in groups only; [unsaved] is the "~" before it. */
    data class Message(
        val chat: String,
        val sender: String?,
        val unsaved: Boolean,
        val time: String,
        val text: String,
    ) {
        val key: String get() = listOf(chat, sender.orEmpty(), time, text).joinToString("\u0001")
    }

    private val TIME = Regex("^\\d{1,2}:\\d{2}(\\s?[AaPp]\\.?[Mm]\\.?)?$")
    private val DATE = Regex("^\\d{1,4}[./-]\\d{1,2}([./-]\\d{1,4})?$")
    private val HEBREW_DAY = Regex("^יום [א-ש]['׳]?$")
    private val DAY_WORDS = setOf(
        "today", "yesterday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "היום", "אתמול", "שבת",
    )

    // Only with the "~": without it, a private message that happens to contain a colon
    // ("Question: when?") would read as a sender.
    private val UNSAVED_PREFIX = Regex("^\\s*~\\s*([^:\\n]{1,60}?)\\s*:\\s*")

    /** Whether [s] is a row's time or day label. */
    fun isWhen(s: String): Boolean {
        val t = s.trim()
        return TIME.matches(t) || DATE.matches(t) || HEBREW_DAY.matches(t) || t.lowercase() in DAY_WORDS
    }

    /** A row's fields in view order → a message, or null when it is not one (a loading row, a header). */
    fun parse(fields: List<Field>): Message? {
        val shown = fields.filter { it.text.isNotBlank() }
        val timeField = shown.firstOrNull { f -> f.id?.let { "time" in it || "date" in it } == true && isWhen(f.text) }
            ?: shown.firstOrNull { isWhen(it.text) }
        val rest = shown.filter { it !== timeField }
        if (rest.size < 2) return null
        val chat = rest[0].text.trim()
        val (sender, unsaved, first) = splitSender(rest[1])
        val text = (listOf(first) + rest.drop(2).map { it.text.trim() }).filter { it.isNotEmpty() }.joinToString("\n")
        return Message(chat, sender, unsaved, timeField?.text?.trim().orEmpty(), text)
    }

    /** "~ Name: text", or "Name: text" with the name in bold → (sender, unsaved, text). */
    fun splitSender(f: Field): Triple<String?, Boolean, String> {
        val t = f.text
        val bold = f.bold.firstOrNull { b -> b.first in 0..t.length && t.substring(0, b.first).all { it == '~' || it.isWhitespace() } }
        if (bold != null) {
            val colon = t.indexOf(':', bold.first)
            if (colon > bold.first && colon <= bold.last + 2) {
                val head = t.substring(0, colon)
                val name = head.trim().trimStart('~').trim()
                if (name.isNotEmpty()) return Triple(name, head.trimStart().startsWith("~"), t.substring(colon + 1).trim())
            }
        }
        if (f.bold.isEmpty()) {
            UNSAVED_PREFIX.find(t)?.let { m -> return Triple(m.groupValues[1].trim(), true, t.substring(m.range.last + 1).trim()) }
        }
        return Triple(null, false, t.trim())
    }
}
