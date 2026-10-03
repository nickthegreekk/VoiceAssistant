package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
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

    private companion object {
        /**
         * A viewport small enough that the last row cannot fit, so scrolling is exercised
         * every run, and identical on every device and density.
         */
        const val HOST_WIDTH_DP = 360
        const val HOST_HEIGHT_DP = 420
    }

    /** The scrolling list itself, for [performScrollToNode]. */
    private fun listNode() = composeRule.onNode(hasScrollAction())

    /** Scrolls the row labelled [text] into view, then asserts it is really displayed. */
    private fun assertRowDisplayed(text: String) {
        listNode().performScrollToNode(hasText(text))
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    /** Scrolls the row labelled [text] into view and clicks it. */
    private fun clickRow(text: String) {
        listNode().performScrollToNode(hasText(text))
        composeRule.onNodeWithText(text).performClick()
    }

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
                // A deliberately SHORT, fixed-size host. The list is a LazyColumn and the rows
                // below the first screenful are NOT composed until scrolled to, so asserting
                // them on a fullscreen host only worked on devices tall enough to show them
                // all. Pinning the viewport here makes "below the fold" a property of the
                // test rather than of whatever screen it runs on, and every row is now
                // reached the same way: scroll it into view.
                Box(Modifier.size(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp)) {
                    SettingsCategoryList(
                        accentColor = accent,
                        summaries = summaries,
                        onCategorySelected = { selected = it }
                    )
                }
            }
        }
    }

    @Test
    fun everyCategoryRowShowsItsTitleAndSummary() {
        render()

        composeRule.onNodeWithText("Settings").assertIsDisplayed()

        // Titles AND their live summary lines. Each row is scrolled into view first: the
        // host is short on purpose, so the later rows are only composed once reached.
        listOf(
            "General" to GENERAL_SETTINGS_SUMMARY,
            "Servers" to "2 Ollama, 1 gateway, 1 image-gen",
            "Cloud" to "3 providers",
            "Personas" to "14 personas",
            "Rooms" to "2 rooms",
            "Skills" to "4 skills"
        ).forEach { (title, summary) ->
            assertRowDisplayed(title)
            assertRowDisplayed(summary)
        }
    }

    @Test
    fun theSectionCaptionsAreShown() {
        render()
        listOf("App", "Connections", "Assistants").forEach(::assertRowDisplayed)
    }

    @Test
    fun tappingARowReportsThatCategory() {
        render()

        clickRow("Servers")

        assertEquals("Servers", selected?.title)
    }

    @Test
    fun tappingAnyRowReportsItsOwnCategory() {
        // Guards the callback wiring for every row, not just the first one clicked. Rendered
        // ONCE (setContent may only be called once per test) and then clicked through: the
        // dialog swaps this list for the category screen on the first tap, so the callback
        // firing again for each row is exactly the contract being checked. Each row is
        // scrolled into view first, since the host only shows the first few.
        render()
        listOf("General", "Servers", "Cloud", "Personas", "Rooms", "Skills").forEach { title ->
            selected = null
            clickRow(title)
            assertEquals("tapping '$title' reported the wrong category", title, selected?.title)
        }
    }

    @Test
    fun eachRowReadsAsOneAccessibleElementNamingItAndItsSummary() {
        render()

        // The whole row is the click target, and TalkBack gets "Servers, 2 Ollama, 1 gateway,
        // 1 image-gen" — not three separate unlabeled fragments.
        listNode().performScrollToNode(hasText("Servers"))
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

        listOf("No servers yet", "None set up yet", "No rooms yet").forEach(::assertRowDisplayed)
        assertTrue(SETTINGS_CATEGORIES.isNotEmpty())
    }
}
