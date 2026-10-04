package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for the model catalog, run against REAL captured responses.
 *
 * Fixtures live in `app/src/test/resources/ollama/`, captured from a live ziptie:
 *  - `tags_ziptie.json`         - 23 models, the full `/api/tags` response
 *  - `show_translategemma.json` - `/api/show` for `translategemma:12b`
 *  - `show_nomic_embed.json`    - `/api/show` for `nomic-embed-text:latest`
 *
 * Inline samples are used only where a fixture cannot express the case (a missing
 * `capabilities` key, malformed JSON). Those are marked `synthetic` in their name.
 *
 * What is pinned here is the set of decisions that were easy to get wrong: "not
 * reported" must survive as distinct from "reported as none", `/api/show` must win field
 * by field, and a failed enrichment must not cost a model its tags values.
 */
class ModelCatalogTest {

    private fun fixture(name: String): String =
        ModelCatalogTest::class.java.classLoader!!.getResourceAsStream("ollama/$name")
            ?.bufferedReader()?.use { it.readText() }
            ?: error("missing fixture ollama/$name")

    private fun tagged(body: String, name: String): ModelEntry? =
        parseTagsModels(body).firstOrNull { it.name == name }

    // ---------------------------------------------------------------- /api/tags

    @Test
    fun `the real ziptie tags response parses every model`() {
        assertEquals(23, parseTagsModels(fixture("tags_ziptie.json")).size)
    }

    @Test
    fun `tags gives size, parameter size and quantization`() {
        val gemma = tagged(fixture("tags_ziptie.json"), "g4-agr:latest")!!
        assertTrue("expected a real byte size", (gemma.sizeBytes ?: 0L) > 0)
        assertNotNull(gemma.parameterSize)
        assertNotNull(gemma.quantization)
        assertEquals(
            setOf(
                ModelCapability.COMPLETION,
                ModelCapability.TOOLS,
                ModelCapability.THINKING
            ),
            gemma.capabilities
        )
    }

    @Test
    fun `translategemma 12b reports no context length in tags`() {
        // The reason /api/show is enrichment and not a fallback: tags has no context for
        // this model at all, and its /api/show does (see the merge tests below).
        val tg = tagged(fixture("tags_ziptie.json"), "translategemma:12b")!!
        assertNull("tags must not invent a context length", tg.contextLength)
        assertEquals(setOf(ModelCapability.COMPLETION), tg.capabilities)
    }

    @Test
    fun `embedding models in the real response are recognised`() {
        val entries = parseTagsModels(fixture("tags_ziptie.json"))
        val embeds = entries.filter { it.capabilities == setOf(ModelCapability.EMBEDDING) }
        assertEquals(
            listOf("mxbai-embed-large:latest", "nomic-embed-text:latest"),
            embeds.map { it.name }.sorted()
        )
        assertTrue("embed-only models must be labelled", embeds.all { it.isEmbeddingOnly })
        assertTrue(
            "a chat model must never be labelled embed-only",
            entries.filter { it.name.contains("gemma") }.none { it.isEmbeddingOnly }
        )
    }

    // ------------------------- not reported vs reported as none

    @Test
    fun `synthetic a missing capabilities key is NOT REPORTED`() {
        // An older Ollama build that predates the capabilities field.
        val body = """
            {"models":[{"name":"legacy:7b","size":100,
              "details":{"parameter_size":"7B","quantization_level":"Q4_0"}}]}
        """.trimIndent()
        val parsed = parseTagsModels(body).single()
        assertNull("absent key must parse as null, never emptySet()", parsed.capabilities)
        assertEquals(
            "an unreported model gets no badges",
            emptyList<ModelCapability>(),
            deriveCapabilityBadges(parsed)
        )
        assertTrue("unreported is not embedding-only", !parsed.isEmbeddingOnly)
    }

    @Test
    fun `synthetic an empty capabilities array IS reported as none`() {
        // A server that answered, and answered with nothing.
        val parsed =
            parseTagsModels("""{"models":[{"name":"bare:1b","size":1,"capabilities":[]}]}""").single()
        assertEquals(emptySet<ModelCapability>(), parsed.capabilities)
        val absent =
            parseTagsModels("""{"models":[{"name":"bare:1b","size":1}]}""").single().capabilities
        assertTrue(
            "null and empty must stay distinguishable",
            parsed.capabilities != absent
        )
    }

    @Test
    fun `an unknown capability token is dropped without losing the model`() {
        val body =
            """{"models":[{"name":"future:1b","size":1,"capabilities":["completion","telepathy"]}]}"""
        assertEquals(
            setOf(ModelCapability.COMPLETION),
            parseTagsModels(body).single().capabilities
        )
    }

    @Test
    fun `malformed json yields no models rather than throwing`() {
        assertEquals(emptyList<ModelEntry>(), parseTagsModels("not json at all"))
        assertEquals(emptyList<ModelEntry>(), parseTagsModels(""))
        assertEquals(emptyList<ModelEntry>(), parseTagsModels("""{"models":"nope"}"""))
        assertEquals(emptyList<ModelEntry>(), parseTagsModels("""[1,2,3]"""))
    }

    @Test
    fun `a model without a name is skipped but its siblings survive`() {
        val body = """{"models":[{"size":1},{"name":"kept:1b","size":2}]}"""
        assertEquals(listOf("kept:1b"), parseTagsModels(body).map { it.name })
    }
    // ------------------------------------------------------- /api/show + merge

    @Test
    fun `the real show response supplies the context length tags lacks`() {
        val tg = tagged(fixture("tags_ziptie.json"), "translategemma:12b")!!
        val show = parseShowModel(fixture("show_translategemma.json"), "translategemma:12b")!!
        assertEquals(131072L, show.contextLength)
        assertEquals("12.2B", show.parameterSize)
        assertEquals("Q4_K_M", show.quantization)
        // The merge is what actually gets this onto the model.
        assertEquals(131072L, mergeModelEntry(tg, show).contextLength)
    }

    @Test
    fun `show wins capabilities field by field -- the gemma4 case`() {
        // tags reported [completion, tools, thinking]; show adds vision and audio.
        val tags = ModelEntry(
            "gemma4:latest",
            setOf(
                ModelCapability.COMPLETION,
                ModelCapability.TOOLS,
                ModelCapability.THINKING
            ),
            "8.0B", "Q4_K_M", null, 9_608_350_718L
        )
        val show = ModelEntry(
            "gemma4:latest",
            setOf(
                ModelCapability.COMPLETION, ModelCapability.VISION, ModelCapability.AUDIO,
                ModelCapability.TOOLS, ModelCapability.THINKING
            ),
            "8.0B", "Q4_K_M", 131_072L, null
        )
        val merged = mergeModelEntry(tags, show)
        assertEquals(show.capabilities, merged.capabilities)
        assertEquals(
            "size is tags-only and must survive a show that has none",
            9_608_350_718L,
            merged.sizeBytes
        )
        assertEquals(131_072L, merged.contextLength)
    }

    @Test
    fun `the real translategemma merge upgrades vision and adds context`() {
        val tg = tagged(fixture("tags_ziptie.json"), "translategemma:12b")!!
        val show = parseShowModel(fixture("show_translategemma.json"), "translategemma:12b")!!
        val merged = mergeModelEntry(tg, show)
        assertEquals(
            setOf(ModelCapability.COMPLETION, ModelCapability.VISION),
            merged.capabilities
        )
        assertEquals(131072L, merged.contextLength)
        assertEquals(tg.sizeBytes, merged.sizeBytes)
    }

    @Test
    fun `the real embed model keeps embedding-only through the merge`() {
        val tags = tagged(fixture("tags_ziptie.json"), "nomic-embed-text:latest")!!
        val show = parseShowModel(fixture("show_nomic_embed.json"), "nomic-embed-text:latest")!!
        assertEquals(setOf(ModelCapability.EMBEDDING), tags.capabilities)
        assertEquals(setOf(ModelCapability.EMBEDDING), show.capabilities)
        val merged = mergeModelEntry(tags, show)
        assertEquals(setOf(ModelCapability.EMBEDDING), merged.capabilities)
        assertEquals(2048L, merged.contextLength)
        assertTrue(merged.isEmbeddingOnly)
    }

    @Test
    fun `a failed show leaves the model on its tags values`() {
        val tg = tagged(fixture("tags_ziptie.json"), "translategemma:12b")!!
        assertEquals(tg, mergeModelEntry(tg, null))
        assertNull(mergeModelEntry(tg, null).contextLength)
        assertEquals(setOf(ModelCapability.COMPLETION), mergeModelEntry(tg, null).capabilities)
    }

    @Test
    fun `show omitting a field does not erase what tags had`() {
        val tags = ModelEntry("m", setOf(ModelCapability.COMPLETION), "8.0B", "Q4_K_M", 4096L, 123L)
        val sparse = ModelEntry("m", null, null, null, null, null)
        val merged = mergeModelEntry(tags, sparse)
        assertEquals(setOf(ModelCapability.COMPLETION), merged.capabilities)
        assertEquals("8.0B", merged.parameterSize)
        assertEquals(4096L, merged.contextLength)
        assertEquals(123L, merged.sizeBytes)
    }

    @Test
    fun `malformed show yields null so the model stays on tags`() {
        assertNull(parseShowModel("garbage", "m"))
        assertNull(parseShowModel("{}", "m"))
    }

    // ------------------------------------------------------------- /v1/models

    @Test
    fun `the openai shape parses ids with nothing reported`() {
        val parsed =
            parseOpenAiModels("""{"object":"list","data":[{"id":"a:1b","object":"model"}]}""").single()
        assertEquals("a:1b", parsed.name)
        assertNull(parsed.capabilities)
        assertNull(parsed.sizeBytes)
        assertNull(parsed.contextLength)
    }
    // ------------------------------------------------------------- formatting

    @Test
    fun `sizes format in binary units`() {
        assertEquals("0 B", formatModelSize(0))
        assertEquals("512 B", formatModelSize(512))
        assertEquals("1.0 KB", formatModelSize(1024))
        assertEquals("8.9 GB", formatModelSize(9_608_350_718L))
        assertEquals("", formatModelSize(-1))
    }

    @Test
    fun `context lengths format compactly`() {
        assertEquals("8K", formatContextLength(8192))
        assertEquals("128K", formatContextLength(131072))
        assertEquals("1M", formatContextLength(1048576))
        assertEquals("", formatContextLength(0))
        assertEquals("", formatContextLength(-5))
    }

    @Test
    fun `the spec line omits fields the server never reported`() {
        // A model with no context (the translategemma tags case) must not print "0 ctx".
        val tg = tagged(fixture("tags_ziptie.json"), "translategemma:12b")!!
        val line = modelSpecLine(tg)
        assertTrue("must not mention ctx: $line", !line.contains("ctx"))
        assertTrue("must still name the model size: $line", line.isNotEmpty())
        val merged =
            mergeModelEntry(tg, parseShowModel(fixture("show_translategemma.json"), tg.name))
        assertTrue(modelSpecLine(merged).contains("128K ctx"))
    }

    @Test
    fun `an unreported model produces an empty spec line rather than placeholders`() {
        assertEquals("", modelSpecLine(ModelEntry("m", null, null, null, null, null)))
    }

    // ------------------------------------------------------------------ badges

    @Test
    fun `completion is never a badge`() {
        val chat = ModelEntry("m", setOf(ModelCapability.COMPLETION), null, null, null, null)
        assertEquals(emptyList<ModelCapability>(), deriveCapabilityBadges(chat))
    }

    @Test
    fun `badges carry the reported extras`() {
        val v = ModelEntry(
            "m",
            setOf(ModelCapability.COMPLETION, ModelCapability.VISION, ModelCapability.TOOLS),
            null, null, null, null
        )
        assertEquals(
            listOf(ModelCapability.VISION, ModelCapability.TOOLS),
            deriveCapabilityBadges(v)
        )
    }

    // -------------------------------------------------------------------- sort

    @Test
    fun `chat picker sorts embed-only last, then favorites, then name`() {
        val entries = listOf(
            ModelEntry("zeta", setOf(ModelCapability.COMPLETION), null, null, null, null),
            ModelEntry("embed", setOf(ModelCapability.EMBEDDING), null, null, null, null),
            ModelEntry("alpha", setOf(ModelCapability.COMPLETION), null, null, null, null),
            ModelEntry("fav", setOf(ModelCapability.COMPLETION), null, null, null, null)
        )
        val sorted = entries.sortedWith { a, b -> compareForChatPicker(a, b, setOf("fav")) }
        assertEquals(listOf("fav", "alpha", "zeta", "embed"), sorted.map { it.name })
    }

    @Test
    fun `an unreported model is not treated as embed-only`() {
        val unknown = ModelEntry("unknown", null, null, null, null, null)
        val chat = ModelEntry("chat", setOf(ModelCapability.COMPLETION), null, null, null, null)
        val sorted = listOf(unknown, chat)
            .sortedWith { a, b -> compareForChatPicker(a, b, emptySet()) }
        assertEquals(listOf("chat", "unknown"), sorted.map { it.name })
    }

    // --------------------------------------------------------------- base urls

    @Test
    fun `base urls drop trailing slash and version suffixes`() {
        assertEquals("http://h:11434", ollamaBaseUrl("http://h:11434/"))
        assertEquals("http://h:11434", ollamaBaseUrl("http://h:11434/v1"))
        assertEquals("http://h:11434", ollamaBaseUrl("http://h:11434/api"))
        assertEquals("http://h:11434", ollamaBaseUrl("  http://h:11434/api  "))
    }

}
