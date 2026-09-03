package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Test

// Unit coverage for the setup-context failure wording in SettingsScreens.kt —
// the strings shown by the Add/Edit Server dialog's inline test result, the
// post-save Snackbar, and the status-dot re-check Snackbar. Keeps the mapping
// from raw ServerConnectionResult to user-facing wording pinned down.
class ServerConnectionMessageTest {

    @Test fun `401 with basic auth names username and password`() {
        val text = serverConnectionFailureMessage(
            ServerConnectionResult(success = false, httpCode = 401, detail = "Unauthorized"),
            AuthType.BASIC
        )
        assertEquals("Authentication failed — check your username/password", text)
    }

    @Test fun `401 without explicit auth type still names username and password`() {
        val text = serverConnectionFailureMessage(
            ServerConnectionResult(success = false, httpCode = 401, detail = null),
            AuthType.NONE
        )
        assertEquals("Authentication failed — check your username/password", text)
    }

    @Test fun `403 with api key auth names the api key`() {
        val text = serverConnectionFailureMessage(
            ServerConnectionResult(success = false, httpCode = 403, detail = "Forbidden"),
            AuthType.API_KEY
        )
        assertEquals("Authentication failed — check your API key", text)
    }

    @Test fun `401 with api key auth names the api key`() {
        val text = serverConnectionFailureMessage(
            ServerConnectionResult(success = false, httpCode = 401, detail = null),
            AuthType.API_KEY
        )
        assertEquals("Authentication failed — check your API key", text)
    }

    @Test fun `404 points at the URL`() {
        val text = serverConnectionFailureMessage(
            ServerConnectionResult(success = false, httpCode = 404, detail = "Not Found"),
            AuthType.NONE
        )
        assertEquals("Server responded, but this address doesn't seem right — check the URL", text)
    }

    @Test fun `timeout maps to unreachable wording with raw detail`() {
        val text = serverConnectionFailureMessage(
            ServerConnectionResult(success = false, httpCode = null, detail = "timeout"),
            AuthType.NONE
        )
        assertEquals("Couldn't reach the server — check the address and that it's running (timeout)", text)
    }

    @Test fun `connection refused maps to unreachable wording with raw detail`() {
        val text = serverConnectionFailureMessage(
            ServerConnectionResult(success = false, httpCode = null, detail = "Connection refused"),
            AuthType.NONE
        )
        assertEquals("Couldn't reach the server — check the address and that it's running (Connection refused)", text)
    }

    @Test fun `unknown host maps to unreachable wording with raw detail`() {
        val text = serverConnectionFailureMessage(
            ServerConnectionResult(success = false, httpCode = null, detail = "Unable to resolve host \"nosuchhost.invalid\": No address associated with hostname"),
            AuthType.BASIC
        )
        assertEquals(
            "Couldn't reach the server — check the address and that it's running " +
                "(Unable to resolve host \"nosuchhost.invalid\": No address associated with hostname)",
            text
        )
    }

    @Test fun `no-response failure without detail keeps clean wording`() {
        val text = serverConnectionFailureMessage(
            ServerConnectionResult(success = false, httpCode = null, detail = null),
            AuthType.NONE
        )
        assertEquals("Couldn't reach the server — check the address and that it's running", text)
    }

    @Test fun `unexpected http code is shown verbatim with its message`() {
        val text = serverConnectionFailureMessage(
            ServerConnectionResult(success = false, httpCode = 502, detail = "Bad Gateway"),
            AuthType.NONE
        )
        assertEquals("Unexpected server response: HTTP 502 — Bad Gateway", text)
    }

    @Test fun `unexpected http code without detail still shows the code`() {
        val text = serverConnectionFailureMessage(
            ServerConnectionResult(success = false, httpCode = 500, detail = null),
            AuthType.NONE
        )
        assertEquals("Unexpected server response: HTTP 500", text)
    }

    @Test fun `other transport errors still surface the raw message`() {
        val text = serverConnectionFailureMessage(
            ServerConnectionResult(success = false, httpCode = null, detail = "Hostname 192.168.1.10 not verified"),
            AuthType.BASIC
        )
        assertEquals(
            "Couldn't reach the server — check the address and that it's running (Hostname 192.168.1.10 not verified)",
            text
        )
    }
}
