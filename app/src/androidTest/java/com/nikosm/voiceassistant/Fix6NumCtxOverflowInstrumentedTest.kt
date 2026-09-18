package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.File
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
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Fix #6 — a request whose FIXED parts already cannot fit the persona's Context Window Size
 * must be refused before it is sent, with a clear, actionable explanation.
 *
 * The bug: `performDirectOllamaChat` and `buildCloudRequest` both trim conversation history
 * against the persona's Context Window Size (`persona.numCtx`), but history trimming cannot
 * help when the parts that are never trimmed — the reserved output
 * (`persona.maxTokens.coerceAtLeast(1024)`), the system prompt and the current message (with
 * attachment text already inlined) — are ALONE bigger than the window. The trimmed history
 * floor was 0, so the request went out anyway and the server silently truncated whatever it
 * wanted (usually the system prompt or the attachment), leaving the user with a degraded or
 * nonsensical answer and no explanation.
 *
 * The fix: both builders now go through one shared helper,
 * `resolveHistoryBudget(contextWindow, reservedOutput, systemPrompt, modelText)`
 * (AssistantServiceChat.kt), which does the existing budget arithmetic in ONE place and, when
 * the resulting budget is negative, refuses to send — throwing the user-facing message
 *
 *   "Your message and system prompt (~X tokens) exceed this persona's Context Window Size (Y).
 *    Try a shorter message, a smaller attachment, or increase Context Window Size in this
 *    persona's settings."
 *
 * which the callers' existing error handling surfaces as an `isError` chat bubble. The helper
 * deliberately does not distinguish causes: a long system prompt, a long message, a large
 * attachment or any combination are the same problem with the same fix.
 *
 * This class is the on-device, end-to-end proof of that contract, deliberately covering the
 * overflow through BOTH routes the fix was written for, plus the second call site and the
 * non-overflow control:
 *
 *  1. [longSystemPromptIsRefusedBeforeSending] — a persona with `numCtx = 2048` whose system
 *     prompt alone blows the budget, short message (route a).
 *  2. [largeTextAttachmentIsRefusedBeforeSending] — short system prompt, but a large TEXT
 *     attachment inlined by `buildModelPrompt` blows it instead (route b). Preconditions assert
 *     the same persona fits without the attachment, so the attachment is provably the cause.
 *  3. [cloudRequestWithLongSystemPromptIsRefusedBeforeSending] — the same overflow through the
 *     OTHER edited builder (`buildCloudRequest`, cloud provider): refused before any HTTP call.
 *  4. [requestThatFitsIsStillSentNormally] — the inverse control: an under-budget request on the
 *     same 2048-token persona is still sent and answered. Without this, an over-eager check that
 *     refused everything would pass tests 1-3.
 *
 * Every refusal assertion checks four independent things: the specific message text (including
 * the reported token count, recomputed here from the same inputs), that the message names the
 * Context Window Size actually in effect, that NO request reached the server at all, and that
 * the bubble is actually rendered by the real [ChatList].
 *
 * Harness notes — why this is genuinely end-to-end rather than a mock of the wiring:
 *  - It binds the REAL [AssistantService] through the production `AssistantBinder`, exactly like
 *    MainActivity, and drives real turns through `sendTextMessageToServer` — the same entry point
 *    the send button calls. The budget math, the refusal and the error bubble are all production
 *    code paths.
 *  - It renders the REAL [ChatList] off the REAL service StateFlows through `collectAsState`, so
 *    the "clear error message" is asserted where the user actually reads it.
 *  - The only stand-in is the model server: this repo ships no Ollama instance, so a minimal
 *    in-process stub counts every request it receives. For the overflow routes the assertion is
 *    that the count stays at ZERO — i.e. the request was never built or sent — while for the
 *    control it answers with the real NDJSON streaming contract `performDirectOllamaChat` reads.
 *  - `voiceMode = NONE`, so `playResponse` takes its documented no-play bail and no TTS engine is
 *    ever touched; `webSearchEnabled`/`ragEnabled` are false and no prompt contains the word
 *    "news" (`isNewsRequest`), so no search/RAG network call can happen either.
 *
 * Everything this class mutates on the (long-lived) bound service is restored in tearDown:
 * `sendTextMessageToServer` calls `saveSettings()`, so the stub Ollama entry and the stub cloud
 * provider must be written back to SharedPreferences as well as to memory.
 */
@RunWith(AndroidJUnit4::class)
class Fix6NumCtxOverflowInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    // Pre-test baseline of everything this class mutates on a service that outlives it.
    private var ollamaBasesBefore: List<ServerConfig> = emptyList()
    private var customCloudApisBefore: List<CloudApiSetting> = emptyList()
    private var personaNameBefore: String? = null
    private var messagesBefore: List<ChatMessage> = emptyList()

    /** Attachment files are written into the app's own cacheDir; removed in tearDown. */
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
        ollamaBasesBefore = service._ollamaBaseUrls.value
        customCloudApisBefore = service._customCloudApis.value
        personaNameBefore = service.currentPersonaName
        messagesBefore = service._messages.value
        // The probe personas must start with no stored history, so nothing a previous run left
        // behind can influence the budget or show up in the rendered list.
        probePersonaNames.forEach { service.settingsManager.deletePersonaHistory(it) }
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
                // `sendTextMessageToServer` persists settings, so removing the stubs from memory
                // is not enough — write the original lists back to disk too.
                service._ollamaBaseUrls.value = ollamaBasesBefore
                service._customCloudApis.value = customCloudApisBefore
                service.saveSettings()
            }
        }
        probePersonaNames.forEach { name ->
            runCatching { service.settingsManager.deletePersonaHistory(name) }
        }
        runCatching { service._streamingText.value = null }
        scratchFiles.forEach { runCatching { it.delete() } }
        scratchFiles.clear()
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }
    /**
     * Renders the REAL production [ChatList] off the REAL service flows — the same widget the
     * chat screen uses to show an `isError` bubble. `revealedChars = Int.MAX_VALUE` mirrors the
     * non-animating state (no typewriter in progress), so every bubble's full text is on screen.
     */
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

    /**
     * Semantics presence check that deliberately does NOT wait for Compose idleness (the app's
     * screens run infinite animations, so idling-based queries are avoided); this polls the
     * semantics tree directly, which is also what `composeRule.waitUntil` drives frame-by-frame.
     */
    private fun hasTextSubstring(substring: String): Boolean = try {
        composeRule.onAllNodesWithText(substring, substring = true).fetchSemanticsNodes().isNotEmpty()
    } catch (_: Throwable) {
        // No semantics root yet (before the very first frame) — treat as "not there".
        false
    }

    /** Asserts the given text is actually RENDERED (not merely present in service state). */
    private fun awaitRenderedText(substring: String, failure: String) {
        composeRule.waitUntil(20_000) { hasTextSubstring(substring) }
        assertTrue(
            "$failure (visited the semantics tree for \"$substring\"; " +
                "rendered messages=${service.messages.value.map { it.role + ":" + it.text.take(48) }})",
            hasTextSubstring(substring)
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
                "messages=${service.messages.value.map { it.role + ":" + it.text.take(48) }})",
            condition()
        )
    }

    // ---- budget arithmetic, recomputed independently of the production helper ----------------

    /** Mirrors the production `estimateTokens` approximation (chars / 4, floored, min 1). */
    private fun estimateTokens(text: String): Int = (text.length / 4.0).toInt().coerceAtLeast(1)

    /**
     * The system prompt as both budgeted builders assemble it: the request-time date/time
     * prefix followed by the persona's own prompt (`AssistantServiceChat.kt:1161` / `:1399`).
     * Rebuilt here from the same pattern, so the only possible drift is a date-string length
     * change between the turn and this assertion — hence [DATE_PREFIX_SLACK_TOKENS].
     */
    private fun expectedSystemPrompt(persona: Persona): String =
        "Current date and time: " +
            SimpleDateFormat("EEEE, MMMM d, yyyy, HH:mm", Locale.getDefault()).format(Date()) +
            "\n\n${persona.systemPrompt}"

    /** The whole fixed (never-trimmed) prompt the pre-flight check measures against the window. */
    private fun expectedFixedTokens(persona: Persona, modelText: String): Int =
        RESERVED_OUTPUT_TOKENS + estimateTokens(expectedSystemPrompt(persona)) + estimateTokens(modelText)

    /**
     * `buildModelPrompt` with one attachment: the question, then the "--- Attached file: X ---"
     * section carrying the file's text verbatim (`AssistantServiceChat.kt:113-133`).
     */
    private fun expectedAttachmentModelText(text: String, fileName: String, content: String): String =
        "$text\n\n--- Attached file: $fileName ---\n$content\n--- End of $fileName ---\n\n"

    /**
     * A real text attachment in the app's own cacheDir, referenced as a `file://` Uri — the same
     * shape the app's own picker-copy path produces (`contentResolver.openInputStream` handles
     * it, and the `.txt` name satisfies `isPlainTextAttachment`).
     */
    private fun createTextAttachment(fileName: String, content: String): Uri {
        val file = File(context.cacheDir, fileName)
        file.writeText(content)
        scratchFiles += file
        return Uri.fromFile(file)
    }
    // ---- driving a turn through the production entry point -----------------------------------

    /** Registers [stub] under the exact name the probe persona's `[Name] model` tag points at. */
    private fun registerProbeServer(stub: StubProbeServer) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service._ollamaBaseUrls.value =
                ollamaBasesBefore.filterNot { it.name == PROBE_SERVER_NAME } +
                    ServerConfig(name = PROBE_SERVER_NAME, url = stub.url)
        }
    }

    /** Registers the stub as a cloud provider — icon "C" ⇒ no API key required. */
    private fun registerProbeCloudProvider(stub: StubProbeServer) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service._customCloudApis.value =
                customCloudApisBefore.filterNot { it.name == PROBE_CLOUD_PROVIDER } +
                    CloudApiSetting(
                        name = PROBE_CLOUD_PROVIDER,
                        baseUrl = stub.url,
                        icon = PROBE_CLOUD_ICON,
                        color = PROBE_COLOR
                    )
        }
    }

    private fun activate(persona: Persona) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.currentPersonaName = persona.name
            // L5: the history owner moves with the selection, exactly as switchPersona does —
            // this test's probe turns are saved under the probe persona's key (which tearDown
            // deletes), not under whichever persona the service bound with.
            service.messagesOwnerName = persona.name
            service._messages.value = emptyList()
        }
    }

    /** Exactly what the send button does — the production text entry point, no shortcuts. */
    private fun sendTurn(persona: Persona, text: String, attachments: List<Uri> = emptyList()) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.sendTextMessageToServer(text, persona, attachments)
        }
    }

    /**
     * The full Fix #6 contract for one refused turn: the actionable message (with its token
     * count recomputed here from the same inputs), the Context Window Size actually in effect, no
     * generic failure, nothing at all sent to the server, and no answer produced. Returns the
     * error bubble so the caller can also assert it on screen.
     */
    private fun assertRefusedBeforeSending(
        route: String,
        persona: Persona,
        stub: StubProbeServer,
        expectedFixedTokens: Int,
        expectedUserMessageText: String
    ): ChatMessage {
        awaitService(20_000, "[$route] no error bubble ever appeared — the turn did not refuse") {
            service.messages.value.any { it.isError }
        }
        awaitService(20_000, "[$route] the refused turn never returned to IDLE") {
            service.assistantState.value == AssistantState.IDLE
        }
        val messages = service.messages.value
        val error = messages.last { it.isError }

        assertTrue(
            "[$route] expected the Fix #6 overflow explanation, got: ${error.text}",
            error.text.startsWith("Error: Your message and system prompt (~")
        )
        assertTrue(
            "[$route] the message must name the persona's Context Window Size (${persona.numCtx}), " +
                "got: ${error.text}",
            error.text.contains("exceed this persona's Context Window Size (${persona.numCtx})")
        )
        assertTrue(
            "[$route] the message must offer the concrete remedies, got: ${error.text}",
            error.text.contains(
                "Try a shorter message, a smaller attachment, or increase Context Window Size " +
                    "in this persona's settings."
            )
        )
        // The refusal must be THIS specific error, not a generic "Error 4xx" / "Ollama error".
        assertFalse(
            "[$route] a generic failure was surfaced instead of the actionable overflow message: " +
                error.text,
            error.text.contains("Unknown error") || error.text.contains("Ollama error") ||
                error.text.contains("Error 4") || error.text.contains("Error 5")
        )

        val reported = Regex("\\(~(\\d+) tokens\\)").find(error.text)?.groupValues?.get(1)?.toIntOrNull()
        assertNotNull(
            "[$route] the message must report the overflowing token count, got: ${error.text}",
            reported
        )
        assertTrue(
            "[$route] reported ~$reported tokens, but the same inputs measure ~$expectedFixedTokens " +
                "(reserved $RESERVED_OUTPUT_TOKENS + system prompt + current message w/ attachments). " +
                "A difference of up to $DATE_PREFIX_SLACK_TOKENS is allowed because the request's " +
                "date/time prefix is rebuilt at assertion time",
            abs(reported!! - expectedFixedTokens) <= DATE_PREFIX_SLACK_TOKENS
        )

        // The user's own turn stays in the transcript (the refusal is per-turn, pre-send)...
        assertTrue(
            "[$route] the user's own message must still be in the transcript, got: " +
                messages.map { it.role + ":" + it.text.take(40) },
            messages.any { it.role == "user" && it.text == expectedUserMessageText }
        )
        // ...and nothing else was produced: no answer, no streamed placeholder.
        assertTrue(
            "[$route] a non-error assistant bubble was added despite the refusal: " +
                messages.filter { it.role == "assistant" && !it.isError }.map { it.text.take(60) },
            messages.none { it.role == "assistant" && !it.isError }
        )

        // THE decisive check: no request was built or sent at all.
        assertEquals(
            "[$route] the oversized request must NOT reach the server, but the stub saw " +
                "${stub.requestCount.get()} connection(s) (${stub.chatRequestCount.get()} to /api/chat)",
            0,
            stub.requestCount.get()
        )
        return error
    }
    // ---- the two overflow routes, the second builder, and the inverse control ----------------

    /**
     * Route (a): a deliberately long SYSTEM PROMPT with a short message.
     *
     * The persona's window is 2048 tokens and 1024 of them are reserved for the reply, so a
     * ~2,460-token system prompt overflows the window on its own — before any history is even
     * considered. The request must be refused with the actionable message, and nothing may be sent.
     */
    @Test
    fun longSystemPromptIsRefusedBeforeSending() {
        val persona = directPersona(LONG_PROMPT_PERSONA_NAME, LONG_SYSTEM_PROMPT)
        val fixed = expectedFixedTokens(persona, PROBE_SHORT_MESSAGE)
        assertTrue(
            "precondition: the long system prompt alone must exceed the $SMALL_NUM_CTX-token " +
                "window (measured ~$fixed tokens including $RESERVED_OUTPUT_TOKENS reserved)",
            fixed > SMALL_NUM_CTX
        )

        StubProbeServer().use { stub ->
            registerProbeServer(stub)
            activate(persona)
            renderChatList()

            sendTurn(persona, PROBE_SHORT_MESSAGE)

            val error = assertRefusedBeforeSending(
                route = "long system prompt + short message",
                persona = persona,
                stub = stub,
                expectedFixedTokens = fixed,
                expectedUserMessageText = PROBE_SHORT_MESSAGE
            )
            awaitRenderedText(
                "exceed this persona's Context Window Size (${persona.numCtx})",
                "the refusal was never rendered in the chat list (bubble text: ${error.text})"
            )
        }
    }

    /**
     * Route (b): a short system prompt but a LARGE TEXT ATTACHMENT.
     *
     * The attachment's text is inlined verbatim into the current message by `buildModelPrompt`,
     * so it counts against the window exactly like the message itself does. The preconditions
     * pin the cause down: the same persona fits comfortably without the attachment and cannot fit
     * with it — so a refusal here can only be the attachment.
     */
    @Test
    fun largeTextAttachmentIsRefusedBeforeSending() {
        val persona = directPersona(SHORT_PROMPT_PERSONA_NAME, SHORT_SYSTEM_PROMPT)
        val withoutAttachment = expectedFixedTokens(persona, PROBE_SHORT_MESSAGE)
        assertTrue(
            "precondition: this persona must be UNDER budget without the attachment " +
                "(measured ~$withoutAttachment tokens against a $SMALL_NUM_CTX-token window)",
            withoutAttachment < SMALL_NUM_CTX
        )
        val modelText = expectedAttachmentModelText(
            PROBE_SHORT_MESSAGE,
            ATTACHMENT_FILE_NAME,
            LARGE_ATTACHMENT_FILLER
        )
        val withAttachment = expectedFixedTokens(persona, modelText)
        assertTrue(
            "precondition: the ${LARGE_ATTACHMENT_FILLER.length}-char attachment must push the " +
                "request over the $SMALL_NUM_CTX-token window (measured ~$withAttachment tokens)",
            withAttachment > SMALL_NUM_CTX
        )

        StubProbeServer().use { stub ->
            registerProbeServer(stub)
            activate(persona)
            renderChatList()
            val attachment = createTextAttachment(ATTACHMENT_FILE_NAME, LARGE_ATTACHMENT_FILLER)

            sendTurn(persona, PROBE_SHORT_MESSAGE, listOf(attachment))

            val error = assertRefusedBeforeSending(
                route = "short system prompt + large text attachment",
                persona = persona,
                stub = stub,
                expectedFixedTokens = withAttachment,
                expectedUserMessageText = "$PROBE_SHORT_MESSAGE\n\n(Attached: $ATTACHMENT_FILE_NAME)"
            )
            awaitRenderedText(
                "exceed this persona's Context Window Size (${persona.numCtx})",
                "the refusal was never rendered in the chat list (bubble text: ${error.text})"
            )
        }
    }
    /**
     * The SAME overflow through the other edited builder: a cloud provider persona (so
     * `sendTextMessageToServer` routes into `performCloudChat` → `buildCloudRequest`). The refusal
     * must happen before the HTTP call, so the stub's counters must still be zero.
     */
    @Test
    fun cloudRequestWithLongSystemPromptIsRefusedBeforeSending() {
        val persona = cloudPersona(CLOUD_PROMPT_PERSONA_NAME, LONG_SYSTEM_PROMPT)
        val fixed = expectedFixedTokens(persona, PROBE_SHORT_MESSAGE)
        assertTrue(
            "precondition: the cloud persona's long system prompt alone must exceed the " +
                "$SMALL_NUM_CTX-token window (measured ~$fixed tokens)",
            fixed > SMALL_NUM_CTX
        )

        StubProbeServer().use { stub ->
            registerProbeCloudProvider(stub)
            activate(persona)
            renderChatList()

            sendTurn(persona, PROBE_SHORT_MESSAGE)

            val error = assertRefusedBeforeSending(
                route = "cloud provider + long system prompt",
                persona = persona,
                stub = stub,
                expectedFixedTokens = fixed,
                expectedUserMessageText = PROBE_SHORT_MESSAGE
            )
            awaitRenderedText(
                "exceed this persona's Context Window Size (${persona.numCtx})",
                "the cloud refusal was never rendered in the chat list (bubble text: ${error.text})"
            )
        }
    }

    /**
     * The inverse control: the same 2048-token window, but nothing overflows — so the request must
     * still be sent and answered normally. This is what rules out an over-eager check that would
     * satisfy the three refusal tests above by refusing everything.
     */
    @Test
    fun requestThatFitsIsStillSentNormally() {
        val persona = directPersona(CONTROL_PERSONA_NAME, SHORT_SYSTEM_PROMPT)
        val fixed = expectedFixedTokens(persona, PROBE_CONTROL_MESSAGE)
        assertTrue(
            "precondition: the control request must FIT the $SMALL_NUM_CTX-token window " +
                "(measured ~$fixed tokens including $RESERVED_OUTPUT_TOKENS reserved)",
            fixed < SMALL_NUM_CTX
        )

        StubProbeServer(answer = CONTROL_ANSWER).use { stub ->
            registerProbeServer(stub)
            activate(persona)
            renderChatList()

            sendTurn(persona, PROBE_CONTROL_MESSAGE)

            awaitService(20_000, "the under-budget request was never sent to the server") {
                stub.chatRequestCount.get() > 0
            }
            awaitRenderedText(
                CONTROL_ANSWER,
                "the under-budget answer never reached the chat list"
            )
            awaitService(20_000, "the under-budget turn never returned to IDLE") {
                service.assistantState.value == AssistantState.IDLE
            }

            assertEquals(
                "exactly one chat request should have been sent",
                1,
                stub.chatRequestCount.get()
            )
            assertEquals(
                "the answer should be the only assistant bubble",
                1,
                service.messages.value.count { it.role == "assistant" && it.text == CONTROL_ANSWER }
            )
            assertFalse(
                "the pre-flight check refused a request that fits: " +
                    service.messages.value.filter { it.isError }.map { it.text },
                service.messages.value.any { it.isError }
            )
            assertFalse(
                "an in-budget turn must not show the overflow explanation",
                service.messages.value.any {
                    it.text.contains("exceed this persona's Context Window Size")
                }
            )
        }
    }
    /**
     * Minimal HTTP/1.1 server on loopback that counts what reaches it. Every fully read request
     * increments [requestCount]; a `POST /api/chat` additionally increments [chatRequestCount] and
     * is answered with the Direct-Ollama streaming NDJSON contract `performDirectOllamaChat` reads
     * (`stream = true`, one JSON object per line, final line `done:true`); anything else gets a 404.
     *
     * The refusal tests assert both counters stay at ZERO — that is what proves the oversized
     * request was never built or sent — while the control asserts exactly one chat request.
     */
    private class StubProbeServer(private val answer: String = CONTROL_ANSWER) : AutoCloseable {

        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val worker: Thread

        val url: String get() = "http://127.0.0.1:${socket.localPort}"
        val requestCount = AtomicInteger()
        val chatRequestCount = AtomicInteger()

        init {
            worker = thread(isDaemon = true, name = "stub-fix6-overflow") {
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

            requestCount.incrementAndGet()
            val output = connection.getOutputStream()
            if (!(headerText.startsWith("POST") && headerText.contains("/api/chat"))) {
                output.write(
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                output.flush()
                return
            }

            chatRequestCount.incrementAndGet()
            val body = (
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
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            output.write(body)
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
        /** Must match the `[Name] model` tag on the direct probe personas — that is how it resolves. */
        const val PROBE_SERVER_NAME = "Fix6ProbeOverflowServer"

        /** Registered in `_customCloudApis`; the cloud probe persona's tag points at it. */
        const val PROBE_CLOUD_PROVIDER = "Fix6ProbeCloud"

        /** Icon "C" is the generic cloud/api-key-optional provider, so no key is needed. */
        const val PROBE_CLOUD_ICON = "C"

        /** Not an `isKnownThinkingModel` match, so `think` is never requested. */
        const val PROBE_MODEL = "fix6-probe-overflow-model"

        /** The control turn's whole answer, delivered as ONE NDJSON chunk. */
        const val CONTROL_ANSWER = "FIX6-CONTROL-ANSWER"

        /** The persona Context Window Size under test — small, so the fixed parts overflow it. */
        const val SMALL_NUM_CTX = 2048

        /** `persona.maxTokens`: what both builders reserve for the reply (floor 1024). */
        const val RESERVED_OUTPUT_TOKENS = 1024

        /**
         * Slack allowed when comparing the message's "~X tokens" against this test's independent
         * recomputation: the builders embed the request-time date/time string, which this test
         * rebuilds a moment later. Within a day that string's length only changes at midnight
         * (day-of-month digit count), which moves the chars/4 estimate by at most this much.
         */
        const val DATE_PREFIX_SLACK_TOKENS = 2

        /** `.txt` ⇒ accepted by `isPlainTextAttachment`; referenced as a real `file://` Uri. */
        const val ATTACHMENT_FILE_NAME = "fix6_large_probe.txt"

        /**
         * ~2,460 tokens on its own: with 1024 tokens reserved for the reply inside a 2048-token
         * window, no amount of history trimming can make this persona's request fit, which is
         * exactly the situation the pre-flight refusal exists for.
         */
        val LONG_SYSTEM_PROMPT: String = "You are the Fix6 overflow probe persona. ".repeat(240)

        /** ~8 tokens — comfortably inside the window on its own (proved by the control test). */
        const val SHORT_SYSTEM_PROMPT = "Fix6 short-prompt probe persona."

        /** Deliberately short: the overflow must come from the prompt or the attachment. */
        const val PROBE_SHORT_MESSAGE = "probe question"

        /** Also avoids the word "news", so `isNewsRequest` cannot fire a search. */
        const val PROBE_CONTROL_MESSAGE = "describe the control answer"

        /**
         * ~35,000 chars (~8,750 tokens) of plain text: inlined into the current message by
         * `buildModelPrompt`, it alone blows the 2048-token window.
         */
        val LARGE_ATTACHMENT_FILLER: String =
            "Fix6 attachment filler line for the overflow route.\n".repeat(700)

        val PROBE_COLOR = Color(0xFF4ADE80)

        const val LONG_PROMPT_PERSONA_NAME = "Fix6ProbeLongPromptPersona"
        const val SHORT_PROMPT_PERSONA_NAME = "Fix6ProbeAttachmentPersona"
        const val CLOUD_PROMPT_PERSONA_NAME = "Fix6ProbeCloudPromptPersona"
        const val CONTROL_PERSONA_NAME = "Fix6ProbeControlPersona"

        /** Every probe persona whose stored history this class must clean up. */
        val probePersonaNames = listOf(
            LONG_PROMPT_PERSONA_NAME,
            SHORT_PROMPT_PERSONA_NAME,
            CLOUD_PROMPT_PERSONA_NAME,
            CONTROL_PERSONA_NAME
        )

        /**
         * Direct-Ollama probe persona: pointed at [PROBE_SERVER_NAME] through the `[Name] model`
         * tag, `voiceMode = NONE` (so `playResponse` bails to IDLE without touching TTS), a
         * 2048-token window and the minimum 1024-token output reservation.
         */
        fun directPersona(name: String, systemPrompt: String): Persona = Persona(
            name = name,
            themeColor = PROBE_COLOR,
            model = "[$PROBE_SERVER_NAME] $PROBE_MODEL",
            systemPrompt = systemPrompt,
            isCloud = false,
            backendUrl = "",
            allowGatewayFailover = false,
            voiceMode = VoiceMode.NONE,
            enableThinking = false,
            webSearchEnabled = false,
            ragEnabled = false,
            maxTokens = RESERVED_OUTPUT_TOKENS,
            numCtx = SMALL_NUM_CTX
        )

        /**
         * The same, but a CLOUD persona: the provider name must resolve to a registered api setting
         * for `sendTextMessageToServer` to route into `performCloudChat` → `buildCloudRequest`.
         */
        fun cloudPersona(name: String, systemPrompt: String): Persona = Persona(
            name = name,
            themeColor = PROBE_COLOR,
            model = "[$PROBE_CLOUD_PROVIDER] $PROBE_MODEL",
            systemPrompt = systemPrompt,
            isCloud = true,
            providerIcon = PROBE_CLOUD_ICON,
            backendUrl = "",
            allowGatewayFailover = false,
            voiceMode = VoiceMode.NONE,
            enableThinking = false,
            webSearchEnabled = false,
            ragEnabled = false,
            maxTokens = RESERVED_OUTPUT_TOKENS,
            numCtx = SMALL_NUM_CTX
        )
    }
}
