package com.nikosm.voiceassistant

import android.Manifest
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.nikosm.voiceassistant.ui.theme.VoiceAssistantTheme
import com.nikosm.voiceassistant.ui.theme.isDynamicColorSupported
import kotlinx.coroutines.*
import androidx.compose.foundation.gestures.scrollBy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaSelector(
    personaList: List<Persona>,
    currentPersona: Persona,
    onPersonaSelected: (Persona) -> Unit,
    onDismiss: () -> Unit,
    personaColor: Color,
    // Room entry point (Phase 3): listed below the personas; selecting one switches
    // the main screen into room mode. Defaults keep every existing call site working.
    rooms: List<Room> = emptyList(),
    activeRoomId: String? = null,
    onRoomSelected: ((Room) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
        val scrollState = rememberScrollState()
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(scrollState)) {
            Text("Select Persona", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))
            personaList.forEach { persona ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onPersonaSelected(persona) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProviderLogo(icon = persona.providerIcon, isCloud = persona.isCloud, imagePath = persona.iconImageUri)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(persona.name, style = MaterialTheme.typography.bodyLarge, color = if (persona.name == currentPersona.name) personaColor else MaterialTheme.colorScheme.onSurface)
                }
            }
            if (onRoomSelected != null) {
                RoomPickerRows(
                    rooms = rooms,
                    activeRoomId = activeRoomId,
                    accentColor = personaColor,
                    onRoomSelected = onRoomSelected
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ServerConfig/CloudApiSetting are kotlinx-serializable but not Parcelable, so the
// add/edit dialog states are persisted to instance state as JSON strings.
private val ServerConfigSaver = Saver<ServerConfig?, String>(
    save = { it?.let { config -> Json.encodeToString(ServerConfig.serializer(), config) } ?: "" },
    restore = { if (it.isEmpty()) null else runCatching { Json.decodeFromString(ServerConfig.serializer(), it) }.getOrNull() }
)

private val CloudApiSettingSaver = Saver<CloudApiSetting?, String>(
    save = { it?.let { api -> Json.encodeToString(CloudApiSetting.serializer(), api) } ?: "" },
    restore = { if (it.isEmpty()) null else runCatching { Json.decodeFromString(CloudApiSetting.serializer(), it) }.getOrNull() }
)

/**
 * The top-level Settings categories, in the order they appear on the flat list.
 *
 * Plain data rather than an enum so each entry carries its own icon AND its section caption,
 * which keeps both the grouping and the drill-down target a single registry: adding a category
 * is one line here, and it appears in the right section with no other edit. The section a
 * category belongs to is therefore NEVER written into the composable — it is derived from this
 * order by [settingsCategoryGroups].
 */
internal val SETTINGS_CATEGORIES = listOf(
    SettingsCategory("General", Icons.Default.Settings, SettingsGroup.APP),
    SettingsCategory("Servers", Icons.Default.Dns, SettingsGroup.CONNECTIONS),
    SettingsCategory("Cloud", Icons.Default.Cloud, SettingsGroup.CONNECTIONS),
    SettingsCategory("Personas", Icons.Default.Person, SettingsGroup.ASSISTANTS),
    SettingsCategory("Rooms", Icons.Default.Forum, SettingsGroup.ASSISTANTS),
    // After Personas, not beside them: a skill only means something once the persona that
    // can enable it exists, so the order follows "what to configure" rather than the alphabet.
    SettingsCategory("Skills", Icons.Default.AutoAwesome, SettingsGroup.ASSISTANTS)
)

/**
 * The three section captions. Held here rather than inline in the composable so the registry
 * stays the single place a category (or a whole section) is declared.
 */
internal enum class SettingsGroup(val caption: String) {
    APP("App"),
    CONNECTIONS("Connections"),
    ASSISTANTS("Assistants")
}

/** One entry of the flat top-level Settings list. */
internal data class SettingsCategory(
    val title: String,
    val icon: ImageVector,
    val group: SettingsGroup
)

/** One caption and the categories filed under it, in registry order. */
internal data class SettingsCategoryGroup(
    val caption: String,
    val categories: List<SettingsCategory>
)

/**
 * The categories folded into captioned sections, derived from [SETTINGS_CATEGORIES] order.
 *
 * `groupBy` returns a LinkedHashMap, so sections appear in the order their first member does —
 * reordering the registry reorders the screen, and a new category needs no other edit.
 */
internal fun settingsCategoryGroups(
    categories: List<SettingsCategory> = SETTINGS_CATEGORIES
): List<SettingsCategoryGroup> =
    categories.groupBy { it.group }
        .map { (group, members) -> SettingsCategoryGroup(group.caption, members) }

// ---------------------------------------------------------------------------------
// Row summaries
//
// Pure functions beside the composable (the `roomMemberSummary` / `roomCapSummary`
// pattern), because what each row says must be unit-testable without a device and without
// a composition. They read only what the dialog ALREADY collects — no disk access, so a
// summary can never block or slow the first frame.
// ---------------------------------------------------------------------------------

/**
 * The General screen's own contents, listed as they are today: Appearance, Data & Usage,
 * Location, History, Backup & Recovery. Static on purpose — this category has no counts to
 * report, and inventing any would rot the moment a section is added.
 */
internal const val GENERAL_SETTINGS_SUMMARY = "Appearance, usage, location, history, backup"

/** "$count word", with the label pluralized at n > 1. */
internal fun countedItems(count: Int, singular: String, plural: String): String =
    "$count " + if (count == 1) singular else plural

/**
 * What the Servers row says, e.g. `2 Ollama, 1 gateway, 1 image-gen`.
 *
 * Zero segments are omitted entirely rather than shown as "0 …" — a row reading
 * "2 Ollama, 0 gateway" is noise — and a genuinely empty Servers screen says so instead.
 * "Ollama" and "image-gen" are product/kind names and stay invariant; "gateway" pluralizes.
 */
internal fun serversSummary(ollama: Int, gateway: Int, imageGen: Int): String {
    val segments = buildList {
        if (ollama > 0) add(countedItems(ollama, "Ollama", "Ollama"))
        if (gateway > 0) add(countedItems(gateway, "gateway", "gateways"))
        if (imageGen > 0) add(countedItems(imageGen, "image-gen", "image-gen"))
    }
    return segments.joinToString(", ").ifEmpty { "No servers yet" }
}

/**
 * How many cloud providers are actually usable.
 *
 * "Set up" deliberately means [builtInWithKey] — the BUILT-IN providers are always present in
 * the list (the app seeds `DEFAULT_CLOUD_APIS` on first run whether or not the user has a key),
 * so counting the list itself would report "5 configured" on a device that has never used a
 * cloud model. Every CUSTOM provider counts: adding one is already a deliberate act, and
 * OpenAI-compatible endpoints are legitimately keyless.
 */
internal fun cloudSummary(builtInWithKey: Int, custom: Int): String {
    val total = builtInWithKey + custom
    return if (total == 0) "None set up yet" else countedItems(total, "provider", "providers")
}

internal fun personasSummary(count: Int): String =
    if (count == 0) "No personas yet" else countedItems(count, "persona", "personas")

internal fun roomsSummary(count: Int): String =
    if (count == 0) "No rooms yet" else countedItems(count, "room", "rooms")

internal fun skillsSummary(count: Int): String =
    if (count == 0) "No skills yet" else countedItems(count, "skill", "skills")

/**
 * Every category's summary line, keyed by the category itself.
 *
 * Built once per state change and handed to the list, so the composable only renders and a
 * test can assert the whole set without composing anything. Derived from the registry, so a
 * new category is covered by adding its summary case here — no silent blank row.
 */
internal fun settingsCategorySummaries(
    ollamaServers: Int,
    gatewayServers: Int,
    imageGenServers: Int,
    cloudProvidersWithKey: Int,
    customCloudProviders: Int,
    personas: Int,
    rooms: Int,
    skills: Int
): Map<SettingsCategory, String> = buildMap {
    SETTINGS_CATEGORIES.forEach { category ->
        put(
            category,
            when (category.title) {
                "General" -> GENERAL_SETTINGS_SUMMARY
                "Servers" -> serversSummary(ollamaServers, gatewayServers, imageGenServers)
                "Cloud" -> cloudSummary(cloudProvidersWithKey, customCloudProviders)
                "Personas" -> personasSummary(personas)
                "Rooms" -> roomsSummary(rooms)
                "Skills" -> skillsSummary(skills)
                else -> ""
            }
        )
    }
}

/**
 * The flat top-level Settings list: a captioned "Settings" title, then one card per category
 * showing its title and a live one-line summary.
 *
 * Purely presentational. Tapping a row still navigates to that category's screen, and the
 * list stays inside the dialog's existing AnimatedContent slot — the drill-down, the crossfade
 * and the BackHandler are untouched.
 *
 * @param summaries precomputed per-category lines (see [settingsCategorySummaries]); passed in
 *   rather than collected here so this composable renders only what it is given, which is also
 *   what makes it testable on its own.
 */
@Composable
internal fun SettingsCategoryList(
    accentColor: Color,
    summaries: Map<SettingsCategory, String>,
    onCategorySelected: (SettingsCategory) -> Unit
) {
    LazyColumn(
        // fillMaxSize, not weight(): AnimatedContent already carries the weight(1f) that
        // reserves this area, and its content lambda is not a ColumnScope.
        //
        // The insets: this dialog is a fullscreen, edge-to-edge window, so the list used to
        // start underneath the status bar. statusBarsPadding plus the display cutout's
        // horizontal edges keeps the title clear of both, and degrades to no padding on a
        // window that reports none.
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)
    ) {
        item(key = "settings-title") {
            Text(
                text = "Settings",
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )
        }

        settingsCategoryGroups().forEach { group ->
            item(key = "settings-caption-${group.caption}") {
                Text(
                    text = group.caption,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }
            items(group.categories, key = { it.title }) { category ->
                SettingsCategoryRow(
                    category = category,
                    summary = summaries[category].orEmpty(),
                    accentColor = accentColor,
                    onClick = { onCategorySelected(category) },
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
    }
}

/**
 * One category row: a tonal card holding an accent-tinted icon tile, the category name, a
 * one-line summary and the chevron.
 *
 * Sizing is deliberately NOT fixed. The row carries a 48dp MINIMUM height (the accessibility
 * floor for a touch target, which the 12dp padding plus the 36dp tile already exceeds) and
 * lets the text grow the card at large font scales rather than clipping it — that growth is
 * also what keeps this scrollable list honest on a small screen.
 */
@Composable
private fun SettingsCategoryRow(
    category: SettingsCategory,
    summary: String,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            // One node for the whole row: TalkBack reads "Servers, 2 Ollama, 1 gateway"
            // instead of walking three unlabeled pieces. The icon and chevron are decorative
            // (contentDescription = null below), so nothing extra merges in.
            .semantics(mergeDescendants = true) {
                contentDescription =
                    if (summary.isBlank()) category.title else "${category.title}, $summary"
            }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        // The same container the existing settings cards use (SettingsSection), not a new
        // color: tonal fill, and borderless — the outline is what made this read as a form.
        color = Color.Black.copy(alpha = 0.2f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = accentColor.copy(alpha = 0.16f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = category.icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = category.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (summary.isNotBlank()) {
                    Text(
                        text = summary,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
/**
 * Footer caption size, in sp. Was 12sp, the same as Close.
 *
 * Named because it is deliberately the one Settings text size that is NOT shared with
 * the rest of the chrome: the caption is a passive note rather than something to read,
 * so it sits below the group captions (13sp) instead of matching Close (12sp). It is
 * the only text that may use it — every other Settings text keeps its own size.
 */
internal val SettingsFooterCaptionSize = 10.sp

/**
 * The Settings footer's single shared copy: "Settings are automatically saved." at the
 * far left, Close at the far right, on ONE row.
 *
 * The dialog renders this exactly once, below the crossfading content, so it is pinned
 * under the scrolling category list and under every category screen without any of them
 * having to draw their own footer — there is no second copy to keep in step.
 *
 * Position only: Close keeps its accent colour and medium weight, and no other Settings
 * text size changes.
 *
 * Sizing is deliberately asymmetric. The caption takes the slack (weight 1f) and wraps
 * onto a second line at large font scales rather than being clipped; Close is measured at
 * its natural width with maxLines = 1 and softWrap = false, and carries an explicit 48dp
 * minimum height, so a wrapping caption can never squeeze it, wrap it or shrink it below
 * the accessibility floor for a touch target.
 */
@Composable
private fun SettingsFooter(
    accentColor: Color,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Settings are automatically saved.",
            fontSize = SettingsFooterCaptionSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.defaultMinSize(minHeight = 48.dp)
        ) {
            Text(
                text = "Close",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = accentColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * A category's own full screen: a header (back arrow + category name) over that
 * category's settings.
 *
 * Replaces the accordion entirely. There is no height cap and no internal scrollbox —
 * the LazyColumn here is the only thing that scrolls, exactly like the Personas/Rooms
 * list-then-editor screens already did. PersonaEditor and RoomEditor are plain Columns
 * for the same reason: no nested scroller for them to cap.
 *
 * @param onBack invoked by the header arrow; the caller clears its active-category state
 *   and the flat list reappears exactly as it was left.
 */
@Composable
private fun SettingsCategoryScreen(
    category: SettingsCategory,
    accentColor: Color,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Settings",
                    tint = accentColor
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = category.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp)
        ) {
            item(key = "category-${category.title}") {
                content()
            }
        }
    }
}

/**
 * Which row of the Personas list a shortcut target names.
 *
 * The header icon does not carry an index — it carries a NAME, because that is all the chat
 * header has (it knows `currentPersona`, not the list's ordering, which the user can change
 * with the reordering arrows). Persona names are unique, so the name resolves to exactly one
 * row.
 *
 * Pure and separate from the composable for the same reason as the row-summary helpers: what
 * the shortcut does is unit-testable without a device, without a composition and without a
 * service. Returns null when the persona is gone — the caller then does NOTHING at all rather
 * than opening the list or, worse, landing the editor on whichever persona shifted into that
 * slot.
 */
internal fun personaEditorIndex(personas: List<Persona>, name: String?): Int? {
    if (name.isNullOrBlank()) return null
    val index = personas.indexOfFirst { it.name == name }
    return if (index >= 0) index else null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    service: AssistantService?,
    onDismiss: () -> Unit,
    personaColor: Color,
    /**
     * Name of the persona whose editor should already be open when this dialog appears — the
     * chat header's persona icon. One-shot: consumed via [onOpenPersonaEditorConsumed] and
     * never re-read, so a rotation cannot reopen the editor behind the user's back.
     */
    openPersonaEditorFor: String? = null,
    onOpenPersonaEditorConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    // Drill-down navigation, one level above the Personas/Rooms list-then-editor pattern:
    // which category screen is showing, null = the flat top-level list.
    //
    // Ephemeral by design: a plain `remember` (not `rememberSaveable`, nothing written to
    // SettingsManager) so Settings always opens on the flat list, and no navigation state
    // is persisted across app restarts.
    var activeCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    // "This dialog was opened by the chat header's persona icon", as the persona it targets.
    // `rememberSaveable` because it is the one piece of this dialog's state that a rotation
    // MUST keep: it is what makes leaving return to the chat rather than strand the user on the
    // flat Settings list. The OPEN REQUEST itself stays ephemeral — see [editRequestDone].
    var shortcutPersonaName by rememberSaveable { mutableStateOf<String?>(null) }
    // One-shot latch for the open request. Plain `remember` on purpose, for the same reason the
    // request is: it must not outlive the composition, or a rotation re-opens the editor.
    var editRequestDone by remember { mutableStateOf(false) }
    var pendingEditIndex by remember { mutableStateOf<Int?>(null) }

    var totalCost by remember { mutableDoubleStateOf(0.0) }
    var serverBases by remember { mutableStateOf<List<ServerConfig>>(emptyList()) }
    var ollamaBases by remember { mutableStateOf<List<ServerConfig>>(emptyList()) }
    // Whether the Ollama list has been DELIVERED at least once. An empty list means two very
    // different things — "the collector has not run yet" (the first frame of every composition,
    // and what a `rememberSaveable` models-screen selection sees after a rotation) versus "the
    // list loaded and really is empty" (the last server was deleted) — and only the second may
    // clear the selection. Tracking delivery is what tells them apart.
    var ollamaLoaded by remember { mutableStateOf(false) }
    var imageGenBases by remember { mutableStateOf<List<ServerConfig>>(emptyList()) }
    var cloudApis by remember { mutableStateOf<List<CloudApiSetting>>(emptyList()) }
    // The Cloud row's summary counts custom providers too, and they live in their own flow.
    // Still no disk access: both are StateFlows the service already keeps in memory.
    var customCloudApis by remember { mutableStateOf<List<CloudApiSetting>>(emptyList()) }
    var personas by remember { mutableStateOf<List<Persona>>(emptyList()) }
    var rooms by remember { mutableStateOf<List<Room>>(emptyList()) }
    var skills by remember { mutableStateOf<List<Skill>>(emptyList()) }

    LaunchedEffect(service) {
        if (service != null) {
            launch { service.totalCost.collect { totalCost = it } }
            launch { service.serverBases.collect { serverBases = it } }
            launch { service.ollamaBaseUrls.collect { ollamaBases = it; ollamaLoaded = true } }
            launch { service.imageGenBases.collect { imageGenBases = it } }
            launch { service.cloudApis.collect { cloudApis = it } }
            launch { service.customCloudApis.collect { customCloudApis = it } }
            launch { service.personas.collect { personas = it } }
            launch { service.rooms.collect { rooms = it } }
            launch { service.skills.collect { skills = it } }
        }
    }

    if (service == null) return

    val svc = service

    val personasCategory = remember { SETTINGS_CATEGORIES.first { it.title == "Personas" } }

    // The header-icon shortcut, resolved ONCE.
    //
    // Keyed on the persona list as well as the request, because `personas` starts empty and
    // fills from the service's flow a frame or two later — resolving on the first composition
    // would find nothing and (had the latch not been there) silently do nothing forever.
    // The `editRequestDone` latch is what makes "keyed on personas" safe: the second pass
    // cannot re-resolve.
    //
    // A persona that has been deleted consumes the request and does nothing, exactly like
    // `applySpontaneousDeepLink`'s rule: leaving it pending would ambush the NEXT tap.
    LaunchedEffect(openPersonaEditorFor, personas, editRequestDone) {
        if (editRequestDone) return@LaunchedEffect
        val name = openPersonaEditorFor ?: return@LaunchedEffect
        if (personas.isEmpty()) return@LaunchedEffect
        val index = personaEditorIndex(personas, name)
        editRequestDone = true
        onOpenPersonaEditorConsumed()
        if (index == null) return@LaunchedEffect
        activeCategory = personasCategory
        shortcutPersonaName = name
        pendingEditIndex = index
    }

    // `activeCategory` is deliberately ephemeral (see above), so after a rotation it is a
    // fresh null. A dialog opened by the shortcut restores its category from the SAVED flag
    // instead — that is what keeps the editor on screen across the rotation instead of
    // dropping the user back on the flat list. A dialog opened the normal way has no flag and
    // behaves exactly as it always has.
    val shownCategory = activeCategory ?: shortcutPersonaName?.let { personasCategory }

    // Leaving a shortcut-opened dialog returns to the CHAT: the user came from the chat to
    // edit one persona, so "back" means back, not "here is the whole Settings list". Opened
    // the normal way, back still pops one level and then closes, exactly as today.
    val leaveCategory: () -> Unit = {
        if (shortcutPersonaName != null) onDismiss() else activeCategory = null
    }

    // Every category row's second line, computed from what is already collected above. This is
    // derived state, so it is computed once per change and passed down: the list composable
    // renders it and reads nothing itself.
    val categorySummaries = settingsCategorySummaries(
        ollamaServers = ollamaBases.size,
        gatewayServers = serverBases.size,
        imageGenServers = imageGenBases.size,
        // A built-in provider counts as "set up" only once it carries a key — the built-ins
        // are seeded whether or not the user has one.
        cloudProvidersWithKey = cloudApis.count { it.apiKey.isNotBlank() },
        customCloudProviders = customCloudApis.size,
        personas = personas.size,
        rooms = rooms.size,
        skills = skills.size
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        // Fullscreen settings: fill the whole screen (the previous 90%-height
        // window with 16dp gutters wasted space on dense tabs like Servers).
        // Scoped to the dialog only — the system back gesture/button still
        // dismisses via onDismissRequest.
        modifier = Modifier.fillMaxSize(),
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = androidx.compose.ui.graphics.RectangleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // System back/gesture: leave the category screen before dismissing Settings,
                // matching the header arrow. On the flat list it falls through to the
                // dialog's own onDismissRequest. A shortcut-opened dialog leaves all the way
                // out (see leaveCategory), so back from the editor lands on the chat.
                BackHandler(enabled = shownCategory != null) {
                    leaveCategory()
                }

                // Crossfade between the flat list and the active category screen. Replaces
                // the accordion's expand/shrink: exactly one of the two is ever on screen.
                AnimatedContent(
                    targetState = shownCategory,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(220)) togetherWith
                            fadeOut(animationSpec = tween(150))
                    },
                    modifier = Modifier.weight(1f),
                    label = "settingsCategoryNav"
                ) { category ->
                    if (category == null) {
                        SettingsCategoryList(
                            accentColor = personaColor,
                            summaries = categorySummaries,
                            onCategorySelected = { activeCategory = it }
                        )
                    } else {
                        SettingsCategoryScreen(
                            category = category,
                            accentColor = personaColor,
                            onBack = leaveCategory
                        ) {
                            when (category.title) {
                                "General" -> GeneralSettings(
                                    service = svc,
                                    totalCost = totalCost,
                                    onDismiss = onDismiss
                                )
                                "Servers" -> ServerSettings(
                                    service = svc,
                                    gateways = serverBases,
                                    ollama = ollamaBases,
                                    ollamaLoaded = ollamaLoaded,
                                    imageGen = imageGenBases,
                                    currentThemeColor = personaColor
                                )
                                "Cloud" -> CloudSettings(
                                    service = svc,
                                    apis = cloudApis
                                )
                                "Personas" -> PersonaSettings(
                                    service = svc,
                                    personas = personas,
                                    currentThemeColor = personaColor,
                                    initialEditingIndex = pendingEditIndex,
                                    onInitialEditConsumed = { pendingEditIndex = null }
                                )
                                "Rooms" -> RoomSettings(
                                    service = svc,
                                    rooms = rooms,
                                    personas = personas,
                                    currentThemeColor = personaColor
                                )
                                "Skills" -> SkillSettings(
                                    service = svc,
                                    skills = skills,
                                    // Personas are passed so each row can say who would
                                    // lose it on delete — the cost of editing a shared
                                    // entry is invisible from the edit button otherwise.
                                    personas = personas,
                                    currentThemeColor = personaColor
                                )
                            }
                        }
                    }
                }

                // One footer for the whole dialog, so it is pinned below the scrolling
                // content on the category list AND on every category screen. Same close
                // affordance as before, now a single caption/Close row.
                SettingsFooter(accentColor = personaColor, onDismiss = onDismiss)
            }
        }
    }
}

/**
 * Sub-title for the Adaptive Theme (Material You) switch.
 *
 * Split out of the composable and parameterised on [sdkInt] so the older-Android
 * branch is actually executed by a test: when the platform cannot supply dynamic
 * color the switch is disabled *and* says why, rather than silently no-opping.
 * The copy is surfaced verbatim in the UI, so its two branches are asserted by
 * AdaptiveThemeGateTest.
 */
internal fun adaptiveThemeDescription(sdkInt: Int): String =
    if (isDynamicColorSupported(sdkInt))
        "Material You: derive the app's base colors from your wallpaper (Android 12+). " +
            "Each persona's color stays the accent on top."
    else
        "Unavailable: dynamic color needs Android 12 (API 31) or newer. This device runs " +
            "API $sdkInt, so the standard theme is used instead."

/**
 * "Add to Quick Settings" — the tile is otherwise invisible to users, so offer the
 * system's own placement prompt. [android.app.StatusBarManager.requestAddTileService]
 * arrived in API 33; below that the tile is added by editing the panel by hand, so this
 * row is absent rather than dead.
 *
 * The prompt is best-effort: a device that refuses it (OEM policy, repeated denials)
 * must not crash the settings screen, hence the `runCatching`.
 */
@Composable
private fun QuickSettingsTileRow() {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Quick Settings tile", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add Celeste to your Quick Settings panel for one-tap voice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        TextButton(onClick = {
            val manager = context.getSystemService(StatusBarManager::class.java) ?: return@TextButton
            runCatching {
                manager.requestAddTileService(
                    ComponentName(context, CelesteTileService::class.java),
                    context.getString(R.string.tile_celeste_label),
                    Icon.createWithResource(context, R.drawable.ic_tile_celeste),
                    context.mainExecutor
                ) { /* result ignored: the panel is the source of truth */ }
            }
        }) {
            Text("Add")
        }
    }
}

/**
 * General category screen: appearance, chat font, cost, location, history, backup.
 *
 * Body of [SettingsCategoryScreen] — a whole category's content, not an embeddable
 * block. There is no wrapper surface and no expand state here: the screen header and the
 * page scroll are provided by the category screen, and this is just the form.
 */
@Composable
fun GeneralSettings(
    service: AssistantService,
    totalCost: Double,
    onDismiss: () -> Unit
) {

    // Celestial UI toggle state (collected from the service so the switch
    // reflects the live value and flips reactively when changed).
    var celestialUi by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        service.celestialUi.collect { celestialUi = it }
    }

    // Adaptive Theme (Material You) toggle state — same service-flow pattern as
    // Celestial UI above.
    var adaptiveTheme by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        service.adaptiveTheme.collect { adaptiveTheme = it }
    }

    var chatFontSize by remember { mutableStateOf(14f) }
    LaunchedEffect(Unit) {
        service.chatFontSize.collect { chatFontSize = it }
    }

    // Fix #7: optional password encryption for exported backups. The password is
    // collected HERE (checkbox + fields + validation below), but the actual
    // encryption/decryption runs service-side on serviceScope — see
    // exportBackupToFile/importBackupFromFile — so it stays dialog-closure-safe.
    var encryptExport by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var exportConfirmPassword by remember { mutableStateOf("") }
    var exportPasswordError by remember { mutableStateOf<String?>(null) }
    var pendingExportPassword by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        // Runs on serviceScope (inside the service) so closing the Settings dialog can
        // no longer cancel the export mid-write and leave a truncated file.
        uri?.let { service.exportBackupToFile(it, pendingExportPassword) }
        pendingExportPassword = null
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // Runs on serviceScope (inside the service) so closing the Settings dialog can
        // no longer cancel a half-applied import. Encrypted backups pause that coroutine
        // on a password prompt (backupPasswordRequested below). A single "*/*" type is
        // used deliberately: some OEM pickers (MIUI) fail to match real files when a
        // multi-type list is passed, hiding existing backups — the service already
        // validates the format and reports "Invalid format" for wrong files.
        uri?.let { service.importBackupFromFile(it) }
    }

    var showImportPasswordDialog by remember { mutableStateOf(false) }
    var importPassword by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        service.backupPasswordRequested.collect { requested ->
            showImportPasswordDialog = requested
            if (requested) importPassword = ""
        }
    }

Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    SettingsSectionHeader(title = "Appearance", icon = Icons.Default.Palette)
    SettingsSection {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Celestial UI (beta)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Starfield + planet voice screen with HUD transcript. " +
                        "Text mode keeps the classic layout. Toggle back anytime.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Switch(
                checked = celestialUi,
                onCheckedChange = { service.setCelestialUi(it) }
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = Color.White.copy(alpha = 0.05f)
        )
        // Adaptive Theme (Material You): base scheme derived from the wallpaper.
        // The persona's own themeColor is deliberately NOT routed through the
        // scheme — the voice screen/HUD paint `personaColor` directly — so the
        // per-persona accent stays distinct on top of the dynamic base.
        val dynamicSupported = isDynamicColorSupported(Build.VERSION.SDK_INT)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Adaptive Theme", style = MaterialTheme.typography.titleMedium)
                Text(
                    adaptiveThemeDescription(Build.VERSION.SDK_INT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Switch(
                checked = adaptiveTheme,
                onCheckedChange = { service.setAdaptiveTheme(it) },
                // Disabled (with the reason above) rather than a silent no-op
                // when the platform can't provide dynamic color.
                enabled = dynamicSupported
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = Color.White.copy(alpha = 0.05f)
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Chat Font Size", style = MaterialTheme.typography.titleMedium)
                Text("${chatFontSize.toInt()} sp", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = chatFontSize,
                onValueChange = { service.setChatFontSize(it) },
                valueRange = 12f..24f,
                steps = 11, // 12, 13, ..., 24 -> 13 values, 12 gaps -> 11 steps
                modifier = Modifier.fillMaxWidth()
            )
            // Live preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(
                    "Sample message at ${chatFontSize.toInt()}sp size.",
                    fontSize = chatFontSize.sp,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }

    // Tile discoverability. The API (requestAddTileService) arrived in API 33; below that the
    // tile is added by editing the Quick Settings panel, so the row is hidden rather than dead.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        SettingsSectionHeader(title = "Shortcuts", icon = Icons.Default.Add)
        SettingsSection { QuickSettingsTileRow() }
    }

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
    SettingsSectionHeader(title = "History", icon = Icons.Default.History)
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
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

    SettingsSectionHeader(title = "Backup & Recovery", icon = Icons.Default.CloudSync)
    SettingsSection {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = encryptExport,
                    onCheckedChange = {
                        encryptExport = it
                        exportPasswordError = null
                    }
                )
                Text("Encrypt backup with password")
            }
            if (encryptExport) {
                OutlinedTextField(
                    value = exportPassword,
                    onValueChange = {
                        exportPassword = it
                        exportPasswordError = null
                    },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = exportConfirmPassword,
                    onValueChange = {
                        exportConfirmPassword = it
                        exportPasswordError = null
                    },
                    label = { Text("Confirm password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Minimum 8 characters. Encrypts API keys, server credentials and chats (AES-256-GCM, PBKDF2 key derivation).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                exportPasswordError?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(
                onClick = {
                    if (encryptExport) {
                        when {
                            exportPassword.length < 8 ->
                                exportPasswordError = "Password must be at least 8 characters."
                            exportPassword != exportConfirmPassword ->
                                exportPasswordError = "Passwords do not match."
                            else -> {
                                exportPasswordError = null
                                pendingExportPassword = exportPassword
                                exportLauncher.launch("voice_assistant_backup_${System.currentTimeMillis()}.json")
                            }
                        }
                    } else {
                        exportPasswordError = null
                        pendingExportPassword = null
                        exportLauncher.launch("voice_assistant_backup_${System.currentTimeMillis()}.json")
                    }
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
                    importLauncher.launch(arrayOf("*/*"))
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

    // Fix #7: prompt for the password of an encrypted backup. Shown when the
    // service-side import detects the encrypted envelope; the answer resumes the
    // import coroutine on serviceScope (dialog-closure-safe).
    if (showImportPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                showImportPasswordDialog = false
                service.cancelBackupPasswordPrompt()
                importPassword = ""
            },
            title = { Text("Encrypted Backup") },
            text = {
                Column {
                    Text("This backup is encrypted. Enter the password it was exported with.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it },
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showImportPasswordDialog = false
                    service.submitBackupPassword(importPassword)
                    importPassword = ""
                }) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportPasswordDialog = false
                    service.cancelBackupPasswordPrompt()
                    importPassword = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * The models-screen selection after the Ollama list has (or has not) loaded.
 *
 * `modelsServerUrl` is `rememberSaveable`, so it survives an Activity recreation, while the
 * collected list it is validated against starts EMPTY on the first frame of every composition.
 * An empty list therefore means two very different things, and [loaded] is what tells them
 * apart:
 *
 *  - `loaded == false` — the collector has not delivered yet: KEEP the selection (clearing here
 *    would strand a restored selection before the list had any chance to arrive).
 *  - `loaded == true` — the list really is the server list: KEEP the URL if it is present, and
 *    CLEAR it if it is absent — including when the list is now EMPTY because the LAST server was
 *    deleted.
 *
 * Pure, so the rotation case (not loaded, restored URL), the delete case (loaded, URL gone) and
 * the delete-the-last-server case (loaded, empty) are all unit-tested without a device.
 */
internal fun modelsServerUrlAfterListLoad(
    selectedUrl: String?,
    servers: List<ServerConfig>,
    loaded: Boolean
): String? {
    if (selectedUrl == null) return null
    if (!loaded) return selectedUrl
    return if (servers.any { it.url == selectedUrl }) selectedUrl else null
}

/**
 * Servers category screen: local gateways, Ollama, image-gen endpoints, external
 * services and the knowledge base. Body of [SettingsCategoryScreen]; every list keeps
 * its add/edit/reorder/delete/test-connection behaviour unchanged.
 */
@Composable
fun ServerSettings(
    service: AssistantService,
    gateways: List<ServerConfig>,
    ollama: List<ServerConfig>,
    /** Whether [ollama] has been delivered at least once — see `modelsServerUrlAfterListLoad`. */
    ollamaLoaded: Boolean,
    imageGen: List<ServerConfig>,
    currentThemeColor: Color
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var serverStatus by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // List-to-detail for the models screen, keyed on server URL — the same identity the
    // catalog cache uses, and the one field ServerConfig guarantees unique. Keyed by NAME
    // would break on a rename, and by nothing at all would lose the selection.
    var modelsServerUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var favoriteModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var catalog by remember { mutableStateOf<Map<String, ModelCatalogState>>(emptyMap()) }
    LaunchedEffect(service) {
        launch { service.favoriteModels.collect { favoriteModels = it } }
        launch { service.modelCatalog.collect { catalog = it } }
    }
    // Setup-context connection failures (Test Connection button, save flow, dot re-check)
    // are surfaced through this host — visible and specific, unlike the passive status dot.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(service) {
        service.serverStatus.collect { serverStatus = it }
    }
    Box(modifier = Modifier.fillMaxWidth()) {
Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    // Detail view for one server's models. Plain Column, no nested scroller: the category
    // page's LazyColumn remains the single scroller, exactly as RoomEditor/SkillEditor do.
    val modelsServer = modelsServerUrl?.let { url -> ollama.firstOrNull { it.url == url } }
    // A server deleted (or re-pointed) elsewhere must not strand the selection. This MUST live
    // OUTSIDE the `modelsServer != null` branch below — inside it the `modelsServer == null`
    // test can never be true, which made the guard dead code (kotlinc: "Condition is always
    // 'false'"). The decision is the pure `modelsServerUrlAfterListLoad`, which uses `ollamaLoaded`
    // to tell "the collector has not delivered yet" (keep the selection) from "the list loaded
    // and no longer has this URL" (clear it — including when the LAST server was deleted).
    LaunchedEffect(ollama, modelsServerUrl, ollamaLoaded) {
        val settled = modelsServerUrlAfterListLoad(modelsServerUrl, ollama, ollamaLoaded)
        if (settled != modelsServerUrl) modelsServerUrl = settled
    }
    if (modelsServer != null) {
        // KNOWN ISSUE (found by screenshot review on a real device, deliberately NOT fixed):
        // Servers -> Models draws TWO back arrows, stacked vertically in the header. One is
        // SettingsCategoryScreen's own ("Back to Settings", SettingsScreens.kt:525); the other
        // is ModelsHeader's inside ModelsScreen ("Back to servers", ModelScreens.kt:193). Both
        // are composed at once because this screen is used as the CONTENT of the Servers
        // category page, so the category header never goes away underneath the detail header.
        // Screenshot: /tmp/shots/30-models-doubled-arrow.png.
        //
        // Not fixed here because either fix changes navigation the picker work did not touch:
        // drop ModelsHeader's arrow and let the category arrow do both jobs (it already does),
        // or stop hosting the detail inside the category page. Both are a deliberate call for
        // whoever owns settings navigation; until then it is cosmetic only -- both arrows go
        // back to the same server list, and system back is bound to the same onBack.
        ModelsScreen(
            server = modelsServer,
            state = catalog[modelsServer.url] ?: ModelCatalogState.NotLoaded,
            healthStatus = serverStatus[modelsServer.url],
            favoriteModels = favoriteModels,
            onRefresh = { service.fetchModelCatalog(modelsServer, force = true) },
            onToggleFavorite = { service.toggleFavoriteModel(it) },
            accentColor = currentThemeColor,
            onBack = { modelsServerUrl = null }
        )
        return@Column
    }
    SettingsSectionHeader(title = "Connections", icon = Icons.Default.Dns)
    ServerListSection(
        title = "Local Gateways",
        servers = gateways,
        status = serverStatus,
        onAdd = { name: String, url: String, user: String?, pass: String?, authType: AuthType, apiKey: String? -> service.addServerBase(name, url, user, pass, authType, apiKey) },
        onRemove = { service.removeServerBase(it) },
        onEdit = { old: ServerConfig, name: String, url: String, user: String?, pass: String?, authType: AuthType, apiKey: String? -> service.updateServerBase(old, name, url, user, pass, authType, apiKey) },
        currentThemeColor = currentThemeColor,
        onRefreshHealth = { service.forceCheckHealth(it, true) },
        onRefreshModels = { /* Gateways don't have models */ },
        onMoveUp = { service.moveServerUp(it, false) },
        onMoveDown = { service.moveServerDown(it, false) },
        isGateway = true,
        service = service,
        snackbarHostState = snackbarHostState
    )
    ServerListSection(
        title = "Ollama Servers",
        servers = ollama,
        status = serverStatus,
        onAdd = { name: String, url: String, user: String?, pass: String?, authType: AuthType, apiKey: String? -> service.addOllamaBase(name, url, user, pass, authType, apiKey) },
        onRemove = { service.removeOllamaBase(it) },
        onEdit = { old: ServerConfig, name: String, url: String, user: String?, pass: String?, authType: AuthType, apiKey: String? -> service.updateOllamaBase(old, name, url, user, pass, authType, apiKey) },
        currentThemeColor = currentThemeColor,
        onRefreshHealth = { service.forceCheckHealth(it, false) },
        onRefreshModels = { service.fetchModels(it) },
        onMoveUp = { service.moveServerUp(it, true) },
        onMoveDown = { service.moveServerDown(it, true) },
        isGateway = false,
        service = service,
        snackbarHostState = snackbarHostState,
        onOpenModels = { modelsServerUrl = it.url }
    )
    ServerListSection(
        title = "Image Gen Servers",
        servers = imageGen,
        status = serverStatus,
        onAdd = { name: String, url: String, user: String?, pass: String?, authType: AuthType, apiKey: String? -> service.addImageGenBase(name, url, user, pass, authType, apiKey) },
        onRemove = { service.removeImageGenBase(it) },
        onEdit = { old: ServerConfig, name: String, url: String, user: String?, pass: String?, authType: AuthType, apiKey: String? -> service.updateImageGenBase(old, name, url, user, pass, authType, apiKey) },
        currentThemeColor = currentThemeColor,
        onRefreshHealth = { service.forceCheckHealth(it, false, true) },
        onRefreshModels = { },
        onMoveUp = { service.moveImageGenUp(it) },
        onMoveDown = { service.moveImageGenDown(it) },
        isGateway = false,
        isImageGen = true,
        service = service,
        snackbarHostState = snackbarHostState
    )
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
    SettingsSectionHeader(title = "Knowledge Base", icon = Icons.Default.Book)
    SettingsSection {
        var ragUrl by remember { mutableStateOf("") }
        var ragUser by remember { mutableStateOf("") }
        var ragPass by remember { mutableStateOf("") }
        var docCount by remember { mutableStateOf<Int?>(null) }
        // Upload progress/outcome is hoisted to the service (StateFlow) so it
        // survives dialog closure: the upload runs on serviceScope and the
        // dialog just collects the status for display. The count display is
        // still dialog-local (transient) — reset when an upload succeeds.
        val uploadStatus by service.knowledgeUploadStatus.collectAsState()
        LaunchedEffect(uploadStatus) {
            if (uploadStatus.startsWith("Uploaded")) docCount = null
        }

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
                            is RagResult.Failure -> service._knowledgeUploadStatus.value = "Error: ${result.exception.message}"
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
            // Upload runs on serviceScope (inside the service) so closing the
            // Settings dialog can no longer cancel it and silently discard the
            // outcome; progress/result is published via knowledgeUploadStatus.
            service.uploadKnowledgeDocumentsToRag(uris, ragUrl)
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
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
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
    isGateway: Boolean,
    isImageGen: Boolean = false,
    service: AssistantService,
    snackbarHostState: SnackbarHostState,
    /** The persona's accent colour, so the Models entry matches every other row here. */
    currentThemeColor: Color,
    /**
     * Opens the models screen for this server. Null for gateway and image-gen lists, which
     * have no Ollama catalog — those sections simply render no entry.
     */
    onOpenModels: ((ServerConfig) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editingServer by rememberSaveable(stateSaver = ServerConfigSaver) { mutableStateOf<ServerConfig?>(null) }

    // Outcome of the in-dialog "Test Connection" probe (result + the auth type actually
    // used, captured at test time so later field edits can't silently change the message).
    // Keyed to the dialog's visibility so it resets on open/close/server switch.
    var testOutcome by remember(showAddDialog, editingServer) { mutableStateOf<Pair<ServerConnectionResult, AuthType>?>(null) }
    var testInProgress by remember(showAddDialog, editingServer) { mutableStateOf(false) }

    var newName by rememberSaveable { mutableStateOf("") }
    var newUrl by rememberSaveable { mutableStateOf("") }
    var newUser by rememberSaveable { mutableStateOf("") }
    var newPass by rememberSaveable { mutableStateOf("") }
    var newAuthType by rememberSaveable { mutableStateOf(AuthType.NONE) }
    var newApiKey by rememberSaveable { mutableStateOf("") }

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
                    val serverStatus = status[server.url]
                    val isOnline = serverStatus == null || !serverStatus.lowercase().contains("failed")
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isOnline) Color.Green else Color.Red)
                            .clickable {
                                onRefreshHealth(server)
                                // The dot alone is easy to miss — also surface a specific,
                                // actionable failure message for this manual re-check.
                                scope.launch {
                                    val result = service.testServerConnection(server, isGateway, isImageGen)
                                    if (!result.success) {
                                        snackbarHostState.showSnackbar(
                                            message = "${server.name}: ${serverConnectionFailureMessage(result, server.effectiveAuthType)}",
                                            withDismissAction = true,
                                            duration = SnackbarDuration.Long
                                        )
                                    }
                                }
                            }
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
                // The per-server Models entry, under its own server row. Only where a
                // callback was supplied (the Ollama list): gateways and image-gen servers
                // have no Ollama catalog, so no entry is rendered for them at all.
                onOpenModels?.let { open ->
                    Spacer(Modifier.height(4.dp))
                    ModelsEntryRow(server = server, accentColor = currentThemeColor, onClick = { open(server) })
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

                        Spacer(Modifier.height(12.dp))
                        // Setup-context connection probe against the fields as currently
                        // entered (nothing is saved). Result shows inline — a snackbar would
                        // render behind this dialog's scrim, so inline is the visible channel
                        // while the dialog is open.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    val candidate = ServerConfig(
                                        name = newName.ifBlank { "Server" },
                                        url = newUrl,
                                        username = newUser.ifBlank { null },
                                        password = newPass.ifBlank { null },
                                        authType = newAuthType,
                                        apiKey = newApiKey.ifBlank { null }
                                    )
                                    testOutcome = null
                                    testInProgress = true
                                    scope.launch {
                                        val result = service.testServerConnection(candidate, isGateway, isImageGen)
                                        testOutcome = result to candidate.effectiveAuthType
                                        testInProgress = false
                                    }
                                },
                                enabled = newUrl.isNotBlank() && !testInProgress
                            ) {
                                if (testInProgress) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Icon(Icons.Default.Wifi, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Test Connection")
                            }
                        }
                        testOutcome?.let { (result, authUsed) ->
                            Text(
                                text = if (result.success) "Connection successful" else serverConnectionFailureMessage(result, authUsed),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newUrl.isNotBlank()) {
                            // Capture before the field resets below — the connection check
                            // runs after save and reports failures as a snackbar (this
                            // dialog is gone by then, so inline text wouldn't be visible).
                            val savedConfig = ServerConfig(
                                name = newName.ifBlank { "Server" },
                                url = newUrl,
                                username = newUser.ifBlank { null },
                                password = newPass.ifBlank { null },
                                authType = newAuthType,
                                apiKey = newApiKey.ifBlank { null }
                            )
                            if (isEditing) {
                                onEdit(editingServer!!, newName.ifBlank { "Server" }, newUrl, newUser.ifBlank { null }, newPass.ifBlank { null }, newAuthType, newApiKey.ifBlank { null })
                            } else {
                                onAdd(newName.ifBlank { "Server" }, newUrl, newUser.ifBlank { null }, newPass.ifBlank { null }, newAuthType, newApiKey.ifBlank { null })
                            }
                            showAddDialog = false
                            editingServer = null
                            newName = ""; newUrl = ""; newUser = ""; newPass = ""; newAuthType = AuthType.NONE; newApiKey = ""
                            scope.launch {
                                val result = service.testServerConnection(savedConfig, isGateway)
                                if (!result.success) {
                                    snackbarHostState.showSnackbar(
                                        message = "${savedConfig.name}: ${serverConnectionFailureMessage(result, savedConfig.effectiveAuthType)}",
                                        withDismissAction = true,
                                        duration = SnackbarDuration.Long
                                    )
                                }
                            }
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

/**
 * Cloud category screen: dynamic pricing, per-provider API keys and the custom-provider
 * list. Body of [SettingsCategoryScreen]; add/edit/reorder/delete/refresh unchanged.
 */
@Composable
fun CloudSettings(
    service: AssistantService,
    apis: List<CloudApiSetting>
) {
    var customApis by remember { mutableStateOf<List<CloudApiSetting>>(emptyList()) }
    var lastSync by remember { mutableLongStateOf(0L) }

    LaunchedEffect(service) {
        launch { service.customCloudApis.collect { customApis = it } }
        launch { service.lastPriceSyncTimestamp.collect { lastSync = it } }
    }

Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    SettingsSectionHeader(title = "Cloud Providers", icon = Icons.Default.Cloud)

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

    apis.forEachIndexed { index, api ->
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

// Maps a setup-context connection-test outcome to specific, actionable wording —
// deliberately distinct from the generic strings used elsewhere. Setup context only:
// chat errors keep their existing error-bubble display.
// internal so the JVM unit test (ServerConnectionMessageTest) can pin the wording.
internal fun serverConnectionFailureMessage(result: ServerConnectionResult, authType: AuthType): String {
    return when {
        result.httpCode == 401 || result.httpCode == 403 ->
            if (authType == AuthType.API_KEY) "Authentication failed — check your API key"
            else "Authentication failed — check your username/password"
        result.httpCode == 404 ->
            "Server responded, but this address doesn't seem right — check the URL"
        result.httpCode != null ->
            buildString {
                append("Unexpected server response: HTTP ${result.httpCode}")
                result.detail?.let { append(" — $it") }
            }
        else ->
            buildString {
                append("Couldn't reach the server — check the address and that it's running")
                result.detail?.let { append(" ($it)") }
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
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var editingApi by rememberSaveable(stateSaver = CloudApiSettingSaver) { mutableStateOf<CloudApiSetting?>(null) }

    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }

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
