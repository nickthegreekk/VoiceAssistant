package com.nikosm.voiceassistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Pure helpers — the text the list rows and the editor render
//
// All pure and free of Compose, so they are unit-testable on the JVM and the copy
// they produce can be asserted verbatim. The UI never derives a summary string
// inline: these are the single definition of what a skill row says.
// ---------------------------------------------------------------------------

/** Debounce for SkillEditor's field auto-save, mirroring PersonaEditor/RoomEditor. */
internal const val SKILL_EDITOR_SAVE_DEBOUNCE_MS = 500L

/**
 * Shown wherever a skill has no usable trigger phrase, because such a skill can be enabled
 * by a persona and still never fire — a silently dead entry is the one state a user cannot
 * diagnose from the transcript.
 */
internal const val NO_TRIGGERS_WARNING = "No triggers — this skill will never fire"

/** Shown where a skill has no instruction text, for the same "enabled but inert" reason. */
internal const val NO_INSTRUCTIONS_WARNING = "No instructions — this skill will never fire"

/**
 * Render the trigger list for editing: one phrase per line, in order.
 *
 * The inverse of [parseTriggerPhrases]. Kept as a named pair so a skill's triggers always
 * round-trip — an editor that reformatted them on open would silently rewrite a list the
 * user never touched.
 */
internal fun formatTriggerPhrases(phrases: List<String>): String = phrases.joinToString("\n")

/**
 * Parse the one-per-line editor field back into a trigger list.
 *
 * Every line is trimmed and blanks are dropped, because triggers are matched as a PREFIX
 * (`startsWith`): an untrimmed " summarize this" would never fire, and a stray trailing
 * comma or blank line would otherwise become a phrase that silently never matches.
 * Order is preserved — a user's phrase order is their own business.
 */
internal fun parseTriggerPhrases(text: String): List<String> =
    text.split('\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/** One-line trigger summary for a skill row; the inert-skill warning when there are none. */
internal fun skillTriggerSummary(skill: Skill): String =
    if (skill.triggerPhrases.isEmpty()) NO_TRIGGERS_WARNING
    else skill.triggerPhrases.joinToString(" · ")

/**
 * How many personas have this skill enabled, for the list row.
 *
 * Shown on the row because a skill is SHARED: deleting one silently changes other personas,
 * so the cost of the delete is not visible from the button that performs it. Saying it here
 * is what makes the delete dialog's question answerable in advance.
 */
internal fun skillUsageSummary(skill: Skill, personas: List<Persona>): String =
    when (val count = personas.count { skill.id in it.enabledSkillIds }) {
        0 -> "Not used by any persona"
        1 -> "Used by 1 persona"
        else -> "Used by $count personas"
    }

/** The personas that would lose this skill if it were deleted, in list order. */
internal fun skillUserNames(skill: Skill, personas: List<Persona>): List<String> =
    personas.filter { skill.id in it.enabledSkillIds }.map { it.name }

/**
 * The delete confirmation's body, naming the personas that would lose the skill.
 *
 * A skill is shared, so "are you sure?" alone does not tell the user what they are agreeing
 * to. Naming the affected personas is the whole point of asking at all — they are the ones
 * whose behavior changes as a side effect of editing a shared library entry.
 */
internal fun skillDeleteConfirmText(skill: Skill, userNames: List<String>): String {
    val who = when (userNames.size) {
        0 -> return "“${skill.name}” leaves your Skills library."
        1 -> userNames.first()
        2 -> "${userNames[0]} and ${userNames[1]}"
        // More than a couple of names is noise; the count carries it instead.
        else -> "${userNames.size} personas"
    }
    return "“${skill.name}” leaves your Skills library. $who will lose it."
}

/**
 * The skill the editor should commit, or null when there is nothing worth saving.
 *
 * Null covers both "nothing changed" (no redundant write on every recomposition) and "the
 * name is blank" (a nameless skill is not a skill, and the editor's Done button is disabled
 * in that state anyway). Triggers are re-parsed here so the TRIMMED list — not the raw field
 * text — is what reaches the model, which makes a stray space harmless rather than a trigger
 * that can never fire.
 */
internal fun skillEditorCommit(
    skill: Skill,
    name: String,
    triggerText: String,
    instructions: String
): Skill? {
    val trimmedName = name.trim()
    if (trimmedName.isEmpty()) return null
    val updated = skill.copy(
        name = trimmedName,
        triggerPhrases = parseTriggerPhrases(triggerText),
        instructions = instructions
    )
    return if (updated != skill) updated else null
}

// ---------------------------------------------------------------------------
// The Skills category screen — list-then-editor, as Rooms and Personas
// ---------------------------------------------------------------------------

/**
 * Skills category screen: the list-then-editor pattern of [RoomSettings], one level below
 * the top-level Settings list.
 *
 * A skill is defined ONCE and shared by any number of personas, so the row carries its usage
 * count: that is the only place a user can learn that deleting it will change someone else's
 * persona before they delete it.
 *
 * Body of [SettingsCategoryScreen], so the page LazyColumn is the single scroller and this is
 * a plain Column — exactly like Rooms. Ordering controls live on the row (the PersonaSettings
 * pattern) because library order is user-visible: it decides the order multiple matching
 * skills are injected in.
 */
@Composable
fun SkillSettings(
    service: AssistantService,
    skills: List<Skill>,
    personas: List<Persona>,
    currentThemeColor: Color
) {
    var editingSkillId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingSkill = skills.find { it.id == editingSkillId }

    // Nothing to seed: a skill has no sensible default content, so "New Skill" opens an
    // empty editor rather than a half-written one.
    fun createSkill() {
        val skill = Skill(name = "New Skill")
        service.addSkill(skill)
        editingSkillId = skill.id
    }

    // Neither the list nor the editor scrolls itself: both are plain Columns over the
    // category page's LazyColumn.
    if (editingSkill == null) {
        // Back from a deleted skill: don't hold a stale id.
        if (editingSkillId != null) editingSkillId = null
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (skills.isEmpty()) {
                Text(
                    text = "Skills are reusable instruction sets. Give one a few trigger " +
                        "phrases and instruction text, then switch it on for any persona: " +
                        "matching messages get those instructions for that turn only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            skills.forEachIndexed { index, skill ->
                SkillRow(
                    skill = skill,
                    personas = personas,
                    accentColor = currentThemeColor,
                    onClick = { editingSkillId = skill.id },
                    onMoveUp = { service.moveSkillUp(skill) },
                    onMoveDown = { service.moveSkillDown(skill) },
                    canMoveUp = index > 0,
                    canMoveDown = index < skills.size - 1
                )
            }
            NewSkillRow(accentColor = currentThemeColor, onClick = { createSkill() })
        }
    } else {
        SkillEditor(
            skill = editingSkill,
            personas = personas,
            onClose = { editingSkillId = null },
            service = service
        )
    }
}

/**
 * One library row: name, trigger summary, usage count, and the reorder controls.
 *
 * The trigger line turns red for a skill with no triggers. That skill is still perfectly
 * legal to keep — a user may be mid-way through writing it — but it can never fire, and a
 * red line is the cheapest possible signal that this is why nothing happened.
 */
@Composable
private fun SkillRow(
    skill: Skill,
    personas: List<Persona>,
    accentColor: Color,
    onClick: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.AutoAwesome, null,
                tint = accentColor, modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    skill.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = skillTriggerSummary(skill),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (skill.triggerPhrases.isEmpty()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = skillUsageSummary(skill, personas),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    "Move ${skill.name} up",
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    "Move ${skill.name} down",
                    modifier = Modifier.size(20.dp)
                )
            }
            Icon(
                Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
    }
}

/** The trailing "add" row, as a category screen has no header row to hang an IconButton on. */
@Composable
private fun NewSkillRow(accentColor: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, null, tint = accentColor, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Text(
                "New Skill",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The skill editor
// ---------------------------------------------------------------------------

/**
 * Skill editor: name, trigger phrases (one per line), instruction text, and delete.
 *
 * [skill] is the LIVE service copy, so the commit targets the service's own index and can
 * never write into a stale position. Text fields are the only local state, mirroring
 * PersonaEditor's edit-then-commit discipline — a half-typed instruction is not a skill, so
 * it must not be written on every keystroke.
 *
 * Commit is debounced and flushed on dispose, for the same reason RoomEditor's is: committing
 * only on an explicit "Done" made every other way of leaving (the back chevron, system back,
 * the category back arrow, dismissing Settings) silently discard the edit.
 *
 * A plain Column with NO scrolling of its own — the category page's LazyColumn is the single
 * scroller, exactly as in RoomEditor and PersonaEditor.
 */
@Composable
fun SkillEditor(
    skill: Skill,
    personas: List<Persona>,
    onClose: () -> Unit,
    service: AssistantService
) {
    var name by rememberSaveable(skill.id) { mutableStateOf(skill.name) }
    var triggerText by rememberSaveable(skill.id) {
        mutableStateOf(formatTriggerPhrases(skill.triggerPhrases))
    }
    var instructions by rememberSaveable(skill.id) { mutableStateOf(skill.instructions) }
    var showDeleteConfirm by rememberSaveable(skill.id) { mutableStateOf(false) }

    val parsedTriggers = remember(triggerText) { parseTriggerPhrases(triggerText) }
    val commit = skillEditorCommit(skill, name, triggerText, instructions)
    // Hoisted above the layout because the delete dialog is a sibling of the Column, not a
    // child of it — and it needs the same list the "enabled on ..." note inside the Column
    // renders, so the two can never disagree about who a delete would affect.
    val users = skillUserNames(skill, personas)
    // The dispose flush never re-keys, so it cannot close over a frozen `commit`: a ref
    // re-assigned on every recomposition carries the live one into onDispose.
    val pendingRef = remember { mutableStateOf<Skill?>(null) }
    pendingRef.value = commit

    fun commitNow() {
        val target = pendingRef.value ?: return
        // Resolve the index from the LIVE list at commit time, not from a captured one: the
        // library can be reordered from the list screen while this editor holds a draft.
        val index = service.skills.value.indexOfFirst { it.id == skill.id }
        if (index != -1) service.updateSkill(index, target)
    }

    LaunchedEffect(commit) {
        val target = commit ?: return@LaunchedEffect
        delay(SKILL_EDITOR_SAVE_DEBOUNCE_MS)
        val index = service.skills.value.indexOfFirst { it.id == skill.id }
        if (index != -1) service.updateSkill(index, target)
    }

    DisposableEffect(Unit) {
        onDispose { commitNow() }
    }

    Column(modifier = Modifier.fillMaxWidth().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                // Named, not null: this is the editor's only way back, and an unlabelled
                // icon is invisible to both TalkBack and any UI test driving this screen.
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Back to skills list",
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                "Edit skill",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    // Commit synchronously so the skill is already stored by the time the
                    // list row comes back, then leave.
                    commitNow()
                    onClose()
                },
                enabled = name.isNotBlank()
            ) {
                Text("Done")
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Skill name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = triggerText,
            onValueChange = { triggerText = it },
            label = { Text("Trigger phrases") },
            placeholder = { Text("One per line") },
            minLines = 3,
            maxLines = 6,
            supportingText = {
                Text(
                    "A message starting with one of these (ignoring case) injects the " +
                        "instructions below for that one turn. Nothing else changes."
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (parsedTriggers.isEmpty()) {
            Text(
                text = NO_TRIGGERS_WARNING,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = instructions,
            onValueChange = { instructions = it },
            label = { Text("Instructions") },
            placeholder = { Text("What the persona should do when this skill fires") },
            minLines = 4,
            maxLines = 10,
            supportingText = {
                Text(
                    "Appended to this persona's system prompt for that one turn only — it is " +
                        "never saved into the conversation or replayed later. Large " +
                        "instructions count against the persona's Context Window Size."
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        if (instructions.isBlank()) {
            Text(
                text = NO_INSTRUCTIONS_WARNING,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(16.dp))

        // Which personas this delete would change, said up front rather than only in the
        // confirmation. Cheap, and it makes the dialog's question answerable beforehand.
        if (users.isNotEmpty()) {
            Text(
                text = "Enabled on ${users.joinToString(", ")}. Deleting it removes it from " +
                    "those personas too.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(8.dp))
        }

        TextButton(onClick = {
            // Ask only when someone would actually lose it. A skill nobody uses deletes
            // outright, so the dialog never appears for a consequence-free action.
            if (users.isEmpty()) {
                service.removeSkill(skill)
                onClose()
            } else {
                showDeleteConfirm = true
            }
        }) {
            Icon(
                Icons.Default.Delete, null,
                modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(4.dp))
            Text("Delete skill", color = MaterialTheme.colorScheme.error)
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete skill?") },
            text = { Text(skillDeleteConfirmText(skill, users)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    service.removeSkill(skill)
                    onClose()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Per-persona selection — embedded in PersonaEditor
// ---------------------------------------------------------------------------

/**
 * The skills this persona has switched on, plus the picker that adds more.
 *
 * Lives in the persona editor because a skill is only ever INJECTED for a persona that
 * enabled it: this is where that decision is made, and it has to sit next to the prompt the
 * skill amends.
 *
 * A dialog + LazyColumn picker rather than a dropdown, mirroring `RoomAddMemberDialog`: a
 * dropdown is single-select by construction, and this has to stay usable as the library grows
 * (a LazyColumn inside a dialog is bounded by the dialog, so it is not a nested scroller
 * fighting the page's).
 *
 * @param onToggle called with (skillId, enabled). The caller must ALSO keep its own
 *   `enabledSkillIds` in sync — see PersonaEditor's note: the service call alone cannot keep
 *   the editor's debounced whole-persona commit from writing a stale value back.
 */
@Composable
fun PersonaSkillSection(
    enabledSkillIds: List<String>,
    onToggle: (skillId: String, enabled: Boolean) -> Unit,
    service: AssistantService
) {
    val skills by service.skills.collectAsState()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    // Resolved against the live library, so a skill deleted elsewhere in Settings simply
    // stops appearing here rather than lingering as an enabled-but-missing row.
    val enabled = skills.filter { it.id in enabledSkillIds }
    val available = skills.filter { it.id !in enabledSkillIds }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (skills.isEmpty()) {
            Text(
                text = "No skills yet — create them in Settings, then switch them on here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        } else {
            enabled.forEach { skill ->
                PersonaSkillRow(skill = skill, onToggle = { onToggle(skill.id, it) })
            }
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { showAddDialog = true },
                shape = RoundedCornerShape(12.dp),
                color = Color.Black.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Add, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (available.isEmpty()) "All skills are on" else "Add skill",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddSkillDialog(
            available = available,
            onPick = { skillId -> onToggle(skillId, true) },
            onDismiss = { showAddDialog = false }
        )
    }
}

/** One enabled skill: name, trigger summary, and a switch that turns it off. */
@Composable
private fun PersonaSkillRow(skill: Skill, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(skill.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                text = if (skill.triggerPhrases.isEmpty()) NO_TRIGGERS_WARNING
                else skillTriggerSummary(skill),
                style = MaterialTheme.typography.labelSmall,
                color = if (skill.triggerPhrases.isEmpty()) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Switch(checked = true, onCheckedChange = onToggle)
    }
}

/**
 * "Add skill" dialog: the skills this persona does NOT have yet, tap to enable.
 *
 * Mirrors `RoomAddMemberDialog`'s shape. A skill that is already on is simply absent rather
 * than greyed out — there is no disabled state to explain, and the list stays short. The
 * dialog stays open on pick, because adding several in a row is the common case.
 */
@Composable
private fun AddSkillDialog(
    available: List<Skill>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add skill") },
        text = {
            if (available.isEmpty()) {
                Text("This persona already has every skill in the library.")
            } else {
                LazyColumn {
                    items(available, key = { it.id }) { skill ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(skill.id) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.AutoAwesome, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    skill.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (skill.triggerPhrases.isEmpty()) NO_TRIGGERS_WARNING
                                    else skillTriggerSummary(skill),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (skill.triggerPhrases.isEmpty())
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}
