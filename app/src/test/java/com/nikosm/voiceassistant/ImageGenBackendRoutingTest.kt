package com.nikosm.voiceassistant

import kotlinx.serialization.encodeToString
import okhttp3.Credentials
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the parts of the ComfyUI backend that are not tied to Android: how an entry's new fields
 * decode from what is already stored, which endpoint a probe uses per backend, and — the one this
 * feature must not regress — that the A1111 request body is byte-for-byte what it always was.
 */
class ImageGenBackendRoutingTest {

    // ---------------------------------------------------------------- persistence

    @Test
    fun anEntryStoredBeforeComfyuiExistedDecodesAsA1111() {
        val legacy = """
            {"name":"Local A1111","url":"http://192.168.1.50:7860",
             "username":"nikos","password":"s3cret","authType":"BASIC"}
        """.trimIndent()

        val decoded = SettingsJson.decodeFromString(ServerConfig.serializer(), legacy)

        assertEquals(
            "an entry with no backendType must behave exactly as it did: A1111",
            ImageGenBackend.A1111,
            decoded.backendType
        )
        assertNull(decoded.comfyWorkflow)
    }

    @Test
    fun aComfyuiEntryRoundTripsThroughTheStoredJson() {
        val entry = ServerConfig(
            name = "Attic Comfy",
            url = "http://192.168.1.9:8188",
            backendType = ImageGenBackend.COMFYUI,
            comfyWorkflow = ComfyFixtures.SAMPLE_WORKFLOW
        )

        val stored = SettingsJson.encodeToString(entry)

        assertEquals(entry, SettingsJson.decodeFromString(ServerConfig.serializer(), stored))
    }

    // ---------------------------------------------------------------- the active backend setting

    @Test
    fun anAbsentOrUnreadableActiveBackendFallsBackToA1111() {
        assertEquals("an install that never touched the switch", ImageGenBackend.A1111, imageGenBackendFromName(null))
        assertEquals("a blank preference", ImageGenBackend.A1111, imageGenBackendFromName(""))
        assertEquals("a value from some other build", ImageGenBackend.A1111, imageGenBackendFromName("MIDJOURNEY"))
        assertEquals("case-insensitively", ImageGenBackend.COMFYUI, imageGenBackendFromName("comfyui"))
        assertEquals("whitespace-tolerantly", ImageGenBackend.COMFYUI, imageGenBackendFromName("  COMFYUI  "))
    }

    @Test
    fun aBackupWithoutTheActiveBackendDecodesToA1111() {
        val minimal = """
            {"serverBases":[],"ollamaBases":[],"imageGenBases":[],"cloudApis":[],"personas":[],
             "messages":[],"totalCost":0.0,"favoriteModels":[],"trustedCerts":{}}
        """.trimIndent()

        val backup = SettingsJson.decodeFromString(BackupData.serializer(), minimal)

        assertEquals(DEFAULT_IMAGE_GEN_BACKEND, backup.activeImageGenBackend)
        assertEquals(ImageGenBackend.A1111, backup.activeImageGenBackend)
    }

    @Test
    fun aGarbageBackendTypeDecaysToA1111InsteadOfBreakingTheWholeList() {
        // coerceInputValues (SettingsJson) turns an unknown enum value into the default, so one
        // hand-edited or future-written entry can never make the Image Gen list unreadable.
        val json = """{"name":"X","url":"http://host:1","backendType":"MIDJOURNEY"}"""
        assertEquals(
            ImageGenBackend.A1111,
            SettingsJson.decodeFromString(ServerConfig.serializer(), json).backendType
        )
    }

    // ---------------------------------------------------------------- probe routing

    @Test
    fun eachBackendProbesItsOwnEndpoint() {
        assertEquals(IMAGE_GEN_SD_MODELS_PATH, imageGenProbePath(ImageGenBackend.A1111))
        assertEquals(COMFY_SYSTEM_STATS_PATH, imageGenProbePath(ImageGenBackend.COMFYUI))
        assertEquals("/system_stats", COMFY_SYSTEM_STATS_PATH)
    }

    @Test
    fun theComfyuiProbeGoesThroughTheSharedBuilderWithTheEntrysCredentials() {
        val entry = ServerConfig(
            name = "Attic Comfy",
            url = "http://192.168.1.9:8188/",
            username = "nikos",
            password = "s3cret",
            authType = AuthType.BASIC,
            backendType = ImageGenBackend.COMFYUI
        )

        val request = imageGenRequestBuilder(entry, imageGenProbePath(entry.backendType)).build()

        assertEquals("GET", request.method)
        // A trailing slash on the saved URL must not produce "//system_stats".
        assertEquals("http://192.168.1.9:8188/system_stats", request.url.toString())
        assertEquals(Credentials.basic("nikos", "s3cret"), request.header("Authorization"))
    }

    @Test
    fun theA1111ProbeIsStillTheModelList() {
        val entry = ServerConfig(
            name = "Local A1111",
            url = "http://192.168.1.50:7860",
            backendType = ImageGenBackend.A1111
        )

        val request = imageGenRequestBuilder(entry, imageGenProbePath(entry.backendType)).build()

        assertEquals("GET", request.method)
        assertEquals("http://192.168.1.50:7860/sdapi/v1/sd-models", request.url.toString())
    }

    // ---------------------------------------------------------------- A1111 payload capture

    @Test
    fun theA1111PayloadCarriesExactlyTheOriginalFieldsAndValues() {
        // Order-INDEPENDENT proof that the request the A1111 path sends is unchanged: same five
        // fields, same values, and — the point of the assertion — nothing added by the ComfyUI work.
        //
        // The byte-exact golden lives on the device instead, deliberately. Android's org.json is
        // backed by a LinkedHashMap, so `toString()` emits insertion order — which is what the
        // A1111 server actually receives. The JVM sitting this test runs on is backed by a
        // HashMap, so asserting a literal order here would pin the *test dependency's* hash order,
        // not anything the app does; it would also break on an unrelated org.json bump.
        // ImageGenAuthInstrumentedTest asserts the exact body the stub captures.
        val body = JSONObject(buildA1111Txt2ImgBody("a serene mountain lake"))

        assertEquals("the A1111 body must still carry exactly five fields", 5, body.length())
        assertEquals("a serene mountain lake", body.getString("prompt"))
        assertEquals(IMAGE_GEN_NEGATIVE_PROMPT, body.getString("negative_prompt"))
        assertEquals(20, body.getInt("steps"))
        assertEquals(512, body.getInt("width"))
        assertEquals(512, body.getInt("height"))
    }
}
