package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onFirst
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
 * The per-server models screen, composed on its own.
 *
 * `ModelsScreen` takes its state as parameters and never collects the service itself, so a
 * fixed state renders identically every run: no clock (the freshness line is computed from
 * a fixed timestamp), no service binding, no infinite transition. MainScreen is deliberately
 * NOT composed here — same reason as SettingsCategoryListComposeTest.
 *
 * The host is pinned small and wrapped in an explicit scroller, mirroring how the screen is
 * actually hosted: a plain Column inside the category page's LazyColumn.
 */
@RunWith(AndroidJUnit4::class)
class ModelsScreenComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        const val HOST_WIDTH_DP = 360
        const val HOST_HEIGHT_DP = 520
        const val SERVER = "5800x"
        const val URL = "http://127.0.0.1:11434"
        const val HOST_TAG = "modelsHostScroller"
        const val FIXED_HOUR = 14
        const val FIXED_MINUTE = 32
    }

    /**
     * Today at 14:32 **local**, so "Updated 14:32" is stable wherever the suite runs: a
     * hardcoded instant would render as a different wall-clock time on every timezone.
     */
    private val fixedFetch: Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, FIXED_HOUR)
        set(java.util.Calendar.MINUTE, FIXED_MINUTE)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val accent = Color(0xFF4ADE80)
    private val server = ServerConfig(
        name = SERVER, url = URL, username = null, password = null,
        authType = AuthType.NONE, apiKey = null
    )

    private fun entry(
        name: String,
        caps: Set<ModelCapability>?,
        params: String? = "7.5B",
        quant: String? = "Q4_K_M",
        ctx: Long? = 128_000L,
        size: Long? = 5_000_000_000L
    ) = ModelEntry(name, caps, params, quant, ctx, size)

    private val chat = setOf(ModelCapability.COMPLETION)
    private val visionChat = setOf(ModelCapability.COMPLETION, ModelCapability.VISION, ModelCapability.TOOLS)

    private var state: ModelCatalogState = ModelCatalogState.NotLoaded
    private var health: String? = "Online"
    private var favorites: List<String> = emptyList()
    private var refreshes = 0
    private var lastToggled: String? = null
    private var backs = 0

    @Before
    fun setUp() {
        state = ModelCatalogState.Loaded(
            entries = listOf(
                entry("deepseek-coder:6.7b", setOf(ModelCapability.COMPLETION, ModelCapability.TOOLS)),
                entry("gemma4:26b", visionChat, params = "25.2B", ctx = 262_144L),
                entry("nomic-embed-text", setOf(ModelCapability.EMBEDDING), params = "137M", quant = "F16"),
                entry("unreported-model", null)
            ),
            fetchedAtMillis = fixedFetch,
            showStates = mapOf(
                "deepseek-coder:6.7b" to ModelShowState.RESOLVED,
                "gemma4:26b" to ModelShowState.RESOLVED,
                "nomic-embed-text" to ModelShowState.RESOLVED,
                "unreported-model" to ModelShowState.RESOLVED
            )
        )
        health = "Online"
        favorites = emptyList()
        refreshes = 0
        lastToggled = null
        backs = 0
    }

    private fun listNode() = composeRule.onNodeWithTag(HOST_TAG)

    private fun assertShown(text: String) {
        listNode().performScrollToNode(hasText(text, substring = true))
        composeRule.onAllNodesWithText(text, substring = true).onFirst().assertIsDisplayed()
    }

    /**
     * A capability label exists twice on purpose: once as a FILTER CHIP and once as a row's
     * reported-capability badge. Only the chip is clickable, so clickability picks it out
     * without depending on the order the two happen to appear in the tree.
     */
    private fun filterChip(text: String) =
        composeRule.onNode(hasText(text) and hasClickAction())

    private fun clickFilterChip(text: String) {
        listNode().performScrollToNode(hasText(text))
        filterChip(text).performClick()
    }

    private fun render() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp)) {
                    Box(
                        Modifier
                            // Tagged because the screen itself contains a horizontally
                            // scrolling chip row: `hasScrollAction()` alone matches both.
                            .testTag(HOST_TAG)
                            .verticalScroll(rememberScrollState())
                    ) {
                        ModelsScreen(
                            server = server,
                            state = state,
                            healthStatus = health,
                            favoriteModels = favorites,
                            onRefresh = { refreshes++ },
                            onToggleFavorite = { lastToggled = it },
                            accentColor = accent,
                            onBack = { backs++ }
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- rows and chips

    @Test
    fun rowsShowSplitNamesChipsAndSpecs() {
        render()
        assertShown("deepseek-coder")
        // The tag is shown, and NOT repeated inside the prominent name.
        assertShown(":6.7b")
        assertShown("deepseek-coder:6.7b")   // the full name is available
        assertShown("gemma4:26b")
        // The reported-capability badge sits inside the row's own description, so it is
        // asserted there rather than by text (which would also match the filter chip).
        composeRule.onAllNodesWithContentDescription(
            "gemma4:26b on 5800x, vision, tools, reported by this server, not a favorite",
            substring = true
        ).assertCountEquals(1)
        assertShown("25.2B")
        assertShown("256K ctx")
        // Sizes render in GiB (the same formatter the rest of the app uses); the exact
        // separator-and-unit format is pinned once, in ModelCatalogScreenLogicTest.
        assertShown("4.7 GB")
    }

    @Test
    fun textIsNeverShownAsAChip() {
        render()
        assertShown("gemma4:26b")
        // COMPLETION is real, it is simply not worth a chip: every chat model has it.
        composeRule.onNodeWithText("Text").assertDoesNotExist()
    }

    @Test
    fun anEmbeddingOnlyModelIsLabelled() {
        render()
        assertShown("nomic-embed-text")
        assertShown("Embedding only")
    }

    @Test
    fun anUnreportedModelClaimsNoCapability() {
        render()
        assertShown("unreported-model")
        // Its description says so, rather than implying it lacks anything.
        listNode().performScrollToNode(hasText("unreported-model"))
        composeRule.onAllNodesWithContentDescription(
            "unreported-model on 5800x, capabilities not reported by this server, not a favorite",
            substring = true
        ).assertCountEquals(1)
    }

    @Test
    fun theHeaderShowsFreshnessAndHealth() {
        render()
        assertShown("Updated 14:32")
        assertShown("Online")
    }

    @Test
    fun neverLoadedSaysSo() {
        state = ModelCatalogState.NotLoaded
        render()
        assertShown("Never loaded")
    }

    // ------------------------------------------------------------------ the star

    @Test
    fun theStarCallsBackWithTheServerQualifiedName() {
        render()
        assertShown("deepseek-coder")
        // The row's own label is the one thing that must survive to the accessibility tree.
        listNode().performScrollToNode(hasText("deepseek-coder"))
        composeRule.onAllNodesWithContentDescription(
            "deepseek-coder:6.7b on 5800x, tools, reported by this server, not a favorite",
            substring = true
        ).assertCountEquals(1)
        // The star is the row's only control; tapping it must send the QUALIFIED name,
        // because favorites are shared across servers.
        composeRule.onNodeWithContentDescription("Toggle favorite deepseek-coder:6.7b on 5800x")
            .assertIsDisplayed().performClick()
        assertEquals("[5800x] deepseek-coder:6.7b", lastToggled)
    }

    @Test
    fun favouritesAreKeyedByServer() {
        favorites = listOf("[$SERVER] gemma4:26b", "[Other] gemma4:26b")
        render()
        assertShown("gemma4:26b")
        // The Other server's favorite must not light this one up.
        listNode().performScrollToNode(hasText("gemma4:26b"))
        composeRule.onAllNodesWithContentDescription(
            "gemma4:26b on 5800x, vision, tools, reported by this server, favorite",
            substring = true
        ).assertCountEquals(1)
    }

    // ------------------------------------------------------------------ filtering

    @Test
    fun theVisionFilterNarrowsTheList() {
        render()
        assertShown("deepseek-coder")     // present before filtering
        clickFilterChip("Vision")
        listNode().performScrollToNode(hasText("gemma4:26b"))
        composeRule.onNodeWithText("gemma4:26b").assertIsDisplayed()
        // The non-vision models are gone, and the unreported one never matched.
        composeRule.onNodeWithText("deepseek-coder:6.7b").assertDoesNotExist()
        composeRule.onNodeWithText("unreported-model").assertDoesNotExist()
    }

    @Test
    fun filtersAreAnded() {
        render()
        clickFilterChip("Vision")
        clickFilterChip("Thinking")
        // gemma4 has vision but not thinking; nothing reports both.
        assertShown("No model on this server reports vision, thinking — reported by this server")
    }

    @Test
    fun anEmptyFilterResultExplainsItself() {
        render()
        clickFilterChip("Audio")
        assertShown("No model on this server reports audio — reported by this server")
    }

    // -------------------------------------------------------------- favourites view

    @Test
    fun favouritesToggleDefaultsToShowAllAndExplainsAnEmptyView() {
        render()
        assertShown("Show all")
        composeRule.onNodeWithText("Show all").performClick()
        assertShown("No favorites yet. Tap a star to add one.")
    }

    @Test
    fun favouritesOnlyShowsJustTheFavourites() {
        favorites = listOf("[$SERVER] gemma4:26b")
        render()
        composeRule.onNodeWithText("Show all").performClick()
        assertShown("gemma4:26b")
        composeRule.onNodeWithText("deepseek-coder:6.7b").assertDoesNotExist()
    }

    // ------------------------------------------------------------------ the states

    @Test
    fun loadingShowsProgress() {
        state = ModelCatalogState.Loading
        render()
        assertShown("Loading models…")
    }

    @Test
    fun aStaleListIsKeptWhileRefreshing() {
        state = state.asLoaded(stale = true, degradedReason = "server reported no models")
        render()
        assertShown("Showing the last models loaded (server reported no models).")
        // ...and the list itself is still there, not blanked.
        assertShown("gemma4:26b")
    }

    @Test
    fun anUnreachableServerKeepsTheLastGoodList() {
        health = "failed: connection refused"
        state = state.asLoaded(stale = true, degradedReason = null)
        render()
        assertShown("Unreachable — reported by this server")
        assertShown("This server looks unreachable. Showing the last models loaded.")
        assertShown("gemma4:26b")
    }

    @Test
    fun aFailedFetchWithNoListSaysSo() {
        state = ModelCatalogState.Failed("connection refused", fixedFetch)
        render()
        assertShown("Could not load models from this server. connection refused")
    }

    @Test
    fun anEmptyServerSaysSo() {
        state = ModelCatalogState.Loaded(emptyList(), emptyMap(), fixedFetch)
        render()
        assertShown("No models on this server")
    }

    // ----------------------------------------------------------- pending + back nav

    @Test
    fun aPendingShowHoldsTheChipsPlace() {
        state = state.asLoaded().copy(
            showStates = (state.asLoaded().showStates + ("deepseek-coder:6.7b" to ModelShowState.PENDING))
        )
        render()
        assertShown("deepseek-coder")
        // A placeholder, so the chips do not flip once enrichment lands.
        listNode().performScrollToNode(hasText("deepseek-coder"))
        assertShown("checking capabilities…")
    }

    @Test
    fun backReturnsToTheServerList() {
        render()
        composeRule.onNodeWithContentDescription("Back to servers").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun aNotLoadedCatalogFetchesOnceOnOpen() {
        state = ModelCatalogState.NotLoaded
        render()
        assertEquals("first open fetches exactly once", 1, refreshes)
    }

    @Test
    fun anAlreadyLoadedCatalogDoesNotFetchOnOpen() {
        render()
        assertEquals("never polled, and never refetched behind the user's back", 0, refreshes)
    }

    private fun ModelCatalogState.asLoaded(
        stale: Boolean = false,
        degradedReason: String? = null
    ): ModelCatalogState.Loaded {
        val l = this as ModelCatalogState.Loaded
        return l.copy(stale = stale, degradedReason = degradedReason)
    }
}
