package com.nikosm.voiceassistant

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-request credential guard on the gateway TTS paths.
 *
 * A BASIC entry with a username and no password used to be sent as `Basic user:`, and the
 * server's 401 arrived as "Invalid credentials" — a sentence that points at the server's user
 * database while the repair is the empty field in Servers settings. The same silent shape
 * applied to a missing username and to an API-key entry with no key.
 *
 * These are pure cases over the shared helper; the end-to-end authentication proof remains
 * `GatewayCredentialResolutionInstrumentedTest`.
 */
class GatewayCredentialGuardTest {

    private fun gateway(
        name: String = "tts lxc",
        authType: AuthType,
        username: String? = null,
        password: String? = null,
        apiKey: String? = null,
    ) = ServerConfig(
        name = name,
        url = "http://192.0.2.50:8880",
        username = username,
        password = password,
        authType = authType,
        apiKey = apiKey,
    )

    @Test
    fun basicWithUsernameAndPasswordIsAllowed() {
        assertNull(gatewayCredentialProblem(gateway(authType = AuthType.BASIC, username = "Celeste", password = "pw")))
    }

    @Test
    fun basicWithNoPasswordNamesTheFieldAndTheEntry() {
        val problem = gatewayCredentialProblem(gateway(authType = AuthType.BASIC, username = "Celeste"))!!
        assertTrue("the empty field must be named: $problem", problem.contains("no password"))
        assertTrue("the entry must be named: $problem", problem.contains("'tts lxc'"))
    }

    @Test
    fun basicWithNoUsernameNamesTheField() {
        val problem = gatewayCredentialProblem(gateway(authType = AuthType.BASIC, password = "pw"))!!
        assertTrue("the empty field must be named: $problem", problem.contains("no username"))
    }

    @Test
    fun basicWithNeitherNamesBoth() {
        val problem = gatewayCredentialProblem(gateway(authType = AuthType.BASIC))!!
        assertTrue("both empties must be named: $problem", problem.contains("no username or password"))
    }

    @Test
    fun apiKeyWithNoKeyIsRefused() {
        val problem = gatewayCredentialProblem(gateway(authType = AuthType.API_KEY))!!
        assertTrue("the empty field must be named: $problem", problem.contains("no API key"))
    }

    @Test
    fun apiKeyWithAKeyIsAllowed() {
        assertNull(gatewayCredentialProblem(gateway(authType = AuthType.API_KEY, apiKey = "k")))
    }

    @Test
    fun noAuthNeedsNoCredential() {
        assertNull(gatewayCredentialProblem(gateway(authType = AuthType.NONE)))
    }

    @Test
    fun aLegacyNoneEntryWithAUsernameIsTreatedAsBasicAndNeedsItsPassword() {
        // effectiveAuthType: authType NONE + username has always been sent as BASIC.
        val problem = gatewayCredentialProblem(gateway(authType = AuthType.NONE, username = "Celeste"))!!
        assertTrue("the legacy BASIC shape must still be checked: $problem", problem.contains("no password"))
    }
}
