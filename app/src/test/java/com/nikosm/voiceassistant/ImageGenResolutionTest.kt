package com.nikosm.voiceassistant

import okhttp3.Credentials
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    @Test
    fun trailingSlashAndWhitespaceStillResolveTheSameEntry() {
        assertSame(local, resolveImageGenConfig(servers, "http://192.168.1.50:7860/"))
        assertSame(local, resolveImageGenConfig(servers, "  http://192.168.1.50:7860//  "))
        // A saved entry that itself carries a trailing slash is matched by a plain URL.
        assertSame(remote, resolveImageGenConfig(servers, "http://192.168.1.9:7860"))
    }

    @Test
    fun nameBasedBackendUrlResolvesTheEntry() {
        assertSame(local, resolveImageGenConfig(servers, "Local A1111"))
        assertSame(local, resolveImageGenConfig(servers, "  Local A1111  "))
    }

    @Test
    fun anUnresolvableServerResolvesToNullSoNoBareRequestCanBeBuilt() {
        assertNull(
            "an unconfigured URL must resolve to null so the caller fails visibly instead of " +
                "sending an unauthenticated request",
            resolveImageGenConfig(servers, "http://10.0.0.7:7860")
        )
        assertNull(resolveImageGenConfig(emptyList(), "http://192.168.1.50:7860"))
        assertNull(resolveImageGenConfig(emptyList(), ""))
    }

    @Test
    fun blankBackendUrlFallsBackToTheFirstConfiguredServer() {
        // The old lookup defaulted a blank Backend URL to the first entry — preserved, but now
        // the entry is returned WITH its credentials instead of just its url.
        assertSame(local, resolveImageGenConfig(servers, ""))
        assertSame(local, resolveImageGenConfig(servers, "   "))
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
            resolveImageGenConfig(listOf(entry), rewritten.backendUrl)
        )
    }
}
