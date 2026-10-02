/**
 * Phase 2: the Room turn loop — orchestration, cap enforcement, and Stop.
 *
 * This file contains NO UI. It is driven by `startRoomRound` and stopped by
 * `stopRoomRound`, both of which the Room UI will call in Phase 3.
 *
 * Design notes
 * ------------
 *  - Each member's turn reuses the EXISTING backend functions (the same ones the
 *    single chat path uses) via the Phase 0 [TurnContext], so request assembly,
 *    budget selection, provider quirks, streaming and Stop-cancellation all behave
 *    identically. Only the transcript source and destination differ.
 *  - Routing is a small local re-decision rather than a call into
 *    `sendTextMessageToServer`, for two reasons: that function appends its own
 *    "user" turn (a room member must NOT re-ask the user's question) and its apply
 *    gate is `isChatContextCurrent(generation, personaName)`, which requires the
 *    ACTIVE persona to be the speaker. A room member is by definition not the active
 *    persona. The gateway branch is omitted because gateway-only personas are
 *    ineligible for rooms (RoomEligibility), so that branch is unreachable here.
 *  - Cap and Stop are checked BEFORE every participant turn, not after, so the
 *    round can never overshoot the cap by even one message.
 */

package com.nikosm.voiceassistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


// ---------------------------------------------------------------------------
// Transcript <-> context conversion
// ---------------------------------------------------------------------------

/**
 * The label used to thread speaker identity through a persona's prompt.
 *
 * The room transcript is fed to the model as an ordinary [ChatMessage] history (so
 * every provider-specific quirk handler — Anthropic's alternating roles, Gemini's
 * user/model mapping — keeps working untouched). Speaker identity therefore has to
 * ride INSIDE the text. Every persona turn, including the current persona's own
 * earlier turns, is labeled the same way, so "whose turn is this" is never ambiguous
 * and a persona can never mistake another persona's reply for its own.
 */
internal fun roomTranscriptLabel(speakerName: String): String =
    if (speakerName == RoomMessage.USER_SPEAKER) "User" else speakerName

internal fun formatRoomTranscriptLine(msg: RoomMessage): String =
    "${roomTranscriptLabel(msg.speakerName)}: ${msg.text}"

/**
 * The user's own most recent message in a room transcript — the text skill triggers are
 * matched against for a room turn — or null when the transcript holds no user message.
 *
 * Searched over the WHOLE transcript rather than "the last entry": a round's later turns
 * follow other members' replies, so the last entry is usually a persona, and taking the last
 * user turn is what keeps a skill fired by the user's question applying to every member
 * that answers it (rather than only the member whose turn happened to be first).
 *
 * Blank text is skipped for the same reason [roomHistoryAsChatMessages] skips it: a
 * streaming placeholder is not something the user said.
 */
internal fun latestUserTurnText(messages: List<RoomMessage>): String? =
    messages.lastOrNull { it.isUser && it.text.isNotBlank() }?.text

/**
 * Build the context history one member sees: the whole room transcript, labeled by
 * speaker. [speakingPersonaName] is accepted to make the intent explicit at the call
 * site — the label set is deliberately identical for every participant, so no persona
 * gets privileged framing of the transcript.
 */
internal fun roomHistoryAsChatMessages(
    messages: List<RoomMessage>,
    @Suppress("UNUSED_PARAMETER") speakingPersonaName: String
): List<ChatMessage> = messages
    // A blank turn is a streaming placeholder not yet filled in (or an error the
    // caller dropped). Never feed one to a model.
    .filter { it.text.isNotBlank() }
    .map { msg ->
        // The room message's own time is carried onto the ChatMessage the model will see, so
        // a Direct-Ollama member's history carries the same [Oct 1, 2026 14:32] prefixes a
        // single chat would (historyTextWithTimestamp reads exactly this field).
        if (msg.isUser) {
            // The human's own turn stays a "user" role: providers require a leading
            // user message, and it keeps the alternation handlers intact.
            ChatMessage("user", formatRoomTranscriptLine(msg), timestamp = msg.timestamp)
        } else {
            // Every persona (this one included) is an "assistant" role carrying a
            // name label — never a bare "assistant" turn, which a persona would read
            // as its own unrecoverable output.
            ChatMessage("assistant", formatRoomTranscriptLine(msg), timestamp = msg.timestamp)
        }
    }

// ---------------------------------------------------------------------------
// The room transcript sink
// ---------------------------------------------------------------------------

/**
 * [TurnSink] that writes a member's turn into the ROOM transcript.
 *
 * The Phase 0 pipeline (streaming placeholder, budget selection, provider quirks) is
 * shared with single chat and speaks only [ChatMessage]; this is the adapter that lands
 * those writes in the room transcript under the correct speaker. Speaker identity is
 * bound at construction, so no code path can attribute a turn to the wrong persona.
 *
 * Index semantics match the single-chat sink: the index returned by [append] is the
 * position in the room transcript, which is what the H3 placeholder bookkeeping records.
 */
internal class RoomTurnSink(
    private val service: AssistantService,
    private val roomId: String,
    private val speakerName: String
) : TurnSink {

    private fun toRoomMessage(message: ChatMessage, existing: RoomMessage? = null) = RoomMessage(
        // An in-place update must keep the ORIGINAL identity and creation time.
        //
        // The room transcript's LazyColumn keys its items on `id`, so minting a fresh UUID
        // per streaming chunk would make every chunk read as remove+insert: the bubble is
        // destroyed and rebuilt on each token, which throws away its scroll anchoring and
        // is a large part of why following a live round felt broken. `timestamp` is held for
        // the same reason — it is when the message was written, not when its last chunk
        // arrived. `existing` is null only on append, which is genuinely a new message.
        //
        // This is the mirror of what `toChatMessage` documents just below: the sink's
        // contract is about text/reasoning/error/timing, and an in-place edit must not
        // re-stamp identity in EITHER direction.
        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
        speakerName = speakerName,
        text = message.text,
        reasoning = message.reasoning,
        isError = message.isError,
        responseTimeMs = message.responseTimeMs,
        timestamp = existing?.timestamp ?: System.currentTimeMillis()
    )

    override fun size(): Int = if (isAttached()) service._roomMessages.value.size else 0

    /**
     * Phase 2: a sink is attached only while its room's transcript is the LOADED one
     * ([AssistantService.roomMessagesOwnerId]). `switchRoom`/`leaveRoom` move that owner
     * (and, once the load lands, replace `_roomMessages` wholesale), so a turn still in
     * flight — or a late streaming chunk from one — must never write into the incoming
     * room's transcript. Detached writes are refused, which mirrors what single-chat
     * writes into a cleared/absent transcript already do: the late output is dropped
     * rather than misfiled into another conversation.
     */
    private fun isAttached(): Boolean = service.roomMessagesOwnerId == roomId

    override fun append(message: ChatMessage): Int {
        // -1 = "refused, nothing written": the H3 bookkeeping treats it exactly as it
        // treats an out-of-range index (updateAt/peek/removeAt all reject it), so a
        // detached turn cannot repaint or strand a bubble in the wrong transcript.
        if (!isAttached()) return -1
        val updated = service._roomMessages.value + toRoomMessage(message)
        service._roomMessages.value = updated
        return updated.size - 1
    }

    override fun updateAt(index: Int, transform: (ChatMessage) -> ChatMessage): Boolean {
        if (!isAttached()) return false
        val current = service._roomMessages.value.toMutableList()
        if (index !in current.indices) return false
        // `existing` carries the identity/timestamp forward — see toRoomMessage.
        val updated = current[index].let {
            toRoomMessage(transform(toChatMessage(it)), existing = it)
        }
        current[index] = updated
        service._roomMessages.value = current
        return true
    }

    override fun removeAt(index: Int) {
        if (!isAttached()) return
        val current = service._roomMessages.value.toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        service._roomMessages.value = current
    }

    override fun peek(index: Int): ChatMessage? =
        if (!isAttached()) null
        else service._roomMessages.value.getOrNull(index)?.let { toChatMessage(it) }

    // Round-trips a room message through the ChatMessage shape the shared pipeline
    // expects. The id/timestamp are not carried across: the sink's contract is about
    // text/reasoning/error/timing, and a streaming update must not re-stamp the
    // message's creation time or mint a new identity for an in-place edit.
    private fun toChatMessage(message: RoomMessage) = ChatMessage(
        role = "assistant",
        text = message.text,
        reasoning = message.reasoning,
        isError = message.isError,
        responseTimeMs = message.responseTimeMs
    )
}

// ---------------------------------------------------------------------------
// Round lifecycle
// ---------------------------------------------------------------------------

/**
 * Start a round in [room]: append the user's message, then run the members' turns on a
 * background job. Returns false (writing nothing) when the round could not start — blank
 * text, or the room's transcript not being the loaded one.
 *
 * The user's turn is part of the round: it is what every member answers, and appending it
 * here (before the first member's turn, and on the caller's thread) means the transcript
 * every member reads already ends with it.
 */
internal fun AssistantService.startRoomRound(room: Room, text: String): Boolean {
    val userText = text.trim()
    if (userText.isEmpty()) return false
    // A round may only start once the ACTIVE room's history is actually the loaded
    // transcript: `switchRoom` publishes `_roomMessages` asynchronously, and a round that
    // started before the load landed would have its user message replaced by that load.
    if (_activeRoomId.value != room.id || roomMessagesOwnerId != room.id) {
        android.util.Log.d(
            "AssistantService",
            "Room round not started — room ${room.id} is not the loaded transcript " +
                "(active=${_activeRoomId.value}, owner=$roomMessagesOwnerId)"
        )
        return false
    }

    // ONE generation for the whole round: every member's turn shares it, so Stop
    // (`nextChatRequestSeq`) and every newer round invalidate all of them through the
    // existing chat-request mechanism — no second sequencing to keep in sync, and the
    // streaming/apply gates that already guard single chat guard the room too.
    val generation = nextChatRequestSeq()
    roomRoundGeneration = generation

    _roomMessages.value = _roomMessages.value + RoomMessage(
        speakerName = RoomMessage.USER_SPEAKER,
        text = userText
    )
    saveRoomHistory()
    _roomRound.value = RoomRoundState(
        roomId = room.id,
        phase = RoomRoundPhase.USER_TURN,
        currentMemberIndex = 0,
        cap = RoomReplyPolicy.capFor(room)
    )

    // Supersede: a round already in flight is cancelled exactly the way Stop cancels a turn
    // — its call is closed, and the generation bumped just above makes whatever it still
    // produces undeliverable — and its job is JOINED before this round's first turn starts,
    // so at most one provider call is ever in flight for a room and `currentCall` can never
    // be left pointing at the previous round's call.
    currentCall?.cancel()
    val superseded = roomRoundJob
    roomRoundJob = serviceScope.launch {
        superseded?.join()
        runRoomRound(room, generation)
    }
    return true
}

/**
 * Run one round to completion. Reached through [startRoomRound], which owns the generation,
 * the user's message and the published round state.
 *
 * 1. INITIAL ROUND — every member answers the user, in the room's own order. Members,
 *    eligibility and prompts are resolved FRESH for every turn, so a persona deleted (or
 *    whose server was removed) mid-round simply stops speaking instead of failing.
 * 2. AUTONOMOUS CONTINUATION — LOCAL members only (Q1: a self-initiated cloud turn is a
 *    paid turn nobody asked for), in a rotating order, capped by [RoomReplyPolicy.capFor] on
 *    the TOTAL number of autonomous messages. Cap and Stop are both re-checked BEFORE each
 *    turn, so a round can never overshoot the cap by even one message.
 *
 * Turns are strictly sequential: each one is awaited, so a persona always reads everything
 * the others said before it speaks. Nothing is spoken aloud (the room is text-only and
 * autonomous turns must never make noise on their own).
 */
internal suspend fun AssistantService.runRoomRound(room: Room, generation: Long) {
    try {
        for ((index, name) in room.memberNames.withIndex()) {
            if (!isRoundLive(room, generation)) return
            val persona = personas.value.find { it.name == name } ?: continue
            val eligibility = persona.roomEligibility(ollamaServerNames(), cloudProviderNames())
            if (eligibility !is RoomEligibility.Eligible) continue
            publishRoundState(room, generation) {
                it.copy(phase = RoomRoundPhase.INITIAL_ROUND, currentMemberIndex = index)
            }
            runRoomTurn(room, persona, cloud = eligibility.cloud, generation = generation)
        }

        val cap = RoomReplyPolicy.capFor(room)
        var emitted = 0
        var cursor = 0
        while (emitted < cap) {
            if (!isRoundLive(room, generation)) return
            val speakers = autonomousSpeakers(room)
            if (speakers.isEmpty()) return
            // Rotate through the eligible locals: nobody holds the floor for two turns in a
            // row, and the rotation is re-derived from what is eligible right now.
            val persona = speakers[cursor % speakers.size]
            cursor++
            publishRoundState(room, generation) {
                it.copy(
                    phase = RoomRoundPhase.AUTONOMOUS,
                    currentMemberIndex = room.memberNames.indexOf(persona.name).coerceAtLeast(0),
                    autonomousCount = emitted
                )
            }
            runRoomTurn(room, persona, cloud = false, generation = generation)
            emitted++
            publishRoundState(room, generation) { it.copy(autonomousCount = emitted) }
        }
    } finally {
        // Ownership-gated: a superseded round unwinding late must not blank its successor's
        // round state, which is why only the recorded owner may settle (see
        // roomRoundGeneration and the same rule in the H3 placeholder helpers).
        settleRoomRound(generation)
    }
}

/**
 * Run ONE member's turn, landing its reply in the room transcript.
 *
 * The turn hands the shared pipeline a [TurnContext] whose `history` is the room
 * transcript, whose `systemPromptSuffix` is the room preamble, and whose `sink` is this
 * member's [RoomTurnSink] — so request assembly, budget selection, provider quirks,
 * streaming and Stop-cancellation are the single-chat code, reached with room data.
 */
internal suspend fun AssistantService.runRoomTurn(
    room: Room,
    persona: Persona,
    cloud: Boolean,
    generation: Long
) {
    // Snapshot the transcript as it stands NOW: the user's message (or the previous
    // member's reply) is what this member must react to.
    val transcript = _roomMessages.value
    val turnContext = TurnContext(
        history = roomHistoryAsChatMessages(transcript, persona.name),
        // Skills (Q1/Q7): the SAME injection point single-chat and voice use — rooms are
        // simply another way of talking TO a persona, so a persona must not behave
        // differently here (Q7). Matched against the user's OWN last message in the
        // transcript, never against roomTurnInstruction: that is a fixed internal
        // instruction issued to every member of every round, so matching against it would
        // make every skill with a common-word trigger fire on every turn in every room.
        // The user's message still reads naturally to a human, so the text flow and the
        // room flow fire on the same words.
        systemPromptSuffix = roomSystemPromptSuffix(room, persona),
        sink = RoomTurnSink(this, room.id, persona.name)
    ).let { base ->
        latestUserTurnText(transcript)?.let { base.withSkills(it, persona, _skills.value) } ?: base
    }
    val startTime = System.currentTimeMillis()

    if (cloud) {
        // The same non-streaming pipeline single chat uses for a cloud persona:
        // `currentTurnInHistory = false` because the room history is passed in full (the
        // user's turn is already its labeled last entry), and `allowPlayback = false`
        // because a room is text-only — an autonomous turn in particular must never make
        // noise. Awaited, so the next member's turn starts only once this reply is in the
        // transcript.
        performCloudChat(
            text = roomTurnInstruction(persona, room),
            persona = persona,
            useDeviceVoice = false,
            startTime = startTime,
            currentTurnInHistory = false,
            generation = generation,
            turnContext = turnContext,
            allowPlayback = false
        ).join()
        // performCloudChat's apply block wrote the bubble through the sink (or discarded it
        // when superseded); either way the room transcript wants persisting.
        if (isRoundLive(room, generation)) saveRoomHistory()
        return
    }

    runDirectOllamaRoomTurn(room, persona, turnContext, generation, startTime)
}

/**
 * The direct-Ollama half of a room turn. Mirrors the direct branch of
 * `sendTextMessageToServer` — including its terminal cleanup, which is why the placeholder
 * handling here goes through the SINK (`removeBlankPlaceholderSvc` walks the single-chat
 * `_messages` list and would delete a row from the wrong conversation).
 */
private suspend fun AssistantService.runDirectOllamaRoomTurn(
    room: Room,
    persona: Persona,
    turnContext: TurnContext,
    generation: Long,
    startTime: Long
) {
    val sink = turnContext.sink ?: return
    // The same "[Server] model" resolution the router performs. [Persona.roomEligibility]
    // already guaranteed such a form, naming a CONFIGURED Ollama server, so a miss here means
    // the server was deleted between the eligibility check and this call — skip silently
    // rather than erroring into the transcript.
    val rawModel = persona.model.trim()
    val displayServer = if (rawModel.startsWith("[") && rawModel.contains("] ")) {
        rawModel.substring(1, rawModel.indexOf("]"))
    } else null
    val baseUrl = displayServer?.let { name -> _ollamaBaseUrls.value.find { it.name == name }?.url }
    if (baseUrl == null) {
        android.util.Log.d("AssistantService", "Room turn skipped — server '$displayServer' is gone")
        return
    }
    val actualModel = rawModel.substringAfter("] ")

    try {
        val response = withContext(Dispatchers.IO) {
            performDirectOllamaChat(
                baseUrl = baseUrl,
                model = actualModel,
                text = roomTurnInstruction(persona, room),
                persona = persona,
                currentTurnInHistory = false,
                generation = generation,
                turnContext = turnContext
            )
        }
        // A superseded/stopped turn must not finalize: whatever it streamed stays as the real
        // utterance it was (D2), and ownership is released so a later round can adopt its own
        // placeholder.
        if (!isRoundLive(room, generation)) {
            rejectRoomTurnReply(sink, generation)
            return
        }
        val responseTimeMs = System.currentTimeMillis() - startTime
        // H3: consume/finalize OUR OWN placeholder in place (one final write, never a second
        // append). A null or refused index (-1: detached sink) falls back to an append, which
        // is itself refused when detached — nothing leaks into another room's transcript.
        val streamedIdx = takeStreamedPlaceholderIfOwned(generation)
        clearStreamingTextIfOwned(generation)
        val finalized = streamedIdx != null && streamedIdx >= 0 && sink.updateAt(streamedIdx) { m ->
            m.copy(text = response.first, reasoning = response.second, responseTimeMs = responseTimeMs)
        }
        if (!finalized) {
            sink.append(ChatMessage("assistant", response.first, response.second, responseTimeMs = responseTimeMs))
        }
        saveRoomHistory()
    } catch (e: CancellationException) {
        // Cooperative cancellation must keep unwinding (service teardown, scope cancel).
        throw e
    } catch (e: StreamCancelledByUserException) {
        // Stop (or a successor round) interrupted a mid-stream turn. D2: the trailing
        // placeholder already holds the partial text (updated per chunk), so it stays as this
        // persona's real utterance and the room is persisted. A superseded turn keeps
        // nothing — its partial belongs to a round the user already abandoned.
        if (isRoundLive(room, generation)) {
            if (e.partialContent.isBlank()) rejectRoomTurnReply(sink, generation) else saveRoomHistory()
        } else {
            rejectRoomTurnReply(sink, generation)
        }
        android.util.Log.d("AssistantService", "Room stream cancelled by user — partial kept (${e.partialContent.length} chars)")
    } catch (e: StreamPersonaChangedException) {
        // Unreachable for a room turn — the persona-changed guard in performDirectOllamaChat
        // is single-chat only (a room speaker is never the active persona). Kept so
        // re-enabling it could never strand a room placeholder.
        rejectRoomTurnReply(sink, generation)
    } catch (e: Exception) {
        if (!isRoundLive(room, generation)) {
            android.util.Log.d("AssistantService", "Room turn superseded — discarding failure: ${e.message}")
        } else if (isUserCancellation(e)) {
            // The user deliberately stopped this turn — no error bubble.
            android.util.Log.d("AssistantService", "Room turn cancelled by user — no error bubble")
        } else {
            android.util.Log.e("AssistantService", "Room turn failed", e)
            sink.append(ChatMessage("assistant", "Error: ${e.message ?: "Unknown error"}", isError = true))
            saveRoomHistory()
        }
    } finally {
        // Terminal cleanup, exactly as the single-chat path does it — ownership-gated, and
        // through the sink. This is also what drops a blank placeholder left by a mid-stream
        // failure that was not an IOException (a malformed stream line, say), which is the
        // room equivalent of `removeBlankPlaceholderSvc`.
        rejectRoomTurnReply(sink, generation)
    }
}




// ---------------------------------------------------------------------------
// Prompts
// ---------------------------------------------------------------------------

/**
 * The turn instruction handed to a member as the LAST "user" turn of its request.
 *
 * The room transcript already carries the real user message (labeled `User: ...`), so a
 * member must NOT be asked the user's question again — [roomHistoryAsChatMessages] is the
 * request history and this text is the user turn that completes it. It is phrased as an
 * instruction because the provider-side alternation handlers (Anthropic, Gemini) need a
 * user turn to end on, while the persona's own system prompt still governs HOW it answers.
 */
internal fun roomTurnInstruction(persona: Persona, room: Room): String =
    "It is your turn to speak in ${room.name.ifBlank { "the group conversation" }}. " +
        "Reply in character as ${persona.name} with ONE message addressed to the other " +
        "participants and the user. React to the latest messages in the transcript above and " +
        "move the conversation forward. Never write anyone else's lines, and never repeat a " +
        "line that is already in the transcript."

/**
 * The room preamble appended to a member's own system prompt
 * ([TurnContext.systemPromptSuffix]) — the persona's prompt is never replaced, only told
 * that other speakers exist.
 *
 * Without it, a persona reading `Bob: ...` lines in its own history (the transcript is
 * delivered as ordinary assistant turns, so it is otherwise indistinguishable from its own
 * output) would answer as if it had said them, or would try to write Bob's part itself.
 */
internal fun roomSystemPromptSuffix(room: Room, persona: Persona): String {
    val others = room.memberNames.filter { it != persona.name }
    val othersLine = if (others.isEmpty()) {
        "No other participants are currently available."
    } else {
        "The other participants are: ${others.joinToString(", ")}."
    }
    val heading = "You are taking part in a GROUP conversation" +
        (if (room.name.isBlank()) "" else " called \"${room.name}\"") + "."
    return listOf(
        heading,
        "Participants: ${(listOf("User") + room.memberNames).joinToString(", ")}.",
        "You are ${persona.name}. $othersLine",
        "Every line of the transcript is labeled with its speaker's name — \"User\" is the " +
            "human. Only ever write lines as ${persona.name}, one message per turn."
    ).joinToString("\n")
}

// ---------------------------------------------------------------------------
// Round bookkeeping: liveness, published state, settling
// ---------------------------------------------------------------------------

/**
 * True while [generation] still owns the round in [room] — the one question every turn asks
 * before it does anything observable (start a provider call, finalize a bubble, persist).
 *
 * Four independent things end a round's right to keep speaking, and all four are checked:
 *  - the round was SUPERSEDED (a newer round recorded itself in `roomRoundGeneration`);
 *  - the user pressed Stop, or a newer single-chat request started — both bump the shared
 *    chat-request sequence, which is exactly why the room needs no sequencer of its own;
 *  - the user left or switched rooms (`_activeRoomId`);
 *  - the loaded transcript is no longer this room's (`roomMessagesOwnerId`), i.e. the room's
 *    messages were swapped out from under the turn.
 */
internal fun AssistantService.isRoundLive(room: Room, generation: Long): Boolean {
    if (roomRoundGeneration != generation) return false
    if (!isChatRequestCurrent(generation)) return false
    if (_activeRoomId.value != room.id) return false
    if (roomMessagesOwnerId != room.id) return false
    // Round-level Stop: publishing the flag is what stops an in-flight turn from finalizing
    // and what makes the loop stop starting new turns even before the sequence settles.
    return _roomRound.value?.stopRequested != true
}

/**
 * Publish a round-state change on behalf of the round that OWNS the state.
 *
 * Ownership-gated for the same reason [settleRoomRound] is: `_roomRound` is service-global
 * while rooms are switchable, so a superseded round that is still unwinding could otherwise
 * overwrite its successor's phase and counters with stale numbers.
 */
private fun AssistantService.publishRoundState(
    room: Room,
    generation: Long,
    transform: (RoomRoundState) -> RoomRoundState
) {
    if (roomRoundGeneration != generation) return
    val current = _roomRound.value ?: return
    // A round can only ever publish state for its OWN room: a leftover state for another room
    // means the user already moved on, and this round's numbers are meaningless there.
    if (current.roomId != room.id) return
    _roomRound.value = transform(current)
}

/**
 * Release the round's ownership of `_roomRound`: only the recorded owner may clear it.
 *
 * A superseded round unwinding late must not blank its successor's state — the same
 * record-the-owner rule the H3 placeholder helpers follow. Deliberately does NOT bump the
 * chat-request sequence: the round is over, but the turn that just unwound still had to
 * settle its own placeholder bookkeeping, which is ownership-gated rather than
 * sequence-gated.
 */
internal fun AssistantService.settleRoomRound(generation: Long) {
    if (roomRoundGeneration != generation) return
    roomRoundGeneration = null
    roomRoundJob = null
    _roomRound.value = null
}

/**
 * The members that may take an autonomous turn right now, in room order.
 *
 * Resolved FRESH before every autonomous turn, so a member that became ineligible mid-round
 * (persona deleted, model cleared, its Ollama server removed) stops speaking rather than
 * failing a turn. Q1: cloud members are excluded via
 * [RoomReplyPolicy.participatesInAutonomousRounds] — they answered the user in the initial
 * round, and never spend money talking to each other unsupervised.
 */
private fun AssistantService.autonomousSpeakers(room: Room): List<Persona> {
    val ollama = ollamaServerNames()
    val cloud = cloudProviderNames()
    return room.memberNames
        .mapNotNull { name -> personas.value.find { it.name == name } }
        .filter { persona ->
            val eligibility = persona.roomEligibility(ollama, cloud)
            eligibility is RoomEligibility.Eligible &&
                RoomReplyPolicy.participatesInAutonomousRounds(eligibility.cloud)
        }
}

/**
 * Stop the round that is running (the room's Stop button), leaving the user in room mode.
 *
 * Two things happen, and both are needed:
 *  1. the shared generation is bumped, exactly as a Stop in single chat does — the turn
 *     currently in flight can no longer land its reply, and the loop's next `isRoundLive`
 *     check fails, so no further participant turn starts;
 *  2. the round state is flagged `stopRequested` (phase STOPPING) and the in-flight provider
 *     call is cancelled, so the streaming turn is interrupted rather than left to finish its
 *     token stream.
 *
 * Deliberately does NOT clear `_roomRound`: the round's own turn still has to unwind (settle
 * its placeholder bookkeeping and keep whatever text it already streamed as its real
 * utterance, D2), and the loop clears the state itself through [settleRoomRound] as it exits.
 * That is also why the room cannot be left showing a stale Stop button: the loop is the only
 * settler, and it exits whenever `isRoundLive` returns false.
 */
internal fun AssistantService.stopRoomRound() {
    if (_roomRound.value == null) return
    nextChatRequestSeq()
    markRoomRoundStopped()
}

/**
 * The global Stop button's room hook (called from `stopEverything`).
 *
 * Stop has already bumped the chat-request sequence, which by itself makes every room turn
 * discard its output; flagging the round adds the two things the sequence cannot express:
 * the UI's STOPPING phase, and an explicit intent that survives in-flight turns which cannot
 * be cancelled. A no-op when no round is running, so the single-chat Stop path is untouched.
 */
internal fun AssistantService.markRoomRoundStopped() {
    val state = _roomRound.value ?: return
    if (state.stopRequested) return
    _roomRound.value = state.copy(phase = RoomRoundPhase.STOPPING, stopRequested = true)
    currentCall?.cancel()
}

/**
 * H3 for the ROOM transcript: stop a turn's output from being finalized, and always release
 * its streaming-placeholder ownership.
 *
 * Whatever the turn already streamed STAYS on screen as this persona's real utterance (D2 —
 * the same treatment a stopped single-chat turn gets); it is simply not finalized, and the
 * caller persists the room only when the turn is still live. The one thing that IS removed is
 * a placeholder that never received a single character, which would otherwise sit in the
 * transcript as an empty bubble (the room's equivalent of `removeBlankPlaceholderSvc`).
 *
 * Deliberately sink-based rather than reusing `removeBlankPlaceholderSvc`, which walks the
 * single-chat `_messages` list: for a room turn the two transcripts are different lists, so
 * the service-level helper would remove a row from the wrong conversation.
 */
private fun AssistantService.rejectRoomTurnReply(sink: TurnSink, generation: Long) {
    val idx = streamedPlaceholderIndexIfRecorded(generation)
    if (idx != null && idx >= 0 && sink.peek(idx)?.text?.isBlank() == true) sink.removeAt(idx)
    releaseStreamedPlaceholderIfOwned(generation)
    clearStreamingTextIfOwned(generation)
}

