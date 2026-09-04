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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

// The persona editor's open state must survive Activity recreation (rotation), but
// Persona is kotlinx-serializable rather than Parcelable — persist it as JSON.
@Serializable
private data class EditingPersonaPayload(val index: Int, val persona: Persona)

private val EditingPersonaSaver = Saver<Pair<Int, Persona>?, String>(
    save = { editing ->
        editing?.let { (index, persona) ->
            Json.encodeToString(EditingPersonaPayload.serializer(), EditingPersonaPayload(index, persona))
        } ?: ""
    },
    restore = { encoded ->
        if (encoded.isEmpty()) null
        else runCatching {
            val payload = Json.decodeFromString(EditingPersonaPayload.serializer(), encoded)
            payload.index to payload.persona
        }.getOrNull()
    }
)

    class MainActivity : ComponentActivity() {
    private var assistantService by mutableStateOf<AssistantService?>(null)
    private var isBound = false
    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            val binder = service as AssistantService.AssistantBinder
            assistantService = binder.getService()
            isBound = true
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            assistantService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Intent(this, AssistantService::class.java).also { intent ->
            startForegroundService(intent)
            bindService(intent, connection, BIND_AUTO_CREATE)
        }
        enableEdgeToEdge()
        setContent { VoiceAssistantTheme { MainScreen(assistantService) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            try {
                unbindService(connection)
            } catch (_: IllegalArgumentException) {
                // Service may have already been disconnected
            }
            isBound = false
        }
    }
}

@Composable
fun PersonaSettings(service: AssistantService, personas: List<Persona>, currentThemeColor: Color) {
    var editingPersona by rememberSaveable(stateSaver = EditingPersonaSaver) { mutableStateOf<Pair<Int, Persona>?>(null) }

    // One-shot staleness guard: when the Activity is recreated while the editor is open,
    // the restored snapshot can be older than the service's copy (edits are debounced
    // into the service while editing). Re-resolve it against the current list so the
    // editor doesn't show — and later re-commit — outdated values. On a fresh open,
    // editingPersona is still null at first composition, so this is a no-op.
    LaunchedEffect(Unit) {
        val (index, snapshot) = editingPersona ?: return@LaunchedEffect
        if (index == -1) return@LaunchedEffect // brand-new persona: the snapshot is the source of truth
        personas.find { it.name == snapshot.name }?.let { fresh ->
            if (fresh != snapshot) editingPersona = index to fresh
        }
    }

    if (editingPersona == null) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsSectionHeader(title = "Personas & Voice", icon = Icons.Default.Person)
                IconButton(onClick = { editingPersona = -1 to DEFAULT_PERSONAS[0].copy(name = "New Persona", themeColor = PERSONA_PALETTE.random()) }) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(personas) { index, persona ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable { editingPersona = index to persona },
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, persona.themeColor.copy(alpha = 0.3f))
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            ProviderLogo(icon = persona.providerIcon, isCloud = persona.isCloud, size = 40.dp)
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(persona.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(persona.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                        }
                    }
                }
            }
        }
    } else {
        val (index, persona) = editingPersona!!
        PersonaEditor(
            persona = persona,
            onSave = { updated ->
                if (index == -1) {
                    service.addPersona(updated)
                    // Switch from "add" to "edit" mode for this persona
                    val newIdx = service.personas.value.indexOf(updated)
                    if (newIdx != -1) editingPersona = newIdx to updated
                } else {
                    service.updatePersona(index, updated)
                }
            },
            onDelete = if (index != -1) { { service.removePersona(index); editingPersona = null } } else null,
            onCancel = { editingPersona = null },
            service = service
        )
    }
}



