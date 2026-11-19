package org.halachmi.polls.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNamesTest {
    @Test
    fun emojiPunctuationAndMarksDoNotCount() {
        val heading = "חוג שחייה - קבוצה ב🌊"
        assertEquals("חוג שחייה קבוצה ב", ChatNames.key(heading))
        assertTrue(ChatNames.isFollowed(setOf("חוג שחייה קבוצה ב"), heading))
        assertTrue(ChatNames.isFollowed(setOf("  Book Club "), "book club 📚"))
        assertTrue(ChatNames.isFollowed(setOf("‏כיתה א'2"), "כיתה א׳2"))
    }

    @Test
    fun partOfANameIsNotTheChat() {
        assertFalse(ChatNames.isFollowed(setOf("חוג שחייה"), "חוג שחייה - קבוצה ב🌊"))
        assertFalse(ChatNames.isFollowed(setOf("🌊"), "חוג שחייה - קבוצה ב🌊"))
    }
}
