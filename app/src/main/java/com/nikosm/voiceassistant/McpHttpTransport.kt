package com.nikosm.voiceassistant

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource

/**
 * Streamable HTTP transport: JSON-RPC messages are POSTed to the server's URL, and the answer is
 * either a JSON body or an SSE stream (PLAN.md §10/M9).
 *
 * ## The two channels
 *
 * A **POST** carries one outgoing message. The server may answer with `application/json` (one
 * message) or `text/event-stream` (a stream of messages, of which ours is one); both are read here
 * and every message is forwarded as text. The `Mcp-Session-Id` the server returns on any response is
 * remembered and sent on every later request — the protocol's session.
 *
 * A **GET** event stream is opened once, after the session id is known. It carries server-initiated
 * messages, of which this client acts on `notifications/tools/list_changed`; a server that refuses
 * the GET (405 or a non-SSE answer) is not an error — tool changes then go unseen until the next
 * connection, and that is logged at debug.
 *
 * ## Failure is the whole transport's — except one request's read timeout
 *
 * A socket error or a non-2xx answer is not attributable to one request from here — this class does
 * not parse ids — so it reports the transport closed once, and the registry fails every request
 * waiting on it with that sentence. **A `SocketTimeoutException` is the exception**: it is one
 * request's read deadline, the registry already bounds the logical call, and killing a healthy
 * connection because one tool was slow would fail every other pending request with it. JSON-RPC
 * errors inside a 200 body are the server's answer and travel to the registry as an ordinary
 * message.
 *
 * OkHttp rather than `java.net.http`: it is already in the packaged image (the cloud providers use
 * it) and its SSE bodies can be read line by line without pulling another module into the jlink
 * list. The timeout split matters: 30 s for a request, **no read timeout** on the event stream.
 */
class McpHttpTransport(
    private val server: McpServerConfig,
    private val logger: AppLogger = AndroidAppLogger,
    readTimeoutMillis: Long = 30_000L,
) : McpTransport {

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "mcp-http-${server.id}").apply { isDaemon = true }
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()
    private val streamClient = client.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()

    /** Every call in flight, so [close] can cancel a blocked SSE read. */
    private val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())

    @Volatile
    private var closed = false

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var streamCall: Call? = null

    private val streamStarted = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)

    @Volatile
    private var onMessage: ((String) -> Unit)? = null

    @Volatile
    private var onClosed: ((String) -> Unit)? = null

    override fun start(onMessage: (String) -> Unit, onClosed: (String) -> Unit) {
        val uri = try {
            URI(server.url)
        } catch (e: Exception) {
            throw McpTransportException("invalid URL for '${server.name}': ${server.url}", e)
        }
        if (uri.scheme != "http" && uri.scheme != "https") {
            throw McpTransportException("'${server.name}' must use http or https, not '${uri.scheme}'")
        }
        this.onMessage = onMessage
        this.onClosed = onClosed
    }

    override fun send(message: String) {
        if (closed) throw McpTransportException("the server '${server.name}' is not running")
        try {
            executor.execute { post(message) }
        } catch (e: RejectedExecutionException) {
            // close() shut the executor between the check and the dispatch. The caller deserves
            // the transport's own sentence, not the executor's unchecked exception.
            throw McpTransportException("the server '${server.name}' is not running", e)
        }
    }

    private fun post(message: String) {
        val builder = Request.Builder()
            .url(server.url)
            .post(message.toRequestBody(JSON_MEDIA))
            // Both are acceptable per the protocol; the server picks one per response.
            .header("Accept", "application/json, text/event-stream")
        server.headers.forEach { (name, value) -> builder.header(name, value) }
        sessionId?.let { builder.header(SESSION_HEADER, it) }

        val call = client.newCall(builder.build())
        calls += call
        try {
            call.execute().use { response ->
                noteSession(response)
                if (!response.isSuccessful) {
                    reportClosed("the server answered HTTP ${response.code}${snippet(response)}")
                    return
                }
                val body = response.body ?: return
                val type = response.header("Content-Type").orEmpty()
                if (type.contains("text/event-stream", ignoreCase = true)) {
                    readSse(body.source()) { emit(it) }
                } else {
                    val text = body.string().trim()
                    if (text.isNotEmpty()) emit(text)
                }
            }
        } catch (e: SocketTimeoutException) {
            // One request's read deadline is not the connection's death. The registry times the
            // logical call out; every other pending request must be allowed to finish.
            if (!closed) logger.w(TAG, "[${server.id}] request timed out: ${e.message}")
        } catch (e: IOException) {
            // A closed transport cancels its calls; that IOException is ours, not the server's.
            if (!closed) reportClosed("the request to '${server.name}' failed: ${e.message}")
        } finally {
            calls -= call
        }
    }

    /**
     * Remember the session id, and open the event stream the first time one arrives.
     *
     * A server may be stateless and send no id at all; then there is no stream to open (the GET
     * needs the session), and the POST channel remains the whole connection.
     */
    private fun noteSession(response: Response) {
        val id = response.header(SESSION_HEADER) ?: return
        if (sessionId == id) return
        sessionId = id
        // A rotated id invalidates the event stream opened for the previous session. Cancel it
        // and allow exactly one new stream for the new id; without the reset the one-shot CAS
        // below would leave notifications dead for the rest of the connection.
        streamCall?.cancel()
        streamStarted.set(false)
        startEventStream()
    }

    private fun startEventStream() {
        if (!streamStarted.compareAndSet(false, true)) return
        executor.execute {
            val builder = Request.Builder()
                .url(server.url)
                .get()
                .header("Accept", "text/event-stream")
            server.headers.forEach { (name, value) -> builder.header(name, value) }
            sessionId?.let { builder.header(SESSION_HEADER, it) }

            val call = streamClient.newCall(builder.build())
            streamCall = call
            try {
                call.execute().use { response ->
                    val type = response.header("Content-Type").orEmpty()
                    if (!response.isSuccessful || !type.contains("text/event-stream", ignoreCase = true)) {
                        logger.d(TAG, "[${server.id}] no server event stream (HTTP ${response.code}); list changes wait for a reconnect")
                        return@execute
                    }
                    response.body?.source()?.let { source -> readSse(source) { emit(it) } }
                }
            } catch (e: IOException) {
                // Losing the notification stream is not losing the connection: POSTs still work.
                if (!closed) logger.d(TAG, "[${server.id}] event stream ended: ${e.message}")
            }
        }
    }

    /**
     * Read one SSE stream, forwarding each event's `data:` payload.
     *
     * MCP sends the JSON-RPC message as the data of an unnamed event, so `event:`, `id:` and
     * `retry:` are ignored and comment lines (`:`) are skipped. Multiple `data:` lines in one event
     * are joined with a newline, which is what the SSE format means and what a pretty-printed
     * message would need.
     */
    private fun readSse(source: BufferedSource, emit: (String) -> Unit) {
        val data = StringBuilder()
        while (true) {
            val line = try {
                source.readUtf8Line() ?: break
            } catch (e: IOException) {
                break
            }
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) {
                        emit(data.toString())
                        data.setLength(0)
                    }
                }
                line.startsWith(":") -> Unit
                line.startsWith("data:") -> {
                    val value = line.removePrefix("data:").removePrefix(" ")
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(value)
                }
                else -> Unit
            }
        }
        if (data.isNotEmpty()) emit(data.toString())
    }

    private fun emit(text: String) {
        if (!closed) onMessage?.invoke(text)
    }

    private fun reportClosed(why: String) {
        // The transport is dead from this moment: `send` must refuse rather than hand work to an
        // executor whose connection is gone, even before the registry gets around to closing it.
        closed = true
        if (reported.compareAndSet(false, true)) onClosed?.invoke(why)
    }

    private fun snippet(response: Response): String = try {
        val text = response.body?.string().orEmpty().trim().replace('\n', ' ')
        if (text.isEmpty()) "" else ": ${text.take(200)}"
    } catch (_: IOException) {
        ""
    }

    override fun close() {
        closed = true
        streamCall?.cancel()
        synchronized(calls) { calls.toList() }.forEach { runCatching { it.cancel() } }
        executor.shutdownNow()
        reportClosed("the transport was closed")
    }

    private companion object {
        const val TAG = "McpHttp"
        const val SESSION_HEADER = "Mcp-Session-Id"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
