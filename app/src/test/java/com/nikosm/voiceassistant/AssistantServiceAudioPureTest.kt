package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantServiceAudioPureTest {

    // (1) H: Digit / numbered-list guard in splitIntoTtsChunks
    @Test
    fun testVersionNumberFollowedByPeriodDoesNotSplit() {
        val input = "Please review version 2. Then proceed to the next step."
        val chunks = splitIntoTtsChunks(input, minChunkChars = 20)
        assertEquals(listOf(input), chunks)
    }

    // (1) I: Unicode uppercase \p{Lu} and terminators (Greek ;, CJK, Russian, Accented Latin)
    @Test
    fun testGreekSentenceEndWithSemicolonAndUnicodeUppercase() {
        val input = "Καλημέρα σας; Πώς είστε σήμερα;"
        val chunks = splitIntoTtsChunks(input, minChunkChars = 10)
        assertEquals(listOf("Καλημέρα σας;", "Πώς είστε σήμερα;"), chunks)
    }

    @Test
    fun testRussianSentenceEndWithUnicodeUppercase() {
        val input = "Это первое предложение. Второе предложение достаточно длинное."
        val chunks = splitIntoTtsChunks(input, minChunkChars = 10)
        assertEquals(listOf("Это первое предложение.", "Второе предложение достаточно длинное."), chunks)
    }

    @Test
    fun testAccentedLatinSentenceEndWithUnicodeUppercase() {
        val input = "C'est la première phrase! Écoutez attentivement ce message."
        val chunks = splitIntoTtsChunks(input, minChunkChars = 10)
        assertEquals(listOf("C'est la première phrase!", "Écoutez attentivement ce message."), chunks)
    }

    @Test
    fun testCjkSentenceEndTerminators() {
        val input = "这是第一句话。这是第二句话！还有第三句话？"
        val chunks = splitIntoTtsChunks(input, minChunkChars = 5)
        assertEquals(listOf("这是第一句话。", "这是第二句话！", "还有第三句话？"), chunks)
    }

    @Test
    fun testVersionNumberFollowedByPeriodWithUnicodeUppercaseGuard() {
        // "version 2. Then" should NOT split at "2." even with \p{Lu}
        val input = "We updated to version 2. Then everything worked much better."
        val chunks = splitIntoTtsChunks(input, minChunkChars = 20)
        assertEquals(listOf(input), chunks)
    }
}
