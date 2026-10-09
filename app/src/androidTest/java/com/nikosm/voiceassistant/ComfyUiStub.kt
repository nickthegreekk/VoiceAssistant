package com.nikosm.voiceassistant

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** One recorded ComfyUI request: method, path, decoded query, lowercased headers, raw body. */
internal data class RecordedComfyRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: String
)

internal const val COMFY_PROBE_USER = "nikos"
internal const val COMFY_PROBE_PASSWORD = "s3cret"
internal const val COMFY_STUB_PROMPT_ID = "5e11a1c0-0000-4000-8000-000000000001"
internal const val COMFY_STUB_FILENAME = "ComfyUI_00001_.png"

/**
 * Minimal loopback ComfyUI stub, the counterpart to [ImageGenStub].
 *
 * It answers the real routes and shapes: `GET /system_stats`, `POST /prompt` (200 with a prompt id,
 * or 400 with `node_errors`), `GET /history/{id}` (empty for the first [pollsBeforeDone] polls,
 * then the finished entry), `GET /queue`, `GET /view` (a real PNG), `POST /interrupt`. Every request
 * 401s unless the expected Basic header arrives, exactly as a ComfyUI behind a proxy would, and
 * every request is recorded with its method, path, decoded query, headers and body — so a test can
 * prove the path THAT was used and the header THAT was sent, not merely that something happened.
 *
 * The knobs ([pollsBeforeDone], [rejectWorkflow], [loseJob], [failJob], [imageBytes],
 * [imageContentType]) are plain vars so a test can set them up before the run starts.
 */
internal class ComfyUiStub(
    username: String? = COMFY_PROBE_USER,
    private val password: String? = COMFY_PROBE_PASSWORD
) : AutoCloseable {

    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    val url: String = "http://127.0.0.1:${socket.localPort}"
    val requests = CopyOnWriteArrayList<RecordedComfyRequest>()

    /** `/history` answers `{}` this many times before the finished entry appears. */
    var pollsBeforeDone: Int = 0

    /** `POST /prompt` answers 400 with a `node_errors` body. */
    var rejectWorkflow: Boolean = false

    /** `/history` stays empty forever and `/queue` reports the job in neither list. */
    var loseJob: Boolean = false

    /** `/history` reports the run as failed, with no image. */
    var failJob: Boolean = false

    /** What `/view` returns. Defaults to a real 1×1 PNG. */
    var imageBytes: ByteArray = PNG_1X1

    /** What `/view` claims to return, so a test can simulate a non-image answer. */
    var imageContentType: String = "image/png"

    /**
     * Omit `Content-Length` on responses, so the client cannot know the size up front and must
     * enforce its cap while STREAMING (a plain Content-Length would let it refuse before reading a
     * byte, which is a different code path). The connection closes after the body, which is how an
     * HTTP/1.1 response with no length signals its end.
     */
    var omitContentLength: Boolean = false

    val historyPolls = AtomicInteger(0)
    val interruptCalls = AtomicInteger(0)
    var lastPromptBody: String? = null
    var lastViewQuery: Map<String, String>? = null

    val promptId: String get() = COMFY_STUB_PROMPT_ID

    /** What a fully authorized request must carry, or null when the stub accepts anything. */
    val basicHeader: String? = username?.let { user ->
        "Basic " + android.util.Base64.encodeToString(
            "$user:${password.orEmpty()}".toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
    }

    init {
        val port = socket.localPort
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                Thread({ handle(client) }, "comfy-stub-$port").apply { isDaemon = true }.start()
            }
        }, "comfy-stub-accept-$port").apply { isDaemon = true }.start()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private companion object {
        /** A real 1×1 PNG — the app writes these bytes straight to a file. */
        val PNG_1X1: ByteArray = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
            android.util.Base64.DEFAULT
        )

        const val SYSTEM_STATS =
            """{"system":{"os":"linux","comfyui_version":"stub","python_version":"3.12"},"devices":[]}"""

        /** What a real ComfyUI answers for a workflow whose checkpoint does not exist. */
        const val NODE_ERRORS_400 =
            """{"error":{"type":"prompt_outputs_failed_validation","message":"Prompt outputs failed validation","details":""},"node_errors":{"6":{"errors":[{"type":"value_not_in_list","message":"Value not in list","details":"ckpt_name: 'missing.safetensors' not in [...]"}],"dependent_outputs":["9"],"class_type":"CheckpointLoaderSimple"}}}"""
    }

    private fun doneHistory(id: String) = """
        {"$id":{"prompt":[[1,"$id",{},{}]],
         "outputs":{"9":{"images":[{"filename":"$COMFY_STUB_FILENAME","subfolder":"","type":"output"}]}},
         "meta":{},
         "status":{"status_str":"success","completed":true,"messages":[]}}}
    """.trimIndent()

    private fun failedHistory(id: String) = """
        {"$id":{"outputs":{},"status":{"status_str":"error","completed":false,
         "messages":[["execution_error",{"node_id":"9","node_type":"SaveImage","exception_message":"stub failure"}]]}}}
    """.trimIndent()

    private fun queueWithJob() =
        """{"queue_running":[[0,"$COMFY_STUB_PROMPT_ID",{},{}]],"queue_pending":[]}"""

    private fun handle(connection: Socket) {
        try {
            connection.use { client ->
                val input = BufferedInputStream(client.inputStream)
                val requestLine = readStubLine(input) ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: return
                val target = parts.getOrNull(1) ?: "/"
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))
                val headers = mutableMapOf<String, String>()
                var contentLength = 0
                while (true) {
                    val line = readStubLine(input) ?: return
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val name = line.substring(0, idx).trim().lowercase()
                        val value = line.substring(idx + 1).trim()
                        headers[name] = value
                        if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
                    }
                }
                val bodyBytes = ByteArray(contentLength.coerceAtLeast(0))
                var read = 0
                while (read < bodyBytes.size) {
                    val n = input.read(bodyBytes, read, bodyBytes.size - read)
                    if (n <= 0) break
                    read += n
                }
                val body = String(bodyBytes, Charsets.UTF_8)
                requests += RecordedComfyRequest(method, path, query, headers, body)
                respond(client, method, path, query, headers, body)
            }
        } catch (_: Exception) {
            // A stub that dies mid-test must not take the instrumentation process with it.
        }
    }

    private fun respond(
        client: Socket,
        method: String,
        path: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: String
    ) {
        if (basicHeader != null && headers["authorization"] != basicHeader) {
            writeText(client, 401, "application/json", """{"detail":"Unauthorized"}""")
            return
        }
        when {
            path == COMFY_SYSTEM_STATS_PATH && method == "GET" ->
                writeText(client, 200, "application/json", SYSTEM_STATS)

            path == COMFY_PROMPT_PATH && method == "POST" -> {
                lastPromptBody = body
                if (rejectWorkflow) {
                    writeText(client, 400, "application/json", NODE_ERRORS_400)
                } else {
                    writeText(
                        client, 200, "application/json",
                        """{"prompt_id":"$COMFY_STUB_PROMPT_ID","number":1,"node_errors":{}}"""
                    )
                }
            }

            path == COMFY_QUEUE_PATH && method == "GET" ->
                writeText(
                    client, 200, "application/json",
                    if (loseJob) """{"queue_running":[],"queue_pending":[]}""" else queueWithJob()
                )

            path.startsWith("$COMFY_HISTORY_PATH/") && method == "GET" -> {
                val id = path.removePrefix("$COMFY_HISTORY_PATH/")
                val polls = historyPolls.incrementAndGet()
                when {
                    loseJob -> writeText(client, 200, "application/json", "{}")
                    failJob -> writeText(client, 200, "application/json", failedHistory(id))
                    polls <= pollsBeforeDone -> writeText(client, 200, "application/json", "{}")
                    else -> writeText(client, 200, "application/json", doneHistory(id))
                }
            }

            path == COMFY_INTERRUPT_PATH && method == "POST" -> {
                interruptCalls.incrementAndGet()
                writeText(client, 200, "application/json", "{}")
            }

            path == COMFY_VIEW_PATH && method == "GET" -> {
                lastViewQuery = query
                writeBytes(client, 200, imageContentType, imageBytes)
            }

            else -> writeBytes(client, 404, "text/plain", ByteArray(0))
        }
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val name = pair.substringBefore('=')
            val value = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }
                .getOrDefault(pair.substringAfter('=', ""))
            name to value
        }

    private fun writeText(client: Socket, status: Int, contentType: String, body: String) =
        writeBytes(client, status, contentType, body.toByteArray(Charsets.UTF_8))

    private fun writeBytes(client: Socket, status: Int, contentType: String, bytes: ByteArray) {
        val output = client.getOutputStream()
        val reason = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            else -> "Not Found"
        }
        output.write(
            buildString {
                append("HTTP/1.1 $status $reason\r\n")
                append("Content-Type: $contentType\r\n")
                if (!omitContentLength) append("Content-Length: ${bytes.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)
        )
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    private fun readStubLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val next = input.read()
            if (next == -1) return if (builder.isEmpty()) null else builder.toString()
            if (next == '\n'.code) return builder.toString().trimEnd('\r')
            builder.append(next.toChar())
        }
    }
}
