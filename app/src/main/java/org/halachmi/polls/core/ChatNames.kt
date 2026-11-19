package org.halachmi.polls.core

/**
 * Matching a chat's name as the widget shows it against a name the user picked or typed.
 *
 * Only letters and digits count, lower-cased, one space between words: emoji, punctuation and the
 * direction marks WhatsApp puts around mixed Hebrew/English names are not something to type.
 */
object ChatNames {
    fun key(name: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) sb.appendCodePoint(Character.toLowerCase(cp))
            else if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            i += Character.charCount(cp)
        }
        return sb.toString().trim()
    }

    fun same(a: String, b: String): Boolean {
        val k = key(a)
        return k.isNotEmpty() && k == key(b)
    }

    /** Whether [chat] is one of [followed]. */
    fun isFollowed(followed: Set<String>, chat: String): Boolean = followed.any { same(it, chat) }
}
