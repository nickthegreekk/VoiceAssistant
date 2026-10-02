package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-message timestamps — the display rule, the model-facing prefix, and the eligibility gate.
 *
 * Everything here is pure and takes `now`/`zone` as parameters, so "is this today?" is decided
 * by the test instead of by whatever the wall clock happened to be — including the two sides
 * of midnight, which is where a today/not-today rule is most likely to be subtly wrong.
 *
 * The device-level half (a real request body carrying the prefixes for a Direct-Ollama persona
 * and none for a cloud persona, and the bubbles rendering) is `MessageTimestampInstrumentedTest`.
 */
class MessageTimestampTest {

    private val utc: ZoneId = ZoneOffset.UTC
    private val tokyo: ZoneId = ZoneId.of("Asia/Tokyo")

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    private fun stamp(messageAt: String, nowAt: String, zone: ZoneId = utc): String? =
        formatMessageStamp(at(messageAt), at(nowAt), zone, Locale.US)

    // ------------------------------------------------------- display: today vs not

    @Test fun `a message from today shows only the time`() {
        assertEquals("14:32", stamp("2026-10-01T14:32:00Z", "2026-10-01T18:00:00Z"))
    }

    @Test fun `a message from a previous day shows its date and time`() {
        assertEquals("Sep 28, 2026 09:05", stamp("2026-09-28T09:05:00Z", "2026-10-01T18:00:00Z"))
    }

    @Test fun `the day boundary flips the format on both sides`() {
        // One millisecond either side of midnight, same message. This is the case a
        // "same calendar day" shortcut gets wrong in one direction or the other.
        val lastMessageOfTheDay = "2026-10-01T23:59:59Z"
        assertEquals(
            "seen before midnight it is today's message",
            "23:59",
            stamp(lastMessageOfTheDay, "2026-10-01T23:59:59Z")
        )
        assertEquals(
            "seen one second later it must have become an older message, not silently stayed a time",
            "Oct 1, 2026 23:59",
            stamp(lastMessageOfTheDay, "2026-10-02T00:00:01Z")
        )
        // And the mirror: the first message of the next day reads as bare time again.
        assertEquals("00:00", stamp("2026-10-02T00:00:00Z", "2026-10-02T00:00:01Z"))
    }

    @Test fun `the stamp is rendered in the given zone`() {
        // 09:30 in Tokyo is 00:30 UTC on the SAME date, so the format is unchanged but the
        // clock is not — the stamp must follow the device's zone, not UTC.
        assertEquals("00:30", stamp("2026-10-02T00:30:00Z", "2026-10-02T06:00:00Z", utc))
        assertEquals("09:30", stamp("2026-10-02T00:30:00Z", "2026-10-02T06:00:00Z", tokyo))
    }

    @Test fun `a message with no known time shows no stamp rather than a wrong one`() {
        // Every message written before the timestamp field existed decodes as 0.
        assertNull(formatMessageStamp(0L, at("2026-10-01T18:00:00Z"), utc, Locale.US))
        assertNull(formatMessageStamp(-1L, at("2026-10-01T18:00:00Z"), utc, Locale.US))
    }

    // ------------------------------------------------------- model-facing prefix

    @Test fun `the model-facing prefix always carries the full date even for today`() {
        val message = ChatMessage("user", "what did we decide?", timestamp = at("2026-10-01T14:32:00Z"))
        assertEquals(
            "the UI may hide today's date; the model may not, or it reads an old turn as today",
            "[Oct 1, 2026 14:32] what did we decide?",
            message.historyTextWithTimestamp(utc)
        )
    }

    @Test fun `the model-facing prefix does not localize`() {
        // Locale.US is deliberate: this string is read by a model, so it must not change with
        // the phone's language. Pinned because the display stamps above DO localize.
        val message = ChatMessage("assistant", "sure", timestamp = at("2026-09-28T09:05:00Z"))
        assertEquals("[Sep 28, 2026 09:05] sure", message.historyTextWithTimestamp(utc))
    }

    @Test fun `a message with no known time is passed through verbatim`() {
        val legacy = ChatMessage("user", "an older message")
        assertEquals(legacy.text, legacy.historyTextWithTimestamp(utc))
    }

    @Test fun `stamping is idempotent so an edit cannot move a message's time`() {
        val stamped = ChatMessage("user", "hello").stampedAt(at("2026-10-01T14:32:00Z"))
        assertEquals(at("2026-10-01T14:32:00Z"), stamped.timestamp)
        assertEquals(
            "re-stamping must not move it",
            at("2026-10-01T14:32:00Z"),
            stamped.stampedAt(at("2026-11-09T03:00:00Z")).timestamp
        )
    }

    @Test fun `an unstamped message defaults to no timestamp rather than to now`() {
        // The legacy-transcript trap: a default of System.currentTimeMillis() would file the
        // entire existing history under "just now".
        assertEquals(0L, ChatMessage("user", "old").timestamp)
    }

    // ------------------------------------------------------- eligibility gating

    private fun persona(
        name: String = "Bob",
        model: String = "[HomeBox] llama3",
        cloud: Boolean = false
    ) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = model,
        systemPrompt = "You are $name.",
        isCloud = cloud
    )

    private val ollama = setOf("HomeBox")
    private val cloudProviders = setOf("OpenAI")

    @Test fun `a Direct-Ollama persona stamps its history`() {
        assertTrue(persona().stampsHistoryWithTimestamps(ollama, cloudProviders))
    }

    @Test fun `a cloud persona never stamps its history`() {
        val cloudPersona = persona(model = "[OpenAI] gpt-4o", cloud = true)
        assertEquals(
            "a paid request must not silently grow by a prefix per message",
            false,
            cloudPersona.stampsHistoryWithTimestamps(ollama, cloudProviders)
        )
    }

    @Test fun `a gateway-routed persona never stamps its history`() {
        // No bracket prefix at all: this request is history-blind, so there is nothing to stamp.
        assertFalse(persona(model = "llama3").stampsHistoryWithTimestamps(ollama, cloudProviders))
    }

    @Test fun `an unconfigured server name is not Direct-Ollama`() {
        // The inverse control: a name that merely LOOKS like a server tag must not qualify,
        // which is exactly what `requestRouting` checks against the configured lists.
        assertFalse(
            persona(model = "[NotConfigured] llama3").stampsHistoryWithTimestamps(ollama, cloudProviders)
        )
    }

    @Test fun `the gate is the Rooms distinction, not a second local-vs-cloud rule`() {
        // The whole point of extracting `requestRouting`: for every plain conversational
        // persona, "gets history timestamps" and "Rooms treats as local" must be the SAME
        // answer. If a future change moved one without the other, this fails.
        val candidates = listOf(
            persona(model = "[HomeBox] llama3"),
            persona(model = "[OpenAI] gpt-4o", cloud = true),
            persona(model = "llama3"),
            persona(model = "[NotConfigured] llama3"),
            persona(model = "  [HomeBox] llama3  "),
            persona(model = "[HomeBox]", cloud = false)
        )
        candidates.forEach { p ->
            val roomsSaysLocal =
                p.roomEligibility(ollama, cloudProviders) == RoomEligibility.Eligible(cloud = false)
            assertEquals(
                "gate disagreed with roomEligibility for model '${p.model}'",
                roomsSaysLocal,
                p.stampsHistoryWithTimestamps(ollama, cloudProviders)
            )
        }
    }
}
