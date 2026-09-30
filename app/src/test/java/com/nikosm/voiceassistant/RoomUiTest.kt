package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 (Room UI): the pure presentation helpers in RoomScreens.kt.
 *
 * Everything here is service-free and composition-free — speaker attribution, round
 * status copy, member candidates, and cap parsing — so it runs as plain unit tests.
 */
class RoomUiTest {

    private fun persona(name: String, color: Color = Color(0xFF4ADE80)) = Persona(
        name = name,
        themeColor = color,
        model = "[HomeBox] llama3",
        systemPrompt = "You are $name."
    )

    private fun roomMessage(speaker: String, text: String = "hi") =
        RoomMessage(speakerName = speaker, text = text)

    // ------------------------------------------------------------- Speaker chip

    @Test fun `user messages need no label`() {
        assertNull(roomSpeakerLabel(roomMessage(RoomMessage.USER_SPEAKER)))
    }

    @Test fun `persona messages are labeled with the speaker name`() {
        assertEquals("Bob", roomSpeakerLabel(roomMessage("Bob")))
    }

    @Test fun `chip color resolves from the matching persona theme`() {
        val bob = Color(0xFF123456)
        val personas = listOf(persona("Bob", bob), persona("Alice"))
        assertEquals(bob, roomSpeakerColor("Bob", personas))
    }

    @Test fun `removed member falls back to neutral gray`() {
        // The persistence layer prunes membership but never rewrites the transcript, so a
        // message can outlive its speaker. It keeps its name (label) and loses its color.
        val personas = listOf(persona("Alice"))
        assertEquals(ROOM_NEUTRAL_SPEAKER_COLOR, roomSpeakerColor("Bob", personas))
        assertEquals("Bob", roomSpeakerLabel(roomMessage("Bob")))
    }

    // ------------------------------------------------------------- Round status

    @Test fun `no round means no status line`() {
        assertNull(roomRoundStatusText(null, listOf("Bob", "Alice")))
    }

    @Test fun `status names the current speaker with the autonomous count against its cap`() {
        val state = RoomRoundState(
            roomId = "r",
            phase = RoomRoundPhase.INITIAL_ROUND,
            currentMemberIndex = 1,
            autonomousCount = 2,
            cap = 4
        )
        assertEquals(
            "Alice is replying · 2/4 autonomous",
            roomRoundStatusText(state, listOf("Bob", "Alice"))
        )
    }

    @Test fun `autonomous phase reads as autonomous`() {
        val state = RoomRoundState(
            roomId = "r",
            phase = RoomRoundPhase.AUTONOMOUS,
            currentMemberIndex = 0,
            autonomousCount = 3,
            cap = 4
        )
        assertEquals(
            "Bob is replying autonomously · 3/4 autonomous",
            roomRoundStatusText(state, listOf("Bob"))
        )
    }

    @Test fun `out-of-range index degrades instead of crashing`() {
        val state = RoomRoundState(roomId = "r", currentMemberIndex = 5, autonomousCount = 1, cap = 4)
        assertEquals("Round in progress · 1/4 autonomous", roomRoundStatusText(state, listOf("Bob")))
    }

    // ------------------------------------------------------------- Member candidates

    @Test fun `candidates exclude existing members and carry the eligibility verdict`() {
        val ollama = setOf("HomeBox")
        val cloud = setOf("OpenAI")
        val personas = listOf(
            persona("Bob"),
            persona("Alice"),
            Persona(
                name = "Translator",
                themeColor = Color.Cyan,
                model = "[HomeBox] translategemma:latest",
                systemPrompt = "Translate.",
                isTranslator = true
            )
        )
        val candidates = roomMemberCandidates(personas, ollama, cloud, listOf("Bob"))
        assertEquals(listOf("Alice", "Translator"), candidates.map { it.persona.name })
        assertTrue(candidates[0].isEligible)
        assertNull(candidates[0].blockedReason)
        assertFalse("translators must be offered greyed out, never joinable", candidates[1].isEligible)
        assertTrue(candidates[1].blockedReason!!.isNotBlank())
    }

    // ------------------------------------------------------------- Cap parsing

    @Test fun `blank cap means inherit the global default`() {
        // Blank is its OWN sentinel, never the value 0: conflating the two is what made
        // "exactly one reply each, no continuation" unexpressible in the first place.
        assertEquals(ROOM_CAP_INHERIT, parseAutoReplyCap(""))
        assertEquals(ROOM_CAP_INHERIT, parseAutoReplyCap("   "))
        assertNull(autoReplyCapOverrideOrNull(""))
    }

    @Test fun `positive cap persists as the override`() {
        assertEquals(6, parseAutoReplyCap("6"))
        assertEquals(6, autoReplyCapOverrideOrNull(" 6 "))
    }

    @Test fun `a cap of zero is committable and persists as zero`() {
        // The regression this pins: 0 used to parse to ROOM_CAP_INVALID (field turned red,
        // Done disabled), and even once parsed it collapsed to null via the inherit
        // sentinel. Both halves are asserted, because either alone re-breaks the feature.
        assertEquals(0, parseAutoReplyCap("0"))
        assertEquals(0, parseAutoReplyCap(" 0 "))
        assertEquals(0, autoReplyCapOverrideOrNull("0"))
        assertNotEquals(
            "0 must not be confused with 'inherit the global default'",
            ROOM_CAP_INHERIT,
            parseAutoReplyCap("0")
        )
        assertNotEquals(
            "0 must not be confused with 'garbage, refuse to commit'",
            ROOM_CAP_INVALID,
            parseAutoReplyCap("0")
        )
    }

    @Test fun `zero and blank stay distinguishable end to end`() {
        // Same-shaped inputs, opposite meanings, and only ONE of them may collapse to null.
        assertNull(autoReplyCapOverrideOrNull(""))
        assertEquals(0, autoReplyCapOverrideOrNull("0"))
    }

    @Test fun `garbage and negative caps are not committable`() {
        assertEquals(ROOM_CAP_INVALID, parseAutoReplyCap("abc"))
        assertEquals(ROOM_CAP_INVALID, parseAutoReplyCap("1.5"))
        assertEquals(ROOM_CAP_INVALID, parseAutoReplyCap("-3"))
        assertNull(autoReplyCapOverrideOrNull("abc"))
    }

    // ------------------------------------------------------------- Row summaries

    @Test fun `member and cap summaries name the effective values`() {
        assertEquals("No members yet", roomMemberSummary(Room(name = "R")))
        assertEquals("Bob, Alice", roomMemberSummary(Room(name = "R", memberNames = listOf("Bob", "Alice"))))
        assertEquals("cap 4/round (default)", roomCapSummary(Room(name = "R")))
        assertEquals("cap 7/round", roomCapSummary(Room(name = "R", autoReplyCap = 7)))
    }

    @Test fun `a zero cap is spelled out, never rendered as a bare 0`() {
        // "cap 0/round" reads as "no limit", the opposite of what 0 does.
        val summary = roomCapSummary(Room(name = "R", autoReplyCap = 0))
        assertFalse("a zero cap must not print as a bare number", summary.contains("cap 0"))
        assertTrue("it must say the continuation is off, not unbounded", summary.contains("no autonomous"))
    }
}
