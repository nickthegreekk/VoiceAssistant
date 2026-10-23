package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * redactBackupCredentials removes credentials and nothing else. It once rebuilt BackupData from
 * a constructor call and silently dropped every field that call did not name — persona chats and
 * persona artwork — because both are defaulted. Android has no messageImages or scheduledSkills.
 */
class RedactionPreservesDataTest {
    @Test
    fun redactionCarriesEveryNonCredentialFieldThrough() {
        val messages = listOf(ChatMessage(role = "user", text = "hello"))
        val personaMessages = mapOf("Persona" to listOf(ChatMessage(role = "assistant", text = "hi")))
        val personaImages = mapOf("Persona" to PersonaArtwork(icon = "icon-bytes", background = "bg-bytes"))
        val rooms = listOf(Room(id = "room-1", name = "Room", memberNames = listOf("Persona", "Other")))
        val roomMessages = mapOf(
            "room-1" to listOf(RoomMessage(id = "rm-1", speakerName = "user", text = "hello room"))
        )
        val skills = listOf(
            Skill(id = "skill-1", name = "Skill", triggerPhrases = listOf("do"), instructions = "Do it")
        )
        val imageWorkflows = listOf(ImageWorkflow(id = "wf-1", name = "Character", workflow = "{}"))
        val personas = listOf(
            Persona(name = "Persona", themeColor = Color(0xFF112233), model = "m", systemPrompt = "p")
        )
        val personaMemories = mapOf("Persona" to listOf("remembered fact"))
        val personaMemoryEnabled = listOf("Persona")
        val favoriteModels = listOf("llama3")
        val trustedCerts = mapOf("host.example" to "pin")
        val userLocation = "Athens"
        val totalCost = 1.25

        val backup = BackupData(
            serverBases = emptyList(),
            ollamaBases = emptyList(),
            imageGenBases = emptyList(),
            cloudApis = emptyList(),
            customCloudApis = emptyList(),
            personas = personas,
            messages = messages,
            personaMessages = personaMessages,
            totalCost = totalCost,
            favoriteModels = favoriteModels,
            trustedCerts = trustedCerts,
            searxngUrl = "https://searx.example/search?q=1",
            userLocation = userLocation,
            ragServerUrl = "https://user:pass@rag.example/v1?key=1",
            ragUsername = "raguser",
            ragPassword = "ragpass",
            personaImages = personaImages,
            rooms = rooms,
            roomMessages = roomMessages,
            skills = skills,
            imageWorkflows = imageWorkflows,
            personaMemories = personaMemories,
            personaMemoryEnabled = personaMemoryEnabled,
            activeImageGenBackend = ImageGenBackend.COMFYUI
        )

        val redacted = redactBackupCredentials(backup)

        assertEquals(messages, redacted.messages)
        assertEquals(personaMessages, redacted.personaMessages)
        assertEquals(personaImages, redacted.personaImages)
        assertEquals(roomMessages, redacted.roomMessages)
        assertEquals(rooms, redacted.rooms)
        assertEquals(skills, redacted.skills)
        assertEquals(personas, redacted.personas)
        assertEquals(imageWorkflows, redacted.imageWorkflows)
        assertEquals(personaMemories, redacted.personaMemories)
        assertEquals(personaMemoryEnabled, redacted.personaMemoryEnabled)
        assertEquals(favoriteModels, redacted.favoriteModels)
        assertEquals(totalCost, redacted.totalCost, 0.0)
        assertEquals(trustedCerts, redacted.trustedCerts)
        assertEquals(userLocation, redacted.userLocation)
        assertEquals(ImageGenBackend.COMFYUI, redacted.activeImageGenBackend)
    }
}
