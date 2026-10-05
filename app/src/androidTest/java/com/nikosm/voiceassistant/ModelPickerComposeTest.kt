package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The persona editor's Ollama model picker, composed on its own.
 *
 * `OllamaModelPicker` takes its state as parameters and never collects the service, so a
 * fixed state renders identically every run. MainScreen is deliberately NOT composed here.
 *
 * The host is pinned small and wrapped in an explicit scroller, which is also how the real
 * dropdown bounds itself: the picker declares a max height and the menu scrolls it.
 */
@RunWith(AndroidJUnit4::class)
class ModelPickerComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private companion object {
        const val HOST_WIDTH_DP = 320
        const val HOST_HEIGHT_DP = 460
        const val SERVER = "5800x"
        const val HOST_TAG = "pickerHostScroller"
        const val FIXED_HOUR = 9
        const val FIXED_MINUTE = 5
    }

    private val fixedFetch: Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, FIXED_HOUR)
        set(java.util.Calendar.MINUTE, FIXED_MINUTE)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val accent = Color(0xFF4ADE80)
    private val server = ServerConfig(
        name = SERVER, url = "http://127.0.0.1:11434", username = null, password = null,
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

private var state: ModelCatalogState = ModelCatalogState.NotLoaded
    private var health: String? = "Online"
    // Snapshot state, so a test can hand the picker its favourites AFTER it has opened — which
    // is what the real editor does, since its favourites list starts empty and is filled by
    // a flow rather than being passed in ready.
    private val favorites = mutableStateOf<List<String>>(emptyList())
    private var currentModel: String = ""
    private var refreshes = 0
    private var lastToggled: String? = null
    private var lastSelected: String? = null

    @Before
    fun setUp() {
        state = ModelCatalogState.Loaded(
            entries = listOf(
                entry("deepseek-coder:6.7b", setOf(ModelCapability.COMPLETION, ModelCapability.TOOLS)),
                entry("gemma4:26b", setOf(ModelCapability.COMPLETION, ModelCapability.VISION), "25.2B", ctx = 262_144L),
                entry("nomic-embed-text", setOf(ModelCapability.EMBEDDING), "137M", "F16"),
                entry("llama3:latest", chat)
            ),
            fetchedAtMillis = fixedFetch,
            showStates = listOf(
                "deepseek-coder:6.7b", "gemma4:26b", "nomic-embed-text", "llama3:latest"
            ).associateWith { ModelShowState.RESOLVED }
        )
        health = "Online"
        favorites.value = emptyList()
        currentModel = ""
        refreshes = 0
        lastToggled = null
        lastSelected = null
    }

    private fun listNode() = composeRule.onNodeWithTag(HOST_TAG)

    private fun assertShown(text: String) {
        listNode().performScrollToNode(hasText(text, substring = true))
        composeRule.onAllNodesWithText(text, substring = true).onFirst().assertIsDisplayed()
    }

    private fun render() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.size(HOST_WIDTH_DP.dp, HOST_HEIGHT_DP.dp)) {
                    Box(
                        Modifier
                            .testTag(HOST_TAG)
                            .verticalScroll(rememberScrollState())
                    ) {
                        OllamaModelPicker(
                            server = server,
                            state = state,
                            healthStatus = health,
                            favoriteModels = favorites.value,
                            currentModel = currentModel,
                            onRefresh = { refreshes++ },
                            onToggleFavorite = { lastToggled = it },
                            onSelect = { lastSelected = it },
                            accentColor = accent
                        )
                    }
                }
            }
        }
    }

// -------------------------------------------------------------- the list itself

    @Test
    fun rowsCarryTheSameChipsAndSpecLineAsTheModelsScreen() {
        render()
        assertShown("deepseek-coder")
        assertShown(":6.7b")
        // The full identity is available on its own line, uncut.
        assertShown("deepseek-coder:6.7b")
        // Capability badges, read through the row's own description.
        listNode().performScrollToNode(hasText("gemma4:26b"))
        composeRule.onAllNodesWithContentDescription(
            "gemma4:26b on 5800x, vision, reported by this server, not a favorite",
            substring = true
        ).assertCountEquals(1)
        // And the spec line, exactly as the models screen formats it.
        assertShown("25.2B")
        assertShown("256K ctx")
    }

    @Test
    fun anEmbeddingOnlyModelIsLabelled() {
        render()
        assertShown("nomic-embed-text")
        assertShown("Embedding only")
    }

    @Test
    fun anUnreportedModelIsStillOffered() {
        // No capability may block, hide or disable a model in a picker.
        val loaded = state as ModelCatalogState.Loaded
        state = loaded.copy(
            entries = loaded.entries + entry("mystery-model", null),
            showStates = loaded.showStates + ("mystery-model" to ModelShowState.RESOLVED)
        )
        render()
        assertShown("mystery-model")
        listNode().performScrollToNode(hasText("mystery-model"))
        composeRule.onAllNodesWithContentDescription(
            "mystery-model on 5800x, capabilities not reported by this server, not a favorite",
            substring = true
        ).assertCountEquals(1)
    }

    // ------------------------------------------------------ favourites / Show all

    @Test
    fun withNoFavoritesEverythingIsListedAndTheToggleIsHidden() {
        render()
        assertShown("llama3:latest")
        // Nothing to toggle: there is nothing curated to switch away from.
        composeRule.onNodeWithText("Favorites").assertDoesNotExist()
        composeRule.onNodeWithText("Show all").assertDoesNotExist()
    }

    @Test
    fun favoritesOnlyIsTheDefaultOnceTheServerHasAFavorite() {
        favorites.value = listOf("[5800x] gemma4:26b")
        render()
        // The curated list is what opens, without the user asking for it.
        composeRule.onNodeWithText("Favorites").assertIsDisplayed()
        listNode().performScrollToNode(hasText("gemma4:26b"))
        composeRule.onNodeWithText("gemma4:26b").assertIsDisplayed()
        composeRule.onNodeWithText("llama3:latest").assertDoesNotExist()
    }

    /**
     * The favourites that arrive after the picker has already opened must still take effect.
     *
     * This is not a corner case invented for the test: the persona editor holds its favourites
     * in a `mutableStateOf(emptyList())` that a collector fills from the service, so a picker
     * opened before that flow's first emission sees no favourites at all. Recording that as
     * "show all" froze the wrong default for the rest of the process — every model listed on a
     * server the user had curated, with the chip reading "Show all" and the stars lit.
     */
    @Test
    fun favoritesThatArriveAfterThePickerOpenedStillBecomeTheDefault() {
        render()
        // Nothing curated yet, so there is nothing to switch to.
        composeRule.onNodeWithText("Show all").assertDoesNotExist()
        composeRule.onNodeWithText("Favorites").assertDoesNotExist()

        favorites.value = listOf("[5800x] gemma4:26b")
        composeRule.waitForIdle()

        // The list the user curated is what opens, without them having asked for it.
        composeRule.onNodeWithText("Favorites").assertIsDisplayed()
        listNode().performScrollToNode(hasText("gemma4:26b"))
        composeRule.onNodeWithText("gemma4:26b").assertIsDisplayed()
        composeRule.onNodeWithText("llama3:latest").assertDoesNotExist()
    }

    /** A deliberate "Show all" is a choice, so it must outrank the default and survive. */
    @Test
    fun aDeliberateShowAllIsNotOverriddenByTheFavoritesArriving() {
        render()
        favorites.value = listOf("[5800x] gemma4:26b")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Favorites").performClick()
        composeRule.onNodeWithText("Show all").assertIsDisplayed()

        // A later emission must not undo the choice the user actually made.
        favorites.value = listOf("[5800x] gemma4:26b", "[5800x] llama3:latest")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Show all").assertIsDisplayed()
        listNode().performScrollToNode(hasText("llama3:latest"))
        composeRule.onNodeWithText("llama3:latest").assertIsDisplayed()
    }

    /**
     * The "Show all" choice lasts for as long as this menu stays open, and no longer.
     *
     * The test above pins the choice outranking the default *within* one open menu. This pins
     * what happens after it: DropdownMenu composes its content only while expanded, so closing
     * the menu disposes the picker and unregisters its rememberSaveable provider instead of
     * writing it out. The next open starts from "no choice" and lands back on Favorites.
     *
     * Confirmed on the device — tap "Show all", press back, reopen: the chip reads "Favorites".
     * Pinned so the comment in ModelPicker.kt cannot go on promising that a deliberate choice
     * survives the close, and so hoisting the flag up into the editor (which WOULD make it
     * survive) has to be a deliberate change to a test that names exactly that.
     */
    @Test
    fun theShowAllChoiceLastsForThisOpenOnly() {
        favorites.value = listOf("[5800x] gemma4:26b")
        val mounted = mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                if (mounted.value) {
                    OllamaModelPicker(
                        server = server,
                        state = state,
                        healthStatus = health,
                        favoriteModels = favorites.value,
                        currentModel = currentModel,
                        onRefresh = { refreshes++ },
                        onToggleFavorite = { lastToggled = it },
                        onSelect = { lastSelected = it },
                        accentColor = accent
                    )
                }
            }
        }
        composeRule.onNodeWithText("Favorites").performClick()
        composeRule.onNodeWithText("Show all").assertIsDisplayed()

        // The menu closes, which is what disposes the real picker's content too.
        mounted.value = false
        composeRule.waitForIdle()
        mounted.value = true
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Favorites").assertIsDisplayed()
    }

    /**
     * Un-starring the last favorite while the picker is open must fall back to the full list.
     *
     * The recorded choice is "favorites only", but there is nothing left to show, so holding
     * to it would empty the list the user is choosing from.
     */
    @Test
    fun losingTheLastFavoriteFallsBackToEveryModel() {
        favorites.value = listOf("[5800x] gemma4:26b")
        render()
        composeRule.onNodeWithText("Favorites").assertIsDisplayed()

        favorites.value = emptyList()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Show all").assertDoesNotExist()
        listNode().performScrollToNode(hasText("llama3:latest"))
        composeRule.onNodeWithText("llama3:latest").assertIsDisplayed()
    }
// ---------------------------------------- the current model is always visible

    @Test
    fun theCurrentModelIsMarkedAsSelected() {
        currentModel = "[5800x] llama3:latest"
        render()
        assertShown("llama3:latest")
        assertShown("Current")
        listNode().performScrollToNode(hasText("llama3:latest"))
        composeRule.onAllNodesWithContentDescription(
            "llama3:latest on 5800x, text only, as reported by this server, not a favorite, current model",
            substring = true
        ).assertCountEquals(1)
    }

    @Test
    fun theCurrentModelIsListedWhileItIsNotAFavorite() {
        favorites.value = listOf("[5800x] gemma4:26b")
        currentModel = "[5800x] llama3:latest"
        render()
        // Favorites-only is the default here, and the current model is NOT a favorite...
        composeRule.onNodeWithText("Favorites").assertIsDisplayed()
        // ...yet it is listed and marked, because hiding it would leave the picker unable to
        // show what the persona is actually using.
        assertShown("llama3:latest")
        assertShown("Current")
        // The favorite is there too.
        assertShown("gemma4:26b")
    }

    @Test
    fun aCurrentModelTheServerDeletedIsStillListed() {
        currentModel = "[5800x] long-gone:70b"
        render()
        assertShown("long-gone")
        assertShown("Current")
        // It claims nothing the server did not report.
        listNode().performScrollToNode(hasText("long-gone"))
        composeRule.onAllNodesWithContentDescription(
            "long-gone:70b on 5800x, capabilities not reported by this server, not a favorite, current model",
            substring = true
        ).assertCountEquals(1)
        // And it is not left claiming enrichment is on its way.
        composeRule.onNodeWithText("checking capabilities…").assertDoesNotExist()
    }

    @Test
    fun aCurrentModelFromAnotherServerIsNotInjected() {
        // The picker belongs to 5800x; another connection's model must not be listed here.
        currentModel = "[Other] llama3"
        render()
        composeRule.onNodeWithText("Current").assertDoesNotExist()
    }

// ------------------------------------------------------- what a tap reports

    @Test
    fun theStarReportsTheQualifiedString() {
        render()
        assertShown("deepseek-coder")
        composeRule.onNodeWithContentDescription("Toggle favorite deepseek-coder:6.7b on 5800x")
            .assertIsDisplayed().performClick()
        // Exactly what the old inline dropdown sent to toggleFavoriteModel.
        assertEquals("[5800x] deepseek-coder:6.7b", lastToggled)
        // ...and starring is not selecting.
        assertNull("the star must not select the row", lastSelected)
    }

    @Test
    fun selectingARowReportsTheIdenticalStoredString() {
        render()
        assertShown("gemma4:26b")
        listNode().performScrollToNode(hasText("gemma4:26b"))
        composeRule.onNodeWithContentDescription(
            "gemma4:26b on 5800x, vision, reported by this server, not a favorite",
            substring = true
        ).performClick()
        // Byte-for-byte the string the pre-change dropdown wrote into `model`.
        assertEquals("[5800x] gemma4:26b", lastSelected)
    }

    @Test
    fun selectingAFavoriteReportsTheSameQualifiedString() {
        favorites.value = listOf("[5800x] gemma4:26b")
        currentModel = "[5800x] llama3:latest"
        render()
        listNode().performScrollToNode(hasText("gemma4:26b"))
        composeRule.onNodeWithContentDescription("Toggle favorite gemma4:26b on 5800x")
            .performClick()
        assertEquals("[5800x] gemma4:26b", lastToggled)
    }

    // ----------------------------------------------------------------- the states

    @Test
    fun loadingShowsProgress() {
        state = ModelCatalogState.Loading
        render()
        assertShown("Loading models…")
    }

    @Test
    fun aFailedFetchSaysSoAndStillShowsTheCurrentModel() {
        state = ModelCatalogState.Failed("connection refused", fixedFetch)
        currentModel = "[5800x] llama3:latest"
        render()
        assertShown("Could not load models from this server. connection refused")
        // The failure does not hide what the persona is using.
        assertShown("llama3:latest")
        assertShown("Current")
    }

    @Test
    fun anUnreachableServerKeepsTheLastGoodList() {
        health = "failed: connection refused"
        val loaded = state as ModelCatalogState.Loaded
        state = loaded.copy(stale = true, degradedReason = null)
        currentModel = "[5800x] llama3:latest"
        render()
        assertShown("Unreachable — reported by this server")
        assertShown("This server looks unreachable. Showing the last models loaded.")
        // The list, not an error page.
        assertShown("gemma4:26b")
        assertShown("llama3:latest")
    }

    @Test
    fun anEmptyServerSaysSo() {
        state = ModelCatalogState.Loaded(emptyList(), emptyMap(), fixedFetch)
        render()
        assertShown("No models on this server")
    }

    // --------------------------------------------------------------- first open

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

    @Test
    fun theRefreshControlForcesARefetch() {
        render()
        assertEquals(0, refreshes)
        composeRule.onNodeWithContentDescription("Refresh models for 5800x").performClick()
        assertEquals(1, refreshes)
    }
    @Test
    fun openingThePickerDoesNotChangeTheStoredValue() {
        currentModel = "[5800x] llama3:latest"
        render()
        // Merely composing the picker emits nothing: only a tap does.
        assertNull("no selection was made", lastSelected)
        assertNull("no favorite was toggled", lastToggled)
    }


    @Test
    fun showAllListsEverything() {
        favorites.value = listOf("[5800x] gemma4:26b")
        render()
        // The chip reads "Favorites" while it is ON; tapping it is the switch to show-all.
        composeRule.onNodeWithText("Favorites").performClick()
        composeRule.onNodeWithText("Show all").assertIsDisplayed()
        listNode().performScrollToNode(hasText("llama3:latest"))
        composeRule.onNodeWithText("llama3:latest").assertIsDisplayed()
        assertShown("gemma4:26b")
    }

    @Test
    fun favoritesAreKeyedByServer() {
        favorites.value = listOf("[Other] gemma4:26b")
        render()
        // Not a favorite of 5800x, so the toggle is hidden and nothing is lit.
        composeRule.onNodeWithText("Favorites").assertDoesNotExist()
        listNode().performScrollToNode(hasText("gemma4:26b"))
        composeRule.onAllNodesWithContentDescription(
            "gemma4:26b on 5800x, vision, reported by this server, not a favorite",
            substring = true
        ).assertCountEquals(1)
    }

    /**
     * Every row must be reachable when the catalog outgrows the dropdown.
     *
     * This is the shape the real host has: the picker as a direct child of a scrolling,
     * height-bounded container. The picker must NOT cap its own height — a `heightIn` on the
     * child caps the content, so the surrounding scroller measures the capped height, finds
     * nothing to scroll, and clips the surplus. The rows past the fold are still composed
     * (so `assertDoesNotExist` would pass and prove nothing) and still unusable, which is why
     * this went unnoticed until it was seen on a real device with a real 5800x.
     */
    @Test
    fun everyRowIsReachableWhenTheCatalogOutgrowsTheDropdown() {
        val many = (1..14).map { entry("row-$it:8b", chat) }
        state = ModelCatalogState.Loaded(
            entries = many,
            fetchedAtMillis = fixedFetch,
            showStates = many.associate { it.name to ModelShowState.RESOLVED }
        )
        render()

        // `assertExists`, not `assertIsDisplayed`: the first row's exact on-screen fraction depends on
        // the window this run happens to get, but it must certainly be composed.
        composeRule.onNodeWithText("row-1:8b", substring = true).assertExists()

        listNode().performScrollToNode(hasText("row-14:8b"))
        composeRule.onNodeWithText("row-14:8b", substring = true).assertIsDisplayed()
        // Reaching it took scrolling: the first row is now off-screen. Asserting that here
        // rather than asserting "row 14 starts off-screen" beforehand is what makes the
        // overflow real without depending on where the window happens to start. On the
        // device this class runs against, the root wrapped its content and was bottom-aligned,
        // so rows 1-9 were clipped away and the last row landed inside the viewport without
        // any scrolling at all — the old precondition failed there every single run, while
        // saying nothing about whether the list actually scrolls.
        composeRule.onNodeWithText("row-1:8b", substring = true).assertIsNotDisplayed()
        // And it is a real row, not a ghost: it can be starred.
        composeRule.onNodeWithContentDescription(
            "Toggle favorite row-14:8b on 5800x", substring = true
        ).performClick()
        assertEquals("[$SERVER] row-14:8b", lastToggled)
    }

}
