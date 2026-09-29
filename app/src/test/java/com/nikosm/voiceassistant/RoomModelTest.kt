package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 (multi-persona Rooms): the data model and the reply policy.
 *
 * [Persona.roomEligibility] mirrors the real routing branches, and that is the part worth
 * pinning: a persona the router cannot serve conversationally must be BLOCKED, or a room
 * turn would land in the single-chat transcript (the image-generator hazard) or bypass the
 * room's context entirely (the gateway, which sends no history).
 */
class RoomModelTest {

    private companion object {
        // The configured Ollama server names and cloud provider names, as the
        // real router would resolve them.
        val OLLAMA = setOf("HomeBox")
        val CLOUD = setOf("OpenAI", "Anthropic", "Google")
    }

    private fun persona(
        name: String,
        model: String = "[HomeBox] llama3",
        cloud: Boolean = false,
        image: Boolean = false,
        translator: Boolean = false
    ) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = model,
        systemPrompt = "You are $name.",
        isCloud = cloud,
        isImageGenerator = image,
        isTranslator = translator
    )

    private fun room(vararg members: String, cap: Int? = null) =
        Room(id = "room-1", name = "War Room", memberNames = members.toList(), autoReplyCap = cap)

    // ------------------------------------------------------------- Identity

    @Test fun `room id defaults to a unique uuid, not the name`() {
        val a = Room(name = "Same Name")
        val b = Room(name = "Same Name")
        assertFalse("two rooms must never share an id", a.id == b.id)
        // Renaming is free: the id is independent of the display name, so a rename can
        // never collide with another room's transcript.
        assertEquals(a.id, a.copy(name = "Renamed").id)
    }

    @Test fun `room survives a round trip through kotlinx serialization`() {
        val original = room("A", "B", cap = 7)
        val encoded = Json.encodeToString(Room.serializer(), original)
        assertEquals(original, Json.decodeFromString(Room.serializer(), encoded))
    }

    @Test fun `room message round-trips and identifies the human by a reserved name`() {
        val user = RoomMessage(speakerName = RoomMessage.USER_SPEAKER, text = "hi")
        val agent = RoomMessage(speakerName = "A", text = "hello", responseTimeMs = 12L)
        assertTrue("the reserved speaker name must map to isUser", user.isUser)
        assertFalse(agent.isUser)
        assertEquals(user, RoomMessage.serializer().let { Json.decodeFromString(it, Json.encodeToString(it, user)) })
        assertEquals(agent, RoomMessage.serializer().let { Json.decodeFromString(it, Json.encodeToString(it, agent)) })
    }

    // ------------------------------------------------------------- Eligibility

    @Test fun `direct-ollama and cloud personas are eligible`() {
        assertTrue(persona("A").roomEligibility(OLLAMA, CLOUD) is RoomEligibility.Eligible)
        val cloud = persona("C", model = "[OpenAI] gpt-4o", cloud = true).roomEligibility(OLLAMA, CLOUD)
        assertTrue(cloud is RoomEligibility.Eligible)
        assertTrue("cloud personas must be marked as cloud", (cloud as RoomEligibility.Eligible).cloud)
    }

    @Test fun `image-generator personas are blocked`() {
        val result = persona("I", image = true).roomEligibility(OLLAMA, CLOUD)
        assertTrue("image generators must never be offered to a room", result is RoomEligibility.Blocked)
    }

    @Test fun `translator personas are blocked`() {
        assertTrue(persona("T", translator = true).roomEligibility(OLLAMA, CLOUD) is RoomEligibility.Blocked)
    }

    @Test fun `gateway-only personas are blocked`() {
        // No bracket prefix: the router reaches Ollama through the gateway's
        // history-blind backend_url path, so a room turn would see no room context.
        assertTrue(persona("G", model = "llama3").roomEligibility(OLLAMA, CLOUD) is RoomEligibility.Blocked)
        // A bracket prefix naming a server that is NOT configured as Ollama is the
        // same situation: it falls through to the gateway.
        assertTrue(persona("G2", model = "[Unconfigured] llama3").roomEligibility(OLLAMA, CLOUD) is RoomEligibility.Blocked)
    }

    @Test fun `blank model is blocked`() {
        assertTrue(persona("B", model = "").roomEligibility(OLLAMA, CLOUD) is RoomEligibility.Blocked)
    }

    @Test fun `isCloud without a matching provider is blocked, not treated as cloud`() {
        val result = persona("X", model = "[NotConfigured] llama3", cloud = true).roomEligibility(OLLAMA, CLOUD)
        assertTrue(result is RoomEligibility.Blocked)
    }

    @Test fun `eligibility parses the model exactly as the router does, including trim`() {
        // The router's isCloudModel() trims before testing the "[Provider] model" form, so
        // a padded cloud model still ROUTES to cloud. Eligibility must agree, or a persona
        // the router would serve as cloud could be offered to a room and then take the
        // direct-Ollama/gateway branch instead.
        val padded = persona("P", model = "  [OpenAI] gpt-4o  ", cloud = true)
        val result = padded.roomEligibility(OLLAMA, CLOUD)
        assertTrue("a padded cloud model must still be eligible", result is RoomEligibility.Eligible)
        assertTrue("and must be recognised as cloud", (result as RoomEligibility.Eligible).cloud)

        // Same for a padded direct-Ollama model.
        assertTrue(persona("P2", model = " [HomeBox] llama3 ").roomEligibility(OLLAMA, CLOUD) is RoomEligibility.Eligible)
    }

    @Test fun `eligibleRoomMembers filters to eligible personas and preserves order`() {
        val list = listOf(
            persona("A"), persona("I", image = true), persona("C", model = "[OpenAI] gpt-4o", cloud = true)
        )
        val eligible = eligibleRoomMembers(list, OLLAMA, CLOUD).map { it.name }
        assertEquals(listOf("A", "C"), eligible)
    }

    // ------------------------------------------------------------- Reply policy

    @Test fun `the default cap is four and a room can override it`() {
        assertEquals(4, RoomReplyPolicy.DEFAULT_AUTO_REPLY_CAP)
        assertEquals(4, RoomReplyPolicy.capFor(room("A", "B")))
        assertEquals(9, RoomReplyPolicy.capFor(room("A", "B", cap = 9)))
    }

    @Test fun `cloud personas join the initial round but never the autonomous one`() {
        assertFalse(RoomReplyPolicy.participatesInAutonomousRounds(cloud = true))
        assertTrue(RoomReplyPolicy.participatesInAutonomousRounds(cloud = false))
    }
}
