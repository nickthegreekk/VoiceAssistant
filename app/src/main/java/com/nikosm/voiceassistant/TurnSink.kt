package com.nikosm.voiceassistant

/**
 * Phase 0 (multi-persona Rooms groundwork): where ONE turn's transcript writes land.
 *
 * The chat pipeline's *backend* concerns (routing, request assembly, streaming, budget
 * selection) are already properties of the [Persona] alone, but its transcript writes were
 * hardcoded against the service-global `_messages` list. A room needs the same pipeline to
 * write into its own transcript instead, so those writes go through this narrow interface.
 *
 * Deliberately NOT part of the sink:
 *  - The H3 placeholder-ownership machinery ([AssistantService.streamedPlaceholderIndex],
 *    adopt/owns/take/release). That is shared, generation-scoped SERVICE state on purpose —
 *    it is what stops a superseded turn from clobbering a newer turn's in-progress bubble.
 *    Keeping it out here means the supersede/stale-unwind fixes are untouched by Rooms.
 *  - Persistence (`saveSettings()`). Single-chat calls keep saving at their existing call
 *    sites; a room turn saves its own history. Folding persistence into the sink would make
 *    it reach back into service-global state and blur that boundary.
 *
 * [SingleChatSink] is the production implementation and is what every pre-existing call path
 * resolves to, so single-chat behavior is unchanged by construction.
 *
 * KNOWN REMAINING SINGLE-CHAT COUPLING (deliberate, and a Phase 1 prerequisite to close):
 *  - The voice flow (`sendAudioToServer`) still writes `_messages` directly. Rooms are
 *    text-only in v1, so no room turn reaches it.
 *  - `performImageGeneration` and `fetchSuggestedReplies` still write/read `_messages`
 *    directly. Phase 1 must exclude image-generator personas from rooms (as it already
 *    must exclude gateway-only ones) or parameterize those two, or an image-gen reply
 *    would land in the single-chat transcript.
 * Neither is reachable from a room turn as v1 is currently scoped.
 */
internal interface TurnSink {
    /** Number of messages currently in the transcript. */
    fun size(): Int

    /** Append [message] and return the index it now occupies. */
    fun append(message: ChatMessage): Int

    /**
     * Replace the message at [index] with `transform(message)`.
     * Returns false (and writes nothing) when [index] is out of range.
     */
    fun updateAt(index: Int, transform: (ChatMessage) -> ChatMessage): Boolean

    /** Remove the message at [index]. No-op when [index] is out of range. */
    fun removeAt(index: Int)

    /** The message at [index], or null when [index] is out of range. */
    fun peek(index: Int): ChatMessage?
}

/**
 * The single-chat [TurnSink]: the active persona's own `_messages` transcript.
 *
 * This is the identity mapping used by every existing code path. Note that each mutation
 * re-assigns `_messages` with an equal-but-new list where the original code did so (and
 * where it rebuilt the list via `mapIndexed`, [updateAt] produces an equal list instead):
 * `_messages` is a `StateFlow`, which conflates equal values, so collectors observe exactly
 * the same emissions as before.
 */
internal class SingleChatSink(private val service: AssistantService) : TurnSink {

    override fun size(): Int = service._messages.value.size

    override fun append(message: ChatMessage): Int {
        val updated = service._messages.value + message
        service._messages.value = updated
        return updated.size - 1
    }

    override fun updateAt(index: Int, transform: (ChatMessage) -> ChatMessage): Boolean {
        val current = service._messages.value.toMutableList()
        if (index !in current.indices) return false
        current[index] = transform(current[index])
        service._messages.value = current
        return true
    }

    override fun removeAt(index: Int) {
        val current = service._messages.value.toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        service._messages.value = current
    }

    override fun peek(index: Int): ChatMessage? = service._messages.value.getOrNull(index)
}

/** The single-chat transcript sink for this service (the pre-Rooms behavior). */
internal fun AssistantService.singleChatSink(): TurnSink = SingleChatSink(this)

/**
 * The sink this turn writes to: its own when a caller supplied one, otherwise the
 * single-chat transcript — so every pre-existing call site is identity-preserving.
 */
internal fun TurnContext.sinkOrDefault(service: AssistantService): TurnSink =
    sink ?: service.singleChatSink()