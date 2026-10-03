package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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

/**
 * Per-message timestamps — the on-device half.
 *
 * The JVM side ([MessageTimestampTest]) pins the pure formatting, the prefix and the
 * eligibility gate. This class proves the two things only a real app can show:
 *
 *  1. **The real request body.** A stub server on the device captures the bytes the app
 *     actually POSTs: a Direct-Ollama persona's history must arrive with `[Oct 1, 2026 14:32]`
 *     prefixes, a cloud persona's must not, and the prefixes must sit INSIDE the budget
 *     arithmetic rather than being bolted on after it.
 *  2. **The real UI.** The bubbles are rendered through the production `ChatList`, for a local
 *     AND a cloud persona — the stamp is a property of the stored message, so it cannot be
 *     gated by anything backend-side.
 *
 * Everything else is production code: `sendTextMessageToServer` is the send button's own entry
 * point and `ChatList` is the list the screen renders.
 */
@RunWith(AndroidJUnit4::class)
class MessageTimestampInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private var ollamaBasesBefore: List<ServerConfig> = emptyList()
    private var customCloudApisBefore: List<CloudApiSetting> = emptyList()
    private var personasBefore: List<Persona> = emptyList()
    private var currentPersonaBefore: String? = null
    private var messagesOwnerBefore: String? = null
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
        // Join the service's startup history load before this test touches _messages:
        // onCreate -> loadSettings() -> switchPersona() publishes _messages asynchronously,
        // and a test that assigns _messages while that is still in flight has it
        // overwritten underneath (a replay then finds no message to index and publishes
        // nothing). No-op when no history operation is in flight.
        runBlocking { service.awaitPersonaHistoryIo() }
        ollamaBasesBefore = service._ollamaBaseUrls.value
        customCloudApisBefore = service._customCloudApis.value
        personasBefore = service.personas.value
        currentPersonaBefore = service.currentPersonaName
        messagesOwnerBefore = service.messagesOwnerName
        messagesBefore = service._messages.value
        probePersonaNames.forEach { service.settingsManager.deletePersonaHistory(it) }
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching { instrumentation.runOnMainSync { service.stopEverything() } }
        runCatching {
            instrumentation.runOnMainSync {
                service._messages.value = messagesBefore
                service.currentPersonaName = currentPersonaBefore
                service.messagesOwnerName = messagesOwnerBefore
                service._ollamaBaseUrls.value = ollamaBasesBefore
                service._customCloudApis.value = customCloudApisBefore
                service.personas.value.indices.reversed().forEach { service.removePersona(it) }
                personasBefore.forEach { service.addPersona(it) }
                service.saveSettings()
            }
        }
        probePersonaNames.forEach { runCatching { service.settingsManager.deletePersonaHistory(it) } }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    // ------------------------------------------------------------------ fixtures

    /** A direct-Ollama persona pointed at the stub, with everything off that could phone home. */
    private fun directPersona(name: String, numCtx: Int = LARGE_NUM_CTX) = Persona(
        name = name,
        themeColor = PROBE_COLOR,
        model = "[$PROBE_SERVER_NAME] $PROBE_MODEL",
        systemPrompt = LOCAL_SYSTEM_PROMPT,
        isCloud = false,
        backendUrl = "",
        allowGatewayFailover = false,
        voiceMode = VoiceMode.NONE,
        enableThinking = false,
        webSearchEnabled = false,
        ragEnabled = false,
        maxTokens = RESERVED_OUTPUT_TOKENS,
        numCtx = numCtx
    )

    /** A cloud persona pointed at the stub, icon "C" (OpenAI-shaped request and response). */
    private fun cloudPersona(name: String) = Persona(
        name = name,
        themeColor = PROBE_COLOR,
        model = "[$PROBE_CLOUD_PROVIDER] $PROBE_MODEL",
        systemPrompt = "You are a terse cloud assistant.",
        isCloud = true,
        providerIcon = PROBE_CLOUD_ICON,
        backendUrl = "",
        allowGatewayFailover = false,
        voiceMode = VoiceMode.NONE,
        enableThinking = false,
        webSearchEnabled = false,
        ragEnabled = false,
        maxTokens = RESERVED_OUTPUT_TOKENS,
        numCtx = LARGE_NUM_CTX
    )

    private fun registerProbeServer(stub: CapturingStubServer) = onMain {
        service._ollamaBaseUrls.value =
            ollamaBasesBefore.filterNot { it.name == PROBE_SERVER_NAME } +
                ServerConfig(name = PROBE_SERVER_NAME, url = stub.url)
    }

    private fun registerProbeCloudProvider(stub: CapturingStubServer) = onMain {
        service._customCloudApis.value =
            customCloudApisBefore.filterNot { it.name == PROBE_CLOUD_PROVIDER } +
                CloudApiSetting(
                    name = PROBE_CLOUD_PROVIDER,
                    baseUrl = stub.url,
                    icon = PROBE_CLOUD_ICON,
                    color = PROBE_COLOR
                )
    }

    private fun addAndActivate(persona: Persona, seeded: List<ChatMessage>) = onMain {
        service.addPersona(persona)
        service.currentPersonaName = persona.name
        // L5: the history owner moves with the selection, so this turn's writes land under the
        // probe persona's key (deleted in tearDown), not the real selected persona's.
        service.messagesOwnerName = persona.name
        service._messages.value = seeded
    }

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /** Exactly what the send button does. */
    private fun sendTurn(persona: Persona, text: String) = onMain {
        service.sendTextMessageToServer(text, persona)
    }

    /**
     * Waits for the turn to produce an answer the transcript did not already contain.
     *
     * Counting rather than merely "any assistant message" matters here: the seeded history
     * deliberately contains one, and a predicate that matches it returns instantly — which
     * would assert against the stub before the request had even been sent.
     */
    private fun awaitNewAnswer(before: Int, timeoutMs: Long = 30_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        fun newAnswers() =
            service.messages.value.count { !it.isError && it.role == "assistant" } > before
        while (SystemClock.uptimeMillis() < deadline) {
            if (newAnswers()) return
            Thread.sleep(25)
        }
        assertTrue(
            "the turn never produced an answer (state=${service.assistantState.value}, " +
                "messages=${service.messages.value.map { it.role + ":" + it.text.take(40) }})",
            newAnswers()
        )
    }

    private fun assistantCount(): Int =
        service.messages.value.count { !it.isError && it.role == "assistant" }

    /** `true` when the semantics tree holds a node whose text is exactly [text]. */
    private fun hasExactText(text: String): Boolean =
        composeRule.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()

    private fun awaitRendered(text: String, failure: String) {
        composeRule.waitUntil(20_000) { hasExactText(text) }
        assertTrue(
            "$failure (transcript=${service.messages.value.map { it.text.take(32) }})",
            hasExactText(text)
        )
    }

    /** Renders the production chat list from live service state. */
    private fun renderChatList() {
        composeRule.setContent {
            val state by service.assistantState.collectAsState()
            val messages by service.messages.collectAsState()
            val streaming by service.streamingText.collectAsState()
            val fraction by service.ttsPlaybackFraction.collectAsState()
            val timestamps by service.ttsWordTimestamps.collectAsState()
            val duration by service.voiceDuration.collectAsState()
            MaterialTheme {
                ChatList(
                    messages = messages,
                    listState = rememberLazyListState(),
                    state = state,
                    personaColor = PROBE_COLOR,
                    revealedChars = Int.MAX_VALUE,
                    streamingText = streaming,
                    ttsPlaybackFraction = fraction,
                    ttsWordTimestamps = timestamps,
                    voiceDuration = duration,
                    onEditMessage = { _, _ -> },
                    onDeleteMessage = { },
                    onReplayAudio = { }
                )
            }
        }
    }

    private fun daysAgo(days: Long): Long =
        System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L

    /** The date+time stamp the UI is expected to render, computed independently of the feature. */
    private fun expectedDisplayStamp(epochMillis: Long): String =
        SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(epochMillis))

    private fun expectedTimeOnlyStamp(epochMillis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))

    private val STAMPED_PREFIX = Regex("""^\[[A-Z][a-z]{2} \d{1,2}, \d{4} \d{2}:\d{2}\] """)

    /**
     * The HISTORICAL message contents from a captured request body: index 0 is the system
     * prompt and the last entry is the current user turn, neither of which carries a prefix.
     */
    private fun historyContents(body: String): List<String> {
        val messages = JSONObject(body).getJSONArray("messages")
        return (1 until messages.length() - 1).map { messages.getJSONObject(it).getString("content") }
    }

    /** A seeded history pair, old enough to prove a date is shown rather than a bare time. */
    private fun oldTurn(whenMillis: Long, index: Int) = listOf(
        ChatMessage("user", "old question $index", timestamp = whenMillis),
        ChatMessage("assistant", "old answer $index", timestamp = whenMillis)
    )

    private companion object {
        const val PROBE_SERVER_NAME = "TimestampProbeServer"
        const val PROBE_CLOUD_PROVIDER = "TimestampProbeCloud"
        const val PROBE_CLOUD_ICON = "C"
        const val PROBE_MODEL = "probe-model"
        val PROBE_COLOR = Color(0xFF4ADE80)

        const val LOCAL_SYSTEM_PROMPT = "You are a terse local assistant."
        const val RESERVED_OUTPUT_TOKENS = 1024
        const val LARGE_NUM_CTX = 32_000
        const val SMALL_NUM_CTX = 2048
        const val CURRENT_MESSAGE = "and what did we land on?"
        const val STUB_ANSWER = "stubbed reply"
        const val SEEDED_TURNS = 120
        const val BUDGET_SLACK = 24

        val probePersonaNames = listOf("ProbeStampLocal", "ProbeStampCloud", "ProbeStampBudget")

        /** Mirrors production `estimateTokens` (chars / 4, floored, minimum 1). */
        fun estimateTokens(text: String): Int = (text.length / 4.0).toInt().coerceAtLeast(1)
    }

    /**
     * Stub model server that RECORDS what it was sent, in the shape the production client
     * expects: the real NDJSON streaming contract for Ollama's `/api/chat`, and an
     * OpenAI-shaped JSON reply for the cloud provider's `/chat/completions`.
     *
     * This is the point of the class: the assertions read the actual bytes on the wire, not a
     * re-implementation of what the app would have sent.
     */
    private class CapturingStubServer : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val worker: Thread

        val url: String get() = "http://127.0.0.1:${socket.localPort}"
        val requestCount = AtomicInteger()
        val lastBody = AtomicReference<String?>(null)

        init {
            worker = thread(isDaemon = true, name = "stub-timestamps") {
                while (!socket.isClosed) {
                    try {
                        socket.accept().use { handle(it) }
                    } catch (_: Exception) {
                        // Socket closed by close() — the loop exits on the isClosed check.
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
            // The body is the whole point: read it fully, then remember it.
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(body, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            requestCount.incrementAndGet()
            lastBody.set(String(body, 0, maxOf(read, 0), Charsets.UTF_8))

            val output = connection.getOutputStream()
            val payload =
                if (headerText.contains("/api/chat")) ollamaStreamingBody() else cloudJsonBody()
            output.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n").toByteArray()
            )
            output.write(payload)
            output.flush()
        }

        private fun ollamaStreamingBody(): ByteArray = (
            "{\"model\":\"$PROBE_MODEL\",\"created_at\":\"2026-01-01T00:00:00Z\"," +
                "\"message\":{\"role\":\"assistant\",\"content\":\"$STUB_ANSWER\"},\"done\":false}\n" +
                "{\"model\":\"$PROBE_MODEL\",\"created_at\":\"2026-01-01T00:00:01Z\"," +
                "\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true," +
                "\"done_reason\":\"stop\",\"prompt_eval_count\":9,\"eval_count\":4}\n"
            ).toByteArray()

        private fun cloudJsonBody(): ByteArray = (
            "{\"id\":\"stub\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\"," +
                "\"content\":\"$STUB_ANSWER\"},\"finish_reason\":\"stop\"}]," +
                "\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4}}"
            ).toByteArray()

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

    // ---- the real request body -----------------------------------------------------------

    @Test
    fun directOllamaHistoryCarriesTimestampPrefixes() {
        val old = daysAgo(3)
        val persona = directPersona("ProbeStampLocal")

        CapturingStubServer().use { stub ->
            registerProbeServer(stub)
            addAndActivate(persona, oldTurn(old, 1))

            val answersBefore = assistantCount()
            sendTurn(persona, CURRENT_MESSAGE)
            awaitNewAnswer(answersBefore)

            val body = stub.lastBody.get()
            assertTrue("no request reached the stub", body != null)
            val history = historyContents(body!!)
            assertTrue("the seeded turn should have been sent as history", history.isNotEmpty())
            history.forEach { content ->
                assertTrue(
                    "every historical message must carry a full [date time] prefix, got: $content",
                    STAMPED_PREFIX.containsMatchIn(content)
                )
                assertTrue(
                    "the prefix must be the message's OWN time (3 days old), got: $content",
                    content.contains(expectedDisplayStamp(old))
                )
            }
            // The current turn is NOT history and must stay clean.
            val messages = JSONObject(body).getJSONArray("messages")
            assertEquals(
                "the current user turn must not be prefixed",
                CURRENT_MESSAGE,
                messages.getJSONObject(messages.length() - 1).getString("content")
            )
        }
    }

    @Test
    fun cloudHistoryCarriesNoTimestampPrefixes() {
        val old = daysAgo(3)
        val persona = cloudPersona("ProbeStampCloud")

        CapturingStubServer().use { stub ->
            registerProbeCloudProvider(stub)
            addAndActivate(persona, oldTurn(old, 1))

            val answersBefore = assistantCount()
            sendTurn(persona, CURRENT_MESSAGE)
            awaitNewAnswer(answersBefore)

            val body = stub.lastBody.get()
            assertTrue("no cloud request reached the stub", body != null)
            val history = historyContents(body!!)
            assertTrue(
                "the seeded turn should still have been sent to the cloud provider",
                history.isNotEmpty()
            )
            history.forEach { content ->
                assertFalse(
                    "a cloud persona's history must NOT be stamped (it is a paid request): $content",
                    STAMPED_PREFIX.containsMatchIn(content)
                )
            }
            // Verbatim, not merely unprefixed: the same turns, byte for byte.
            assertEquals(
                "the cloud request must carry the seeded turns unchanged",
                listOf("old question 1", "old answer 1"),
                history
            )
        }
    }

    @Test
    fun theTimestampPrefixesAreCountedByTheHistoryBudget() {
        // The placement claim: the prefixes are added BEFORE budgetedHistory, so they eat into
        // the same allowance as the words they prefix.
        //
        // Sized deliberately: the history is many SHORT messages, so the ~21-character prefix
        // is roughly half of each stamped message. That makes "counted" and "bolted on after"
        // differ by dozens of messages — far more than any slack in recomputing the budget
        // below — instead of by a rounding error that could flip either way.
        val old = daysAgo(3)
        val persona = directPersona("ProbeStampBudget", numCtx = SMALL_NUM_CTX)
        val seeded = (1..SEEDED_TURNS).map { i ->
            ChatMessage(
                role = if (i % 2 == 0) "assistant" else "user",
                text = "turn $i " + "x".repeat(30),
                timestamp = old
            )
        }

        CapturingStubServer().use { stub ->
            registerProbeServer(stub)
            addAndActivate(persona, seeded)

            val answersBefore = assistantCount()
            sendTurn(persona, CURRENT_MESSAGE)
            awaitNewAnswer(answersBefore)

            val sent = historyContents(stub.lastBody.get()!!)

            // The system prompt is "Current date and time: ..." + the persona prompt and the
            // current message is the user turn; neither is stamped. Slack covers the date
            // string's varying length.
            // The stamped Direct-Ollama prompt also carries MODEL_STAMP_INSTRUCTION as a
            // suffix, so it is part of the fixed cost this budget subtracts — pulled in from
            // the production constant, not pasted as a literal.
            val budget = SMALL_NUM_CTX - RESERVED_OUTPUT_TOKENS -
                estimateTokens(LOCAL_SYSTEM_PROMPT) - estimateTokens(MODEL_STAMP_INSTRUCTION) -
                estimateTokens(CURRENT_MESSAGE) + BUDGET_SLACK

            // budgetedHistory's own rule, recomputed: newest-first, stop at the first that
            // does not fit, never skip past it.
            fun fitCount(withPrefix: Boolean): Int {
                var used = 0
                var count = 0
                for (i in seeded.indices.reversed()) {
                    val text =
                        if (withPrefix) seeded[i].historyTextWithTimestamp() else seeded[i].text
                    val tokens = estimateTokens(text)
                    if (used + tokens > budget) break
                    used += tokens
                    count++
                }
                return count
            }

            val unstampedFit = fitCount(withPrefix = false)
            val stampedFit = fitCount(withPrefix = true)
            assertTrue(
                "precondition: the prefixes must change what fits, or this proves nothing " +
                    "(unstamped=$unstampedFit stamped=$stampedFit budget=$budget)",
                stampedFit < unstampedFit
            )
            assertTrue(
                "the request must carry FEWER turns than it would if the prefixes were added " +
                    "after budgeting (sent=${sent.size}, unprefixed would fit $unstampedFit)",
                sent.size < unstampedFit
            )
            assertTrue(
                "and it should match what fits WITH the prefixes counted (sent=${sent.size}, " +
                    "stamped fits $stampedFit)",
                Math.abs(sent.size - stampedFit) <= 3
            )
            assertTrue(
                "everything that did fit must still be stamped",
                sent.all { STAMPED_PREFIX.containsMatchIn(it) }
            )
            assertTrue(
                "and the trimming must have happened at all",
                sent.size < seeded.size
            )
        }
    }

    // ---- the real UI ---------------------------------------------------------------------

    @Test
    fun bubblesShowTimeOnlyForTodayAndDateOtherwise() {
        val old = daysAgo(3)
        val today = System.currentTimeMillis()
        val persona = directPersona("ProbeStampLocal")

        addAndActivate(
            persona,
            listOf(
                ChatMessage("user", "old question", timestamp = old),
                ChatMessage("assistant", STUB_ANSWER, timestamp = today)
            )
        )
        renderChatList()

        awaitRendered(
            expectedDisplayStamp(old),
            "a message from a previous day must render its date AND time"
        )
        awaitRendered(
            expectedTimeOnlyStamp(today),
            "a message from today must render as a bare time"
        )
    }

    @Test
    fun cloudPersonaTranscriptsAreStampedAndDisplayedToo() {
        // The stamp is a property of the stored message, not of the backend: the same list,
        // rendered for a CLOUD persona's real turn, must show both stamp forms.
        val old = daysAgo(3)
        val persona = cloudPersona("ProbeStampCloud")

        CapturingStubServer().use { stub ->
            registerProbeCloudProvider(stub)
            addAndActivate(persona, listOf(ChatMessage("user", "old question", timestamp = old)))

            val answersBefore = assistantCount()
            sendTurn(persona, CURRENT_MESSAGE)
            awaitNewAnswer(answersBefore)

            val transcript = service.messages.value
            assertTrue(
                "every message a cloud persona's turn produced must carry a timestamp: $transcript",
                transcript.all { it.timestamp > 0L }
            )
            renderChatList()

            awaitRendered(
                expectedDisplayStamp(old),
                "a cloud persona's older message must render its date and time"
            )
            val today = transcript.last { it.role == "assistant" }.timestamp
            awaitRendered(
                expectedTimeOnlyStamp(today),
                "a cloud persona's message from today must render as a bare time"
            )
        }
    }
}
