package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantServiceAudioPureTest {

    // (1) H: Digit / numbered-list guard in splitIntoTtsChunks
    @Test
    fun testVersionNumberFollowedByPeriodDoesNotSplit() {
        val input = "Please review version 2. Then proceed to the next step."
        // "Please review version 2." has length 24. With minChunkChars = 20,
        // if falsely split, it emits two chunks: ["Please review version 2.", "Then proceed to the next step."]
        // With the digit guard working, "version 2." is not treated as a sentence boundary.
        val chunks = splitIntoTtsChunks(input, minChunkChars = 20)
        assertEquals(listOf(input), chunks)
    }
}
