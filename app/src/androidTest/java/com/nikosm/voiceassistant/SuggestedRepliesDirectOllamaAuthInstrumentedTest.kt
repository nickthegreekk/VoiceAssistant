package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Suggested-replies routing proof for the exact `Assistant (Voice)` failure:
 * a direct-Ollama persona (`[Name] model`, cloud=false) whose Ollama server
 * entry requires basic auth. Loopback stub + harness live in part 2 of this
 * split file (same directory).
 */
@RunWith(AndroidJUnit4::class)
class SuggestedRepliesDirectOllamaAuthInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private var ollamaBefore: List<ServerConfig> = emptyList()
    private var serverBasesBefore: List<ServerConfig> = emptyList()
    private var personaNameBefore: String? = null
    private var messagesBefore: List<ChatMessage> = emptyList()

    @Before
    fun bindAssistantService() {
        val bound = CountDownLatch(1)
        var boundService: AssistantService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as AssistantService.AssistantBinder).getService()
                bound.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) { }
        }
        connection = conn
        assertTrue(
            "bindService() refused to connect to AssistantService",
            context.bindService(
                Intent(context, AssistantService::class.java),
                conn,
                Context.BIND_AUTO_CREATE
            )
        )
        assertTrue("AssistantService did not bind within 30s", bound.await(30, TimeUnit.SECONDS))
        service = boundService!!
        ollamaBefore = service._ollamaBaseUrls.value
        serverBasesBefore = service._serverBases.value
        personaNameBefore = service.currentPersonaName
        messagesBefore = service._messages.value
        serverFailCooldownUntilMillis.keys
            .filter { it.startsWith(SUGGEST_LOOPBACK_PREFIX) }
            .forEach { serverFailCooldownUntilMillis.remove(it) }
        service.settingsManager.deletePersonaHistory(PROBE_PERSONA)
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching { instrumentation.runOnMainSync { service.stopEverything() } }
        runCatching {
            instrumentation.runOnMainSync {
                service._messages.value = messagesBefore
                service.currentPersonaName = personaNameBefore
                service.messagesOwnerName = personaNameBefore
                service._ollamaBaseUrls.value = ollamaBefore
                service._serverBases.value = serverBasesBefore
                service.saveSettings()
            }
        }
        serverFailCooldownUntilMillis.keys
            .filter { it.startsWith(SUGGEST_LOOPBACK_PREFIX) }
            .forEach { serverFailCooldownUntilMillis.remove(it) }
        runCatching { service.settingsManager.deletePersonaHistory(PROBE_PERSONA) }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    @Test
    fun directOllamaPersonaWithBasicAuthReachesApiChatWithCredentials() {
        OllamaSuggestionStub().use { stub ->
            val ollamaEntry = ServerConfig(
                name = PROBE_OLLAMA,
                url = stub.url,
                username = PROBE_USER,
                password = PROBE_PASSWORD,
                authType = AuthType.BASIC
            )
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                service._ollamaBaseUrls.value =
                    ollamaBefore.filterNot { it.name == PROBE_OLLAMA } + ollamaEntry
                service.currentPersonaName = PROBE_PERSONA
                service.messagesOwnerName = PROBE_PERSONA
                service._messages.value = listOf(
                    ChatMessage("user", "What is the capital of France?"),
                    ChatMessage("assistant", "Paris is the capital of France. Want a quick quiz on it?")
                )
            }
            val persona = Persona(
                name = PROBE_PERSONA,
                themeColor = PROBE_COLOR,
                model = "[$PROBE_OLLAMA] ornith-1.5:9b",
                systemPrompt = "Routing probe persona.",
                isCloud = false,
                backendUrl = "",
                allowGatewayFailover = false,
                voiceMode = VoiceMode.GATEWAY,
                enableThinking = false,
                webSearchEnabled = false,
                ragEnabled = false,
                maxTokens = 256,
                numCtx = 8192
            )

            var suggestions: List<String>? = null
            var failure: Throwable? = null
            val done = CountDownLatch(1)
            Thread {
                try {
                    suggestions = kotlinx.coroutines.runBlocking {
                        service.fetchSuggestedReplies(persona)
                    }
                } catch (e: Throwable) {
                    failure = e
                } finally {
                    done.countDown()
                }
            }.start()
            assertTrue("fetchSuggestedReplies never returned within 30s", done.await(30, TimeUnit.SECONDS))
            assertTrue("fetchSuggestedReplies threw: $failure", failure == null)

            val chatHits = stub.requests.filter { it.path == "/api/chat" || it.path.startsWith("/api/chat?") }
            assertTrue(
                "no /api/chat request reached the stub (old code routes this persona to the gateway, " +
                    "which 401s before any generation)",
                chatHits.isNotEmpty()
            )
            assertEquals(
                "the stub must see the Ollama entry's basic-auth credentials",
                basicHeader(),
                chatHits.first().headers["authorization"]
            )
            assertEquals(
                listOf("Yes, quiz me on Paris", "What else is nearby?", "Thanks, that's clear"),
                suggestions
            )
        }
    }

    private fun basicHeader(): String =
        "Basic " + android.util.Base64.encodeToString(
            "$PROBE_USER:$PROBE_PASSWORD".toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )

    private companion object {
        const val PROBE_PERSONA = "SuggestedRepliesDirectOllamaProbePersona"
        const val PROBE_OLLAMA = "SuggestedRepliesProbeOllama"
        const val PROBE_USER = "nikos"
        const val PROBE_PASSWORD = "s3cret"
        val PROBE_COLOR = Color(0xFF4ADE80)
    }
}
