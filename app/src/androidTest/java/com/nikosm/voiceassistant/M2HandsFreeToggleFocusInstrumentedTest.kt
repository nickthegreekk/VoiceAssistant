package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * M2 — toggling hands-free off must not force IDLE underneath a live turn (and thereby leak
 * the audio focus that turn retained through THINKING).
 *
 * The bug: `stopVadListening()` ended with an UNCONDITIONAL `_state.value = IDLE`. Toggling
 * hands-free off while a VAD-initiated turn was in flight therefore reset THINKING even though
 * the turn was still running. The turn's own terminal path is guarded by
 * `if (_state.value == AssistantState.THINKING && isChatRequestCurrent(generation))`, so that
 * guard then FAILED — and skipped `abandonAssistantFocus()`, the abandonment that releases the
 * focus `stopRecording()` deliberately retains through THINKING for a playback-following voice
 * turn. Other apps stayed ducked with no owner left to un-duck them: the UI was already back on
 * IDLE, and the turn that held the request could never reach its own abandon.
 *
 * The fix gates the reset on OWNERSHIP: the reset happens only when the THINKING found there is
 * not owned by a chat turn that also recorded itself as the state's owner
 * (`recordThinkingOwner(generation)`) and is still current.
 *
 * How this proves it on-device: a real VAD-path turn runs through the production
 * `sendAudioToServer` (the exact call the VAD recorder's `onSpeechEnd` makes) into two held
 * loopback stubs — a gateway (`/transcribe`) and a cloud provider — while the retained focus is
 * requested through the production `requestAssistantFocus()`, the exact call `stopRecording()`
 * relies on to hold focus through THINKING. (Only the framework's own answer is stubbed, via the
 * service's `audioFocusRequester` seam, so a busy focus stack on the test device cannot turn the
 * precondition flaky — what this test is about is the retained REQUEST, and that the turn's
 * terminal path must release it.) The turn is parked inside `/transcribe` when the
 * production `stopVadListening()` toggle runs, so "a turn is in flight when hands-free goes
 * off" is ENGINEERED rather than raced. The turn is then let through to the cloud stub, which
 * answers 500 so the turn terminates WITHOUT handing off to playback — the failure path whose
 * finally owns the abandon under test, and the one that never touches a TTS engine or
 * MediaPlayer on the test device.
 *
 * What is asserted is that ownership contract: the toggle leaves the live turn THINKING and
 * holding its focus, and that turn's own terminal path still releases the focus and lands IDLE.
 * The control case pins the other direction — a THINKING that NO chat turn owns (the Gateway
 * synthesis-feedback replay) must still be normalized to IDLE by the toggle, so the fix cannot
 * pass by turning the toggle into a no-op.
 *
 * WHAT IS NOT EXERCISED HERE, HONESTLY: the recorder side of the toggle
 * (`vadRecorder?.stop()` / `_handsFreeMode`). That needs a live `AudioRecord` session
 * (RECORD_AUDIO plus a real mic) and contributes nothing to the state/ownership decision this
 * test is about — `stopVadListening()` is the production method the UI's toggle-off calls, and
 * only its state/focus decision is under test.
 */
@RunWith(AndroidJUnit4::class)
class M2HandsFreeToggleFocusInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    /** The service's own production requester (the real framework call), captured for restore. */
    private lateinit var defaultAudioFocusRequester: (AudioFocusRequest) -> Int

    private var serverBasesBefore: List<ServerConfig> = emptyList()
    private var customCloudApisBefore: List<CloudApiSetting> = emptyList()
    private var serverStatusBefore: Map<String, String> = emptyMap()
    private var personaNameBefore: String? = null
    private var messagesBefore: List<ChatMessage> = emptyList()
    private val scratchFiles = mutableListOf<File>()

    /** Set when a case had to unmute the service, so tearDown can restore that setting. */
    private var silencedWasToggled = false

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
        defaultAudioFocusRequester = service.audioFocusRequester
        serverBasesBefore = service._serverBases.value
        customCloudApisBefore = service._customCloudApis.value
        serverStatusBefore = service._serverStatus.value
        personaNameBefore = service.currentPersonaName
        messagesBefore = service._messages.value
        service.settingsManager.deletePersonaHistory(PROBE_PERSONA)
        // Start from "no focus held and no state owner recorded": the service is reused from an
        // earlier test in this process, and both cases assert against exactly that baseline.
        service.abandonAssistantFocus()
        service.thinkingOwnerGeneration = null
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        runCatching { instrumentation.runOnMainSync { service.stopEverything() } }
        runCatching {
            instrumentation.runOnMainSync { service.audioFocusRequester = defaultAudioFocusRequester }
        }
        runCatching { service.abandonAssistantFocus() }
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
                // M2/H3 bookkeeping is service-global process state: leave none of it behind.
                service.thinkingOwnerGeneration = null
                service._streamingText.value = null
                service.streamedPlaceholderIndex = null
                service.streamedPlaceholderGeneration = null
                service.streamingTextGeneration = null
            }
        }
        runCatching { service.settingsManager.deletePersonaHistory(PROBE_PERSONA) }
        if (silencedWasToggled) runCatching { service.toggleSilence() }
        scratchFiles.forEach { runCatching { it.delete() } }
        scratchFiles.clear()
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    // ---- the evidence ---------------------------------------------------------------------

    /**
     * The bug's own scenario: a VAD-initiated voice turn is in flight — parked inside the
     * gateway's `/transcribe` — when hands-free is toggled off. The toggle used to reset
     * THINKING to IDLE unconditionally; the turn's own terminal path then found
     * `_state != THINKING` and skipped BOTH `abandonAssistantFocus()` and its ownership
     * release, leaving the focus it had retained through THINKING held forever (with the UI
     * already on IDLE, nothing could ever release it).
     *
     * Asserted here: the toggle leaves the live turn's state and focus alone, and that same
     * turn's failure path still releases both.
     */
    @Test
    fun handsFreeToggleLeavesTheInFlightTurnsStateAndFocusToThatTurn() {
        val question = "what is the weather"
        val gatewayReply = "{\"text\":\"$question\"}"
        ParkedServer(holdResponse = true, statusCode = 200, responseBody = gatewayReply.toByteArray())
            .use { gateway ->
                ParkedServer(
                    holdResponse = true,
                    statusCode = 500,
                    // An OpenAI-shaped error body: the app surfaces `Error 500: <message>`.
                    responseBody = """{"error":{"message":"probe upstream failure"}}""".toByteArray()
                ).use { cloud ->
                    registerGateway(gateway)
                    registerCloudProvider(cloud, PROBE_PROVIDER, icon = "C", apiKey = "")
                    val persona = gatewayVoicePersona(backendUrl = gateway.url)
                    activate(persona)

                    // 1. The focus a playback-following voice turn retains through THINKING,
                    //    requested through the production call stopRecording() relies on. Only
                    //    the framework's answer is stubbed (the service's own test seam), so a
                    //    busy focus stack on the device cannot make this precondition flaky.
                    mainSync {
                        service.audioFocusRequester = { AudioManager.AUDIOFOCUS_REQUEST_GRANTED }
                    }
                    assertTrue(
                        "requestAssistantFocus() did not grant the retained focus; the release " +
                            "half of this test needs a real request to release",
                        mainSyncGet { service.requestAssistantFocus() }
                    )
                    assertNotNull(
                        "production requestAssistantFocus() did not retain an AudioFocusRequest",
                        service.audioFocusRequest
                    )

                    // 2. The VAD-initiated turn: the exact production call the recorder's
                    //    onSpeechEnd makes, parked inside the gateway's /transcribe.
                    sendVoiceTurn(persona)
                    awaitService(20_000, "the voice turn never reached the gateway's /transcribe") {
                        gateway.requestCount.get() >= 1
                    }
                    assertEquals(
                        "the voice turn must own THINKING while it is parked in transcription",
                        AssistantState.THINKING,
                        service.assistantState.value
                    )
                    assertEquals(
                        "the turn must not have moved past transcription yet",
                        0,
                        cloud.requestCount.get()
                    )

                    // 3. Hands-free is toggled off while that turn is in flight.
                    mainSync { service.stopVadListening() }

                    // 4. M2: the live turn keeps the state it owns (and, with it, the guard in
                    //    its own finally that releases the focus) ...
                    assertStateHolds(
                        AssistantState.THINKING,
                        1_500,
                        "M2: stopVadListening() forced IDLE underneath a live turn — the turn's own " +
                            "`_state == THINKING` finally guard then skips the focus abandonment"
                    )
                    // ... and the toggle did not release the focus either.
                    assertNotNull(
                        "M2: the toggle must not abandon the focus a live turn retained through THINKING",
                        service.audioFocusRequest
                    )

                    // 5. The turn is let through: it reaches the cloud provider, still THINKING.
                    gateway.releaseResponse()
                    awaitService(20_000, "the turn never reached the cloud provider") {
                        cloud.requestCount.get() >= 1
                    }
                    assertEquals(
                        "the turn must still be THINKING while its cloud call is in flight",
                        AssistantState.THINKING,
                        service.assistantState.value
                    )

                    // 6. The cloud call fails, so the turn terminates through its own failure
                    //    path — the code that owns both the THINKING state and the focus here.
                    cloud.releaseResponse()
                    awaitIdle("the failed turn never returned to IDLE")
                    assertTrue(
                        "the turn's failure never surfaced in the transcript",
                        service.messages.value.any { it.isError }
                    )

                    // 7. The evidence for M2: that path released the focus it retained and let go
                    //    of the state ownership. Pre-fix both survive, because step 3 already
                    //    cleared the THINKING the guard tests for.
                    assertNull(
                        "M2 leak: the turn terminated but its retained AudioFocusRequest was never " +
                            "abandoned — other apps stay ducked with the UI back on IDLE and no owner " +
                            "left to release it",
                        service.audioFocusRequest
                    )
                    assertNull(
                        "M2: the terminal path left a stale THINKING owner behind (state=${service.assistantState.value})",
                        service.thinkingOwnerGeneration
                    )
                }
            }
    }

    /**
     * The other direction of the same rule, so the fix cannot pass by turning the toggle into a
     * no-op: a THINKING that NO chat turn owns must still be normalized to IDLE.
     *
     * The state shape comes from a real production path — replaying a Gateway persona's message
     * whose audio was never persisted (`ChatMessage.audioFilePath == null` by design for streamed
     * turns) re-synthesizes it, and `synthesizeGatewayTextForPlayback` sets THINKING for the
     * synthesis latency WITHOUT recording an owner: it is playback feedback, not a chat turn, and
     * it never retains audio focus. With the `/synthesize` response parked, that is exactly
     * "THINKING with no owner recorded", held still long enough to toggle against.
     *
     * The parked response is deliberately EMPTY bytes: when it is released the pipeline finds no
     * audio, so this case can never drift into playback (or a device-dependent MediaPlayer) after
     * its assertions — it can only land IDLE, which is what tearDown expects to find anyway.
     */
    @Test
    fun handsFreeToggleStillNormalizesAThinkingThatNoTurnOwns() {
        // An empty `audio/wav` answer: the pipeline's own `audioBytes.isEmpty() -> continue`
        // guard handles it (no JSON parse, no exception), so the case can only land IDLE.
        ParkedServer(
            holdResponse = true,
            statusCode = 200,
            responseBody = ByteArray(0),
            contentType = "audio/wav"
        ).use { gateway ->
            registerGateway(gateway)
            val persona = gatewayVoicePersona(backendUrl = gateway.url)
            activate(persona)
            if (service.silenced.value) {
                // A muted service bails before synthesis (AssistantServiceAudio: silenced ->
                // IDLE, no THINKING feedback). That landing is covered elsewhere; this case
                // needs the unmuted path, and tearDown restores the setting.
                service.toggleSilence()
                silencedWasToggled = true
            }
            assertNull(
                "precondition: this case asserts against a baseline of 'no owner recorded'",
                service.thinkingOwnerGeneration
            )

            mainSync { service.replayMessageAudio(ChatMessage("assistant", REPLAY_TEXT), persona) }
            awaitService(20_000, "the replay never entered its THINKING synthesis feedback") {
                service.assistantState.value == AssistantState.THINKING
            }
            assertEquals(
                "the synthesis feedback must be waiting on the gateway, not finished",
                1,
                gateway.requestCount.get()
            )
            assertNull(
                "precondition: this THINKING must have NO owner recorded — that is the case under test",
                service.thinkingOwnerGeneration
            )

            // Toggling hands-free off here must still land IDLE, exactly as the unconditional
            // write always did: nothing is in flight that could own the state or release focus.
            mainSync { service.stopVadListening() }
            assertEquals(
                "M2: the toggle no longer normalizes a THINKING that no live turn owns — the fix " +
                    "must not have disabled the reset",
                AssistantState.IDLE,
                service.assistantState.value
            )
            assertNull(service.thinkingOwnerGeneration)

            // Let the parked synthesis finish so nothing outlives this case (empty body -> no
            // audio -> the pipeline's own no-play guard lands IDLE).
            gateway.releaseResponse()
            awaitService(20_000, "the parked synthesis never completed") {
                gateway.responseSent.count == 0L
            }
            assertEquals(
                "the settled no-audio path must end on IDLE",
                AssistantState.IDLE,
                service.assistantState.value
            )
        }
    }

    // ---- harness --------------------------------------------------------------------------

    /** Points the service at the loopback stub gateway (the entry the persona resolves through). */
    private fun registerGateway(stub: ParkedServer) {
        mainSync {
            service._serverBases.value =
                serverBasesBefore.filterNot { it.name == PROBE_GATEWAY } +
                    ServerConfig(name = PROBE_GATEWAY, url = stub.url)
        }
    }

    /** Icon "C" needs no API key; "A"/"G" require a non-blank one to be accepted at all. */
    private fun registerCloudProvider(stub: ParkedServer, provider: String, icon: String, apiKey: String) {
        mainSync {
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

    /** Makes [persona] the active persona with an empty transcript, as the UI would. */
    private fun activate(persona: Persona) {
        mainSync {
            service.currentPersonaName = persona.name
            // L5: the history owner moves with the selection, exactly as switchPersona does —
            // this test's probe turns are saved under the probe persona's key (which tearDown
            // deletes), not under whichever persona the service bound with.
            service.messagesOwnerName = persona.name
            service._messages.value = emptyList()
        }
    }

    /**
     * A fresh, real recording file in the app's own cacheDir, sent through the production entry
     * point the VAD recorder's `onSpeechEnd` calls.
     */
    private fun sendVoiceTurn(persona: Persona) {
        val file = File(context.cacheDir, "m2_probe_recording_${System.currentTimeMillis()}.wav")
        file.writeBytes(ByteArray(64) { 0 })
        scratchFiles += file
        mainSync { service.sendAudioToServer(file, persona) }
    }

    /**
     * The cloud voice persona the VAD path drives: transcribe at the gateway, then answer at the
     * cloud provider — the shape whose terminal path owns both the THINKING state and the focus.
     */
    private fun gatewayVoicePersona(backendUrl: String) = Persona(
        name = PROBE_PERSONA,
        themeColor = PROBE_COLOR,
        model = "[$PROBE_PROVIDER] $PROBE_MODEL",
        systemPrompt = PROBE_SYSTEM_PROMPT,
        isCloud = true,
        providerIcon = "C",
        backendUrl = backendUrl,
        allowGatewayFailover = false,
        voiceMode = VoiceMode.GATEWAY,
        enableThinking = false,
        webSearchEnabled = false,
        ragEnabled = false,
        maxTokens = 256,
        numCtx = 8192
    )

    private fun awaitIdle(failure: String) =
        awaitService(20_000, failure) { service.assistantState.value == AssistantState.IDLE }

    private fun awaitService(timeoutMs: Long, failure: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(
            "$failure (state=${service.assistantState.value}, messages=${service.messages.value.size})",
            condition()
        )
    }

    /** Fails if the state is anything but [expected] at ANY sample over [durationMs]. */
    private fun assertStateHolds(expected: AssistantState, durationMs: Long, failure: String) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            assertEquals(failure, expected, service.assistantState.value)
            SystemClock.sleep(25)
        }
    }

    private fun mainSync(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    private fun <T> mainSyncGet(block: () -> T): T {
        val holder = arrayOfNulls<Any>(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { holder[0] = block() }
        @Suppress("UNCHECKED_CAST")
        return holder[0] as T
    }

    private companion object {
        const val PROBE_PERSONA = "M2HandsFreeToggleFocusProbePersona"
        const val PROBE_GATEWAY = "M2HandsFreeToggleFocusProbeGateway"
        const val PROBE_PROVIDER = "M2HandsFreeToggleFocusProbeCloud"
        const val PROBE_MODEL = "probe-model"
        const val PROBE_SYSTEM_PROMPT = "M2 hands-free toggle focus probe persona."

        /** Under the pipeline's 80-char chunk minimum and free of sentence boundaries: ONE chunk. */
        const val REPLAY_TEXT = "M2 replay ownership probe"

        val PROBE_COLOR = Color(0xFF4ADE80)
    }
}

/**
 * A loopback stub that can HOLD its response, so a turn can be parked INSIDE a request instead
 * of raced against one. It reads every request in full (line + headers + body) before it counts
 * it, then — when [holdResponse] — waits for [releaseResponse] before answering with the
 * scripted [statusCode]/[responseBody]. `requestArrived` fires the moment the request is in,
 * which is what "the turn is parked here" means; `responseSent` fires once the answer is out.
 *
 * Plain HTTP on 127.0.0.1, like the app's own LAN gateway deployments and the other stubs in
 * this suite — the instrumentation shares the app process, so no adb reverse is needed.
 */
private class ParkedServer(
    private val holdResponse: Boolean,
    private val statusCode: Int,
    private val responseBody: ByteArray,
    private val contentType: String = "application/json"
) : AutoCloseable {

    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    /** e.g. `http://127.0.0.1:41234` — the app appends its own path. */
    val url: String = "http://127.0.0.1:${socket.localPort}"

    val requestCount = AtomicInteger(0)

    /** Fires once the FIRST request has been read in full — the "turn is parked here" signal. */
    val requestArrived = CountDownLatch(1)

    /** Fires once a response has been written; while held, only after [releaseResponse]. */
    val responseSent = CountDownLatch(1)

    /** When [holdResponse], the worker waits on this before writing anything back. */
    private val released = CountDownLatch(1)

    private val connections = CopyOnWriteArrayList<Socket>()

    init {
        val port = socket.localPort
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break // close() tore the listener down
                }
                connections += client
                Thread({ handle(client) }, "held-server-$port").apply { isDaemon = true }.start()
            }
        }, "held-server-accept-$port").apply { isDaemon = true }.start()
    }

    /** Lets a held response through. Idempotent. */
    fun releaseResponse() = released.countDown()

    override fun close() {
        // Never leave a worker parked: the held response is allowed through and the connection
        // is torn down, so a test that ends while a turn is still parked cannot hang the run.
        releaseResponse()
        runCatching { socket.close() }
        connections.forEach { runCatching { it.close() } }
        connections.clear()
    }

    private fun handle(connection: Socket) {
        try {
            connection.use { client ->
                val input = BufferedInputStream(client.inputStream)
                val requestLine = readAsciiLine(input) ?: return
                var contentLength = 0
                while (true) {
                    val line = readAsciiLine(input) ?: return
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0 &&
                        line.substring(0, separator).trim().equals("Content-Length", ignoreCase = true)
                    ) {
                        contentLength = line.substring(separator + 1).trim().toIntOrNull() ?: 0
                    }
                }
                val body = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = input.read(body, read, contentLength - read)
                    if (count <= 0) break
                    read += count
                }
                // The whole request is in: from here the app is blocked on this answer.
                requestCount.incrementAndGet()
                requestArrived.countDown()

                if (holdResponse) released.await()
                if (client.isClosed) return

                val output = client.getOutputStream()
                val reason = when (statusCode) {
                    200 -> "OK"
                    500 -> "Internal Server Error"
                    else -> "Stub Error"
                }
                output.write(
                    (
                        "HTTP/1.1 $statusCode $reason\r\n" +
                            "Content-Type: $contentType\r\n" +
                            "Content-Length: ${responseBody.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII)
                )
                output.write(responseBody)
                output.flush()
                responseSent.countDown()
            }
        } catch (e: Exception) {
            // Either the test tore the connection down under a parked worker, or the app went
            // away mid-flight; neither is a condition this stub needs to report.
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
