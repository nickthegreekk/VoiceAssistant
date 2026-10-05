package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The chat header's two taps are different actions: the icon opens the persona EDITOR, the
 * name opens the persona SWITCHER.
 *
 * `Header` takes all of its state as parameters and collects nothing, so it composes on its own
 * with no service bound — the same reason ModelsScreenComposeTest does not compose MainScreen,
 * and the reason this test is about the header rather than the screen that hosts it.
 *
 * What is asserted is the routing, not the navigation: that each target reaches its OWN
 * callback and never the other's. Whether the editor then opens is SettingsDialog's job.
 */
@RunWith(AndroidJUnit4::class)
class HeaderPersonaTapComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        const val PERSONA = "Summarizer"
        const val HOST_WIDTH_DP = 360
        /** The accessibility floor for a touch target. */
        const val MIN_TOUCH_DP = 48
    }

    private val persona = Persona(
        name = PERSONA,
        themeColor = Color(0xFF4ADE80),
        model = "qwen3:8b",
        systemPrompt = "Summarize things."
    )

    private var iconTaps = 0
    private var nameTaps = 0

    @Test
    fun iconTapsTheEditorAndNameTapsTheSwitcher() {
        render()

        composeRule.onNodeWithContentDescription("Edit $PERSONA").performClick()
        composeRule.runOnIdle {
            assertEquals("the persona icon must open the EDITOR, exactly once", 1, iconTaps)
            assertEquals("the persona icon must not open the switcher", 0, nameTaps)
        }

        composeRule.onNodeWithContentDescription("Switch persona").performClick()
        composeRule.runOnIdle {
            assertEquals("the name must open the SWITCHER, exactly once", 1, nameTaps)
            assertEquals("the name must not open the editor", 1, iconTaps)
        }
    }

    /**
     * The two targets must not overlap: a tap aimed at the icon has to reach the icon, and the
     * name has to remain its own target beside it.
     */
    @Test
    fun bothTargetsAreSeparateAndAtLeastTouchSized() {
        render()

        val icon = composeRule.onNodeWithContentDescription("Edit $PERSONA")
        val name = composeRule.onNodeWithContentDescription("Switch persona")

        icon.assertHeightIsAtLeast(MIN_TOUCH_DP.dp)
        // Fetching proves the name target exists as its own node rather than being folded
        // into the icon's.
        val nameBounds = name.fetchSemanticsNode().boundsInRoot
        val iconBounds = icon.fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the icon and the name must not overlap (icon=$iconBounds name=$nameBounds)",
            iconBounds.right <= nameBounds.left
        )
    }

    private fun render() {
        iconTaps = 0
        nameTaps = 0
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.size(HOST_WIDTH_DP.dp, 200.dp)) {
                    Header(
                        currentPersona = persona,
                        sessionUsage = UsageInfo(),
                        onPersonaClick = { nameTaps++ },
                        onPersonaIconClick = { iconTaps++ },
                        onEarpieceToggle = {},
                        isEarpieceMode = false,
                        onSettingsClick = {},
                        onTranslatorClick = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }
}