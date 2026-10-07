package com.nikosm.voiceassistant

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** One recorded image-gen request: method, path, lowercased header names, raw body. */
internal data class RecordedImageGenRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String
)

internal const val IMAGE_GEN_PROBE_USER = "nikos"
internal const val IMAGE_GEN_PROBE_PASSWORD = "s3cret"

/**
 * Minimal loopback A1111 stub: answers `GET /sdapi/v1/sd-models` and
 * `POST /sdapi/v1/txt2img` ONLY when the request carries the expected Basic
 * Authorization header; anything unauthorised gets 401, exactly as A1111 started with
 * `--api-auth` answers a bare generation. Records every request's method/path/headers/
 * body, so a test can prove which header reached the endpoint.
 *
 * Same process as the app on 127.0.0.1, so no adb reverse is needed (the shape
 * OllamaSuggestionStub established). The base64 payload is a valid, decodable string —
 * the app only decodes and writes it to a file, it is never rendered.
 */
internal class ImageGenStub(
    username: String = IMAGE_GEN_PROBE_USER,
    password: String = IMAGE_GEN_PROBE_PASSWORD
) : AutoCloseable {
    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))

    val url: String = "http://127.0.0.1:${socket.localPort}"
    val requests = CopyOnWriteArrayList<RecordedImageGenRequest>()

    /** The header a fully authorized request must carry. */
    val basicHeader: String = "Basic " + android.util.Base64.encodeToString(
        "$username:$password".toByteArray(Charsets.UTF_8),
        android.util.Base64.NO_WRAP
    )

    init {
        val port = socket.localPort
        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                Thread({ handle(client) }, "image-gen-stub-$port").apply { isDaemon = true }.start()
            }
        }, "image-gen-stub-accept-$port").apply { isDaemon = true }.start()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun handle(connection: Socket) {
        try {
            connection.use { client ->
                val input = BufferedInputStream(client.inputStream)
                val requestLine = readStubLine(input) ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: return
                val path = parts.getOrNull(1) ?: "/"
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
                requests += RecordedImageGenRequest(method, path, headers, String(bodyBytes, Charsets.UTF_8))

                val authorized = headers["authorization"] == basicHeader
                val payload: Pair<Int, String>? = when {
                    path.startsWith(IMAGE_GEN_SD_MODELS_PATH) ->
                        if (authorized) 200 to "{\"models\":[{\"title\":\"probe-model\",\"model_name\":\"probe-model\"}]}"
                        else 401 to "{\"detail\":\"Unauthorized\"}"
                    path.startsWith(IMAGE_GEN_TXT2IMG_PATH) && method == "POST" ->
                        // base64("hello") — decodable bytes, never rendered.
                        if (authorized) 200 to "{\"images\":[\"aGVsbG8=\"]}"
                        else 401 to "{\"detail\":\"Unauthorized\"}"
                    else -> null
                }
                writeResponse(client, payload)
            }
        } catch (_: Exception) {
            // A stub that dies mid-test must not take the instrumentation process with it.
        }
    }

    private fun writeResponse(client: Socket, payload: Pair<Int, String>?) {
        val output = client.getOutputStream()
        if (payload == null) {
            output.write(
                "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
        } else {
            val (status, body) = payload
            val bytes = body.toByteArray(Charsets.UTF_8)
            val reason = if (status == 200) "OK" else "Unauthorized"
            output.write(
                (
                    "HTTP/1.1 $status $reason\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
            )
            output.write(bytes)
        }
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
