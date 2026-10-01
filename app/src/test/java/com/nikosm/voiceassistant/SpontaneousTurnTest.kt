package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spontaneous messages — the turn's pure half.
 *
 * These pin the request-assembly pieces shared with the production paths
 * ([composeSystemPrompt], [AssistantService.budgetedHistory]), the framing and the
 * silence(parser). Everything under test is pure, so silence — the outcome that leaves
 * nothing in the user's chat — is verified deterministically here rather than against a
 * stub. The parts that genuinely need a server (the non-streaming call itself, the sink,
 * persistence) are instrumented instead (`SpontaneousTurnInstrumentedTest`).
 */
class SpontaneousTurnTest {

    private fun persona(name: String = "Bob") = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = "[HomeBox] llama3",
        systemPrompt = "You are a warm friend.",
        isCloud = false
    )

    // ------------------------------------------------------------------ framing

    @Test fun `the turn instruction addresses the persona and permits silence`() {
        val instruction = spontaneousTurnInstruction(persona("Mabel"))
        assertTrue(instruction.contains("Mabel"))
        assertTrue(instruction.contains(SPONTANEOUS_SILENCE_SENTINEL))
        assertTrue(instruction.contains("ONE"))
    }

    @Test fun `the empty-history context line does not invent a conversation`() {
        assertTrue(spontaneousContextLine(0).contains("have not talked"))
        assertTrue(spontaneousContextLine(3).contains("recent conversation"))
    }

    @Test fun `the system suffix leads with the app's framing and ends with the persona's own words`() {
        val withCustom = spontaneousSystemPromptSuffix(
            persona().copy(spontaneousInstruction = "only ever ask about the garden"),
            historySize = 2
        )
        assertTrue("the persona's own text must ride the suffix", withCustom.contains("only ever ask about the garden"))
        assertTrue(
            "the custom text must come after the framing, not replace it",
            withCustom.indexOf("own initiative") < withCustom.indexOf("only ever ask about the garden")
        )
        val withoutCustom = spontaneousSystemPromptSuffix(persona(), historySize = 0)
        assertFalse("a blank custom instruction contributes nothing", withoutCustom.contains("  "))
        assertTrue(withoutCustom.isNotBlank())
    }

    // ------------------------------------------------------------------ composition order

    @Test fun `the composed prompt keeps the exact order the budgeted builders depend on`() {
        // Regression anchor after the extraction: date/time, then the persona's own prompt,
        // then search/RAG context, then the turn's suffix — re-ordering this silently
        // re-budgets everything, so the order itself is pinned byte-for-byte.
        val composed = composeSystemPrompt(
            persona = persona(),
            currentDateTime = "Mon",
            searchContext = "SEARCH-HIT",
            ragContext = "RAG-HIT",
            suffix = "ONE-TURN SUFFIX"
        )
        val expected = "SEARCH-HIT\n\nRAG-HIT\n\n" +
            "Current date and time: Mon\n\nYou are a warm friend.\n\n" +
            "ONE-TURN SUFFIX"
        assertEquals(expected, composed)
        // The suffix lands LAST: that is exactly what makes it count toward the budget.
        assertTrue(composed.endsWith("ONE-TURN SUFFIX"))
    }

    @Test fun `empty context and suffix leave a clean two-part prompt with no stray separators`() {
        assertEquals(
            "Current date and time: Mon\n\nYou are a warm friend.",
            composeSystemPrompt(
                persona = persona(), currentDateTime = "Mon",
                searchContext = "", ragContext = "", suffix = ""
            )
        )
        assertEquals(
            "Current date and time: Mon\n\nYou are a warm friend.\n\nONE-TURN SUFFIX",
            composeSystemPrompt(
                persona = persona(), currentDateTime = "Mon",
                searchContext = "", ragContext = "", suffix = "ONE-TURN SUFFIX"
            )
        )
    }


    // ------------------------------------------------------------------ silence

    @Test fun `blank model output is silence`() {
        assertNull(parseSpontaneousReply(""))
        assertNull(parseSpontaneousReply("   \n  "))
    }

    @Test fun `the sentinel, however wrapped, is silence`() {
        assertNull(parseSpontaneousReply(SPONTANEOUS_SILENCE_SENTINEL))
        assertNull(parseSpontaneousReply("  ${SPONTANEOUS_SILENCE_SENTINEL.lowercase()}  "))
        assertNull(parseSpontaneousReply("[$SPONTANEOUS_SILENCE_SENTINEL]"))
        assertNull(parseSpontaneousReply("($SPONTANEOUS_SILENCE_SENTINEL)"))
        assertNull(parseSpontaneousReply("*$SPONTANEOUS_SILENCE_SENTINEL*"))
        assertNull(parseSpontaneousReply("SILENCE"))
        assertNull(parseSpontaneousReply(" silence "))
        // The instruction's sentence matches the "wrapped sentinel" shape above and is
        // correctly stripped — preference: when the instruction text itself leaks into
        // the output as the whole message, that is NOT a message TO the user.
    }

    @Test fun `an output that merely mentions the sentinel is still sent`() {
        // Deliberately asserts the LENIENT behavior: "contains the sentinel ⇒ silent" would
        // be safer against a leak of the instruction text, but it would also swallow a
        // genuinely friendly check-in that happened to *mention* the sentinel
        // ("No silence today, friend — how are you?"). Containment would be the right call
        // the moment the delivery path carries any user-facing cost beyond a chat bubble.
        assertEquals(
            "reply with exactly $SPONTANEOUS_SILENCE_SENTINEL and nothing else",
            parseSpontaneousReply("reply with exactly $SPONTANEOUS_SILENCE_SENTINEL and nothing else")
        )
    }

    @Test fun `anything else is sent as written`() {
        assertEquals("Hey — how did the trip go?", parseSpontaneousReply("  Hey — how did the trip go?  "))
        assertEquals("One line.", parseSpontaneousReply("One line."))
        // Looks LIKE the rule but carries a message — the sender decides, not the parser.
        assertEquals(
            "I was silent for a while, sorry — how are you?",
            parseSpontaneousReply("I was silent for a while, sorry — how are you?")
        )
    }
}
