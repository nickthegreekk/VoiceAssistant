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
        if (msg.isUser) {
            // The human's own turn stays a "user" role: providers require a leading
            // user message, and it keeps the alternation handlers intact.
            ChatMessage("user", formatRoomTranscriptLine(msg))
        } else {
            // Every persona (this one included) is an "assistant" role carrying a
            // name label — never a bare "assistant" turn, which a persona would read
            // as its own unrecoverable output.
            ChatMessage("assistant", formatRoomTranscriptLine(msg))
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

    private fun toRoomMessage(message: ChatMessage) = RoomMessage(
        speakerName = speakerName,
        text = message.text,
        reasoning = message.reasoning,
        isError = message.isError,
        responseTimeMs = message.responseTimeMs
    )

    override fun size(): Int = service._roomMessages.value.size

    override fun append(message: ChatMessage): Int {
        val updated = service._roomMessages.value + toRoomMessage(message)
        service._roomMessages.value = updated
        return updated.size - 1
    }

    override fun updateAt(index: Int, transform: (ChatMessage) -> ChatMessage): Boolean {
        val current = service._roomMessages.value.toMutableList()
        if (index !in current.indices) return false
        val updated = current[index].let { toRoomMessage(transform(toChatMessage(it))) }
        current[index] = updated
        service._roomMessages.value = current
        return true
    }

    override fun removeAt(index: Int) {
        val current = service._roomMessages.value.toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        service._roomMessages.value = current
    }

    override fun peek(index: Int): ChatMessage? =
        service._roomMessages.value.getOrNull(index)?.let { toChatMessage(it) }

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

