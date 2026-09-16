package com.nikosm.voiceassistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pins the voice-routing contract of REPLAYING an assistant message whose audio was never
 * persisted (the Stage-2 streaming Gateway pipeline synthesizes per chunk and never writes a
 * .wav, so `ChatMessage.audioFilePath` is null for those turns by design).
 *
 * The bug this guards: that case used to fall through to System TTS, so replaying a Gateway
 * persona's message played it in the phone's own voice instead of the persona's configured
 * Kokoro voice — silently misrepresenting the persona. The fix re-synthesizes the stored text
 * through the persona's own Gateway voice (`synthesizeGatewayTextForPlayback` →
 * `playChunkedTtsGateway`).
 *
 * Why instrumented, and why this assertion: the discriminated observable is *which server was
 * asked for audio*. The persona is pointed at a loopback stub `/synthesize` endpoint, so
 * "the Gateway voice was used" is the arrival of that request, and "System TTS was used" is
 * its absence — with the pre-fix code the stub sees zero requests (the turn went to
 * `speakTextOnDevice`), which is exactly how this test fails under mutation. Playing the
 * returned bytes is then confirmed by finding the chunk WAV the pipeline wrote, byte-for-byte,
 * in the service cache — and no assertion depends on MediaPlayer's callback timing.
 */
@RunWith(AndroidJUnit4::class)
class AssistantReplayRoutingInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var service: AssistantService
    private var connection: ServiceConnection? = null
    private var ttsStub: StubTtsServer? = null
    private var chunkFileWritten: File? = null
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
    }

    @After
    fun tearDown() {
        // Stop playback on Main (the chunked player lives there) before touching its file —
        // release() drops the MediaPlayer's fd, so deleting the chunk afterwards is safe.
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { service.stopAudio() }
        }
        chunkFileWritten?.let { runCatching { it.delete() } }
        if (silencedWasToggled) runCatching { service.toggleSilence() }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        ttsStub?.close()
        ttsStub = null
    }

    @Test
    fun gatewayReplayReSynthesizesWithThePersonasOwnVoiceInsteadOfSystemTts() {
        // Under the 80-char chunk minimum and free of sentence boundaries, so the pipeline
        // sends this text as ONE chunk — the request body must then carry it verbatim.
        val historyText = "Replay routing probe: gateway voice, not the phone's own engine"
        val stubAudio = silentWavBytes()
        val stub = StubTtsServer(stubAudio)
        ttsStub = stub

        val persona = Persona(
            name = "ReplayRoutingProbe",
            themeColor = Color(0xFF4ADE80),
            model = "probe-model",
            systemPrompt = "",
            backendUrl = stub.baseUrl,
            voiceMode = VoiceMode.GATEWAY,
            voiceEngine = "kokoro",
            kokoroVoice = "af_bella"
        )
        // A muted service bails before synthesis; that landing is covered elsewhere.
        if (service.silenced.value) {
            service.toggleSilence()
            silencedWasToggled = true
        }

        val generationBefore = service.ttsGeneration
        val message = ChatMessage("assistant", historyText) // audioFilePath == null by design
        service.replayMessageAudio(message, persona)

        assertTrue(
            "replay never reached the persona's Gateway /synthesize endpoint within 30s — the " +
                "turn was routed to a different voice engine (pre-fix: System TTS) instead of " +
                "re-synthesizing with the persona's configured voice",
            stub.synthesisArrived.await(30, TimeUnit.SECONDS)
        )
        assertEquals(
            "the persona's own Gateway endpoint must be the one used",
            "/synthesize",
            stub.lastPath
        )

        val body = stub.lastRequestBody ?: ""
        assertTrue("the replayed message text must be sent: $body", body.contains(historyText))
        assertTrue(
            "the persona's configured Kokoro voice must be sent: $body",
            body.contains("af_bella")
        )
        assertTrue("the persona's configured engine must be sent: $body", body.contains("kokoro"))

        // The pipeline's own artifact is the second half of the proof: the exact bytes the
        // stub returned were written as chunk 0 of a fresh generation, i.e. they were what
        // got played — not merely fetched.
        val written = waitUntilChunkFile(stubAudio)
        assertNotNull("no chunk WAV carrying the Gateway's bytes appeared in the cache", written)
        chunkFileWritten = written
        assertTrue(
            "the chunk file must belong to the generation this replay started " +
                "(was ${generationBefore}, now ${service.ttsGeneration}): ${written!!.name}",
            service.ttsGeneration > generationBefore
        )
    }

    /** Returns the chunk-0 file whose bytes match what the stub served. */
    private fun waitUntilChunkFile(expected: ByteArray, timeoutMs: Long = 20_000): File? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val match = context.cacheDir.listFiles { f ->
                f.name.startsWith("tts_chunk_") && f.name.endsWith("_0.wav") &&
                    f.length().toInt() == expected.size && f.readBytes().contentEquals(expected)
            }?.maxByOrNull { it.lastModified() }
            if (match != null) return match
            Thread.sleep(50)
        }
        return null
    }
}

/**
 * A loopback stand-in for one persona's Gateway TTS endpoint. It answers any request with a
 * canned WAV (plus optional word timestamps, mirroring the server's
 * `X-Word-Timestamps-B64` header) and records what was asked of it, so a test can tell
 * *which* voice engine served a playback — the whole point of the routing contract above.
 *
 * Plain HTTP on 127.0.0.1, like the app's LAN gateway deployments and the cloud-fetch stubs.
 */
private class StubTtsServer(
    private val audioBytes: ByteArray,
    private val timestampsJson: String? = null
) {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    /** e.g. `http://127.0.0.1:41234` — the app appends `/synthesize` itself. */
    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    /** Fires once a full request (head + body) has been read. */
    val synthesisArrived = CountDownLatch(1)

    @Volatile
    var lastPath: String? = null
        private set

    /** The raw multipart body, verbatim — the text/engine/voice fields are asserted on it. */
    @Volatile
    var lastRequestBody: String? = null
        private set

    init {
        val port = server.localPort
        Thread({
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break // close() tore the listener down
                }
                Thread({ serve(client) }, "stub-tts-$port").apply { isDaemon = true }.start()
            }
        }, "stub-tts-accept-$port").apply { isDaemon = true }.start()
    }

    fun close() {
        runCatching { server.close() }
    }

    private fun serve(client: Socket) {
        try {
            client.use { socket ->
                val head = readRequestHead(socket.getInputStream())
                lastPath = head.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)
                lastRequestBody = readBody(socket.getInputStream(), head)
                synthesisArrived.countDown()
                writeResponse(socket.getOutputStream())
            }
        } catch (e: IOException) {
            // The test closed the socket; nothing to report.
        }
    }

    private fun readRequestHead(input: InputStream): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next == -1) break
            head.append(next.toChar())
        }
        return head.toString()
    }

    private fun readBody(input: InputStream, head: String): String {
        val length = head.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n == -1) break
            read += n
        }
        return String(body, 0, read, Charsets.UTF_8)
    }

    private fun writeResponse(output: OutputStream) {
        val tsHeader = timestampsJson?.let {
            "X-Word-Timestamps-B64: " +
                Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "\r\n"
        } ?: ""
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: audio/wav\r\n" +
            "Content-Length: ${audioBytes.size}\r\n" +
            tsHeader +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(audioBytes)
        output.flush()
    }
}

/**
 * A real, MediaPlayer-playable WAV of silence (0.25s, 16-bit mono @ 24kHz). Its size is
 * unique among the app's chunk files, which is what lets the test recognise *its* bytes in
 * the cache without depending on generation numbering.
 */
private fun silentWavBytes(durationMs: Int = 250, sampleRate: Int = 24000): ByteArray {
    val samples = sampleRate * durationMs / 1000
    val dataSize = samples * 2
    fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    val out = ByteArrayOutputStream(44 + dataSize)
    out.write("RIFF".toByteArray(Charsets.US_ASCII))
    out.write(le32(36 + dataSize))
    out.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
    out.write(le32(16))
    out.write(le16(1))              // PCM
    out.write(le16(1))              // mono
    out.write(le32(sampleRate))
    out.write(le32(sampleRate * 2)) // byte rate
    out.write(le16(2))              // block align
    out.write(le16(16))             // bits per sample
    out.write("data".toByteArray(Charsets.US_ASCII))
    out.write(le32(dataSize))
    repeat(dataSize) { out.write(0) }
    return out.toByteArray()
}