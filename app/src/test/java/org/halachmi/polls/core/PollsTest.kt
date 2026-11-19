package org.halachmi.polls.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PollsTest {
    private fun msg(chat: String, text: String, time: String = "09:10") =
        WidgetRows.Message(chat, "Alex", true, time, text)

    @Test
    fun aPollIsARowThatStartsWithTheMark() {
        assertTrue(Polls.isPoll("📊 POLL: מי מגיע לטיול?"))
        assertTrue(Polls.isPoll("  📊 מי מגיע?"))
        assertFalse(Polls.isPoll("ראיתם את הסקר 📊?"))
        assertFalse(Polls.isPoll("📷 Photo"))
    }

    @Test
    fun theQuestionLosesTheMarkAndTheLabel() {
        assertEquals("מי מגיע לטיול?", Polls.question("📊 POLL: מי מגיע לטיול?"))
        assertEquals("מי מגיע לטיול?", Polls.question("📊 סקר: מי מגיע לטיול?"))
        assertEquals("Pizza or pasta?", Polls.question("📊 poll: Pizza or pasta?"))
        assertEquals("", Polls.question("📊"))
    }

    @Test
    fun onlyANewPollInAFollowedChatIsReported() {
        val followed = setOf("ועד הורים ג'2")
        val read = listOf(
            msg("ועד הורים ג׳2 🌼", "📊 POLL: מי מגיע?"),
            msg("ועד הורים ג׳2 🌼", "תודה"),
            msg("Book club", "📊 POLL: Next book?"),
        )
        val first = Polls.check(emptyMap(), read, followed, 1_000L)
        assertEquals(listOf("📊 POLL: מי מגיע?"), first.polls.map { it.text })
        assertTrue(Polls.check(first.seen, read, followed, 2_000L).polls.isEmpty())
    }

    @Test
    fun aPollAlreadyUnreadWhenItsChatIsFollowedIsNotNew() {
        val read = listOf(msg("Book club", "📊 POLL: Next book?"))
        val before = Polls.check(emptyMap(), read, emptySet(), 1_000L)
        val after = Polls.check(before.seen, read, setOf("Book club"), 2_000L)
        assertTrue(after.polls.isEmpty())
        val next = Polls.check(after.seen, listOf(msg("Book club", "📊 POLL: Where?", "09:30")) + read, setOf("Book club"), 3_000L)
        assertEquals(listOf("📊 POLL: Where?"), next.polls.map { it.text })
    }

    @Test
    fun aReadThatUnderstandsNothingIsAProblem() {
        assertNull(Polls.diagnose(lists = 1, rows = 0, parsed = 0))
        assertNull(Polls.diagnose(lists = 1, rows = 5, parsed = 4))
        assertEquals(Polls.Problem.UNREADABLE, Polls.diagnose(lists = 1, rows = 5, parsed = 0))
        assertEquals(Polls.Problem.NO_LIST, Polls.diagnose(lists = 0, rows = 0, parsed = 0))
        assertEquals(Polls.Problem.FAILING, Polls.diagnose(lists = -1, rows = 0, parsed = 0))
    }
}
