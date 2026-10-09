package com.nikosm.voiceassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 7's pure pieces: the GPU-failure hint, the "stopped responding" wording, the three bodies
 * Stop and the optional `/free` can send, and the new per-entry switch's default.
 *
 * All HTTP-free, so every branch is pinned here; the instrumented suite then only has to prove the
 * wiring (which body goes out, and when).
 */
class ComfyStopAndErrorsTest {

    private val promptId = "5e11a1c0-0000-4000-8000-000000000001"

    // ------------------------------------------------------------------ the GPU failure hint

    @Test
    fun eachGpuMarkerAppendsTheHintWithoutLosingTheServersWords() {
        for (message in listOf(
            "CUDA error: an illegal memory access was encountered",
            "hipErrorNoBinaryForGpu: unable to find code object",
            "torch.cuda.OutOfMemoryError: CUDA out of memory"
        )) {
            val hinted = withComfyGpuHint(message)
            assertTrue("the server's own words must survive: $hinted", hinted.startsWith(message))
            assertTrue("the hint must be appended: $hinted", hinted.contains(COMFY_GPU_FAILURE_HINT))
        }
    }

    @Test
    fun markersMatchCaseInsensitively() {
        assertTrue(withComfyGpuHint("HIPERROR: out of memory").contains(COMFY_GPU_FAILURE_HINT))
    }

    @Test
    fun anOrdinaryFailureIsReturnedUnchanged() {
        val message = "Value not in list (node 6, CheckpointLoaderSimple)"
        assertEquals(message, withComfyGpuHint(message))
    }

    @Test
    fun eachGpuMarkerCoversBothServerSpellings() {
        // The three markers named in the plan; a typo here would silently disable the hint.
        assertEquals(setOf("illegal memory access", "hipError", "out of memory"), COMFY_GPU_ERROR_MARKERS.toSet())
    }

    @Test
    fun aFailedHistoryEntryCarryingAGpuErrorGetsTheHint() {
        val body = """
            {"$promptId":{"outputs":{},"status":{"status_str":"error","completed":false,
             "messages":[["execution_error",{"node_id":"3","node_type":"KSampler",
              "exception_message":"CUDA error: an illegal memory access was encountered"}]]}}}
        """.trimIndent()
        val state = parseComfyJobState(body, promptId) as ComfyJobState.Failed
        assertTrue("the run's own words must survive: ${state.message}", state.message.contains("illegal memory access"))
        assertTrue("the hint must be appended: ${state.message}", state.message.contains(COMFY_GPU_FAILURE_HINT))
    }

    // ------------------------------------------------------------------ the dead-server wording

    @Test
    fun theUnreachableMessageNamesTheAddress() {
        assertEquals(
            "ComfyUI at http://192.168.1.9:8188 stopped responding",
            comfyUnreachableMessage("http://192.168.1.9:8188")
        )
    }

    // ------------------------------------------------------------------ the bodies Stop sends

    @Test
    fun theInterruptBodyTargetsOnlyOurPrompt() {
        val root = Json.parseToJsonElement(buildComfyInterruptBody(promptId)) as JsonObject
        assertEquals(1, root.size)
        assertEquals(promptId, (root["prompt_id"] as JsonPrimitive).content)
    }

    @Test
    fun theQueueDeleteBodyDeletesExactlyOurPrompt() {
        val root = Json.parseToJsonElement(buildComfyQueueDeleteBody(promptId)) as JsonObject
        val deleted = root["delete"]!!.jsonArray.map { (it as JsonPrimitive).content }
        assertEquals(listOf(promptId), deleted)
    }

    // ------------------------------------------------------------------ the optional /free body

    @Test
    fun theFreeBodyAsksForBothUnloadAndFreeMemory() {
        val root = Json.parseToJsonElement(buildComfyFreeBody()) as JsonObject
        assertEquals("true", (root["unload_models"] as JsonPrimitive).content)
        assertEquals("true", (root["free_memory"] as JsonPrimitive).content)
    }

    // ------------------------------------------------------------------ the new ServerConfig flag

    @Test
    fun theFreeMemorySwitchDefaultsOffAndIsKeptByTheDataClass() {
        val entry = ServerConfig(name = "Local Comfy", url = "http://10.0.0.5:8188")
        assertFalse("the switch must default off", entry.freeComfyMemoryAfterImage)
        assertTrue(
            "an explicit opt-in must round-trip",
            entry.copy(freeComfyMemoryAfterImage = true).freeComfyMemoryAfterImage
        )
    }
}
