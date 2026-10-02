package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

    // ------------------------------------------------------------- Persona backdrop

    @Test fun `a room never wears the selected persona background`() {
        // The reported bug: the backdrop is drawn in the Scaffold's shared content body, so
        // without this gate a room inherited whichever persona was selected when it was
        // opened — their photo, and the 0.55 scrim that goes with it, behind the transcript.
        val room = Room(name = "R", memberNames = listOf("Bob", "Alice"))
        assertNull(
            "room mode has no persona of its own, so no persona backdrop may paint behind it",
            personaBackdropPath(room, "/data/user/0/app/files/persona_images/background_1.jpg")
        )
    }

    @Test fun `a room member's background is not lent to the room either`() {
        // Membership does not change the relationship: the backdrop is that persona's CHAT,
        // not a property of who happens to be in the room.
        val room = Room(name = "R", memberNames = listOf("Bob"))
        assertNull(personaBackdropPath(room, "/files/persona_images/background_1.jpg"))
    }

    @Test fun `single chat shows the persona backdrop, and an absent or blank path stays none`() {
        val path = "/files/persona_images/background_1.jpg"
        assertEquals("no room means the persona's own backdrop, unchanged", path, personaBackdropPath(null, path))
        assertNull("no backdrop set", personaBackdropPath(null, null))
        assertNull("a blank path is not an image", personaBackdropPath(null, "   "))
    }

    // ------------------------------------------------- Editor commit (auto-save)

    private fun room(name: String = "R", cap: Int? = null, members: List<String> = listOf("A", "B")) =
        Room(name = name, memberNames = members, autoReplyCap = cap)

    @Test fun `an edited name commits`() {
        val committed = roomEditorCommit(room(name = "Old"), "New", "")
        assertEquals("New", committed?.name)
        assertNull(committed?.autoReplyCap)
    }

    @Test fun `a name edit carries the cap and members it was made alongside`() {
        // The commit is the whole room, not a name-only patch — that is what keeps a member
        // edit and a name edit made in the same session from clobbering each other.
        val committed = roomEditorCommit(
            room(name = "Old", cap = 3, members = listOf("A", "B", "C")),
            "New",
            "5"
        )
        assertEquals("New", committed?.name)
        assertEquals(5, committed?.autoReplyCap)
        assertEquals(listOf("A", "B", "C"), committed?.memberNames)
    }

    @Test fun `an unchanged editor commits nothing`() {
        // null is load-bearing: it is what stops the auto-save and the dispose-time flush
        // from rewriting the room on every recomposition.
        assertNull(roomEditorCommit(room(name = "Same"), "Same", ""))
        assertNull(roomEditorCommit(room(name = "Same", cap = 2), "Same", "2"))
        assertNull(roomEditorCommit(room(name = "Same"), "  Same  ", ""))
    }

    @Test fun `blank name and garbage cap are never committed`() {
        // A half-deleted name is not a room, and an uncommittable cap is never coerced —
        // the stored value must survive an in-progress edit untouched.
        assertNull(roomEditorCommit(room(name = "Old"), "", ""))
        assertNull(roomEditorCommit(room(name = "Old"), "   ", ""))
        assertNull(roomEditorCommit(room(name = "Old", cap = 4), "New", "abc"))
        assertNull(roomEditorCommit(room(name = "Old", cap = 4), "New", "-1"))
    }

    @Test fun `a half-deleted name does not destroy the cap`() {
        // The specific hazard of committing the whole room: clearing the name must not also
        // reset the cap the user set earlier in the session.
        assertNull(roomEditorCommit(room(name = "Old", cap = 6), "  ", ""))
    }

    @Test fun `clearing the cap back to blank commits the inherit value`() {
        // null (inherit) and 0 (initial round only) are different states; clearing the field
        // has to move between them, not sit still.
        val committed = roomEditorCommit(room(name = "R", cap = 6), "R", "")
        assertNotNull("clearing an explicit cap must commit", committed)
        assertNull(committed?.autoReplyCap)
    }

    @Test fun `zero survives a commit as zero`() {
        val committed = roomEditorCommit(room(name = "R"), "R", "0")
        assertEquals(0, committed?.autoReplyCap)
    }
}
