package com.nikosm.voiceassistant

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Settings home screen, composed on its own.
 *
 * `SettingsCategoryList` is a static list over precomputed data: no clock, no animation, no
 * infinite transition, no service binding. That is what makes this test deterministic — it is
 * exactly why the list takes its summaries as a parameter instead of collecting them itself.
 * (MainScreen is deliberately NOT composed here; it carries the infinite-transition history
 * that makes composition-based tests flaky.)
 */
@RunWith(AndroidJUnit4::class)
class SettingsCategoryListComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val accent = Color(0xFF4ADE80)
    private var summaries: Map<SettingsCategory, String> = emptyMap()
    private var selected: SettingsCategory? = null

    @Before
    fun setUp() {
        summaries = settingsCategorySummaries(
            ollamaServers = 2,
            gatewayServers = 1,
            imageGenServers = 1,
            cloudProvidersWithKey = 3,
            customCloudProviders = 0,
            personas = 14,
            rooms = 2,
            skills = 4
        )
        selected = null
    }

    /** Renders the list exactly as the dialog does — nothing else. */
    private fun render() {
        composeRule.setContent {
            MaterialTheme {
                SettingsCategoryList(
                    accentColor = accent,
                    summaries = summaries,
                    onCategorySelected = { selected = it }
                )
            }
        }
    }

    @Test
    fun everyCategoryRowShowsItsTitleAndSummary() {
        render()

        composeRule.onNodeWithText("Settings").assertIsDisplayed()

        // Titles AND their live summary lines.
        composeRule.onNodeWithText("General").assertIsDisplayed()
        composeRule.onNodeWithText(GENERAL_SETTINGS_SUMMARY).assertIsDisplayed()
        composeRule.onNodeWithText("Servers").assertIsDisplayed()
        composeRule.onNodeWithText("2 Ollama, 1 gateway, 1 image-gen").assertIsDisplayed()
        composeRule.onNodeWithText("Cloud").assertIsDisplayed()
        composeRule.onNodeWithText("3 providers").assertIsDisplayed()
        composeRule.onNodeWithText("Personas").assertIsDisplayed()
        composeRule.onNodeWithText("14 personas").assertIsDisplayed()
        composeRule.onNodeWithText("Rooms").assertIsDisplayed()
        composeRule.onNodeWithText("2 rooms").assertIsDisplayed()
        composeRule.onNodeWithText("Skills").assertIsDisplayed()
        composeRule.onNodeWithText("4 skills").assertIsDisplayed()
    }

    @Test
    fun theSectionCaptionsAreShown() {
        render()
        composeRule.onNodeWithText("App").assertIsDisplayed()
        composeRule.onNodeWithText("Connections").assertIsDisplayed()
        composeRule.onNodeWithText("Assistants").assertIsDisplayed()
    }

    @Test
    fun tappingARowReportsThatCategory() {
        render()

        composeRule.onNodeWithText("Servers").performClick()

        assertEquals("Servers", selected?.title)
    }

    @Test
    fun tappingAnyRowReportsItsOwnCategory() {
        // Guards the callback wiring for every row, not just the first one clicked. Rendered
        // ONCE (setContent may only be called once per test) and then clicked through: the
        // dialog swaps this list for the category screen on the first tap, so the callback
        // firing again for each row is exactly the contract being checked.
        render()
        listOf("General", "Servers", "Cloud", "Personas", "Rooms", "Skills").forEach { title ->
            selected = null
            composeRule.onNodeWithText(title).performClick()
            assertEquals("tapping '$title' reported the wrong category", title, selected?.title)
        }
    }

    @Test
    fun eachRowReadsAsOneAccessibleElementNamingItAndItsSummary() {
        render()

        // The whole row is the click target, and TalkBack gets "Servers, 2 Ollama, 1 gateway,
        // 1 image-gen" — not three separate unlabeled fragments.
        composeRule
            .onNodeWithContentDescription("Servers, 2 Ollama, 1 gateway, 1 image-gen")
            .assertIsDisplayed()
            .performClick()
        assertEquals("Servers", selected?.title)
    }

    @Test
    fun anEmptyConfigurationStillRendersEveryRowWithAFallbackSummary() {
        summaries = settingsCategorySummaries(0, 0, 0, 0, 0, 0, 0, 0)
        render()

        composeRule.onNodeWithText("No servers yet").assertIsDisplayed()
        composeRule.onNodeWithText("None set up yet").assertIsDisplayed()
        composeRule.onNodeWithText("No rooms yet").assertIsDisplayed()
        assertTrue(SETTINGS_CATEGORIES.isNotEmpty())
    }
}
