package com.nikosm.voiceassistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Model Context Protocol, as **pure JSON-RPC 2.0** (PLAN.md §10/M9): what goes out and what
 * comes back, with no transport, no process, no socket and no service in sight.
 *
 * ## Scope
 *
 * Tools only. The methods here are `initialize`, `notifications/initialized`, `tools/list` (with its
 * cursor), `tools/call` and `ping`; resources, prompts, sampling, roots and elicitation are out of
 * v1, each a different feature wearing the same transport. The wire format is
 * `2025-06-18`, the version this was written against; [initializeRequest] sends it and
 * [McpHandshake] accepts whatever version the server answers with — a server that speaks an older
 * revision of the tool subset still works, and one that answers a version we do not understand is
 * rejected loudly by the caller rather than silently half-used.
 *
 * ## Why this is pure
 *
 * The same reason [FsToolProtocol] is: every function is `org.json` in and `org.json` out, so the
 * whole surface is testable against fabricated bodies without a server, a process or a clock. The
 * transport and the registry own the parts that need those.
 */
internal object McpProtocol {

    /** The protocol revision this client implements. */
    const val VERSION = "2025-06-18"

    /** The client name a server sees in `initialize`. */
    const val CLIENT_NAME = "celeste-linux"

    // ------------------------------------------------------------------ outgoing

    fun request(id: Long, method: String, params: JSONObject? = null): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .apply { if (params != null) put("params", params) }

    fun notification(method: String, params: JSONObject? = null): JSONObject =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .apply { if (params != null) put("params", params) }

    /**
     * The opening request. `capabilities` is empty on purpose: this client accepts tools and
     * nothing else, and a server must not be told it can sample, ask for roots, or elicit input.
     */
    fun initializeRequest(id: Long, clientVersion: String): JSONObject =
        request(
            id,
            "initialize",
            JSONObject()
                .put("protocolVersion", VERSION)
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", CLIENT_NAME).put("version", clientVersion)),
        )

    fun initializedNotification(): JSONObject = notification("notifications/initialized")

    fun listToolsRequest(id: Long, cursor: String? = null): JSONObject =
        request(id, "tools/list", cursor?.let { JSONObject().put("cursor", it) } ?: JSONObject())

    fun callToolRequest(id: Long, name: String, arguments: JSONObject): JSONObject =
        request(id, "tools/call", JSONObject().put("name", name).put("arguments", arguments))

    fun pingRequest(id: Long): JSONObject = request(id, "ping", JSONObject())

    // ------------------------------------------------------------------ incoming

    /** The `result` of a response, or null when this message is an error or not a response. */
    fun resultOf(message: JSONObject): JSONObject? = message.optJSONObject("result")

    /**
     * The error sentence of a response, or null when it carries a result.
     *
     * JSON-RPC's error object is `{code, message, data}`; the code is kept because servers use it
     * for genuinely different situations (`-32601` unknown method, `-32602` bad params) and a log
     * line without it cannot be told apart from a transport failure.
     */
    fun errorOf(message: JSONObject): String? {
        val error = message.optJSONObject("error") ?: return null
        val code = error.optInt("code", 0)
        val text = error.optString("message").ifBlank { "no message" }
        return "JSON-RPC error $code: $text"
    }

    /** True for the one server notification this client acts on. */
    fun isToolListChanged(message: JSONObject): Boolean =
        message.opt("id") == null && message.optString("method") == "notifications/tools/list_changed"

    /**
     * The tools and the next page cursor from a `tools/list` result.
     *
     * A tool with no name is dropped rather than declared — a provider rejects a nameless function
     * and one bad entry would lose the whole list. The schema defaults to an empty object schema,
     * which every provider accepts, for a server that omits one.
     */
    fun toolList(result: JSONObject): Pair<List<McpToolInfo>, String?> {
        val tools = mutableListOf<McpToolInfo>()
        val array = result.optJSONArray("tools") ?: JSONArray()
        for (i in 0 until array.length()) {
            val tool = array.optJSONObject(i) ?: continue
            val name = tool.optString("name").trim()
            if (name.isEmpty()) continue
            val annotations = tool.optJSONObject("annotations")
            tools += McpToolInfo(
                name = name,
                description = tool.optString("description").trim(),
                inputSchema = tool.optJSONObject("inputSchema") ?: emptySchema(),
                readOnly = annotations?.optBoolean("readOnlyHint", false) ?: false,
                destructive = annotations?.optBoolean("destructiveHint", false) ?: false,
            )
        }
        val cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
        return tools to cursor
    }

    /**
     * One `tools/call` result as the sentence the model will read.
     *
     * The content array is the protocol's shape, not a string: text parts are joined, an image
     * part becomes a placeholder naming its type and size (this build has no vision plumbing), and
     * a resource part contributes its embedded text if it has one or its URI if it does not.
     * `isError` is the server's own refusal and maps to [McpCallOutcome.ok] being false — the model
     * must be told the call failed, not handed a sentence that reads like a success.
     */
    fun callResult(result: JSONObject): McpCallOutcome {
        val isError = result.optBoolean("isError", false)
        val text = contentText(result.optJSONArray("content"))
            ?: result.optJSONObject("structuredContent")?.toString()
            ?: if (isError) "the tool reported an error with no details." else "(the tool returned no content)"
        return McpCallOutcome(text = text, ok = !isError)
    }

    private fun contentText(content: JSONArray?): String? {
        if (content == null) return null
        val parts = mutableListOf<String>()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            when (part.optString("type")) {
                "text" -> part.optString("text").takeIf { it.isNotEmpty() }?.let { parts += it }
                "image" -> {
                    val mime = part.optString("mimeType").ifBlank { "image" }
                    val bytes = (part.optString("data").length * 3) / 4
                    parts += "[image: $mime, about $bytes bytes]"
                }
                "resource" -> {
                    val resource = part.optJSONObject("resource") ?: continue
                    val embedded = resource.optString("text").takeIf { it.isNotEmpty() }
                    val uri = resource.optString("uri").takeIf { it.isNotEmpty() }
                    parts += embedded ?: "[resource: ${uri ?: "no uri"}]"
                }
                else -> Unit // An unknown part type is not an error; the rest of the content is useful.
            }
        }
        return parts.joinToString("\n").ifEmpty { null }
    }

    private fun emptySchema(): JSONObject =
        JSONObject().put("type", "object").put("properties", JSONObject())
}

/**
 * What the server says a tool is, after [McpProtocol.toolList].
 *
 * The hints are MCP's own annotations. They are advisory, never enforcement — a server can lie —
 * and v1 uses them only to describe the tool; approval is the user's per-server trust, not a hint.
 */
internal data class McpToolInfo(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val readOnly: Boolean,
    val destructive: Boolean,
)

/** What one `tools/call` produced, as the model is told it. */
internal data class McpCallOutcome(val text: String, val ok: Boolean)

/** The server's answer to `initialize`: the negotiated version and the capabilities we may use. */
internal data class McpHandshake(val protocolVersion: String, val serverName: String, val serverVersion: String, val tools: Boolean) {
    companion object {
        /** Parse a successful `initialize` result; null when it does not describe a server. */
        fun from(result: JSONObject): McpHandshake? {
            val version = result.optString("protocolVersion").trim()
            if (version.isEmpty()) return null
            val info = result.optJSONObject("serverInfo")
            return McpHandshake(
                protocolVersion = version,
                serverName = info?.optString("name").orEmpty().ifBlank { "unnamed server" },
                serverVersion = info?.optString("version").orEmpty(),
                tools = result.optJSONObject("capabilities")?.has("tools") == true,
            )
        }
    }
}
