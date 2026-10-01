package com.nikosm.voiceassistant

import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Spontaneous messages — the turn itself.
 *
 * ## Why nothing here touches the streaming pipeline
 *
 * The plan's O1 decision was to keep this STRUCTURALLY ISOLATED, and the reason is concrete:
 * `performDirectOllamaChat` was hardened this session around a *single live turn* assumption
 * — the H3 placeholder-ownership fixes and the M2 THINKING-state ownership fix both assume
 * that at most one turn is in flight and that `chatRequestSeq` names it. A spontaneous trigger
 * is an independent second actor that can fire while the user is mid-conversation with the
 * SAME persona, which is exactly the shape of the concurrency bug those fixes closed.
 *
 * So this file deliberately does NOT:
 *  - call `nextChatRequestSeq()` (bumping it would SUPERSEDE the user's in-flight turn);
 *  - touch the streamed-placeholder bookkeeping (`adoptStreamedPlaceholder` and friends);
 *  - write `currentCall` (that is the user's Stop handle);
 *  - set `_state` or `updateNotification()` (a background message must not hijack the
 *    foreground notification while the user is recording or listening);
 *  - play any audio.
 *
 * It SHARES the parts that are rules rather than machinery — `composeSystemPrompt`,
 * `resolveHistoryBudget` and `budgetedHistory` — so the prompt is assembled and budgeted
 * exactly like every other turn (see the extraction note in AssistantServiceChat.kt), and it
 * writes through a [TurnSink] like every other turn.
 */

/**
 * What the model replies with when it has nothing worth saying.
 *
 * Silence is a first-class outcome, not a failure: a persona that MUST speak on every cycle
 * spams whenever there is nothing to say, which is the single biggest way this feature would
 * feel broken. The instruction below tells the model to use this sentinel;
 * [parseSpontaneousReply] is what turns it into "send nothing".
 */
internal const val SPONTANEOUS_SILENCE_SENTINEL = "__SILENCE__"

/**
 * The instruction handed to the model as the LAST "user" turn of a spontaneous request.
 *
 * Mirrors [roomTurnInstruction] in shape and for the same reason: the conversation above is
 * the request history, so this one turn supplies the only thing that differs — that nobody
 * has spoken to this persona, and that it is speaking on its own initiative. It is phrased as
 * an instruction because the provider-side alternation handlers need a user turn to end on,
 * while the persona's own system prompt still governs HOW it sounds.
 */
internal fun spontaneousTurnInstruction(persona: Persona): String =
    "Nobody has messaged you. Send your next message to the user now, on your own initiative, " +
        "as ONE message. Reply in character as ${persona.name}, reacting to the conversation " +
        "above and moving it forward. Never write the user's side of the conversation, and never " +
        "repeat a line that is already above. If you have nothing worth saying right now, reply " +
        "with exactly $SPONTANEOUS_SILENCE_SENTINEL and nothing else."

/**
 * One honest line about why there is nothing to reply to, derived from the history the turn
 * is actually given.
 *
 * Deliberately NOT a "time since your last message" line: `ChatMessage` carries no timestamp,
 * so any duration here would be invented. The current date and time reach the model anyway,
 * through `composeSystemPrompt`.
 */
internal fun spontaneousContextLine(historySize: Int): String =
    if (historySize == 0) {
        "You have not talked with this user yet — this is your first message to them."
    } else {
        "The recent conversation is above; it is your turn to add to it."
    }

/**
 * The one-turn system-prompt suffix for a spontaneous turn: the situation, the guardrails the
 * APP owns, then the persona's own optional instruction.
 *
 * The app-authored lines come first and are never replaced — the persona's own text steers
 * topic and tone only, exactly as `roomSystemPromptSuffix` leaves the persona's prompt
 * governing. It is handed to [composeSystemPrompt] as its `suffix`, so it lands LAST in the
 * prompt and is therefore counted by `resolveHistoryBudget` before the request is built —
 * the same contract `TurnContext.systemPromptSuffix` gives skills and room preambles, reached
 * without constructing a TurnContext (this path has no chat generation to hang one on).
 */
internal fun spontaneousSystemPromptSuffix(persona: Persona, historySize: Int): String =
    (
        listOf(
            "You are messaging the user on your own initiative. Nothing they said prompted this.",
            spontaneousContextLine(historySize),
            "Write only your own message, in character as ${persona.name}. Never invent words the user did not write.",
            "If you have nothing worth saying, reply with exactly $SPONTANEOUS_SILENCE_SENTINEL " +
                "and nothing else — choosing silence is always acceptable."
        ) + listOf(persona.spontaneousInstruction.trim()).filter { it.isNotBlank() }
        ).joinToString("\n")

/**
 * The text to persist for a model reply, or null for "send nothing".
 *
 * Accepts the sentinel bare or wrapped in brackets/parens/stars, and accepts a plain "SILENCE"
 * too — models routinely drop the underscores. The cost of that leniency is that a message
 * consisting of literally the word "silence" would be swallowed; that is a far better trade
 * than posting `__SILENCE__` into the user's chat.
 *
 * Pure, so the silence rule is unit-tested without a device.
 */
internal fun parseSpontaneousReply(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val normalized = trimmed
        .removeSurrounding("[", "]")
        .removeSurrounding("(", ")")
        .removeSurrounding("*", "*")
        .trim()
    if (normalized.equals(SPONTANEOUS_SILENCE_SENTINEL, ignoreCase = true)) return null
    if (normalized.equals("SILENCE", ignoreCase = true)) return null
    return trimmed
}

// ---------------------------------------------------------------- the sink

/**
 * Where a spontaneous turn's writes land: the target persona's OWN history.
 *
 * The mirror of [RoomTurnSink], with the same two properties and for the same reasons:
 *
 *  - **Dual-mode, by ownership.** `_messages` holds only the ACTIVE persona's transcript
 *    (`messagesOwnerName`). When the target IS that persona, writes go there so the user
 *    watching the chat sees the message arrive; otherwise they go to a detached copy loaded
 *    from that persona's own persisted history, so a message for someone else can never leak
 *    into the conversation on screen.
 *  - **Fail-closed when detached.** `append` returns -1, and the mutators no-op, once the
 *    persona no longer exists — so a turn for a persona deleted mid-flight cannot recreate
 *    it, and (matching `TurnSink`'s contract) a refused index is treated exactly like an
 *    out-of-range one.
 */
internal class PersonaHistorySink(
    private val service: AssistantService,
    private val personaName: String,
    /** This persona's persisted history, loaded by the caller OFF the main thread. */
    persistedHistory: List<ChatMessage>
) : TurnSink {

    private val detached: MutableList<ChatMessage> = persistedHistory.toMutableList()

    /** True while the target persona's history is the one loaded into `_messages`. */
    private fun isLive(): Boolean = service.messagesOwnerName == personaName

    /** The persona must still exist; a rename or delete mid-flight refuses every write. */
    private fun exists(): Boolean = service.personas.value.any { it.name == personaName }

    override fun size(): Int = if (isLive()) service._messages.value.size else detached.size

    override fun append(message: ChatMessage): Int {
        if (!exists()) return -1
        if (isLive()) {
            val updated = service._messages.value + message
            service._messages.value = updated
            return updated.size - 1
        }
        detached.add(message)
        return detached.size - 1
    }

    override fun updateAt(index: Int, transform: (ChatMessage) -> ChatMessage): Boolean {
        if (!exists()) return false
        if (isLive()) {
            val current = service._messages.value.toMutableList()
            if (index !in current.indices) return false
            current[index] = transform(current[index])
            service._messages.value = current
            return true
        }
        if (index !in detached.indices) return false
        detached[index] = transform(detached[index])
        return true
    }

    override fun removeAt(index: Int) {
        if (!exists()) return
        if (isLive()) {
            val current = service._messages.value.toMutableList()
            if (index !in current.indices) return
            current.removeAt(index)
            service._messages.value = current
            return
        }
        if (index in detached.indices) detached.removeAt(index)
    }

    override fun peek(index: Int): ChatMessage? =
        if (isLive()) service._messages.value.getOrNull(index) else detached.getOrNull(index)

    /**
     * Write the target persona's history to disk.
     *
     * Two different mechanisms on purpose, because the two modes own different storage:
     * a LIVE target is persisted by the service's normal `saveSettings()` (the same debounced
     * path single chat uses, and the same one `onDestroy` flushes), while a DETACHED target is
     * written straight to that persona's own key — there is no live state for the debounce to
     * read, so relying on it would silently drop the message.
     */
    internal fun persist() {
        if (!exists()) return
        if (isLive()) {
            service.saveSettings()
        } else {
            service.settingsManager.savePersonaMessages(personaName, detached.toList())
        }
    }
}

// ---------------------------------------------------------------- generation

/**
 * One NON-STREAMING direct-Ollama completion for a spontaneous turn.
 *
 * Shares the request RULES with the streaming path (`composeSystemPrompt` →
 * `resolveHistoryBudget` → `budgetedHistory`, so injected framing is counted against the
 * window and an over-full prompt is refused rather than silently truncated) and none of its
 * machinery: `stream = false` because there is no bubble to animate, and the call is never
 * published to `currentCall`, which belongs to the user's Stop.
 *
 * Search and RAG are deliberately NOT consulted: both take the turn's text as their QUERY,
 * and a spontaneous turn has no question to search for — passing the framing instruction
 * would retrieve documents about the instruction itself.
 */
private suspend fun AssistantService.performSpontaneousOllamaCall(
    baseUrl: String,
    model: String,
    persona: Persona,
    history: List<ChatMessage>,
    turnInstruction: String,
    suffix: String
): Pair<String, String?> = withContext(Dispatchers.IO) {
    // The same backend normalisation the streaming direct path performs, including the
    // refusal to treat a gateway (8880) as an Ollama backend.
    val stripped = baseUrl.trim()
    val resolvedBackend = if (stripped.isBlank() || stripped.contains(":8880")) {
        _ollamaBaseUrls.value.firstOrNull()?.url ?: ""
    } else {
        stripped.removeSuffix("/")
    }
    if (resolvedBackend.isBlank()) throw Exception("No Ollama server configured")
    val url = if (resolvedBackend.endsWith("/api/chat")) resolvedBackend
    else "${resolvedBackend.trimEnd('/')}/api/chat"

    val systemPrompt = composeSystemPrompt(
        persona = persona,
        currentDateTime = getCurrentDateTimeString(),
        searchContext = "",
        ragContext = "",
        suffix = suffix
    )
    val budget = resolveHistoryBudget(
        contextWindow = persona.numCtx,
        reservedOutput = persona.maxTokens.coerceAtLeast(1024),
        systemPrompt = systemPrompt,
        modelText = turnInstruction
    )
    val trimmed = budgetedHistory(history, budget)

    val messages = JSONArray()
        .put(JSONObject().put("role", "system").put("content", systemPrompt))
    trimmed.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.text)) }
    messages.put(JSONObject().put("role", "user").put("content", turnInstruction))

    val options = JSONObject()
        .put("temperature", persona.temperature)
        .put("top_p", persona.topP)
        .put("top_k", persona.topK)
        .put("repeat_penalty", persona.repeatPenalty)
        .put("num_ctx", persona.numCtx)
        .put("think", persona.enableThinking && isKnownThinkingModel(model))
        .put("num_predict", persona.maxTokens)

    val json = JSONObject()
        .put("model", model)
        .put("stream", false)
        .put("messages", messages)
        .put("options", options)

    val requestBuilder = Request.Builder()
        .url(url)
        .post(json.toString().toRequestBody("application/json".toMediaType()))
    // Same credential lookup the streaming path performs, keyed by the resolved URL.
    _ollamaBaseUrls.value.find { it.url == resolvedBackend }?.let { config ->
        when (config.effectiveAuthType) {
            AuthType.NONE -> { /* no Authorization header */ }
            AuthType.BASIC -> if (!config.username.isNullOrBlank()) {
                requestBuilder.header("Authorization", Credentials.basic(config.username, config.password ?: ""))
            }
            AuthType.API_KEY -> if (!config.apiKey.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
            }
        }
    }

    android.util.Log.d(
        "AssistantService",
        "Spontaneous request: model=$model history=${trimmed.size} budget=$budget url=$url"
    )
    getDynamicClient(persona).newCall(requestBuilder.build()).execute().use { response ->
        if (!response.isSuccessful) throw Exception("Ollama error: ${response.code}")
        val obj = JSONObject(response.body.string())
        val message = obj.optJSONObject("message")
        val content = message?.optString("content").orEmpty()
        val thinking = message?.optString("thinking")?.ifBlank { null }
        content to thinking
    }
}

// ---------------------------------------------------------------- scheduling hooks

/**
 * Apply a spontaneous-message notification tap.
 *
 * Lives on the SERVICE, not in the composable, because everything it touches is service
 * state: the persona list, the selection, and room mode. The composable keeps only the two
 * things only it can do — close its own overlays and mirror the selection — which also makes
 * this decision testable without composing the screen.
 *
 * Returns the selected persona, or `null` when the name no longer exists. **A null return is
 * still a consumed tap**: the caller must clear the request either way, or a stale name would
 * sit there and ambush the next tap.
 *
 * O14: the extra names the persona, never a message — nothing here scrolls anywhere.
 * O4: a tap is always honoured, even mid-turn on another persona; `switchPersona`
 * `stopEverything()`s first, so it interrupts that turn exactly as tapping in the picker does.
 */
internal fun AssistantService.applySpontaneousDeepLink(personaName: String): Persona? {
    val persona = personas.value.find { it.name == personaName }
    if (persona == null) {
        android.util.Log.w("VoiceAssistant", "Deep link for '$personaName' ignored — persona is gone")
        return null
    }
    switchPersona(persona)
    // The notification promised a conversation, so room mode must not stay in front of it.
    // Ordered after the switch only because switchPersona cannot re-enter a room.
    if (activeRoomId.value != null) leaveRoom()
    return persona
}

/**
 * Arm — or leave alone — the unique WorkManager chain for [personaName].
 *
 * [force] decides between two very different jobs:
 *
 *  - `false` (the default, and what `loadSettings` uses): a SAFETY NET. It only arms when
 *    nothing is pending, and never replaces. WorkManager already persists and reboots the
 *    request on its own, so unconditionally re-arming here would restart the countdown on
 *    every service start and could cancel a run that is mid-turn. This is also what picks
 *    up personas that reached storage without passing through `updatePersona`, such as an
 *    `importBackup`.
 *  - `true` (what `updatePersona` uses when `spontaneousScheduleDiffers` says the schedule
 *    actually changed): a RESET. The previous delay was computed from settings that no
 *    longer hold, so both slots are cleared and slot 0 is armed fresh. Clearing first — and
 *    arming into slot 0 rather than "whichever slot is free" — is what keeps a reset from
 *    ever leaving two live chains for one persona.
 *
 * Either way the chain is dropped when the persona is gone, switched off or no longer
 * eligible — the same precondition test the worker re-runs at execution time, evaluated
 * here so the timer stops promptly instead of waiting out the gap.
 *
 * The WorkManager calls run on `Dispatchers.IO`: `getWorkInfosForUniqueWork().get()` blocks,
 * and `serviceScope` is a Main-dispatcher scope.
 */
internal fun AssistantService.refreshSpontaneousSchedule(personaName: String, force: Boolean = false) {
    serviceScope.launch {
        val persona = settingsManager.getPersonas()?.find { it.name == personaName }
        if (persona == null ||
            !persona.spontaneousEnabled ||
            !SpontaneousPolicy.isEligible(persona, ollamaServerNames(), cloudProviderNames())
        ) {
            withContext(Dispatchers.IO) {
                disarmSpontaneousWork(applicationContext, personaName)
                // The chain is gone, so a card still sitting in the shade would open a chat
                // that will never hear from this persona again. The two must move together,
                // which is why they are cleared in one place rather than at each caller.
                cancelSpontaneousNotification(applicationContext, personaName)
            }
            return@launch
        }
        withContext(Dispatchers.IO) {
            if (force) {
                // Order matters here — and unlike the in-run re-arm, this one really is
                // order-sensitive, because WorkManager processes cancels and enqueues in
                // submission order. Clearing both slots first means the fresh arm can only
                // ever leave one live chain.
                disarmSpontaneousWork(applicationContext, personaName)
            } else if (isSpontaneouslyArmed(applicationContext, personaName)) {
                return@withContext
            }
            val now = System.currentTimeMillis()
            armSpontaneousWork(
                context = applicationContext,
                personaName = personaName,
                // A fresh start always begins in slot 0; the worker owns the alternation
                // from there. Any leftover slot-1 entry was cleared by the disarm above (or
                // never existed, on the safety-net path).
                slot = 0,
                // One full gap from NOW: the first message arrives after a gap, not the
                // instant the toggle is switched on, and the delay is clamped into the
                // window so arming never schedules something out of hours.
                delayMillis = nextSpontaneousAttempt(
                    lastAttemptAt = now,
                    gapMillis = SpontaneousPolicy.effectiveGapMinutes(persona) * 60_000L,
                    now = now,
                    zoneId = ZoneId.systemDefault(),
                    windowStartMin = persona.spontaneousWindowStartMin,
                    windowEndMin = persona.spontaneousWindowEndMin
                ) - now
            )
        }
    }
}

/** Stop the chain for a persona that was deleted or renamed out from under it. */
internal fun AssistantService.removeSpontaneousSchedule(personaName: String) {
    serviceScope.launch(Dispatchers.IO) {
        disarmSpontaneousWork(applicationContext, personaName)
        cancelSpontaneousNotification(applicationContext, personaName)
    }
}

/**
 * Safety net over every persona, called once when settings load. Never replaces pending
 * work — see [refreshSpontaneousSchedule].
 */
internal fun AssistantService.refreshAllSpontaneousSchedules() {
    // Reads the IN-MEMORY list, which `loadSettings` has already run the load-time guard
    // over — so this schedules the personas the service will actually serve, not whatever
    // happens to be sitting in `personas_v2` underneath it.
    personas.value
        .filter { it.spontaneousEnabled }
        .forEach { refreshSpontaneousSchedule(it.name, force = false) }
}


// ---------------------------------------------------------------- orchestration

/**
 * What one spontaneous turn produced.
 *
 * The turn's job stops at "produce a message and put it in this persona's history"; turning
 * that into a *delivery* is the caller's, because only the caller knows whether delivery is
 * wanted. That split is what lets the scheduler notify while the editor's send-now button
 * does not: the button's user is already looking at the conversation, so a notification
 * would be a duplicate of the screen in front of them.
 *
 * Transport failures are deliberately NOT a variant here — they are thrown, per the contract
 * established in step 4 and relied on by both callers. See [SpontaneousTurnOutcome.Skipped]
 * for the non-throwing "there was nothing to do" cases.
 */
internal sealed interface SpontaneousTurnOutcome {
    /** A message really did land in the persona's history, with the text that landed. */
    data class Sent(val text: String) : SpontaneousTurnOutcome

    /** The model chose silence. Nothing was written, and nothing should be delivered. */
    data object Silent : SpontaneousTurnOutcome

    /**
     * The turn could not be attributed to a live target (server removed, persona deleted
     * between the guard and the write). Note this is NOT a transport failure: the request was
     * never made, so it must not be reported as "unreachable" or re-armed on that basis.
     */
    data class Skipped(val reason: String) : SpontaneousTurnOutcome
}

/**
 * Run one spontaneous turn for [persona] and land its message in that persona's history.
 *
 * Silence (see [parseSpontaneousReply]) and the skipped cases are normal outcomes and both
 * write NOTHING — a background message that could not be produced must not leave an error
 * bubble in a conversation the user never asked about. Transport failures are logged by the
 * caller and, again, write nothing.
 */
internal suspend fun AssistantService.runSpontaneousTurn(persona: Persona): SpontaneousTurnOutcome {
    // Resolve the "[Server] model" form exactly as the router does. Eligibility was already
    // checked by the caller; a miss here means the server was deleted in between, which is a
    // skip rather than an error.
    val rawModel = persona.model.trim()
    val displayServer = if (rawModel.startsWith("[") && rawModel.contains("] ")) {
        rawModel.substring(1, rawModel.indexOf("]"))
    } else {
        null
    }
    val actualModel = if (displayServer != null) rawModel.substringAfter("] ") else rawModel
    val baseUrl = displayServer?.let { name -> _ollamaBaseUrls.value.find { it.name == name }?.url }
    if (baseUrl == null) {
        android.util.Log.w("AssistantService", "Spontaneous turn skipped — server '$displayServer' is gone")
        return SpontaneousTurnOutcome.Skipped("server '$displayServer' is gone")
    }

    val history = withContext(Dispatchers.IO) {
        settingsManager.getPersonaMessages(persona.name) ?: emptyList()
    }
    val sink = PersonaHistorySink(this, persona.name, history)
    val suffix = spontaneousSystemPromptSuffix(persona, history.size)
    val instruction = spontaneousTurnInstruction(persona)

    val startedAt = System.currentTimeMillis()
    val (content, thinking) = performSpontaneousOllamaCall(
        baseUrl = baseUrl,
        model = actualModel,
        persona = persona,
        history = history,
        turnInstruction = instruction,
        suffix = suffix
    )
    val responseTimeMs = System.currentTimeMillis() - startedAt

    val text = parseSpontaneousReply(content)
    if (text == null) {
        android.util.Log.i("AssistantService", "Spontaneous turn for '${persona.name}' chose silence — nothing sent")
        return SpontaneousTurnOutcome.Silent
    }

    val index = sink.append(
        ChatMessage("assistant", text, thinking, responseTimeMs = responseTimeMs)
    )
    if (index < 0) {
        // The persona was deleted between the guard and the write; the output is dropped
        // rather than filed against a persona that no longer exists.
        android.util.Log.w("AssistantService", "Spontaneous turn for '${persona.name}' discarded — persona gone")
        return SpontaneousTurnOutcome.Skipped("persona '${persona.name}' is gone")
    }
    sink.persist()

    // Record the send HERE rather than at either call site, so the editor's send-now button
    // and the scheduler's worker cannot disagree about when this persona last spoke. This
    // is the ONLY writer of `lastSentAt`: silence (above) and failure (which never reaches
    // here) leave it untouched, which is exactly why a quiet persona keeps its schedule
    // instead of pushing its own gap forward forever.
    val sentAt = System.currentTimeMillis()
    settingsManager.recordSpontaneousSent(
        personaName = persona.name,
        at = sentAt,
        todayDate = spontaneousToday(sentAt, java.time.ZoneId.systemDefault())
    )
    android.util.Log.i(
        "AssistantService",
        "Spontaneous message from '${persona.name}' landed (${text.length} chars, ${responseTimeMs}ms)"
    )
    return SpontaneousTurnOutcome.Sent(text)
}

