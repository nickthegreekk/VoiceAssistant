package com.nikosm.voiceassistant

import org.json.JSONArray
import org.json.JSONObject

/**
 * The tool-call wire formats as **each provider's own dialect**: what is declared in the request,
 * what a call looks like in the response, and what has to be sent back so the model can read its
 * own result.
 *
 * Ported from the Linux build's `FsToolProtocol.kt` (M8), minus the filesystem-tool definitions —
 * this platform offers MCP tools and the `remember` tool, and nothing with a workspace. The type
 * names keep their `Fs` prefix on purpose: the two trees cross-pollinate, and a renamed type is a
 * diff nobody can read. New file name because this tree has no filesystem tools for it to be the
 * protocol of.
 *
 * ## The four shapes, in one place
 *
 * | | declaration | call | result |
 * |---|---|---|---|
 * | Ollama | `tools:[{type:function, function:{…}}]` | `message.tool_calls[]`, `arguments` an **object** | `{role:tool, content, tool_name}` |
 * | OpenAI-compatible | the same declaration | `choices[0].message.tool_calls[]`, `arguments` a **string** | `{role:tool, tool_call_id, content}` |
 * | Anthropic | `tools:[{name, description, input_schema}]` | `content[]` blocks with `type:tool_use` | one `user` message of `tool_result` blocks |
 * | Gemini | `tools:[{function_declarations:[…]}]` | `parts[].functionCall` | one `user` message of `functionResponse` parts |
 *
 * Every function here is pure: `org.json` in, `org.json` out, no IO, no service, no network.
 *
 * **Call ids are ours to mint.** Only the round trip needs them to match: OpenAI and Anthropic quote
 * an id back, and both accept one we chose. Minting them means a model that omits ids still works.
 */

/** Which dialect a request is being built for. */
internal enum class FsToolProvider { OLLAMA, OPENAI, ANTHROPIC, GEMINI }

/** One call the model asked for. [arguments] is the provider's JSON, kept as text until parsed. */
internal data class FsToolCall(val id: String, val name: String, val arguments: String)

/** What executing one call produced. [text] is what the model is told — never empty, always a sentence. */
internal class FsToolResult(val callId: String, val name: String, val text: String, val ok: Boolean)

/**
 * The messages that carry one round of calls and their results back to the model. Raw
 * `JSONObject`s rather than a neutral structure, deliberately: **replaying the provider's own
 * assistant message** (with its ids, its block types and its ordering) is what keeps the four
 * dialects from drifting, and a neutral shape would have to be re-serialised into four shapes that
 * could each be subtly wrong.
 */
internal class FsToolTurn(
    val messages: List<JSONObject>,
    /**
     * How many calls this round made. Not derivable from [messages] — that list is one assistant
     * message and one result per call for two dialects and one message for the other two — and the
     * apply block needs it: a turn that used tools and then answered with nothing says so instead of
     * writing an empty bubble (see `withCloudToolSummary`).
     */
    val calls: Int,
)

// ---------------------------------------------------------------------------- declarations

/** One function the request may declare, provider-neutrally. */
internal data class ToolSpec(val name: String, val description: String, val schema: JSONObject)

/**
 * The declarations in [provider]'s shape, or null when nothing is offered.
 */
internal fun toolsJsonFor(provider: FsToolProvider, specs: List<ToolSpec>): JSONArray? {
    if (specs.isEmpty()) return null
    return when (provider) {
        FsToolProvider.OLLAMA, FsToolProvider.OPENAI -> JSONArray().apply {
            specs.forEach { spec ->
                put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", spec.name)
                                .put("description", spec.description)
                                .put("parameters", spec.schema),
                        ),
                )
            }
        }
        FsToolProvider.ANTHROPIC -> JSONArray().apply {
            specs.forEach { spec ->
                put(
                    JSONObject()
                        .put("name", spec.name)
                        .put("description", spec.description)
                        .put("input_schema", spec.schema),
                )
            }
        }
        FsToolProvider.GEMINI -> JSONArray().apply {
            put(
                JSONObject().put(
                    "function_declarations",
                    JSONArray().apply {
                        specs.forEach { spec ->
                            put(
                                JSONObject()
                                    .put("name", spec.name)
                                    .put("description", spec.description)
                                    .put("parameters", spec.schema),
                            )
                        }
                    },
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------- reading a call

/**
 * The calls in this response body, or null when there are none — and **null is not empty**: it means
 * "this is an ordinary reply, parse it as one", which is the branch every existing path takes.
 *
 * Callers must ask *before* the reply parsers: an OpenAI-compatible tool-call body has
 * `content: null` and the existing `message.getString("content")` would throw on it.
 */
internal fun fsToolCallsFrom(provider: FsToolProvider, body: JSONObject): List<FsToolCall>? = when (provider) {
    FsToolProvider.OLLAMA -> body.optJSONObject("message")?.let(::ollamaCalls)
    FsToolProvider.OPENAI -> body.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.let(::openAiCalls)
    FsToolProvider.ANTHROPIC -> anthropicCalls(body.optJSONArray("content"))
    FsToolProvider.GEMINI -> body.optJSONArray("candidates")?.optJSONObject(0)
        ?.optJSONObject("content")?.optJSONArray("parts")?.let(::geminiCalls)
}

private fun ollamaCalls(message: JSONObject): List<FsToolCall>? =
    callsFrom(message.optJSONArray("tool_calls")) { call, index ->
        val function = call.optJSONObject("function") ?: return@callsFrom null
        // Ollama hands the arguments over as an object where OpenAI hands over a string. Both become
        // text here, because the executor parses them the same way either way.
        FsToolCall(
            id = "call_$index",
            name = function.optString("name"),
            arguments = function.opt("arguments")?.toString() ?: "{}",
        )
    }

private fun openAiCalls(message: JSONObject): List<FsToolCall>? =
    callsFrom(message.optJSONArray("tool_calls")) { call, index ->
        val function = call.optJSONObject("function") ?: return@callsFrom null
        FsToolCall(
            id = call.optString("id").takeIf { it.isNotBlank() } ?: "call_$index",
            name = function.optString("name"),
            arguments = function.optString("arguments").takeIf { it.isNotBlank() } ?: "{}",
        )
    }

private fun anthropicCalls(content: JSONArray?): List<FsToolCall>? {
    if (content == null) return null
    val calls = ArrayList<FsToolCall>()
    for (i in 0 until content.length()) {
        val block = content.optJSONObject(i) ?: continue
        if (block.optString("type") != "tool_use") continue
        calls += FsToolCall(
            id = block.optString("id").takeIf { it.isNotBlank() } ?: "call_${calls.size}",
            name = block.optString("name"),
            arguments = block.opt("input")?.toString() ?: "{}",
        )
    }
    return calls.takeIf { it.isNotEmpty() }
}

private fun geminiCalls(parts: JSONArray): List<FsToolCall>? {
    val calls = ArrayList<FsToolCall>()
    for (i in 0 until parts.length()) {
        val call = parts.optJSONObject(i)?.optJSONObject("functionCall") ?: continue
        calls += FsToolCall(
            id = "call_${calls.size}",
            name = call.optString("name"),
            arguments = call.opt("args")?.toString() ?: "{}",
        )
    }
    return calls.takeIf { it.isNotEmpty() }
}

/** Shared shape of the four readers: index the calls, drop any that name no function, null if none. */
private inline fun callsFrom(array: JSONArray?, build: (JSONObject, Int) -> FsToolCall?): List<FsToolCall>? {
    if (array == null) return null
    val calls = ArrayList<FsToolCall>()
    for (i in 0 until array.length()) {
        val call = array.optJSONObject(i) ?: continue
        val built = build(call, calls.size) ?: continue
        if (built.name.isBlank()) continue
        calls += built
    }
    return calls.takeIf { it.isNotEmpty() }
}

// ---------------------------------------------------------------------------- sending it back

/**
 * Everything this turn's earlier rounds will add to the request, as text — for the budget to count.
 *
 * The rounds are appended after the current user turn by both builders, and they are neither history
 * nor the message, so nothing measured them: a turn that reads 64 KiB of files added ~16k uncounted
 * tokens to a request whose whole point was to be checked for fitting. Rendering the messages as
 * their JSON is exact enough for a token *estimate*, and it is what the model will be sent.
 */
internal fun fsToolTurnsText(turns: List<FsToolTurn>): String =
    turns.joinToString("\n") { turn -> turn.messages.joinToString("\n") { it.toString() } }

/**
 * What the next request has to carry so the model can see what its calls produced: the assistant's
 * own call message, then the results — in the shape [provider] requires, which is not the same shape
 * twice. Anthropic and Gemini take **all** results in one user message; OpenAI and Ollama take one
 * message per result, because that is what their APIs pair ids and names against.
 */
internal fun fsToolTurnFor(
    provider: FsToolProvider,
    body: JSONObject,
    calls: List<FsToolCall>,
    results: List<FsToolResult>,
): FsToolTurn {
    // Matched on the id first and the name ONLY as a fallback for a provider that sent no id — two
    // calls to the same tool in one round both matched the first result on the name, so the second
    // call was answered with the first call's output. Pairing by position is the honest fallback:
    // results are built in call order by the executor.
    fun resultFor(call: FsToolCall): FsToolResult =
        results.firstOrNull { it.callId == call.id }
            ?: results.getOrNull(calls.indexOf(call))
            ?: FsToolResult(call.id, call.name, "the tool produced no result.", ok = false)

    return when (provider) {
        FsToolProvider.OLLAMA -> FsToolTurn(
            calls = calls.size,
            messages = listOfNotNull(
                // The received message, verbatim: it already carries the calls in whatever form this
                // server sent, which is the form it will accept back.
                body.optJSONObject("message"),
                *calls.map { call ->
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_name", call.name)
                        .put("content", resultFor(call).text)
                }.toTypedArray(),
            ),
        )

        FsToolProvider.OPENAI -> FsToolTurn(
            calls = calls.size,
            messages = listOf(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", JSONObject.NULL)
                    .put(
                        "tool_calls",
                        JSONArray().apply {
                            calls.forEach { call ->
                                put(
                                    JSONObject()
                                        .put("id", call.id)
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject().put("name", call.name).put("arguments", call.arguments),
                                        ),
                                )
                            }
                        },
                    ),
            ) + calls.map { call ->
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", call.id)
                    .put("content", resultFor(call).text)
            },
        )

        FsToolProvider.ANTHROPIC -> FsToolTurn(
            calls = calls.size,
            messages = listOf(
                JSONObject().put("role", "assistant").put(
                    "content",
                    JSONArray().apply {
                        calls.forEach { call ->
                            put(
                                JSONObject()
                                    .put("type", "tool_use")
                                    .put("id", call.id)
                                    .put("name", call.name)
                                    .put("input", runCatching { JSONObject(call.arguments) }.getOrElse { JSONObject() }),
                            )
                        }
                    },
                ),
                JSONObject().put("role", "user").put(
                    "content",
                    JSONArray().apply {
                        calls.forEach { call ->
                            put(
                                JSONObject()
                                    .put("type", "tool_result")
                                    .put("tool_use_id", call.id)
                                    .put("content", resultFor(call).text),
                            )
                        }
                    },
                ),
            ),
        )

        FsToolProvider.GEMINI -> FsToolTurn(
            calls = calls.size,
            messages = listOf(
                JSONObject().put("role", "model").put(
                    "parts",
                    JSONArray().apply {
                        calls.forEach { call ->
                            put(
                                JSONObject().put(
                                    "functionCall",
                                    JSONObject()
                                        .put("name", call.name)
                                        .put("args", runCatching { JSONObject(call.arguments) }.getOrElse { JSONObject() }),
                                ),
                            )
                        }
                    },
                ),
                JSONObject().put("role", "user").put(
                    "parts",
                    JSONArray().apply {
                        calls.forEach { call ->
                            put(
                                JSONObject().put(
                                    "functionResponse",
                                    JSONObject()
                                        .put("name", call.name)
                                        .put("response", JSONObject().put("result", resultFor(call).text)),
                                ),
                            )
                        }
                    },
                ),
            ),
        )
    }
}
