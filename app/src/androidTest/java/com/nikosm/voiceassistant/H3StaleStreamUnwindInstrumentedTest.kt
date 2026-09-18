package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * H3 — a STALE streaming turn unwinding must not touch the NEWER turn's placeholder.
 *
 * The bug: `streamedPlaceholderIndex` and `_streamingText` are service-global. Turn A
 * streams its first chunk (so it adopts the trailing assistant placeholder and publishes
 * its partial text), turn B starts and adopts a placeholder of its own, and then A's NEXT
 * chunk arrives, hits the per-chunk `isChatRequestCurrent(generation)` guard, and throws
 * `StreamCancelledByUserException`. The unwind that follows ran UNCONDITIONALLY pre-fix:
 * `streamedPlaceholderIndex = null` (killing the index that had since become B's) and
 * `_streamingText.value = null` (blanking B's live overlay). B's remaining chunks then
 * found `streamedPlaceholderIndex == null`, so `updatePlaceholder()` stopped rewriting
 * B's bubble and the apply block fell back to a plain APPEND — leaving B's streamed
 * placeholder stranded next to a second, freshly appended bubble for the same reply.
 * That is the H3 "duplicate bubble" symptom, produced by an unwind belonging to another
 * turn.
 *
 * How this proves it on-device: one loopback Direct-Ollama stub serves BOTH turns (the
 * persona's `[Name] model` tag selects it), and each accepted connection is driven STEP BY
 * STEP from the test thread — so "A's next chunk arrives only after B has adopted its own
 * placeholder" is ENGINEERED rather than assumed, and A is parked mid-stream rather than
 * raced. Both turns go through the production `sendTextMessageToServer`, so the
 * placeholder adoption, the per-chunk guard, the unwind, and the apply block are all the
 * real production paths. The evidence the fix is judged on is the transcript/UI the user
 * would see: B's reply must be finalized in its own streamed bubble (exactly one bubble
 * carrying the full reply, none left carrying only the partial text) and B's published
 * overlay text must survive A's unwind.
 */
@RunWith(AndroidJUnit4::class)
class H3StaleStreamUnwindInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

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
                // L5: the history owner follows the restored selection — persistSettings() below
                // saves the (owner, messages) pair, so leaving the owner on the probe persona's
                // name would file this test's transcript under the probe key.
                service.messagesOwnerName = personaNameBefore
                // `sendTextMessageToServer` persists settings, so removing the stub entry from
                // memory is not enough — write the original list back to disk too.
                service._ollamaBaseUrls.value = ollamaBasesBefore
                service.saveSettings()
                // H3 bookkeeping is service-global process state: leave none of it behind.
                service._streamingText.value = null
                service.streamingTextGeneration = null
                service.streamedPlaceholderIndex = null
                service.streamedPlaceholderGeneration = null
            }
        }
        runCatching { service.settingsManager.deletePersonaHistory(PROBE_PERSONA.name) }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    // ---- the repro ------------------------------------------------------------------------

    /**
     * The H3 verdict: after a superseded turn's stale chunk unwinds, the newer turn must
     * still own its streamed bubble — one assistant bubble carrying the full reply, and
     * none stranded carrying only the partial text.
     */
    @Test
    fun staleUnwindDoesNotStrandOrDuplicateTheNewerTurnsBubble() {
        SteppedNdjsonServer().use { server ->
            activateWithFreshTranscript(server)

            // ---- Turn A: streams its first chunk, then parks mid-stream. ----
            startTurn(PROMPT_A)
            val stale = server.connection(0)
            assertTrue("turn A never reached the stub", stale.requestArrived.await(20, TimeUnit.SECONDS))
            stale.sendChunk(CHUNK_A1)
            awaitService(20_000, "turn A's first chunk never reached its placeholder") {
                service.messages.value.any { it.role == "assistant" && it.text == PARTIAL_A }
            }
            awaitService(20_000, "turn A never published its streaming text") {
                service.streamingText.value == PARTIAL_A
            }

            // ---- Turn B: supersedes A (new chat generation) and streams its own chunk. ----
            startTurn(PROMPT_B)
            val newer = server.connection(1)
            assertTrue("turn B never reached the stub", newer.requestArrived.await(20, TimeUnit.SECONDS))
            newer.sendChunk(CHUNK_B1)
            awaitService(20_000, "turn B never adopted its own placeholder") {
                service.messages.value.any { it.role == "assistant" && it.text == PARTIAL_B }
            }
            awaitService(20_000, "turn B never published its streaming text") {
                service.streamingText.value == PARTIAL_B
            }

            // ---- A's next chunk arrives now: A is stale, so its per-chunk guard cancels it
            // and throws StreamCancelledByUserException, i.e. A unwinds while B is parked
            // mid-stream with a live placeholder + published text. ----
            stale.sendChunk(CHUNK_A2)
            assertTrue(
                "turn A's stale chunk never cancelled it (its connection stayed open, so the " +
                    "per-chunk generation guard never fired and this test proved nothing)",
                stale.clientDisconnected.await(30, TimeUnit.SECONDS)
            )

            // Evidence #1 (the `_streamingText` half): B is parked, so nothing can republish
            // its overlay. A stale unwind that cleared the published text unconditionally
            // leaves it null for this whole window.
            awaitStreamingTextHolds(2_000, PARTIAL_B)
            assertEquals(
                "H3: the stale turn's unwind forced the newer turn out of THINKING",
                AssistantState.THINKING,
                service.assistantState.value
            )

            // ---- B finishes and finalizes its own placeholder. ----
            newer.sendChunk(CHUNK_B2)
            newer.finishStream()
            awaitService(30_000, "turn B never finished") {
                service.assistantState.value == AssistantState.IDLE
            }

            // Evidence #2 (the placeholder-index half): pre-fix, A's unwind nulled the index
            // B had since adopted, so B's apply appended a SECOND bubble and B's placeholder
            // was left behind still carrying the partial text.
            assertEquals(
                "H3: the stale turn's unwind cleared the newer turn's placeholder index — its " +
                    "reply was appended as a second bubble and its streamed placeholder was " +
                    "stranded with the partial text (assistant bubbles: " +
                    service.messages.value.filter { it.role == "assistant" }.map { it.text } + ")",
                0,
                service.messages.value.count { it.text == PARTIAL_B }
            )
            assertEquals(
                "H3: turn B's reply must be finalized in its own streamed bubble",
                1,
                service.messages.value.count { it.text == FULL_B }
            )
            // Composition check: A's partial (deliberately kept as history, D2) + B's single
            // finalized bubble. The duplicated-bubble outcome leaves three.
            assertEquals(
                "H3: expected one assistant bubble per turn's content (A's kept partial + B's " +
                    "finalized reply), got " +
                    service.messages.value.filter { it.role == "assistant" }.map { it.text },
                2,
                service.messages.value.count { it.role == "assistant" }
            )
            assertNull(
                "H3: the streamed overlay text must be cleared once the newer turn finished",
                service.streamingText.value
            )
        }
    }

    /**
     * Control: with nothing superseding it, the same streamed turn must still finalize its
     * placeholder IN PLACE — no stranded partial bubble, no second append. Guards the fix
     * against "solving" H3 by never adopting a placeholder at all.
     */
    @Test
    fun aLoneStreamedTurnStillFinalizesItsOwnPlaceholderInPlace() {
        SteppedNdjsonServer().use { server ->
            activateWithFreshTranscript(server)

            startTurn(PROMPT_B)
            val only = server.connection(0)
            assertTrue("the turn never reached the stub", only.requestArrived.await(20, TimeUnit.SECONDS))
            only.sendChunk(CHUNK_B1)
            awaitService(20_000, "the turn never adopted its placeholder") {
                service.messages.value.any { it.role == "assistant" && it.text == PARTIAL_B }
            }
            awaitStreamingTextHolds(500, PARTIAL_B)

            only.sendChunk(CHUNK_B2)
            only.finishStream()
            awaitService(30_000, "the turn never finished") {
                service.assistantState.value == AssistantState.IDLE
            }

            assertEquals(
                "the streamed reply was not finalized in place (assistant bubbles: " +
                    service.messages.value.filter { it.role == "assistant" }.map { it.text } + ")",
                1,
                service.messages.value.count { it.text == FULL_B }
            )
            assertEquals(
                "the streamed placeholder was stranded with the partial text",
                0,
                service.messages.value.count { it.text == PARTIAL_B }
            )
            assertNull(service.streamingText.value)
        }
    }

    // ---- harness --------------------------------------------------------------------------

    /**
     * Points [PROBE_PERSONA] at [server] (Direct-Ollama, no gateway failover, no TTS, no
     * search/RAG) and starts from an empty transcript so the rendered bubble list is entirely
     * this test's doing.
     */
    private fun activateWithFreshTranscript(server: SteppedNdjsonServer) {
        mainSync {
            service._ollamaBaseUrls.value = listOf(ServerConfig(PROBE_SERVER_NAME, server.url))
            service.currentPersonaName = PROBE_PERSONA.name
            // L5: the history owner moves with the selection, exactly as switchPersona does —
            // this test's probe turns are saved under the probe persona's key (which tearDown
            // deletes), not under whichever persona the service bound with.
            service.messagesOwnerName = PROBE_PERSONA.name
            service._messages.value = emptyList()
            service._streamingText.value = null
            service.streamedPlaceholderIndex = null
            service.streamedPlaceholderGeneration = null
            service.streamingTextGeneration = null
        }
    }

    /** Starts a turn through the production entry point the composer's send button uses. */
    private fun startTurn(text: String) {
        mainSync { service.sendTextMessageToServer(text, PROBE_PERSONA) }
    }

    private fun awaitService(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("timed out after ${timeoutMs}ms waiting until $what", condition())
    }

    /**
     * Fails if the published streaming text stops being [expected] at any point during
     * [windowMs]. Sampling the window (rather than checking once) is what catches a
     * clear-then-republish: while the newer turn is parked mid-stream, the stale turn's unwind
     * is the only writer that can touch the service-global overlay at all.
     */
    private fun awaitStreamingTextHolds(windowMs: Long, expected: String) {
        val deadline = SystemClock.uptimeMillis() + windowMs
        while (SystemClock.uptimeMillis() < deadline) {
            assertEquals(
                "the published streaming text was blanked/changed while the turn that owns it " +
                    "was still streaming — only that turn's own terminal path may clear it " +
                    "(streamedPlaceholderIndex=${service.streamedPlaceholderIndex})",
                expected,
                service.streamingText.value
            )
            Thread.sleep(20)
        }
    }

    private fun mainSync(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private companion object {
        /** Must match the `[Name] model` tag on [PROBE_PERSONA] — that is how the flow resolves it. */
        const val PROBE_SERVER_NAME = "H3StaleStreamUnwindServer"

        /** Not an `isKnownThinkingModel` match, so `think` is never requested. */
        const val PROBE_MODEL = "h3-stale-stream-model"

        /** Avoids the word "news", so `isNewsRequest` cannot fire a search on the test device. */
        const val PROMPT_A = "explain rate limiting in one sentence"
        const val PROMPT_B = "explain how rate limiting differs from throttling"

        /** Turn A's first chunk — the one that adopts the trailing placeholder and publishes text. */
        const val CHUNK_A1 = "A-ONE "
        const val PARTIAL_A = "A-ONE "

        /**
         * Turn A's SECOND chunk. By the time the test sends it, turn B owns the placeholder, so
         * A's per-chunk guard rejects it and A unwinds — this is the stale unwind under test.
         */
        const val CHUNK_A2 = "A-TWO"

        const val CHUNK_B1 = "B-ONE "
        const val PARTIAL_B = "B-ONE "
        const val CHUNK_B2 = "B-TWO"
        const val FULL_B = "B-ONE B-TWO"

        /**
         * The persona under test: direct (non-cloud), pointed at [PROBE_SERVER_NAME] through the
         * `[Name] model` tag. `voiceMode = NONE` makes `playResponse` take its documented no-play
         * bail straight to IDLE, so no TTS engine is ever touched.
         */
        val PROBE_PERSONA = Persona(
            name = "H3StaleStreamUnwindPersona",
            themeColor = Color(0xFF4ADE80),
            model = "[$PROBE_SERVER_NAME] $PROBE_MODEL",
            systemPrompt = "Instrumentation probe persona",
            isCloud = false,
            backendUrl = "",
            allowGatewayFailover = false,
            voiceMode = VoiceMode.NONE,
            maxTokens = 256,
            numCtx = 8192,
            enableThinking = false,
            webSearchEnabled = false,
            ragEnabled = false
        )
    }
}

/**
 * Loopback Direct-Ollama stub that serves MANY connections and lets the test drive each one
 * step by step.
 *
 * It answers `POST /api/chat` with an EOF-delimited (`Connection: close`, no Content-Length)
 * NDJSON body — the shape the production client streams — and then keeps the response open
 * until the test asks for the next chunk. That makes "turn A's next chunk arrives only after
 * turn B owns a placeholder" something the test SCHEDULES rather than something it hopes to
 * catch, which is what turns a timing race into deterministic evidence.
 */
private class SteppedNdjsonServer : AutoCloseable {

    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val connections = CopyOnWriteArrayList<SteppedConnection>()

    /** Base URL accepted by the Ollama client (`http://host:port`). */
    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true, name = "h3-stepped-ndjson-accept") {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    // Socket closed by close() (or a failed accept): the loop exits on the
                    // isClosed check. A test stub has nothing to report here.
                    break
                }
                val connection = SteppedConnection(client)
                connections.add(connection)
                // One thread per connection: each response stays open (blocked reading for the
                // client's hangup) while the accept loop keeps listening for the next turn.
                thread(isDaemon = true, name = "h3-stepped-ndjson-conn") { connection.serve() }
            }
        }
    }

    /** The [index]-th (0-based) accepted connection, waiting up to [timeoutMs] for it. */
    fun connection(index: Int, timeoutMs: Long = 30_000): SteppedConnection {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            connections.getOrNull(index)?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError(
            "the stub never accepted connection #$index (it accepted ${connections.size})"
        )
    }

    override fun close() {
        connections.forEach { it.close() }
        runCatching { socket.close() }
    }
}

/**
 * One accepted connection. The test advances the response with [sendChunk]/[finishStream] and
 * learns that the client stopped reading from [clientDisconnected] — which is exactly how a
 * cancelled or a finished turn manifests on the wire.
 */
private class SteppedConnection(private val client: Socket) {

    val requestArrived = CountDownLatch(1)
    val clientDisconnected = CountDownLatch(1)

    private val responseReady = CountDownLatch(1)

    @Volatile
    private var output: OutputStream? = null

    fun serve() {
        try {
            val input = BufferedInputStream(client.getInputStream())
            drainRequest(input)
            val out = client.getOutputStream()
            output = out
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/x-ndjson\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            out.flush()
            responseReady.countDown()
            requestArrived.countDown()
            // Block until the client hangs up. `read` returns -1 (or throws) once it closes the
            // connection, so this also serializes the test's "A's chunk was read" evidence.
            while (input.read() != -1) {
                // Body bytes are written by the test thread, never by the client: nothing to do.
            }
        } catch (_: Exception) {
            // A closed/reset socket is the normal end of a cancelled or finished turn.
        } finally {
            clientDisconnected.countDown()
            responseReady.countDown()
            requestArrived.countDown()
            runCatching { client.close() }
        }
    }

    /** Streams one content chunk, exactly like a real partial NDJSON line. */
    fun sendChunk(text: String) = writeLine(
        """{"model":"h3-stale-stream-model","created_at":"2026-01-01T00:00:00Z",""" +
            """"message":{"role":"assistant","content":"$text"},"done":false}"""
    )

    /** Ends the NDJSON stream the way the real server does (`done:true`). */
    fun finishStream() = writeLine(
        """{"model":"h3-stale-stream-model","created_at":"2026-01-01T00:00:01Z",""" +
            """"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}"""
    )

    fun close() {
        runCatching { client.close() }
    }

    private fun writeLine(line: String) {
        if (!responseReady.await(20, TimeUnit.SECONDS)) {
            throw AssertionError("the stub's response headers were never written")
        }
        val out = output ?: throw AssertionError("the stub's response stream is not open")
        out.write((line + "\n").toByteArray())
        out.flush()
    }

    private fun drainRequest(input: InputStream) {
        val headers = StringBuilder()
        while (true) {
            val line = readAsciiLine(input) ?: return
            if (line.isEmpty()) break
            headers.append(line).append('\n')
        }
        val contentLength = headers.toString().lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        // Drain the request body: leaving it unread makes the client see a reset connection
        // instead of a response.
        var remaining = contentLength
        val buffer = ByteArray(8_192)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            remaining -= read
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (sb.isEmpty()) null else sb.toString()
            if (byte == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(byte.toChar())
        }
    }
}
