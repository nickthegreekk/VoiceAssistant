package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Suggested-replies routing: `[Name] model` with Name in the Ollama server
 * list must go DIRECT to that entry (same lookup as the normal chat path),
 * never to the gateway — and the entry's own auth header must ride along.
 *
 * The bug: fetchSuggestedReplies gated its gateway branch on
 * `ollamaBase.contains(":8880") || voiceMode == GATEWAY`, so the
 * `Assistant (Voice)` persona (GATEWAY voice for TTS) whose tag DID match an
 * Ollama entry still fell into the gateway branch — which then picked
 * `_serverBases.firstOrNull()` with NO Authorization header and 401'd 23-72ms
 * after the prompt was built, before any model output or parse logs.
 *
 * Pure-function cases (no Android surface) run as fast JVM unit tests; the
 * loopback proof — request reaches the stub's /api/chat WITH the header and
 * the suggestions parse — lives in
 * `SuggestedRepliesDirectOllamaAuthInstrumentedTest`.
 */
class SuggestedRepliesRoutingTest {

    private val ollama = ServerConfig(
        name = "5800x",
        url = "http://192.168.1.5:11434",
        username = "nikos",
        password = "s3cret",
        authType = AuthType.BASIC
    )
    private val gateway = ServerConfig(
        name = "Home Gateway",
        url = "http://192.168.1.42:8880",
        username = "gw",
        password = "gwpass",
        authType = AuthType.BASIC
    )

    @Test
    fun matchingTagRoutesDirectToTheOllamaEntry() {
        val route = resolveSuggestedRepliesRoute(
            "[5800x] ornith-1.5:9b", "", listOf(ollama), listOf(gateway)
        )
        assertTrue(route is SuggestedRepliesRoute.DirectOllama)
        assertSame(ollama, (route as SuggestedRepliesRoute.DirectOllama).config)
    }

    @Test
    fun voiceModeIsNotPartOfRoutingSoAVoicePersonaStillGoesDirect() {
        // voiceMode lives on the persona, not in this resolver: the same
        // tagged model must route direct regardless. (The old branch read
        // persona.voiceMode == GATEWAY here and sent it to the gateway.)
        val route = resolveSuggestedRepliesRoute(
            "  [5800x] ornith-1.5:9b ", "", listOf(ollama), listOf(gateway)
        )
        assertTrue(
            "a GATEWAY-voiced persona whose tag matches an Ollama entry must still route direct",
            route is SuggestedRepliesRoute.DirectOllama
        )
    }

    @Test
    fun unmatchedTagFallsBackToTheSharedGatewayResolver() {
        val route = resolveSuggestedRepliesRoute(
            "[Unknown] some-model", gateway.url, listOf(ollama), listOf(gateway)
        )
        assertTrue(route is SuggestedRepliesRoute.Gateway)
        assertSame(gateway, (route as SuggestedRepliesRoute.Gateway).config)
    }

    @Test
    fun unresolvablePersonaYieldsNullSoTheCallerRefusesLoudly() {
        assertNull(resolveSuggestedRepliesRoute("[Unknown] m", "", listOf(ollama), listOf(gateway)))
        assertNull(resolveSuggestedRepliesRoute("plain-model", "", listOf(ollama), emptyList()))
    }

    @Test
    fun basicAuthHeaderMatchesOkHttpCredentialsBasic() {
        assertEquals(
            okhttp3.Credentials.basic("nikos", "s3cret"),
            suggestedRepliesAuthHeader(ollama)
        )
    }

    @Test
    fun apiKeyEntryYieldsBearerAndNoneYieldsNull() {
        val keyed = ServerConfig(name = "k", url = "http://h:11434", apiKey = "k-key", authType = AuthType.API_KEY)
        assertEquals("Bearer k-key", suggestedRepliesAuthHeader(keyed))
        assertNull(suggestedRepliesAuthHeader(ServerConfig(name = "n", url = "http://h:11434")))
    }
}
