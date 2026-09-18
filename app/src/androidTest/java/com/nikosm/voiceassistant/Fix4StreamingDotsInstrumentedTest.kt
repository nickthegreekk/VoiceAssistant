package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Fix #4 — the "Thinking" placeholder dots must yield to real streamed text.
 *
 * The bug: a Direct-Ollama turn keeps `assistantState == THINKING` for the ENTIRE
 * NDJSON stream (the state only leaves THINKING in the caller's `finally`, i.e. after
 * `done:true` plus the final `playResponse`). The accumulated answer, meanwhile, is already
 * on screen from the first content chunk — it lives in the trailing assistant placeholder
 * message that `performDirectOllamaChat` appends and rewrites per chunk
 * (AssistantServiceChat.kt:1276-1363). So the dots rendered *underneath* the growing
 * response for the whole generation, in both the classic voice mini-box
 * (`ControlBar`, `Text("...")`) and text mode (`ChatList`, `Text("Thinking...")`).
 *
 * The fix gates the dots on `streamingText.isNullOrBlank()`, which is published strictly
 * AFTER the placeholder exists (`appendPlaceholderIfNeeded()` -> `updatePlaceholder()` ->
 * `_streamingText.value = content` in the same loop iteration), so the premise holds
 * literally: a non-blank `streamingText` implies visible assistant content.
 *
 * This test is the on-device, end-to-end proof of both halves of that contract:
 *
 *  - **Before** any content chunk (stream open, still waiting on the model) the dots ARE
 *    rendered — the fix must not have removed the progress affordance.
 *  - **After** the first content chunk, while `assistantState` is still THINKING (the
 *    stream is deliberately held open), the dots are GONE and the answer text is on screen.
 *    "Still THINKING" is what makes this a regression test for the real bug rather than for
 *    a state transition: an assertion taken after the turn finished would see `IDLE`, which
 *    hides the dots anyway — and would therefore pass on the broken code too.
 *
 * Harness notes — why this is genuinely end-to-end rather than a mock of the wiring:
 *  - It renders the REAL production composables ([ControlBar], with its real [ChatList]
 *    child in text mode) and reads the REAL service StateFlows ([AssistantService.messages],
 *    `assistantState`, `streamingText`) through `collectAsState`, which is exactly what
 *    MainVoiceScreen's collectors do (`service.streamingText.collect { streamingText = it }`).
 *  - It binds the REAL [AssistantService] through the production `AssistantBinder`, exactly
 *    like MainActivity, and drives a real turn through `sendTextMessageToServer`.
 *  - The only stand-in is the model server: this repo ships no Ollama instance, so a minimal
 *    in-process NDJSON stub serves `POST /api/chat` and holds the stream open between chunks
 *    — those holds are the phases the assertions run in. Instrumentation and service share a
 *    process, so the app's own OkHttp client reaches the stub over loopback (the manifest sets
 *    `usesCleartextTraffic="true"`): the request build, the NDJSON read loop, the placeholder
 *    updates and the `_streamingText` publishing are all production code.
 *  - `persona.voiceMode = NONE`, so `playResponse` takes its documented no-play bail to IDLE
 *    (AssistantServiceAudio.kt:684) and no TTS engine is ever touched.
 *  - `webSearchEnabled`/`ragEnabled` are false and the prompt deliberately avoids the word
 *    "news" (`isNewsRequest`), so no search/RAG network call can be made.
 *
 * Every probe value this class writes is restored in tearDown: the bound service instance
 * outlives the test class, and `sendTextMessageToServer` calls `saveSettings()` — which
 * persists `_ollamaBaseUrls` — so the stub entry is removed from both memory AND
 * SharedPreferences (restore + `saveSettings()`) to leave no trace.
 */
@RunWith(AndroidJUnit4::class)
class Fix4StreamingDotsInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    // Pre-test baseline of everything this class mutates on a service that outlives it.
    private var ollamaBasesBefore: List<ServerConfig> = emptyList()
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
        ollamaBasesBefore = service._ollamaBaseUrls.value
        personaNameBefore = service.currentPersonaName
        messagesBefore = service._messages.value
        // The probe persona must start with no stored history so nothing a previous run left
        // behind can show up in the rendered list.
        service.settingsManager.deletePersonaHistory(PROBE_PERSONA.name)
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching { instrumentation.runOnMainSync { service.stopEverything() } }
        runCatching {
            instrumentation.runOnMainSync {
                service._messages.value = messagesBefore
                service.currentPersonaName = personaNameBefore
                // L5: the recorded history owner follows the restored selection. The debounced
                // persistSettings() below saves the pair (owner, messages), and leaving the owner
                // on the probe persona's name would file the restored transcript (and this test's
                // probe turns before it) under that persona's storage key.
                service.messagesOwnerName = personaNameBefore
                // `sendTextMessageToServer` persists settings, so removing the stub entry
                // from memory is not enough — write the original list back to disk too.
                service._ollamaBaseUrls.value = ollamaBasesBefore
                service.saveSettings()
            }
        }
        runCatching { service.settingsManager.deletePersonaHistory(PROBE_PERSONA.name) }
        runCatching { service._streamingText.value = null }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    /**
     * Classic voice mini-box: the dots are the transcription box's literal `"..."`, rendered
     * under the message bubbles while the assistant has produced nothing yet.
     */
    @Test
    fun classicMiniBoxHidesDotsOnceStreamedTextIsVisible() =
        assertDotsYieldToStreamedText(textModeOpen = false, dotsText = "...")

    /** Text mode: the same placeholder sits at the tail of the chat list as `"Thinking..."`. */
    @Test
    fun textModeListHidesDotsOnceStreamedTextIsVisible() =
        assertDotsYieldToStreamedText(textModeOpen = true, dotsText = "Thinking...")

    /**
     * One real streamed turn against [StubDirectOllamaServer], rendered by the real
     * [ControlBar] off the real service flows, asserting the dots' contract in both phases:
     * present before any content chunk, gone (while still THINKING) once text is on screen.
     */
    private fun assertDotsYieldToStreamedText(textModeOpen: Boolean, dotsText: String) {
        val persona = PROBE_PERSONA
        StubDirectOllamaServer().use { stub ->
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                // Register the stub under the exact name the persona's model tag points at:
                // the chat flow resolves "[Name] <model>" through `_ollamaBaseUrls.find
                // { it.name == name }`, so this is what selects the Direct-Ollama path.
                service._ollamaBaseUrls.value =
                    ollamaBasesBefore.filterNot { it.name == SERVER_NAME } +
                        ServerConfig(name = SERVER_NAME, url = stub.url)
                service.currentPersonaName = persona.name
                // L5: the history owner moves with the selection, exactly as switchPersona does —
                // this test's probe turns are saved under the probe persona's key (which tearDown
                // deletes), not under whichever persona the service bound with.
                service.messagesOwnerName = persona.name
                service._messages.value = emptyList()
            }

            composeRule.setContent {
                val state by service.assistantState.collectAsState()
                val messages by service.messages.collectAsState()
                val streaming by service.streamingText.collectAsState()
                val fraction by service.ttsPlaybackFraction.collectAsState()
                val timestamps by service.ttsWordTimestamps.collectAsState()
                val duration by service.voiceDuration.collectAsState()
                MaterialTheme {
                    ControlBar(
                        textModeOpen = textModeOpen,
                        textInput = "",
                        onTextInputChange = { },
                        attachedFiles = emptyList(),
                        onAttachClick = { },
                        onRemoveAttachment = { },
                        onSendClick = { },
                        onMicClick = { },
                        onStopClick = { },
                        state = state,
                        personaColor = persona.themeColor,
                        personaName = persona.name,
                        onTextModeToggle = { },
                        focusRequester = remember { FocusRequester() },
                        muted = false,
                        silenced = false,
                        onMuteToggle = { },
                        onSilenceToggle = { },
                        handsFreeMode = false,
                        onHandsFreeToggle = { },
                        messages = messages,
                        // Exactly what MainVoiceScreen pins while a Direct-Ollama stream is
                        // in flight (the `if (streamingText != null)` branch at
                        // MainVoiceScreen.kt:495-505): no fake reveal, all text shown. That
                        // is the render state this regression is about — the dots used to sit
                        // under this fully-visible text.
                        revealedChars = Int.MAX_VALUE,
                        streamingText = streaming,
                        ttsPlaybackFraction = fraction,
                        ttsWordTimestamps = timestamps,
                        voiceDuration = duration,
                        miniScrollState = rememberScrollState(),
                        listState = rememberLazyListState(),
                        onEditMessage = { _, _ -> },
                        onDeleteMessage = { },
                        onReplayAudio = { }
                    )
                }
            }
            assertDotsContract(stub, dotsText)
        }
    }

    /**
     * The two-phase assertion. Everything below runs while the stub holds the stream open, so
     * "Phase A" and "Phase B" are real windows in a real generation rather than a race.
     */
    private fun assertDotsContract(stub: StubDirectOllamaServer, dotsText: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        // Drive a real turn through the production entry point MainActivity's send button
        // calls. No attachments, so no file extraction is involved.
        instrumentation.runOnMainSync {
            service.sendTextMessageToServer(PROBE_PROMPT, PROBE_PERSONA, emptyList())
        }

        // ---- Phase A: the turn is in flight and the model has produced NOTHING yet. ----
        awaitService(20_000, "the turn never entered THINKING") {
            service.assistantState.value == AssistantState.THINKING
        }
        awaitService(20_000, "the stub's /api/chat endpoint was never called") {
            stub.requestCount.get() > 0
        }
        waitForNodes(
            PROBE_ANSWER,
            present = false,
            timeoutMs = 20_000,
            failure = "precondition failed: the answer was already on screen before the first " +
                "content chunk — the stub's pre-chunk hold is not being honored"
        )
        waitForNodes(
            dotsText,
            present = true,
            timeoutMs = 20_000,
            failure = "the \"$dotsText\" dots were not rendered while the model was still " +
                "generating — the fix must not remove the progress affordance"
        )

        // ---- Phase B: the first content chunk landed; the turn is STILL streaming. ----
        waitForNodes(
            PROBE_ANSWER,
            present = true,
            timeoutMs = 20_000,
            failure = "the streamed answer never reached the UI"
        )
        assertEquals(
            "precondition failed: the turn must still be streaming for this to be a Fix #4 " +
                "regression test — once the stream ends the state is IDLE and the dots are " +
                "hidden by the state check instead of by streamingText",
            AssistantState.THINKING,
            service.assistantState.value
        )
        assertFalse(
            "streamingText was not published alongside the visible answer, so the render state " +
                "the fix keys off was never reached " +
                "(streamingText=${service.streamingText.value})",
            service.streamingText.value.isNullOrBlank()
        )
        assertFalse(
            "Fix #4 regression: the \"$dotsText\" dots are still rendered UNDER the visible " +
                "streamed answer — the user sees the response and the thinking dots at the " +
                "same time",
            hasExactText(dotsText)
        )
        assertTrue(
            "the answer must remain visible while the dots are suppressed",
            hasExactText(PROBE_ANSWER)
        )

        // ---- Stream ends: the placeholder is finalized in place and lands IDLE. ----
        awaitService(30_000, "the streamed turn never completed") {
            service.assistantState.value == AssistantState.IDLE
        }
        assertEquals(
            "the streamed placeholder was not finalized in place (expected exactly one " +
                "assistant message carrying the streamed text, got " +
                "${service.messages.value.count { it.text == PROBE_ANSWER }})",
            1,
            service.messages.value.count { it.text == PROBE_ANSWER }
        )
        assertFalse(
            "\"$dotsText\" dots survived the end of the turn",
            hasExactText(dotsText)
        )
    }

    /**
     * Semantics presence check that deliberately does NOT wait for Compose idleness: the
     * rendered screen runs an infinite animation (MicRing in voice mode), so idling-based
     * queries are avoided here — this polls the semantics tree directly, which is also what
     * `composeRule.waitUntil` drives frame-by-frame.
     */
    private fun hasExactText(text: String): Boolean = try {
        composeRule.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()
    } catch (_: Throwable) {
        // No semantics root yet (before the very first frame) — treat as "not there".
        false
    }

    private fun waitForNodes(text: String, present: Boolean, timeoutMs: Long, failure: String) {
        composeRule.waitUntil(timeoutMs) { hasExactText(text) == present }
        assertTrue(
            "$failure (queried \"$text\" present=$present, actual=${hasExactText(text)}, " +
                "state=${service.assistantState.value}, " +
                "streamingText=${service.streamingText.value}, " +
                "messages=${service.messages.value.map { it.role + ":" + it.text.take(32) }})",
            hasExactText(text) == present
        )
    }

    /** Polls SERVICE state (not UI) — independent of the Compose clock. */
    private fun awaitService(timeoutMs: Long, failure: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue(
            "$failure (state=${service.assistantState.value}, " +
                "streamingText=${service.streamingText.value}, " +
                "messages=${service.messages.value.map { it.role + ":" + it.text.take(32) }})",
            condition()
        )
    }

    /**
     * Minimal HTTP/1.1 stub answering `POST /api/chat` with the Direct-Ollama streaming NDJSON
     * contract `performDirectOllamaChat` reads (`stream = true`, one JSON object per line):
     * headers flush immediately (so the client's `execute()` returns and the read loop starts);
     * [holdBeforeFirstChunkMs] then passes with NO body data — the "model is thinking" window
     * Phase A asserts in; then ONE `{"message":{"content":...},"done":false}` line carries the
     * WHOLE answer, so `streamingText` and the placeholder text equal [PROBE_ANSWER] exactly;
     * [holdAfterFirstChunkMs] keeps the stream open with that text already published (the window
     * Phase B asserts in — where the old unconditional dots sat next to the text); finally a
     * `{"done":true}` line ends the turn, which is what moves the service out of THINKING.
     *
     * The body is EOF-delimited (`Connection: close`, deliberately no `Content-Length`), which
     * is what makes the holds real streaming pauses instead of one buffered response.
     */
    private class StubDirectOllamaServer(
        private val holdBeforeFirstChunkMs: Long = 8_000,
        private val holdAfterFirstChunkMs: Long = 8_000
    ) : AutoCloseable {

        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val worker: Thread

        val url: String get() = "http://127.0.0.1:${socket.localPort}"
        val requestCount = AtomicInteger()

        init {
            worker = thread(isDaemon = true, name = "stub-direct-ollama") {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { handle(it) }
                    } catch (_: Exception) {
                        // Socket closed by close() (or a failed accept) — the loop exits on the
                        // isClosed check; a test stub has nothing to report here.
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
            // Drain the request body: leaving it unread makes the client see a reset connection
            // instead of a response.
            var remaining = contentLength.toLong()
            val buffer = ByteArray(8_192)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                remaining -= read
            }

            val output = connection.getOutputStream()
            val isChatPost = headerText.startsWith("POST") && headerText.contains("/api/chat")
            if (!isChatPost) {
                output.write(
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                output.flush()
                return
            }

            requestCount.incrementAndGet()
            output.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/x-ndjson\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            output.flush()

            // Window A: the turn has been accepted, nothing has been produced yet.
            Thread.sleep(holdBeforeFirstChunkMs)
            output.write(
                (
                    "{\"model\":\"$PROBE_MODEL\",\"created_at\":\"2026-01-01T00:00:00Z\"," +
                        "\"message\":{\"role\":\"assistant\",\"content\":\"$PROBE_ANSWER\"}," +
                        "\"done\":false}\n"
                    ).toByteArray()
            )
            output.flush()

            // Window B: the full answer is published, the turn is still streaming.
            Thread.sleep(holdAfterFirstChunkMs)
            output.write(
                (
                    "{\"model\":\"$PROBE_MODEL\",\"created_at\":\"2026-01-01T00:00:01Z\"," +
                        "\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true," +
                        "\"done_reason\":\"stop\",\"prompt_eval_count\":3,\"eval_count\":2}\n"
                    ).toByteArray()
            )
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

    private companion object {
        /** Must match the `[Name] model` tag on [PROBE_PERSONA] — that is how the flow resolves it. */
        const val SERVER_NAME = "Fix4ProbeStreamServer"

        /** Not an `isKnownThinkingModel` match, so `think` is never requested. */
        const val PROBE_MODEL = "fix4-probe-stream-model"

        /**
         * The whole answer, delivered as ONE NDJSON chunk. One chunk makes the published
         * `streamingText` and the placeholder's `text` equal this exact string, so the semantics
         * queries below can use exact (non-substring) matches.
         */
        const val PROBE_ANSWER = "PROBE-ANSWER-VISIBLE"

        /**
         * Deliberately avoids the word "news", so `isNewsRequest` cannot fire a search even if a
         * SearxNG instance happened to be configured on the test device.
         */
        const val PROBE_PROMPT = "describe the probe answer"

        /**
         * The persona under test: direct (non-cloud), pointed at [SERVER_NAME] through the
         * `[Name] model` tag. `voiceMode = NONE` makes `playResponse` take its documented
         * no-play bail straight to IDLE, so no TTS engine is ever touched.
         * `webSearchEnabled`/`ragEnabled` are false and `allowGatewayFailover` is off, so the
         * stub is the only thing this turn can talk to.
         */
        val PROBE_PERSONA = Persona(
            name = "Fix4ProbeStreamPersona",
            themeColor = Color(0xFF4ADE80),
            model = "[$SERVER_NAME] $PROBE_MODEL",
            systemPrompt = "Instrumentation probe persona",
            isCloud = false,
            backendUrl = "",
            allowGatewayFailover = false,
            voiceMode = VoiceMode.NONE,
            enableThinking = false,
            webSearchEnabled = false,
            ragEnabled = false
        )
    }
}
