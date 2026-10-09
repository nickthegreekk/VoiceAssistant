package com.nikosm.voiceassistant

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import java.io.File
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

/**
 * Everything the persona editor's TWO commit paths read from its local field state.
 *
 * Bundling it is what lets ONE function build the Persona for both the debounced auto-save and
 * the dispose-time flush, so they can never diverge. They used to: the debounce derived
 * `isCloud`/`providerIcon` from the model string while the flush preserved the values of the
 * persona as it was opened, so closing the editor inside the 500ms debounce window persisted
 * the stale pair.
 */
internal data class PersonaEditorFields(
    val name: String,
    val model: String,
    val systemPrompt: String,
    val themeColor: Color,
    val iconImageUri: String?,
    val backgroundImageUri: String?,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val repeatPenalty: Float,
    val maxTokens: Int,
    val numCtx: Int,
    val enableThinking: Boolean,
    val webSearchEnabled: Boolean,
    val ragEnabled: Boolean,
    val allowGatewayFailover: Boolean,
    val isTranslator: Boolean,
    val isImageGenerator: Boolean,
    val targetLanguage: String,
    val voiceMode: VoiceMode,
    val voiceEngine: String,
    val kokoroVoice: String,
    val backendUrl: String,
    val enabledSkillIds: List<String>,
    val spontaneousEnabled: Boolean,
    val spontaneousInstruction: String,
    val spontaneousGapMinutes: Int,
    val spontaneousWindowStartMin: Int?,
    val spontaneousWindowEndMin: Int?
)

/** The `[Provider]` prefix of [model], or "" — the same parse every router performs. */
internal fun personaEditorProviderName(model: String): String {
    val m = model.trim()
    return if (m.startsWith("[") && m.contains("] ")) m.substring(1, m.indexOf("]")) else ""
}

/** Is [providerName] one of the configured cloud providers (fixed list OR custom)? */
internal fun personaEditorIsCloudModel(providerName: String, cloudProviderNames: Set<String>): Boolean =
    providerName.isNotEmpty() && providerName in cloudProviderNames

/** The provider badge glyph, derived from the model. */
internal fun personaEditorProviderIcon(
    trimmedModel: String,
    providerName: String,
    customProviderNames: Set<String>,
    isTranslator: Boolean,
    isImageGenerator: Boolean
): String = when {
    trimmedModel.startsWith("[Anthropic]") -> "A"
    trimmedModel.startsWith("[OpenAI]") -> "O"
    trimmedModel.startsWith("[Google]") -> "G"
    trimmedModel.startsWith("[DeepSeek]") -> "D"
    trimmedModel.startsWith("[Cline]") -> "CL"
    providerName in customProviderNames || trimmedModel.startsWith("[OpenAI-Compatible]") -> "C"
    trimmedModel.contains("Translator") || isTranslator -> "T"
    isImageGenerator -> "IMG"
    else -> "O" // Local Ollama
}

/**
 * THE builder both commit paths use: [fields] applied to [this], with [isCloudModel] and
 * `spontaneousAllowed` decided by the caller from the LIVE server lists.
 *
 * `providerColor` and `spontaneousMaxPerDay` are deliberately NOT set — the editor has no
 * control for them, and a `copy` that does not mention a field preserves the persona's value.
 * Pure, so the local→cloud commit is unit-tested without a device.
 */
internal fun Persona.withEditorFields(
    fields: PersonaEditorFields,
    isCloudModel: Boolean,
    customProviderNames: Set<String>,
    spontaneousAllowed: Boolean
): Persona {
    val trimmedModel = fields.model.trim()
    return copy(
        name = fields.name,
        model = trimmedModel,
        systemPrompt = fields.systemPrompt,
        themeColor = fields.themeColor,
        isCloud = isCloudModel,
        providerIcon = personaEditorProviderIcon(
            trimmedModel = trimmedModel,
            providerName = personaEditorProviderName(trimmedModel),
            customProviderNames = customProviderNames,
            isTranslator = fields.isTranslator,
            isImageGenerator = fields.isImageGenerator
        ),
        iconImageUri = fields.iconImageUri,
        backgroundImageUri = fields.backgroundImageUri,
        temperature = fields.temperature,
        topP = fields.topP,
        topK = fields.topK,
        repeatPenalty = fields.repeatPenalty,
        maxTokens = fields.maxTokens,
        numCtx = fields.numCtx,
        enableThinking = fields.enableThinking,
        webSearchEnabled = fields.webSearchEnabled,
        ragEnabled = fields.ragEnabled,
        allowGatewayFailover = fields.allowGatewayFailover,
        isTranslator = fields.isTranslator,
        isImageGenerator = fields.isImageGenerator,
        targetLanguage = fields.targetLanguage,
        voiceMode = fields.voiceMode,
        voiceEngine = fields.voiceEngine,
        kokoroVoice = fields.kokoroVoice,
        backendUrl = fields.backendUrl,
        enabledSkillIds = fields.enabledSkillIds,
        // Gated (plan location #2): never commit an opt-in the persona could not honour.
        spontaneousEnabled = fields.spontaneousEnabled && spontaneousAllowed,
        spontaneousInstruction = fields.spontaneousInstruction,
        spontaneousGapMinutes = fields.spontaneousGapMinutes,
        spontaneousWindowStartMin = fields.spontaneousWindowStartMin,
        spontaneousWindowEndMin = fields.spontaneousWindowEndMin
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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
    // Custom artwork, held as INTERNAL file paths (never the raw picker URI) — see
    // SettingsManager.savePersonaImage, which copies the pick into app-owned storage
    // at selection time. Both flow into the saved Persona (see the two copy sites
    // below) so the debounced auto-save persists them like any other field.
    var iconImageUri by remember(persona) { mutableStateOf(persona.iconImageUri) }
    var backgroundImageUri by remember(persona) { mutableStateOf(persona.backgroundImageUri) }
    var temp by remember(persona) { mutableFloatStateOf(persona.temperature) }
    var topP by remember(persona) { mutableFloatStateOf(persona.topP) }
    var topK by remember(persona) { mutableIntStateOf(persona.topK) }
    var repeatPenalty by remember(persona) { mutableFloatStateOf(persona.repeatPenalty) }
    var maxTokens by remember(persona) { mutableIntStateOf(persona.maxTokens) }
    var numCtx by remember(persona) { mutableIntStateOf(persona.numCtx) }
    var enableThinking by remember(persona) { mutableStateOf(persona.enableThinking) }
    var webSearchEnabled by remember(persona) { mutableStateOf(persona.webSearchEnabled) }
    var ragEnabled by remember(persona) { mutableStateOf(persona.ragEnabled) }
    var allowGatewayFailover by remember(persona) { mutableStateOf(persona.allowGatewayFailover) }
    
    var isTranslator by remember(persona) { mutableStateOf(persona.isTranslator) }
    var isImageGenerator by remember(persona) { mutableStateOf(persona.isImageGenerator) }
    var targetLanguage by remember(persona) { mutableStateOf(persona.targetLanguage) }
    var voiceMode by remember(persona) { mutableStateOf(persona.voiceMode) }
    var voiceEngine by remember(persona) { mutableStateOf(persona.voiceEngine) }
    var kokoroVoice by remember(persona) { mutableStateOf(persona.kokoroVoice) }
    var backendUrl by remember(persona) { mutableStateOf(persona.backendUrl) }
    // Skills are toggled through setSkillEnabledForPersona (which saves at once), but the
    // debounce and the dispose flush BOTH rebuild the whole Persona from this editor's
    // snapshot — and neither listed enabledSkillIds, so each would have written the value
    // frozen at open time straight back over the toggle. Tracking it locally is what makes
    // the two paths agree; see setSkillEnabled below.
    var enabledSkillIds by remember(persona) { mutableStateOf(persona.enabledSkillIds) }
    // Spontaneous messages. Tracked locally for exactly the reason enabledSkillIds is: BOTH
    // the debounce and the dispose flush rebuild a WHOLE Persona from this editor's
    // snapshot, so a field omitted here would be written back at its open-time value the
    // moment any other field changed — or the moment the editor closed inside the debounce
    // window. `spontaneousMaxPerDay` is deliberately NOT tracked: it has no control yet (it
    // is reserved for the scheduler), and a `copy` that never mentions it preserves it.
    var spontaneousEnabled by remember(persona) { mutableStateOf(persona.spontaneousEnabled) }
    var spontaneousInstruction by remember(persona) { mutableStateOf(persona.spontaneousInstruction) }
    var spontaneousGapMinutes by remember(persona) { mutableStateOf(persona.spontaneousGapMinutes) }
    var spontaneousWindowStartMin by remember(persona) { mutableStateOf(persona.spontaneousWindowStartMin) }
    var spontaneousWindowEndMin by remember(persona) { mutableStateOf(persona.spontaneousWindowEndMin) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var showFullscreenPrompt by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Copies a freshly-picked image into app-owned storage, off the main thread via
    // the service (it decodes + downsamples a full-sized photo). Doing the copy at
    // pick time — rather than rendering the picker URI directly — is what makes the
    // artwork survive the photo-picker's read grant expiring, and what lets the user
    // later move or delete the original photo without breaking the persona.
    fun adoptPickedImage(uri: Uri?, slot: String, onAdopted: (String) -> Unit) {
        if (uri == null) return // picker dismissed — keep whatever artwork exists
        scope.launch {
            val saved = service.savePersonaImage(uri, slot)
            if (saved != null) {
                onAdopted(saved)
            } else {
                Toast.makeText(context, "Couldn't load that image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // PickVisualMedia is the system photo picker: it grants read access to just the
    // selected item and requires NO storage permission at any API level, unlike the
    // legacy GetContent/OpenDocument contracts.
    val iconPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        adoptPickedImage(uri, "icon") { iconImageUri = it }
    }
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        adoptPickedImage(uri, "background") { backgroundImageUri = it }
    }

    // Flush-on-close: the debounce's delay(500) is cancelled when the composable
    // leaves composition, so closing within 500ms of an edit silently discards it.
    // These refs always hold the latest values (re-assigned on every recomposition,
    // exactly like onSaveRef below): onSave switches from addPersona to updatePersona
    // after the first save, lastSavedPersona lets the dispose-time flush skip a save
    // the debounce already made, and disposeSnapshotRef carries the current persona
    // plus the live local field values into the flush — which matters because
    // DisposableEffect(Unit) itself never re-keys: without the ref its closure would
    // stay bound to the FIRST composition's persona and field states forever, and in
    // the add flow the first debounced save swaps the persona instance (add→edit)
    // and re-keys every remember(persona) state, so a close-time flush built from the
    // frozen originals reverted the persona to its pre-transition values, discarding
    // everything typed after that point (the "new persona name doesn't stick" bug).
    val onSaveRef = remember { mutableStateOf(onSave) }
    onSaveRef.value = onSave
    var lastSavedPersona by remember { mutableStateOf(persona) }

    // Spontaneous-messages gate — ONE decision, shared by the section below and by BOTH
    // commit paths in this editor, so what the user is shown and what gets written can
    // never disagree (plan locations #1 and #2).
    //
    // Evaluated against the SERVICE's live server lists, never this composable's collected
    // copies: those arrive from the LaunchedEffect further down, so the FIRST composition —
    // and every frame until it delivers — sees them EMPTY. An empty Ollama set makes a
    // perfectly local persona (`[HomeBox] llama3`) resolve as gateway-only, i.e. Blocked,
    // which would hide the section on the first frame and strip a valid opt-in on the first
    // commit. The service has already loaded its lists before it can be bound at all
    // (onServiceConnected fires only after onCreate returns), so reading it has no window.
    val spontaneousEligibility = spontaneousSectionEligibility(
        persona = persona,
        model = model,
        isTranslator = isTranslator,
        isImageGenerator = isImageGenerator,
        ollamaServerNames = service.ollamaServerNames(),
        cloudProviderNames = service.cloudProviderNames()
    )
    val spontaneousAllowed = spontaneousEligibility is RoomEligibility.Eligible

    // `disposeSnapshotRef` — the close-time flush's snapshot — is built further down, once the
    // cloud-provider lists it derives `isCloud`/`providerIcon` from have been collected. Both
    // commit paths go through the ONE shared builder (`withEditorFields`), so they cannot
    // diverge; see the `editorFields` block below.

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

    // Step 4, additive: the shared model catalog and the per-server health status, so an
    // Ollama connection's picker can read the same cache the models screen fills. Purely
    // additional collectors — nothing below reads them except the picker, and neither is
    // part of the persona snapshot, so the debounce and dispose-flush commit logic are
    // untouched.
    var modelCatalog by remember { mutableStateOf<Map<String, ModelCatalogState>>(emptyMap()) }
    var serverStatusMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(service) {
        launch { service.availableModels.collect { availableModels = it } }
        launch { service.customCloudApis.collect { customCloudApis = it } }
        launch { service.cloudApis.collect { cloudApis = it } }
        launch { service.ollamaBaseUrls.collect { ollamaBases = it } }
        launch { service.favoriteModels.collect { favorites = it } }
        launch { service.fetchedCloudModels.collect { fetchedCloudModels = it } }
        launch { service.modelCatalog.collect { modelCatalog = it } }
        launch { service.serverStatus.collect { serverStatusMap = it } }
    }

    // ---- The ONE builder both commit paths use --------------------------------
    // Everything the editor owns, in a form the single pure function `Persona.withEditorFields`
    // turns into a Persona. The debounced auto-save and the dispose-time flush BOTH call it, so
    // the two can no longer disagree — they used to: the debounce derived `isCloud`/`providerIcon`
    // from the model while the flush preserved the opened persona's values, so closing the editor
    // inside the 500ms debounce window persisted the STALE pair.
    val editorFields = PersonaEditorFields(
        name = name,
        model = model,
        systemPrompt = systemPrompt,
        themeColor = themeColor,
        iconImageUri = iconImageUri,
        backgroundImageUri = backgroundImageUri,
        temperature = temp,
        topP = topP,
        topK = topK,
        repeatPenalty = repeatPenalty,
        maxTokens = maxTokens,
        numCtx = numCtx,
        enableThinking = enableThinking,
        webSearchEnabled = webSearchEnabled,
        ragEnabled = ragEnabled,
        allowGatewayFailover = allowGatewayFailover,
        isTranslator = isTranslator,
        isImageGenerator = isImageGenerator,
        targetLanguage = targetLanguage,
        voiceMode = voiceMode,
        voiceEngine = voiceEngine,
        kokoroVoice = kokoroVoice,
        backendUrl = backendUrl,
        enabledSkillIds = enabledSkillIds,
        spontaneousEnabled = spontaneousEnabled,
        spontaneousInstruction = spontaneousInstruction,
        spontaneousGapMinutes = spontaneousGapMinutes,
        spontaneousWindowStartMin = spontaneousWindowStartMin,
        spontaneousWindowEndMin = spontaneousWindowEndMin
    )
    val editorProviderName = personaEditorProviderName(model)
    // Cloud inputs come from the SERVICE's already-loaded state, exactly like the spontaneous
    // gate above — NEVER from this editor's collected copies alone, which start EMPTY and fill in
    // a frame or two later. Deriving from an empty first frame would commit `isCloud=false` and
    // the local badge for a cloud persona the instant the editor closed inside that window. The
    // collected lists are unioned in only so a list EDITED while the editor is open re-derives.
    val customProviderNames = remember(service, customCloudApis) {
        service.customCloudApis.value.mapTo(mutableSetOf()) { it.name }
            .apply { addAll(customCloudApis.map { it.name }) }
    }
    val editorIsCloudModel = personaEditorIsCloudModel(
        providerName = editorProviderName,
        cloudProviderNames = service.cloudProviderNames() +
            cloudApis.map { it.name } +
            customCloudApis.map { it.name }
    )

    val disposeSnapshotRef = remember { mutableStateOf(persona) }
    disposeSnapshotRef.value =
        persona.withEditorFields(editorFields, editorIsCloudModel, customProviderNames, spontaneousAllowed)

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
    LaunchedEffect(name, model, systemPrompt, themeColor, temp, topP, topK, repeatPenalty, maxTokens, numCtx, enableThinking, webSearchEnabled, ragEnabled, allowGatewayFailover, isTranslator, isImageGenerator, targetLanguage, voiceMode, voiceEngine, kokoroVoice, backendUrl, enabledSkillIds, iconImageUri, backgroundImageUri, spontaneousEnabled, spontaneousInstruction, spontaneousGapMinutes, spontaneousWindowStartMin, spontaneousWindowEndMin, editorIsCloudModel, customProviderNames, spontaneousAllowed) {
        // Skip initial evaluation if needed? No, persona change will trigger it once, which is fine.
        delay(500)
        // The SAME single builder as the dispose flush (see `editorFields` above), so a save from
        // the debounce and one from the close-time flush can never produce different personas for
        // the same field state — the divergence that used to persist a stale `isCloud`/`providerIcon`.
        val updated = persona.withEditorFields(
            editorFields, editorIsCloudModel, customProviderNames, spontaneousAllowed
        )

        if (updated != persona) {
            onSaveRef.value(updated)
            lastSavedPersona = updated
        }
    }

    // Flush any pending edit on dispose. Reads everything through the refs above, so
    // it sees the live persona and field values at close time regardless of the
    // add→edit transition — and only saves if it differs from what the debounce
    // already saved, so a normal close doesn't double-save.
    DisposableEffect(Unit) {
        onDispose {
            val updated = disposeSnapshotRef.value
            if (updated != lastSavedPersona) {
                onSaveRef.value(updated)
            }
        }
    }

    // Flip one skill for this persona, on BOTH paths, deliberately.
    //
    // The service call is the real, immediate save and the one place the whole app mutates
    // this field. The local state update is not redundant: the debounce above and the
    // dispose flush both commit a WHOLE Persona rebuilt from this editor's snapshot, so
    // without it they would write the enabledSkillIds frozen at open time back over the
    // toggle the moment any other field changed — or the moment the editor closed inside
    // the 500ms window. It is also what makes a toggle work on a brand-new persona, which
    // setSkillEnabledForPersona cannot help with (the persona is not in the list yet).
    fun setSkillEnabled(skillId: String, enabled: Boolean) {
        enabledSkillIds = if (enabled) {
            (enabledSkillIds + skillId).distinct()
        } else {
            enabledSkillIds - skillId
        }
        // `persona.name` is the name the service currently knows this persona by. After a
        // rename has already been committed that lookup misses and the service call no-ops
        // — harmlessly, because the local value above still rides out with this editor's
        // own commit.
        service.setSkillEnabledForPersona(persona.name, skillId, enabled)
    }

    // Plain Column, NOT a scrollable of its own: the category page's LazyColumn is the
    // single scroller (same reasoning as the persona list in MainActivity). A nested
    // scrollable here had to be height-capped, which left dead space below the editor and
    // clipped the form mid-field.
    Column(
        modifier = Modifier.fillMaxWidth().imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Edit Persona", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onCancel) { Text("Close") }
        }

        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Persona Name") }, modifier = Modifier.fillMaxWidth())

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = isTranslator,
                onCheckedChange = {
                    isTranslator = it
                    if (it) isImageGenerator = false
                }
            )
            Text("Translator Persona")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = isImageGenerator,
                onCheckedChange = {
                    isImageGenerator = it
                    if (it) isTranslator = false
                }
            )
            Text("Image Generator Persona")
        }

        if (isImageGenerator) {
            var imageGenBases by remember { mutableStateOf<List<ServerConfig>>(emptyList()) }
            var activeBackend by remember { mutableStateOf(DEFAULT_IMAGE_GEN_BACKEND) }
            LaunchedEffect(service) {
                launch { imageGenBases = service.imageGenBases.value }
                launch { activeBackend = service.activeImageGenBackend.value }
                launch { service.imageGenBases.collect { imageGenBases = it } }
                launch { service.activeImageGenBackend.collect { activeBackend = it } }
            }

            // The SAME resolution the generation path uses, so this block can never describe a
            // different server (or a different failure) than the one that will actually run.
            val resolution = resolveImageGenConfig(imageGenBases, activeBackend, backendUrl.ifBlank { null })
            val activeEntry = imageGenBases.firstOrNull { it.backendType == activeBackend }
            val followLabel = "Follow the active generator (" +
                (activeEntry?.name ?: "no ${activeBackend.displayName} server yet") + ")"

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var expanded by remember { mutableStateOf(false) }
                // The pinned entry is shown with its TYPE, because the dropdown lists both
                // backends and two entries can be indistinguishable by name alone.
                val selectedName = when {
                    backendUrl.isBlank() -> followLabel
                    else -> imageGenBases.find { it.url == backendUrl }
                        ?.let { "${it.name} · ${it.backendType.displayName}" }
                        ?: "Custom URL"
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = selectedName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Image Backend") },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { IconButton(onClick = { expanded = true }) { Icon(Icons.Default.ArrowDropDown, null) } }
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text(followLabel) },
                            onClick = {
                                backendUrl = ""
                                expanded = false
                            }
                        )
                        imageGenBases.forEach { cfg ->
                            DropdownMenuItem(
                                text = { Text("${cfg.name} · ${cfg.backendType.displayName}") },
                                onClick = {
                                    backendUrl = cfg.url
                                    expanded = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = backendUrl,
                    onValueChange = { backendUrl = it },
                    label = { Text("Pinned server URL (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                // What this persona will ACTUALLY use, from the same resolution the generation path
                // runs. The stale case is called out by name rather than left invisible: the persona
                // still works, but not through the server its author pinned.
                when (resolution) {
                    is ImageGenResolution.NoEntryForActiveBackend ->
                        Text(
                            "No ${resolution.backend.displayName} server is configured. " +
                                "Add one in Settings → Servers → Image Gen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    is ImageGenResolution.Resolved -> {
                        val stale = resolution.stalePinnedUrl
                        if (stale != null) {
                            Text(
                                "The pinned server ($stale) is no longer configured. " +
                                    "This persona will use ${resolution.config.name} instead.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text(
                                "Images will use ${resolution.config.name} (${resolution.config.backendType.displayName}).",
                                style = MaterialTheme.typography.bodySmall,
                                color = themeColor.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
        }

        if (isTranslator) {
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

        SettingsSectionHeader(title = "Artwork", icon = Icons.Default.AddPhotoAlternate)
        SettingsSection {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Icon — previewed in the same circular crop ProviderLogo renders it
                // with, so what you pick is what you see on the persona list.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (iconImageUri != null) {
                            AsyncImage(
                                model = File(iconImageUri!!),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(Icons.Default.AddPhotoAlternate, null, tint = Color.White.copy(alpha = 0.4f))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Icon", style = MaterialTheme.typography.bodyMedium)
                        Text("Replaces the provider badge on the persona list.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    OutlinedButton(onClick = {
                        iconPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text(if (iconImageUri == null) "Choose" else "Replace") }
                }
                if (iconImageUri != null) {
                    // Clearing the field is enough: the save that follows reports the
                    // change, and AssistantService.updatePersona deletes the now
                    // unreferenced file.
                    TextButton(onClick = { iconImageUri = null }) { Text("Remove icon") }
                }

                // Background — full-screen backdrop behind the voice screen in both
                // the classic and celestial layouts, cropped to fill.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.08f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (backgroundImageUri != null) {
                            AsyncImage(
                                model = File(backgroundImageUri!!),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(Icons.Default.Wallpaper, null, tint = Color.White.copy(alpha = 0.4f))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Background", style = MaterialTheme.typography.bodyMedium)
                        Text("Full-screen backdrop behind the voice screen.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    OutlinedButton(onClick = {
                        backgroundPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text(if (backgroundImageUri == null) "Choose" else "Replace") }
                }
                if (backgroundImageUri != null) {
                    TextButton(onClick = { backgroundImageUri = null }) { Text("Remove background") }
                }
            }
        }

        var connectionExpanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = selectedConnection,
                onValueChange = { },
                readOnly = true,
                label = { Text("Connection") },
                placeholder = { Text("Choose a server or provider") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { connectionExpanded = true }) {
                        Icon(Icons.Default.Dns, null)
                    }
                }
            )
            DropdownMenu(expanded = connectionExpanded, onDismissRequest = { connectionExpanded = false }) {
                if (allConnections.isEmpty()) {
                    DropdownMenuItem(text = { Text("No connections configured") }, onClick = { })
                }
                allConnections.forEach { conn ->
                    DropdownMenuItem(
                        text = { Text(conn) },
                        onClick = { 
                            selectedConnection = conn
                            model = "" // Reset model when connection changes
                            connectionExpanded = false 
                        }
                    )
                }
            }
        }

        var modelExpanded by remember { mutableStateOf(false) }
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model") },
                placeholder = { Text("Choose a model") },
                modifier = Modifier.fillMaxWidth().testTag("personaModelField"),
                trailingIcon = {
                    IconButton(onClick = { modelExpanded = true }) {
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                }
            )
            DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }, modifier = Modifier.fillMaxWidth(0.8f)) {
                // Step 4: an Ollama connection reads the shared catalog, so its rows carry the
                // same capability chips and spec line the models screen shows. Everything else
                // — cloud connections, the empty-list wording, and every string written below —
                // is exactly as it was.
                val ollamaForConnection = ollamaBases.firstOrNull { it.name == selectedConnection }
                if (ollamaForConnection != null) {
                    OllamaModelPicker(
                        server = ollamaForConnection,
                        state = modelCatalog[ollamaForConnection.url] ?: ModelCatalogState.NotLoaded,
                        healthStatus = serverStatusMap[ollamaForConnection.url],
                        favoriteModels = favorites,
                        currentModel = model,
                        onRefresh = { service.fetchModelCatalog(ollamaForConnection, force = true) },
                        onToggleFavorite = { service.toggleFavoriteModel(it) },
                        // The qualified string the old inline row wrote, unchanged.
                        onSelect = { model = it; modelExpanded = false },
                        accentColor = themeColor
                        // No height cap here on purpose. The DropdownMenu already bounds its
                        // own viewport and scrolls its content; a `heightIn` on the child caps
                        // the CONTENT instead, so the menu's scroller measures 380.dp of
                        // "content" inside a taller viewport, concludes there is nothing to
                        // scroll, and silently clips every row past the third — unreachable
                        // and unstarable. Capping the viewport is the menu's job, not ours.
                    )
                } else {
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
                            onClick = { model = m; modelExpanded = false }
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("System Prompt", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                IconButton(
                    onClick = { showFullscreenPrompt = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "Expand System Prompt",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                modifier = Modifier.fillMaxWidth().height(120.dp),
                maxLines = 5
            )
        }

        // Skills sit directly under the prompt: a skill is extra instruction text bolted to
        // THIS persona's system prompt for the turns it fires on, so it is prompt
        // configuration and belongs with the prompt rather than down with the voice or
        // sampling settings.
        SettingsSectionHeader(title = "Skills", icon = Icons.Default.AutoAwesome)
        SettingsSection {
            PersonaSkillSection(
                enabledSkillIds = enabledSkillIds,
                onToggle = { skillId, enabled -> setSkillEnabled(skillId, enabled) },
                service = service
            )
        }

        // Spontaneous messages sit with the prompt-adjacent behaviour sections: like a skill,
        // this persona may carry extra instruction text, so it is prompt configuration rather
        // than voice or sampling. The gate (which personas may see the controls at all) shows
        // the shared verdict computed above — this section never re-decides.
        SettingsSectionHeader(title = "Spontaneous Messages", icon = Icons.Filled.NotificationsActive)
        SettingsSection {
            PersonaSpontaneousSection(
                eligibility = spontaneousEligibility,
                enabled = spontaneousEnabled,
                instruction = spontaneousInstruction,
                gapMinutes = spontaneousGapMinutes,
                windowStartMin = spontaneousWindowStartMin,
                windowEndMin = spontaneousWindowEndMin,
                onEnabledChange = { spontaneousEnabled = it },
                onInstructionChange = { spontaneousInstruction = it },
                onGapChange = { spontaneousGapMinutes = it },
                onWindowChange = { start, end ->
                    spontaneousWindowStartMin = start
                    spontaneousWindowEndMin = end
                },
                onSendNow = {
                    // `persona.name` is the identity the service currently knows this persona by,
                    // matching setSkillEnabled's lookup (see its note: after a committed rename
                    // the lookup misses and the call no-ops). Missing is fail-closed here — the
                    // entry point refuses an unknown name instead of sending for someone else.
                    when (val outcome = service.sendSpontaneousMessageNow(persona.name)) {
                        is AssistantService.SpontaneousNowOutcome.Accepted -> Toast.makeText(
                            context,
                            // Honest about silence: the model may legitimately have nothing to
                            // add, and the turn writes nothing in that case.
                            "${persona.name} is writing — a message will appear in their chat if they have something to say.",
                            Toast.LENGTH_LONG
                        ).show()
                        is AssistantService.SpontaneousNowOutcome.Refused -> Toast.makeText(
                            context, outcome.reason, Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }

        var voiceExpanded by remember { mutableStateOf(false) }
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
                    IconButton(onClick = { voiceExpanded = true }) {
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                }
            )
            DropdownMenu(expanded = voiceExpanded, onDismissRequest = { voiceExpanded = false }, modifier = Modifier.fillMaxWidth(0.8f)) {
                DropdownMenuItem(
                    text = { Text("None") },
                    onClick = { voiceMode = VoiceMode.NONE; voiceExpanded = false }
                )
                DropdownMenuItem(
                    text = { 
                        Column {
                            Text("System TTS")
                            Text("Built-in Android engine", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    },
                    onClick = { voiceMode = VoiceMode.SYSTEM_TTS; voiceExpanded = false }
                )
                DropdownMenuItem(
                    text = { 
                        Column {
                            Text("Bundled eSpeak NG")
                            Text("Offline open-source engine", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                        }
                    },
                    onClick = { voiceMode = VoiceMode.BUNDLED_ESPEAK; voiceExpanded = false }
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
                                voiceExpanded = false 
                            }
                        )
                    }
                }
                
                DropdownMenuItem(
                    text = { Text("Gateway (Custom URL)") },
                    onClick = { 
                        voiceMode = VoiceMode.GATEWAY
                        voiceExpanded = false 
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

        if (voiceMode == VoiceMode.GATEWAY) {
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
            OutlinedTextField(
                value = backendUrl,
                onValueChange = { backendUrl = it },
                label = { Text("Gateway URL") },
                modifier = Modifier.fillMaxWidth()
            )
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Allow Gateway Failover", style = MaterialTheme.typography.bodyMedium)
                        Text("If this persona's configured gateway is unreachable, try other configured gateways instead of failing immediately.",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    Switch(checked = allowGatewayFailover, onCheckedChange = { allowGatewayFailover = it })
                }
            }
        }

        SettingsSectionHeader(title = "LLM Parameters", icon = Icons.Default.Tune)
        SettingsSection {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ParameterSlider(label = "Temperature", value = temp, range = 0f..2f, onValueChange = { temp = it }, lowLabel = "More focused / predictable", highLabel = "More creative / random")
                ParameterSlider(label = "Top P", value = topP, range = 0f..1f, onValueChange = { topP = it }, lowLabel = "Narrower word choice", highLabel = "Wider word choice")
                ParameterSlider(label = "Repeat Penalty", value = repeatPenalty, range = 0.5f..2.0f, onValueChange = { repeatPenalty = it }, lowLabel = "Allows repetition", highLabel = "Avoids repetition")
                ParameterSlider(label = "Top K", value = topK.toFloat(), range = 1f..100f, onValueChange = { topK = it.toInt() }, format = { it.toInt().toString() }, lowLabel = "Fewer word options", highLabel = "More word options")
                ParameterSlider(label = "Max Response Length", value = maxTokens.toFloat(), range = 100f..8192f, onValueChange = { maxTokens = it.toInt() }, format = { it.toInt().toString() }, lowLabel = "Fast (slow CPU / low RAM)", highLabel = "Long (needs fast hardware)")
                // Continuous track, but state snaps to power-of-two detents so num_ctx
                // always lands on values Ollama operators expect (2k → 32k).
                ParameterSlider(label = "Context Window Size", value = numCtx.toFloat(), range = 2048f..32768f, onValueChange = { v -> numCtx = CTX_WINDOW_DETENTS.minByOrNull { kotlin.math.abs(it - v) } ?: v.toInt() }, format = { it.toInt().toString() }, lowLabel = "Smaller (less VRAM / faster)", highLabel = "Larger (more VRAM / memory-heavy)")

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

        onDelete?.let {
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

    if (showFullscreenPrompt) {
        var tempPrompt by remember { mutableStateOf(systemPrompt) }
        AlertDialog(
            onDismissRequest = { showFullscreenPrompt = false },
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
                    Text("Edit System Prompt", style = MaterialTheme.typography.titleLarge)

                    OutlinedTextField(
                        value = tempPrompt,
                        onValueChange = { tempPrompt = it },
                        label = { Text("System Prompt") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        maxLines = Int.MAX_VALUE
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showFullscreenPrompt = false }) {
                            Text("Cancel")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                systemPrompt = tempPrompt
                                showFullscreenPrompt = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = themeColor.copy(alpha = 0.8f))
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

// Sane num_ctx detents (Ollama convention): 2k → 32k. See the Context Window slider.
private val CTX_WINDOW_DETENTS = listOf(2048, 4096, 8192, 16384, 32768)

@Composable
fun ParameterSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    format: ((Float) -> String)? = null,
    lowLabel: String? = null,
    highLabel: String? = null
) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(format?.invoke(value) ?: String.format(java.util.Locale.US, "%.2f", value), style = MaterialTheme.typography.labelSmall)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
        // Two-sided low/high-end caption (10sp, 50% onSurface — the app's parameter-caption
        // style). Each side gets half the row (low = start-aligned, high = end-aligned) so
        // the captions stay pinned edge-to-edge instead of colliding when combined text
        // runs long.
        if (lowLabel != null && highLabel != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Caption(lowLabel, modifier = Modifier.weight(1f))
                Caption(highLabel, modifier = Modifier.weight(1f), align = TextAlign.End)
            }
        }
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) =
    Text(
        text = text,
        modifier = modifier,
        textAlign = align,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    )

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
