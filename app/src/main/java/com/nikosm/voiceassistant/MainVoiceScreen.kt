package com.nikosm.voiceassistant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.nikosm.voiceassistant.ui.theme.VoiceAssistantTheme
import kotlinx.coroutines.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.foundation.gestures.scrollBy

@Composable
fun MainScreen(service: AssistantService?) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    
    var showSettings by remember { mutableStateOf(false) }
    var showPersonaPicker by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }
    var textModeOpen by remember { mutableStateOf(false) }
    var revealedChars by remember { mutableIntStateOf(Int.MAX_VALUE) }
    var lastAnimatedMessageId by remember { mutableStateOf("") }
    var attachedFiles by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isFirstRun by remember(service) { mutableStateOf(service?.isFirstRun() ?: false) }

    var state by remember { mutableStateOf(AssistantState.IDLE) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var voiceDuration by remember { mutableIntStateOf(0) }
    var personaList by remember { mutableStateOf<List<Persona>>(emptyList()) }
    var sessionUsage by remember { mutableStateOf(UsageInfo()) }
    var isEarpieceMode by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var silenced by remember { mutableStateOf(false) }
    var handsFreeMode by remember { mutableStateOf(false) }
    var pendingCert by remember { mutableStateOf<CertApprovalRequest?>(null) }

    LaunchedEffect(service) {
        if (service != null) {
            launch { service.assistantState.collect { state = it } }
            launch { service.messages.collect { messages = it } }
            launch { service.voiceDuration.collect { voiceDuration = it } }
            launch { service.personas.collect { personaList = it } }
            launch { service.sessionUsage.collect { sessionUsage = it } }
            launch { service.earpieceMode.collect { isEarpieceMode = it } }
            launch { service.micMuted.collect { muted = it } }
            launch { service.silenced.collect { silenced = it } }
            launch { service.handsFreeMode.collect { handsFreeMode = it } }
            launch { service.pendingCertApproval.collect { pendingCert = it } }
        }
    }

    var currentPersona by remember { mutableStateOf(DEFAULT_PERSONAS[0]) }
    val personaColor by animateColorAsState(targetValue = currentPersona.themeColor, animationSpec = tween(1000), label = "personaColor")

    val listState = rememberLazyListState()
    val miniScrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }

    val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        attachedFiles = (attachedFiles + uris).distinct()
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Promote the service to foreground once RECORD_AUDIO is granted
        if (granted) {
            service?.promoteToForeground()
        }
    }

    LaunchedEffect(service) {
        if (service != null) {
            if (service.cloudApis.value.isEmpty()) DEFAULT_CLOUD_APIS.forEach { service.updateCloudApi(-1, it) }
            if (service.personas.value.isEmpty()) {
                (DEFAULT_PERSONAS + CLOUD_PERSONAS + TRANSLATOR_PERSONA).forEach { service.addPersona(it) }
            } else if (service.personas.value.none { it.isTranslator }) {
                service.addPersona(TRANSLATOR_PERSONA)
            }
            service.saveSettings()
            service.fetchModels()
            service.switchPersona(currentPersona)
        }
    }

    LaunchedEffect(personaList) {
        if (personaList.isNotEmpty()) {
            val updated = personaList.find { it.name == currentPersona.name }
            if (updated != null) {
                currentPersona = updated
            } else {
                currentPersona = personaList[0]
                service?.switchPersona(personaList[0])
            }
        }
    }

    LaunchedEffect(messages.size, state, voiceDuration) {
        val lastMsg = messages.lastOrNull()
        if (lastMsg != null && lastMsg.role == "assistant") {
            val text = lastMsg.text
            if (text.isEmpty()) return@LaunchedEffect

            // Unique ID for the current message in this persona's history
            val messageId = "${currentPersona.name}_${messages.size}_${text.hashCode()}"

            // If this message was already animated or we're loading history, show fully instantly
            if (messageId == lastAnimatedMessageId || state == AssistantState.IDLE) {
                revealedChars = text.length
                lastAnimatedMessageId = messageId
                return@LaunchedEffect
            }
            
            // If it's a completely new message (ID differs and we aren't in IDLE), start animation
            revealedChars = 0
            lastAnimatedMessageId = messageId
            val startTime = System.currentTimeMillis()
            
            var currentVoiceDuration = voiceDuration
            if (currentVoiceDuration <= 0 && state == AssistantState.SPEAKING) {
                delay(100)
                currentVoiceDuration = voiceDuration
            }

            val animDuration = if (currentVoiceDuration > 0) {
                currentVoiceDuration.toLong()
            } else {
                (text.length * 25L).coerceAtLeast(500L)
            }

            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                val progress = (elapsed.toFloat() / animDuration).coerceIn(0f, 1f)
                revealedChars = (text.length * progress).toInt()
                
                if (progress >= 1f) break
                if (currentVoiceDuration > 0 && state != AssistantState.SPEAKING && elapsed > 500) break
                
                delay(16)
            }
            revealedChars = text.length
        } else {
            revealedChars = Int.MAX_VALUE
            // Reset tracker when no assistant message is present (e.g. cleared chat)
            if (messages.isEmpty()) lastAnimatedMessageId = ""
        }
    }

    // Combined Auto-scroll logic
    LaunchedEffect(messages.size, revealedChars, textModeOpen) {
        if (messages.isNotEmpty()) {
            // Scroll the main chat list
            if (textModeOpen) {
                val lastIndex = messages.size - 1
                listState.scrollToItem(lastIndex)
                
                // Refine scroll to ensure the bottom of the message is visible if it's long
                listState.layoutInfo.visibleItemsInfo.find { it.index == lastIndex }?.let { lastItem ->
                    val viewportBottom = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.afterContentPadding
                    val itemBottom = lastItem.offset + lastItem.size
                    if (itemBottom > viewportBottom) {
                        listState.scrollBy((itemBottom - viewportBottom).toFloat())
                    }
                }
            }
            // Scroll the small transcription box in voice mode
            if (!textModeOpen && miniScrollState.maxValue > 0) {
                miniScrollState.scrollTo(miniScrollState.maxValue)
            }
        }
    }

    LaunchedEffect(textModeOpen) { if (textModeOpen) focusRequester.requestFocus() }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    if (isFirstRun) {
        WelcomeScreen(
            service = service,
            onFinish = {
                service?.setFirstRunComplete()
                isFirstRun = false
                service?.fetchModels()
            },
            personaColor = personaColor
        )
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Header(
                    currentPersona = currentPersona,
                    sessionUsage = sessionUsage,
                    onPersonaClick = { showPersonaPicker = true },
                    onEarpieceToggle = { service?.toggleEarpieceMode() },
                    isEarpieceMode = isEarpieceMode,
                    onSettingsClick = { showSettings = true },
                    onTranslatorClick = {
                        val t = personaList.find { it.isTranslator }
                        if (t != null) {
                            currentPersona = t
                            service?.switchPersona(t)
                            service?.clearMessages()
                        } else {
                            currentPersona = TRANSLATOR_PERSONA
                            service?.addPersona(TRANSLATOR_PERSONA)
                            service?.switchPersona(TRANSLATOR_PERSONA)
                            service?.clearMessages()
                        }
                    },
                    textModeOpen = textModeOpen,
                    onTextModeToggle = { textModeOpen = !textModeOpen },
                    silenced = silenced,
                    onSilenceToggle = { service?.toggleSilence() }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (currentPersona.isTranslator) {
                    LanguageBar(
                        currentLanguage = currentPersona.targetLanguage,
                        onLanguageSelected = { lang ->
                            val idx = personaList.indexOfFirst { it.name == currentPersona.name }
                            if (idx != -1) {
                                service?.updatePersona(idx, currentPersona.copy(targetLanguage = lang))
                            }
                            currentPersona = currentPersona.copy(targetLanguage = lang)
                        },
                        personaColor = personaColor
                    )
                }

                ControlBar(
                    textModeOpen = textModeOpen,
                    textInput = textInput,
                    onTextInputChange = { textInput = it },
                    attachedFiles = attachedFiles,
                    onAttachClick = { attachmentLauncher.launch(arrayOf(
                        "text/plain", "text/markdown", "text/x-markdown",
                        "application/json", "text/x-json",
                        "application/yaml", "text/yaml", "text/x-yaml",
                        "application/xml", "text/xml",
                        "text/html",
                        "text/css",
                        "text/x-javascript", "application/javascript",
                        "text/x-python", "text/x-python-script",
                        "text/x-sh", "text/x-shellscript",
                        "text/x-c", "text/x-csrc", "text/x-c++", "text/x-c++src",
                        "text/x-java-source",
                        "text/x-ruby", "text/x-sql", "text/x-csharp", "text/x-go-source", "text/x-rust"
                    )) },
                    onRemoveAttachment = { attachedFiles = attachedFiles - it },
                    onSendClick = {
                        android.util.Log.d("AssistantService", "DEBUG onSendClick: attachedFiles.size=${attachedFiles.size} BEFORE snapshot")
                        // Defensive copy: the composition's attachedFiles is a
                        // state-backed mutable list. We snapshot it BEFORE clearing the
                        // UI, so the synchronous `attachedFiles = emptyList()` below
                        // can't leave the in-flight send reading an emptied list.
                        val filesToSend = attachedFiles.toList()
                        android.util.Log.d("AssistantService", "DEBUG onSendClick: filesToSend.size=${filesToSend.size} AFTER snapshot")
                        service?.sendTextMessageToServer(textInput, currentPersona, filesToSend)
                        textInput = ""
                        attachedFiles = emptyList()
                    },
                    onMicClick = {
                        if (state == AssistantState.IDLE) service?.startRecording()
                        else if (state == AssistantState.LISTENING) service?.stopRecording(currentPersona)
                    },
                    onStopClick = { service?.stopEverything() },
                    state = state,
                    personaColor = personaColor,
                    onTextModeToggle = { textModeOpen = !textModeOpen },
                    focusRequester = focusRequester,
                    muted = muted,
                    silenced = silenced,
                    onMuteToggle = { service?.toggleMicMute() },
                    onSilenceToggle = { service?.toggleSilence() },
                    handsFreeMode = handsFreeMode,
                    onHandsFreeToggle = {
                        if (service?.handsFreeMode?.value == true) service.stopVadListening()
                        else service?.startVadListening()
                    },
                    messages = messages,
                    revealedChars = revealedChars,
                    miniScrollState = miniScrollState,
                    listState = listState,
                    onEditMessage = { idx, txt -> service?.updateMessage(idx, txt) },
                    onDeleteMessage = { idx -> service?.deleteMessage(idx) },
                    onReplayAudio = { msg -> service?.replayMessageAudio(msg, currentPersona) }
                )
            }
        }
    }

    if (showPersonaPicker) {
        PersonaSelector(
            personaList = personaList,
            currentPersona = currentPersona,
            onPersonaSelected = { 
                currentPersona = it
                showPersonaPicker = false
                service?.switchPersona(it)
                if (it.isTranslator) {
                    service?.clearMessages()
                }
            },
            onDismiss = { showPersonaPicker = false },
            personaColor = personaColor
        )
    }

    if (showSettings) {
        SettingsDialog(
            service = service,
            onDismiss = { showSettings = false },
            personaColor = personaColor
        )
    }

    if (pendingCert != null) {
        AlertDialog(
            onDismissRequest = { service?.denyCertificate() },
            title = { Text("Security Warning") },
            text = {
                Column {
                    Text("The server at ${pendingCert!!.host} is using a new or changed self-signed certificate.")
                    Spacer(Modifier.height(8.dp))
                    Text("Fingerprint (SHA-256):", style = MaterialTheme.typography.labelSmall)
                    Text(pendingCert!!.fingerprint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text("Do you want to trust this certificate?", fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                TextButton(onClick = { service?.approveCertificate(pendingCert!!) }) {
                    Text("Trust & Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { service?.denyCertificate() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun Header(
    currentPersona: Persona,
    sessionUsage: UsageInfo,
    onPersonaClick: () -> Unit,
    onEarpieceToggle: () -> Unit,
    isEarpieceMode: Boolean,
    onSettingsClick: () -> Unit,
    onTranslatorClick: () -> Unit,
    textModeOpen: Boolean = false,
    onTextModeToggle: () -> Unit = {},
    silenced: Boolean = false,
    onSilenceToggle: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (textModeOpen) {
                IconButton(onClick = onTextModeToggle, modifier = Modifier.padding(end = 8.dp)) {
                    Icon(Icons.Default.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                }
            }
            
            Row(
                modifier = Modifier
                    .weight(1f) // Let this side take available space
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onPersonaClick() }
                    .padding(8.dp), 
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProviderLogo(icon = currentPersona.providerIcon, isCloud = currentPersona.isCloud)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    val displayName = if (currentPersona.name.length > 20) currentPersona.name.take(20) + "..." else currentPersona.name
                    
                    Box(modifier = Modifier.fillMaxWidth().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
                        drawContent()
                        if (currentPersona.name.length > 15) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    0.7f to Color.Black,
                                    1.0f to Color.Transparent
                                ),
                                blendMode = BlendMode.DstIn
                            )
                        }
                    }) {
                        Text(
                            text = displayName, 
                            color = MaterialTheme.colorScheme.onBackground, 
                            style = MaterialTheme.typography.titleMedium, 
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    
                    Text(text = if (currentPersona.isCloud) "session: $${String.format(java.util.Locale.US, "%.5f", sessionUsage.cost)}" else "free / local", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onSilenceToggle) { Icon(if (silenced) Icons.Default.SpeakerNotesOff else Icons.Default.SpeakerNotes, null, tint = if (silenced) Color.Green else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)) }
            IconButton(onClick = onEarpieceToggle) { Icon(if (isEarpieceMode) Icons.Filled.PhoneInTalk else Icons.Filled.Phone, null, tint = if (isEarpieceMode) Color.Green else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)) }
            IconButton(onClick = onTranslatorClick) { Icon(Icons.Filled.Translate, null, tint = if (currentPersona.isTranslator) Color.Cyan else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)) }
            IconButton(onClick = onSettingsClick) { Icon(Icons.Filled.Settings, null, tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)) }
        }
    }
}


@Composable
fun ControlBar(
    textModeOpen: Boolean,
    textInput: String,
    onTextInputChange: (String) -> Unit,
    attachedFiles: List<Uri>,
    onAttachClick: () -> Unit,
    onRemoveAttachment: (Uri) -> Unit,
    onSendClick: () -> Unit,
    onMicClick: () -> Unit,
    onStopClick: () -> Unit,
    state: AssistantState,
    personaColor: Color,
    onTextModeToggle: () -> Unit,
    focusRequester: FocusRequester,
    muted: Boolean,
    silenced: Boolean,
    onMuteToggle: () -> Unit,
    onSilenceToggle: () -> Unit,
    handsFreeMode: Boolean,
    onHandsFreeToggle: () -> Unit,
    messages: List<ChatMessage>,
    revealedChars: Int,
    miniScrollState: ScrollState,
    listState: LazyListState,
    onEditMessage: (Int, String) -> Unit,
    onDeleteMessage: (Int) -> Unit,
    onReplayAudio: (ChatMessage) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (textModeOpen) {
            ChatList(
                modifier = Modifier.weight(1f),
                messages = messages,
                listState = listState,
                state = state,
                personaColor = personaColor,
                revealedChars = revealedChars,
                onEditMessage = onEditMessage,
                onDeleteMessage = onDeleteMessage,
                onReplayAudio = onReplayAudio
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (attachedFiles.isNotEmpty()) {
                Row(modifier = Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    attachedFiles.forEach { uri ->
                        Box(modifier = Modifier.size(50.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))) {
                            Icon(Icons.Default.AttachFile, null, modifier = Modifier.align(Alignment.Center))
                            IconButton(onClick = { onRemoveAttachment(uri) }, modifier = Modifier.size(16.dp).align(Alignment.TopEnd)) {
                                Icon(Icons.Default.Close, null, modifier = Modifier.size(10.dp))
                            }
                        }
                    }
                }
            }
            
            TextInputRow(
                textInput = textInput,
                onTextInputChange = onTextInputChange,
                onAttachClick = onAttachClick,
                onSendClick = onSendClick,
                onMicClick = onMicClick,
                onStopClick = onStopClick,
                state = state,
                personaColor = personaColor,
                focusRequester = focusRequester,
                attachedFiles = attachedFiles
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(contentAlignment = Alignment.Center) {
                    MicRing(
                        state = state, 
                        muted = muted, 
                        color = if (handsFreeMode && state == AssistantState.IDLE) personaColor.copy(alpha = 0.5f) else personaColor, 
                        size = 180.dp, 
                        onClick = if (handsFreeMode) ({}) else onMicClick
                    )
                    
                    if (handsFreeMode) {
                        Icon(
                            Icons.Default.Hearing, 
                            null, 
                            tint = if (state == AssistantState.LISTENING) Color.Red else personaColor,
                            modifier = Modifier.size(24.dp).align(Alignment.TopEnd).offset(x = 10.dp, y = (-10).dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                // Flexible Transcription Box
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f) // Grow to fill available space
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.05f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (messages.isEmpty() && state != AssistantState.THINKING) {
                        Text("Ready to help", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.Center))
                    } else {
                        // Sharp fade only at the very top for clarity
                        val fadeBrush = Brush.verticalGradient(0.0f to Color.Transparent, 0.05f to Color.Black, 1.0f to Color.Black)
                        Column(modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            .drawWithContent { 
                                drawContent()
                                drawRect(fadeBrush, blendMode = BlendMode.DstIn) 
                            }
                            .verticalScroll(miniScrollState)) {
                            
                            messages.forEachIndexed { index, msg ->
                                val isLastAssistant = index == messages.size - 1 && msg.role == "assistant"
                                val displayText = if (isLastAssistant) {
                                    msg.text.take(revealedChars)
                                } else msg.text
                                
                                ChatMessageBubble(
                                    message = msg,
                                    displayText = displayText,
                                    personaColor = personaColor,
                                    isCompact = true,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (state == AssistantState.THINKING) {
                                Text("...", color = personaColor.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onMuteToggle) {
                        Icon(
                            imageVector = if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (muted) Color.Red else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(onClick = onHandsFreeToggle) {
                        Icon(
                            imageVector = if (handsFreeMode) Icons.Default.AutoAwesome else Icons.Default.AutoAwesomeMotion,
                            contentDescription = null,
                            tint = if (handsFreeMode) Color.Cyan else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Button(
                        onClick = onStopClick,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.9f)),
                        shape = CircleShape,
                        modifier = Modifier.size(64.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Stop, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }

                    IconButton(onClick = onSilenceToggle) {
                        Icon(
                            imageVector = if (silenced) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = if (silenced) Color.Red else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(onClick = onTextModeToggle) {
                        Icon(
                            imageVector = Icons.Default.Keyboard,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}
