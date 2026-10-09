package com.nikosm.voiceassistant

import okhttp3.Credentials
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shared image-gen resolver and request builder (`AssistantServiceImageGen.kt`).
 *
 * The 401 this fixes: `performImageGeneration` looked the persona's Backend URL up only to
 * take the matched entry's `.url`, then built a bare request. A1111 started with `--api-auth`
 * therefore rejected every generation while "Test Connection" (which passes the full
 * ServerConfig) succeeded. These pure-function cases prove a generation now resolves to the
 * configured entry — credentials included — and builds the same Authorization header Test
 * connection does, for both Basic and API-key auth. The end-to-end authentication proof lives
 * in `ImageGenAuthInstrumentedTest`.
 */
class ImageGenResolutionTest {

    private val local = ServerConfig(
        name = "Local A1111",
        url = "http://192.168.1.50:7860",
        username = "nikos",
        password = "s3cret",
        authType = AuthType.BASIC
    )

    private val remote = ServerConfig(
        name = "Attic A1111",
        url = "http://192.168.1.9:7860/",
        apiKey = "attic-key",
        authType = AuthType.API_KEY
    )

    private val servers = listOf(local, remote)

    private val comfy = ServerConfig(
        name = "Attic Comfy",
        url = "http://192.168.1.9:8188",
        backendType = ImageGenBackend.COMFYUI
    )

    /** Unwraps a resolution, failing loudly if it was not a success. */
    private fun resolved(
        imageGenServers: List<ServerConfig>,
        active: ImageGenBackend = ImageGenBackend.A1111,
        pin: String? = null
    ): ServerConfig {
        val resolution = resolveImageGenConfig(imageGenServers, active, pin)
        assertTrue("expected a resolved entry, was $resolution", resolution is ImageGenResolution.Resolved)
        return (resolution as ImageGenResolution.Resolved).config
    }

    @Test
    fun aPinThatMatchesByUrlOrNameWinsOverTheActiveGenerator() {
        // Trailing slashes and surrounding whitespace are normalized, exactly as before.
        assertSame(local, resolved(servers, pin = "http://192.168.1.50:7860/"))
        assertSame(local, resolved(servers, pin = "  http://192.168.1.50:7860//  "))
        assertSame(remote, resolved(servers, pin = "http://192.168.1.9:7860"))
        assertSame(local, resolved(servers, pin = "Local A1111"))
        assertSame(local, resolved(servers, pin = "  Local A1111  "))
    }

    @Test
    fun aMatchingPinIsNotReportedAsStale() {
        val resolution = resolveImageGenConfig(servers, ImageGenBackend.A1111, "http://192.168.1.50:7860")
        assertNull((resolution as ImageGenResolution.Resolved).stalePinnedUrl)
    }

    @Test
    fun theActiveTypeDecidesWhichEntryIsUsedAndTheFirstOneWins() {
        val both = listOf(local, comfy)

        assertSame(local, resolved(both, active = ImageGenBackend.A1111))
        assertSame(comfy, resolved(both, active = ImageGenBackend.COMFYUI))

        // Two entries of the active type: the user's own ordering decides, which is what the
        // move-up/move-down buttons on the Image Gen screen are for.
        val second = local.copy(name = "Second A1111", url = "http://192.168.1.60:7860")
        assertSame(local, resolved(listOf(local, second, comfy), active = ImageGenBackend.A1111))
        assertSame(second, resolved(listOf(second, local, comfy), active = ImageGenBackend.A1111))
    }

    @Test
    fun aBlankPinFollowsTheActiveGeneratorWithNoNote() {
        for (pin in listOf(null, "", "   ")) {
            val resolution = resolveImageGenConfig(servers, ImageGenBackend.A1111, pin) as ImageGenResolution.Resolved
            assertSame(local, resolution.config)
            assertNull("a blank pin is not a stale pin", resolution.stalePinnedUrl)
        }
    }

    @Test
    fun aStalePinFallsBackToTheActiveGeneratorInsteadOfFailingTheRequest() {
        // THE REPORTED BUG. The persona pinned http://192.168.1.50:7860; that entry was edited to
        // the ComfyUI address (or replaced), so the pin matches nothing. If an entry of the active
        // type exists, the request must still go out through it — and say the pin is stale.
        val fallback = resolveImageGenConfig(
            listOf(local, comfy),
            ImageGenBackend.A1111,
            "http://192.168.1.50:9999"
        ) as ImageGenResolution.Resolved

        assertSame(local, fallback.config)
        assertEquals("http://192.168.1.50:9999", fallback.stalePinnedUrl)
    }

    @Test
    fun aStalePinWithNoEntryOfTheActiveTypeIsAnActionableSetupError() {
        // The genuinely un-fixable-by-fallback case: the user switched to ComfyUI (having edited
        // their only entry to it, or with no ComfyUI entry at all) and the persona's pin no longer
        // matches anything, so there is nothing to fall back TO. The error must name ComfyUI rather
        // than the persona.
        val resolution = resolveImageGenConfig(listOf(local), ImageGenBackend.COMFYUI, "http://192.168.1.50:9999")
        assertEquals(ImageGenResolution.NoEntryForActiveBackend(ImageGenBackend.COMFYUI), resolution)
    }

    @Test
    fun noEntryOfTheActiveTypeIsReportedAsSuchAndTheMessageNamesTheType() {
        val message = noEntryForActiveImageBackendMessage(ImageGenBackend.COMFYUI)

        assertTrue("the error must name the type: $message", message.contains("ComfyUI"))
        assertTrue(
            "...and where to add one: $message",
            message.contains("Settings → Servers → Image Gen")
        )
        assertTrue(
            "the error must NOT blame the persona — that is the wording this replaces: $message",
            !message.contains("persona", ignoreCase = true)
        )
        // Reachable with an EMPTY list too, not only with a list of the wrong type.
        assertEquals(
            ImageGenResolution.NoEntryForActiveBackend(ImageGenBackend.A1111),
            resolveImageGenConfig(emptyList(), ImageGenBackend.A1111, null)
        )
    }

    @Test
    fun onlyAnImageGeneratorPersonaMayPinAServer() {
        val generator = Persona(
            name = "Painter", themeColor = Color(0xFF4ADE80), model = "", systemPrompt = "",
            isImageGenerator = true, backendUrl = "http://192.168.1.9:8188"
        )
        val chatter = Persona(
            name = "Chatty", themeColor = Color(0xFF4ADE80), model = "[Home] llama3", systemPrompt = "",
            isImageGenerator = false, backendUrl = "http://192.168.1.44:11434"
        )

        assertEquals("http://192.168.1.9:8188", imageGenPinnedUrl(generator))
        assertNull("a chat persona's Backend URL is where its CHAT goes", imageGenPinnedUrl(chatter))
    }

    @Test
    fun theInlineTriggerIgnoresThePersonasChatUrlAndUsesTheActiveGenerator() {
        // THE OTHER HALF OF THE REPORTED BUG. The chat persona's URL is an Ollama base; it matches
        // no Image Gen entry, so honouring it failed with "not configured for this persona".
        val chatter = Persona(
            name = "Chatty", themeColor = Color(0xFF4ADE80), model = "[Home] llama3", systemPrompt = "",
            isImageGenerator = false, backendUrl = "http://192.168.1.44:11434"
        )

        val resolution = resolveImageGenConfig(servers, ImageGenBackend.A1111, imageGenPinnedUrl(chatter))

        assertSame(
            "the inline trigger must follow the active generator, not the chat URL",
            local,
            (resolution as ImageGenResolution.Resolved).config
        )
        assertNull(resolution.stalePinnedUrl)
    }

    @Test
    fun theSharedBuilderAttachesBasicAuthAndNormalizesTheUrl() {
        val request = imageGenRequestBuilder(local, IMAGE_GEN_TXT2IMG_PATH).build()

        assertEquals("http://192.168.1.50:7860/sdapi/v1/txt2img", request.url.toString())
        assertEquals(
            "a generation must send the configured entry's Basic credentials",
            Credentials.basic("nikos", "s3cret"),
            request.header("Authorization")
        )
    }

    @Test
    fun theSharedBuilderAttachesApiKeyAuthAndStripsTheSavedTrailingSlash() {
        val request = imageGenRequestBuilder(remote, IMAGE_GEN_SD_MODELS_PATH).build()

        assertEquals("http://192.168.1.9:7860/sdapi/v1/sd-models", request.url.toString())
        assertEquals("Bearer attic-key", request.header("Authorization"))
    }

    @Test
    fun theSharedBuilderSendsNoHeaderWhenAuthIsNone() {
        val open = ServerConfig(name = "Open A1111", url = "http://192.168.1.20:7860")
        val request = imageGenRequestBuilder(open, IMAGE_GEN_TXT2IMG_PATH).build()

        assertNull(request.header("Authorization"))
    }

    @Test
    fun editingAServersUrlRepointsImageGeneratorPersonasButNotOthers() {
        // The persona editor stores the image-gen server by URL (backendUrl = cfg.url). A
        // URL edit must drag those references along, or the persona keeps pointing at the
        // stale address that no entry matches — the 401 this fix removes.
        val generator = Persona(
            name = "Painter", themeColor = Color(0xFF4ADE80), model = "", systemPrompt = "",
            isImageGenerator = true, backendUrl = "http://192.168.1.50:7860/"
        )
        val chatter = Persona(
            name = "Chatty", themeColor = Color(0xFF4ADE80), model = "[Home] llama3", systemPrompt = "",
            isImageGenerator = false, backendUrl = "http://192.168.1.50:7860" // a gateway on the same host
        )

        val after = rewritePersonasBackendUrl(
            listOf(generator, chatter),
            "http://192.168.1.50:7860",
            "http://100.64.0.7:7860",
            forImageGenerators = true
        )

        assertEquals("http://100.64.0.7:7860", after[0].backendUrl)
        assertEquals(
            "a non-generator persona's gateway URL must NOT be rewritten",
            "http://192.168.1.50:7860",
            after[1].backendUrl
        )
    }

    @Test
    fun editingAGatewayUrlRepointsGatewayPersonasButNeverImageGenerators() {
        val gatewayPersona = Persona(
            name = "Chatty", themeColor = Color(0xFF4ADE80), model = "[Home] llama3", systemPrompt = "",
            isImageGenerator = false, backendUrl = "http://192.168.1.9:8880"
        )
        // An image generator whose backendUrl is the SAME string as the gateway's: independent
        // registries, same host, must NOT be carried across by a gateway URL edit.
        val imagePersona = Persona(
            name = "Painter", themeColor = Color(0xFF4ADE80), model = "", systemPrompt = "",
            isImageGenerator = true, backendUrl = "http://192.168.1.9:8880"
        )

        val after = rewritePersonasBackendUrl(
            listOf(gatewayPersona, imagePersona),
            "http://192.168.1.9:8880",
            "http://100.64.0.9:8880",
            forImageGenerators = false
        )

        assertEquals("http://100.64.0.9:8880", after[0].backendUrl)
        assertEquals(
            "an image-generator persona must NOT be rewritten by a gateway URL edit",
            "http://192.168.1.9:8880",
            after[1].backendUrl
        )
    }

    @Test
    fun onlyExactNormalizedUrlsAreRewrittenNeverPrefixes() {
        val other = Persona(
            name = "Other", themeColor = Color(0xFF4ADE80), model = "", systemPrompt = "",
            isImageGenerator = true, backendUrl = "http://192.168.1.50:7860/v1"
        )

        val after = rewritePersonasBackendUrl(
            listOf(other),
            "http://192.168.1.50:7860",
            "http://100.64.0.7:7860",
            forImageGenerators = true
        )

        assertEquals("http://192.168.1.50:7860/v1", after[0].backendUrl)
    }

    @Test
    fun afterTheUrlEditThePersonaResolvesToTheEditedEntryWithItsCredentials() {
        val entry = ServerConfig(
            name = "Local A1111",
            url = "http://100.64.0.7:7860",
            username = "nikos",
            password = "s3cret",
            authType = AuthType.BASIC
        )
        val rewritten = rewritePersonasBackendUrl(
            listOf(
                Persona(
                    name = "Painter", themeColor = Color(0xFF4ADE80), model = "", systemPrompt = "",
                    isImageGenerator = true, backendUrl = "  http://192.168.1.50:7860/  "
                )
            ),
            "http://192.168.1.50:7860",
            "http://100.64.0.7:7860",
            forImageGenerators = true
        ).first()

        assertSame(
            "after the edit the persona must resolve to the edited entry (credentials included)",
            entry,
            (resolveImageGenConfig(listOf(entry), ImageGenBackend.A1111, rewritten.backendUrl)
                as ImageGenResolution.Resolved).config
        )
    }
}
