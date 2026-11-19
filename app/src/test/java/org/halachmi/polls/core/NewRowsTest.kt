package org.halachmi.polls.core

import org.halachmi.polls.core.WidgetRows.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewRowsTest {
    private fun m(time: String, text: String) = Message("Book club", "Alex", false, time, text)

    @Test
    fun onlyWhatWasNotThereBeforeIsNew() {
        val first = NewRows.rows(listOf(m("13:27", "second"), m("13:24", "first")))
        val a = NewRows.update(emptyMap(), first, now = 1000)
        assertEquals(2, a.fresh.size)
        // The newest is listed first; the next read adds one on top.
        val second = NewRows.rows(listOf(m("13:28", "third"), m("13:27", "second"), m("13:24", "first")))
        val b = NewRows.update(a.seen, second, now = 2000)
        assertEquals(listOf("third"), b.fresh.map { it.message.text })
        assertEquals(1000L, b.seen[first[0].id])
    }

    @Test
    fun theSameLineTwiceIsTwoMessages() {
        val rows = NewRows.rows(listOf(m("14:22", "thanks"), m("14:22", "thanks")))
        assertEquals(listOf(1, 2), rows.map { it.occurrence })
        val r = NewRows.update(emptyMap(), rows, now = 1)
        assertEquals(2, r.fresh.size)
        // One of them read: what is left is the first occurrence, already seen.
        val after = NewRows.update(r.seen, NewRows.rows(listOf(m("14:22", "thanks"))), now = 2)
        assertTrue(after.fresh.isEmpty())
    }

    @Test
    fun forgottenAfterTwoWeeks() {
        val rows = NewRows.rows(listOf(m("10/27/26", "old")))
        val r = NewRows.update(emptyMap(), rows, now = 0)
        val later = NewRows.update(r.seen, rows, now = NewRows.KEEP_MS + 1)
        assertEquals(1, later.fresh.size)
    }
}
