package org.halachmi.polls.core

import org.halachmi.polls.core.WidgetRows.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetRowsTest {
    private fun bold(text: String, part: String): List<IntRange> {
        val i = text.indexOf(part)
        return listOf(i until i + part.length)
    }

    @Test
    fun privateChat() {
        val m = WidgetRows.parse(listOf(
            Field("contact_name", "Dana Levi"),
            Field("date", "09:25"),
            Field("message", "📷 ראית את התמונה?"),
        ))!!
        assertEquals("Dana Levi", m.chat)
        assertNull(m.sender)
        assertEquals("09:25", m.time)
        assertEquals("📷 ראית את התמונה?", m.text)
    }

    @Test
    fun groupWithAnUnsavedSenderInBold() {
        val body = "~ Alex Cohen: היי\nמישהו יודע מתי האסיפה?"
        val m = WidgetRows.parse(listOf(
            Field(null, "🏠 ועד הבית"),
            Field(null, "08:44"),
            Field(null, body, bold(body, "Alex Cohen:")),
        ))!!
        assertEquals("🏠 ועד הבית", m.chat)
        assertEquals("Alex Cohen", m.sender)
        assertTrue(m.unsaved)
        assertEquals("היי\nמישהו יודע מתי האסיפה?", m.text)
    }

    @Test
    fun aSavedSenderIsFoundByTheBoldAlone() {
        val body = "נועה: 📊 POLL: מי מגיע?"
        val (sender, unsaved, text) = WidgetRows.splitSender(Field(null, body, bold(body, "נועה")))
        assertEquals("נועה", sender)
        assertFalse(unsaved)
        assertEquals("📊 POLL: מי מגיע?", text)
    }

    @Test
    fun anEmojiNameWithNoSpans() {
        val (sender, unsaved, text) = WidgetRows.splitSender(Field(null, "~ 🙈🙉🙊: מחפשת המלצה"))
        assertEquals("🙈🙉🙊", sender)
        assertTrue(unsaved)
        assertEquals("מחפשת המלצה", text)
    }

    @Test
    fun aColonInAPrivateMessageIsNotASender() {
        assertNull(WidgetRows.splitSender(Field(null, "שאלה: מתי האסיפה?")).first)
        // Bold that does not start the text is not a sender either.
        val body = "תזכורת לפגישה. לחצו כאן: https://example.com"
        assertNull(WidgetRows.splitSender(Field(null, body, bold(body, "לחצו כאן"))).first)
    }

    @Test
    fun timesAndDays() {
        for (s in listOf("14:43", "9:05", "YESTERDAY", "10/27/26", "27.10.26", "אתמול", "יום ב'", "2:05 PM")) {
            assertTrue(s, WidgetRows.isWhen(s))
        }
        for (s in listOf("Dana Levi", "תודה רבה", "(0:26)", "20")) assertFalse(s, WidgetRows.isWhen(s))
    }

    @Test
    fun aRowWithoutABodyIsNotAMessage() {
        assertNull(WidgetRows.parse(listOf(Field(null, "Loading…"))))
        assertNull(WidgetRows.parse(listOf(Field(null, "WhatsApp"), Field(null, "  "))))
    }
}
