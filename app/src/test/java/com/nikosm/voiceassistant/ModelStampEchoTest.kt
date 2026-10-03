package com.nikosm.voiceassistant

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model-written stamp: recognising it, stripping it, and never mistaking ordinary
 * bracketed prose for one.
 *
 * A Direct-Ollama model sees `[MMM d, yyyy HH:mm] …` in its history and sometimes starts its
 * own answer the same way. That text is stored verbatim, so the next turn's injection puts
 * the real stamp in front of an already-stamped reply and the two stack — which is the
 * `[Oct 3, 2026 12:29][Oct 3, 2026 12:29] hello` a user reported.
 *
 * The recognizer is pure and total so it can run on every streaming chunk, and the display
 * wrapper holds a partial stamp rather than letting one blink on screen. The end-to-end half
 * (a real request body and a really-stored reply) is the instrumented echo test.
 */
class ModelStampEchoTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val stamp = "[Oct 3, 2026 12:29]"

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    // ------------------------------------------------------------------ how many

    @Test fun `no stamp is left alone`() {
        assertEquals("hello there", stripLeadingModelStamps("hello there"))
        assertEquals("", stripLeadingModelStamps(""))
    }

    @Test fun `one stamp is removed and the reply survives`() {
        assertEquals("hello there", stripLeadingModelStamps("$stamp hello there"))
        assertEquals("hello there", stripLeadingModelStamps("${stamp}hello there"))
    }

    @Test fun `two stamps are removed`() {
        assertEquals("hello there", stripLeadingModelStamps("$stamp$stamp hello there"))
    }

    @Test fun `three stamps are removed`() {
        assertEquals("hello there", stripLeadingModelStamps("$stamp$stamp$stamp hello there"))
    }

    /** Stripping is a fixed point: it never has to run twice to be safe. */
    @Test fun `stripping is idempotent`() {
        val once = stripLeadingModelStamps("$stamp$stamp hello")
        assertEquals(once, stripLeadingModelStamps(once))
    }

    // ------------------------------------------------------------------ spacing

    @Test fun `spacing the model got wrong is still recognised`() {
        assertEquals("hi", stripLeadingModelStamps("[Oct 3,2026 12:29] hi"))
        assertEquals("hi", stripLeadingModelStamps("[Oct  3,  2026  12:29] hi"))
        assertEquals("hi", stripLeadingModelStamps("[  Oct 3, 2026 12:29  ] hi"))
        assertEquals("hi", stripLeadingModelStamps("[October 3, 2026 12:29] hi"))
        assertEquals("hi", stripLeadingModelStamps("[Oct. 3, 2026 12:29] hi"))
    }

    // ------------------------------------------------------------------ not a stamp

    @Test fun `ordinary bracketed text is untouched`() {
        assertEquals("[Note] ask Sam about it", stripLeadingModelStamps("[Note] ask Sam about it"))
        assertEquals("[1] see below", stripLeadingModelStamps("[1] see below"))
        assertEquals("[TODO] fix", stripLeadingModelStamps("[TODO] fix"))
        assertEquals("[12] step", stripLeadingModelStamps("[12] step"))
        // Letters but no date-time shape is prose, not a stamp.
        assertEquals("[Note 3, 2026 is when]", stripLeadingModelStamps("[Note 3, 2026 is when]"))
    }

    @Test fun `a stamp in the middle of the text is left alone`() {
        val mid = "we agreed on $stamp last, right?"
        assertEquals(mid, stripLeadingModelStamps(mid))
        assertEquals("see [Oct 3, 2026 12:29]", stripLeadingModelStamps("see [Oct 3, 2026 12:29]"))
    }

    // ------------------------------------------------------------------ streaming display

    @Test fun `a partial stamp is held rather than shown`() {
        assertTrue(couldBecomeLeadingModelStamp("[Oct"))
        assertTrue(couldBecomeLeadingModelStamp("[Oct 3,"))
        assertTrue(couldBecomeLeadingModelStamp("[Oct 3, 20"))
        assertEquals("", displaySafeStreamedReply("[Oct 3, 20"))
    }

    @Test fun `a closed bracket is never held`() {
        assertFalse(couldBecomeLeadingModelStamp("[Note]"))
        assertFalse(couldBecomeLeadingModelStamp("[1]"))
        assertFalse(couldBecomeLeadingModelStamp("hello"))
        assertEquals("[Note] hi", displaySafeStreamedReply("[Note] hi"))
    }

    @Test fun `a complete stamp is stripped from the displayed text`() {
        assertEquals("hello there", displaySafeStreamedReply("$stamp hello there"))
        assertEquals("hello there", displaySafeStreamedReply("$stamp$stamp hello there"))
    }

    /** The display wrapper must never hide text that is already final. */
    @Test fun `display never swallows ordinary text`() {
        for (text in listOf("hello", "a [note] mid-sentence", "1. [x] done", "")) {
            assertEquals(text, displaySafeStreamedReply(text))
        }
    }

// ------------------------------------------------------------------ one source of truth

    /**
     * The recognizer and the formatter must not drift: anything the app can PRINT, the
     * recognizer must be able to strip. Several dates and several locales — including a
     * non-Latin one, since a model echoing a localized stamp must still be caught.
     */
    @Test fun `every formatter output is recognised`() {
        val dates = listOf(
            at("2026-01-02T03:04:00Z"),
            at("2026-10-03T12:29:00Z"),
            at("2026-12-31T23:59:00Z"),
            at("2024-02-29T00:00:00Z"),
            at("2030-06-15T07:08:00Z")
        )
        val locales = listOf(Locale.US, Locale.UK, Locale.forLanguageTag("el"), Locale.GERMANY)
        var checked = 0
        for (locale in locales) {
            for (instant in dates) {
                val printed = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm", locale)
                    .format(Instant.ofEpochMilli(instant).atZone(utc))
                assertEquals(
                    "a stamp printed with $locale must be recognised: [$printed] the answer",
                    "the answer",
                    stripLeadingModelStamps("[$printed] the answer")
                )
                checked++
            }
        }
        assertTrue("expected a real spread of cases, checked $checked", checked >= 20)
    }

    /**
     * The other half of the contract: what [historyTextWithTimestamp] writes is exactly what
     * the recognizer strips, so injection cannot stack even when the stored text already
     * carries one (an older build, or an echo that arrived from elsewhere).
     */
    @Test fun `injection is idempotent over its own output`() {
        val message = ChatMessage("assistant", "$stamp hello", timestamp = at("2026-10-03T12:29:00Z"))
        val injected = message.historyTextWithTimestamp(utc)
        assertEquals(1, Regex("""\[[^\]]+]""").findAll(injected).count())
        assertEquals("hello", injected.substringAfter("] "))
        val again = message.copy(text = injected).historyTextWithTimestamp(utc)
        assertEquals(1, Regex("""\[[^\]]+]""").findAll(again).count())
    }

    @Test fun `a message with no timestamp passes through untouched`() {
        val unstamped = ChatMessage("assistant", "[Note] hi", timestamp = 0L)
        assertEquals("[Note] hi", unstamped.historyTextWithTimestamp(utc))
    }
}
