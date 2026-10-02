package com.nikosm.voiceassistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.unit.sp
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.io.File

@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    displayText: String,
    personaColor: Color,
    fontSize: Float = 14f,
    isCompact: Boolean = false,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    // Stage-2 karaoke: char range of the currently-spoken word + its color.
    // Null → plain text (no highlight). Text is never hidden.
    highlightRange: IntRange? = null,
    highlightColor: Color? = null,
    onLongClick: (() -> Unit)? = null,
    onReplayAudio: (() -> Unit)? = null,
    onRegenerateImage: (() -> Unit)? = null,
    // Room mode only: who is speaking, and the color of that persona's theme.
    // Both default to null so every single-persona call site renders EXACTLY as before
    // (the chip below is skipped entirely). A null [speakerColor] on a labeled message
    // (the speaker's persona was removed after the message was sent) renders neutral.
    speakerLabel: String? = null,
    speakerColor: Color? = null,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"
    Column(
        horizontalAlignment = horizontalAlignment,
        modifier = modifier
    ) {
        // Multi-party speaker chip (Rooms): a small color-coded name above the bubble.
        // Skipped for the user's own turns — those are already visually distinct (own
        // alignment + own-voice color) and need no attribution — and in the celestial
        // HUD's compact body, which is a voice surface with no room mode.
        if (speakerLabel != null && !isUser && !isCompact) {
            val chipColor = speakerColor ?: MaterialTheme.colorScheme.onSurfaceVariant
            Surface(
                modifier = Modifier
                    .padding(bottom = 3.dp)
                    .align(horizontalAlignment),
                shape = MaterialTheme.shapes.small,
                color = chipColor.copy(alpha = 0.16f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(chipColor, CircleShape)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = speakerLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = chipColor
                    )
                }
            }
        }

        if (!message.reasoning.isNullOrBlank()) {
            var expandedReasoning by rememberSaveable { mutableStateOf(false) }
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
            text = highlightedAnnotated(
                displayText,
                if (isUser) null else highlightRange,
                highlightColor
            ),
            color = when {
                isUser && isCompact -> personaColor
                isUser -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            },
            fontSize = fontSize.sp,
            style = if (isUser && isCompact) MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyMedium,
            textAlign = if (isCompact) TextAlign.Center else TextAlign.Start,
            modifier = textModifier
        )

        if (isUser && message.imagePath != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier
                    .width(200.dp)
                    .height(150.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                AsyncImage(
                    model = File(message.imagePath),
                    contentDescription = "Attached image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (message.generatedImagePath != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier
                    .width(256.dp)
                    .height(256.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                AsyncImage(
                    model = File(message.generatedImagePath),
                    contentDescription = "Generated image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            val context = LocalContext.current
            if (!isCompact) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onRegenerateImage != null) {
                        TextButton(onClick = onRegenerateImage) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Regenerate")
                        }
                    }
                    TextButton(onClick = {
                        try {
                            val sourceFile = File(message.generatedImagePath)
                            if (!sourceFile.exists()) {
                                Toast.makeText(context, "Image file not found", Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            val filename = "Gen_${System.currentTimeMillis()}.png"
                            val resolver = context.contentResolver
                            val contentValues = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VoiceAssistant")
                                    put(MediaStore.Images.Media.IS_PENDING, 1)
                                }
                            }

                            val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                            if (imageUri == null) {
                                throw Exception("Failed to create MediaStore entry")
                            }

                            resolver.openOutputStream(imageUri)?.use { outputStream ->
                                sourceFile.inputStream().use { inputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                contentValues.clear()
                                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                                resolver.update(imageUri, contentValues, null, null)
                            }

                            Toast.makeText(context, "Saved to gallery", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Log.e("ChatComponents", "Failed to save image to gallery", e)
                            Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("Save to gallery")
                    }
                }
            }
        }

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

        // When this message was sent: a bare time for today, date and time for anything older
        // (see formatMessageStamp). Display only — it is a property of the stored message, so
        // it renders identically for every persona type, local or cloud, and in single chat
        // and rooms alike (Rooms renders through this same bubble).
        //
        // `null` when the message predates the timestamp field: no stamp beats a fabricated
        // one. The Column's horizontalAlignment puts it under the bubble on the same side as
        // the text, so a user's own turns stay right-aligned.
        val sentAt = formatMessageStamp(message)
        if (sentAt != null) {
            Text(
                text = sentAt,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ChatList(
    modifier: Modifier = Modifier,
    messages: List<ChatMessage>,
    listState: LazyListState,
    state: AssistantState,
    personaColor: Color,
    revealedChars: Int,
    streamingText: String? = null,
    fontSize: Float = 14f,
    ttsPlaybackFraction: Float? = null,
    ttsWordTimestamps: String? = null,
    voiceDuration: Int,
    onEditMessage: (Int, String) -> Unit,
    onDeleteMessage: (Int) -> Unit,
    onReplayAudio: (ChatMessage) -> Unit,
    onRegenerateImage: ((Int) -> Unit)? = null,
    shouldShowSuggestions: Boolean = false,
    suggestionState: SuggestionState = SuggestionState.IDLE,
    onRequestSuggestions: () -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    
    var menuMessageIndex by remember { mutableStateOf<Int?>(null) }
    var editingMessageIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var editingText by rememberSaveable { mutableStateOf("") }

    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(messages) { index, message ->
                val isUser = message.role == "user"
                val isLastAssistant = index == messages.size - 1 && !isUser
                val chunkedKaraoke = isLastAssistant && state == AssistantState.SPEAKING &&
                    ttsPlaybackFraction != null && voiceDuration > 0
                val displayText = if (isLastAssistant && !chunkedKaraoke) {
                    message.text.take(revealedChars)
                } else message.text
                val activeFraction = ttsPlaybackFraction
                val highlightRange = if (chunkedKaraoke) {
                    ttsActiveWordRange(message.text, ttsWordTimestamps, activeFraction!!, voiceDuration)
                } else null

                Box(
                    modifier = Modifier.fillMaxWidth(), 
                    contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    ChatMessageBubble(
                        message = message,
                        displayText = displayText,
                        highlightRange = if (isUser) null else highlightRange,
                        highlightColor = if (isUser) null else personaColor,
                        personaColor = personaColor,
                        fontSize = fontSize,
                        isCompact = false,
                        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                        onLongClick = { menuMessageIndex = index },
                        onReplayAudio = { onReplayAudio(message) },
                        onRegenerateImage = if (message.generatedImagePath != null && onRegenerateImage != null) { { onRegenerateImage(index) } } else null,
                        modifier = if (isUser) Modifier else Modifier.fillMaxWidth()
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
            if (shouldShowSuggestions && (suggestionState == SuggestionState.IDLE || suggestionState == SuggestionState.LOADING || suggestionState == SuggestionState.FAILED)) {
                item(key = "suggest_replies_in_list") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        //changed from 4 to 2
                        contentAlignment = Alignment.Center
                    ) {
                        when (suggestionState) {
                            SuggestionState.IDLE -> {
                                Surface(
                                    modifier = Modifier.clickable { onRequestSuggestions() },
                                    shape = RoundedCornerShape(16.dp),
                                    color = personaColor.copy(alpha = 0.15f),
                                    border = BorderStroke(1.dp, personaColor.copy(alpha = 0.4f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        //changed from 10 to 4
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(14.dp), tint = personaColor)
                                        Text("Suggest replies", style = MaterialTheme.typography.labelSmall, color = personaColor)
                                    }
                                }
                            }
                            SuggestionState.LOADING -> {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = personaColor.copy(alpha = 0.15f),
                                    border = BorderStroke(1.dp, personaColor.copy(alpha = 0.4f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        //changed from 10 ro 4
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = personaColor)
                                        Text("Suggesting...", style = MaterialTheme.typography.labelSmall, color = personaColor)
                                    }
                                }
                            }
                            SuggestionState.FAILED -> {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        //changed from 10 to 4
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            "Couldn't get suggestions, tap to retry",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.clickable { onRequestSuggestions() }
                                        )
                                        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }
            if (state == AssistantState.THINKING && streamingText.isNullOrBlank()) {
                item { Text("Thinking...", style = MaterialTheme.typography.bodySmall, color = personaColor.copy(alpha = 0.5f)) }
            }
            // Stage-1 streaming: the placeholder message in messages already
            // carries the accumulated stream content (updated per chunk by the
            // NDJSON loop) - no separate overlay needed here (double-render).
        }

        if (editingMessageIndex != null) {
            AlertDialog(
                onDismissRequest = { editingMessageIndex = null },
                modifier = Modifier.fillMaxSize(),
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .imePadding(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Edit Message", style = MaterialTheme.typography.titleLarge)

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                        ) {
                            OutlinedTextField(
                                value = editingText,
                                onValueChange = { editingText = it },
                                label = { Text("Message") },
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = Int.MAX_VALUE
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { editingMessageIndex = null }) {
                                Text("Cancel")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    onEditMessage(editingMessageIndex!!, editingText)
                                    editingMessageIndex = null
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = personaColor.copy(alpha = 0.8f))
                            ) {
                                Text("Save")
                            }
                        }
                    }
                }
            }
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
    attachedFiles: List<Uri>,
    isImageGenerator: Boolean = false
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
            placeholder = { Text(if (isImageGenerator) "Describe the image you want..." else "Type something...", style = MaterialTheme.typography.bodyMedium) },
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

enum class SuggestionState { IDLE, LOADING, READY, FAILED }

/**
 * Ready-state suggestions, anchored above the input row.
 *
 * The panel does NOT decide whether it is open. Its height is taken from the
 * chat list, so deriving "open" from the list's own scroll state
 * (listState.canScrollForward) collapsed the panel the instant it opened —
 * opening the panel shrinks the list, which makes canScrollForward true — and
 * the suggestions could only ever be seen as the "N suggested replies" pill.
 * The caller owns the decision (keyboard / orientation / whether the user
 * scrolled the chat away from the newest message) and reports it through
 * [isExpanded]; the user gets the final word by tapping the pill
 * ([onToggleExpanded] with true) or the panel header (with false).
 */
@Composable
fun SuggestedRepliesPanel(
    suggestionState: SuggestionState,
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    onRequestSuggestions: () -> Unit,
    onDismiss: () -> Unit,
    personaColor: Color,
    chatFontSize: Float = 14f,
    isExpanded: Boolean,
    onToggleExpanded: (Boolean) -> Unit
) {
    // Height budget: never more than 30% of the screen and never more than
    // 180dp, so the chat list keeps the majority of its space even with three
    // long suggestions on screen; the suggestion list scrolls internally.
    val maxPanelHeight = minOf(LocalConfiguration.current.screenHeightDp.dp * 0.30f, 180.dp)
    // Panel header row (36dp) + divider + vertical paddings: everything the
    // surface spends above the scrolling suggestion list.
    val maxSuggestionsHeight = maxPanelHeight - 40.dp

    if (suggestionState == SuggestionState.READY) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp)
                .heightIn(max = maxPanelHeight)
                .animateContentSize(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(0.5.dp, personaColor.copy(alpha = 0.3f)),
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 1.dp)
            ) {
                if (!isExpanded) {
                    // Compact pill: at most 32dp tall, so with the keyboard up the
                    // chat keeps nearly all of its space; tapping it re-opens the
                    // full list, tapping the ✕ clears the suggestions.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 28.dp, max = 32.dp)
                            .clickable { onToggleExpanded(true) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${suggestions.size} suggested replies ▾",
                            style = MaterialTheme.typography.labelSmall,
                            color = personaColor,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, "Dismiss", modifier = Modifier.size(16.dp))
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 32.dp, max = 36.dp)
                            .clickable { onToggleExpanded(false) },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(14.dp), tint = personaColor)
                            Text(
                                "Suggested replies ▴",
                                style = MaterialTheme.typography.labelSmall,
                                color = personaColor
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = onRequestSuggestions,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Refresh, "Refresh suggestions", modifier = Modifier.size(16.dp), tint = personaColor)
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Close, "Dismiss suggestions", modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        thickness = 0.5.dp
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxSuggestionsHeight)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        suggestions.forEachIndexed { index, suggestion ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                    thickness = 0.5.dp
                                )
                            }
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSuggestionClick(suggestion) },
                                color = Color.Transparent
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp, horizontal = 4.dp)
                                        .heightIn(min = 32.dp),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = suggestion,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = chatFontSize.sp),
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

