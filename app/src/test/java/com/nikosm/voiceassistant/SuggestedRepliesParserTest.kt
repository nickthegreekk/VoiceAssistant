package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestedRepliesParserTest {

    @Test
    fun parseNumberedList() {
        val input = "1. Tell me more\n2. What is an example?\n3. Thanks!"
        val result = parseSuggestedReplies(input)
        assertEquals(listOf("Tell me more", "What is an example?", "Thanks!"), result)
    }

    @Test
    fun parseBulletedList() {
        val input = "- Can you clarify?\n• Show me code\n- Next steps"
        val result = parseSuggestedReplies(input)
        assertEquals(listOf("Can you clarify?", "Show me code", "Next steps"), result)
    }

    @Test
    fun parseQuotedAndPlain() {
        val input = "\"That makes sense\"\n'Can we skip this?'\nShow another option"
        val result = parseSuggestedReplies(input)
        assertEquals(listOf("That makes sense", "Can we skip this?", "Show another option"), result)
    }

    @Test
    fun dropDuplicatesAndBlanks() {
        val input = "\n\n1. Tell me more\n\n1. Tell me more\n- Next steps\n"
        val result = parseSuggestedReplies(input)
        assertEquals(listOf("Tell me more", "Next steps"), result)
    }

    @Test
    fun capAtThree() {
        val input = "One\nTwo\nThree\nFour\nFive"
        val result = parseSuggestedReplies(input)
        assertEquals(listOf("One", "Two", "Three"), result)
    }

    @Test
    fun emptyOrInvalidOutput() {
        val input = ""
        val result = parseSuggestedReplies(input)
        assertEquals(emptyList<String>(), result)
    }
}
