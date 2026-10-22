package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class McpRedactionTest {
    @Test fun `flag and value pair keeps the flag and redacts the value`() {
        assertEquals(listOf("--api-key", "<redacted>"), redactMcpArgs(listOf("--api-key", "SENTINEL")))
    }
    @Test fun `flag equals value form is redacted`() {
        assertEquals(listOf("--token=<redacted>"), redactMcpArgs(listOf("--token=SENTINEL")))
    }
    @Test fun `uppercase flags are redacted`() {
        assertEquals(listOf("--TOKEN", "<redacted>"), redactMcpArgs(listOf("--TOKEN", "SENTINEL")))
    }
    @Test fun `port and timeout flags are left alone`() {
        val args = listOf("-p", "8080", "--port", "8080", "-t", "30", "-k")
        assertEquals(args, redactMcpArgs(args))
    }
    @Test fun `a credential flag as the last argument does not crash`() {
        assertEquals(listOf("serve", "--password"), redactMcpArgs(listOf("serve", "--password")))
    }
    @Test fun `a credential flag followed by another flag redacts nothing`() {
        assertEquals(listOf("--token", "--verbose"), redactMcpArgs(listOf("--token", "--verbose")))
    }
    @Test fun `argument positions are preserved`() {
        assertEquals(
            listOf("run", "--key", "<redacted>", "--port", "80"),
            redactMcpArgs(listOf("run", "--key", "SENTINEL", "--port", "80"))
        )
    }
    @Test fun `url query userinfo and fragment are removed`() {
        assertEquals(
            "https://example.com/mcp",
            redactMcpUrl("https://user:SENTINEL@example.com/mcp?token=SENTINEL#frag")
        )
    }
    @Test fun `null and blank urls stay as they are`() {
        assertNull(redactMcpUrl(null))
        assertEquals("", redactMcpUrl(""))
    }
    @Test fun `an unparseable url loses its query and userinfo`() {
        assertFalse(redactMcpUrl("custom://user:SENTINEL@host/x?token=SENTINEL")!!.contains("SENTINEL"))
    }
}
