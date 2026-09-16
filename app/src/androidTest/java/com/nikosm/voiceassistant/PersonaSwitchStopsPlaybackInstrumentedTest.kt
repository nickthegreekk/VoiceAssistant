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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
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
 * Pins the "a persona switch / clear-chat must stop the outgoing turn" contract:
 * [AssistantService.switchPersona] and [AssistantService.clearMessages] both call
 * `stopEverything()` before doing anything else, for two reasons verified separately here:
 *
 *  1. **Audio.** An in-flight chunked Gateway TTS sequence only ever halts when
 *     `ttsGeneration` advances (every chunk transition / onCompletion re-checks it), and
 *     nothing in a persona switch or a clear-chat advanced it — so the previous persona's
 *     response kept SPEAKING underneath the new persona's screen.
 *  2. **State republishing.** The chunked player's poll coroutine
 *     (`playChunkedTtsGateway` -> `startChunkPlayback`) republishes `_ttsPlaybackFraction`
 *     every ~100ms while `currentPlayer === player && generation matches && SPEAKING`. It
 *     knows nothing about personas, so with the sequence still alive it kept writing that
 *     fraction — measured against a response that is no longer on screen — while the UI
 *     had already swapped to the new persona's message list. The regression assertion
 *     below is therefore not just "the player went null" but "nothing republishes
 *     afterwards".
 *
 * Stop semantics (not just stopAudio) are asserted too: `stopEverything()` bumps the chat
 * sequence, so a discarded turn can no longer append its reply into history the user has
 * moved on from.
 *
 * Harness note — why this test is self-contained on-device: driving a REAL chunked Gateway
 * playback needs a `/synthesize` endpoint, and this repo ships no server (the gateway is
 * the user's own deployment). The class therefore starts a minimal in-process stub HTTP
 * server on 127.0.0.1 and points a probe persona's `backendUrl` at it. The instrumentation
 * and the service share one process, so the app's OkHttp client reaches the stub over
 * loopback with no adb reverse/tunnel needed. The stub returns DIGITAL SILENCE (a valid
 * 12s PCM WAV): the playback being interrupted is real — real MediaPlayer, real SPEAKING
 * window, real poll coroutine — but inaudible. Audio matters to this test only as
 * "something genuinely being played", never as sound.
 *
 * The service is bound through the production `AssistantBinder`, exactly like MainActivity
 * does, and every probe persona/history this class writes is deleted again in tearDown —
 * the bound instance outlives the test class and sibling tests must not inherit its state.
 */
@RunWith(AndroidJUnit4::class)
class PersonaSwitchStopsPlaybackInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null
    private var personaNameBefore: String? = null
    private var messagesBefore: List<ChatMessage> = emptyList()
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
        personaNameBefore = service.currentPersonaName
        messagesBefore = service._messages.value
        // Known baseline: the probe personas start with no stored history, so "the incoming
        // persona's list loaded" is a real observation rather than whatever a previous run
        // (or a previous test in this class) left behind.
        service.settingsManager.deletePersonaHistory(OUTGOING_PROBE_PERSONA.name)
        service.settingsManager.deletePersonaHistory(INCOMING_PROBE_PERSONA.name)
        // The stub-backed playback must not take the mute bail-out — the point is to have a
        // live sequence to interrupt.
        if (service.silenced.value) {
            service.toggleSilence()
            silencedWasToggled = true
        }
    }

    @After
    fun tearDown() {
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { service.stopEverything() }
        }
        runCatching {
            service._messages.value = messagesBefore
            service.currentPersonaName = personaNameBefore
        }
        runCatching {
            service._ttsPlaybackFraction.value = null
            service._ttsWordTimestamps.value = null
            service._voiceDuration.value = 0
        }
        runCatching { service.settingsManager.deletePersonaHistory(OUTGOING_PROBE_PERSONA.name) }
        runCatching { service.settingsManager.deletePersonaHistory(INCOMING_PROBE_PERSONA.name) }
        if (silencedWasToggled) runCatching { service.toggleSilence() }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }
/**
     * The core fix: a Gateway (chunked) sequence is genuinely playing, then the user
     * switches personas. Asserts (a) the playback objects are released and the state lands
     * IDLE, (b) the per-sequence Stage-2 state is cleared, (c) the chat generation advanced
     * (Stop semantics, so the discarded turn cannot append), (d) the outgoing persona's
     * history was still saved and the incoming persona's list loaded, and (e) nothing
     * republishes playback state afterwards — the part that used to hold and paint UI for a
     * response that is no longer on screen.
     */
    @Test
    fun personaSwitchMidGatewayPlaybackStopsAudioAndHaltsStateRepublishing() {
        val outgoingMessages = listOf(
            ChatMessage("user", "outgoing persona turn"),
            ChatMessage("assistant", "outgoing persona reply")
        )
        StubGatewayTtsServer().use { stub ->
            setActiveConversation(OUTGOING_PROBE_PERSONA, outgoingMessages)
            startGatewaySequenceAndAwaitPlayback(
                stub,
                OUTGOING_PROBE_PERSONA.copy(backendUrl = stub.url)
            )

            // Preconditions: a genuinely live chunked sequence — exactly the state that used
            // to survive the switch.
            assertEquals(
                "precondition: the chunked pipeline must be SPEAKING",
                AssistantState.SPEAKING,
                service.assistantState.value
            )
            assertNotNull("precondition: a live player must exist", service.currentPlayer)
            assertNotNull(
                "precondition: a live fraction must exist",
                service.ttsPlaybackFraction.value
            )
            assertNotNull(
                "precondition: live timestamps must exist",
                service.ttsWordTimestamps.value
            )
            assertTrue("precondition: a live duration must exist", service.voiceDuration.value > 0)

            val generationBefore = service.ttsGeneration
            val chatSeqBefore = service.currentChatRequestSeq()

            InstrumentationRegistry.getInstrumentation()
                .runOnMainSync { service.switchPersona(INCOMING_PROBE_PERSONA) }

            // (a) audio actually stopped, synchronously: stopAudio() releases the player and
            // stopEverything() lands the state, so no waiting is needed here.
            assertNull(
                "the chunked sequence's MediaPlayer survived the persona switch — audio keeps " +
                    "playing underneath the new persona's screen",
                service.currentPlayer
            )
            assertNull("an AudioTrack survived the persona switch", service.currentAudioTrack)
            assertEquals(
                "the persona switch left the service SPEAKING",
                AssistantState.IDLE,
                service.assistantState.value
            )
            // (b)
            assertNull(
                "a stale playback fraction survived the persona switch",
                service.ttsPlaybackFraction.value
            )
            assertNull(
                "stale word timestamps survived the persona switch",
                service.ttsWordTimestamps.value
            )
            assertEquals(
                "a stale voice duration survived the persona switch",
                0L,
                service.voiceDuration.value.toLong()
            )
            // (c) Stop semantics, not just stopAudio
            assertTrue(
                "the switch did not bump ttsGeneration ($generationBefore -> " +
                    "${service.ttsGeneration}) — the chunk sequence's own generation checks are " +
                    "what halts it",
                service.ttsGeneration > generationBefore
            )
            assertTrue(
                "the switch did not bump the chat sequence — a discarded turn could still append " +
                    "its reply into the outgoing persona's history",
                service.currentChatRequestSeq() > chatSeqBefore
            )
            // (d)
            assertEquals(
                "the outgoing persona's history was NOT saved by the switch",
                outgoingMessages,
                service.settingsManager.getPersonaMessages(OUTGOING_PROBE_PERSONA.name)
            )
            assertEquals(
                "the switch did not re-point the active persona",
                INCOMING_PROBE_PERSONA.name,
                service.currentPersonaName
            )
            assertEquals(
                "the incoming persona's message list was not loaded",
                0,
                service._messages.value.size
            )

            // (e) The regression that motivated the fix: the now-dead sequence's poll
            // coroutine must not keep republishing playback state. 1.5s is >10 poll ticks;
            // with the sequence still alive this window used to fill with fractions measured
            // against a response the UI no longer shows.
            SystemClock.sleep(1_500)
            assertNull(
                "playback state was REPUBLISHED after the persona switch — the dead sequence's " +
                    "poll coroutine is still writing against the new persona's list",
                service.ttsPlaybackFraction.value
            )
            assertNull(
                "playback state was republished after the persona switch",
                service.ttsWordTimestamps.value
            )
            assertEquals(
                "the service did not stay IDLE after the persona switch",
                AssistantState.IDLE,
                service.assistantState.value
            )
            assertNull("the player came back after the persona switch", service.currentPlayer)
        }
    }

    /**
     * Same contract through the other "move on" gesture. Clear-chat empties the list the
     * sequence was speaking for, so an in-flight sequence must stop for exactly the same two
     * reasons plus one more: a discarded turn must not append its reply into the history the
     * user just cleared.
     */
    @Test
    fun clearMessagesMidGatewayPlaybackStopsAudioAndRepublishing() {
        val outgoingMessages = listOf(
            ChatMessage("user", "turn that is about to be cleared"),
            ChatMessage("assistant", "response that must stop speaking")
        )
        StubGatewayTtsServer().use { stub ->
            setActiveConversation(OUTGOING_PROBE_PERSONA, outgoingMessages)
            startGatewaySequenceAndAwaitPlayback(
                stub,
                OUTGOING_PROBE_PERSONA.copy(backendUrl = stub.url)
            )
            val generationBefore = service.ttsGeneration

            InstrumentationRegistry.getInstrumentation()
                .runOnMainSync { service.clearMessages() }

            assertNull("clear-chat left the chunked sequence playing", service.currentPlayer)
            assertNull("clear-chat left an AudioTrack alive", service.currentAudioTrack)
            assertEquals(
                "clear-chat left the service SPEAKING",
                AssistantState.IDLE,
                service.assistantState.value
            )
            assertNull("clear-chat left a stale fraction", service.ttsPlaybackFraction.value)
            assertNull("clear-chat left stale timestamps", service.ttsWordTimestamps.value)
            assertTrue(
                "clear-chat did not bump ttsGeneration ($generationBefore -> " +
                    "${service.ttsGeneration})",
                service.ttsGeneration > generationBefore
            )
            assertEquals("clear-chat did not empty the message list", 0, service._messages.value.size)
            assertTrue(
                "clear-chat did not empty the persona's persisted history",
                service.settingsManager
                    .getPersonaMessages(OUTGOING_PROBE_PERSONA.name)
                    .orEmpty()
                    .isEmpty()
            )

            SystemClock.sleep(1_000)
            assertNull(
                "the cleared sequence republished playback state",
                service.ttsPlaybackFraction.value
            )
            assertEquals(AssistantState.IDLE, service.assistantState.value)
            assertNull(service.currentPlayer)
        }
    }

    /**
     * The common case must be untouched: switching with nothing playing keeps doing exactly
     * what it did before — save the outgoing history, load the incoming one, re-point the
     * active persona — and leaves the service IDLE with no playback objects.
     *
     * The one intentional difference is that `stopEverything()` still bumps its counters
     * even when there is nothing to stop. That is by design (it is the same call the Stop
     * button makes) and is harmless when idle: the generation only gates in-flight work, and
     * there is none. It is asserted explicitly below rather than left implicit.
     */
    @Test
    fun personaSwitchWithNothingPlayingKeepsTheNormalSaveAndLoadBehaviour() {
        val outgoingMessages = listOf(ChatMessage("user", "idle switch turn"))
        val incomingStored = listOf(
            ChatMessage("user", "incoming stored turn"),
            ChatMessage("assistant", "incoming stored reply")
        )
        setActiveConversation(OUTGOING_PROBE_PERSONA, outgoingMessages)
        // Fixture guard: the incoming persona must have real stored history for "loaded" to
        // mean anything.
        service.settingsManager.savePersonaMessages(INCOMING_PROBE_PERSONA.name, incomingStored)

        val stateBefore = service.assistantState.value
        val generationBefore = service.ttsGeneration

        InstrumentationRegistry.getInstrumentation()
            .runOnMainSync { service.switchPersona(INCOMING_PROBE_PERSONA) }

        assertEquals(
            "an idle persona switch changed the assistant state",
            stateBefore,
            service.assistantState.value
        )
        assertNull("an idle persona switch created a player", service.currentPlayer)
        assertNull("an idle persona switch created an AudioTrack", service.currentAudioTrack)
        assertNull("an idle persona switch published a fraction", service.ttsPlaybackFraction.value)
        assertEquals(
            "the outgoing persona's history was not saved by the switch",
            outgoingMessages,
            service.settingsManager.getPersonaMessages(OUTGOING_PROBE_PERSONA.name)
        )
        assertEquals(
            "the incoming persona's stored history was not loaded",
            incomingStored,
            service._messages.value
        )
        assertEquals(
            "the switch did not re-point the active persona",
            INCOMING_PROBE_PERSONA.name,
            service.currentPersonaName
        )
        // Documented, intended: the stop-everything pass runs unconditionally (see the KDoc
        // above), so its counter bump is observable even with nothing playing.
        assertEquals(
            "an idle switch should still perform exactly one stop-everything pass",
            generationBefore + 1,
            service.ttsGeneration
        )
    }

    /** Points the service at [persona] with [messages] in memory — the pre-switch world. */
    private fun setActiveConversation(persona: Persona, messages: List<ChatMessage>) {
        service.currentPersonaName = persona.name
        service._messages.value = messages
    }

    /**
     * Starts the real chunked Gateway pipeline against [stub] and waits for genuinely live
     * playback (SPEAKING + a player + a published fraction), so the tests below interrupt a
     * sequence that is actually running rather than an assumed one.
     */
    private fun startGatewaySequenceAndAwaitPlayback(stub: StubGatewayTtsServer, persona: Persona) {
        service.synthesizeGatewayTextForPlayback(SINGLE_CHUNK_TEXT, persona) { /* no-audio path unused */ }
        awaitCondition(20_000, "the stub-backed chunked Gateway sequence never reached SPEAKING") {
            service.assistantState.value == AssistantState.SPEAKING && service.currentPlayer != null
        }
        awaitCondition(5_000, "the chunked player never published a live playback fraction") {
            service.ttsPlaybackFraction.value != null
        }
        assertTrue(
            "the stub's /synthesize endpoint was never called — the sequence never really started",
            stub.requestCount.get() > 0
        )
    }

    private fun awaitCondition(timeoutMs: Long, failure: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(25)
        }
        assertTrue(
            "$failure (state=${service.assistantState.value}, player=${service.currentPlayer}, " +
                "fraction=${service.ttsPlaybackFraction.value})",
            condition()
        )
    }

    /**
     * Minimal HTTP/1.1 stub answering `POST /synthesize` with a silent PCM WAV plus the
     * Stage-2 header, mirroring the real gateway's contract as `synthesizeWithGateway` reads
     * it:
     *  - a non-JSON Content-Type means the body bytes are audio,
     *  - `X-Word-Timestamps-B64` carries base64 JSON of `{word, start, end}`.
     * The multipart request body is drained and discarded.
     */
    private class StubGatewayTtsServer(
        private val seconds: Int = 12,
        private val sampleRate: Int = 22_050
    ) : AutoCloseable {

        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val wavBytes = buildSilentPcmWav(seconds, sampleRate)
        private val worker: Thread

        val url: String get() = "http://127.0.0.1:${socket.localPort}"
        val requestCount = AtomicInteger()

        init {
            worker = thread(isDaemon = true, name = "stub-gateway-tts") {
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
            // Drain the multipart body: leaving it unread makes the client see a reset
            // connection instead of a response.
            var remaining = contentLength.toLong()
            val buffer = ByteArray(8_192)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                remaining -= read
            }

            val output = connection.getOutputStream()
            val isSynthesizePost =
                headerText.startsWith("POST") && headerText.contains("/synthesize")
            if (!isSynthesizePost) {
                output.write(
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                output.flush()
                return
            }

            requestCount.incrementAndGet()
            val timestampsJson = """[{"word":"probe","start":0.0,"end":1.0}]"""
            val timestampsB64 = android.util.Base64.encodeToString(
                timestampsJson.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            output.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: audio/wav\r\n" +
                        "Content-Length: ${wavBytes.size}\r\n" +
                        "X-Word-Timestamps-B64: $timestampsB64\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray()
            )
            output.write(wavBytes)
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
        companion object {
            /**
             * Canonical 44-byte RIFF/WAVE header for 16-bit PCM mono. The data section is
             * allocated zero-filled, and zero-valued 16-bit PCM samples ARE digital silence —
             * no sample generation needed, and MediaPlayer still reports a real duration.
             */
            private fun buildSilentPcmWav(seconds: Int, sampleRate: Int): ByteArray {
                val channels = 1
                val bitsPerSample = 16
                val blockAlign = channels * bitsPerSample / 8
                val dataSize = seconds * sampleRate * blockAlign
                val wav = ByteArray(44 + dataSize)

                fun ascii(offset: Int, text: String) {
                    text.forEachIndexed { i, c -> wav[offset + i] = c.code.toByte() }
                }

                fun intLe(offset: Int, value: Int) {
                    wav[offset] = (value and 0xFF).toByte()
                    wav[offset + 1] = ((value shr 8) and 0xFF).toByte()
                    wav[offset + 2] = ((value shr 16) and 0xFF).toByte()
                    wav[offset + 3] = ((value shr 24) and 0xFF).toByte()
                }

                fun shortLe(offset: Int, value: Int) {
                    wav[offset] = (value and 0xFF).toByte()
                    wav[offset + 1] = ((value shr 8) and 0xFF).toByte()
                }

                ascii(0, "RIFF")
                intLe(4, 36 + dataSize)
                ascii(8, "WAVE")
                ascii(12, "fmt ")
                intLe(16, 16)
                shortLe(20, 1)
                shortLe(22, channels)
                intLe(24, sampleRate)
                intLe(28, sampleRate * blockAlign)
                shortLe(32, blockAlign)
                shortLe(34, bitsPerSample)
                ascii(36, "data")
                intLe(40, dataSize)
                return wav
            }
        }
    }

    private companion object {
        /**
         * One long sentence, comfortably over `splitIntoTtsChunks`' default 80-char minimum
         * and with no internal sentence boundary, so the pipeline produces exactly ONE chunk.
         * One long chunk keeps the sequence playing far longer than any assertion window —
         * that is what makes "interrupt mid-playback" deterministic.
         */
        const val SINGLE_CHUNK_TEXT =
            "Probe voice response for the persona switch instrumentation test long enough to " +
                "exceed the minimum chunk length so the pipeline keeps a single chunk playing " +
                "without any sentence boundary to split it"

        fun probePersona(name: String, backendUrl: String = "") = Persona(
            name = name,
            themeColor = Color(0xFF123456),
            model = "",
            systemPrompt = "Instrumentation probe persona",
            backendUrl = backendUrl,
            voiceMode = VoiceMode.GATEWAY,
            voiceEngine = "kokoro",
            kokoroVoice = "af_heart",
            targetLanguage = "English"
        )

        /** The persona whose turn is in flight when the user switches away. */
        val OUTGOING_PROBE_PERSONA = probePersona("StopOnSwitchProbeOutgoing")

        /** The persona the user switches to mid-playback. */
        val INCOMING_PROBE_PERSONA = probePersona("StopOnSwitchProbeIncoming")
    }
}
