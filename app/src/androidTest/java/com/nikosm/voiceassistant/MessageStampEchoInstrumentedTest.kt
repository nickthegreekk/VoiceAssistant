package com.nikosm.voiceassistant

import android.os.SystemClock
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The doubled-stamp report, end to end on a device.
 *
 * A loopback Ollama stands in for a local model and replies with a reply that IMITATES the
 * stamp format the app just showed it (`[stamp][stamp] hello there`) — the shape a real
 * local model produces when it copies the transcript. Two things are then pinned:
 *
 *  1. what the app STORES and displays is the bare reply, and
 *  2. the next request carries EXACTLY ONE stamp for that message.
 *
 * The second is the one that matters to a user: before the fix the stored echo was stamped
 * again on injection, so the model saw `[real][echo][echo] hello` and could keep copying.
 *
 * The pure half (recognizer, stripper, display hold) is `ModelStampEchoTest` on the JVM.
 */
@RunWith(AndroidJUnit4::class)
class MessageStampEchoInstrumentedTest {

    @get:Rule
    val serviceIsolation = ServiceIsolationRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var service: AssistantService
    private var connection: android.content.ServiceConnection? = null

    private val PROBE_SERVER = "StampEchoServer"
    private val REPLY = "hello there"
    private val STALE_REPLY = "old answer"
    private val STALE_STAMPED_REPLY = "[Oct 3, 2026 09:00] $STALE_REPLY"

    /** What a local model produces when it copies the stamp format it was shown. */
    private val ECHO = "[Oct 3, 2026 12:29][Oct 3, 2026 12:29] $REPLY"

    /** One model-facing stamp at the start of the content, shaped like the real thing. */
    private val STAMP_AT_START = Regex("""\[[^\]]*\d{1,2}:\d{2}]""")

    @Before
    fun bind() {
        val bound = java.util.concurrent.CountDownLatch(1)
        var s: AssistantService? = null
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(n: android.content.ComponentName?, b: android.os.IBinder?) {
                s = (b as AssistantService.AssistantBinder).getService(); bound.countDown()
            }
            override fun onServiceDisconnected(n: android.content.ComponentName?) {}
        }
        connection = conn
        assertTrue(context.bindService(
            android.content.Intent(context, AssistantService::class.java), conn,
            android.content.Context.BIND_AUTO_CREATE))
        assertTrue(bound.await(30, java.util.concurrent.TimeUnit.SECONDS))
        service = s!!
        // Snapshotted HERE rather than in a second @Before: JUnit does not order them, and
        // this one needs the bound service.
        personasBefore = service.personas.value
        serversBefore = service._ollamaBaseUrls.value
    }

    private var personasBefore: List<Persona> = emptyList()
    private var serversBefore: List<ServerConfig> = emptyList()

    @org.junit.After
    fun unbind() {
        // Same contract as every other service-touching class: put back what this test
        // changed, so it cannot hand a probe persona to the next one.
        runCatching {
            onMain {
                service.personas.value.indices.reversed().forEach { service.removePersona(it) }
                personasBefore.forEach { service.addPersona(it) }
                service._ollamaBaseUrls.value = serversBefore
                service.saveSettings()
            }
        }
        runCatching { InstrumentationRegistry.getInstrumentation().runOnMainSync { service.stopAudio() } }
        connection?.let { runCatching { context.unbindService(it) } }
    }

    private fun onMain(b: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(b)

    @Test
    fun aReplyThatEchoesTheStampIsStoredCleanAndReStampedExactlyOnce() {
        val bodies = mutableListOf<String>()
        Stub(reply = ECHO) { bodies += it }.use { stub ->
            val persona = Persona(
                name = "StampEchoProbe", themeColor = Color(0xFF4ADE80),
                model = "[$PROBE_SERVER] llama3", systemPrompt = "You are terse.",
                numCtx = 4096, maxTokens = 256
            )
            onMain {
                // Drop any probe server a PREVIOUS run of this test registered: its port is
                // dead now, and addOllamaBase is URL-guarded, so re-adding the name would be
                // a no-op and the turn would dial a closed port.
                service._ollamaBaseUrls.value =
                    service._ollamaBaseUrls.value.filterNot { it.name == PROBE_SERVER }
                service.addOllamaBase(PROBE_SERVER, stub.url)
                service.addPersona(persona)
                service.currentPersonaName = persona.name
                service.messagesOwnerName = persona.name
                // The middle turn carries a stray stamp ALREADY IN ITS STORED TEXT — what an
                // older build, or an echo that reached the transcript some other way, leaves
                // behind. Nothing migrates it; the point is that injection must not stack on
                // top of it either.
                service._messages.value = listOf(
                    ChatMessage("user", "first question", timestamp = System.currentTimeMillis()),
                    ChatMessage("assistant", STALE_STAMPED_REPLY, timestamp = System.currentTimeMillis())
                )
            }
            val before = service._messages.value.size
            onMain { service.sendTextMessageToServer("first question", persona) }
            awaitAnswer(before)
            val stored = service._messages.value.last { it.role == "assistant" }.text
            assertEquals(
                "the model's own stamps must never reach the transcript or the screen: [$stored]",
                REPLY, stored
            )

            val before2 = service._messages.value.size
            onMain { service.sendTextMessageToServer("second question", persona) }
            awaitAnswer(before2)
            val msgs = JSONObject(bodies.last()).getJSONArray("messages")
            val sent = (1 until msgs.length() - 1).map { msgs.getJSONObject(it).getString("content") }
            // (a) the OLD stored reply: injection must add exactly one stamp to it, never two.
            val stale = sent.first { it.endsWith(STALE_REPLY) }
            assertEquals(
                "a stored reply that already carries a stray stamp must be re-stamped ONCE, " +
                    "not stacked: $stale",
                1, STAMP_AT_START.findAll(stale).count()
            )
            assertEquals(STALE_REPLY, stale.substringAfter("] "))

            // (b) the fresh echo: stored clean, and stamped once on the way out.
            val answer = sent.first { it.endsWith(REPLY) }
            val stamps = STAMP_AT_START.findAll(answer).count()
            assertEquals(
                "the model must be shown exactly one true stamp per message, got: $answer",
                1, stamps
            )
            assertEquals(
                "and the reply behind it must be the clean one, got: $answer",
                REPLY, answer.substringAfter("] ")
            )
            val systemPrompt = msgs.getJSONObject(0).getString("content")
            assertTrue(
                "a stamped Direct-Ollama request must tell the model who owns the brackets",
                systemPrompt.contains("Never write a stamp")
            )
        }
    }

    /**
     * Waits for a FINISHED answer, not merely for the count of assistant messages to grow:
     * the streaming placeholder is appended empty on the first chunk, so counting returns
     * instantly and asserts against an empty bubble while the reply is still arriving.
     */
    /** [before] is a message INDEX (the transcript size), not an assistant count. */
    private fun awaitAnswer(before: Int, timeoutMs: Long = 30_000) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        fun settled() = service._messages.value
            .drop(before)
            .firstOrNull { it.role == "assistant" && (it.text.isNotBlank() || it.isError) }
        while (SystemClock.uptimeMillis() < deadline) {
            if (settled() != null) return
            Thread.sleep(25)
        }
        assertTrue(
            "the turn never produced an answer (messages=" +
                "${service._messages.value.map { it.role + ":" + it.text.take(40) }})",
            settled() != null
        )
    }

    /** Minimal loopback Ollama stub: fixed reply, taps every request body. */
    private class Stub(val reply: String, val tap: (String) -> Unit) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url: String get() = "http://127.0.0.1:${socket.localPort}"

        init {
            thread(isDaemon = true, name = "diag-stub") {
                while (!socket.isClosed) {
                    try { socket.accept().use { handle(it) } } catch (_: Exception) {}
                }
            }
        }

        private fun handle(c: Socket) {
            val input = BufferedInputStream(c.inputStream)
            val headers = StringBuilder()
            while (true) {
                val l = readLine(input) ?: return
                if (l.isEmpty()) break
                headers.append(l).append('\n')
            }
            val len = headers.toString().lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val body = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = input.read(body, read, len - read)
                if (n <= 0) break
                read += n
            }
            tap(String(body, 0, maxOf(read, 0), Charsets.UTF_8))
            val payload = listOf(
                JSONObject().put("model", "probe")
                    .put("message", JSONObject().put("content", reply)).toString(),
                JSONObject().put("done", true)
                    .put("message", JSONObject().put("content", "")).toString()
            ).joinToString("\n")
            val bytes = payload.toByteArray()
            c.getOutputStream().write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/x-ndjson\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes
            )
            c.getOutputStream().flush()
        }

        private fun readLine(input: BufferedInputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val ch = input.read()
                if (ch < 0) return if (sb.isEmpty()) null else sb.toString()
                if (ch == 10) return sb.toString().removeSuffix("\r")
                sb.append(ch.toChar())
            }
        }

        override fun close() { runCatching { socket.close() } }
    }
}
