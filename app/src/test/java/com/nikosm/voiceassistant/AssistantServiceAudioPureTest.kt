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

    // (1) K: Timestamp JSON escaping
    @Test
    fun testWordTimestampsJsonEscapesQuotes() {
        val timestamps = listOf(
            mapOf("word" to """said, "hello"""", "start" to 0.0, "end" to 0.5)
        )
        val json = formatWordTimestampsJson(timestamps)
        val parsed = org.json.JSONArray(json)
        assertEquals(1, parsed.length())
        assertEquals("""said, "hello"""", parsed.getJSONObject(0).getString("word"))
    }

    // (1) J: WAV duration with LIST chunk and byteRate fallback
    @Test
    fun testWavDurationWithListChunkBeforeData() {
        // Construct a WAV with a LIST chunk placed before the data chunk:
        // sampleRate = 16000, 1 ch, 16-bit (2 bytes/sample) -> 32000 bytes/sec
        // data size = 16000 bytes -> duration = 0.5s
        val listChunk = "LIST".toByteArray() + byteArrayOf(4, 0, 0, 0) + "INFO".toByteArray()
        val dataChunk = "data".toByteArray() + byteArrayOf(
            (16000 and 0xFF).toByte(),
            ((16000 shr 8) and 0xFF).toByte(),
            ((16000 shr 16) and 0xFF).toByte(),
            ((16000 shr 24) and 0xFF).toByte()
        ) + ByteArray(16000)

        val fmtChunk = "fmt ".toByteArray() + byteArrayOf(
            16, 0, 0, 0, // fmt chunk size = 16
            1, 0,        // PCM = 1
            1, 0,        // 1 channel
            (16000 and 0xFF).toByte(), ((16000 shr 8) and 0xFF).toByte(), 0, 0, // sampleRate = 16000
            (32000 and 0xFF).toByte(), ((32000 shr 8) and 0xFF).toByte(), 0, 0, // byteRate = 32000
            2, 0,        // blockAlign = 2
            16, 0        // bitsPerSample = 16
        )

        val totalSize = 4 + fmtChunk.size + listChunk.size + dataChunk.size
        val riffHeader = "RIFF".toByteArray() + byteArrayOf(
            (totalSize and 0xFF).toByte(),
            ((totalSize shr 8) and 0xFF).toByte(),
            ((totalSize shr 16) and 0xFF).toByte(),
            ((totalSize shr 24) and 0xFF).toByte()
        ) + "WAVE".toByteArray()

        val wavBytes = riffHeader + fmtChunk + listChunk + dataChunk
        val duration = wavDurationSeconds(wavBytes)
        // Fixed offset 40 in old code reads the size of LIST chunk (4) instead of data (16000), giving ~0.000125
        assertEquals(0.5, duration, 0.001)
    }

    @Test
    fun testWavDurationFallbackWhenByteRateIsZero() {
        // sampleRate = 16000, 1 ch, 16-bit, byteRate = 0
        // data size = 16000 bytes -> duration = 0.5s
        val fmtChunk = "fmt ".toByteArray() + byteArrayOf(
            16, 0, 0, 0, // fmt chunk size = 16
            1, 0,        // PCM = 1
            1, 0,        // 1 channel
            (16000 and 0xFF).toByte(), ((16000 shr 8) and 0xFF).toByte(), 0, 0, // sampleRate = 16000
            0, 0, 0, 0,  // byteRate = 0 (triggers fallback)
            2, 0,        // blockAlign = 2
            16, 0        // bitsPerSample = 16
        )
        val dataChunk = "data".toByteArray() + byteArrayOf(
            (16000 and 0xFF).toByte(),
            ((16000 shr 8) and 0xFF).toByte(),
            ((16000 shr 16) and 0xFF).toByte(),
            ((16000 shr 24) and 0xFF).toByte()
        ) + ByteArray(16000)

        val totalSize = 4 + fmtChunk.size + dataChunk.size
        val riffHeader = "RIFF".toByteArray() + byteArrayOf(
            (totalSize and 0xFF).toByte(),
            ((totalSize shr 8) and 0xFF).toByte(),
            ((totalSize shr 16) and 0xFF).toByte(),
            ((totalSize shr 24) and 0xFF).toByte()
        ) + "WAVE".toByteArray()

        val wavBytes = riffHeader + fmtChunk + dataChunk
        val duration = wavDurationSeconds(wavBytes)
        // Old code fallback missed sampleRate: (1 * 16 / 8 = 2 bytes/sec), resulting in 8000.0s
        assertEquals(0.5, duration, 0.001)
    }

    // (2) A: eSpeak marker frame calculation
    @Test
    fun testEspeakMarkerFramesEqualsShortSampleCount() {
        // In PCM 16-bit mono, 1 Short = 1 sample = 1 frame.
        // For 16000 shorts at 16kHz (1.0 second), total frames must be 16000, NOT 8000.
        val sampleCount = 16000
        val frames = espeakMarkerFrames(sampleCount)
        assertEquals(16000, frames)
    }
}
