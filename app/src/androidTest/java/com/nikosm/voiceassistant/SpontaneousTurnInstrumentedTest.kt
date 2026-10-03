package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spontaneous messages — the TURN, proven against the production entry point.
 *
 * The pure half (framing, suffix, prompt composition, silence parsing) is covered on the JVM
 * (`SpontaneousTurnTest`). What only a device can show is the execution:
 *  - a `runSpontaneousTurn` for a persona produces a completion through the REAL loopback
 *    Ollama and lands it in THAT persona's history — never the single-chat author's, never
 *    through the streaming pipeline;
 *  - silence writes nothing at all;
 *  - the wire request honours the isolation contract (non-streaming, framing instruction
 *    last, persona prompt first, persona’s own instruction riding along);
 *  - an unreachable server writes nothing and throws nothing (the caller swallows; a
 *    background miss must never become an error bubble).
 *
 * Everything runs through `AssistantService.runSpontaneousTurn` on the REAL bound service;
 * the only stand-in is the loopback stub. Probe personas are NEVER activated (except the
 * one explicitly live-path test), so the detached sink and its synchronous persist are what
 * make the persistence assertions race-free without sleeping for the service debounce.
 */
@RunWith(AndroidJUnit4::class)
class SpontaneousTurnInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    private companion object {
        const val LOCAL_SERVER = "ProbeOllamaTurn"
        const val MODEL = "probe-model"
        const val LOCAL_MODEL = "[$LOCAL_SERVER] $MODEL"
        const val MAKE_MESSAGE = "Hello from the other side."
        const val PROBE_USER = "nikos"
        const val PROBE_PASSWORD = "s3cret"
    }

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private var personasBefore: List<Persona> = emptyList()
    private var ollamaBefore: List<ServerConfig> = emptyList()
    private var messagesBefore: List<ChatMessage> = emptyList()
    private var currentPersonaBefore: String? = null
    private var messagesOwnerBefore: String? = null

    @Before
    fun bindAssistantService() {
        val bound = CountDownLatch(1)
        var boundService: AssistantService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as AssistantService.AssistantBinder).getService()
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) { /* not expected here */ }
        }
        connection = conn
        assertTrue(
            "bindService() refused to connect to AssistantService",
            context.bindService(Intent(context, AssistantService::class.java), conn, Context.BIND_AUTO_CREATE)
        )
        assertTrue("AssistantService did not bind within 30s", bound.await(30, TimeUnit.SECONDS))
        service = boundService!!
        // Join the service's startup history load before this test touches _messages:
        // onCreate -> loadSettings() -> switchPersona() publishes _messages asynchronously,
        // and a test that assigns _messages while that is still in flight has it
        // overwritten underneath (a replay then finds no message to index and publishes
        // nothing). No-op when no history operation is in flight.
        runBlocking { service.awaitPersonaHistoryIo() }

        personasBefore = service.personas.value
        ollamaBefore = service._ollamaBaseUrls.value
        messagesBefore = service._messages.value
        currentPersonaBefore = service.currentPersonaName
        messagesOwnerBefore = service.messagesOwnerName
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching {
            instrumentation.runOnMainSync {
                service.personas.value.indices.reversed().forEach { service.removePersona(it) }
                personasBefore.forEach { service.addPersona(it) }
                service._ollamaBaseUrls.value = ollamaBefore
                service._messages.value = messagesBefore
                service.currentPersonaName = currentPersonaBefore
                service.messagesOwnerName = messagesOwnerBefore
                service.saveSettings()
            }
        }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    private fun mainSync(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun localPersona(name: String, enabled: Boolean) = Persona(
        name = name, themeColor = Color(0xFF4ADE80), model = LOCAL_MODEL,
        systemPrompt = "You are a warm friend.", isCloud = false, spontaneousEnabled = enabled
    )

    private fun stored(name: String): Persona =
        service.personas.value.firstOrNull { it.name == name }
            ?: error("persona '$name' is not in the service's list")

    private fun roster(name: String): List<ChatMessage> =
        service.settingsManager.getPersonaMessages(name) ?: emptyList()

    private fun registerProbeServer(url: String) = mainSync {
        service._ollamaBaseUrls.value =
            ollamaBefore.filterNot { it.name == LOCAL_SERVER } +
                ServerConfig(LOCAL_SERVER, url, PROBE_USER, PROBE_PASSWORD, AuthType.BASIC)
    }

    // ------------------------------------------------------------------ the turn

    @Test fun `turn lands in that personas history only`() {
        OllamaSuggestionStub(chatContent = MAKE_MESSAGE).use { stub ->
            registerProbeServer(stub.url)
            mainSync { service.addPersona(localPersona("ProbeTurn", enabled = true)) }
            val historyBefore = roster("ProbeTurn")
            val sharedBefore = service._messages.value

            runBlocking { service.runSpontaneousTurn(stored("ProbeTurn")) }

            // The probe persona was never activated, so the detached sink persisted
            // synchronously — no debounce to sleep for.
            val historyAfter = roster("ProbeTurn")
            assertEquals(
                "exactly one spontaneous message must land",
                historyBefore.size + 1, historyAfter.size
            )
            val landed = historyAfter.last()
            assertEquals("assistant", landed.role)
            assertEquals(MAKE_MESSAGE, landed.text)
            assertFalse(landed.isError)
            // Nothing touched the single-chat author's transcript from underneath it.
            assertEquals(sharedBefore, service._messages.value)
        }
    }


    @Test fun `a silence sentinel writes nothing`() {
        OllamaSuggestionStub(chatContent = SPONTANEOUS_SILENCE_SENTINEL).use { stub ->
            registerProbeServer(stub.url)
            mainSync { service.addPersona(localPersona("ProbeSilence", enabled = true)) }
            val historyBefore = roster("ProbeSilence")

            runBlocking { service.runSpontaneousTurn(stored("ProbeSilence")) }

            // The request still went out (one turn was attempted), but no message may land.
            assertEquals(1, stub.requests.size)
            assertEquals(historyBefore, roster("ProbeSilence"))
        }
    }

    @Test fun `an unreachable server raises a transport error and writes nothing`() {
        mainSync {
            service._ollamaBaseUrls.value =
                ollamaBefore.filterNot { it.name == LOCAL_SERVER } +
                    ServerConfig(LOCAL_SERVER, "http://127.0.0.1:1", PROBE_USER, PROBE_PASSWORD, AuthType.BASIC)
            service.addPersona(localPersona("ProbeDead", enabled = true))
        }
        val historyBefore = roster("ProbeDead")

        // Port 1 refuses instantly on every platform, so this is quick. The turn itself
        // lets the transport error propagate — the CALLER is what classifies and silently
        // skips (the dispatch site catches; the future worker re-arms without retrying).
        // Swallowing it here would conflate "server down" with "chose silence" for every
        // future caller. What is asserted is that it fails CLEANLY: nothing is persisted.
        val outcome = runCatching { runBlocking { service.runSpontaneousTurn(stored("ProbeDead")) } }
        assertTrue(
            "expected a transport failure, got: ${outcome.getOrNull() ?: outcome.exceptionOrNull()}",
            outcome.exceptionOrNull() is java.io.IOException
        )
        assertEquals(historyBefore, roster("ProbeDead"))
    }

    @Test fun `wire request is non-streaming and carries the persona framing`() {
        OllamaSuggestionStub(chatContent = MAKE_MESSAGE).use { stub ->
            registerProbeServer(stub.url)
            mainSync {
                service.addPersona(
                    localPersona("ProbeFramed", enabled = true)
                        .copy(spontaneousInstruction = "only ever ask about the garden")
                )
            }

            runBlocking { service.runSpontaneousTurn(stored("ProbeFramed")) }

            val body = JSONObject(stub.requests.single().body)
            assertFalse("there is no bubble to stream into", body.getBoolean("stream"))
            val messages = body.getJSONArray("messages")
            // First is the system prompt — the persona's own governs, framing rides after.
            val system = messages.getJSONObject(0)
            assertEquals("system", system.getString("role"))
            val prompt = system.getString("content")
            assertTrue(prompt.contains("You are a warm friend."))
            assertTrue("the persona's own instruction must reach the model", prompt.contains("only ever ask about the garden"))
            assertTrue(prompt.contains(SPONTANEOUS_SILENCE_SENTINEL))
            assertTrue("the whole framing is budgeted as one piece", prompt.indexOf("own initiative") < prompt.length)
            // Last is the instruction turn that ends the alternation.
            val last = messages.getJSONObject(messages.length() - 1)
            assertEquals("user", last.getString("role"))
            assertTrue(last.getString("content").contains(SPONTANEOUS_SILENCE_SENTINEL))
            // Current date/time is part of every budgeted request.
            assertTrue(prompt.contains("Current date and time:"))
        }
    }
}

