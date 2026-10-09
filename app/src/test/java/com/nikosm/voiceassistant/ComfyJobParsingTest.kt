package com.nikosm.voiceassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins everything ComfyUI can answer with, against the captured `/history` entry: a job that has
 * not appeared yet, a finished run, a failed run, a run whose entry has no status block, several
 * outputs, and a `temp` output.
 *
 * The `type` matters: `/view` needs it, and `temp` files live in a different directory than the
 * `output` ones, so defaulting it would fetch a 404 for every preview image.
 */
class ComfyJobParsingTest {

    private val captured: JsonObject =
        Json.parseToJsonElement(ComfyFixtures.load("history_done.json", ComfyFixtures.HISTORY_DONE)) as JsonObject

    /** The capture's own prompt id, so the assertions work against the real file unchanged. */
    private val promptId: String = captured.keys.first()

    // ------------------------------------------------------------------ history: finished

    @Test
    fun theCapturedHistoryEntryYieldsItsFirstImage() {
        val state = parseComfyJobState(captured.toString(), promptId)

        assertTrue("the captured entry must parse as finished, was $state", state is ComfyJobState.Finished)
        val image = (state as ComfyJobState.Finished).image
        assertTrue("the filename must survive parsing: ${image.filename}", image.filename.isNotBlank())
        assertTrue(
            "a finished run's first output must be an image file, was ${image.filename}",
            image.filename.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp")
        )
        assertTrue(
            "type must be one of the three /view accepts, was '${image.type}'",
            image.type in setOf("output", "temp", "input")
        )
    }

    @Test
    fun severalOutputsPickTheFirstTheServerListed() {
        // The order is ComfyUI's OWN (its executor's cache order, i.e. execution order, so a
        // recorder node lands after the nodes it saved). Deliberately not re-sorted here: imposing
        // a numeric node order would invent a policy the server does not have.
        val body = """
            {"$promptId":{"outputs":{
              "3":{"images":[{"filename":"first.png","subfolder":"batch","type":"output"}]},
              "12":{"images":[{"filename":"second.png","subfolder":"","type":"output"}]}},
             "status":{"status_str":"success","completed":true,"messages":[]}}}
        """.trimIndent()
        val image = (parseComfyJobState(body, promptId) as ComfyJobState.Finished).image
        assertEquals("first.png", image.filename)
        assertEquals("batch", image.subfolder)
    }

    @Test
    fun aTempOutputKeepsItsTypeAndGetsFetchedFromThePreviewDirectory() {
        val body = """
            {"$promptId":{"outputs":{"9":{"images":[{"filename":"ComfyUI_temp_x.png","subfolder":"","type":"temp"}]}},
             "status":{"status_str":"success","completed":true,"messages":[]}}}
        """.trimIndent()
        val image = (parseComfyJobState(body, promptId) as ComfyJobState.Finished).image
        assertEquals("temp", image.type)
    }

    @Test
    fun nodesWithoutAnImagesKeyAreSkipped() {
        val body = """
            {"$promptId":{"outputs":{
              "1":{"text":["a string output, not an image"]},
              "2":{"gifs":[{"filename":"anim.gif","subfolder":"","type":"output"}]},
              "3":{"images":[{"filename":"real.png","subfolder":"","type":"output"}]}},
             "status":{"status_str":"success","completed":true,"messages":[]}}}
        """.trimIndent()
        val image = (parseComfyJobState(body, promptId) as ComfyJobState.Finished).image
        assertEquals("real.png", image.filename)
    }

    @Test
    fun aFinishedRunWithNoImageIsReportedAsSuch() {
        val body = """{"$promptId":{"outputs":{},"status":{"status_str":"success","completed":true,"messages":[]}}}"""
        assertEquals(ComfyJobState.Failed(COMFY_NO_IMAGE_MESSAGE), parseComfyJobState(body, promptId))
    }

    // ------------------------------------------------------------------ history: pending / failed

    @Test
    fun anEmptyHistoryIsPendingNotFinished() {
        assertEquals(ComfyJobState.Pending, parseComfyJobState("{}", promptId))
    }

    @Test
    fun anEntryForAnotherJobIsPending() {
        assertEquals(ComfyJobState.Pending, parseComfyJobState(captured.toString(), "some-other-id"))
    }

    @Test
    fun aHistoryEntryWithNoStatusButWithAnImageCountsAsFinished() {
        // The rule: a missing status block is not a failure — an image means done.
        val body = """{"$promptId":{"outputs":{"9":{"images":[{"filename":"done.png","subfolder":"","type":"output"}]}}}}"""
        val state = parseComfyJobState(body, promptId)
        assertEquals("done.png", (state as ComfyJobState.Finished).image.filename)
    }

    @Test
    fun aHistoryEntryWithNeitherStatusNorImageKeepsPolling() {
        assertEquals(ComfyJobState.Pending, parseComfyJobState("""{"$promptId":{"outputs":{}}}""", promptId))
    }

    @Test
    fun aFailedRunSurfacesTheServersOwnWords() {
        val body = """
            {"$promptId":{"outputs":{},"status":{"status_str":"error","completed":false,
             "messages":[["execution_start",{}],
                         ["execution_error",{"node_id":"9","node_type":"SaveImage",
                          "exception_message":"Allocation on device failed"}]]}}}
        """.trimIndent()
        val state = parseComfyJobState(body, promptId)
        assertEquals(
            "Allocation on device failed (node 9, SaveImage)",
            (state as ComfyJobState.Failed).message
        )
    }

    @Test
    fun aFailedRunThatStillProducedAnImageDeliversTheImage() {
        val body = """
            {"$promptId":{"outputs":{"9":{"images":[{"filename":"partial.png","subfolder":"","type":"output"}]}},
             "status":{"status_str":"error","completed":false,"messages":[["execution_error",{"exception_message":"boom"}]]}}}
        """.trimIndent()
        val state = parseComfyJobState(body, promptId)
        assertEquals("partial.png", (state as ComfyJobState.Finished).image.filename)
    }

    @Test
    fun anErrorWithNoMessagesStillFailsWithSomethingReadable() {
        val body = """{"$promptId":{"outputs":{},"status":{"status_str":"error","completed":false,"messages":[]}}}"""
        assertEquals(ComfyJobState.Failed(COMFY_JOB_FAILED_MESSAGE), parseComfyJobState(body, promptId))
    }
}
