package com.nikosm.voiceassistant

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share target's pure half: what an `ACTION_SEND` payload becomes, and how it lands in the
 * composer's draft.
 *
 * The Activity half (intent extras, one-shot consumption, recreation) is
 * `ShareTargetInstrumentedTest`; this file pins the decisions themselves.
 */
class SharedContentTest {

    // ------------------------------------------------------------------ parse

    @Test
    fun `only ACTION_SEND is a share`() {
        assertNull(parseSharedIntent(Intent.ACTION_SEND_MULTIPLE, "text/plain", "hi", null, false))
        assertNull(parseSharedIntent(Intent.ACTION_VIEW, "text/plain", "hi", null, false))
        assertNull(parseSharedIntent(null, "text/plain", "hi", null, false))
    }

    @Test
    fun `plain text becomes text`() {
        assertEquals(
            SharedIntentContent.Text("hello there"),
            parseSharedIntent(Intent.ACTION_SEND, "text/plain", "hello there", null, false)
        )
    }

    @Test
    fun `a subject that is not in the body is prefixed with a newline`() {
        assertEquals(
            SharedIntentContent.Text("A title\nbody words"),
            parseSharedIntent(Intent.ACTION_SEND, "text/plain", "body words", "A title", false)
        )
    }

    @Test
    fun `a subject already in the body is not repeated`() {
        assertEquals(
            SharedIntentContent.Text("A title\nbody words"),
            parseSharedIntent(Intent.ACTION_SEND, "text/plain", "A title\nbody words", "A title", false)
        )
    }

    @Test
    fun `a subject with no body is the whole text`() {
        assertEquals(
            SharedIntentContent.Text("A title"),
            parseSharedIntent(Intent.ACTION_SEND, "text/plain", "", "A title", false)
        )
    }

    @Test
    fun `a blank text share is nothing`() {
        assertNull(parseSharedIntent(Intent.ACTION_SEND, "text/plain", "", null, false))
        assertNull(parseSharedIntent(Intent.ACTION_SEND, "text/plain", "   ", null, false))
        assertNull(parseSharedIntent(Intent.ACTION_SEND, "text/plain", null, null, false))
    }

    @Test
    fun `an image with a stream is an image`() {
        assertEquals(
            SharedIntentContent.Image,
            parseSharedIntent(Intent.ACTION_SEND, "image/png", null, null, true)
        )
    }

    @Test
    fun `an image with no stream is nothing`() {
        assertNull(parseSharedIntent(Intent.ACTION_SEND, "image/png", null, null, false))
    }

    @Test
    fun `an image share is not turned into text by a caption extra`() {
        // The manifest matched image/*, so the stream is the payload.
        assertEquals(
            SharedIntentContent.Image,
            parseSharedIntent(Intent.ACTION_SEND, "image/jpeg", "a caption", null, true)
        )
    }

    @Test
    fun `an unsupported type is nothing`() {
        assertNull(parseSharedIntent(Intent.ACTION_SEND, "application/pdf", "x", null, false))
        assertNull(parseSharedIntent(Intent.ACTION_SEND, "*/*", "x", null, true))
        assertNull(parseSharedIntent(Intent.ACTION_SEND, null, "x", null, false))
    }

    // ------------------------------------------------------------------ draft append

    @Test
    fun `text lands in an empty draft as-is`() {
        val landed = appendSharedTextToDraft("", "shared words")
        assertEquals("shared words", landed.draft)
        assertFalse(landed.truncated)
    }

    @Test
    fun `text is appended to an existing draft after a newline`() {
        val landed = appendSharedTextToDraft("half typed", "shared words")
        assertEquals("half typed\nshared words", landed.draft)
        assertFalse(landed.truncated)
    }

    @Test
    fun `the cap is thirty thousand characters`() {
        assertEquals(30_000, MAX_SHARED_TEXT_CHARS)
    }

    @Test
    fun `text exactly at the cap is not truncated`() {
        val exact = "a".repeat(MAX_SHARED_TEXT_CHARS)
        val landed = appendSharedTextToDraft("", exact)
        assertEquals(exact, landed.draft)
        assertFalse(landed.truncated)
    }

    @Test
    fun `one character over the cap is truncated to it`() {
        val over = "a".repeat(MAX_SHARED_TEXT_CHARS + 1)
        val landed = appendSharedTextToDraft("", over)
        assertEquals(MAX_SHARED_TEXT_CHARS, landed.draft.length)
        assertTrue(landed.truncated)
    }

    @Test
    fun `truncation keeps the head of the shared text and the draft separator`() {
        val over = "b".repeat(MAX_SHARED_TEXT_CHARS + 100)
        val landed = appendSharedTextToDraft("draft", over)
        assertEquals("draft\n" + "b".repeat(MAX_SHARED_TEXT_CHARS), landed.draft)
        assertTrue(landed.truncated)
    }
}
