package com.nikosm.voiceassistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name half of the thinking-model check. The catalog half (`isThinkingModel`) uses the
 * live Ollama capabilities when available; this pins the fallback families so a model whose
 * catalog entry was never fetched can still receive `think: true` when the user asks for it.
 */
class ThinkingModelDetectionTest {

    @Test
    fun knownReasoningFamiliesAreRecognised() {
        assertTrue(isKnownThinkingModelName("deepseek-r1:8b"))
        assertTrue(isKnownThinkingModelName("deepseek-reasoner"))
        assertTrue(isKnownThinkingModelName("qwq:32b"))
        assertTrue(isKnownThinkingModelName("ornith-1.5:9b"))
    }

    @Test
    fun ordinaryModelsAreNotTreatedAsThinking() {
        assertFalse(isKnownThinkingModelName("llama3:8b"))
        assertFalse(isKnownThinkingModelName("mistral:7b"))
        assertFalse(isKnownThinkingModelName("granite4.1:3b"))
    }
}
