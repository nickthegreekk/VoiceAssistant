package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spontaneous messages — the editor UI's pure half.
 *
 * These pin the decision the render gate shows ([spontaneousSectionEligibility]) and the
 * slider<->value mapping ([spontaneousGapFromSlider],
 * [spontaneousWindowMinuteFromSlider]) without a device. The part these CANNOT show — that
 * the gate reads the service's live lists rather than the editor's not-yet-delivered
 * collected copies on the first composition — is pinned on-device by
 * `SpontaneousEditorUiInstrumentedTest`.
 */
class SpontaneousUiTest {

    private companion object {
        val OLLAMA = setOf("HomeBox")
        val CLOUD = setOf("OpenAI", "Anthropic", "Google")
    }

    private fun persona(
        name: String = "Bob",
        model: String = "[HomeBox] llama3",
        cloud: Boolean = false,
        image: Boolean = false,
        translator: Boolean = false
    ) = Persona(
        name = name,
        themeColor = Color(0xFF4ADE80),
        model = model,
        systemPrompt = "You are $name.",
        isCloud = cloud,
        isImageGenerator = image,
        isTranslator = translator
    )

    private fun gate(
        p: Persona = persona(),
        model: String = "[HomeBox] llama3",
        translator: Boolean = false,
        image: Boolean = false,
        ollama: Set<String> = OLLAMA,
        cloud: Set<String> = CLOUD
    ) = spontaneousSectionEligibility(p, model, translator, image, ollama, cloud)

    // ------------------------------------------------------------------ the gate

    @Test fun `a local persona is shown the controls`() {
        assertTrue(gate() is RoomEligibility.Eligible)
    }

    @Test fun `the gate follows the LIVE model text, not the committed one`() {
        val cloudNow = gate(model = "[OpenAI] gpt-4o")
        assertTrue(cloudNow is RoomEligibility.Blocked)
        assertEquals(SpontaneousPolicy.CLOUD_BLOCK_REASON, (cloudNow as RoomEligibility.Blocked).reason)
    }

    @Test fun `the gate trims the model exactly as the router does`() {
        assertTrue(gate(model = " [HomeBox] llama3 ") is RoomEligibility.Eligible)
    }

    @Test fun `the gate reflects the LIVE translator and image flags`() {
        assertTrue(gate(translator = true) is RoomEligibility.Blocked)
        assertTrue(gate(image = true) is RoomEligibility.Blocked)
    }

    @Test fun `a gateway-only persona is told why, not just hidden`() {
        val verdict = gate(model = "llama3")
        assertTrue(verdict is RoomEligibility.Blocked)
        assertTrue((verdict as RoomEligibility.Blocked).reason.isNotBlank())
    }

    @Test fun `an unconfigured local server reads as blocked`() {
        assertTrue(gate(ollama = emptySet()) is RoomEligibility.Blocked)
    }

    // ------------------------------------------------------------------ gap slider

    @Test fun `the gap slider cannot produce anything below the platform floor`() {
        assertEquals(15, spontaneousGapFromSlider(0f))
        assertEquals(15, spontaneousGapFromSlider(1f))
        assertEquals(15, spontaneousGapFromSlider(14.9f))
        assertEquals(15, spontaneousGapFromSlider(-100f))
    }

    @Test fun `the gap slider snaps to fifteen-minute detents`() {
        assertEquals(90, spontaneousGapFromSlider(90f))
        assertEquals(105, spontaneousGapFromSlider(100f))
        assertEquals(240, spontaneousGapFromSlider(240f))
    }

    @Test fun `the gap slider cannot exceed a day`() {
        assertEquals(24 * 60, spontaneousGapFromSlider(10_000f))
    }

    // ------------------------------------------------------------------ window sliders

    @Test fun `the window slider snaps to half-hour detents`() {
        assertEquals(480, spontaneousWindowMinuteFromSlider(480f))
        assertEquals(510, spontaneousWindowMinuteFromSlider(504f))
        assertEquals(0, spontaneousWindowMinuteFromSlider(0f))
    }

    @Test fun `window minutes stay inside the day`() {
        assertEquals(0, spontaneousWindowMinuteFromSlider(-50f))
        assertEquals(24 * 60 - 30, spontaneousWindowMinuteFromSlider(10_000f))
    }

    // ------------------------------------------------------------------ summaries

    @Test fun `minute-of-day formats as zero-padded 24h clock`() {
        assertEquals("08:00", formatMinuteOfDay(480))
        assertEquals("22:30", formatMinuteOfDay(1350))
        assertEquals("00:00", formatMinuteOfDay(0))
        assertEquals("23:59", formatMinuteOfDay(1439))
    }

    @Test fun `an overnight window is labelled, not shown reversed`() {
        assertEquals("08:00–22:00", spontaneousWindowSummary(480, 1320))
        assertEquals("22:00–06:00 (overnight)", spontaneousWindowSummary(1320, 360))
        assertEquals("any time of day", spontaneousWindowSummary(null, null))
        assertEquals("after 08:00", spontaneousWindowSummary(480, null))
        assertEquals("before 22:00", spontaneousWindowSummary(null, 1320))
    }

    @Test fun `the schedule summary names the gap, the default, and the window`() {
        assertEquals(
            "at least 4h apart (default), any time of day",
            spontaneousScheduleSummary(240, true, null, null)
        )
        assertEquals(
            "at least 90m apart, 08:00–22:00",
            spontaneousScheduleSummary(90, false, 480, 1320)
        )
        assertEquals("4h", formatGap(240))
        assertEquals("90m", formatGap(90))
        assertEquals("15m", formatGap(15))
        // The plan bans the word "schedule": it implies an exactness Android cannot deliver.
        assertFalse(spontaneousScheduleSummary(90, false, 480, 1320).contains("schedule"))
    }
}

