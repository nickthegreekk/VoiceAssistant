package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
