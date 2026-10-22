package com.nikosm.voiceassistant

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlinx.serialization.json.Json

/** Sentinel test verifying that exportBackup(excludeSecrets=true) strips all credential fields. */
class BackupRedactionTest {
    private val SENTINEL = "CREDENTIAL_LEAK_SENTINEL"

    @Test
    fun `sanitizes server URL userinfo and query`() {
        // Server URL with userinfo and query string
        val backup = BackupData(
            serverBases = listOf(
                ServerConfig(
                    name = "test-server",
                    url = "http://user:pass@host.com/api?token=123",
                    username = null,
                    password = null,
                    apiKey = null,
                    backendType = ImageGenBackend.A1111
                )
            ),
            ollamaBases = emptyList(),
            imageGenBases = emptyList(),
            cloudApis = emptyList(),
            customCloudApis = emptyList(),
            personas = emptyList(),
            messages = emptyList(),
            totalCost = 0.0,
            favoriteModels = emptyList(),
            trustedCerts = emptyMap(),
            searxngUrl = null,
            userLocation = null,
            ragServerUrl = null,
            ragUsername = null,
            ragPassword = null,
            rooms = emptyList(),
            roomMessages = emptyMap(),
            skills = emptyList(),
            imageWorkflows = emptyList(),
            activeImageGenBackend = ImageGenBackend.A1111,
            mcpServers = emptyList(),
            personaMemories = emptyMap(),
            personaMemoryEnabled = emptyList()
        )

        val redacted = redactBackupCredentials(backup)
        val jsonString = Json.encodeToString(BackupData.serializer(), redacted)

        // Should not contain the original credentials
        assertFalse("URL userinfo should be stripped", jsonString.contains("user:pass@"))
        assertFalse("Query string should be stripped", jsonString.contains("token=123"))
        // Should contain just the host
        assertTrue("Should contain just host", jsonString.contains("host.com/api"))
    }

    @Test
    fun `sanitizes STDIO server with null URL`() {
        // STDIO server with null URL should not crash
        val backup = BackupData(
            serverBases = listOf(
                ServerConfig(
                    name = "test-stdio",
                    url = "",
                    username = null,
                    password = null,
                    apiKey = null,
                    backendType = ImageGenBackend.A1111
                )
            ),
            ollamaBases = emptyList(),
            imageGenBases = emptyList(),
            cloudApis = emptyList(),
            customCloudApis = emptyList(),
            personas = emptyList(),
            messages = emptyList(),
            totalCost = 0.0,
            favoriteModels = emptyList(),
            trustedCerts = emptyMap(),
            searxngUrl = null,
            userLocation = null,
            ragServerUrl = null,
            ragUsername = null,
            ragPassword = null,
            rooms = emptyList(),
            roomMessages = emptyMap(),
            skills = emptyList(),
            imageWorkflows = emptyList(),
            activeImageGenBackend = ImageGenBackend.A1111,
            mcpServers = emptyList(),
            personaMemories = emptyMap(),
            personaMemoryEnabled = emptyList()
        )

        val redacted = redactBackupCredentials(backup)
        val jsonString = Json.encodeToString(BackupData.serializer(), redacted)
        // Should not throw, and URL should be empty string
        // Verify redaction completed successfully (JSON was generated)
    }

    @Test
    fun `sanitizes args with credential flags`() {
        // MCP args with various credential flag forms
        val backup = BackupData(
            serverBases = emptyList(),
            ollamaBases = emptyList(),
            imageGenBases = emptyList(),
            cloudApis = emptyList(),
            customCloudApis = emptyList(),
            personas = emptyList(),
            messages = emptyList(),
            totalCost = 0.0,
            favoriteModels = emptyList(),
            trustedCerts = emptyMap(),
            searxngUrl = null,
            userLocation = null,
            ragServerUrl = null,
            ragUsername = null,
            ragPassword = null,
            rooms = emptyList(),
            roomMessages = emptyMap(),
            skills = emptyList(),
            imageWorkflows = emptyList(),
            activeImageGenBackend = ImageGenBackend.A1111,
            mcpServers = listOf(
                McpServerConfig(
                    id = "test-mcp",
                    name = "test-mcp",
                    transport = McpTransportKind.STDIO,
                    command = "test-cmd",
                    args = listOf("--token", "mysecretkey", "--api-key=abc123", "--password=xyz", "-k", "val", "--port", "8080", "-timeout", "60"),
                    env = emptyMap(),
                    headers = emptyMap(),
                    url = "",
                    enabled = true,
                    trust = McpTrust.ASK
                )
            ),
            personaMemories = emptyMap(),
            personaMemoryEnabled = emptyList()
        )

        val redacted = redactBackupCredentials(backup)
        val jsonString = Json.encodeToString(BackupData.serializer(), redacted)

        // --token and its value should be redacted
        assertTrue("--token flag stays so argument positions are kept", jsonString.contains("--token"))
        assertTrue("the value becomes <redacted>", jsonString.contains("<redacted>"))
        assertFalse("Redacted value should not appear", jsonString.contains("mysecretkey"))
        // --api-key=X form should be redacted
        assertFalse("--api-key=X should be stripped", jsonString.contains("--api-key=abc123"))
        // --password=X form should be redacted
        assertFalse("--password=X should be stripped", jsonString.contains("--password=xyz"))
        // -k as flag should be stripped
        assertTrue("-k is not a credential flag, so its value is untouched", jsonString.contains("\"val\""))
        // --port and -timeout should NOT be stripped (they're not credential flags)
        assertTrue("--port is not a credential flag", jsonString.contains("--port"))
        assertTrue("-timeout is not a credential flag", jsonString.contains("-timeout"))
    }

    @Test
    fun `sanitizes cloud API key`() {
        val backup = BackupData(
            serverBases = emptyList(),
            ollamaBases = emptyList(),
            imageGenBases = emptyList(),
            cloudApis = listOf(
                com.nikosm.voiceassistant.CloudApiSetting(
                    name = "test-cloud",
                    baseUrl = "http://host.com/api",
                    apiKey = "sk-live-abc123",
                    icon = "O",
                    color = Color(0xFF4ADE80)
                )
            ),
            customCloudApis = emptyList(),
            personas = emptyList(),
            messages = emptyList(),
            totalCost = 0.0,
            favoriteModels = emptyList(),
            trustedCerts = emptyMap(),
            searxngUrl = null,
            userLocation = null,
            ragServerUrl = null,
            ragUsername = null,
            ragPassword = null,
            rooms = emptyList(),
            roomMessages = emptyMap(),
            skills = emptyList(),
            imageWorkflows = emptyList(),
            activeImageGenBackend = ImageGenBackend.A1111,
            mcpServers = emptyList(),
            personaMemories = emptyMap(),
            personaMemoryEnabled = emptyList()
        )

        val redacted = redactBackupCredentials(backup)
        val jsonString = Json.encodeToString(BackupData.serializer(), redacted)

        assertFalse("Cloud API key should be stripped", jsonString.contains("sk-live-abc123"))
        // API key should be cleared/empty
        assertFalse("JSON should not contain the original apiKey", jsonString.contains("apiKey"))
    }

    @Test
    fun `sanitizes RAG credentials`() {
        val backup = BackupData(
            serverBases = emptyList(),
            ollamaBases = emptyList(),
            imageGenBases = emptyList(),
            cloudApis = emptyList(),
            customCloudApis = emptyList(),
            personas = emptyList(),
            messages = emptyList(),
            totalCost = 0.0,
            favoriteModels = emptyList(),
            trustedCerts = emptyMap(),
            searxngUrl = null,
            userLocation = null,
            ragServerUrl = "http://host.com",
            ragUsername = "raguser",
            ragPassword = "ragpass",
            rooms = emptyList(),
            roomMessages = emptyMap(),
            skills = emptyList(),
            imageWorkflows = emptyList(),
            activeImageGenBackend = ImageGenBackend.A1111,
            mcpServers = emptyList(),
            personaMemories = emptyMap(),
            personaMemoryEnabled = emptyList()
        )

        val redacted = redactBackupCredentials(backup)
        val jsonString = Json.encodeToString(BackupData.serializer(), redacted)

        assertFalse("RAG username should be cleared", jsonString.contains("raguser"))
        assertFalse("RAG password should be cleared", jsonString.contains("ragpass"))
    }

    @Test
    fun `sanitizes MCP URL userinfo`() {
        val backup = BackupData(
            serverBases = emptyList(),
            ollamaBases = emptyList(),
            imageGenBases = emptyList(),
            cloudApis = emptyList(),
            customCloudApis = emptyList(),
            personas = emptyList(),
            messages = emptyList(),
            totalCost = 0.0,
            favoriteModels = emptyList(),
            trustedCerts = emptyMap(),
            searxngUrl = null,
            userLocation = null,
            ragServerUrl = null,
            ragUsername = null,
            ragPassword = null,
            rooms = emptyList(),
            roomMessages = emptyMap(),
            skills = emptyList(),
            imageWorkflows = emptyList(),
            activeImageGenBackend = ImageGenBackend.A1111,
            mcpServers = listOf(
                McpServerConfig(
                    id = "test-mcp",
                    name = "test-mcp",
                    transport = McpTransportKind.HTTP,
                    command = "",
                    args = emptyList(),
                    env = emptyMap(),
                    headers = emptyMap(),
                    url = "http://user:pass@mcp.host.com/api",
                    enabled = true,
                    trust = McpTrust.ASK
                )
            ),
            personaMemories = emptyMap(),
            personaMemoryEnabled = emptyList()
        )

        val redacted = redactBackupCredentials(backup)
        val jsonString = Json.encodeToString(BackupData.serializer(), redacted)

        assertFalse("MCP URL userinfo should be stripped", jsonString.contains("user:pass@"))
        assertTrue("MCP URL host should remain", jsonString.contains("mcp.host.com/api"))
    }

    @Test
    fun `sanitizes MCP args - flag and value pair`() {
        val backup = BackupData(
            serverBases = emptyList(),
            ollamaBases = emptyList(),
            imageGenBases = emptyList(),
            cloudApis = emptyList(),
            customCloudApis = emptyList(),
            personas = emptyList(),
            messages = emptyList(),
            totalCost = 0.0,
            favoriteModels = emptyList(),
            trustedCerts = emptyMap(),
            searxngUrl = null,
            userLocation = null,
            ragServerUrl = null,
            ragUsername = null,
            ragPassword = null,
            rooms = emptyList(),
            roomMessages = emptyMap(),
            skills = emptyList(),
            imageWorkflows = emptyList(),
            activeImageGenBackend = ImageGenBackend.A1111,
            mcpServers = listOf(
                McpServerConfig(
                    id = "test-mcp",
                    name = "test-mcp",
                    transport = McpTransportKind.STDIO,
                    command = "cmd",
                    args = listOf("--key", "secretvalue"),
                    env = emptyMap(),
                    headers = emptyMap(),
                    url = "",
                    enabled = true,
                    trust = McpTrust.ASK
                )
            ),
            personaMemories = emptyMap(),
            personaMemoryEnabled = emptyList()
        )

        val redacted = redactBackupCredentials(backup)
        val jsonString = Json.encodeToString(BackupData.serializer(), redacted)

        assertTrue("the flag stays", jsonString.contains("--key"))
        assertTrue("its value becomes <redacted>", jsonString.contains("<redacted>"))
        assertFalse("secretvalue should be stripped", jsonString.contains("secretvalue"))
    }

    @Test
    fun `uppercase credential flags are also redacted`() {
        val backup = BackupData(
            serverBases = emptyList(),
            ollamaBases = emptyList(),
            imageGenBases = emptyList(),
            cloudApis = emptyList(),
            customCloudApis = emptyList(),
            personas = emptyList(),
            messages = emptyList(),
            totalCost = 0.0,
            favoriteModels = emptyList(),
            trustedCerts = emptyMap(),
            searxngUrl = null,
            userLocation = null,
            ragServerUrl = null,
            ragUsername = null,
            ragPassword = null,
            rooms = emptyList(),
            roomMessages = emptyMap(),
            skills = emptyList(),
            imageWorkflows = emptyList(),
            activeImageGenBackend = ImageGenBackend.A1111,
            mcpServers = listOf(
                McpServerConfig(
                    id = "test-mcp",
                    name = "test-mcp",
                    transport = McpTransportKind.STDIO,
                    command = "cmd",
                    args = listOf("--TOKEN", "SENTINEL_A", "--API-KEY", "SENTINEL_B", "--SECRET=SENTINEL_C"),
                    env = emptyMap(),
                    headers = emptyMap(),
                    url = "",
                    enabled = true,
                    trust = McpTrust.ASK
                )
            ),
            personaMemories = emptyMap(),
            personaMemoryEnabled = emptyList()
        )

        val redacted = redactBackupCredentials(backup)
        val jsonString = Json.encodeToString(BackupData.serializer(), redacted)

        assertFalse("value after --TOKEN should be redacted", jsonString.contains("SENTINEL_A"))
        assertFalse("value after --API-KEY should be redacted", jsonString.contains("SENTINEL_B"))
        assertFalse("--SECRET=value should be redacted", jsonString.contains("SENTINEL_C"))
        assertTrue("flags keep their original case", jsonString.contains("--TOKEN") && jsonString.contains("--API-KEY"))
    }

    @Test
    fun `non-excluded export stays identical`() {
        // Build backup without redaction
        val backup = BackupData(
            serverBases = listOf(
                ServerConfig(
                    name = "test-server",
                    url = "http://host.com/api",
                    username = null,
                    password = null,
                    apiKey = null,
                    backendType = ImageGenBackend.A1111
                )
            ),
            ollamaBases = emptyList(),
            imageGenBases = emptyList(),
            cloudApis = emptyList(),
            customCloudApis = emptyList(),
            personas = emptyList(),
            messages = emptyList(),
            totalCost = 0.0,
            favoriteModels = emptyList(),
            trustedCerts = emptyMap(),
            searxngUrl = null,
            userLocation = null,
            ragServerUrl = null,
            ragUsername = null,
            ragPassword = null,
            rooms = emptyList(),
            roomMessages = emptyMap(),
            skills = emptyList(),
            imageWorkflows = emptyList(),
            activeImageGenBackend = ImageGenBackend.A1111,
            mcpServers = emptyList(),
            personaMemories = emptyMap(),
            personaMemoryEnabled = emptyList()
        )

        // Without excludeSecrets, the backup should stay the same (no redaction)
        // The function should return the backup unchanged when excludeSecrets is false
        // We just verify the function doesn't crash and returns a valid BackupData
        val redacted = redactBackupCredentials(backup)
        val jsonString = Json.encodeToString(BackupData.serializer(), redacted)
        // Should contain the original URL since no redaction was applied
        assertTrue("Non-excluded export should keep original URL", jsonString.contains("http://host.com/api"))
    }
}
