package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure logic behind the models screen: name splitting, filtering, sorting, the row's
 * spoken label and the freshness line.
 *
 * The capability cases are the ones worth pinning. A model whose capabilities were never
 * reported must match NO filter and must never be described as lacking anything.
 */
class ModelCatalogScreenLogicTest {

    private fun entry(
        name: String,
        caps: Set<ModelCapability>?,
        size: Long? = null
    ) = ModelEntry(name, caps, "8.0B", "Q4_K_M", 131072L, size)

    private val chat = setOf(ModelCapability.COMPLETION)
    private fun select(
        entries: List<ModelEntry>,
        favorites: Set<String> = emptySet(),
        filters: Set<ModelCapability> = emptySet(),
        favoritesOnly: Boolean = false,
        sort: ModelSort = ModelSort.NAME
    ) = selectModels(entries, "5800x", favorites, filters, favoritesOnly, sort).map { it.name }

    // ------------------------------------------------------- name splitting

    @Test
    fun `a hugging face name splits at the last slash`() {
        val full = "hf.co/DavidAU/L3.1-Dark-Planet-SpinFire-Uncensored-8B-GGUF:q4_k_m"
        assertEquals("L3.1-Dark-Planet-SpinFire-Uncensored-8B-GGUF", displayModelName(full))
        assertEquals(":q4_k_m", modelTag(full))
        // The identity itself is untouched by any of that display splitting.
        assertEquals(full, shortModelName(full))
    }

    @Test
    fun `the tag is not repeated inside the prominent name`() {
        assertEquals("deepseek-coder", displayModelName("deepseek-coder:6.7b"))
        assertEquals(":6.7b", modelTag("deepseek-coder:6.7b"))
        // No tag at all: the whole short name is shown.
        assertEquals("nomic-embed-text", displayModelName("nomic-embed-text"))
        assertEquals("", modelTag("nomic-embed-text"))
    }

    @Test
    fun `a name with no tag has an empty tag`() {
        assertEquals("deepseek-coder", displayModelName("deepseek-coder:6.7b"))
        assertEquals(":6.7b", modelTag("deepseek-coder:6.7b"))
        assertEquals("gemma4", displayModelName("gemma4:latest"))
        assertEquals(":latest", modelTag("gemma4:latest"))
    }

    @Test
    fun `a colon inside the path is not mistaken for a tag boundary`() {
        // "hf.co/x/y:z" — the tag is the TRAILING ":z", and "y" is the name.
        assertEquals("y", displayModelName("hf.co/x/y:z"))
        assertEquals(":z", modelTag("hf.co/x/y:z"))
        // The full name, path and all, is always available and never clipped.
        assertEquals("hf.co/x/y:z", shortModelName("hf.co/x/y:z"))
    }

    // ---------------------------------------------------------------- filters

    @Test
    fun `filters are ANDed`() {
        val entries = listOf(
            entry("both", setOf(ModelCapability.COMPLETION, ModelCapability.VISION, ModelCapability.TOOLS)),
            entry("vision-only", setOf(ModelCapability.COMPLETION, ModelCapability.VISION)),
            entry("tools-only", setOf(ModelCapability.COMPLETION, ModelCapability.TOOLS))
        )
        assertEquals(
            listOf("both"),
            select(entries, filters = setOf(ModelCapability.VISION, ModelCapability.TOOLS))
        )
        assertEquals(
            listOf("both", "vision-only"),
            select(entries, filters = setOf(ModelCapability.VISION)).sorted()
        )
    }

    @Test
    fun `an unreported model matches NO capability filter`() {
        val entries = listOf(
            entry("unknown", null),
            entry("known", setOf(ModelCapability.COMPLETION, ModelCapability.VISION))
        )
        assertEquals(
            "an unreported model must not be claimed by any filter",
            listOf("known"),
            select(entries, filters = setOf(ModelCapability.VISION))
        )
        // Nor may it be swept in by a filter the model was never asked about.
        assertFalse(matchesCapabilityFilters(entries[0], setOf(ModelCapability.COMPLETION)))
        // ...but an empty filter set is not a filter, so everything survives.
        assertEquals(setOf("unknown", "known"), select(entries).toSet())
    }

    @Test
    fun `a reported-but-empty model also matches no capability filter`() {
        val entries = listOf(entry("bare", emptySet()))
        assertFalse(matchesCapabilityFilters(entries[0], setOf(ModelCapability.VISION)))
        assertTrue(matchesCapabilityFilters(entries[0], emptySet()))
    }

    // -------------------------------------------------------- favorites + sort

    @Test
    fun `favorites and filters combine`() {
        val entries = listOf(
            entry("fav-vision", setOf(ModelCapability.COMPLETION, ModelCapability.VISION)),
            entry("plain-vision", setOf(ModelCapability.COMPLETION, ModelCapability.VISION)),
            entry("fav-plain", setOf(ModelCapability.COMPLETION))
        )
        val favorites = setOf("[5800x] fav-vision", "[5800x] fav-plain", "[Other] fav-vision")
        // Favorites only, filtered to vision: the other server's favorite must not appear.
        assertEquals(
            listOf("fav-vision"),
            select(entries, favorites, setOf(ModelCapability.VISION), favoritesOnly = true)
        )
        // Show all, filtered to vision: favorites first, then name.
        assertEquals(
            listOf("fav-vision", "plain-vision"),
            select(entries, favorites, setOf(ModelCapability.VISION))
        )
    }

    @Test
    fun `another server's favorites never leak in`() {
        val entries = listOf(entry("m", chat))
        assertEquals(
            "a favorite belonging to another server must not make this model a favorite here",
            emptyList<String>(),
            select(entries, setOf("[Other] m"), favoritesOnly = true, sort = ModelSort.NAME)
        )
        assertEquals(
            listOf("m"),
            select(entries, setOf("[5800x] m"), favoritesOnly = true, sort = ModelSort.NAME)
        )
        assertTrue(serverHasFavorites("5800x", setOf("[5800x] a")))
        assertFalse(serverHasFavorites("5800x", setOf("[Other] a")))
    }

    @Test
    fun `sort by size orders descending and keeps embedding last`() {
        val entries = listOf(
            entry("small", chat, 1_000L),
            entry("big", chat, 9_000L),
            entry("embed", setOf(ModelCapability.EMBEDDING), 99_000L)
        )
        assertEquals(listOf("big", "small", "embed"), select(entries, sort = ModelSort.SIZE))
    }

    @Test
    fun `embedding only sorts last but is never removed`() {
        val entries = listOf(entry("embed", setOf(ModelCapability.EMBEDDING)), entry("chat", chat))
        assertEquals(listOf("chat", "embed"), select(entries, sort = ModelSort.NAME))
        assertTrue(entry("embed", setOf(ModelCapability.EMBEDDING)).isEmbeddingOnly)
        assertFalse("unreported is not embedding-only", entry("u", null).isEmbeddingOnly)
    }

    // ---------------------------------------------------- content description

    @Test
    fun `the row description reads name, capabilities and favorite state`() {
        val e = entry("gemma4:26b", setOf(ModelCapability.COMPLETION, ModelCapability.VISION))
        val desc = modelContentDescription(e, "5800x", isFavorite = true)
        assertTrue(desc, desc.contains("gemma4:26b"))
        assertTrue(desc, desc.contains("5800x"))
        assertTrue(desc, desc.contains("vision"))
        assertTrue(desc, desc.contains("favorite"))
        assertTrue(desc, desc.contains("reported by this server"))
    }

    @Test
    fun `an unreported model says so rather than claiming it lacks capabilities`() {
        val desc = modelContentDescription(entry("m", null), "5800x", isFavorite = false)
        assertTrue(desc, desc.contains("not reported by this server"))
        assertTrue(desc, desc.contains("not a favorite"))
        assertFalse("must never say it lacks vision", desc.contains("lacks"))
    }

    @Test
    fun `a text-only model says text only, and never lists completion as a badge`() {
        val desc = modelContentDescription(entry("m", chat), "5800x", isFavorite = false)
        assertTrue(desc, desc.contains("text only"))
        assertFalse("completion is not a badge", desc.contains("completion"))
    }

    // --------------------------------------------------- freshness + reachability

    @Test
    fun `freshness is a clock time or Never loaded`() {
        assertEquals("Never loaded", modelFreshnessLabel(null))
        assertEquals("Never loaded", modelFreshnessLabel(0L))
        val cal = java.util.Calendar.getInstance()
        cal.set(2026, 0, 1, 14, 32, 0)
        assertEquals("Updated 14:32", modelFreshnessLabel(cal.timeInMillis))
    }

    @Test
    fun `health wording decides reachability, informationally`() {
        assertFalse(isServerUnreachable("Online"))
        assertFalse("an unknown status is not a verdict", isServerUnreachable(null))
        assertTrue(isServerUnreachable("failed: offline"))
        assertTrue(isServerUnreachable("failed: 500"))
        assertTrue(
            "the vaguer fetchModels wording counts as unreachable",
            isServerUnreachable("Could not connect to server")
        )
        assertTrue(isServerUnreachable("Unauthorized"))
    }

    @Test
    fun `the spec line reads as one muted line`() {
        val e = ModelEntry(
            "gemma4:26b",
            setOf(ModelCapability.COMPLETION, ModelCapability.VISION),
            "25.2B", "Q4_K_M", 262_144L, 18_580_000_000L
        )
        assertEquals("25.2B \u00b7 Q4_K_M \u00b7 256K ctx \u00b7 17.3 GB", modelSpecLine(e))
    }

    @Test
    fun `text is never offered as a filter chip`() {
        assertFalse(FILTERABLE_CAPABILITIES.contains(ModelCapability.COMPLETION))
        assertEquals(5, FILTERABLE_CAPABILITIES.size)
        assertEquals("Vision", capabilityLabel(ModelCapability.VISION))
    }


// ================================================================ step 4: the picker

    private val SERVER = "5800x"

    /** The picker's rows, by name, for server 5800x. */
    private fun picker(
        entries: List<ModelEntry>,
        favorites: Set<String> = emptySet(),
        favoritesOnly: Boolean = false,
        current: String? = null
    ) = selectPickerModels(entries, SERVER, favorites, favoritesOnly, current).map { it.name }

    @Test
    fun `the picker keeps the models screen's favorites-first order`() {
        val entries = listOf(entry("zebra", chat), entry("alpha", chat), entry("mid", chat))
        assertEquals(listOf("mid", "alpha", "zebra"), picker(entries, setOf("[5800x] mid")))
    }

    @Test
    fun `the picker sorts embedding-only last and never removes it`() {
        val entries = listOf(entry("embed", setOf(ModelCapability.EMBEDDING)), entry("chat", chat))
        assertEquals(listOf("chat", "embed"), picker(entries))
    }

    @Test
    fun `favorites-only narrows the picker to the favorites`() {
        val entries = listOf(entry("a", chat), entry("b", chat), entry("c", chat))
        assertEquals(listOf("b"), picker(entries, setOf("[5800x] b"), favoritesOnly = true))
    }
    @Test
    fun `another server's favorite does not leak into this picker`() {
        val entries = listOf(entry("a", chat), entry("b", chat))
        // "[Other] b" is not a favorite of 5800x, so favorites-only shows nothing at all.
        // It must never be mistaken for one and drag "b" into the list.
        assertEquals(emptyList<String>(), picker(entries, setOf("[Other] b"), favoritesOnly = true))
        assertEquals(
            listOf("a", "b"),
            picker(entries, setOf("[Other] b"), favoritesOnly = false)
        )
    }

// ------------------------------------------- the current model is always listed

    @Test
    fun `a current model the server does not list is still listed`() {
        val entries = listOf(entry("present", chat))
        assertEquals(listOf("deleted-model", "present"), picker(entries, current = "deleted-model"))
    }

    @Test
    fun `a current model is listed even when it is not a favorite`() {
        val entries = listOf(entry("fav", chat), entry("current", chat))
        // Favorites-only would otherwise drop it entirely, leaving a picker that cannot show
        // what the persona is actually using.
        assertEquals(
            listOf("fav", "current"),
            picker(entries, setOf("[5800x] fav"), favoritesOnly = true, current = "current")
        )
    }

    @Test
    fun `a current model the server DOES list keeps its real row, not a stand-in`() {
        val entries = listOf(
            entry("gemma4:26b", setOf(ModelCapability.COMPLETION, ModelCapability.VISION))
        )
        val rows = selectPickerModels(entries, SERVER, emptySet(), false, "gemma4:26b")
        assertEquals(1, rows.size)
        // The real entry, so its chips and spec line survive.
        assertEquals(
            setOf(ModelCapability.COMPLETION, ModelCapability.VISION),
            rows.single().capabilities
        )
        assertEquals("8.0B", rows.single().parameterSize)
        assertEquals(131072L, rows.single().contextLength)
    }

    @Test
    fun `a nonfavorite current model keeps its real row in favorites-only mode`() {
        // The favorites-only filter drops the current model because it is not a favorite, but
        // the catalog still knows it. A blank stand-in here would show "current" as a bare
        // name with no chips and no spec line, while the models screen shows it fully enriched
        // — the two surfaces must not disagree about the model actually in use.
        val entries = listOf(
            entry("fav", chat),
            entry("gemma4:26b", setOf(ModelCapability.COMPLETION, ModelCapability.VISION))
        )
        val rows = selectPickerModels(
            entries, SERVER, setOf("[5800x] fav"), favoritesOnly = true,
            currentModelName = "gemma4:26b"
        )
        assertEquals(listOf("fav", "gemma4:26b"), rows.map { it.name })
        val current = rows.last()
        assertEquals(
            setOf(ModelCapability.COMPLETION, ModelCapability.VISION),
            current.capabilities
        )
        assertEquals("8.0B", current.parameterSize)
        assertEquals(131072L, current.contextLength)
    }

    @Test
    fun `a current model missing from the catalog is still a blank stand-in in favorites-only mode`() {
        // Nothing to preserve here — the server never reported it, so no chips may be invented.
        val entries = listOf(entry("fav", chat))
        val rows = selectPickerModels(
            entries, SERVER, setOf("[5800x] fav"), favoritesOnly = true,
            currentModelName = "deleted"
        )
        assertEquals(listOf("fav", "deleted"), rows.map { it.name })
        assertNull(rows.last().capabilities)
        assertNull(rows.last().parameterSize)
        assertNull(rows.last().contextLength)
    }

    @Test
    fun `no current model means no invented row`() {
        val entries = listOf(entry("a", chat))
        assertEquals(listOf("a"), picker(entries, current = null))
        assertEquals(listOf("a"), picker(entries, current = ""))
        assertEquals(listOf("a"), picker(entries, current = "   "))
    }

    @Test
    fun `an unreachable server with no entries still lists the current model`() {
        // What Failed/NotLoaded look like to the picker: an empty entry list.
        assertEquals(listOf("llama3"), picker(emptyList(), current = "llama3"))
    }

    // ------------------------------------------------------- the strings written

    @Test
    fun `the qualified name is the exact string favorites and personas store`() {
        assertEquals("[5800x] gemma4:26b", qualifiedModelName("5800x", "gemma4:26b"))
        assertEquals("weird", bareModelName(qualifiedModelName("5800x", "weird"), "5800x"))
        assertEquals("gemma4:26b", bareModelName("[5800x] gemma4:26b", "5800x"))
    }

    @Test
    fun `bareModelName only strips its own server's prefix`() {
        // A cloud persona's plain model name is passed through untouched.
        assertEquals("gpt-4o-mini", bareModelName("gpt-4o-mini", "5800x"))
        // Another Ollama server's model is NOT this server's.
        assertEquals("[Other] llama3", bareModelName("[Other] llama3", "5800x"))
    }

    @Test
    fun `favoriteNamesFor strips only this server's prefix`() {
        assertEquals(
            setOf("a", "b"),
            favoriteNamesFor("5800x", setOf("[5800x] a", "[5800x] b", "[Other] c"))
        )
        assertEquals(emptySet<String>(), favoriteNamesFor("5800x", setOf("[Other] c")))
        assertTrue(serverHasFavorites("5800x", setOf("[5800x] a")))
        assertFalse(serverHasFavorites("5800x", setOf("[Other] a")))
    }

    // ------------------------------------------------------ shared state wording

    @Test
    fun `the stale note is shared between the two surfaces`() {
        assertEquals(
            "This server looks unreachable. Showing the last models loaded.",
            staleModelNote(null, unreachable = true)
        )
        assertEquals(
            "This server looks unreachable. Showing the last models loaded.",
            staleModelNote("connection refused", unreachable = true)
        )
        assertEquals("Showing the last models loaded.", staleModelNote(null, unreachable = false))
        assertEquals(
            "Showing the last models loaded (connection refused).",
            staleModelNote("connection refused", unreachable = false)
        )
        assertEquals("Unreachable — reported by this server", UNREACHABLE_LABEL)
    }
    @Test
    fun `a stand-in claims nothing the server did not report`() {
        val rows = selectPickerModels(emptyList(), SERVER, emptySet(), false, "gone:1b")
        assertEquals(1, rows.size)
        val e = rows.single()
        assertEquals("gone:1b", e.name)
        assertEquals(null, e.capabilities)
        assertEquals(null, e.parameterSize)
        assertEquals(null, e.quantization)
        assertEquals(null, e.contextLength)
        assertEquals(null, e.sizeBytes)
        assertFalse("we were not told, so it is not embedding-only", e.isEmbeddingOnly)
    }


}
