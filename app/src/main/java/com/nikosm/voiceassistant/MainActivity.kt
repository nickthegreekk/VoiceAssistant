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

    /**
     * Persona name carried by a spontaneous-message notification tap. `MutableState` (not a
     * plain field) so Compose picks up a tap that lands while the tree is already composed —
     * `onNewIntent` fires with no recomposition of its own.
     */
    private var deepLinkPersona by mutableStateOf<String?>(null)
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
            // FGS-timeout hardening: startForegroundService() puts the system on a
            // ~10s clock requiring startForeground() — but onStartCommand can only
            // legally do that once RECORD_AUDIO is granted (the manifest FGS type is
            // microphone), so a fresh install whose permission dialogs took longer
            // than the clock had its whole process killed mid-flow. Start the
            // service as a plain started service while the mic permission is still
            // missing (no deadline — the dialogs may take as long as the user
            // needs); the permission result callback promotes it to foreground the
            // moment RECORD_AUDIO lands (see MainVoiceScreen.permissionLauncher and
            // onResume below).
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            bindService(intent, connection, BIND_AUTO_CREATE)
        }
        enableEdgeToEdge()
        setContent {
            // Adaptive Theme (Settings > General > Appearance): the service owns the
            // persisted flag, so collect it here and re-derive the root color scheme from
            // the wallpaper the instant the switch flips. While the binder is still null
            // the flag stays false, so the existing static theme renders first, unchanged.
            val svc = assistantService
            var adaptiveTheme by remember { mutableStateOf(false) }
            LaunchedEffect(svc) {
                val bound = svc
                if (bound == null) adaptiveTheme = false
                else bound.adaptiveTheme.collect { adaptiveTheme = it }
            }
            VoiceAssistantTheme(dynamicColor = adaptiveTheme) {
                MainScreen(
                    service = svc,
                    deepLinkPersona = deepLinkPersona,
                    onDeepLinkHandled = { deepLinkPersona = null }
                )
            }
        }
        // O3: a cold start from a notification tap arrives via onCreate, not onNewIntent.
        handleSpontaneousDeepLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        // O3: with launchMode="singleTop", a tap while the activity exists (backgrounded OR
        // foreground — the foreground case keeps working because the state above is picked
        // up) routes here instead of stacking a second instance.
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpontaneousDeepLink(intent)
    }

    /**
     * Pull [EXTRA_TARGET_PERSONA_NAME] out of a notification tap into [deepLinkPersona].
     * The actual switch waits for `MainScreen` for two reasons: the service may not be
     * bound yet (cold start), and only the UI knows which overlays (`showSettings`,
     * room mode) would hide the chat the notification promised.
     */
    private fun handleSpontaneousDeepLink(intent: Intent?) {
        val target = intent?.getStringExtra(EXTRA_TARGET_PERSONA_NAME)?.takeIf { it.isNotBlank() }
        if (target != null) deepLinkPersona = target
    }

    override fun onResume() {
        super.onResume()
        // Hardening companion: no in-app result callback fires when RECORD_AUDIO is
        // granted from system Settings (or after a process recreation mid-dialog
        // flow), so the service could otherwise stay a plain started service while
        // the mic is already usable. Promote on every resume when the service is
        // idle — promoteToForeground() is mic-guarded and startForeground() is
        // idempotent; the IDLE guard avoids clobbering a "Listening..."/"Thinking..."
        // notification with the idle one.
        val svc = assistantService ?: return
        if (svc._state.value == AssistantState.IDLE) svc.promoteToForeground()
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

/**
 * Personas category screen: the list-then-editor pattern, one level below the top-level
 * Settings list.
 *
 * Body of [SettingsCategoryScreen]. Tapping a persona opens its editor in place, exactly
 * as before; the "add" control is now a trailing row in the list rather than a header
 * affordance, because there is no collapsible header row any more.
 */
@Composable
fun PersonaSettings(
    service: AssistantService,
    personas: List<Persona>,
    currentThemeColor: Color
) {
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

    // PersonaEditor is a plain Column, so it adds no scrollable of its own and needs no
    // height bound: a category screen caps nothing and scrolls nothing itself.
    if (editingPersona == null) {
        // Plain Column, not LazyColumn: the category page already scrolls, and a nested
        // lazy list inside a vertical scroll parent fights it for the drag.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            personas.forEachIndexed { index, persona ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { editingPersona = index to persona },
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, persona.themeColor.copy(alpha = 0.3f))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProviderLogo(icon = persona.providerIcon, isCloud = persona.isCloud, size = 52.dp, imagePath = persona.iconImageUri)
                        // icon size is provider logo changed from 40 to 52 dp
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(persona.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(persona.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // Reorder controls — same up/down pattern as the server and
                        // cloud-API lists. Order is user-visible: the persona selector
                        // and the deletion fallback ("first remaining persona") follow it.
                        IconButton(onClick = { service.movePersonaUp(index) }, enabled = index > 0, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, null, modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { service.movePersonaDown(index) }, enabled = index < personas.size - 1, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(20.dp))
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    }
                }
            }

            // "New persona" used to be an IconButton in the collapsible section header.
            // A category screen has no header row, so it is a trailing list row instead —
            // same seeded draft, always visible below the existing personas.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        editingPersona = -1 to DEFAULT_PERSONAS[0].copy(
                            name = "New Persona",
                            themeColor = PERSONA_PALETTE.random()
                        )
                    },
                shape = RoundedCornerShape(16.dp),
                color = Color.Black.copy(alpha = 0.2f),
                border = BorderStroke(1.dp, currentThemeColor.copy(alpha = 0.3f))
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Add, null, tint = currentThemeColor, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(
                        "New Persona",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = currentThemeColor
                    )
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



