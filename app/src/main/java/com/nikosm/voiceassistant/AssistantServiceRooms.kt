package com.nikosm.voiceassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Phase 1: Room definitions + transcript persistence + lifecycle.
//
// Deliberately separate from AssistantServiceChat.kt (the turn loop, Phase 3) and from
// the single-chat plumbing in AssistantService.kt. Everything here is about rooms as
// STORED THINGS: what they are, where their transcript lives, and which one is open.
// No model is called from this file.

// ---------------------------------------------------------------- Eligibility

/**
 * Server-name sets that decide whether a persona can speak in a room. These mirror the
 * routing branches in `sendTextMessageToServer` exactly — see [Persona.roomEligibility].
 */
internal fun AssistantService.ollamaServerNames(): Set<String> =
    _ollamaBaseUrls.value.map { it.name }.toSet()

internal fun AssistantService.cloudProviderNames(): Set<String> =
    (_cloudApis.value + _customCloudApis.value).map { it.name }.toSet()

/** Personas that could join a room right now, with a per-persona reason when they can't. */
internal fun AssistantService.roomEligiblePersonas(): List<Pair<Persona, RoomEligibility>> =
    personas.value.map { it to it.roomEligibility(ollamaServerNames(), cloudProviderNames()) }

/** Resolve a room's members to live personas, in the room's own order. */
internal fun AssistantService.roomMembers(room: Room): List<Persona> =
    room.memberNames.mapNotNull { name -> personas.value.find { it.name == name } }

// ---------------------------------------------------------------- Persistence

/**
 * Save a room transcript, serialized behind the room-history IO lane.
 *
 * Snapshots the list synchronously on the calling (Main) thread — the same discipline the
 * single-chat swap uses — so a later mutation of `_roomMessages` cannot change what this
 * write persists. Keyed by the recorded owner ([roomMessagesOwnerId]) rather than the live
 * active-room id, so a save that lands mid-swap can't file one room's transcript under
 * another's id.
 */
internal fun AssistantService.saveRoomHistory() {
    val owner = roomMessagesOwnerId ?: _activeRoomId.value ?: return
    val snapshot = _roomMessages.value
    val previousIo = roomHistoryIoJob
    roomHistoryIoJob = serviceScope.launch {
        previousIo?.join()
        withContext(Dispatchers.IO) {
            settingsManager.saveRoomMessages(owner, snapshot)
        }
    }
}

/**
 * Load room definitions, pruning members that can no longer speak.
 *
 * The transcript is NEVER pruned: a member that became ineligible (its server was deleted,
 * the persona was switched to an image generator) stops being addressed, but the messages
 * it already said stay in the room. Only membership is corrected.
 */
internal fun AssistantService.loadRooms() {
    val loaded = settingsManager.getRooms() ?: emptyList()
    val ollama = ollamaServerNames()
    val cloud = cloudProviderNames()
    val pruned = loaded.map { room ->
        val kept = room.memberNames.filter { name ->
            personas.value.find { it.name == name }
                ?.roomEligibility(ollama, cloud) is RoomEligibility.Eligible
        }
        if (kept.size == room.memberNames.size) room else room.copy(memberNames = kept)
    }
    _rooms.value = pruned
    // Persist the pruning so it doesn't re-run on every launch, but only when something
    // actually changed — an unconditional save here would rewrite prefs on every start.
    if (pruned != loaded) settingsManager.saveRooms(pruned)
}

// ---------------------------------------------------------------- Room CRUD

internal fun AssistantService.addRoom(room: Room) {
    _rooms.value = _rooms.value + room
    saveSettings()
}

internal fun AssistantService.updateRoom(room: Room) {
    _rooms.value = _rooms.value.map { if (it.id == room.id) room else it }
    saveSettings()
}

internal fun AssistantService.updateRoomCap(roomId: String, cap: Int?) {
    _rooms.value = _rooms.value.map { if (it.id == roomId) it.copy(autoReplyCap = cap) else it }
    saveSettings()
}

/**
 * Re-point every room that lists [from] at [to], because that persona was renamed.
 *
 * Room membership is name-keyed (Q3) to match every other name-keyed identity in the app,
 * so a rename that didn't rewrite membership would silently DROP the persona from each
 * room it belonged to — and `loadRooms` prunes unresolved names, so the loss would be
 * persisted on the next load. The room's transcript is untouched: messages carry the
 * speaker name they were written with, which is the persona's history to keep or lose
 * exactly as a rename already decides for single-chat history.
 */
internal fun AssistantService.renameRoomMemberEverywhere(from: String, to: String) {
    if (from == to) return
    val current = _rooms.value
    if (current.none { it.memberNames.contains(from) }) return
    _rooms.value = current.map { room ->
        if (room.memberNames.contains(from)) {
            room.copy(memberNames = room.memberNames.map { if (it == from) to else it })
        } else room
    }
    saveSettings()
}

/**
 * Drop [name] from every room that lists it, because that persona was deleted.
 *
 * Unlike a rename, a deletion is a genuine loss, so the membership edit is the only
 * correct response — the room keeps existing with its remaining members and its
 * transcript, and rooms that would be left with fewer than two members are removed
 * outright (a room with one member is just that persona's single chat, which the user
 * already has). Reuses [removeRoom] so their transcripts are deleted rather than orphaned.
 */
internal fun AssistantService.removeRoomMemberEverywhere(name: String) {
    val affected = _rooms.value.filter { it.memberNames.contains(name) }
    if (affected.isEmpty()) return
    val stillViable = affected.filter { room ->
        room.memberNames.count { it != name } >= Room.MIN_MEMBERS
    }
    affected.forEach { room ->
        if (room in stillViable) {
            updateRoom(room.copy(memberNames = room.memberNames.filterNot { m -> m == name }))
        } else {
            removeRoom(room.id)
        }
    }
    saveSettings()
}

internal fun AssistantService.moveRoomMember(roomId: String, from: Int, to: Int) {
    _rooms.value = _rooms.value.map { room ->
        if (room.id != roomId) return@map room
        val members = room.memberNames.toMutableList()
        if (from !in members.indices || to !in members.indices) return@map room
        members.add(to, members.removeAt(from))
        room.copy(memberNames = members)
    }
    saveSettings()
}

/**
 * Delete a room, and (by default) its transcript.
 *
 * The transcript delete is queued on the room-history IO lane, joining any in-flight write
 * first, so a swap that is still writing this room's transcript can't resurrect the key
 * after the delete (the same ordering hazard `removePersona` handles for persona history).
 *
 * [deleteTranscript] is exposed for the editor's confirmation, which asks the user whether
 * the transcript should go with the room. Keeping it leaves an orphaned-but-intact history
 * key: nothing reads it (a recreated room gets a fresh UUID and can therefore never inherit
 * an old transcript — see [Room.id]), so it is purely a "not destroyed yet" state, which is
 * what the user asked for. The in-memory detach below happens either way: the deleted room
 * must not stay on screen as the active room just because its history survived.
 */
internal fun AssistantService.removeRoom(roomId: String, deleteTranscript: Boolean = true) {
    val wasActive = _activeRoomId.value == roomId
    _rooms.value = _rooms.value.filterNot { it.id == roomId }
    if (wasActive) {
        // Leaving the deleted room: drop the round and the transcript, mirroring how
        // removing the active persona detaches the in-memory conversation.
        _roomRound.value = null
        _activeRoomId.value = null
        _roomMessages.value = emptyList()
        roomMessagesOwnerId = null
    }
    if (deleteTranscript) {
        val previousIo = roomHistoryIoJob
        roomHistoryIoJob = serviceScope.launch {
            previousIo?.join()
            withContext(Dispatchers.IO) { settingsManager.deleteRoomHistory(roomId) }
        }
    }
    saveSettings()
}

// ---------------------------------------------------------------- Enter / leave

/**
 * Open a room: persists the outgoing room's transcript, then loads the incoming one.
 *
 * Mirrors [AssistantService.switchPersona] — snapshot the outgoing transcript before the
 * id moves, chain the write BEFORE the read in one job, and publish the load only if it
 * is still the newest swap.
 */
internal fun AssistantService.switchRoom(room: Room) {
    if (_activeRoomId.value == room.id) return
    val outgoingOwner = roomMessagesOwnerId ?: _activeRoomId.value
    val outgoingHistory = _roomMessages.value
    val outgoingLoaded = outgoingOwner != null && outgoingOwner == _activeRoomId.value
    val outgoingSaveTarget = outgoingOwner?.takeIf { outgoingLoaded }
    val swapSeq = ++roomHistoryLoadSeq
    val previousIo = roomHistoryIoJob
    _activeRoomId.value = room.id
    roomHistoryIoJob = serviceScope.launch {
        previousIo?.join()
        if (outgoingSaveTarget != null) {
            withContext(Dispatchers.IO) {
                settingsManager.saveRoomMessages(outgoingSaveTarget, outgoingHistory)
            }
        }
        val restored = withContext(Dispatchers.IO) {
            settingsManager.getRoomMessages(room.id)
        }
        if (swapSeq == roomHistoryLoadSeq) {
            _roomMessages.value = restored ?: emptyList()
            roomMessagesOwnerId = room.id
        }
    }
}

/** Leave room mode and return to the normal single-persona chat. */
internal fun AssistantService.leaveRoom() {
    if (_activeRoomId.value == null) return
    val outgoingOwner = roomMessagesOwnerId ?: _activeRoomId.value
    val outgoingHistory = _roomMessages.value
    val outgoingLoaded = outgoingOwner == _activeRoomId.value
    val swapSeq = ++roomHistoryLoadSeq
    val previousIo = roomHistoryIoJob
    _activeRoomId.value = null
    _roomRound.value = null
    roomHistoryIoJob = serviceScope.launch {
        previousIo?.join()
        if (outgoingLoaded && outgoingOwner != null) {
            withContext(Dispatchers.IO) {
                settingsManager.saveRoomMessages(outgoingOwner, outgoingHistory)
            }
        }
        if (swapSeq == roomHistoryLoadSeq) {
            _roomMessages.value = emptyList()
            roomMessagesOwnerId = null
        }
    }
}

/** Clear the active room's transcript (in memory + on disk). */
internal fun AssistantService.clearRoomMessages() {
    if (_activeRoomId.value == null) return
    _roomMessages.value = emptyList()
    saveRoomHistory()
}

