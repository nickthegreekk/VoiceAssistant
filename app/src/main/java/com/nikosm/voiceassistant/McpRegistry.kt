package com.nikosm.voiceassistant

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * One tool offered by an MCP server, as the application sees it.
 *
 * [toolName] is the server's own name and travels back in `tools/call`; [declaredName] is what the
 * model is offered — `mcp_<server>_<tool>` — because providers require names in
 * `[A-Za-z0-9_-]{1,64}` and two servers may well both have an `echo`.
 */
internal data class McpTool(
    val serverId: String,
    val serverName: String,
    val toolName: String,
    val declaredName: String,
    val description: String,
    val inputSchema: JSONObject,
)

/**
 * The live connections to configured MCP servers (PLAN.md §10/M9).
 *
 * ## Lazy, because "configured" is not "connected"
 *
 * A connection is opened the first time a turn actually asks for that server's tools. A persona
 * with no MCP server enabled — which is every persona until the user enables one — never spawns a
 * process and never opens a socket, which is what lets the no-network promise stay a property of
 * the code rather than of a settings value.
 *
 * ## Failure belongs to one server
 *
 * A server that cannot be reached contributes no tools to the turn and a logged sentence; it never
 * fails the turn. A call that reaches the registry for a tool whose server is gone answers with a
 * sentence the model can repeat ("could not run"), not an exception — the same contract the
 * filesystem executor follows, and the reason a stopped server is a degraded answer rather than a
 * broken one.
 *
 * ## The handshake is once per connection, under a mutex
 *
 * `initialize` → tools capability check → `notifications/initialized` → `tools/list` (all pages).
 * The tool list is cached; `notifications/tools/list_changed` clears it, so the next turn re-lists.
 * Every request is correlated by an id this class mints and carries a timeout; a transport closure
 * fails every request waiting on it at once.
 */
internal class McpRegistry(
    private val factory: McpTransportFactory = McpTransportFactory.NONE,
    private val logger: AppLogger = AndroidAppLogger,
    private val connectTimeoutMillis: Long = 10_000,
    private val callTimeoutMillis: Long = 35_000,
) : AutoCloseable {

    private val connections = ConcurrentHashMap<String, McpConnection>()

    /**
     * The tools [server] offers, already namespaced and collision-checked; empty when it cannot be
     * reached, has no tools capability, or is disabled.
     */
    suspend fun toolsFor(server: McpServerConfig): List<McpTool> {
        if (!server.enabled || !server.isRunnable) return emptyList()
        // A saved edit can change where the same id points. Reusing the old connection would run
        // the old command for a server the user has just reconfigured — the settings screen's Test
        // button would report the previous server's tools. The connection fields decide; trust and
        // enabled do not (a grant change is not a reconnect).
        val existing = connections[server.id]
        val connection = if (existing != null && existing.configuredFor(server)) {
            existing
        } else {
            existing?.close()
            McpConnection(server, factory, logger, callTimeoutMillis).also { connections[server.id] = it }
        }
        val infos = try {
            withTimeout(connectTimeoutMillis + callTimeoutMillis) { connection.tools() }
        } catch (e: TimeoutCancellationException) {
            logger.w(TAG, "[${server.id}] no tools this turn: ${e.message}")
            return emptyList()
        } catch (e: CancellationException) {
            // The turn was stopped. A server that is slow is a degraded answer; a turn that was
            // cancelled is not this function's to answer at all.
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "[${server.id}] no tools this turn: ${e.message}")
            return emptyList()
        }
        val declared = LinkedHashMap<String, McpTool>()
        for (info in infos) {
            val name = declaredMcpToolName(server.id, info.name)
            if (declared.containsKey(name)) {
                logger.w(TAG, "[${server.id}] two tools map to '$name'; the later one is not offered")
                continue
            }
            declared[name] = McpTool(server.id, server.name, info.name, name, info.description, info.inputSchema)
        }
        return declared.values.toList()
    }

    /** Run one call on the connection that owns [tool]; never throws for a server-side failure. */
    suspend fun call(tool: McpTool, arguments: JSONObject): McpCallOutcome {
        val connection = connections[tool.serverId]
            ?: return McpCallOutcome("the MCP server '${tool.serverName}' is not connected.", ok = false)
        return try {
            connection.call(tool.toolName, arguments)
        } catch (e: CancellationException) {
            // A stopped turn must unwind, not receive "the server could not run ...".
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "[${tool.serverId}] ${tool.toolName} failed: ${e.message}")
            McpCallOutcome(
                "the MCP server '${tool.serverName}' could not run ${tool.toolName}: ${e.message}",
                ok = false,
            )
        }
    }

    /** Stop every transport; called by `AssistantService.shutdown()` and by tests. */
    override fun close() {
        connections.values.forEach { runCatching { it.close() } }
        connections.clear()
    }

    private companion object {
        const val TAG = "McpRegistry"
    }
}

/**
 * What a provider is offered for one MCP tool: the `mcp_<server>_<tool>` name every dialect will
 * accept.
 *
 * Deterministic and testable on its own. A server slug is truncated so the prefix leaves room, and
 * a collision is the caller's to drop — renaming silently would send a call to the wrong tool.
 */
internal fun declaredMcpToolName(serverId: String, toolName: String): String {
    val serverSlug = sanitizeMcpName(serverId).take(40)
    val prefix = "mcp_${serverSlug}_"
    val tool = sanitizeMcpName(toolName)
    val room = (64 - prefix.length).coerceAtLeast(1)
    return prefix + tool.take(room)
}

private fun sanitizeMcpName(value: String): String =
    value.lowercase()
        .map { c -> if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_' }
        .joinToString("")
        .trim('_')
        .ifBlank { "x" }

/**
 * One server's connection: the transport, the pending requests and the tool cache.
 *
 * Everything that mutates connection state — opening, handshaking, re-listing — runs under
 * [setup], so two turns that start at once cannot handshake twice. A call does **not** hold the
 * mutex while it waits for the server; it only borrows the connection the setup established.
 */
private class McpConnection(
    private val server: McpServerConfig,
    private val factory: McpTransportFactory,
    private val logger: AppLogger,
    private val callTimeoutMillis: Long,
) {
    private val setup = Mutex()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicLong(1)

    @Volatile
    private var transport: McpTransport? = null

    @Volatile
    private var toolsCache: List<McpToolInfo>? = null

    @Volatile
    private var failure: String? = null

    fun configuredFor(other: McpServerConfig): Boolean =
        server.transport == other.transport &&
            server.command == other.command &&
            server.args == other.args &&
            server.env == other.env &&
            server.workingDir == other.workingDir &&
            server.url == other.url &&
            server.headers == other.headers

    suspend fun tools(): List<McpToolInfo> = setup.withLock {
        ensureReadyLocked()
        toolsCache ?: fetchTools().also { toolsCache = it }
    }

    suspend fun call(toolName: String, arguments: JSONObject): McpCallOutcome {
        setup.withLock { ensureReadyLocked() }
        val response = try {
            request(McpProtocol.callToolRequest(nextId.getAndIncrement(), toolName, arguments))
        } catch (e: McpTransportException) {
            return McpCallOutcome("the MCP server '${server.name}' is not reachable: ${e.message}", ok = false)
        }
        McpProtocol.errorOf(response)?.let { return McpCallOutcome(it, ok = false) }
        val result = McpProtocol.resultOf(response)
            ?: return McpCallOutcome("the MCP server '${server.name}' sent no result.", ok = false)
        return McpProtocol.callResult(result)
    }

    private suspend fun ensureReadyLocked() {
        if (transport != null && failure == null) return
        failure = null
        toolsCache = null

        val opened = factory.open(server)
        opened.start({ raw -> onMessage(raw) }, { why -> onClosed(why) })
        transport = opened

        val handshake = try {
            val response = request(McpProtocol.initializeRequest(nextId.getAndIncrement(), CLIENT_VERSION))
            McpProtocol.errorOf(response)?.let { throw McpTransportException("the server refused the handshake: $it") }
            val result = McpProtocol.resultOf(response) ?: throw McpTransportException("the handshake carried no result")
            McpHandshake.from(result) ?: throw McpTransportException("the handshake did not describe a server")
        } catch (e: CancellationException) {
            // Cancellation while the handshake is in flight is the turn ending, not a bad server.
            // Leaving `transport` installed is safe: the next turn's ensureReadyLocked reuses it.
            throw e
        } catch (e: Exception) {
            failure = "the handshake failed: ${e.message}"
            runCatching { opened.close() }
            transport = null
            throw McpTransportException("the MCP server '${server.name}' did not complete the handshake: ${e.message}", e)
        }

        if (handshake.protocolVersion != McpProtocol.VERSION) {
            logger.i(
                TAG,
                "[${server.id}] speaks MCP ${handshake.protocolVersion}; this client implements ${McpProtocol.VERSION}",
            )
        }
        if (!handshake.tools) {
            failure = "the server offers no tools"
            runCatching { opened.close() }
            transport = null
            throw McpTransportException("the MCP server '${server.name}' offers no tools")
        }
        opened.send(McpProtocol.initializedNotification().toString())
        logger.i(TAG, "[${server.id}] connected to ${handshake.serverName} ${handshake.serverVersion}".trim())
    }

    private suspend fun fetchTools(): List<McpToolInfo> {
        val all = mutableListOf<McpToolInfo>()
        var cursor: String? = null
        var pages = 0
        while (true) {
            val response = request(McpProtocol.listToolsRequest(nextId.getAndIncrement(), cursor))
            McpProtocol.errorOf(response)?.let { throw McpTransportException(it) }
            val result = McpProtocol.resultOf(response) ?: throw McpTransportException("tools/list sent no result")
            val (tools, next) = McpProtocol.toolList(result)
            all += tools
            pages++
            if (next == null) break
            if (pages >= MAX_TOOL_PAGES) {
                logger.w(TAG, "[${server.id}] stopped after $MAX_TOOL_PAGES pages of tools")
                break
            }
            cursor = next
        }
        return all
    }

    private suspend fun request(message: JSONObject): JSONObject {
        val transport = transport ?: throw McpTransportException("the MCP server '${server.name}' is not connected")
        val id = message.getLong("id")
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        try {
            transport.send(message.toString())
        } catch (e: McpTransportException) {
            pending.remove(id)
            throw e
        }
        return try {
            withTimeout(callTimeoutMillis) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw McpTransportException(
                "the MCP server '${server.name}' did not answer within $callTimeoutMillis ms",
                e,
            )
        } finally {
            pending.remove(id)
        }
    }

    private fun onMessage(raw: String) {
        val message = runCatching { JSONObject(raw) }.getOrElse {
            logger.d(TAG, "[${server.id}] ignored a non-JSON message: ${raw.take(120)}")
            return
        }
        if (McpProtocol.isToolListChanged(message)) {
            toolsCache = null
            return
        }
        val id = message.optLong("id", NO_ID)
        if (id == NO_ID) {
            logger.d(TAG, "[${server.id}] ignored a notification: ${message.optString("method")}")
            return
        }
        pending.remove(id)?.complete(message)
    }

    /**
     * The transport died. Fail everything waiting on it now rather than letting each request run
     * to its own timeout, and drop the cache so the next turn reconnects and re-lists.
     */
    private fun onClosed(why: String) {
        if (transport == null) return
        transport = null
        toolsCache = null
        failure = why
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { deferred ->
            deferred.completeExceptionally(McpTransportException(why))
        }
    }

    fun close() {
        transport?.let { runCatching { it.close() } }
        onClosed("the connection was closed")
    }

    private companion object {
        const val TAG = "McpRegistry"
        const val CLIENT_VERSION = "0.2.0"
        const val NO_ID = Long.MIN_VALUE

        /** A server that pages forever must not keep the turn waiting forever. */
        const val MAX_TOOL_PAGES = 20
    }
}
