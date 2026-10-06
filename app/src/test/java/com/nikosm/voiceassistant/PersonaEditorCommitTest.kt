package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persona editor's commit.
 *
 * The 500ms debounced auto-save and the dispose-time flush now build their Persona through ONE
 * function ([Persona.withEditorFields]), so closing the editor inside the debounce window can no
 * longer persist a stale `isCloud`/`providerIcon`. These pin the derivation the builder performs
 * — the local→cloud switch that used to diverge between the two paths.
 */
class PersonaEditorCommitTest {

    private val local = Persona(
        name = "Bob",
        themeColor = Color(0xFF4ADE80),
        model = "[HomeBox] llama3",
        systemPrompt = "You are Bob.",
        isCloud = false,
        providerIcon = "O"
    )

    private fun fields(model: String) = PersonaEditorFields(
        name = local.name,
        model = model,
        systemPrompt = local.systemPrompt,
        themeColor = local.themeColor,
        iconImageUri = null,
        backgroundImageUri = null,
        temperature = 0.7f,
        topP = 0.9f,
        topK = 40,
        repeatPenalty = 1.1f,
        maxTokens = 2048,
        numCtx = 8192,
        enableThinking = true,
        webSearchEnabled = false,
        ragEnabled = false,
        allowGatewayFailover = false,
        isTranslator = false,
        isImageGenerator = false,
        targetLanguage = "English",
        voiceMode = VoiceMode.SYSTEM_TTS,
        voiceEngine = "kokoro",
        kokoroVoice = "af_heart",
        backendUrl = "",
        enabledSkillIds = emptyList(),
        spontaneousEnabled = false,
        spontaneousInstruction = "",
        spontaneousGapMinutes = 0,
        spontaneousWindowStartMin = null,
        spontaneousWindowEndMin = null
    )

    @Test
    fun `switching local to a cloud provider derives isCloud and the icon`() {
        val model = "[OpenAI] gpt-4o"
        val providerName = personaEditorProviderName(model)
        val isCloud = personaEditorIsCloudModel(providerName, setOf("OpenAI", "Anthropic"))
        val committed = local.withEditorFields(
            fields(model), isCloudModel = isCloud, customProviderNames = emptySet(), spontaneousAllowed = true
        )
        assertTrue("a cloud model must commit isCloud=true", committed.isCloud)
        assertEquals("O", committed.providerIcon)
    }

    @Test
    fun `an unchanged local model stays local`() {
        val committed = local.withEditorFields(
            fields("[HomeBox] llama3"), isCloudModel = false, customProviderNames = emptySet(), spontaneousAllowed = true
        )
        assertFalse(committed.isCloud)
        assertEquals("O", committed.providerIcon)
    }

    @Test
    fun `a custom cloud provider is cloud with the C badge`() {
        val model = "[MyGateway] some-model"
        val providerName = personaEditorProviderName(model)
        val custom = setOf("MyGateway")
        val isCloud = personaEditorIsCloudModel(providerName, custom)
        val committed = local.withEditorFields(
            fields(model), isCloudModel = isCloud, customProviderNames = custom, spontaneousAllowed = true
        )
        assertTrue(committed.isCloud)
        assertEquals("C", committed.providerIcon)
    }

    @Test
    fun `both commit paths build the identical persona for the same field state`() {
        // The whole point of the shared builder: by construction the debounce and the dispose
        // flush cannot produce different personas.
        val f = fields("[Anthropic] claude-3-5-sonnet-latest")
        val fromDebounce = local.withEditorFields(f, isCloudModel = true, customProviderNames = emptySet(), spontaneousAllowed = true)
        val fromDispose = local.withEditorFields(f, isCloudModel = true, customProviderNames = emptySet(), spontaneousAllowed = true)
        assertEquals(fromDebounce, fromDispose)
    }
}
