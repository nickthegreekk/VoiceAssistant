package com.nikosm.voiceassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Credentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins `POST /prompt`, `GET /queue` and `GET /view`.
 *
 * The view URL is the part worth staring at: its three parameters are whatever ComfyUI put in the
 * history entry, so a filename can hold a space, a `#` or a `%`, and a subfolder legitimately holds
 * `/`. A hand-built query string would corrupt every one of those — and `#` would silently truncate
 * the request — so the encoding is asserted literally, not only by round trip.
 */
class ComfySubmitAndViewTest {

    // ------------------------------------------------------------------ POST /prompt

    @Test
    fun aTwoHundredWithAPromptIdIsAccepted() {
        val response = """{"prompt_id":"8b0f1c2e-1111-2222-3333-444455556666","number":3,"node_errors":{}}"""
        assertEquals(
            ComfyPromptSubmit.Accepted("8b0f1c2e-1111-2222-3333-444455556666"),
            parseComfySubmitResponse(200, response)
        )
    }

    @Test
    fun aFourHundredIsReadFromNodeErrorsFirst() {
        // node_errors names the node and carries the server's own reason; the sibling `error` object
        // only says the outputs failed validation, so it must not be preferred.
        val response = """
            {"error":{"type":"prompt_outputs_failed_validation",
                      "message":"Prompt outputs failed validation","details":""},
             "node_errors":{"6":{"errors":[{"type":"value_not_in_list","message":"Value not in list",
                                           "details":"ckpt_name: 'nope.safetensors' not in [...]"}],
                              "dependent_outputs":["9"],"class_type":"CheckpointLoaderSimple"}}}
        """.trimIndent()
        val rejected = parseComfySubmitResponse(400, response) as ComfyPromptSubmit.Rejected
        assertEquals(
            "Node 6 (CheckpointLoaderSimple): Value not in list — ckpt_name: 'nope.safetensors' not in [...]",
            rejected.message
        )
    }

    @Test
    fun aFourHundredWithNoNodeErrorsFallsBackToTheErrorObject() {
        val response = """{"error":{"type":"prompt_no_outputs","message":"Prompt has no outputs","details":""},"node_errors":{}}"""
        val rejected = parseComfySubmitResponse(400, response) as ComfyPromptSubmit.Rejected
        assertEquals("Prompt has no outputs", rejected.message)
    }

    @Test
    fun aTwoHundredWithoutAPromptIdIsRejectedRatherThanPollingNothing() {
        val rejected = parseComfySubmitResponse(200, """{"number":1,"node_errors":{}}""") as ComfyPromptSubmit.Rejected
        assertEquals(COMFY_NO_PROMPT_ID_MESSAGE, rejected.message)
    }

    @Test
    fun aNonJsonFailureBodyStillProducesAReadableMessage() {
        val rejected = parseComfySubmitResponse(502, "<html>bad gateway</html>") as ComfyPromptSubmit.Rejected
        assertEquals("ComfyUI returned HTTP 502", rejected.message)
    }

    @Test
    fun theSubmitBodyCarriesTheWorkflowAsAnObjectNotAString() {
        val body = buildComfySubmitBody(ComfyFixtures.SAMPLE_WORKFLOW, clientId = "client-1")
        val root = Json.parseToJsonElement(body) as JsonObject

        assertEquals("client-1", (root["client_id"] as JsonPrimitive).content)
        val workflow = root["prompt"] as? JsonObject
        assertTrue("the workflow must travel as an object, not an escaped string", workflow != null)
        assertTrue("the workflow's nodes must survive", workflow!!.containsKey("3"))
    }

    // ------------------------------------------------------------------ GET /queue

    @Test
    fun theQueuePositionIsReadFromTheSecondTupleElement() {
        val running = """{"queue_running":[[0,"job-a",{},{}]],"queue_pending":[]}"""
        val pending = """{"queue_running":[],"queue_pending":[[1,"job-b",{},{}]]}"""
        val absent = """{"queue_running":[],"queue_pending":[]}"""

        assertEquals(ComfyQueuePosition.RUNNING, parseComfyQueuePosition(running, "job-a"))
        assertEquals(ComfyQueuePosition.PENDING, parseComfyQueuePosition(pending, "job-b"))
        assertEquals(ComfyQueuePosition.ABSENT, parseComfyQueuePosition(absent, "job-a"))
    }

    @Test
    fun aQueueBodyThatDidNotReallyAnswerIsUnknownNeverAbsent() {
        // ABSENT is what makes the app give up on a job, so it must never come from a body that
        // simply was not a queue.
        assertEquals(ComfyQueuePosition.UNKNOWN, parseComfyQueuePosition("<html>", "job-a"))
        assertEquals(ComfyQueuePosition.UNKNOWN, parseComfyQueuePosition("{}", "job-a"))
        assertEquals(ComfyQueuePosition.UNKNOWN, parseComfyQueuePosition("""{"queue_running":[]}""", "job-a"))
    }

    // ------------------------------------------------------------------ GET /view

    @Test
    fun theViewUrlEncodesEveryParameterAndCarriesTheEntrysCredentials() {
        val entry = ServerConfig(
            name = "Attic Comfy",
            url = "http://192.168.1.9:8188/",
            username = "nikos",
            password = "s3cret",
            authType = AuthType.BASIC,
            backendType = ImageGenBackend.COMFYUI
        )
        val image = ComfyOutputImage(
            filename = "my image #2 100%.png",
            subfolder = "sub/dir",
            type = "temp"
        )

        val request = imageGenViewRequest(entry, image).build()

        assertEquals("GET", request.method)
        val url = request.url
        assertEquals("http://192.168.1.9:8188/view", "${url.scheme}://${url.host}:${url.port}${url.encodedPath}")
        // Every parameter survives the round trip...
        assertEquals("my image #2 100%.png", url.queryParameter("filename"))
        assertEquals("sub/dir", url.queryParameter("subfolder"))
        assertEquals("temp", url.queryParameter("type"))
        // ...because the characters that would break a hand-built query are encoded.
        val query = url.encodedQuery.orEmpty()
        assertTrue("a space must be encoded: $query", query.contains("my%20image"))
        assertTrue("a # must be encoded, or it truncates the URL: $query", query.contains("%23"))
        assertTrue("a % must be encoded: $query", query.contains("%25"))
        assertTrue("a / inside subfolder must be encoded: $query", query.contains("sub%2Fdir"))
        assertEquals(Credentials.basic("nikos", "s3cret"), request.header("Authorization"))
    }

    @Test
    fun anEmptySubfolderIsStillSentBecauseViewExpectsTheParameter() {
        val entry = ServerConfig(name = "Local", url = "http://10.0.0.5:8188", backendType = ImageGenBackend.COMFYUI)
        val url = imageGenViewRequest(entry, ComfyOutputImage("ComfyUI_00001_.png", "", "output")).build().url
        assertEquals("", url.queryParameter("subfolder"))
        assertEquals("output", url.queryParameter("type"))
        assertEquals("/view", url.encodedPath)
    }

    // -------------------------------------------------- the captured /system_stats (real capture)

    @Test
    fun theCapturedSystemStatsIsTheJsonTheProbeAssumes() {
        // Test Connection treats any 2xx as Online, so what matters is that the real endpoint
        // answers with the object the app expects rather than, say, the browser UI's HTML.
        val stats = Json.parseToJsonElement(ComfyFixtures.loadCaptured("system_stats.json")) as? JsonObject
        assertTrue("the capture must be a JSON object", stats != null)
        assertTrue("a ComfyUI /system_stats carries a system block", stats!!.containsKey("system"))
        assertTrue("...and a devices list", stats.containsKey("devices"))
    }
}
