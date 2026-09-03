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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaSelector(
    personaList: List<Persona>,
    currentPersona: Persona,
    onPersonaSelected: (Persona) -> Unit,
    onDismiss: () -> Unit,
    personaColor: Color
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Select Persona", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))
            personaList.forEach { persona ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onPersonaSelected(persona) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProviderLogo(icon = persona.providerIcon, isCloud = persona.isCloud)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(persona.name, style = MaterialTheme.typography.bodyLarge, color = if (persona.name == currentPersona.name) personaColor else MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(service: AssistantService?, onDismiss: () -> Unit, personaColor: Color) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("General", "Servers", "Cloud", "Personas")

    var totalCost by remember { mutableDoubleStateOf(0.0) }
    var serverBases by remember { mutableStateOf<List<ServerConfig>>(emptyList()) }
    var ollamaBases by remember { mutableStateOf<List<ServerConfig>>(emptyList()) }
    var cloudApis by remember { mutableStateOf<List<CloudApiSetting>>(emptyList()) }
    var personas by remember { mutableStateOf<List<Persona>>(emptyList()) }

    LaunchedEffect(service) {
        if (service != null) {
            launch { service.totalCost.collect { totalCost = it } }
            launch { service.serverBases.collect { serverBases = it } }
            launch { service.ollamaBaseUrls.collect { ollamaBases = it } }
            launch { service.cloudApis.collect { cloudApis = it } }
            launch { service.personas.collect { personas = it } }
        }
    }

    if (service == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.padding(16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = personaColor,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = personaColor
                        )
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f).padding(16.dp)) {
                    when (selectedTab) {
                        0 -> GeneralSettings(service, totalCost, onDismiss)
                        1 -> ServerSettings(service, serverBases, ollamaBases)
                        2 -> CloudSettings(service, cloudApis)
                        3 -> PersonaSettings(service, personas, personaColor)
                    }
                }

                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        text = "Settings are automatically saved.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        textAlign = TextAlign.Center
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Close", color = personaColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GeneralSettings(service: AssistantService, totalCost: Double, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val backup = service.exportBackup()
                    context.contentResolver.openOutputStream(it)?.use { out ->
                        out.write(backup.toByteArray())
                    }
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Backup exported successfully", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val json = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { it.readText() }
                    if (json != null) {
                        val success = service.importBackup(json)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                android.widget.Toast.makeText(context, "Import successful", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, "Import failed: Invalid format", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Import failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SettingsSectionHeader(title = "Data & Usage", icon = Icons.Default.Analytics)
            SettingsSection {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Total Session Cost", style = MaterialTheme.typography.titleMedium)
                        Text("$${String.format(java.util.Locale.US, "%.6f", totalCost)}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Button(
                        onClick = { service.resetTotalCost() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Reset")
                    }
                }
            }
        }

        item {
            SettingsSectionHeader(title = "Location", icon = Icons.Default.LocationOn)
            SettingsSection {
                var userLocation by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    userLocation = service.getUserLocation() ?: ""
                }
                OutlinedTextField(
                    value = userLocation,
                    onValueChange = { 
                        userLocation = it
                        service.saveUserLocation(it)
                    },
                    label = { Text("Assistant Location") },
                    placeholder = { Text("e.g. Athens, Greece") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Optional: Used to localize news briefings and searches.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        item {
            SettingsSectionHeader(title = "History", icon = Icons.Default.History)
            var showClearConfirm by remember { mutableStateOf(false) }
            SettingsSection {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.DeleteSweep, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Clear All Messages")
                    }
                }
            }

            if (showClearConfirm) {
                AlertDialog(
                    onDismissRequest = { showClearConfirm = false },
                    title = { Text("Clear History") },
                    text = { Text("Are you sure you want to permanently delete all messages?") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                service.clearMessages()
                                showClearConfirm = false
                                onDismiss()
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Clear All")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearConfirm = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }

        item {
            SettingsSectionHeader(title = "Backup & Recovery", icon = Icons.Default.CloudSync)
            SettingsSection {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            exportLauncher.launch("voice_assistant_backup_${System.currentTimeMillis()}.json")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.Save, null)
                        Spacer(Modifier.width(12.dp))
                        Text("Export Backup to File")
                    }

                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.SettingsBackupRestore, null)
                        Spacer(Modifier.width(12.dp))
                        Text("Import Backup from File")
                    }
                }
            }
        }
    }
}


@Composable
fun ServerSettings(service: AssistantService, gateways: List<ServerConfig>, ollama: List<ServerConfig>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverStatus by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(service) {
        service.serverStatus.collect { serverStatus = it }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SettingsSectionHeader(title = "Connections", icon = Icons.Default.Dns)
            ServerListSection(
                title = "Local Gateways",
                servers = gateways,
                status = serverStatus,
                onAdd = { name: String, url: String, user: String?, pass: String?, authType: AuthType, apiKey: String? -> service.addServerBase(name, url, user, pass, authType, apiKey) },
                onRemove = { service.removeServerBase(it) },
                onEdit = { old: ServerConfig, name: String, url: String, user: String?, pass: String?, authType: AuthType, apiKey: String? -> service.updateServerBase(old, name, url, user, pass, authType, apiKey) },
                onRefreshHealth = { service.forceCheckHealth(it, true) },
                onRefreshModels = { /* Gateways don't have models */ },
                onMoveUp = { service.moveServerUp(it, false) },
                onMoveDown = { service.moveServerDown(it, false) },
                isGateway = true
            )
        }
        item {
            ServerListSection(
                title = "Ollama Servers",
                servers = ollama,
                status = serverStatus,
                onAdd = { name: String, url: String, user: String?, pass: String?, authType: AuthType, apiKey: String? -> service.addOllamaBase(name, url, user, pass, authType, apiKey) },
                onRemove = { service.removeOllamaBase(it) },
                onEdit = { old: ServerConfig, name: String, url: String, user: String?, pass: String?, authType: AuthType, apiKey: String? -> service.updateOllamaBase(old, name, url, user, pass, authType, apiKey) },
                onRefreshHealth = { service.forceCheckHealth(it, false) },
                onRefreshModels = { service.fetchModels(it) },
                onMoveUp = { service.moveServerUp(it, true) },
                onMoveDown = { service.moveServerDown(it, true) },
                isGateway = false
            )
        }
        item {
            SettingsSectionHeader(title = "External Services", icon = Icons.Default.Public)
            SettingsSection {
                var searxngUrl by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    searxngUrl = service.getSearxngUrl() ?: ""
                }

                OutlinedTextField(
                    value = searxngUrl,
                    onValueChange = { 
                        searxngUrl = it
                        service.saveSearxngUrl(it)
                    },
                    label = { Text("SearXNG URL") },
                    placeholder = { Text("e.g. http://192.168.1.10:8080") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Self-hosted search API for persona web capability.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        item {
            SettingsSectionHeader(title = "Knowledge Base", icon = Icons.Default.Book)
            SettingsSection {
                var ragUrl by remember { mutableStateOf("") }
                var ragUser by remember { mutableStateOf("") }
                var ragPass by remember { mutableStateOf("") }
                var docCount by remember { mutableStateOf<Int?>(null) }
                var uploadStatus by remember { mutableStateOf<String>("") }

                LaunchedEffect(service) {
                    ragUrl = service.getRagServerUrl() ?: ""
                    ragUser = service.getRagUsername() ?: ""
                    ragPass = service.getRagPassword() ?: ""
                }

                OutlinedTextField(
                    value = ragUrl,
                    onValueChange = {
                        ragUrl = it
                        service.saveRagServerUrl(it)
                        docCount = null
                    },
                    label = { Text("RAG Server URL") },
                    placeholder = { Text("e.g. https://192.168.1.10:8882") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = ragUser,
                        onValueChange = {
                            ragUser = it
                            service.saveRagUsername(it)
                        },
                        label = { Text("RAG Username") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = ragPass,
                        onValueChange = {
                            ragPass = it
                            service.saveRagPassword(it)
                        },
                        label = { Text("RAG Password") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "Local RAG microservice (HTTPS with self-signed cert — approve the trust prompt on first connection). Enter the service's own username/password above. Leave blank to disable.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (ragUrl.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = docCount?.let { "Documents in collection: $it" } ?: "Click refresh to check collection",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = {
                            scope.launch {
                                docCount = null
                                when (val result = service.getKnowledgeCount(ragUrl)) {
                                    is RagResult.Success -> docCount = result.value
                                    is RagResult.Failure -> uploadStatus = "Error: ${result.exception.message}"
                                }
                            }
                        }) {
                            Text("Refresh")
                        }
                    }
                }

                val kbAttachmentLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris ->
                    uris.forEach { uri ->
                        val name = uri.path?.substringAfterLast("/", "document")?.substringAfterLast(".") ?: "document"
                        scope.launch {
                            try {
                                val text = service.readAttachmentText(uri)
                                uploadStatus = "Uploading $name..."
                                when (val result = service.uploadKnowledgeDocument(text, name, ragUrl)) {
                                    is RagResult.Success -> {
                                        uploadStatus = "Uploaded $name"
                                        docCount = null
                                    }
                                    is RagResult.Failure -> uploadStatus = "Failed: ${result.exception.message}"
                                }
                            } catch (e: Exception) {
                                uploadStatus = "Failed: ${e.message}"
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { kbAttachmentLauncher.launch(arrayOf("*/*")) },
                    enabled = ragUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.UploadFile, null)
                    Spacer(Modifier.width(12.dp))
                    Text("Add Document")
                }

                if (uploadStatus.isNotBlank()) {
                    Text(
                        uploadStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uploadStatus.startsWith("Failed") || uploadStatus.startsWith("Unsupported")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ServerListSection(
    title: String,
    servers: List<ServerConfig>,
    status: Map<String, String>,
    onAdd: (String, String, String?, String?, AuthType, String?) -> Unit,
    onRemove: (ServerConfig) -> Unit,
    onEdit: (ServerConfig, String, String, String?, String?, AuthType, String?) -> Unit,
    onRefreshHealth: (ServerConfig) -> Unit,
    onRefreshModels: (ServerConfig) -> Unit,
    onMoveUp: (ServerConfig) -> Unit,
    onMoveDown: (ServerConfig) -> Unit,
    isGateway: Boolean
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<ServerConfig?>(null) }

    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var newUser by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var newAuthType by remember { mutableStateOf(AuthType.NONE) }
    var newApiKey by remember { mutableStateOf("") }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            IconButton(onClick = { 
                newName = ""; newUrl = ""; newUser = ""; newPass = ""
                showAddDialog = true 
            }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
        
        SettingsSection {
            if (servers.isEmpty()) {
                Text("No servers configured", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 8.dp))
            }
            servers.forEachIndexed { index, server ->
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status Dot
                    val serverStatus = status[server.name]
                    val isOnline = serverStatus == null || !serverStatus.lowercase().contains("failed")
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isOnline) Color.Green else Color.Red)
                            .clickable { onRefreshHealth(server) }
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(server.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(server.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        serverStatus?.let { err ->
                            Text(err, style = MaterialTheme.typography.labelSmall, color = if (isOnline) Color.Gray else MaterialTheme.colorScheme.error)
                        }
                    }
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onMoveUp(server) }, enabled = index > 0, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, null, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { onMoveDown(server) }, enabled = index < servers.size - 1, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(20.dp))
                        }
                        
                        if (!isGateway) {
                            IconButton(onClick = { onRefreshModels(server) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Sync, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }

                        IconButton(onClick = {
                            editingServer = server
                            newName = server.name
                            newUrl = server.url
                            newUser = server.username ?: ""
                            newPass = server.password ?: ""
                            newAuthType = if (server.username.isNullOrBlank()) AuthType.NONE else server.authType
                            newApiKey = server.apiKey ?: ""
                        }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onRemove(server) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (index < servers.size - 1) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                }
            }
        }

        if (showAddDialog || editingServer != null) {
            val isEditing = editingServer != null
            AlertDialog(
                onDismissRequest = {
                    showAddDialog = false
                    editingServer = null
                    newName = ""; newUrl = ""; newUser = ""; newPass = ""; newAuthType = AuthType.NONE; newApiKey = ""
                },
                title = { Text(if (isEditing) "Edit Server" else "Add Server") },
                text = {
                    Column {
                        OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = newUrl, onValueChange = { newUrl = it }, label = { Text("URL (e.g. http://192.168.1.10:8880)") }, modifier = Modifier.fillMaxWidth())

                        Spacer(Modifier.height(12.dp))
                        // Auth Type selector
                        Text("Authentication", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Spacer(Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AuthType.values().forEach { type ->
                                FilterChip(
                                    selected = newAuthType == type,
                                    onClick = { newAuthType = type },
                                    label = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) }
                                )
                            }
                        }

                        if (newAuthType == AuthType.BASIC) {
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = newUser, onValueChange = { newUser = it }, label = { Text("Username") }, modifier = Modifier.weight(1f))
                                OutlinedTextField(value = newPass, onValueChange = { newPass = it }, label = { Text("Password") }, modifier = Modifier.weight(1f), visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                            }
                        }

                        if (newAuthType == AuthType.API_KEY) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = newApiKey, onValueChange = { newApiKey = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newUrl.isNotBlank()) {
                            if (isEditing) {
                                onEdit(editingServer!!, newName.ifBlank { "Server" }, newUrl, newUser.ifBlank { null }, newPass.ifBlank { null }, newAuthType, newApiKey.ifBlank { null })
                            } else {
                                onAdd(newName.ifBlank { "Server" }, newUrl, newUser.ifBlank { null }, newPass.ifBlank { null }, newAuthType, newApiKey.ifBlank { null })
                            }
                            showAddDialog = false
                            editingServer = null
                            newName = ""; newUrl = ""; newUser = ""; newPass = ""; newAuthType = AuthType.NONE; newApiKey = ""
                        }
                    }) { Text(if (isEditing) "Save" else "Add") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showAddDialog = false
                        editingServer = null
                        newName = ""; newUrl = ""; newUser = ""; newPass = ""; newAuthType = AuthType.NONE; newApiKey = ""
                    }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
fun CloudSettings(service: AssistantService, apis: List<CloudApiSetting>) {
    var customApis by remember { mutableStateOf<List<CloudApiSetting>>(emptyList()) }
    var lastSync by remember { mutableLongStateOf(0L) }

    LaunchedEffect(service) {
        launch { service.customCloudApis.collect { customApis = it } }
        launch { service.lastPriceSyncTimestamp.collect { lastSync = it } }
    }
    
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SettingsSectionHeader(title = "Cloud Providers", icon = Icons.Default.Cloud)
        }

        item {
            SettingsSection {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Dynamic Pricing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        val syncLabel = remember(lastSync) {
                            if (lastSync == 0L) "Never synced" 
                            else "Last synced: ${java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US).format(java.util.Date(lastSync))}"
                        }
                        Text(
                            text = syncLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    TextButton(onClick = { service.syncOpenRouterPricing(force = true) }) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Refresh Now")
                    }
                }
            }
        }

        apis.forEachIndexed { index, api ->
            item {
                var apiKey by remember { mutableStateOf(api.apiKey) }

                SettingsSection {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProviderLogo(icon = api.icon, isCloud = true)
                            Spacer(Modifier.width(12.dp))
                            Text(api.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            
                            IconButton(onClick = { service.moveCloudApiUp(api) }, enabled = index > 0, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.KeyboardArrowUp, null, modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { service.moveCloudApiDown(api) }, enabled = index < apis.size - 1, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(20.dp))
                            }
                        }
                        
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it; service.updateCloudApi(index, api.copy(apiKey = it)) },
                            label = { Text("API Key") },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                        )
                        
                        Button(
                            onClick = { service.fetchCloudModels(api) },
                            modifier = Modifier.align(Alignment.End),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Refresh Models", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        
        item {
            CustomCloudApiList(
                apis = customApis,
                onAdd = { name, url, key -> service.addCustomCloudApi(name, url, key) },
                onRemove = { service.removeCustomCloudApi(it) },
                onEdit = { old, new -> service.updateCustomCloudApi(old, new) },
                onRefresh = { service.fetchCloudModels(it) },
                onMoveUp = { service.moveCustomCloudApiUp(it) },
                onMoveDown = { service.moveCustomCloudApiDown(it) }
            )
        }
    }
}

@Composable
fun CustomCloudApiList(
    apis: List<CloudApiSetting>,
    onAdd: (String, String, String) -> Unit,
    onRemove: (CloudApiSetting) -> Unit,
    onEdit: (CloudApiSetting, CloudApiSetting) -> Unit,
    onRefresh: (CloudApiSetting) -> Unit,
    onMoveUp: (CloudApiSetting) -> Unit,
    onMoveDown: (CloudApiSetting) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingApi by remember { mutableStateOf<CloudApiSetting?>(null) }
    
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsSectionHeader(title = "OpenAI-Compatible", icon = Icons.Default.SettingsInputComponent)
            IconButton(onClick = { showAddDialog = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
        
        SettingsSection {
            if (apis.isEmpty()) {
                Text("No custom providers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 8.dp))
            }
            apis.forEachIndexed { index, api ->
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(api.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(api.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onMoveUp(api) }, enabled = index > 0, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, null, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { onMoveDown(api) }, enabled = index < apis.size - 1, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { onRefresh(api) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { editingApi = api; name = api.name; url = api.baseUrl; key = api.apiKey }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onRemove(api) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (index < apis.size - 1) {
                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                }
            }
        }

        if (showAddDialog || editingApi != null) {
            val isEditing = editingApi != null
            AlertDialog(
                onDismissRequest = { 
                    showAddDialog = false; editingApi = null
                    name = ""; url = ""; key = ""
                },
                title = { Text(if (isEditing) "Edit Provider" else "Add Provider") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API Key") }, modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (url.isNotBlank() && name.isNotBlank()) {
                            if (isEditing) {
                                onEdit(editingApi!!, editingApi!!.copy(name = name, baseUrl = url, apiKey = key))
                            } else {
                                onAdd(name, url, key)
                            }
                            showAddDialog = false; editingApi = null
                            name = ""; url = ""; key = ""
                        }
                    }) { Text(if (isEditing) "Save" else "Add") }
                },
                dismissButton = {
                    TextButton(onClick = { 
                        showAddDialog = false; editingApi = null
                        name = ""; url = ""; key = ""
                    }) { Text("Cancel") }
                }
            )
        }
    }
}
