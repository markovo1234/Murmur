package app.murmur.core.text

import app.murmur.core.Murmur
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRulesTest {
    @Test
    fun mentionsMatchWholeNamesCaseInsensitively() {
        assertTrue(Mentions.mentions("hey @luna are you here", "Luna"))
        assertTrue(Mentions.mentions("@Luna!", "Luna"))
        assertTrue(Mentions.mentions("ping @Sam Lee please", "Sam Lee"))
        assertFalse(Mentions.mentions("hey @lunatic", "Luna"))
        assertFalse(Mentions.mentions("mail@luna", "Luna"))
        assertFalse(Mentions.mentions("hello", "Luna"))
        assertFalse(Mentions.mentions("@x", null))
    }

    @Test
    fun partialMentionIsDetectedAndCompleted() {
        assertEquals("lu", Mentions.partial("hi @lu"))
        assertEquals("", Mentions.partial("hi @"))
        assertNull(Mentions.partial("mail@lu"))
        assertNull(Mentions.partial("no mention"))
        assertEquals("hi @Luna ", Mentions.complete("hi @lu", "Luna"))
    }

    @Test
    fun repliesRoundTripThroughPlainText() {
        val body = Replies.compose("Luna", "Want to meet by the fountain?", "Yes! 5 min")
        assertEquals("> Luna: Want to meet by the fountain?\nYes! 5 min", body)
        val parsed = Replies.parse(body)
        assertEquals("Luna", parsed.quoteAuthor)
        assertEquals("Want to meet by the fountain?", parsed.quote)
        assertEquals("Yes! 5 min", parsed.text)
        // Plain messages (and 1.0 clients) are untouched.
        assertEquals(Replies.Parsed(null, null, "just text"), Replies.parse("just text"))
    }

    @Test
    fun longQuotesAreShortenedAndStayWithinTheLimit() {
        val long = "x".repeat(500)
        val body = Replies.compose("Luna", long, "ok")
        assertTrue(body.lines().first().endsWith("…"))
        assertTrue(Replies.parse(body).quote!!.length <= Replies.SNIPPET_CHARS)
        val big = Replies.compose("Luna", "quote", "é".repeat(480))
        assertTrue(Murmur.utf8Size(big) <= Murmur.MAX_TEXT_BYTES)
        assertEquals("é".repeat(480), Replies.parse(big).text)
        // A reply that already fills the limit is sent without a quote rather than failing.
        val full = Replies.compose("Luna", "quote", "é".repeat(500))
        assertEquals("é".repeat(500), full)
    }

    @Test
    fun timerLabels() {
        assertEquals("Off", Disappearing.label(0))
        assertEquals("5 minutes", Disappearing.label(300))
        assertEquals("1 hour", Disappearing.label(3600))
        assertEquals("1 day", Disappearing.label(86_400))
        assertEquals("1 week", Disappearing.label(604_800))
    }
}
