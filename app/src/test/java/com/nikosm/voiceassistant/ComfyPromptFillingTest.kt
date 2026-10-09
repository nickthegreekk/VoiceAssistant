package com.nikosm.voiceassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [buildComfyPromptWorkflow] — the token filling for any exported workflow.
 *
 * The two things a reader should take from this file: a `%SEED%` field becomes a JSON NUMBER
 * (a quoted "4242" is rejected by the server, which validates `seed` as an integer), and a LINK is
 * never treated as a value, because a link is `["<node id>", <output index>]` — an array that a
 * naive string pass would happily corrupt.
 */
class ComfyPromptFillingTest {

    private val values = ComfyPromptValues(
        prompt = "a red bicycle",
        negative = "blurry",
        seed = 4242L
    )

    @Test
    fun theCapturedWorkflowGetsItsPromptNegativeAndNumericSeed() {
        val filled = fill(ComfyFixtures.load("workflow_api.json", ComfyFixtures.SAMPLE_WORKFLOW))

        // The real export keys its nodes however it likes (62/63/65…71), so these assertions find
        // the fields by CLASS and by what the template contained, never by ids that only exist in a
        // hand-written stand-in. The first version of this test hardcoded the stand-in's ids and was
        // the one thing that broke against the real capture.
        val texts = textValues(filled, "CLIPTextEncode")
        assertTrue("the template's %PROMPT% must be filled; texts=$texts", texts.contains("a red bicycle"))
        assertTrue("the template's %NEGATIVE% must be filled; texts=$texts", texts.contains("blurry"))

        val seed = seedValues(filled).first()
        assertFalse("a seed must be a number, never a quoted string", seed.isString)
        assertEquals(
            "with no %SEED% in this template its literal seed is re-randomised to ours",
            4242L,
            seed.longOrNull
        )
    }

    @Test
    fun fillingChangesNothingExceptTokenFieldsAndLiteralSeeds() {
        // The strongest statement available about a REAL export: everything else — every model
        // name, every link array, every step count, every size — is byte-identical afterwards, so
        // filling can never quietly rewrite a workflow the user hand-tuned.
        val template = ComfyFixtures.load("workflow_api.json", ComfyFixtures.SAMPLE_WORKFLOW)
        val before = Json.parseToJsonElement(template) as JsonObject
        val after = fill(template)

        assertOnlyExpectedFieldsChanged(before, after, path = "$", key = null)
    }

    @Test
    fun theSizeSavedInTheWorkflowIsNotOverridden() {
        // The template's own size survives: the workflow owns it, and a hidden 512 would be wrong
        // for a 1024×1024 graph like the captured one. Asserted structurally so it holds for any
        // export rather than for one node id in the stand-in.
        val template = ComfyFixtures.load("workflow_api.json", ComfyFixtures.SAMPLE_WORKFLOW)
        val before = sizeFields(Json.parseToJsonElement(template) as JsonObject)

        assertTrue("the fixture must carry a size for this to prove anything", before.isNotEmpty())
        assertEquals("filling the prompt must not touch the size", before, sizeFields(fill(template)))
    }

    @Test
    fun linkArraysSurviveUntouched() {
        val filled = fill(ComfyFixtures.SAMPLE_WORKFLOW)
        val modelLink = inputs(filled, "3")["model"] as JsonArray
        assertEquals("4", (modelLink[0] as JsonPrimitive).content)
        assertEquals(0L, (modelLink[1] as JsonPrimitive).longOrNull)
    }

    @Test
    fun aTokenInsideLargerTextIsReplacedInsideThatString() {
        val filled = fill("""{"1":{"class_type":"CLIPTextEncode","inputs":{"text":"photo of %PROMPT%, cinematic"}}}""")
        assertEquals("photo of a red bicycle, cinematic", primitive(filled, "1", "text").content)
    }

    @Test
    fun widthAndHeightTokensAreLeftExactlyAsWrittenBecauseTheWorkflowOwnsItsSize() {
        // %WIDTH%/%HEIGHT% are deliberately unsupported in v1: a hidden 512 default would be wrong
        // for SDXL and for any graph with another base resolution. The token survives VERBATIM so
        // nothing is silently changed; validateComfyWorkflow warns about it on save.
        val filled = fill("""{"5":{"class_type":"EmptyLatentImage","inputs":{"width":"%WIDTH%","height":"%HEIGHT%"}}}""")
        assertEquals("%WIDTH%", primitive(filled, "5", "width").content)
        assertEquals("%HEIGHT%", primitive(filled, "5", "height").content)
    }

    @Test
    fun withoutASeedTokenEveryLiteralSeedIsRandomisedSoRegenerateDiffers() {
        val template = """
            {"1":{"class_type":"KSampler","inputs":{
                "seed":12345,"noise_seed":777,"noise":["2",0],"steps":20,"text":"%PROMPT%"}},
             "2":{"class_type":"Noise","inputs":{"seed":["1",0]}}}
        """.trimIndent()
        val filled = fill(template, values.copy(seed = 999L))

        assertEquals(999L, primitive(filled, "1", "seed").longOrNull)
        assertEquals(999L, primitive(filled, "1", "noise_seed").longOrNull)
        assertEquals("steps belong to the workflow", 20L, primitive(filled, "1", "steps").longOrNull)

        // A LINK under a seed-named key stays a link: ["2", 0] references a node, it is not a seed.
        val noiseLink = inputs(filled, "1")["noise"] as JsonArray
        assertEquals("2", (noiseLink[0] as JsonPrimitive).content)
        assertEquals(0L, (noiseLink[1] as JsonPrimitive).longOrNull)
        val seedLink = inputs(filled, "2")["seed"] as JsonArray
        assertEquals("1", (seedLink[0] as JsonPrimitive).content)
    }

    @Test
    fun twoRunsWithDifferentSeedsProduceDifferentWorkflows() {
        val template = ComfyFixtures.SAMPLE_WORKFLOW
        assertNotEquals(
            buildComfyPromptWorkflow(template, values.copy(seed = 1L)),
            buildComfyPromptWorkflow(template, values.copy(seed = 2L))
        )
    }

    @Test
    fun tokensAreMatchedCaseInsensitively() {
        val filled = fill("""{"1":{"class_type":"CLIPTextEncode","inputs":{"text":"%prompt%"}}}""")
        assertEquals("a red bicycle", primitive(filled, "1", "text").content)
    }

    @Test
    fun anUnparseableTemplateIsReturnedUnchangedRatherThanThrowing() {
        assertEquals("{not json", buildComfyPromptWorkflow("{not json", values))
    }

    // ------------------------------------------------------------------ helpers

    private fun fill(template: String, v: ComfyPromptValues = values): JsonObject =
        Json.parseToJsonElement(buildComfyPromptWorkflow(template, v)) as JsonObject

    private fun inputs(root: JsonObject, id: String): JsonObject =
        (root[id] as JsonObject)["inputs"] as JsonObject

    private fun primitive(root: JsonObject, id: String, key: String): JsonPrimitive =
        inputs(root, id)[key] as JsonPrimitive

    private fun nodesOfType(root: JsonObject, classType: String): List<JsonObject> =
        root.values.filterIsInstance<JsonObject>()
            .filter { (it["class_type"] as? JsonPrimitive)?.content == classType }

    private fun textValues(root: JsonObject, classType: String): List<String> =
        nodesOfType(root, classType).mapNotNull {
            ((it["inputs"] as? JsonObject)?.get("text") as? JsonPrimitive)?.content
        }

    private fun seedValues(root: JsonObject): List<JsonPrimitive> = root.values
        .filterIsInstance<JsonObject>()
        .flatMap { node ->
            val inputs = node["inputs"] as? JsonObject ?: return@flatMap emptyList()
            inputs.filterKeys { it in COMFY_SEED_INPUT_NAMES }
                .values.filterIsInstance<JsonPrimitive>()
        }

    /** Every literal `width`/`height` anywhere, keyed "nodeId.field" so a change is visible. */
    private fun sizeFields(root: JsonObject): Map<String, Long> = buildMap {
        for ((id, node) in root) {
            val nodeInputs = (node as? JsonObject)?.get("inputs") as? JsonObject ?: continue
            for (field in listOf("width", "height")) {
                (nodeInputs[field] as? JsonPrimitive)?.longOrNull?.let { put("$id.$field", it) }
            }
        }
    }

    /**
     * Asserts that nothing changed except a token field or a literal seed.
     *
     * Recurses structurally rather than comparing serialized text, so the failure names the exact
     * path that moved. [key] is the property name that reached this node, which is what distinguishes
     * "a seed we may re-randomise" from "any other number we may not".
     */
    private fun assertOnlyExpectedFieldsChanged(
        before: JsonElement,
        after: JsonElement,
        path: String,
        key: String?
    ) {
        when {
            before is JsonObject && after is JsonObject -> {
                assertEquals("$path: the set of fields must not change", before.keys, after.keys)
                for ((name, value) in before) {
                    assertOnlyExpectedFieldsChanged(value, after[name]!!, "$path.$name", name)
                }
            }
            before is JsonArray && after is JsonArray -> {
                assertEquals("$path: array length must not change", before.size, after.size)
                before.forEachIndexed { i, value ->
                    assertOnlyExpectedFieldsChanged(value, after[i], "$path[$i]", key)
                }
            }
            before is JsonPrimitive && after is JsonPrimitive -> {
                if (before.content == after.content && before.isString == after.isString) return
                val wasAToken = before.isString && before.content.contains('%')
                val wasALiteralSeed = key != null &&
                    key.lowercase() in COMFY_SEED_INPUT_NAMES &&
                    before.doubleOrNull != null
                assertTrue(
                    "$path changed from '${before.content}' to '${after.content}' but it is neither " +
                        "a token field nor a literal seed — filling must touch nothing else",
                    wasAToken || wasALiteralSeed
                )
            }
            else -> assertEquals("$path", before, after)
        }
    }
}
