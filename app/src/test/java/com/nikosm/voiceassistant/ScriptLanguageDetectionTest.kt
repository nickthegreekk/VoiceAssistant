package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M3 + L1: pins the zero-dependency script heuristic behind gateway TTS voice routing
 * ([detectScriptLanguage]) — the classification the reply is spoken with.
 *
 *  - **M3 (kana wins outright):** every kanji in Japanese text falls in the Han range that
 *    is counted as "Chinese", and ordinary Japanese is frequently kanji-majority, so the
 *    old dominance count could outvote the kana and route a Japanese reply to a Mandarin
 *    voice. Kana is exclusive to Japanese, so its mere presence is now decisive.
 *  - **L1 (the whole text is examined):** the old code sampled only the first 200
 *    characters, so a Latin-script preamble ("Here is the translation: …") with the actual
 *    foreign text after it was classified as Latin and spoken by the English voice. Latin
 *    characters contribute nothing to the counts, so the window could only ever lose
 *    evidence.
 *
 * The function has no Android surface, so this runs on the JVM; the suspend wrapper
 * (`detectGatewayResponseLanguage`) only adds the "English" / exception defaults around it.
 */
class ScriptLanguageDetectionTest {

    @Test
    fun kanjiHeavyJapaneseIsJapaneseBecauseOneKanaIsPresent() {
        // 19 kanji and a single hiragana: the character count says "Chinese", the script
        // says "Japanese", and the script has to win.
        val kanjiHeavy = "東京大学法学部政治学科研究室発表会資料" + "の"

        assertEquals("Japanese", detectScriptLanguage(kanjiHeavy))
    }

    @Test
    fun everyKanaScriptAloneIsJapanese() {
        assertEquals("Japanese", detectScriptLanguage("ありがとうございます"))
        assertEquals("Japanese", detectScriptLanguage("コンピューター"))
        assertEquals("Japanese", detectScriptLanguage("テスト"))
    }

    @Test
    fun kanaOutranksACrowdOfKanji() {
        val mostlyHan = "日本語文章漢字表現" + "です"

        assertEquals("Japanese", detectScriptLanguage(mostlyHan))
    }

    @Test
    fun chineseWithoutKanaStaysChinese() {
        assertEquals(
            "Chinese",
            detectScriptLanguage("这是一个完全用简体中文写成的长句子，不带任何假名。")
        )
    }

    @Test
    fun theOtherSupportedNonLatinScriptsAreDetected() {
        assertEquals("Russian", detectScriptLanguage("Привет, как дела?"))
        assertEquals("Greek", detectScriptLanguage("Καλημέρα, τι κάνεις;"))
        assertEquals("Hebrew", detectScriptLanguage("שלום, מה שלומך?"))
        assertEquals("Arabic", detectScriptLanguage("مرحبا، كيف حالك؟"))
        assertEquals("Korean", detectScriptLanguage("안녕하세요, 반갑습니다"))
        // Devanagari is a LANG_CONFIG language; omitting it was the original bug class.
        assertEquals("Hindi", detectScriptLanguage("नमस्ते, आप कैसे हैं?"))
    }

    @Test
    fun latinScriptTextIsNotClassified() {
        assertNull(detectScriptLanguage("Hello, how are you today?"))
        assertNull(detectScriptLanguage("Voici la traduction que vous avez demandée."))
    }

    @Test
    fun anEmptyOrPunctuationOnlySampleIsNotClassified() {
        assertNull(detectScriptLanguage(""))
        assertNull(detectScriptLanguage("   \n\t  "))
        assertNull(detectScriptLanguage("!!! ... --- ???"))
        assertNull(detectScriptLanguage("1234567890"))
    }

    // ---------------------------------------------------------------------
    // L1: the Latin preamble must not hide the text that follows it.
    // ---------------------------------------------------------------------

    @Test
    fun aLongLatinPreambleDoesNotHideTheForeignTextAfterIt() {
        // 320 Latin characters ahead of the Greek sentence: longer than the old 200-char
        // window, so the entire sample used to be Latin-only -> "English".
        val preamble = "Here is the translation you asked for: ".repeat(8)

        assertEquals("Greek", detectScriptLanguage(preamble + "Καλημέρα φίλε μου"))
    }

    @Test
    fun foreignTextDetectedBeyondTheOldWindowBoundaryIsStillCounted() {
        // The old window ended inside the Latin run at 200 chars; the Cyrillic that follows
        // is now part of the sample.
        val longLatin = "The answer follows in the next sentence. ".repeat(6)

        assertEquals("Russian", detectScriptLanguage(longLatin + "Привет мир"))
    }

    // ---------------------------------------------------------------------
    // The dominance/threshold rules themselves.
    // ---------------------------------------------------------------------

    @Test
    fun fewerThanThreeNonLatinCharactersIsTreatedAsNoise() {
        assertNull("2 Greek characters", detectScriptLanguage("Hi αβ"))
        assertEquals("3 Greek characters", "Greek", detectScriptLanguage("Hi αβγ"))
    }

    @Test
    fun twoMinorityScriptsDoNotAddUpTowardTheThreshold() {
        // Greek 2 + Cyrillic 1: the threshold is per script, so neither qualifies.
        assertNull(detectScriptLanguage("Hi αβ П"))
        // Cyrillic 3 crosses it on its own.
        assertEquals("Russian", detectScriptLanguage("Hi αβ При"))
    }

    @Test
    fun theMajorityScriptWinsBetweenTwoQualifyingOnes() {
        assertEquals("Russian", detectScriptLanguage("Привет мир как дела αβγ"))
        assertEquals("Greek", detectScriptLanguage("Καλημέρα φίλε μου При"))
    }
}
