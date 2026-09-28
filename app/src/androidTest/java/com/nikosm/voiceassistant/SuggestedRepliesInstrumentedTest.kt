package com.nikosm.voiceassistant

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SuggestedRepliesInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun testPersonaFilteringAndPromptStructure() {
        val translatorPersona = DEFAULT_PERSONAS[0].copy(isTranslator = true, isImageGenerator = false)
        val imageGenPersona = DEFAULT_PERSONAS[0].copy(isTranslator = false, isImageGenerator = true)
        val normalPersona = DEFAULT_PERSONAS[0].copy(isTranslator = false, isImageGenerator = false, model = "test-model")

        assertTrue(translatorPersona.isTranslator)
        assertTrue(imageGenPersona.isImageGenerator)
        assertFalse(normalPersona.isTranslator)
        assertFalse(normalPersona.isImageGenerator)
    }
}
