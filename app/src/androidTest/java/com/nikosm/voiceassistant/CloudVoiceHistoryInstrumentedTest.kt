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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * H1 — a cloud voice turn must record the user's spoken turn, not only the reply.
 *
 * The bug: `sendAudioToServer`'s cloud branch transcribed the audio, threw the text away
 * (it only ever reached the model as the current turn) and let `performCloudChat` append
 * the ASSISTANT message. So a cloud voice conversation left the question out of `_messages`
 * — a visible transcript gap — and out of every subsequent request: `buildCloudRequest`
 * builds the next prompt FROM `_messages`, and an Anthropic persona runs
 * `history.dropWhile { it.role != "user" }` over it. With an assistant-only history that
 * `dropWhile` discarded EVERYTHING, so an Anthropic cloud voice persona had no memory at
 * all between turns (and Gemini/OpenAI personas lost the user half of the previous turn).
 *
 * The fix appends the transcribed user turn before `performCloudChat` (same gates as the
 * failure path) and passes `currentTurnInHistory = true` so that turn reaches the model
 * exactly once as the current turn and stays in the history slice.
 *
 * How this proves it on-device: the whole turn runs through the production entry point
 * (`sendAudioToServer`) against loopback stub servers — one standing in for the gateway
 * (`/transcribe`), one for the cloud provider (`/v1/messages`, `generateContent`,
 * `/chat/completions`). The second turn's REQUEST BODY is what is asserted: the previous
 * question and answer must be present, in alternating roles. Before the fix the request
 * contained only the current question for Anthropic (history wiped) and no previous user
 * turn for the others.
 *
 * Cleanup: the bound service outlives this class, so every probe entry it mutates
 * (`_serverBases`, `_customCloudApis`, `_serverStatus`, `_messages`, the active persona
 * name) is restored in tearDown and the probe personas' stored histories are deleted.
 */
@RunWith(AndroidJUnit4::class)
class CloudVoiceHistoryInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private var serverBasesBefore: List<ServerConfig> = emptyList()
    private var customCloudApisBefore: List<CloudApiSetting> = emptyList()
    private var serverStatusBefore: Map<String, String> = emptyMap()
    private var personaNameBefore: String? = null
    private var messagesBefore: List<ChatMessage> = emptyList()
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
        serverBasesBefore = service._serverBases.value
        customCloudApisBefore = service._customCloudApis.value
        serverStatusBefore = service._serverStatus.value
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
                service.currentPersonaName = personaNameBefore
                // L5: the history owner follows the restored selection — persistSettings() below
                // saves the (owner, messages) pair, so leaving the owner on the probe persona's
                // name would file this test's transcript under the probe key.
                service.messagesOwnerName = personaNameBefore
                service._serverBases.value = serverBasesBefore
                service._customCloudApis.value = customCloudApisBefore
                service._serverStatus.value = serverStatusBefore
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

    // ---- the three provider routes ---------------------------------------------------------

    @Test
    fun cloudVoiceTurnIsRecordedInTheTranscriptAndCarriedIntoTheNextRequest() {
        ProbeServer(transcripts = listOf(QUESTION_ONE, QUESTION_TWO)).use { gateway ->
            ProbeServer(responder = ::openAiReply).use { cloud ->
                registerGateway(gateway)
                registerCloudProvider(cloud, PROBE_PROVIDER, icon = "C", apiKey = "")
                val persona = cloudPersona(PROBE_PROVIDER, icon = "C", backendUrl = gateway.url)

                activate(persona)
                sendVoiceTurn(persona)

                awaitService(20_000, "the first cloud voice turn never produced a user+assistant pair") {
                    service.messages.value.size >= 2
                }
                val firstTurn = service.messages.value
                assertEquals(
                    "H1: the spoken question never reached the transcript — only the reply was appended",
                    "user",
                    firstTurn[0].role
                )
                assertEquals(QUESTION_ONE, firstTurn[0].text)
                assertEquals("assistant", firstTurn[1].role)
                assertEquals(PROBE_ANSWER, firstTurn[1].text)
                awaitIdle("the first cloud voice turn never returned to IDLE")

                sendVoiceTurn(persona)
                awaitService(20_000, "the second cloud voice turn never completed") {
                    service.messages.value.size >= 4
                }

                val secondRequest = cloud.requests.last { it.path.endsWith("/chat/completions") }
                val messages = JSONObject(secondRequest.body).getJSONArray("messages")
                val turns = (0 until messages.length()).map { i ->
                    val entry = messages.getJSONObject(i)
                    entry.getString("role") to entry.getString("content")
                }
                assertEquals(
                    "H1: turn 2's request must carry turn 1's question and answer as history " +
                        "(the transcript now feeds the next prompt)",
                    listOf("user" to QUESTION_ONE, "assistant" to PROBE_ANSWER, "user" to QUESTION_TWO),
                    turns.filter { it.first != "system" }
                )
            }
        }
    }

    @Test
    fun anthropicCloudVoiceConversationKeepsItsHistoryInsteadOfBeingWiped() {
        ProbeServer(transcripts = listOf(QUESTION_ONE, QUESTION_TWO)).use { gateway ->
            ProbeServer(responder = ::anthropicReply).use { cloud ->
                registerGateway(gateway)
                registerCloudProvider(cloud, PROBE_PROVIDER, icon = "A", apiKey = "probe-key")
                val persona = cloudPersona(PROBE_PROVIDER, icon = "A", backendUrl = gateway.url)

                activate(persona)
                driveTwoVoiceTurns(persona, cloud)

                val secondRequest = cloud.requests.last { it.path.endsWith("/v1/messages") }
                val messages = JSONObject(secondRequest.body).getJSONArray("messages")
                val turns = (0 until messages.length()).map { i ->
                    val entry = messages.getJSONObject(i)
                    entry.getString("role") to entry.getString("content")
                }
                // The pre-fix history was assistant-only, and `dropWhile { it.role != "user" }`
                // threw all of it away — the request then held the current question alone.
                assertEquals(
                    "H1/Anthropic: the dropWhile world must still see a previous user turn",
                    listOf("user" to QUESTION_ONE, "assistant" to PROBE_ANSWER, "user" to QUESTION_TWO),
                    turns
                )
            }
        }
    }

    @Test
    fun geminiCloudVoiceConversationKeepsAlternatingTurns() {
        ProbeServer(transcripts = listOf(QUESTION_ONE, QUESTION_TWO)).use { gateway ->
            ProbeServer(responder = ::geminiReply).use { cloud ->
                registerGateway(gateway)
                registerCloudProvider(cloud, PROBE_PROVIDER, icon = "G", apiKey = "probe-key")
                val persona = cloudPersona(PROBE_PROVIDER, icon = "G", backendUrl = gateway.url)

                activate(persona)
                driveTwoVoiceTurns(persona, cloud)

                val secondRequest = cloud.requests.last { it.path.contains(":generateContent") }
                val contents = JSONObject(secondRequest.body).getJSONArray("contents")
                val turns = (0 until contents.length()).map { i ->
                    val entry = contents.getJSONObject(i)
                    entry.getString("role") to
                        entry.getJSONArray("parts").getJSONObject(0).getString("text")
                }
                assertEquals(
                    "H1/Gemini: user/model turns must alternate with the previous turn intact",
                    listOf("user" to QUESTION_ONE, "model" to PROBE_ANSWER, "user" to QUESTION_TWO),
                    turns
                )
            }
        }
    }

    // ---- harness --------------------------------------------------------------------------

    private fun registerGateway(stub: ProbeServer) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service._serverBases.value =
                serverBasesBefore.filterNot { it.name == PROBE_GATEWAY } +
                    ServerConfig(name = PROBE_GATEWAY, url = stub.url)
        }
    }

    /** Icon "C" needs no API key; "A"/"G" require a non-blank one to be accepted at all. */
    private fun registerCloudProvider(stub: ProbeServer, provider: String, icon: String, apiKey: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service._customCloudApis.value =
                customCloudApisBefore.filterNot { it.name == provider } +
                    CloudApiSetting(
                        name = provider,
                        baseUrl = stub.url,
                        apiKey = apiKey,
                        icon = icon,
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

    /** A fresh, real recording file in the app's own cacheDir, sent via the production entry point. */
    private fun sendVoiceTurn(persona: Persona) {
        val file = File(context.cacheDir, "probe_recording_${System.currentTimeMillis()}.wav")
        file.writeBytes(ByteArray(64) { 0 })
        scratchFiles += file
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.sendAudioToServer(file, persona)
        }
    }

    private fun driveTwoVoiceTurns(persona: Persona, cloud: ProbeServer) {
        sendVoiceTurn(persona)
        awaitService(20_000, "the first voice turn never completed") { service.messages.value.size >= 2 }
        awaitIdle("the first voice turn never returned to IDLE")
        sendVoiceTurn(persona)
        awaitService(20_000, "the second voice turn never completed") {
            service.messages.value.size >= 4 && cloud.requests.size >= 2
        }
    }

    private fun awaitIdle(failure: String) =
        awaitService(20_000, failure) { service.assistantState.value == AssistantState.IDLE }

    private fun awaitService(timeoutMs: Long, failure: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("$failure (state=${service.assistantState.value}, messages=${service.messages.value.size})", condition())
    }

    private fun cloudPersona(provider: String, icon: String, backendUrl: String) = Persona(
        name = PROBE_PERSONA,
        themeColor = PROBE_COLOR,
        model = "[$provider] $PROBE_MODEL",
        systemPrompt = PROBE_SYSTEM_PROMPT,
        isCloud = true,
        providerIcon = icon,
        backendUrl = backendUrl,
        allowGatewayFailover = false,
        // NONE: playResponse's NONE branch lands in IDLE without touching TTS, so the
        // test never depends on a real MediaPlayer/audio device.
        voiceMode = VoiceMode.NONE,
        enableThinking = false,
        webSearchEnabled = false,
        ragEnabled = false,
        maxTokens = 256,
        numCtx = 8192
    )

    private companion object {
        const val PROBE_PERSONA = "CloudVoiceHistoryProbePersona"
        const val PROBE_GATEWAY = "CloudVoiceHistoryProbeGateway"
        const val PROBE_PROVIDER = "CloudVoiceHistoryProbeCloud"
        const val PROBE_MODEL = "probe-model"
        const val PROBE_SYSTEM_PROMPT = "Cloud voice history probe persona."

        const val QUESTION_ONE = "what is the capital of France"
        const val QUESTION_TWO = "and how many people live there"
        const val PROBE_ANSWER = "probe assistant reply"

        val PROBE_COLOR = Color(0xFF4ADE80)

        fun openAiReply(path: String): String? = when {
            path.endsWith("/chat/completions") ->
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"$PROBE_ANSWER\"}}]," +
                    "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3}}"
            else -> null
        }

        fun anthropicReply(path: String): String? = when {
            path.endsWith("/v1/messages") ->
                "{\"content\":[{\"type\":\"text\",\"text\":\"$PROBE_ANSWER\"}]," +
                    "\"usage\":{\"input_tokens\":5,\"output_tokens\":3}}"
            else -> null
        }

        fun geminiReply(path: String): String? = when {
            path.contains(":generateContent") ->
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"$PROBE_ANSWER\"}]}}]," +
                    "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":3}}"
            else -> null
        }
    }
/**
 * A loopback-only stand-in for one HTTP endpoint, scripted per request path.
 *
 * `/transcribe` is served internally from [transcripts] (one text per call, in order);
 * every other path goes through [responder], which returns the raw JSON body for that
 * path (null → 404). Every request's line, headers and body is recorded so a test can
 * assert what the app actually SENT — which is the whole point of the H1 assertions.
 *
 * Plain HTTP is used deliberately: the app talks to LAN gateways that way already and
 * opts into cleartext (`android:usesCleartextTraffic="true"`), and the instrumentation
 * shares the app process, so loopback needs no adb reverse.
 */
private class ProbeServer(
    private val transcripts: List<String> = emptyList(),
    private val responder: (path: String) -> String? = { null }
) : AutoCloseable {

    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val transcriptIndex = AtomicInteger()

    val url: String = "http://127.0.0.1:${socket.localPort}"
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    init {
        val port = socket.localPort
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break // close() tore the listener down
                }
                Thread({ handle(client) }, "probe-server-$port").apply { isDaemon = true }.start()
            }
        }, "probe-server-accept-$port").apply { isDaemon = true }.start()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun handle(connection: Socket) {
        connection.use { client ->
            val input = BufferedInputStream(client.inputStream)
            val requestLine = readAsciiLine(input) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: return
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] =
                        line.substring(separator + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = input.read(body, read, contentLength - read)
                if (count <= 0) break
                read += count
            }
            val path = requestLine.split(' ').getOrNull(1) ?: "/"
            requests += RecordedRequest(path, headers, String(body, 0, read, Charsets.UTF_8))

            val reply = when {
                path.startsWith("/transcribe") -> {
                    val index = transcriptIndex.getAndIncrement()
                    val text = transcripts.getOrElse(index) { transcripts.lastOrNull() ?: "" }
                    "{\"text\":\"$text\"}"
                }
                else -> responder(path)
            }

            val output = client.getOutputStream()
            if (reply == null) {
                output.write(
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.US_ASCII)
                )
            } else {
                val bytes = reply.toByteArray(Charsets.UTF_8)
                output.write(
                    (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII)
                )
                output.write(bytes)
            }
            output.flush()
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) return if (builder.isEmpty()) null else builder.toString()
            if (next == '\n'.code) return builder.toString().trimEnd('\r')
            builder.append(next.toChar())
        }
    }
}

/** One recorded request: the target path, lowercased header names, and the raw body. */
private data class RecordedRequest(
    val path: String,
    val headers: Map<String, String>,
    val body: String
)
}