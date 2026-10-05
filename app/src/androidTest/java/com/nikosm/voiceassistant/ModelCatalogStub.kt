package com.nikosm.voiceassistant

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** One recorded request: method, path and raw body. */
internal data class RecordedCatalogRequest(val method: String, val path: String, val body: String)

/**
 * Minimal loopback Ollama stub for the model catalog: answers GET /api/tags and
 * POST /api/show, and records every request so a test can prove /api/show was called for
 * EVERY model and not lazily.
 *
 * Same process as the app on 127.0.0.1, so no adb reverse is needed (the shape
 * OllamaSuggestionStub established).
 *
 * @param tagsBody what GET /api/tags returns.
 * @param showBodies per-model /api/show bodies. A model with no entry here is answered
 *   with a 404, which is how "show fails for one model only" is exercised.
 * @param failTags when true, /api/tags answers 500 — the HTTP-error case.
 * @param tagsStatus the status /api/tags answers with when [failTags] is false.
 * @param openAiModelsBody what GET /v1/models returns, or null for "no such route" (404),
 *   which is the gateway case /v1/models exists to cover and the one that makes a fetch
 *   fall through from /api/tags.
 */
internal class ModelCatalogStub(
    private val tagsBody: String,
    private val showBodies: Map<String, String> = emptyMap(),
    private val failTags: Boolean = false,
    private val tagsStatus: Int = 200,
    private val openAiModelsBody: String? = null
) : AutoCloseable {
    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val url: String = "http://127.0.0.1:${socket.localPort}"
    val requests = CopyOnWriteArrayList<RecordedCatalogRequest>()

    /** Flip mid-test to simulate a server going away between a load and a refresh. */
    @Volatile var failTagsNow: Boolean = failTags

    /**
     * Flip mid-test to simulate the user removing their last model: /api/tags keeps
     * answering 200, but with an empty list. Distinct from [failTagsNow], which is the
     * server breaking — the whole point being that the two must not be reported alike.
     */
    @Volatile var emptyTagsNow: Boolean = false

    /** Count of models whose /api/show is currently in flight, for concurrency checks. */
    val showCalls = ConcurrentHashMap<String, Int>()

    init {
        val port = socket.localPort
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                Thread({ handle(client) }, "catalog-stub-$port").apply { isDaemon = true }.start()
            }
        }, "catalog-stub-accept-$port").apply { isDaemon = true }.start()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun handle(connection: Socket) {
        try {
            connection.use { client ->
                val input = BufferedInputStream(client.inputStream)
                val requestLine = readCatalogLine(input) ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: return
                val path = parts.getOrNull(1) ?: "/"
                var contentLength = 0
                while (true) {
                    val line = readCatalogLine(input) ?: return
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0 && line.substring(0, idx).trim().lowercase() == "content-length") {
                        contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
                    }
                }
                val bodyBytes = ByteArray(contentLength.coerceAtLeast(0))
                var read = 0
                while (read < bodyBytes.size) {
                    val n = input.read(bodyBytes, read, bodyBytes.size - read)
                    if (n <= 0) break
                    read += n
                }
                requests += RecordedCatalogRequest(method, path, String(bodyBytes, Charsets.UTF_8))

                val (status, body) = respond(method, path, String(bodyBytes, Charsets.UTF_8))
                writeResponse(client, status, body)
            }
        } catch (_: Exception) {
            // A stub that dies mid-test must not take the instrumentation process with it.
        }
    }

    private fun respond(method: String, path: String, body: String): Pair<Int, String> {
        if (path.endsWith("/api/tags") && method == "GET") {
            if (failTagsNow) return 500 to "{\"error\":\"server is down\"}"
            if (tagsStatus != 200) return tagsStatus to "{\"error\":\"nope\"}"
            if (emptyTagsNow) return 200 to "{\"models\":[]}"
            return 200 to tagsBody
        }
        if (path.endsWith("/v1/models") && method == "GET") {
            // Null body == this stub is not an OpenAI-shaped gateway, which is the 404 the
            // fetch falls through on.
            return if (openAiModelsBody == null) 404 to "{\"error\":\"no route\"}"
            else 200 to openAiModelsBody
        }
        if (path.endsWith("/api/show") && method == "POST") {
            val name = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            val response = name?.let { showBodies[it] }
            return if (response == null) 404 to "{\"error\":\"model not found\"}" else 200 to response
        }
        return 404 to "{\"error\":\"no route\"}"
    }

    private fun writeResponse(client: Socket, status: Int, body: String) {
        val out = client.getOutputStream()
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write(
            ("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
        )
        out.write(bytes)
        out.flush()
    }

    private fun readCatalogLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().removeSuffix("\r")
            sb.append(c.toChar())
        }
    }
}
