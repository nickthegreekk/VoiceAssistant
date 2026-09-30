package com.nikosm.voiceassistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// Phase 3: the Room UI. The room SERVICE (AssistantServiceRooms.kt /
// AssistantServiceRoomTurns.kt) is unchanged by this phase — everything here is
// presentation plus wiring to those already-proven entry points (switchRoom/leaveRoom,
// addRoom/updateRoom/moveRoomMember/removeRoom, startRoomRound/stopRoomRound). Rooms are
// TEXT-ONLY in v1: nothing in this file touches TTS/voice, the karaoke/teleprompter
// reveal, suggested replies, or per-message edit/delete/replay — the room transcript
// renders through ChatMessageBubble's plain path with only the speaker chip added.

// ---------------------------------------------------------------- Pure helpers
// Unit-tested in RoomUiTest; no service, no composition needed.

/** Neutral chip color for a message whose speaker can no longer be resolved. */
internal val ROOM_NEUTRAL_SPEAKER_COLOR = Color(0xFF808080)

/**
 * Chip label for a room transcript message: null for the user's own turns (already
 * visually distinct — own alignment and own-voice bubble color, as in single chat),
 * the speaker's name otherwise.
 */
internal fun roomSpeakerLabel(message: RoomMessage): String? =
    if (message.isUser) null else message.speakerName

/**
 * Color for a speaker's chip: the matching persona's theme color, looked up by NAME
 * (room membership is name-keyed, same as everywhere else in the app).
 *
 * A message from a member removed since it was sent falls back to a neutral gray. This
 * matches the persistence-layer decision: pruned members stay removed (loadRooms never
 * rewrites the transcript), so the transcript can outlive its speakers and must still
 * render — named, but no longer colored.
 */
internal fun roomSpeakerColor(speakerName: String, personas: List<Persona>): Color =
    personas.find { it.name == speakerName }?.themeColor ?: ROOM_NEUTRAL_SPEAKER_COLOR

/**
 * One-line round status shown while a round is active, e.g.
 * "Bob is replying · 2/4 autonomous". Null when no round is running.
 *
 * [memberNames] is the room's own ordered member list, which is what
 * [RoomRoundState.currentMemberIndex] indexes into. An out-of-range index (the room
 * shrank mid-round) degrades to a nameless line rather than crashing or naming the
 * wrong persona.
 */
internal fun roomRoundStatusText(state: RoomRoundState?, memberNames: List<String>): String? {
    if (state == null) return null
    val budget = "${state.autonomousCount}/${state.cap} autonomous"
    val speaker = memberNames.getOrNull(state.currentMemberIndex)
        ?: return "Round in progress · $budget"
    return if (state.phase == RoomRoundPhase.AUTONOMOUS) {
        "$speaker is replying autonomously · $budget"
    } else {
        "$speaker is replying · $budget"
    }
}

/** A persona offered in the editor's "add member" list, with its eligibility verdict. */
internal data class RoomMemberCandidate(
    val persona: Persona,
    val eligibility: RoomEligibility
) {
    val isEligible: Boolean get() = eligibility is RoomEligibility.Eligible

    /** Why this persona can't join, for the greyed-out row. Null when eligible. */
    val blockedReason: String? get() = (eligibility as? RoomEligibility.Blocked)?.reason
}

/**
 * Personas that could be ADDED to a room: everyone not already a member, each carrying
 * the [RoomEligibility] verdict the UI renders (eligible, or greyed out with reason).
 */
internal fun roomMemberCandidates(
    personas: List<Persona>,
    ollamaServerNames: Set<String>,
    cloudProviderNames: Set<String>,
    existingMembers: List<String>
): List<RoomMemberCandidate> = personas
    .filterNot { it.name in existingMembers }
    .map { persona ->
        RoomMemberCandidate(persona, persona.roomEligibility(ollamaServerNames, cloudProviderNames))
    }

/**
 * Cap value the editor's field maps to.
 *
 * Three DISTINCT outcomes, deliberately not sharing a sentinel:
 *  - [ROOM_CAP_INVALID] — the text is not a committable cap (garbage, or negative). The
 *    editor shows this as an error and refuses to commit.
 *  - [ROOM_CAP_INHERIT] — the field is blank, meaning "use the global default".
 *  - anything >= 0 — a real cap, including 0.
 *
 * 0 used to double as the blank/inherit marker, which is what made it impossible to
 * express "initial round only": the parser read a typed 0 as the inherit sentinel and
 * then collapsed it to `null`. "Inherit" now has a sentinel of its own, so 0 survives
 * parsing as the value it is.
 */
internal const val ROOM_CAP_INVALID = Int.MIN_VALUE

/** Blank field = "inherit the global default". Distinct from the cap value 0 on purpose. */
internal const val ROOM_CAP_INHERIT = Int.MIN_VALUE + 1

/** Lowest cap a room may be set to: initial round only, no autonomous continuation. */
internal const val ROOM_CAP_MIN = 0

/**
 * Parse the editor's cap field. Returns [ROOM_CAP_INHERIT] for blank, [ROOM_CAP_INVALID]
 * for anything uncommittable, else the cap itself — [ROOM_CAP_MIN] (`0`) included, since
 * 0 is a real setting ("no autonomous continuation"), not an absence of one.
 */
internal fun parseAutoReplyCap(text: String): Int {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return ROOM_CAP_INHERIT // blank = use the global default
    val value = trimmed.toIntOrNull() ?: return ROOM_CAP_INVALID
    return if (value >= ROOM_CAP_MIN) value else ROOM_CAP_INVALID
}

/**
 * What the cap field should persist for [text], or null when not committable.
 *
 * [ROOM_CAP_INHERIT] is the ONLY path to null — a parsed 0 persists as 0, so clearing a
 * room's cap to "initial round only" is a real, storable value rather than a silent
 * reversion to the global default.
 */
internal fun autoReplyCapOverrideOrNull(text: String): Int? {
    val parsed = parseAutoReplyCap(text)
    if (parsed == ROOM_CAP_INVALID || parsed == ROOM_CAP_INHERIT) return null
    return parsed
}

/** One-line member summary for a room row ("Bob, Alice" / "No members yet"). */
internal fun roomMemberSummary(room: Room): String =
    if (room.memberNames.isEmpty()) "No members yet" else room.memberNames.joinToString(", ")

/**
 * One-line cap summary for a room row, naming the effective source.
 *
 * A cap of 0 is spelled out rather than printed bare: "cap 0/round" on its own reads as
 * "no limit", which is the exact opposite of what it does — it means nobody speaks again
 * on their own.
 */
internal fun roomCapSummary(room: Room): String =
    room.autoReplyCap?.let { cap ->
        if (cap == ROOM_CAP_MIN) "initial round only (no autonomous replies)"
        else "cap $cap/round"
    } ?: "cap ${RoomReplyPolicy.DEFAULT_AUTO_REPLY_CAP}/round (default)"

// ---------------------------------------------------------------- Bottom-sheet entry

/**
 * The "Rooms" section of the persona bottom sheet.
 *
 * Rooms sit BELOW the personas in the picker (personas first, unchanged), because
 * selecting a room is a mode switch into room mode, not a persona switch: it hands the
 * main screen to the room transcript via the caller's onRoomSelected. Nothing here
 * mutates persona state.
 */
@Composable
fun RoomPickerRows(
    rooms: List<Room>,
    activeRoomId: String?,
    accentColor: Color,
    onRoomSelected: (Room) -> Unit
) {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.Groups,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text("Rooms", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
    if (rooms.isEmpty()) {
        Text(
            text = "No rooms yet. Create one in Settings > Rooms.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )
    }
    rooms.forEach { room ->
        val isActive = room.id == activeRoomId
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onRoomSelected(room) }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Forum,
                null,
                tint = if (isActive) accentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = room.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isActive) accentColor else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = roomMemberSummary(room),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isActive) {
                Text("open", style = MaterialTheme.typography.labelSmall, color = accentColor)
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    )
}


// ---------------------------------------------------------------- Settings section

/**
 * Rooms section: the list-then-editor pattern of PersonaSettings (MainActivity.kt),
 * now embedded in an expandable [SettingsExpandableSection] instead of being its own
 * full-screen destination.
 *
 * Rooms are edited in place (every field commits to the service as it changes, on the
 * service's existing debounced save), so there is no "save" button and no draft state
 * to keep in sync with the list.
 */
@Composable
fun RoomSettings(
    service: AssistantService,
    rooms: List<Room>,
    personas: List<Persona>,
    currentThemeColor: Color,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    var editingRoomId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingRoom = rooms.find { it.id == editingRoomId }

    // RoomEditor is its own verticalScroll root; like PersonaEditor it carries its own
    // height bound (RoomEditorMaxHeight) so it still scrolls inside itself now that the
    // section surface no longer caps or scrolls its content.
    SettingsExpandableSection(
        title = "Rooms",
        icon = Icons.Default.Forum,
        expanded = expanded,
        onToggle = onToggle,
        accentColor = currentThemeColor,
        headerAction = {
            IconButton(onClick = {
                // Seed a new room with the first two ELIGIBLE personas: a room needs
                // at least two members to do anything (Room.MIN_MEMBERS), and the
                // ones the user most likely wants are the ones that can speak.
                // Fully editable afterwards, including removing both.
                val seed = service.roomEligiblePersonas()
                    .filter { it.second is RoomEligibility.Eligible }
                    .map { it.first.name }
                    .take(Room.MIN_MEMBERS)
                val room = Room(name = "New Room", memberNames = seed)
                service.addRoom(room)
                editingRoomId = room.id
            }) {
                Icon(Icons.Default.Add, null, tint = currentThemeColor)
            }
        }
    ) {
        if (editingRoom == null) {
            // Back from a deleted room (deleted in the editor, or the persona-deletion
            // cascade removed it): don't hold a stale id.
            if (editingRoomId != null) editingRoomId = null
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (rooms.isEmpty()) {
                    Text(
                        text = "Rooms let two or more personas talk to each other and to you. " +
                            "Pick members from your existing personas; each one keeps its own " +
                            "model and settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                // Plain Column, not LazyColumn: the section surface already scrolls.
                rooms.forEach { room ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { editingRoomId = room.id },
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, currentThemeColor.copy(alpha = 0.3f))
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Forum, null, tint = currentThemeColor, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(room.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    text = "${roomMemberSummary(room)} · ${roomCapSummary(room)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        } else {
            RoomEditor(room = editingRoom, personas = personas, onClose = { editingRoomId = null }, service = service)
        }
    }
}


/**
 * Room editor: name, ordered members (add/remove/reorder), per-room autonomous cap
 * override, and delete-with-transcript-choice. [room] is the LIVE service copy, so
 * member/cap edits committed through the service flow straight back in with no local
 * draft to drift. The name and cap fields are the only locals, mirroring
 * PersonaEditor's edit-then-commit discipline for text fields.
 */
/**
 * Max height of the room editor before it scrolls inside itself.
 *
 * RoomEditor is a `verticalScroll` root, which likewise requires a bounded height —
 * unbounded inside the page LazyColumn throws "Vertically scrollable component was
 * measured with an infinity maximum height constraints". The editor owns this bound
 * itself, independently of the (now uncapped) section surface.
 */
internal val RoomEditorMaxHeight = 420.dp

@Composable
fun RoomEditor(room: Room, personas: List<Persona>, onClose: () -> Unit, service: AssistantService) {
    var name by rememberSaveable(room.id) { mutableStateOf(room.name) }
    var capText by rememberSaveable(room.id) { mutableStateOf(room.autoReplyCap?.toString() ?: "") }
    var showAddMember by rememberSaveable(room.id) { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable(room.id) { mutableStateOf(false) }
    // The delete dialog asks whether the transcript goes with the room (default: yes).
    var deleteTranscript by rememberSaveable(room.id) { mutableStateOf(true) }

    Column(modifier = Modifier.heightIn(max = RoomEditorMaxHeight).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(20.dp))
            }
            Text("Edit room", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty() && trimmed != room.name) {
                        service.updateRoom(room.copy(name = trimmed))
                    }
                    val cap = autoReplyCapOverrideOrNull(capText)
                    // Commit only when committable: blank (inherit), 0 (initial round only)
                    // and positive ints all persist; garbage keeps the current value, never
                    // coerces. `cap != room.autoReplyCap` is a genuine change check — 0 and
                    // null are different states, so a room already at 0 is not rewritten.
                    if (parseAutoReplyCap(capText) != ROOM_CAP_INVALID && cap != room.autoReplyCap) {
                        service.updateRoomCap(room.id, cap)
                    }
                    onClose()
                },
                enabled = name.trim().isNotEmpty() && parseAutoReplyCap(capText) != ROOM_CAP_INVALID
            ) {
                Text("Done")
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Room name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))


        RoomMemberList(
            room = room,
            personas = personas,
            onAddClick = { showAddMember = true },
            service = service
        )

        Spacer(Modifier.height(16.dp))
        Text("Autonomous replies per round", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = capText,
            onValueChange = { capText = it },
            label = { Text("Blank = default (${RoomReplyPolicy.DEFAULT_AUTO_REPLY_CAP})") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (parseAutoReplyCap(capText) != ROOM_CAP_INVALID) {
                    service.updateRoomCap(room.id, autoReplyCapOverrideOrNull(capText))
                }
            }),
            isError = parseAutoReplyCap(capText) == ROOM_CAP_INVALID,
            supportingText = {
                val parsed = parseAutoReplyCap(capText)
                if (parsed == ROOM_CAP_INVALID) {
                    Text("Enter 0 or a positive number, or leave blank for the default.")
                } else if (parsed == ROOM_CAP_INHERIT) {
                    Text(
                        "How many persona-to-persona messages a round may add after everyone " +
                            "has answered you. Blank uses the default of " +
                            "${RoomReplyPolicy.DEFAULT_AUTO_REPLY_CAP}; 0 means nobody replies " +
                            "on their own. Applies to the next round."
                    )
                } else if (parsed == ROOM_CAP_MIN) {
                    // Spell 0 out. It is the one value that reads as "disabled" but is NOT:
                    // it does not switch rooms off, it stops only the self-started chatter.
                    Text(
                        "Initial round only. Everyone answers you once, then the round stops — " +
                            "nobody replies on their own. Rooms still work normally. Applies to " +
                            "the next round."
                    )
                } else {
                    Text(
                        "Everyone answers you once, then up to $parsed persona-to-persona " +
                            "messages are added before the round stops itself. Use 0 for " +
                            "initial round only. Applies to the next round."
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = { showDeleteConfirm = true }) {
            Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(4.dp))
            Text("Delete room", color = MaterialTheme.colorScheme.error)
        }
    }

    if (showAddMember) {
        RoomAddMemberDialog(
            room = room,
            personas = personas,
            service = service,
            onDismiss = { showAddMember = false }
        )
    }

    if (showDeleteConfirm) {
        RoomDeleteDialog(
            room = room,
            deleteTranscript = deleteTranscript,
            onToggleTranscript = { deleteTranscript = it },
            onDismiss = { showDeleteConfirm = false },
            onConfirm = {
                showDeleteConfirm = false
                service.removeRoom(room.id, deleteTranscript)
                onClose()
            }
        )
    }
}

/** Ordered member rows with remove + up/down reorder (the movePersona pattern). */
@Composable
private fun RoomMemberList(room: Room, personas: List<Persona>, onAddClick: () -> Unit, service: AssistantService) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Members (${room.memberNames.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        TextButton(onClick = onAddClick) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add")
        }
    }
    if (room.memberNames.size < Room.MIN_MEMBERS) {
        Text(
            "A room needs at least ${Room.MIN_MEMBERS} members to hold a round.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(4.dp))
    }
    room.memberNames.forEachIndexed { index, memberName ->
        val persona = personas.find { it.name == memberName }
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.2f),
            border = BorderStroke(1.dp, (persona?.themeColor ?: ROOM_NEUTRAL_SPEAKER_COLOR).copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ProviderLogo(icon = persona?.providerIcon ?: "?", isCloud = persona?.isCloud ?: false, imagePath = persona?.iconImageUri)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(memberName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (persona == null) {
                        Text(
                            "No longer available — kept in the transcript only",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                }
                IconButton(
                    onClick = { service.moveRoomMember(room.id, index, index - 1) },
                    enabled = index > 0,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, null, modifier = Modifier.size(20.dp))
                }
                IconButton(
                    onClick = { service.moveRoomMember(room.id, index, index + 1) },
                    enabled = index < room.memberNames.size - 1,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(20.dp))
                }
                IconButton(
                    onClick = {
                        service.updateRoom(room.copy(memberNames = room.memberNames.filterIndexed { i, _ -> i != index }))
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}


/** "Add member" dialog: eligible personas join on tap; ineligible ones are greyed out. */
@Composable
private fun RoomAddMemberDialog(room: Room, personas: List<Persona>, service: AssistantService, onDismiss: () -> Unit) {
    val candidates = roomMemberCandidates(
        personas = personas,
        ollamaServerNames = service.ollamaServerNames(),
        cloudProviderNames = service.cloudProviderNames(),
        existingMembers = room.memberNames
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add member") },
        text = {
            if (candidates.isEmpty()) {
                Text("Everyone is already in this room.")
            } else {
                LazyColumn {
                    items(candidates) { candidate ->
                        val blocked = candidate.blockedReason
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = candidate.isEligible) {
                                    service.updateRoom(room.copy(memberNames = room.memberNames + candidate.persona.name))
                                    onDismiss()
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProviderLogo(
                                icon = candidate.persona.providerIcon,
                                isCloud = candidate.persona.isCloud,
                                imagePath = candidate.persona.iconImageUri
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    candidate.persona.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (candidate.isEligible) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (blocked != null) {
                                    Text(
                                        blocked,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/** Delete confirmation, asking whether the transcript goes with the room. */
@Composable
private fun RoomDeleteDialog(
    room: Room,
    deleteTranscript: Boolean,
    onToggleTranscript: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete room?") },
        text = {
            Column {
                Text("“${room.name}” leaves your Rooms list. Members keep their own chats — only the shared space goes away.")
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleTranscript(!deleteTranscript) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = deleteTranscript, onCheckedChange = onToggleTranscript)
                    Spacer(Modifier.width(8.dp))
                    Text("Also delete its transcript", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}


// ---------------------------------------------------------------- Room mode on the main screen

/**
 * Room transcript + round status + text input. Replaces the single-chat transcript and
 * input while a room is open; the single-chat state is untouched (room mode keeps its
 * own transcript from the service's roomMessages flow, so leaving a room returns to
 * exactly the conversation that was there before).
 *
 * Text-only by design: attachments, voice/TTS, suggested replies and per-message
 * edit/delete/replay are single-chat machinery and are not offered here.
 */
@Composable
fun RoomChatArea(
    room: Room,
    messages: List<RoomMessage>,
    personas: List<Persona>,
    roundState: RoomRoundState?,
    chatFontSize: Float,
    textInput: String,
    onTextInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    focusRequester: FocusRequester,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        RoomRoundStatusLine(roundState = roundState, memberNames = room.memberNames, onStop = onStop)

        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    val isUser = message.isUser
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        ChatMessageBubble(
                            message = ChatMessage(
                                role = if (isUser) "user" else "assistant",
                                text = message.text,
                                reasoning = message.reasoning,
                                isError = message.isError,
                                responseTimeMs = message.responseTimeMs
                            ),
                            displayText = message.text,
                            personaColor = MaterialTheme.colorScheme.primary,
                            fontSize = chatFontSize,
                            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                            speakerLabel = roomSpeakerLabel(message),
                            speakerColor = if (isUser) null else roomSpeakerColor(message.speakerName, personas),
                            modifier = if (isUser) Modifier else Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        RoomInputRow(
            roomName = room.name,
            textInput = textInput,
            onTextInputChange = onTextInputChange,
            onSend = onSend,
            roundActive = roundState != null,
            onStop = onStop,
            focusRequester = focusRequester
        )
    }
}


/** Status line while a round is active: who is replying + autonomous count vs cap. */
@Composable
private fun RoomRoundStatusLine(roundState: RoomRoundState?, memberNames: List<String>, onStop: () -> Unit) {
    val status = roomRoundStatusText(roundState, memberNames) ?: return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Stop, "Stop round", tint = Color.Red, modifier = Modifier.size(20.dp))
        }
    }
}

/** Room text input. Sends into the active room: new user message, new round. */
@Composable
private fun RoomInputRow(
    roomName: String,
    textInput: String,
    onTextInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    roundActive: Boolean,
    onStop: () -> Unit,
    focusRequester: FocusRequester
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedTextField(
            value = textInput,
            onValueChange = onTextInputChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            placeholder = { Text("Message $roomName...", style = MaterialTheme.typography.bodyMedium) },
            shape = RoundedCornerShape(24.dp),
            singleLine = false,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (textInput.isNotBlank()) onSend(textInput)
            }),
            trailingIcon = {
                IconButton(
                    onClick = { if (textInput.isNotBlank()) onSend(textInput) },
                    modifier = Modifier.size(40.dp),
                    enabled = textInput.isNotBlank()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        null,
                        tint = if (textInput.isNotBlank()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        )
        // The existing red Stop control: in room mode it halts the round
        // (stopRoomRound) instead of the single-chat stopEverything.
        if (roundActive) {
            androidx.compose.material3.Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.9f)),
                shape = androidx.compose.foundation.shape.CircleShape,
                modifier = Modifier.size(48.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.Stop, "Stop round", tint = Color.White, modifier = Modifier.size(24.dp))
            }
        }
    }
}

