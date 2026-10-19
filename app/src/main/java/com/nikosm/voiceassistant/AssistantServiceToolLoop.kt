package com.nikosm.voiceassistant

import android.net.Uri
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The bounded tool loop: a turn that offers tools asks the model, and while the model answers with
 * calls instead of prose, those calls are carried out and the model is asked again.
 *
 * Ported from the Linux build's `AssistantServiceFsTools.kt`, with the filesystem and terminal
 * halves removed — this platform has neither, so the loop carries MCP tools and the `remember`
 * tool, and nothing else. The shape is unchanged on purpose: the single-turn functions are not
 * restructured, each round is another ordinary call with this turn's earlier rounds travelling in
 * [TurnContext.toolTurns], and the caps are the same two numbers.
 *
 * ## The bounds
 *
 * A model that keeps calling forever stops with a sentence rather than a hang. Two caps: rounds
 * (how many times the model may answer with calls) and calls (how many calls one turn may make in
 * total). Both are pure functions so the refusal is testable without a model.
 */

/** What one call is carried out by, whichever source offered it. */
internal interface ToolCallExecutor {
    suspend fun execute(call: FsToolCall): FsToolResult
}

/**
 * Routes one call to the MCP server that offered it, or to the application's own `remember` tool.
 *
 * The declared name is the key: MCP tools are `mcp_<server>_<tool>` and `remember` is the
 * application's own fixed name, so the two namespaces cannot collide. A name neither knows is the
 * model inventing one — answered with a sentence, like every other tool failure, rather than a
 * thrown exception that would end the turn.
 */
internal class ToolRouter(
    private val mcpTools: Map<String, McpTool>,
    private val callMcp: suspend (McpTool, JSONObject) -> McpCallOutcome,
    /** The application's own `remember` tool, or null when the persona has memory off. */
    private val remember: (suspend (FsToolCall) -> FsToolResult)? = null,
) : ToolCallExecutor {

    override suspend fun execute(call: FsToolCall): FsToolResult {
        if (call.name == MEMORY_TOOL) {
            val remember = remember
                ?: return FsToolResult(call.id, call.name, "There is no tool named '${call.name}'.", ok = false)
            return remember(call)
        }
        val tool = mcpTools[call.name]
        if (tool != null) {
            val outcome = callMcp(tool, toolArgumentsOf(call))
            return FsToolResult(call.id, call.name, outcome.text, outcome.ok)
        }
        return FsToolResult(call.id, call.name, "There is no tool named '${call.name}'.", ok = false)
    }
}

/**
 * Why a round may not run, or null when it may. **Pure, and tested**, because it is the only
 * thing standing between a model that keeps calling and a user watching a spinner forever.
 */
internal fun toolRoundRefusal(rounds: Int, callsMade: Int, callsThisRound: Int): String? = when {
    rounds >= MAX_TOOL_ROUNDS ->
        "I stopped after $rounds rounds of using tools without reaching an answer. Ask again with a narrower request."
    callsMade + callsThisRound > MAX_TOOL_CALLS_PER_TURN ->
        "I stopped after $callsMade tool operations in one turn. Ask again with a narrower request."
    else -> null
}

/** How many times the model may answer with calls in one turn. */
internal const val MAX_TOOL_ROUNDS = 6

/** How many calls one turn may make in total, across every round. */
internal const val MAX_TOOL_CALLS_PER_TURN = 12

/**
 * The sentence a turn with no prose answers with — **one string, because two loops say it.** A user
 * with both a local and a cloud persona configured should not be able to tell which route produced
 * the reply, and two copies of a sentence are two sentences within one edit.
 */
internal fun toolSummarySentence(callsMade: Int): String =
    "I used $callsMade tool${if (callsMade == 1) "" else "s"} and have nothing to add."

/**
 * A turn that only ever called tools has no prose to show, and an **empty bubble is worse than a
 * sentence**: the reply says what was done, in the same shape as any other answer — so it is also
 * spoken, which is what a hands-free user gets instead of silence. Any non-blank answer is left
 * exactly as the model wrote it.
 */
private fun withToolSummary(
    outcome: Triple<String, String?, ByteArray?>,
    callsMade: Int,
): Triple<String, String?, ByteArray?> =
    if (outcome.first.isBlank() && callsMade > 0) {
        Triple(toolSummarySentence(callsMade), outcome.second, outcome.third)
    } else {
        outcome
    }

/**
 * One round: carry out the calls and build the messages that report them. Shared by both loops so
 * the executor, the approval gate and the audit cannot be wired differently in one of them.
 */
private suspend fun AssistantService.runToolRound(
    executor: ToolCallExecutor,
    provider: FsToolProvider,
    body: JSONObject,
    calls: List<FsToolCall>,
): FsToolTurn {
    val results = calls.map { call -> executor.execute(call) }
    return fsToolTurnFor(provider, body, calls, results)
}

/**
 * The turn's executor: the MCP router when the persona offers servers, the `remember` tool when
 * the persona has memory, and both behind one interface when it has each.
 */
private fun AssistantService.toolExecutorFor(
    persona: Persona,
    mcpTools: List<McpTool>,
    memoryEnabled: Boolean,
): ToolCallExecutor = ToolRouter(
    mcpTools = mcpTools.associateBy { it.declaredName },
    callMcp = { tool, arguments -> runMcpTool(tool, arguments, persona.name) },
    remember = if (memoryEnabled) {
        { call -> rememberTool(call, persona.name) }
    } else {
        null
    },
)

// ---------------------------------------------------------------------------- the gateway route

/**
 * Whether a turn for this persona is one the app **builds the request for** — a direct Ollama
 * server or a cloud provider — and therefore one that can carry the tools.
 *
 * The third case is the Voice Gateway, which assembles the prompt itself: the app sends it a model,
 * a backend URL and the user's words, and never a system prompt, so there is nowhere to put a tool
 * declaration or a tool contract. Asked through `requestRouting`, which is the same classification
 * Rooms and the history stamps use rather than a second parse that could drift from the router.
 */
internal fun AssistantService.toolsReachableFor(persona: Persona): Boolean {
    val routing = persona.requestRouting(ollamaServerNames(), cloudProviderNames())
    return routing.isDirectOllama || routing.routesToCloud
}

/**
 * What the user is told when their persona has tools and the turn cannot carry them.
 *
 * **Answering anyway is the failure this exists to prevent**: a model asked to summarise what a
 * tool would have returned produces a fluent hallucination. So the turn does not reach the model at
 * all, and the sentence says both why and what to do about it — it is spoken as well as written,
 * because the route it most often fires on is the one where the user is listening rather than
 * reading.
 */
internal const val GATEWAY_TOOLS_REFUSAL =
    "Tools are on for this persona, but this turn goes through the Voice Gateway, which " +
        "assembles the prompt itself — so the tools cannot be offered here. Rather than " +
        "describe what a tool would have returned, I stopped. Use the main chat, or a persona " +
        "that talks to its model directly."

/** Append the refusal, speak it, and let the caller end the turn. */
internal fun AssistantService.refuseToolsOnGatewayRoute(persona: Persona, append: (ChatMessage) -> Unit) {
    append(ChatMessage("assistant", GATEWAY_TOOLS_REFUSAL))
    saveSettings()
    playResponse(persona, deviceText = cleanTextForTts(GATEWAY_TOOLS_REFUSAL))
    logger.i("McpTools", "turn refused: the persona has tools but this route cannot carry them")
}

/**
 * Whether a round's calls may still be carried out: the turn that asked for them is still the
 * newest, and — in single chat — still the active persona's. **The content path's own guards,
 * asked where the work happens.**
 *
 * They are missing here, and the window they close is not exotic: it is the one the content
 * path's guards were written for. Stop cancels the *call*, so a turn is safe while its request is
 * in flight — but between the last byte of a response and the executor's first instruction there
 * is no call left to cancel, and a tool round is the only thing in this application that acts on
 * what arrived in that window. Without the check, a model's tool call that arrived a moment after
 * the user pressed Stop was carried out anyway.
 *
 * The two dialects ask it differently for the same reason the apply blocks do: a room turn's
 * speaker is never the active persona, so its round generation is the whole question.
 */
internal fun AssistantService.toolRoundMayRun(turnContext: TurnContext, personaName: String, generation: Long): Boolean =
    isChatRequestCurrent(generation) && (!turnContext.isSingleChat || isChatContextCurrent(generation, personaName))

/**
 * One text turn with tools, for a persona that has MCP servers or memory: ask, and while the model
 * answers with calls, carry them out and ask again — bounded, so a model that keeps calling
 * forever stops with a sentence rather than a hang.
 *
 * Each round is a fresh request through the **unchanged** single-turn function, with this turn's
 * calls and results carried in `TurnContext.toolTurns`. That is why the existing streaming,
 * supersede and placeholder machinery needs no changes: round two is just another turn, and the
 * first-token rule in the stream loop is what keeps a call out of the bubble.
 */
internal suspend fun AssistantService.performOllamaChatWithTools(
    baseUrl: String,
    model: String,
    text: String,
    persona: Persona,
    currentTurnInHistory: Boolean,
    generation: Long,
    attachments: List<Uri>,
    imagePath: String?,
    turnContext: TurnContext,
    mcpTools: List<McpTool> = emptyList(),
    memoryEnabled: Boolean = false,
): Triple<String, String?, ByteArray?> {
    var context = turnContext
    var rounds = 0
    var callsMade = 0
    val executor = toolExecutorFor(persona, mcpTools, memoryEnabled)

    while (true) {
        var captured: JSONObject? = null
        val outcome = performDirectOllamaChat(
            baseUrl = baseUrl,
            model = model,
            text = text,
            persona = persona,
            currentTurnInHistory = currentTurnInHistory,
            generation = generation,
            attachments = attachments,
            imagePath = imagePath,
            turnContext = context,
            toolSink = { captured = it },
        )
        val message = captured ?: return withToolSummary(outcome, callsMade)
        val body = JSONObject().put("message", message)
        val calls = fsToolCallsFrom(FsToolProvider.OLLAMA, body) ?: return withToolSummary(outcome, callsMade)

        // The turn may already be over by the time its answer arrived — Stop, a persona switch, a
        // newer message — and a call that arrived after that must not be carried out. Checked before
        // the refusal, so a stale turn is not told off for something no one is waiting for.
        if (!toolRoundMayRun(turnContext, persona.name, generation)) return outcome

        toolRoundRefusal(rounds, callsMade, calls.size)?.let { refusal ->
            notifyUser(refusal)
            return withToolSummary(outcome, callsMade)
        }

        val turn = runToolRound(executor, FsToolProvider.OLLAMA, body, calls)
        rounds++
        callsMade += calls.size
        context = context.copy(toolTurns = context.toolTurns + turn)
    }
}

/**
 * The cloud twin of [performOllamaChatWithTools], and the same shape for the same reason: **the
 * single-turn function is not restructured** — it takes a capture sink, returns a sentinel the
 * wrapper discards, and each round is another ordinary call with this turn's earlier rounds
 * travelling in `TurnContext.toolTurns`.
 *
 * `parseCloudResponse` is never reached for a tool-call body, which is the one thing this had to
 * get right: such a body carries `content: null` and the parser reads that field with `getString`.
 */
internal suspend fun AssistantService.performCloudChatWithTools(
    text: String,
    persona: Persona,
    useDeviceVoice: Boolean,
    startTime: Long,
    currentTurnInHistory: Boolean,
    generation: Long,
    attachments: List<Uri>,
    imagePath: String?,
    turnContext: TurnContext,
    mcpTools: List<McpTool> = emptyList(),
    memoryEnabled: Boolean = false,
): Job {
    var context = turnContext
    var rounds = 0
    var callsMade = 0
    val executor = toolExecutorFor(persona, mcpTools, memoryEnabled)

    return serviceScope.launch {
        // **The loop holds THINKING, not the round** — the rounds pass `ownsThinkingState =
        // false`, so nothing between them drops to IDLE. That is not tidiness: the state collector
        // resumes the VAD on IDLE, so a gap between rounds would put the microphone back on while
        // the next round is still being asked for, and while a confirmation is on screen.
        recordThinkingOwner(generation)
        _state.value = AssistantState.THINKING
        updateNotification("Thinking (Cloud)...")
        try {
            while (true) {
                // The sink carries the dialect as well as the body: `performCloudChat` already
                // resolved the persona's cloud entry to build the request, so the loop does not look
                // it up a second time and the two cannot disagree about which provider a round is
                // for.
                var provider: FsToolProvider? = null
                var captured: JSONObject? = null
                performCloudChat(
                    text = text,
                    persona = persona,
                    useDeviceVoice = useDeviceVoice,
                    startTime = startTime,
                    currentTurnInHistory = currentTurnInHistory,
                    generation = generation,
                    attachments = attachments,
                    imagePath = imagePath,
                    turnContext = context,
                    toolSink = { from, body -> provider = from; captured = body },
                    ownsThinkingState = false,
                ).join()
                val toolProvider = provider ?: return@launch
                val body = captured ?: return@launch
                val calls = fsToolCallsFrom(toolProvider, body) ?: return@launch
                // The same guard as the Ollama loop's, at the same point and for the same window:
                // a call that arrived after the user moved on is not carried out. It matters more
                // here than there, because the round that captured it returned *before* the currency
                // checks that follow the parse — this is the only thing standing between a
                // superseded turn and a tool call.
                if (!toolRoundMayRun(turnContext, persona.name, generation)) return@launch
                toolRoundRefusal(rounds, callsMade, calls.size)?.let { refusal ->
                    notifyUser(refusal)
                    return@launch
                }
                val turn = runToolRound(executor, toolProvider, body, calls)
                rounds++
                callsMade += calls.size
                context = context.copy(toolTurns = context.toolTurns + turn)
            }
        } finally {
            // The span ends here. Focus first: a final round that handed off to playback has
            // already moved the state out of THINKING (the playback paths set SPEAKING
            // themselves, and they re-request focus on entry), so a span still sitting in
            // THINKING ended WITHOUT playback and must release the focus the voice path
            // retained through it — the same check every single-turn finally makes. Doing it
            // here rather than in the rounds is what keeps an intermediate round from
            // un-ducking the user's other audio in the middle of the turn.
            if (_state.value == AssistantState.THINKING && isChatRequestCurrent(generation)) {
                abandonAssistantFocus()
            }
            endThinkingIfOwned(generation)
        }
    }
}
