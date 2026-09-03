package com.nikosm.voiceassistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsSectionHeader(title: String, icon: ImageVector? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun SettingsSection(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
fun PersonaEditor(
    persona: Persona,
    onSave: (Persona) -> Unit,
    onDelete: (() -> Unit)? = null,
    onCancel: () -> Unit,
    service: AssistantService
) {
    var name by remember(persona) { mutableStateOf(persona.name) }
    var model by remember(persona) { mutableStateOf(persona.model) }
    var systemPrompt by remember(persona) { mutableStateOf(persona.systemPrompt) }
    var themeColor by remember(persona) { mutableStateOf(persona.themeColor) }
    var temp by remember(persona) { mutableFloatStateOf(persona.temperature) }
    var topP by remember(persona) { mutableFloatStateOf(persona.topP) }
    var topK by remember(persona) { mutableIntStateOf(persona.topK) }
    var repeatPenalty by remember(persona) { mutableFloatStateOf(persona.repeatPenalty) }
    var maxTokens by remember(persona) { mutableIntStateOf(persona.maxTokens) }
    var numCtx by remember(persona) { mutableIntStateOf(persona.numCtx) }
    var enableThinking by remember(persona) { mutableStateOf(persona.enableThinking) }
    var webSearchEnabled by remember(persona) { mutableStateOf(persona.webSearchEnabled) }
    var ragEnabled by remember(persona) { mutableStateOf(persona.ragEnabled) }
    
    var isTranslator by remember(persona) { mutableStateOf(persona.isTranslator) }
    var targetLanguage by remember(persona) { mutableStateOf(persona.targetLanguage) }
    var voiceMode by remember(persona) { mutableStateOf(persona.voiceMode) }
    var voiceEngine by remember(persona) { mutableStateOf(persona.voiceEngine) }
    var kokoroVoice by remember(persona) { mutableStateOf(persona.kokoroVoice) }
    var backendUrl by remember(persona) { mutableStateOf(persona.backendUrl) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val initialConnection = remember(persona) {
        if (model.startsWith("[") && model.contains("] ")) {
            model.substring(1, model.indexOf("]"))
        } else ""
    }
    var selectedConnection by remember(persona) { mutableStateOf(initialConnection) }

    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var customCloudApis by remember { mutableStateOf<List<CloudApiSetting>>(emptyList()) }
    var cloudApis by remember { mutableStateOf<List<CloudApiSetting>>(emptyList()) }
    var ollamaBases by remember { mutableStateOf<List<ServerConfig>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<String>>(emptyList()) }
    var fetchedCloudModels by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }

    LaunchedEffect(service) {
        launch { service.availableModels.collect { availableModels = it } }
        launch { service.customCloudApis.collect { customCloudApis = it } }
        launch { service.cloudApis.collect { cloudApis = it } }
        launch { service.ollamaBaseUrls.collect { ollamaBases = it } }
        launch { service.favoriteModels.collect { favorites = it } }
        launch { service.fetchedCloudModels.collect { fetchedCloudModels = it } }
    }
    
    val allConnections = remember(cloudApis, customCloudApis, ollamaBases) {
        val list = mutableListOf<String>()
        list.addAll(cloudApis.map { it.name })
        list.addAll(customCloudApis.map { it.name })
        list.addAll(ollamaBases.map { it.name })
        list.distinct()
    }

    val filteredModels = remember(selectedConnection, availableModels, fetchedCloudModels, favorites) {
        val list = mutableListOf<String>()
        if (selectedConnection.isNotBlank()) {
            val prefix = "[$selectedConnection] "
            list.addAll(availableModels.filter { it.startsWith(prefix) })
            fetchedCloudModels[selectedConnection]?.let { list.addAll(it) }
        }
        list.distinct().sortedWith(compareByDescending<String> { it in favorites }.thenBy { it })
    }

    // Auto-save logic
    // TODO(temporary debug logging — confirm numCtx is in the key list)
    LaunchedEffect(name, model, systemPrompt, themeColor, temp, topP, topK, repeatPenalty, maxTokens, numCtx, enableThinking, webSearchEnabled, ragEnabled, isTranslator, targetLanguage, voiceMode, voiceEngine, kokoroVoice, backendUrl) {
        // Skip initial evaluation if needed? No, persona change will trigger it once, which is fine.
        delay(500)
        val trimmedModel = model.trim()
        val providerName = if (trimmedModel.startsWith("[") && trimmedModel.contains("] ")) {
            trimmedModel.substring(1, trimmedModel.indexOf("]"))
        } else ""
        
        val isCloudModel = cloudApis.any { it.name == providerName } || 
                           customCloudApis.any { it.name == providerName }

        val updated = persona.copy(
            name = name, 
            model = trimmedModel, 
            systemPrompt = systemPrompt, 
            themeColor = themeColor,
            isCloud = isCloudModel,
            providerIcon = when {
                trimmedModel.startsWith("[Anthropic]") -> "A"
                trimmedModel.startsWith("[OpenAI]") -> "O"
                trimmedModel.startsWith("[Google]") -> "G"
                trimmedModel.startsWith("[DeepSeek]") -> "D"
                customCloudApis.any { it.name == providerName } || trimmedModel.startsWith("[OpenAI-Compatible]") -> "C"
                trimmedModel.contains("Translator") || isTranslator -> "T"
                else -> "O" // Local Ollama
            },
            temperature = temp,
            topP = topP,
            topK = topK,
            repeatPenalty = repeatPenalty,
            maxTokens = maxTokens,
            numCtx = numCtx,
            enableThinking = enableThinking,
            webSearchEnabled = webSearchEnabled,
            ragEnabled = ragEnabled,
            isTranslator = isTranslator,
            targetLanguage = targetLanguage,
            voiceMode = voiceMode,
            voiceEngine = voiceEngine,
            kokoroVoice = kokoroVoice,
            backendUrl = backendUrl
        )
        // TODO(temporary debug logging — remove after confirming numCtx persistence)
        android.util.Log.d("AssistantService", "DEBUG PersonaEditor save: numCtx=$numCtx, backendUrl=$backendUrl, maxTokens=$maxTokens")
        
        if (updated != persona) {
            onSave(updated)
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Edit Persona", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onCancel) { Text("Close") }
            }
        }

        item {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Persona Name") }, modifier = Modifier.fillMaxWidth())
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isTranslator, onCheckedChange = { isTranslator = it })
                Text("Translator Persona")
            }
        }

        if (isTranslator) {
            item {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedTextField(
                        value = targetLanguage,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Target Language") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { IconButton(onClick = { expanded = true }) { Icon(Icons.Default.ArrowDropDown, null) } }
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        TRANSLATION_LANGUAGES.forEach { lang ->
                            DropdownMenuItem(text = { Text(lang) }, onClick = { targetLanguage = lang; expanded = false })
                        }
                    }
                }
            }
        }

        item {
            Text("Theme Color", style = MaterialTheme.typography.labelSmall)
            FlowRow(modifier = Modifier.fillMaxWidth(), maxItemsInEachRow = 6) {
                PERSONA_PALETTE.forEach { color ->
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(if (themeColor == color) 2.dp else 0.dp, Color.White, CircleShape)
                            .clickable { themeColor = color }
                    )
                }
            }
        }

        item {
            var expanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = selectedConnection,
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("Connection") },
                    placeholder = { Text("Choose a server or provider") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Default.Dns, null)
                        }
                    }
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    if (allConnections.isEmpty()) {
                        DropdownMenuItem(text = { Text("No connections configured") }, onClick = { })
                    }
                    allConnections.forEach { conn ->
                        DropdownMenuItem(
                            text = { Text(conn) },
                            onClick = { 
                                selectedConnection = conn
                                model = "" // Reset model when connection changes
                                expanded = false 
                            }
                        )
                    }
                }
            }
        }

        item {
            var expanded by remember { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    placeholder = { Text("Choose a model") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                    }
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth(0.8f)) {
                    if (filteredModels.isEmpty()) {
                        DropdownMenuItem(text = { Text("No models found for this connection") }, onClick = { })
                    }
                    filteredModels.forEach { m ->
                        DropdownMenuItem(
                            text = { 
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(m, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { service.toggleFavoriteModel(m) }, modifier = Modifier.size(32.dp)) {
                                        Icon(
                                            if (m in favorites) Icons.Default.Star else Icons.Default.StarBorder,
                                            null,
                                            tint = if (m in favorites) Color.Yellow else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            },
                            onClick = { model = m; expanded = false }
                        )
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("System Prompt") },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                maxLines = 5
            )
        }

        item {
            var expanded by remember { mutableStateOf(false) }
            val currentModeLabel = when (voiceMode) {
                VoiceMode.NONE -> "None"
                VoiceMode.SYSTEM_TTS -> "System TTS"
                VoiceMode.BUNDLED_ESPEAK -> "Bundled eSpeak NG"
                VoiceMode.GATEWAY -> {
                    val gateways by service.serverBases.collectAsState()
                    gateways.find { it.url == backendUrl }?.name ?: "Gateway (Custom URL)"
                }
            }
            
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = currentModeLabel,
                    onValueChange = { },
                    readOnly = true,
                    label = { Text("Voice via") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                    }
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth(0.8f)) {
                    DropdownMenuItem(
                        text = { Text("None") },
                        onClick = { voiceMode = VoiceMode.NONE; expanded = false }
                    )
                    DropdownMenuItem(
                        text = { 
                            Column {
                                Text("System TTS")
                                Text("Built-in Android engine", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        },
                        onClick = { voiceMode = VoiceMode.SYSTEM_TTS; expanded = false }
                    )
                    DropdownMenuItem(
                        text = { 
                            Column {
                                Text("Bundled eSpeak NG")
                                Text("Offline open-source engine", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        },
                        onClick = { voiceMode = VoiceMode.BUNDLED_ESPEAK; expanded = false }
                    )
                    
                    val gateways by service.serverBases.collectAsState()
                    if (gateways.isNotEmpty()) {
                        Text("GATEWAYS", modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        gateways.forEach { gw ->
                            DropdownMenuItem(
                                text = { 
                                    Column {
                                        Text(gw.name)
                                        Text("Better voice, adds ~1-3s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                                    }
                                },
                                onClick = { 
                                    voiceMode = VoiceMode.GATEWAY
                                    backendUrl = gw.url
                                    expanded = false 
                                }
                            )
                        }
                    }
                    
                    DropdownMenuItem(
                        text = { Text("Gateway (Custom URL)") },
                        onClick = { 
                            voiceMode = VoiceMode.GATEWAY
                            expanded = false 
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { 
                    service.testAudio("This is a test of the voice output.", voiceMode, backendUrl, targetLanguage, isTranslator, voiceEngine, kokoroVoice)
                },
                enabled = voiceMode != VoiceMode.NONE,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = themeColor.copy(alpha = 0.8f))
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null)
                Spacer(Modifier.width(8.dp))
                Text("Test Audio")
            }
        }

        if (voiceMode == VoiceMode.GATEWAY) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Voice Engine", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = voiceEngine == "kokoro",
                                onClick = { voiceEngine = "kokoro" },
                                label = { Text("Kokoro") },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = voiceEngine == "sesame",
                                onClick = { voiceEngine = "sesame" },
                                label = { Text("Sesame") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (voiceEngine == "sesame") {
                            Text(
                                "Note: Sesame engine currently only supports English.",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (targetLanguage == "English") MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }

                    if (voiceEngine == "kokoro") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Kokoro Voice", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilterChip(
                                    selected = kokoroVoice == "af_heart",
                                    onClick = { kokoroVoice = "af_heart" },
                                    label = { Text("Default", style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = kokoroVoice == "af_nicole",
                                    onClick = { kokoroVoice = "af_nicole" },
                                    label = { Text("Nicole", style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = kokoroVoice == "am_echo",
                                    onClick = { kokoroVoice = "am_echo" },
                                    label = { Text("Echo", style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = backendUrl,
                    onValueChange = { backendUrl = it },
                    label = { Text("Gateway URL") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            SettingsSectionHeader(title = "LLM Parameters", icon = Icons.Default.Tune)
            SettingsSection {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ParameterSlider(label = "Temperature", value = temp, range = 0f..2f, onValueChange = { temp = it })
                    ParameterSlider(label = "Top P", value = topP, range = 0f..1f, onValueChange = { topP = it })
                    ParameterSlider(label = "Repeat Penalty", value = repeatPenalty, range = 0.5f..2.0f, onValueChange = { repeatPenalty = it })
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Top K: ", style = MaterialTheme.typography.labelSmall)
                        Slider(value = topK.toFloat(), onValueChange = { topK = it.toInt() }, valueRange = 1f..100f, modifier = Modifier.weight(1f))
                        Text(topK.toString(), modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Max Response Length", style = MaterialTheme.typography.labelSmall)
                            Text(maxTokens.toString(), style = MaterialTheme.typography.labelSmall)
                        }
                        Slider(value = maxTokens.toFloat(), onValueChange = { maxTokens = it.toInt() }, valueRange = 100f..8192f)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Fast (slow CPU / low RAM)", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            Text("Long (needs fast hardware)", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }

                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Context Window Size", style = MaterialTheme.typography.labelSmall)
                            Text(numCtx.toString(), style = MaterialTheme.typography.labelSmall)
                        }
                        Slider(value = numCtx.toFloat(), onValueChange = { numCtx = it.toInt() }, valueRange = 2048f..32768f)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Smaller (less VRAM / faster)", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            Text("Larger (more VRAM / memory-heavy)", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Enable Thinking Process", style = MaterialTheme.typography.bodyMedium)
                                Text("Turn off for creative writing or simple chat — frees the full token budget for the response instead of internal reasoning.", 
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                            Switch(checked = enableThinking, onCheckedChange = { enableThinking = it })
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Web Search", style = MaterialTheme.typography.bodyMedium)
                                Text("Search the web before responding — best for factual or current-events questions.", 
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                            Switch(checked = webSearchEnabled, onCheckedChange = { webSearchEnabled = it })
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Knowledge Base", style = MaterialTheme.typography.bodyMedium)
                                Text("Search your uploaded documents before responding.",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                            Switch(checked = ragEnabled, onCheckedChange = { ragEnabled = it })
                        }
                    }
                }
            }
        }

        onDelete?.let {
            item {
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Default.Delete, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Delete Persona")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Persona") },
            text = { Text("Are you sure you want to delete '${name}'? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete?.invoke()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ParameterSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(String.format(java.util.Locale.US, "%.2f", value), style = MaterialTheme.typography.labelSmall)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
fun LanguageBar(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    personaColor: Color
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.wrapContentSize(Alignment.Center)) {
            OutlinedButton(
                onClick = { expanded = true },
                border = BorderStroke(1.dp, personaColor),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = personaColor)
            ) {
                Icon(Icons.Default.Translate, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Target: $currentLanguage", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.ArrowDropDown, null)
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(200.dp).heightIn(max = 500.dp)
            ) {
                TRANSLATION_LANGUAGES.forEach { lang ->
                    val selected = lang == currentLanguage
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = lang,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) personaColor else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        onClick = {
                            onLanguageSelected(lang)
                            expanded = false
                        },
                        leadingIcon = {
                            if (selected) {
                                Icon(Icons.Default.Check, null, tint = personaColor, modifier = Modifier.size(18.dp))
                            }
                        }
                    )
                }
            }
        }
    }
}
