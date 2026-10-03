package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.io.File
import kotlin.concurrent.thread

/**
 * Skills — the INJECTION layer, proven on-device through the production entry point.
 *
 * The pure matching half is covered on the JVM (`SkillMatchingTest`). What only a real
 * service can show is the wiring: that the resolved skill text actually reaches the model's
 * system prompt, on every flow, and — just as importantly — that it does NOT leak anywhere
 * it must not go.
 *
 * Every turn below is driven through `sendTextMessageToServer` on the REAL bound
 * [AssistantService], against a loopback stub that CAPTURES the request body. The stub is the
 * only stand-in; the budget math, request assembly, routing and refusal are all production
 * code. The captured system prompt is the assertion surface, because that is exactly where
 * injected skill text is supposed to be visible.
 *
 * Covered:
 *  1. [matchingSkillReachesTheSystemPrompt] — the skill's instructions ARE in the system
 *     prompt, while the user's own message stays byte-for-byte what they typed.
 *  2. [multiMatchSkillsAreInjectedInLibraryOrder] — all matches, in library order (Q1).
 *  3. [skillTextIsAbsentFromTheTranscriptAndTheNextTurn] — the one-turn guarantee (Q2).
 *  4. [oversizedSkillTextIsCountedByTheBudgetRefusal] — injected text is part of the system
 *     prompt BEFORE `resolveHistoryBudget`, so it can push a request over the window.
 *  5. [aNonMatchingTurnInjectsNothing] — the inverse control, so a build that injected
 *     everything would not pass 1-4.
 */
@RunWith(AndroidJUnit4::class)
class SkillInjectionInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private var ollamaBasesBefore: List<ServerConfig> = emptyList()
    private var serverBasesBefore: List<ServerConfig> = emptyList()
    private var roomMessagesBefore: List<RoomMessage> = emptyList()
    private var skillsBefore: List<Skill> = emptyList()
    private var personasBefore: List<Persona> = emptyList()
    private var personaNameBefore: String? = null
    private var messagesBefore: List<ChatMessage> = emptyList()

    /** Voice recordings are written into the app's own cacheDir; removed in tearDown. */
    private val scratchFiles = mutableListOf<File>()

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
            context.bindService(
                Intent(context, AssistantService::class.java),
                conn,
                Context.BIND_AUTO_CREATE
            )
        )
        assertTrue("AssistantService did not bind within 30s", bound.await(30, TimeUnit.SECONDS))
        service = boundService!!
        // Join the service's startup history load before this test touches _messages:
        // onCreate -> loadSettings() -> switchPersona() publishes _messages asynchronously,
        // and a test that assigns _messages while that is still in flight has it
        // overwritten underneath (a replay then finds no message to index and publishes
        // nothing). No-op when no history operation is in flight.
        runBlocking { service.awaitPersonaHistoryIo() }
        ollamaBasesBefore = service._ollamaBaseUrls.value
        serverBasesBefore = service._serverBases.value
        roomMessagesBefore = service._roomMessages.value
        skillsBefore = service._skills.value
        personasBefore = service.personas.value
        personaNameBefore = service.currentPersonaName
        messagesBefore = service._messages.value
        service.settingsManager.deletePersonaHistory(PROBE_PERSONA)
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching { instrumentation.runOnMainSync { service.stopEverything() } }
        runCatching {
            instrumentation.runOnMainSync {
                service._messages.value = messagesBefore
                service._skills.value = skillsBefore
                // Restored through the public API, in reverse so indices stay valid, and
                // `_personas` itself is private — this is the only way back.
                service.personas.value.indices.reversed().forEach { service.removePersona(it) }
                personasBefore.forEach { service.addPersona(it) }
                service._roomMessages.value = roomMessagesBefore
                service.currentPersonaName = personaNameBefore
                service.messagesOwnerName = personaNameBefore
                service._ollamaBaseUrls.value = ollamaBasesBefore
                service._serverBases.value = serverBasesBefore
                // `sendTextMessageToServer` persists settings, so restoring memory alone is
                // not enough — the stubs and probe library must be written back to disk too.
                service.saveSettings()
            }
        }
        runCatching { service.settingsManager.deletePersonaHistory(PROBE_PERSONA) }
        runCatching { service._streamingText.value = null }
        scratchFiles.forEach { runCatching { it.delete() } }
        scratchFiles.clear()
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    // ---- driving the production entry points -----------------------------------------------

    private fun mainSync(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun registerProbeServer(stub: StubProbeServer) = mainSync {
        service._ollamaBaseUrls.value =
            ollamaBasesBefore.filterNot { it.name == PROBE_SERVER_NAME } +
                ServerConfig(name = PROBE_SERVER_NAME, url = stub.url)
    }

    /** The voice flow's OTHER half: the gateway the audio is transcribed through. */
    private fun registerProbeGateway(stub: StubProbeServer) = mainSync {
        service._serverBases.value =
            serverBasesBefore.filterNot { it.name == PROBE_GATEWAY_NAME } +
                ServerConfig(name = PROBE_GATEWAY_NAME, url = stub.url)
    }

    /** A real recording file in the app's own cacheDir, sent via the production entry point. */
    private fun sendVoiceTurn(persona: Persona) {
        val file = File(context.cacheDir, "skill_probe_recording_${System.currentTimeMillis()}.wav")
        file.writeBytes(ByteArray(64) { 0 })
        scratchFiles += file
        mainSync { service.sendAudioToServer(file, persona) }
    }

    /**
     * Installs the probe library and persona through the SAME public API the settings UI
     * uses (`addSkill` / `addPersona`), rather than assigning the flows directly, so the test
     * exercises the real persistence path too.
     */
    private fun installLibrary(skills: List<Skill>, persona: Persona) = mainSync {
        service._skills.value = emptyList()
        skills.forEach { service.addSkill(it) }
        // Drop any pre-existing personas, then add the probe one last so it is selected.
        personasBefore.indices.reversed().forEach { service.removePersona(it) }
        service.addPersona(persona)
        service.currentPersonaName = persona.name
        // L5: the history owner moves with the selection, exactly as switchPersona does, so
        // this test's transcript is filed under the probe key that tearDown deletes.
        service.messagesOwnerName = persona.name
        service._messages.value = emptyList()
    }

    /** The send button's own call: the production text entry point, no shortcuts. */
    private fun sendTurn(text: String) = mainSync {
        val persona = service.personas.value.first { it.name == service.currentPersonaName }
        service.sendTextMessageToServer(text, persona)
    }

    private fun awaitService(timeoutMs: Long, failure: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError(failure)
    }

    private fun awaitRequests(stub: StubProbeServer, count: Int, failure: String) {
        awaitService(20_000, "$failure (only ${stub.chatRequestCount.get()} arrived)") {
            stub.chatRequestCount.get() >= count
        }
        awaitService(20_000, "the turn never returned to IDLE") {
            service.assistantState.value == AssistantState.IDLE
        }
    }

    /**
     * The system prompt of the [index]-th captured chat request, read out of the real
     * request body the production builder produced.
     */
    private fun systemPromptAt(stub: StubProbeServer, index: Int): String {
        val body = JSONObject(stub.bodies.getOrNull(index) ?: error(
            "no request #$index reached the stub (only ${stub.chatRequestCount.get()})"
        ))
        val messages = body.getJSONArray("messages")
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            if (message.getString("role") == "system") return message.getString("content")
        }
        error("request #$index carried no system message")
    }

    /** Every non-system message content of the [index]-th request, in order. */
    private fun userMessagesAt(stub: StubProbeServer, index: Int): List<String> {
        val body = JSONObject(stub.bodies.getOrNull(index) ?: error("no request #$index"))
        val messages = body.getJSONArray("messages")
        return (0 until messages.length())
            .map { messages.getJSONObject(it) }
            .filter { it.getString("role") != "system" }
            .map { it.getString("content") }
    }

    /**
     * Loopback HTTP/1.1 stub that CAPTURES each request body and answers `POST /api/chat`
     * with the Direct-Ollama streaming NDJSON contract `performDirectOllamaChat` reads.
     *
     * Capturing the body is the point of this class: injected skill text is only observable
     * in the request the production builder actually produced.
     */
    private class StubProbeServer(
        private val answer: String = ANSWER,
        private val transcript: String? = null
    ) : AutoCloseable {

        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val worker: Thread

        val url: String get() = "http://127.0.0.1:${socket.localPort}"
        val chatRequestCount = AtomicInteger()

        /** Every captured `POST /api/chat` body, in arrival order. */
        val bodies = CopyOnWriteArrayList<String>()

        init {
            worker = thread(isDaemon = true, name = "stub-skill-injection") {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { handle(it) }
                    } catch (_: Exception) {
                        // Socket closed by close() (or a failed accept) — the loop exits on
                        // the isClosed check; a test stub has nothing to report here.
                    }
                }
            }
        }

        private fun handle(connection: Socket) {
            val input = BufferedInputStream(connection.inputStream)
            val headers = StringBuilder()
            while (true) {
                val line = readAsciiLine(input) ?: return
                if (line.isEmpty()) break
                headers.append(line).append('\n')
            }
            val headerText = headers.toString()
            val contentLength = headerText.lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            // Drain the body: leaving it unread makes the client see a reset connection.
            val body = StringBuilder()
            var remaining = contentLength.toLong()
            val buffer = ByteArray(8_192)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                body.append(String(buffer, 0, read, Charsets.UTF_8))
                remaining -= read
            }

            val output = connection.getOutputStream()
            // A voice turn transcribes through the gateway's /transcribe first; the returned
            // text is then the user's message, and therefore the text skills match against.
            if (headerText.startsWith("POST") && headerText.contains("/transcribe")) {
                val reply = "{\"text\":\"${transcript ?: ""}\"}".toByteArray()
                output.write(
                    (
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${reply.size}\r\nConnection: close\r\n\r\n"
                        ).toByteArray()
                )
                output.write(reply)
                output.flush()
                return
            }
            if (!(headerText.startsWith("POST") && headerText.contains("/api/chat"))) {
                output.write(
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                output.flush()
                return
            }

            chatRequestCount.incrementAndGet()
            bodies += body.toString()
            val response = (
                "{\"model\":\"$PROBE_MODEL\",\"created_at\":\"2026-01-01T00:00:00Z\"," +
                    "\"message\":{\"role\":\"assistant\",\"content\":\"$answer\"},\"done\":false}\n" +
                    "{\"model\":\"$PROBE_MODEL\",\"created_at\":\"2026-01-01T00:00:01Z\"," +
                    "\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true," +
                    "\"done_reason\":\"stop\",\"prompt_eval_count\":9,\"eval_count\":4}\n"
                ).toByteArray()
            output.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/x-ndjson\r\n" +
                        "Content-Length: ${response.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            output.write(response)
            output.flush()
        }

        private fun readAsciiLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }

        override fun close() {
            runCatching { socket.close() }
            runCatching { worker.interrupt() }
        }
    }

    // ---- the tests --------------------------------------------------------------------------

    /**
     * The core contract: a matching skill's instructions are present in the system prompt of
     * the request that was actually sent, and the user's own message is untouched.
     *
     * The second half matters as much as the first — trigger matching must not rewrite what
     * the user typed (Q4). Had matching mutated the message, the triggering text would have
     * been replaced or annotated in the transcript.
     */
    @Test
    fun matchingSkillReachesTheSystemPrompt() {
        val persona = probePersona(enabledSkillIds = listOf(SUMMARIZE_ID))
        StubProbeServer().use { stub ->
            registerProbeServer(stub)
            installLibrary(listOf(SUMMARIZE_SKILL), persona)

            sendTurn(TRIGGERED_MESSAGE)
            awaitRequests(stub, 1, "the skill turn never reached the server")

            val systemPrompt = systemPromptAt(stub, 0)
            assertTrue(
                "the skill's instructions were missing from the system prompt, which was:\n$systemPrompt",
                systemPrompt.contains(SUMMARIZE_INSTRUCTIONS)
            )
            // The persona's own prompt still governs — the skill is ADDED, not substituted.
            assertTrue(
                "the persona's own system prompt was replaced instead of extended",
                systemPrompt.contains(SHORT_SYSTEM_PROMPT)
            )
            assertEquals(
                "the user's message must reach the model exactly as typed",
                listOf(TRIGGERED_MESSAGE),
                userMessagesAt(stub, 0)
            )
        }
    }

    /**
     * Q1: when several enabled skills match one message, ALL of them are injected, in the
     * LIBRARY's order — never just the first, and never in the order the persona happens to
     * list their ids.
     */
    @Test
    fun multiMatchSkillsAreInjectedInLibraryOrder() {
        // The persona lists the ids in the OPPOSITE order to the library, so this fails if
        // anything iterates `enabledSkillIds` (a Set, order not contractual) instead.
        val persona = probePersona(enabledSkillIds = listOf(POEMY_ID, SUMMARIZE_ID))
        StubProbeServer().use { stub ->
            registerProbeServer(stub)
            installLibrary(listOf(SUMMARIZE_SKILL, POEMY_SKILL), persona)

            sendTurn(TRIGGERED_MESSAGE)
            awaitRequests(stub, 1, "the multi-match turn never reached the server")

            val systemPrompt = systemPromptAt(stub, 0)
            assertTrue("the first skill was not injected:\n$systemPrompt", systemPrompt.contains(SUMMARIZE_INSTRUCTIONS))
            assertTrue("the second skill was dropped instead of also being injected:\n$systemPrompt", systemPrompt.contains(POEMY_INSTRUCTIONS))
            assertTrue(
                "skills must be injected in library order, but here first=" +
                    "${systemPrompt.indexOf(SUMMARIZE_INSTRUCTIONS)} and second=" +
                    "${systemPrompt.indexOf(POEMY_INSTRUCTIONS)}",
                systemPrompt.indexOf(SUMMARIZE_INSTRUCTIONS) < systemPrompt.indexOf(POEMY_INSTRUCTIONS)
            )
        }
    }

    /**
     * Q2, the one-turn guarantee: injected text belongs to the turn that triggered it and
     * must vanish afterwards.
     *
     * Three separate leaks are covered because they are three different failure modes: the
     * transcript the user sees, the NEXT turn's system prompt, and the next turn's replayed
     * message history.
     */
    @Test
    fun skillTextIsAbsentFromTheTranscriptAndTheNextTurn() {
        val persona = probePersona(enabledSkillIds = listOf(SUMMARIZE_ID))
        StubProbeServer().use { stub ->
            registerProbeServer(stub)
            installLibrary(listOf(SUMMARIZE_SKILL), persona)

            sendTurn(TRIGGERED_MESSAGE)
            awaitRequests(stub, 1, "the skill turn never reached the server")
            assertTrue(
                "the skill text should have been injected on the first turn",
                systemPromptAt(stub, 0).contains(SUMMARIZE_INSTRUCTIONS)
            )

            // A second, NON-triggering turn in the same conversation.
            sendTurn(FOLLOW_UP_MESSAGE)
            awaitRequests(stub, 2, "the follow-up turn never reached the server")

            // 1. The transcript the user sees (and the app persists) holds no skill text.
            val messages = service.messages.value
            assertTrue(
                "the skill's instructions leaked into the transcript: " + messages.map { it.text.take(40) },
                messages.none { it.text.contains(SUMMARIZE_INSTRUCTIONS) }
            )
            assertTrue(
                "the user's own message must still be in the transcript",
                messages.any { it.role == "user" && it.text == TRIGGERED_MESSAGE }
            )
            // 2. The next turn's system prompt must not re-inject the previous turn's skill.
            assertFalse(
                "the previous turn's skill text was replayed into the next turn's system prompt:\n" +
                    systemPromptAt(stub, 1),
                systemPromptAt(stub, 1).contains(SUMMARIZE_INSTRUCTIONS)
            )
            // 3. Nor into the replayed message history.
            assertTrue(
                "the skill text was replayed as a message: ${userMessagesAt(stub, 1)}",
                userMessagesAt(stub, 1).none { it.contains(SUMMARIZE_INSTRUCTIONS) }
            )
            // The prior exchange IS still there — the turn history itself must be intact.
            assertTrue(
                "the first turn should still be in the follow-up's history: ${userMessagesAt(stub, 1)}",
                userMessagesAt(stub, 1).any { it.contains(TRIGGERED_MESSAGE) }
            )
        }
    }

    /**
     * Injected skill text is appended to the system prompt BEFORE `resolveHistoryBudget` runs,
     * so it counts against the persona's Context Window Size. A skill large enough to overflow
     * the window must therefore produce the same actionable refusal as an oversized system
     * prompt — NOT a request the server silently truncates.
     *
     * The control turn matters: without it, a persona so small that even a short message is
     * refused would satisfy this test for the wrong reason.
     */
    @Test
    fun oversizedSkillTextIsCountedByTheBudgetRefusal() {
        val persona = probePersona(enabledSkillIds = listOf(SUMMARIZE_ID))
        val hugeSkill = SUMMARIZE_SKILL.copy(instructions = HUGE_INSTRUCTIONS)

        StubProbeServer().use { stub ->
            registerProbeServer(stub)
            installLibrary(listOf(hugeSkill), persona)

            // A NON-matching turn on this same tiny-window persona still fits: proves the
            // refusal below is caused by the injected skill text, not the window size.
            sendTurn(FOLLOW_UP_MESSAGE)
            awaitRequests(stub, 1, "the non-triggering control turn never reached the server")

            sendTurn(TRIGGERED_MESSAGE)
            awaitService(20_000, "the oversized skill turn never refused") {
                service.messages.value.any { it.isError }
            }
            val error = service.messages.value.last { it.isError }
            assertTrue(
                "expected the actionable context-window refusal, got: ${error.text}",
                error.text.contains("exceed this persona's Context Window Size")
            )
            // The refusal happens pre-send, so only the control turn is on the wire.
            assertEquals(
                "the over-budget request must never be sent, but the stub saw " +
                    "${stub.chatRequestCount.get()} chat request(s)",
                1,
                stub.chatRequestCount.get()
            )
        }
    }

    /**
     * The inverse control: a message that matches nothing injects nothing. Without this, a
     * build that appended the whole skill library to every system prompt would pass the three
     * tests above.
     */
    @Test
    fun aNonMatchingTurnInjectsNothing() {
        val persona = probePersona(enabledSkillIds = listOf(SUMMARIZE_ID, POEMY_ID))
        StubProbeServer().use { stub ->
            registerProbeServer(stub)
            installLibrary(listOf(SUMMARIZE_SKILL, POEMY_SKILL), persona)

            sendTurn(FOLLOW_UP_MESSAGE)
            awaitRequests(stub, 1, "the non-matching turn never reached the server")

            val systemPrompt = systemPromptAt(stub, 0)
            assertFalse(
                "a non-matching message must not inject a skill:\n$systemPrompt",
                systemPrompt.contains(SUMMARIZE_INSTRUCTIONS) || systemPrompt.contains(POEMY_INSTRUCTIONS)
            )
        }
    }

    /**
     * Q7 for the VOICE flow: a spoken turn must inject exactly what the typed one does.
     *
     * This is the regression guard for a real defect, not a hypothetical. The voice path
     * called `performDirectOllamaChat` WITHOUT a `turnContext`, so no skill could ever reach
     * a spoken turn — the feature simply did not exist for voice users, while looking
     * correct in the typed flow.
     *
     * The trigger is matched against the TRANSCRIPTION, so the assertion is only meaningful
     * because the stub gateway returns the triggering sentence: if the code had matched
     * against something else (an empty placeholder, the persona prompt), nothing would fire.
     */
    @Test
    fun voiceTurnInjectsSkillsFromTheTranscription() {
        StubProbeServer(transcript = VOICE_TRANSCRIPT).use { gateway ->
            StubProbeServer().use { ollama ->
                registerProbeGateway(gateway)
                registerProbeServer(ollama)
                val persona = probePersona(listOf(SUMMARIZE_ID), gatewayUrl = gateway.url)
                installLibrary(listOf(SUMMARIZE_SKILL), persona)

                sendVoiceTurn(persona)
                awaitRequests(ollama, 1, "the voice turn never reached the model")

                val systemPrompt = systemPromptAt(ollama, 0)
                assertTrue(
                    "a spoken turn matched by a skill must inject it, as a typed turn does. " +
                        "The voice path used to pass no turnContext at all, so skills never " +
                        "reached it. System prompt was:\n$systemPrompt",
                    systemPrompt.contains(SUMMARIZE_INSTRUCTIONS)
                )
                // And the injected text still never lands in the transcript (Q2, voice too).
                assertTrue(
                    "the skill's instructions leaked into the voice transcript: " +
                        service.messages.value.map { it.text.take(40) },
                    service.messages.value.none { it.text.contains(SUMMARIZE_INSTRUCTIONS) }
                )
            }
        }
    }

    /**
     * The voice flow's inverse control: a spoken turn that matches nothing injects nothing,
     * so the test above cannot pass on a build that injects unconditionally.
     */
    @Test
    fun nonMatchingVoiceTurnInjectsNothing() {
        StubProbeServer(transcript = FOLLOW_UP_MESSAGE).use { gateway ->
            StubProbeServer().use { ollama ->
                registerProbeGateway(gateway)
                registerProbeServer(ollama)
                val persona = probePersona(listOf(SUMMARIZE_ID), gatewayUrl = gateway.url)
                installLibrary(listOf(SUMMARIZE_SKILL), persona)

                sendVoiceTurn(persona)
                awaitRequests(ollama, 1, "the voice turn never reached the model")

                assertFalse(
                    "a spoken turn that matched no skill must inject nothing:\n" +
                        systemPromptAt(ollama, 0),
                    systemPromptAt(ollama, 0).contains(SUMMARIZE_INSTRUCTIONS)
                )
            }
        }
    }

    private companion object {
        /** Must match the `[Name] model` tag on the probe persona — that is how it resolves. */
        const val PROBE_SERVER_NAME = "SkillInjectionProbeServer"

        /** The gateway the voice flow transcribes through. */
        const val PROBE_GATEWAY_NAME = "SkillInjectionProbeGateway"

        /** Not an `isKnownThinkingModel` match, so `think` is never requested. */
        const val PROBE_MODEL = "skill-injection-probe-model"

        const val PROBE_PERSONA = "SkillInjectionProbePersona"

        val PROBE_COLOR = Color(0xFF4ADE80)

        const val ANSWER = "SKILL-PROBE-ANSWER"

        /** A short prompt, so any overflow has to come from the injected skill text. */
        const val SHORT_SYSTEM_PROMPT = "Skill injection probe persona."

        const val SUMMARIZE_ID = "skill-probe-summarize"
        const val POEMY_ID = "skill-probe-poemy"

        const val SUMMARIZE_INSTRUCTIONS = "SKILL-SUMMARIZE-INSTRUCTIONS-MARKER"
        const val POEMY_INSTRUCTIONS = "SKILL-POEMY-INSTRUCTIONS-MARKER"

        /** Starts with the summarize skill's trigger, and also fires the poemy one. */
        const val TRIGGERED_MESSAGE = "summarize this, and make it a poem"

        const val FOLLOW_UP_MESSAGE = "what did you just say?"

        /** What the stub gateway "hears" — starts with the summarize trigger. */
        const val VOICE_TRANSCRIPT = "summarize this out loud please"

        val SUMMARIZE_SKILL = Skill(
            id = SUMMARIZE_ID,
            name = "Summarizer",
            triggerPhrases = listOf("summarize this"),
            instructions = SUMMARIZE_INSTRUCTIONS
        )

        val POEMY_SKILL = Skill(
            id = POEMY_ID,
            name = "Poemy",
            triggerPhrases = listOf("summarize this, and"),
            instructions = POEMY_INSTRUCTIONS
        )

        /**
         * ~2,400 tokens on its own: with 1024 reserved for the reply inside a 2048-token
         * window, injecting this overflows the request on its own.
         */
        val HUGE_INSTRUCTIONS: String = "Skill probe filler instruction line. ".repeat(240)

        const val SMALL_NUM_CTX = 2048
        const val RESERVED_OUTPUT_TOKENS = 1024

        /**
         * Direct-Ollama probe persona pointed at [PROBE_SERVER_NAME] through the `[Name] model`
         * tag, `voiceMode = NONE` (so `playResponse` bails to IDLE without touching TTS), a
         * 2048-token window and the minimum 1024-token output reservation. No web search or
         * RAG, so a skill is the only thing that can change the system prompt.
         *
         * [gatewayUrl] is the transcribing gateway, and is required for a VOICE turn: the
         * direct-Ollama voice branch resolves its gateway from `persona.backendUrl` (it has no
         * other source), so a blank value there fails before any request is made. It is
         * deliberately NOT used as the model's endpoint — that still comes from the `[Name]`
         * tag above, so the text and voice routes differ only in how the text arrives.
         */
        fun probePersona(enabledSkillIds: List<String>, gatewayUrl: String = ""): Persona = Persona(
            name = PROBE_PERSONA,
            themeColor = PROBE_COLOR,
            model = "[$PROBE_SERVER_NAME] $PROBE_MODEL",
            systemPrompt = SHORT_SYSTEM_PROMPT,
            isCloud = false,
            backendUrl = gatewayUrl,
            allowGatewayFailover = false,
            voiceMode = VoiceMode.NONE,
            enableThinking = false,
            webSearchEnabled = false,
            ragEnabled = false,
            maxTokens = RESERVED_OUTPUT_TOKENS,
            numCtx = SMALL_NUM_CTX,
            enabledSkillIds = enabledSkillIds
        )
    }
}
