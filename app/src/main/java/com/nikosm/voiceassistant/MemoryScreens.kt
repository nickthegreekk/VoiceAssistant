package com.nikosm.voiceassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The persona editor's Memory section (PLAN.md §10/M10): the switch, every note the model can see,
 * and the controls to add, forget and clear.
 *
 * **Readable and deletable is the design, not a nicety.** Memory is injected into every later
 * prompt, and the model can append to it; a feature with that power that the user cannot audit is
 * one they have to trust blindly. The note under the switch says the two exclusions in as many
 * words — shared rooms never see these notes, and a Voice Gateway turn never receives them — so the
 * privacy boundary is on screen rather than inferred.
 */
@Composable
internal fun PersonaMemorySection(personaName: String, service: AssistantService) {
    var enabled by remember(personaName) { mutableStateOf(service.personaMemoryEnabled(personaName)) }
    var entries by remember(personaName) { mutableStateOf(service.personaMemory(personaName)) }
    var draft by remember(personaName) { mutableStateOf("") }
    var notice by remember(personaName) { mutableStateOf<String?>(null) }
    val characters = entries.sumOf { it.length }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Long-term memory", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Notes about this conversation's user that ride in the system prompt, so they " +
                        "survive even when old messages are trimmed. The model can add one when you ask " +
                        "it to remember something. Shared rooms never see these notes, and a Voice " +
                        "Gateway turn never receives them. They are sent to whichever model this " +
                        "persona uses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    enabled = on
                    service.setPersonaMemoryEnabled(personaName, on)
                },
            )
        }

        Text(
            "$characters of $MEMORY_MAX_TOTAL_CHARS characters, ${entries.size} of " +
                "$MEMORY_MAX_ENTRIES notes.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        if (enabled) {
            if (entries.isEmpty()) {
                Text(
                    "Nothing remembered yet. Ask the assistant to remember something, or add a note here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            entries.forEachIndexed { index, note ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    TextButton(onClick = {
                        val next = entries.toMutableList().apply { removeAt(index) }
                        entries = next
                        service.savePersonaMemory(personaName, next)
                        notice = null
                    }) { Text("Forget") }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Add a note") },
                    singleLine = false,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        when (val result = addMemoryNote(entries, draft)) {
                            is MemoryAdd.Added -> {
                                entries = result.entries
                                service.savePersonaMemory(personaName, result.entries)
                                draft = ""
                                notice = null
                            }
                            is MemoryAdd.Refused -> notice = result.reason
                        }
                    },
                ) { Text("Add") }
            }

            if (entries.isNotEmpty()) {
                TextButton(onClick = {
                    entries = emptyList()
                    service.savePersonaMemory(personaName, emptyList())
                    notice = "Memory cleared."
                }) { Text("Clear all") }
            }
        }

        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
