package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * M1: pins the shared gateway-credential resolver.
 *
 * A gateway's AUTH credentials live only on the configured `Servers` entry, so every
 * persona/gateway flow that wants to be authenticated has to match the persona's Backend
 * URL against that list. The old code did a raw `it.url == backendUrl` lookup and, on
 * failure, invented a credential-less `ServerConfig(...)`: a trailing slash, stray
 * whitespace, or a name-based Backend URL (which the audio-chat path already accepted)
 * therefore sent an UNAUTHENTICATED request and got a 401 that was swallowed as "no
 * audio" — the reply appeared in the transcript but was never spoken.
 *
 * These are pure-function cases (no Android surface), so they run as fast JVM unit tests;
 * the end-to-end authentication proof for the same resolver lives in
 * `GatewayCredentialResolutionInstrumentedTest`.
 */
class GatewayResolutionTest {

    private val homeGateway = ServerConfig(
        name = "Home Gateway",
        url = "http://192.168.1.42:8880",
        username = "nikos",
        password = "s3cret",
        authType = AuthType.BASIC
    )

    private val atticGateway = ServerConfig(
        name = "Attic",
        url = "http://192.168.1.9:8880",
        apiKey = "attic-key",
        authType = AuthType.API_KEY
    )

    private val gateways = listOf(homeGateway, atticGateway)

    @Test
    fun byteIdenticalUrlResolvesTheConfiguredEntryWithItsCredentials() {
        val resolved = resolveGatewayConfig(gateways, "http://192.168.1.42:8880")

        assertSame(
            "an exact URL must resolve to the saved entry itself (credentials included), " +
                "never a rebuilt credential-less copy",
            homeGateway,
            resolved
        )
    }

    @Test
    fun trailingSlashAndSurroundingWhitespaceStillResolveTheSameEntry() {
        assertSame(homeGateway, resolveGatewayConfig(gateways, "http://192.168.1.42:8880/"))
        assertSame(homeGateway, resolveGatewayConfig(gateways, "  http://192.168.1.42:8880//  "))
    }

    @Test
    fun aSavedEntryWithATrailingSlashIsMatchedByAPlainUrl() {
        val slashed = ServerConfig(name = "Slashed", url = "http://192.168.1.7:8880/")
        val resolved = resolveGatewayConfig(listOf(slashed), "http://192.168.1.7:8880")

        assertSame(slashed, resolved)
    }

    @Test
    fun nameBasedBackendUrlResolvesTheEntry() {
        // The audio-chat path already accepted a Backend URL holding a gateway NAME;
        // the resolver makes that match available to the gateway flows too.
        assertSame(homeGateway, resolveGatewayConfig(gateways, "Home Gateway"))
        assertSame(homeGateway, resolveGatewayConfig(gateways, "  Home Gateway  "))
    }

    @Test
    fun displayNameFromTheModelTagResolvesTheEntry() {
        // `[Name] model` tags resolve by name even when the Backend URL is something else.
        assertSame(
            atticGateway,
            resolveGatewayConfig(gateways, "http://unrelated.example:8880", "Attic")
        )
    }

    @Test
    fun unmatchedUrlResolvesToNullSoNoCredentialLessRequestCanBeBuilt() {
        assertNull(
            "an unconfigured URL must resolve to null so the caller fails visibly instead of " +
                "sending an unauthenticated request",
            resolveGatewayConfig(gateways, "http://10.0.0.7:8880")
        )
        assertNull(resolveGatewayConfig(gateways, ""))
        assertNull(resolveGatewayConfig(gateways, "   "))
        assertNull(resolveGatewayConfig(emptyList(), "http://192.168.1.42:8880"))
    }

    @Test
    fun urlMatchStillWinsWhenTheBackendUrlLooksLikeAName() {
        // Guard the ordering: a Backend URL equal to a configured URL is resolved by URL,
        // and a name that happens to equal another entry's URL is not mistranslated.
        val named = ServerConfig(name = "http://192.168.1.9:8880", url = "http://192.168.1.99:8880")
        assertSame(named, resolveGatewayConfig(listOf(named), "http://192.168.1.9:8880"))
    }

    @Test
    fun normalizeGatewayUrlTrimsAndStripsTrailingSlashesOnly() {
        assertEquals("http://host:8880", normalizeGatewayUrl("  http://host:8880/  "))
        assertEquals("http://host:8880", normalizeGatewayUrl("http://host:8880///"))
        assertEquals("http://host:8880/path", normalizeGatewayUrl("http://host:8880/path/"))
        assertEquals("", normalizeGatewayUrl("   "))
    }

    @Test
    fun aNameOnlyBackendUrlDoesNotMatchAnUnrelatedEntry() {
        assertNull(resolveGatewayConfig(gateways, "Basement"))
    }
}
