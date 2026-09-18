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

/**
 * H2 — a slow "Test Voice" fetch must not corrupt a newer turn that started meanwhile.
 *
 * The bug: `testGatewayVoice`'s `finally` reset the assistant state to IDLE whenever it
 * found THINKING, with NO ownership check — unlike the two sibling finally blocks
 * (`AssistantServiceChat.kt` voice + cloud flows) which both guard on
 * `isChatRequestCurrent(generation)`. So a "Test Voice" whose `/synthesize` fetch was still
 * in flight when a real turn started would, on completion, force the NEWER turn out of
 * THINKING and repaint "Ready to help". Worse: because the newer turn's own finally then
 * failed its `_state == THINKING` guard, a turn that had retained the audio focus
 * (stopRecording() keeps it through THINKING) never abandoned it — other apps stayed ducked
 * until the process died.
 *
 * How this proves it on-device: both requests are held inside loopback stub servers, so
 * "the voice test completes while the real turn is still in flight" is ENGINEERED rather
 * than assumed. The real turn is a production `sendTextMessageToServer` cloud turn pointed
 * at a second held stub, and the audio focus is requested through the production
 * `requestAssistantFocus()` — the same focus `stopRecording()` retains for a voice turn.
 *
 * What is asserted is the state/focus ownership contract: the superseded voice test leaves
 * the newer turn THINKING, and the newer turn's own terminal path still releases its focus.
 */
@RunWith(AndroidJUnit4::class)
class GatewayTestVoiceSupersedeInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null

    private var serverBasesBefore: List<ServerConfig> = emptyList()
    private var customCloudApisBefore: List<CloudApiSetting> = emptyList()
    private var serverStatusBefore: Map<String, String> = emptyMap()
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
        runCatching { instrumentation.runOnMainSync { service.abandonAssistantFocus() } }
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
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
    }

    // ---- the repro ------------------------------------------------------------------------

    @Test
    fun supersededVoiceTestDoesNotForceTheNewerTurnsStateToIdle() {
        HeldServer(holdResponse = true, responseBody = silentWav()).use { gateway ->
            HeldServer(
                holdResponse = true,
                statusCode = 500,
                responseBody = PROBE_ERROR_BODY.toByteArray()
            ).use { cloud ->
                registerGateway(gateway)
                registerCloudProvider(cloud)
                val persona = cloudPersona()
                activate(persona)

                beginVoiceTest(gateway)
                startRealTurn(cloud, persona)

                // The voice test finishes now — while the real turn is still parked at the
                // cloud stub, which cannot land IDLE by itself.
                gateway.releaseResponse()
                assertTrue(
                    "the held voice-test request never completed on the wire",
                    gateway.responseSent.await(20, TimeUnit.SECONDS)
                )

                awaitNeverIdleFor(
                    2_000,
                    "H2: the superseded voice test forced the newer turn out of THINKING"
                )
                assertEquals(
                    "H2: the newer turn must still own THINKING while its request is in flight",
                    AssistantState.THINKING,
                    service.assistantState.value
                )

                // Let the real turn fail so its own finally runs and nothing stays in flight.
                cloud.releaseResponse()
                awaitService(20_000, "the newer turn never finished") {
                    service.assistantState.value == AssistantState.IDLE
                }
            }
        }
    }

    @Test
    fun supersededVoiceTestDoesNotLeakTheNewerTurnsRetainedFocus() {
        HeldServer(holdResponse = true, responseBody = silentWav()).use { gateway ->
            HeldServer(
                holdResponse = true,
                statusCode = 500,
                responseBody = PROBE_ERROR_BODY.toByteArray()
            ).use { cloud ->
                registerGateway(gateway)
                registerCloudProvider(cloud)
                val persona = cloudPersona()
                activate(persona)

                beginVoiceTest(gateway)
                startRealTurn(cloud, persona)

                gateway.releaseResponse()
                assertTrue(
                    "the held voice-test request never completed on the wire",
                    gateway.responseSent.await(20, TimeUnit.SECONDS)
                )
                cloud.releaseResponse()
                awaitService(20_000, "the newer turn never finished") {
                    service.assistantState.value == AssistantState.IDLE
                }

                assertTrue(
                    "the newer turn's own terminal path never ran (no error bubble)",
                    service.messages.value.any { it.isError }
                )
                assertNull(
                    "H2: the newer turn's finally skipped its `_state == THINKING` guard because the " +
                        "superseded voice test had already forced IDLE — so the focus retained through " +
                        "THINKING was never abandoned and other apps stay ducked",
                    service.audioFocusRequest
                )
            }
        }
    }

    /** Control: with nothing superseding it, the same flow must still land IDLE. */
    @Test
    fun voiceTestStillLandsIdleWhenNothingSupersedesIt() {
        HeldServer(holdResponse = true, responseBody = silentWav()).use { gateway ->
            registerGateway(gateway)
            activate(cloudPersona())

            beginVoiceTest(gateway)
            gateway.releaseResponse()

            awaitService(30_000, "an unsuperseded voice test never landed IDLE") {
                service.assistantState.value == AssistantState.IDLE
            }
        }
    }

    // ---- harness --------------------------------------------------------------------------

    private fun registerGateway(stub: HeldServer) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service._serverBases.value =
                serverBasesBefore.filterNot { it.name == PROBE_GATEWAY } +
                    ServerConfig(name = PROBE_GATEWAY, url = stub.url)
        }
    }

    private fun registerCloudProvider(stub: HeldServer) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service._customCloudApis.value =
                customCloudApisBefore.filterNot { it.name == PROBE_PROVIDER } +
                    CloudApiSetting(
                        name = PROBE_PROVIDER,
                        baseUrl = stub.url,
                        // Icon "C" is the no-API-key probe provider shape.
                        icon = "C",
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

    /**
     * Starts the production "Test Voice" flow and proves it is genuinely in flight (THINKING
     * plus an arrived, held request) before returning — so the caller's "a newer turn starts
     * meanwhile" ordering is engineered, not hoped for.
     */
    private fun beginVoiceTest(gateway: HeldServer) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            service.testAudio(
                PROBE_VOICE_TEST_TEXT,
                VoiceMode.GATEWAY,
                gateway.url,
                "English",
                false,
                "kokoro",
                "af_heart"
            )
        }
        awaitService(20_000, "the voice test never entered THINKING") {
            service.assistantState.value == AssistantState.THINKING
        }
        assertTrue(
            "the voice test never reached the gateway",
            gateway.requestArrived.await(20, TimeUnit.SECONDS)
        )
    }

    /**
     * Starts the newer, real turn through the production text entry point (which bumps the
     * chat generation, superseding the voice test) while retaining the audio focus the way a
     * voice turn does (`stopRecording()` keeps it through THINKING).
     */
    private fun startRealTurn(cloud: HeldServer, persona: Persona) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val granted = service.requestAssistantFocus()
            assertTrue("the device refused the audio-focus request this test depends on", granted)
            assertNotNull("a granted request must be recorded on the service", service.audioFocusRequest)
            service.sendTextMessageToServer(PROBE_REAL_TURN_TEXT, persona)
        }
        awaitService(20_000, "the real turn never entered THINKING") {
            service.assistantState.value == AssistantState.THINKING
        }
        assertTrue(
            "the real turn never reached the cloud provider",
            cloud.requestArrived.await(20, TimeUnit.SECONDS)
        )
    }

    /** Fails as soon as the state reaches IDLE anywhere inside [millis]. */
    private fun awaitNeverIdleFor(millis: Long, failure: String) {
        val deadline = SystemClock.elapsedRealtime() + millis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (service.assistantState.value == AssistantState.IDLE) {
                assertTrue("$failure (IDLE observed inside the ${millis}ms window)", false)
            }
            SystemClock.sleep(20)
        }
    }

    private fun awaitService(timeoutMs: Long, failure: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("$failure (state=${service.assistantState.value})", condition())
    }

    private fun cloudPersona() = Persona(
        name = PROBE_PERSONA,
        themeColor = PROBE_COLOR,
        model = "[$PROBE_PROVIDER] probe-model",
        systemPrompt = "Supersede probe persona.",
        isCloud = true,
        providerIcon = "C",
        backendUrl = "",
        allowGatewayFailover = false,
        voiceMode = VoiceMode.NONE,
        enableThinking = false,
        webSearchEnabled = false,
        ragEnabled = false,
        maxTokens = 256,
        numCtx = 8192
    )

    private companion object {
        const val PROBE_PERSONA = "TestVoiceSupersedeProbePersona"
        const val PROBE_GATEWAY = "TestVoiceSupersedeProbeGateway"
        const val PROBE_PROVIDER = "TestVoiceSupersedeProbeCloud"
        const val PROBE_VOICE_TEST_TEXT = "voice test probe"
        const val PROBE_REAL_TURN_TEXT = "superseding real turn probe"
        const val PROBE_ERROR_BODY = "{\"error\":{\"message\":\"probe failure\"}}"

        val PROBE_COLOR = Color(0xFF4ADE80)

        /**
         * One second of digital silence as a canonical 44-byte RIFF/WAVE (16-bit PCM mono,
         * 8 kHz). Real audio is irrelevant here — the voice test merely has to complete with
         * a genuine body so the flow reaches the pre-playback generation check.
         */
        fun silentWav(): ByteArray {
            val sampleRate = 8_000
            val dataSize = sampleRate * 2 // 1s, 16-bit mono
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
            shortLe(20, 1)          // PCM
            shortLe(22, 1)          // mono
            intLe(24, sampleRate)
            intLe(28, sampleRate * 2)
            shortLe(32, 2)          // block align
            shortLe(34, 16)         // bits per sample
            ascii(36, "data")
            intLe(40, dataSize)
            return wav
        }
    }
}
/**
 * A loopback stub that holds its response until [releaseResponse] is called, so a request
 * can be parked mid-flight for as long as a test needs. [requestArrived] fires once the
 * request head + body have been read (the request is genuinely in flight); [responseSent]
 * once the scripted reply has been written and the socket closed.
 */
private class HeldServer(
    private val statusCode: Int = 200,
    private val responseBody: ByteArray = "{}".toByteArray(),
    private val contentType: String = "application/json",
    private val holdResponse: Boolean = false
) : AutoCloseable {

    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val released = CountDownLatch(1)

    val url: String = "http://127.0.0.1:${socket.localPort}"
    val requestArrived = CountDownLatch(1)
    val responseSent = CountDownLatch(1)
    val requestCount = AtomicInteger()

    init {
        val port = socket.localPort
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break // close() tore the listener down
                }
                Thread({ handle(client) }, "held-server-$port").apply { isDaemon = true }.start()
            }
        }, "held-server-accept-$port").apply { isDaemon = true }.start()
    }

    fun releaseResponse() = released.countDown()

    override fun close() {
        released.countDown() // unblock any held worker so the test cannot leak a thread
        runCatching { socket.close() }
    }

    private fun handle(connection: Socket) {
        try {
            connection.use { client ->
                val input = BufferedInputStream(client.inputStream)
                val head = StringBuilder()
                while (true) {
                    val line = readAsciiLine(input) ?: return
                    if (line.isEmpty()) break
                    head.append(line).append('\n')
                }
                val headText = head.toString()
                val contentLength = headText.lineSequence()
                    .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                // Drain the body: an unread body makes the client see a reset connection.
                var remaining = contentLength.toLong()
                val buffer = ByteArray(8_192)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    remaining -= read
                }
                requestCount.incrementAndGet()
                requestArrived.countDown()

                if (holdResponse) released.await(60, TimeUnit.SECONDS)

                val reason = if (statusCode == 200) "OK" else "Stub Error"
                val output = client.getOutputStream()
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
            }
        } catch (e: Exception) {
            // The test closed the socket mid-flight; nothing to report.
        } finally {
            responseSent.countDown()
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