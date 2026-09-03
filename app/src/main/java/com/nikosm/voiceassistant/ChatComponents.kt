package com.nikosm.voiceassistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    displayText: String,
    personaColor: Color,
    isCompact: Boolean = false,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    onLongClick: (() -> Unit)? = null,
    onReplayAudio: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"
    Column(
        horizontalAlignment = horizontalAlignment,
        modifier = modifier
    ) {
        if (!message.reasoning.isNullOrBlank()) {
            var expandedReasoning by remember { mutableStateOf(false) }
            Surface(
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .clickable { expandedReasoning = !expandedReasoning },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (expandedReasoning) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Thought process",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (message.responseTimeMs != null) {
                            Spacer(Modifier.width(8.dp))
                            ResponseTimeBadge(
                                responseTimeMs = message.responseTimeMs,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (expandedReasoning) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = message.reasoning,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        } else if (!isUser && message.responseTimeMs != null) {
            ResponseTimeBadge(
                responseTimeMs = message.responseTimeMs,
                modifier = Modifier.padding(bottom = 4.dp, start = 4.dp),
                color = if (isCompact) personaColor else MaterialTheme.colorScheme.onSurface
            )
        }

        val textModifier = if (!isCompact) {
            Modifier
                .background(
                    if (isUser) personaColor.copy(alpha = 0.1f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                    MaterialTheme.shapes.medium
                )
                .combinedClickable(
                    onClick = { },
                    onLongClick = onLongClick
                )
                .padding(12.dp)
        } else {
            Modifier.fillMaxWidth().padding(bottom = 6.dp)
        }

        Text(
            text = displayText,
            color = when {
                isUser && isCompact -> personaColor
                isUser -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            },
            style = if (isUser && isCompact) MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyMedium,
            textAlign = if (isCompact) TextAlign.Center else TextAlign.Start,
            modifier = textModifier
        )

        if (!isUser && onReplayAudio != null && !isCompact) {
            IconButton(
                onClick = onReplayAudio,
                modifier = Modifier.size(24.dp).padding(top = 4.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    null,
                    tint = personaColor.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatList(
    modifier: Modifier = Modifier,
    messages: List<ChatMessage>,
    listState: LazyListState,
    state: AssistantState,
    personaColor: Color,
    revealedChars: Int,
    onEditMessage: (Int, String) -> Unit,
    onDeleteMessage: (Int) -> Unit,
    onReplayAudio: (ChatMessage) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    
    var menuMessageIndex by remember { mutableStateOf<Int?>(null) }
    var editingMessageIndex by remember { mutableStateOf<Int?>(null) }
    var editingText by remember { mutableStateOf("") }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(messages) { index, message ->
                val isUser = message.role == "user"
                val isLastAssistant = index == messages.size - 1 && !isUser
                val displayText = if (isLastAssistant) {
                    message.text.take(revealedChars)
                } else message.text

                Box(
                    modifier = Modifier.fillMaxWidth(), 
                    contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    ChatMessageBubble(
                        message = message,
                        displayText = displayText,
                        personaColor = personaColor,
                        isCompact = false,
                        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                        onLongClick = { menuMessageIndex = index },
                        onReplayAudio = { onReplayAudio(message) },
                        modifier = Modifier.fillMaxWidth(0.85f)
                    )

                    DropdownMenu(
                        expanded = menuMessageIndex == index,
                        onDismissRequest = { menuMessageIndex = null }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Copy") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                val clip = ClipData.newPlainText("Assistant Message", message.text)
                                clipboardManager.setPrimaryClip(clip)
                                menuMessageIndex = null
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, message.text)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share via"))
                                menuMessageIndex = null
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                editingMessageIndex = index
                                editingText = message.text
                                menuMessageIndex = null
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = Color.Red, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                onDeleteMessage(index)
                                menuMessageIndex = null
                            }
                        )
                    }
                }
            }
            if (state == AssistantState.THINKING) {
                item { Text("Thinking...", style = MaterialTheme.typography.bodySmall, color = personaColor.copy(alpha = 0.5f)) }
            }
        }

        if (editingMessageIndex != null) {
            AlertDialog(
                onDismissRequest = { editingMessageIndex = null },
                title = { Text("Edit Message") },
                text = {
                    OutlinedTextField(
                        value = editingText,
                        onValueChange = { editingText = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 5
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        onEditMessage(editingMessageIndex!!, editingText)
                        editingMessageIndex = null
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { editingMessageIndex = null }) { Text("Cancel") }
                }
            )
        }
    }
}


@Composable
fun TextInputRow(
    textInput: String,
    onTextInputChange: (String) -> Unit,
    onAttachClick: () -> Unit,
    onSendClick: () -> Unit,
    onMicClick: () -> Unit,
    onStopClick: () -> Unit,
    state: AssistantState,
    personaColor: Color,
    focusRequester: FocusRequester,
    attachedFiles: List<android.net.Uri>
) {
    val hasContent = textInput.isNotBlank() || attachedFiles.isNotEmpty()
    val isListening = state == AssistantState.LISTENING

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedTextField(
            value = textInput,
            onValueChange = onTextInputChange,
            modifier = Modifier.weight(1f).focusRequester(focusRequester),
            placeholder = { Text("Type something...", style = MaterialTheme.typography.bodyMedium) },
            shape = RoundedCornerShape(24.dp),
            leadingIcon = {
                IconButton(onClick = onAttachClick, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Add, 
                        null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
            trailingIcon = {
                IconButton(
                    onClick = { 
                        if (state == AssistantState.SPEAKING) onStopClick()
                        else if (isListening) onMicClick() // Stop
                        else if (hasContent) onSendClick() 
                        else onMicClick() // Start
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (hasContent || isListening || state == AssistantState.SPEAKING) personaColor.copy(alpha = 0.1f) else Color.Transparent, 
                            CircleShape
                        )
                ) {
                    Icon(
                        imageVector = when {
                            state == AssistantState.SPEAKING -> Icons.AutoMirrored.Filled.VolumeOff
                            isListening -> Icons.Default.Stop
                            hasContent -> Icons.AutoMirrored.Filled.Send
                            else -> Icons.Default.Mic
                        },
                        contentDescription = null,
                        tint = when {
                            state == AssistantState.SPEAKING -> personaColor
                            isListening -> Color.Red
                            hasContent -> personaColor
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = personaColor.copy(alpha = 0.5f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            ),
            singleLine = false,
            maxLines = 4
        )
    }
}

