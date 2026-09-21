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
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.roundToInt

// Picked attachments must survive Activity recreation (rotation); Uri is Parcelable
// but a List<Uri> isn't directly Bundle-storable, so save the elements individually.
private val AttachedFilesSaver = listSaver<List<Uri>, Uri>(
    save = { it.toList() },
    restore = { it }
)

/** Non-observing holder for layout coordinates (auto-teleprompter anchors). */
private class LayoutRefHolder {
    var container: LayoutCoordinates? = null
    var bubble: LayoutCoordinates? = null
    var viewport: LayoutCoordinates? = null
    /**
     * Identity of the message [bubble] was measured for, using the same
     * "{persona}_{messageCount}_{textHash}" pattern the reveal effect uses for the
     * last assistant message. The holder is never invalidated when the message list
     * changes (persona switch, clear-chat), so the teleprompter re-checks this
     * against the CURRENT last assistant message before trusting [bubble]/[container]:
     * measuring a detached pair can throw from layout math, not just scroll wrong.
     */
    var bubbleMessageId: String? = null
}

// Fix #3: how long a freshly-armed replay reveal waits for the engine to publish the
// replayed audio's real duration before falling back to the heuristic tick. Bundled
// eSpeak publishes it as soon as the AudioTrack is up (a few hundred ms after the
// reveal is armed); System TTS never publishes one, so its replay reveals on the
// fallback. Bounded so a failed/absent duration can never leave the text hidden.
private const val ReplayRevealDurationWaitMs = 1500L

// Update banner: slim, non-intrusive row shown at the top of the
// main chat screen while a newer release is available. "View" opens the release page
// in the browser; the X dismisses it for that specific version (remembered by the
// service until an even newer release is published).
@Composable
private fun UpdateBanner(info: UpdateInfo, onView: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Version ${info.version} is available",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onView,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text("View", style = MaterialTheme.typography.labelLarge)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss update banner",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Stage-2 chunked-TTS sync engine (karaoke): text is ALWAYS fully visible;
 * the sync computation only positions the highlight on the word currently
 * being spoken. Replaces the old reveal/hide machinery (space-substitution +
 * whole-message null fallbacks), whose fresh-per-update visibility
 * recomputation let previously-revealed words become hidden again — the
 * reported collapse and mid-response flicker.
 *
 * Clock unification (the old drift bug): the player's fraction is progress
 * against the REFINED total-duration estimate (totalEstimatedMs), and elapsed
 * audio time is E = fraction × totalMs in the same absolute WAV-clock seconds
 * the word timestamps use. Words covered by real timestamps compare directly
 * against E (authoritative — no mp.duration vs timestamps.last().end mismatch
 * left to accumulate); the not-yet-synthesized tail is estimated
 * char-proportionally between the last covered word's end and totalMs. With
 * no timestamps yet the whole text degrades to pure estimation.
 *
 * Alignment is a greedy match between the raw display words and the spoken
 * timestamps: tokens that vanish under markdown/emoji cleaning (asterisks,
 * arrows, emoji) consume no timestamp, while "inserted" punctuation tokens
 * the SERVER adds during cleaning (e.g. the period injected per paragraph
 * break) are skipped because they have no visual counterpart in the raw text.
 * Any irreconcilable mismatch degrades the entire computation to estimation.
 */
internal data class WordTimestamp(val word: String, val start: Double, val end: Double)

private fun parseWordTimestamps(tsJson: String?): List<WordTimestamp>? {
    if (tsJson == null || tsJson.isBlank()) return null
    return try {
        val arr = org.json.JSONArray(tsJson)
        if (arr.length() == 0) return null
        val out = ArrayList<WordTimestamp>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val s = o.optDouble("start", -1.0)
            val e = o.optDouble("end", -1.0)
            if (s < 0.0 || e < s) return null
            out.add(WordTimestamp(o.optString("word", ""), s, e))
        }
        out
    } catch (_: Exception) {
        null
    }
}

// "Inserted" punctuation: pure-punctuation tokens (e.g. the "." the server
// adds per paragraph break). Also treats the final terminal "." as one.
private fun isPurePunctToken(w: String): Boolean {
    val t = w.trim()
    return t.isNotEmpty() && t.all { !it.isLetterOrDigit() }
}

// Normalized form for comparing a raw token to a spoken timestamp token:
// trim + lowercase, ignoring trailing sentence punctuation.
private fun normToken(w: String): String =
    w.trim().lowercase().trimEnd('.', ',', '!', '?', ';', ':', '…', '”', '’')

/**
 * Returns the char range of the word currently being spoken in [text], or
 * null when nothing is actively spoken (no chunked playback, between
 * sequences, or after the sequence completes — the player forces fraction
 * to 1f at completion).
 */
internal fun ttsActiveWordRange(
    text: String,
    tsJson: String?,
    fraction: Float,
    totalMs: Int
): IntRange? {
    if (fraction >= 1f) return null               // sequence finished
    val totalS = totalMs / 1000.0
    if (totalS <= 0.0) return null
    val elapsed = fraction.coerceIn(0f, 1f).toDouble() * totalS

    val rawSpans = Regex("\\S+").findAll(text).map { it.range.first to (it.range.last + 1) }.toList()
    val speech = ArrayList<Pair<IntRange, String>>()   // span + cleaned word
    for ((wStart, wEnd) in rawSpans) {
        val cleaned = cleanTextForTts(text.substring(wStart, wEnd)).trim()
        if (cleaned.isNotEmpty()) speech.add(IntRange(wStart, wEnd - 1) to cleaned)
    }
    if (speech.isEmpty()) return null

    // Align speech words against the real timestamps. Partial timestamp lists
    // (mid-synthesis) cover a PREFIX of the spoken words: everything aligned
    // is authoritative; the remainder is estimated. An irreconcilable mismatch
    // degrades the whole computation to pure estimation.
    var timestamps = parseWordTimestamps(tsJson)
    val covered = ArrayList<Pair<IntRange, Double>>()  // span → real spoken start (s)
    var lastCoveredEnd = 0.0
    if (timestamps != null) {
        var ti = 0
        var failed = false
        for ((span, cleaned) in speech) {
            if (ti >= timestamps.size) break           // covered portion exhausted
            while (ti + 1 < timestamps.size &&
                   isPurePunctToken(timestamps[ti].word) &&
                   normToken(cleaned) != normToken(timestamps[ti].word)) {
                ti++
            }
            if (isPurePunctToken(timestamps[ti].word) &&
                normToken(cleaned) != normToken(timestamps[ti].word)) {
                failed = true
                break
            }
            covered.add(span to timestamps[ti].start)
            lastCoveredEnd = timestamps[ti].end
            ti++
        }
        if (failed) {
            timestamps = null
            covered.clear()
            lastCoveredEnd = 0.0
        }
    }

    // Tail estimation: char-proportional interpolation in
    // [lastCoveredEnd, totalS] over the not-yet-covered speech words.
    val tail = speech.drop(covered.size)
    val tailTotalWeight = tail.sumOf { (it.first.last - it.first.first + 1).toDouble() }.coerceAtLeast(1.0)

    var tailConsumed = 0.0
    var active: IntRange? = null
    var ci = 0
    for ((span, _) in speech) {
        val pos = if (ci < covered.size) {
            covered[ci].second                        // real, authoritative
        } else {
            val weight = (span.last - span.first + 1).toDouble()
            val estimated = lastCoveredEnd + (totalS - lastCoveredEnd) * (tailConsumed / tailTotalWeight)
            tailConsumed += weight
            estimated
        }
        ci++
        // Positions are non-decreasing in speech order, so the first future
        // word ends the walk; the active word is the last one started.
        if (pos <= elapsed) active = span else break
    }
    return active
}

/**
 * Applies the karaoke highlight to [text] for the currently-spoken word.
 * With no active range the text renders as a plain AnnotatedString — visually
 * identical to the old plain-Text path (nothing is ever hidden).
 *
 * The highlight is COLOR-ONLY (persona color text + a faint persona-tinted
 * background). No font-weight change: bold glyphs are wider in proportional
 * fonts, so a weight flip would rewrap the line and shift neighboring words
 * every time the highlight moved — the spec requires that nothing about the
 * text's layout changes during playback, only the spoken word's color/style.
 */
internal fun highlightedAnnotated(text: String, range: IntRange?, highlightColor: Color?): AnnotatedString {
    if (range == null || highlightColor == null || text.isEmpty()) return AnnotatedString(text)
    val start = range.first.coerceIn(0, text.length)
    val end = (range.last + 1).coerceIn(start, text.length)
    if (end <= start) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        addStyle(
            SpanStyle(
                color = highlightColor,
                background = highlightColor.copy(alpha = 0.16f)
            ),
            start, end
        )
    }
}

@Composable
fun MainScreen(service: AssistantService?) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    
    // Dialog/panel visibility and in-progress input use rememberSaveable so they survive
    // Activity recreation (e.g. device rotation), which discards plain remember state.
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showPersonaPicker by rememberSaveable { mutableStateOf(false) }
    var textInput by rememberSaveable { mutableStateOf("") }
    var textModeOpen by rememberSaveable { mutableStateOf(false) }
    var revealedChars by remember { mutableIntStateOf(Int.MAX_VALUE) }
    var lastAnimatedMessageId by remember { mutableStateOf("") }
    // Fix #3 (device-voice replay): consumed-token bookkeeping for the one-shot
    // "re-run the reveal" request. `restartIndex` + `restartCount` pin the forced
    // reveal to the exact message the service re-synthesized — both must still
    // describe the current list, so a reshaped list (deletion, persona switch,
    // clear-chat) can never re-point the forced reveal at a different message —
    // and `restartStarted` records that the timer already owns this reveal, so a
    // later duration refinement re-runs the effect without zeroing the text again.
    var consumedRevealToken by remember { mutableStateOf(0L) }
    var revealRestartIndex by remember { mutableStateOf(-1) }
    var revealRestartCount by remember { mutableStateOf(-1) }
    var revealRestartStarted by remember { mutableStateOf(false) }
    var attachedFiles by rememberSaveable(stateSaver = AttachedFilesSaver) { mutableStateOf<List<Uri>>(emptyList()) }
    var attachedImage by rememberSaveable { mutableStateOf<Uri?>(null) }
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
    // Celestial UI toggle (Settings > General > Appearance) — seeded from the
    // service's already-loaded value (the same synchronous-read pattern as
    // isFirstRun above) so a fresh install paints the HUD on its first frame
    // instead of flashing the classic layout, then kept live from the service
    // so flipping it in settings swaps the voice screen reactively.
    var celestialUi by remember(service) { mutableStateOf(service?.celestialUi?.value ?: false) }
    var streamingText by remember { mutableStateOf<String?>(null) }
    var micAmplitude by remember { mutableStateOf(0f) }
    // Fix #3: latest reveal-restart request from the service (non-chunked replay).
    // The effect below keys on it, so a request re-runs the reveal logic immediately.
    var revealRestartRequest by remember { mutableStateOf<RevealRestartRequest?>(null) }
    var ttsPlaybackFraction by remember { mutableStateOf<Float?>(null) }
    var ttsWordTimestamps by remember { mutableStateOf<String?>(null) }
    var pendingCert by remember { mutableStateOf<CertApprovalRequest?>(null) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

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
            launch { service.celestialUi.collect { celestialUi = it } }
            launch { service.streamingText.collect { streamingText = it } }
            launch { service.micAmplitude.collect { micAmplitude = it } }
            launch { service.ttsPlaybackFraction.collect { ttsPlaybackFraction = it } }
            launch { service.ttsWordTimestamps.collect { ttsWordTimestamps = it } }
            launch { service.revealRestartRequest.collect { revealRestartRequest = it } }
            launch { service.pendingCertApproval.collect { pendingCert = it } }
            launch { service.updateAvailable.collect { updateInfo = it } }
        }
    }

    // Seeded from the service's actual selection the moment the binder arrives
    // (keyed on service: re-seeds synchronously on (re)bind). The service outlives
    // the Activity, so after rotation its current persona IS the user's active one
    // — a plain remember of DEFAULT_PERSONAS[0] used to reset the selection (and,
    // via the old unconditional switchPersona below, the service's too) on every
    // recreation.
    var currentPersona by remember(service) { mutableStateOf(service?.currentPersona ?: DEFAULT_PERSONAS[0]) }
    val personaColor by animateColorAsState(targetValue = currentPersona.themeColor, animationSpec = tween(1000), label = "personaColor")

    val listState = rememberLazyListState()
    val miniScrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }

    val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        attachedFiles = (attachedFiles + uris).distinct()
    }

    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) attachedImage = uri
    }

    // Fix #12: every still-missing runtime permission must be requested through ONE
    // multi-permission launcher. Launching two single-permission requests back-to-back
    // on the same ActivityResultLauncher displaces the first request before its result
    // callback fires — the mic grant/deny result was lost and promoteToForeground()
    // (which depends on that result) never ran until the flow was manually retriggered.
    // READ_PHONE_STATE (proximity barge-in's call guard) joined this set, so its dialog is
    // part of the same single prompt rather than a second sequential request. It needs no
    // branch in the result handler below: only RECORD_AUDIO's grant drives
    // promoteToForeground(), and the service re-reads the call-state permission at every
    // trigger, so a later change (including a grant from Settings) needs no UI reaction.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Promote the service to foreground once RECORD_AUDIO is granted
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            service?.promoteToForeground()
        }
    }

    LaunchedEffect(service) {
        if (service != null) {
            if (service.cloudApis.value.isEmpty()) {
                DEFAULT_CLOUD_APIS.forEach { service.updateCloudApi(-1, it) }
            } else {
                // Migration: add any new default providers the user doesn't already have
                val existingNames = service.cloudApis.value.map { it.name }.toSet()
                DEFAULT_CLOUD_APIS.forEach { api ->
                    if (api.name !in existingNames) service.updateCloudApi(-1, api)
                }
            }
            if (service.personas.value.isEmpty()) {
                (DEFAULT_PERSONAS + CLOUD_PERSONAS + TRANSLATOR_PERSONA).forEach { service.addPersona(it) }
            } else {
                // Migration: add any new default personas the user doesn't already have
                val existingPersonaNames = service.personas.value.map { it.name }.toSet()
                (DEFAULT_PERSONAS + CLOUD_PERSONAS + TRANSLATOR_PERSONA).forEach { persona ->
                    if (persona.name !in existingPersonaNames) service.addPersona(persona)
                }
            }
            service.saveSettings()
            service.fetchModels()
            // Only seed the service when it has no selection of its own (fresh
            // service, first launch). On rebind after rotation the service already
            // holds the user's active persona — currentPersona was seeded from it
            // above — so an unconditional switch here silently reset the selection
            // (and swapped the visible history) on every recreation.
            if (service.currentPersona == null) {
                service.switchPersona(currentPersona)
            }
        }
    }

    LaunchedEffect(personaList) {
        if (personaList.isNotEmpty()) {
            // Resolve the persona to mirror via the service's own selection where
            // possible: the service re-points currentPersonaName when the active
            // persona is renamed, so a rename no longer misses the name lookup and
            // falls through to the personaList[0] fallback below (which silently
            // switched the user off the persona they were using). The fallback now
            // only fires when the active persona was genuinely deleted.
            val lookupName = service?.currentPersona?.name ?: currentPersona.name
            val updated = personaList.find { it.name == lookupName }
            if (updated != null) {
                currentPersona = updated
            } else {
                currentPersona = personaList[0]
                service?.switchPersona(personaList[0])
            }
        }
    }

    LaunchedEffect(messages.size, state, voiceDuration, streamingText, ttsPlaybackFraction, revealRestartRequest) {
        // Stage-1 streaming gate: while a Direct-Ollama stream is in flight, the
        // fake typewriter must NOT run — the overlay carries the progressive
        // text (shown in full as it arrives; the HUD/classic render it with a
        // typing cursor). Streaming completion re-triggers this effect via the
        // streamingText key change, and the normal TTS-synced reveal then runs
        // over the complete final text. Non-streaming paths (cloud/gateway)
        // never see a non-null streamingText → unchanged behavior.
        if (streamingText != null) {
            // During streaming: show ALL text (no fake reveal). The placeholder
            // in messages carries the accumulated content. Track the messageId
            // so after streaming completes, the final hash matches → no
            // re-reveal flash (the user just watched the real stream).
            messages.lastOrNull()?.let { streamingMsg ->
                if (streamingMsg.role == "assistant") {
                    revealedChars = Int.MAX_VALUE
                    val text = streamingMsg.text
                    lastAnimatedMessageId = "${currentPersona.name}_${messages.size}_${text.hashCode()}"
                }
            }
            return@LaunchedEffect
        }

        // Fix #3 (non-chunked replay): consume a pending reveal-restart request. A
        // replay of a device-voice message must re-run the duration-synced reveal the
        // first play got — otherwise the text just appears in full with no sync to the
        // replayed audio. The request is consumed once per token; the index it carries
        // must still address the last message, because only the last assistant bubble
        // is reveal-driven (older bubbles always render in full) — anything else would
        // animate an unrelated bubble.
        val restartRequest = revealRestartRequest
        if (restartRequest != null && restartRequest.token != consumedRevealToken) {
            consumedRevealToken = restartRequest.token
            if (restartRequest.messageIndex == messages.lastIndex) {
                revealRestartIndex = restartRequest.messageIndex
                revealRestartCount = messages.size
                revealRestartStarted = false
                // Bypass the "already animated" cache for this action: this message WAS
                // revealed when it first arrived, so without the reset the ID gate below
                // would pin the full text instantly and the replay would stay visually
                // silent.
                lastAnimatedMessageId = ""
            }
        }

        val lastMsg = messages.lastOrNull()
        if (lastMsg != null && lastMsg.role == "assistant") {
            val text = lastMsg.text
            if (text.isEmpty()) return@LaunchedEffect

            // Unique ID for the current message in this persona's history
            val messageId = "${currentPersona.name}_${messages.size}_${text.hashCode()}"

            // Stage-2 karaoke gate: while the chunked player drives this
            // response, the text is ALWAYS fully visible — the moving word
            // highlight is the only dynamic element (no typewriter). Pinning
            // revealedChars here also stops the 16ms ticker that used to re-run
            // the auto-scroll effect all through playback — the reported
            // "stuck at bottom" mini-box (scroll now fires only on content
            // arrival, never on the highlight).
            if (state == AssistantState.SPEAKING && ttsPlaybackFraction != null && voiceDuration > 0) {
                revealedChars = text.length
                lastAnimatedMessageId = messageId
                return@LaunchedEffect
            }

            // Fix #3: is a fresh replay reveal armed for THIS message? Both the index
            // and the list size captured when the request arrived must still describe
            // the current list, so a reshaped/cleared list (deletion, persona switch,
            // clear-chat) can never make it apply to a different message.
            val forcedReveal = revealRestartIndex == messages.lastIndex &&
                revealRestartCount == messages.size

            // If this message was already animated or we're loading history, show fully
            // instantly. A forced (replay) reveal deliberately skips BOTH halves of this
            // gate: this message was already animated on first play, and the device
            // engines walk through non-SPEAKING states around a replay (eSpeak is IDLE
            // while it synthesizes), so either check would pin the text and leave the
            // replay with no visual sync at all.
            if (!forcedReveal && (messageId == lastAnimatedMessageId || state == AssistantState.IDLE)) {
                revealedChars = text.length
                lastAnimatedMessageId = messageId
                return@LaunchedEffect
            }

            // Forced reveal: wait (briefly) for the engine to publish the replayed
            // audio's real duration, so the typing below is synced to what the user is
            // actually hearing rather than to the heuristic tick. The text stays as it
            // is during the wait (no flash to empty), and the wait is bounded — System
            // TTS never publishes a duration, so its replay reveals on the fallback
            // instead of staying hidden.
            if (forcedReveal && !revealRestartStarted) {
                var waited = 0L
                while (voiceDuration <= 0 && waited < ReplayRevealDurationWaitMs) {
                    delay(50)
                    waited += 50
                }
                revealRestartStarted = true
                revealedChars = 0
            }

            // New message: start from zero (unchanged). Forced reveal: start from zero
            // the first time for this action, then continue from the current position
            // if a later refinement re-runs this effect — a duration landing mid-reveal
            // then re-syncs the remaining text instead of snapping it back to empty.
            val startChars = if (forcedReveal) revealedChars.coerceIn(0, text.length) else 0
            if (!forcedReveal) revealedChars = 0
            lastAnimatedMessageId = messageId
            val startTime = System.currentTimeMillis()
            val remainingChars = text.length - startChars
            
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
                revealedChars = startChars + (remainingChars * progress).toInt()
                
                if (progress >= 1f) break
                if (currentVoiceDuration > 0 && state != AssistantState.SPEAKING && elapsed > 500) break
                
                delay(16)
            }
            revealedChars = text.length
            // Disarm once the reveal has run: later triggers for this message (the
            // engine's completion flipping the state, a duration refinement) go back
            // through the classic path above.
            if (forcedReveal) {
                revealRestartIndex = -1
                revealRestartCount = -1
            }
        } else {
            revealedChars = Int.MAX_VALUE
            // Reset tracker when no assistant message is present (e.g. cleared chat)
            if (messages.isEmpty()) lastAnimatedMessageId = ""
        }
    }

    // Auto-scroll logic: scrolls ONLY to show newly-arrived content, as it
    // always did — scroll to bottom / last item on message or stream updates.
    // Exception: while the chunked TTS karaoke is active, the mini box's scroll
    // is owned by the progress-driven teleprompter effect in the voice column.
    // Suppressing the bottom-jump here matters because the karaoke-start content
    // change (revealedChars pinned to full length) used to fire this effect and
    // slam the box to the tail — leaving the highlighted word off-screen at the
    // top for the whole response.
    LaunchedEffect(messages.size, revealedChars, textModeOpen, streamingText) {
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
            // Scroll the small transcription box in voice mode to newly-arrived
            // content — except while the chunked karaoke player is active, where
            // the teleprompter effect owns the position (see the voice column).
            val karaokeActive = state == AssistantState.SPEAKING && ttsPlaybackFraction != null
            if (!textModeOpen && !karaokeActive && miniScrollState.maxValue > 0) {
                miniScrollState.scrollTo(miniScrollState.maxValue)
            }
        }
    }

    LaunchedEffect(textModeOpen) { if (textModeOpen) focusRequester.requestFocus() }

    LaunchedEffect(Unit) {
        // Fix #12: collect every still-missing runtime permission and request them all
        // in a single launch (see the comment on permissionLauncher — two sequential
        // single-permission launches displace each other's results).
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            // Proximity barge-in's call guard (AssistantService.isPhoneCallActive): on API
            // 31+ the coarse call-state read is permission-gated for apps targeting 31 or
            // newer, so without this grant the guard silently never skips a wave-to-
            // interrupt during a real call. Declining is supported — the guard then sees
            // "no call", its documented pre-fix behaviour — so nothing blocks on the
            // result here. Requested on every launch where it is still missing, which also
            // gives a user who declined once a way back to it.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.READ_PHONE_STATE)
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    // Service still binding — first-run status isn't knowable yet (isFirstRun's
    // remember(service) can only resolve once the binder arrives). Hold on a blank
    // themed background instead of flashing the main UI before the Welcome flow
    // appears on first launch.
    if (service == null) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
        return
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
            // Persona backdrop: an app-owned copy of the chosen image (see
            // SettingsManager.savePersonaImage), drawn BEHIND the entire voice UI and
            // cropped to fill. It lives inside the content lambda rather than behind
            // the Scaffold because Scaffold paints its containerColor opaquely, which
            // would hide it. One layer serves both bodies: the celestial HUD's
            // starfield is a transparent Canvas, so the image shows through it too.
            Box(modifier = Modifier.fillMaxSize()) {
                currentPersona.backgroundImageUri?.takeIf { it.isNotBlank() }?.let { path ->
                    AsyncImage(
                        model = File(path),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Scrim: keeps the HUD's thin mono labels and the chat text legible
                    // over an arbitrary user photo, while leaving the image clearly
                    // visible underneath.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f))
                    )
                }
                // Celestial UI (Option-2 toggle, Settings > General > Appearance):
                // when enabled AND in voice mode, the classic body below is replaced
                // by the HUD voice screen (starfield + planet + HUD transcript).
                // Text mode ALWAYS renders the unchanged classic ControlBar (chat
                // list + input), and the classic body is byte-identical when the
                // toggle is off — full runtime reversibility.
                val celestialActive = celestialUi && !textModeOpen
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .imePadding(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Update banner: quiet, dismissible, only present when the service's
                    // throttled GitHub check found a newer version (see checkForAppUpdate).
                    updateInfo?.let { info ->
                        UpdateBanner(
                            info = info,
                            onView = {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.url)))
                                }
                            },
                            onDismiss = { service?.dismissUpdate(info.version) }
                        )
                    }
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

                    if (celestialActive) {
                        CelestialHudBody(
                            state = state,
                            personaColor = personaColor,
                            voiceDuration = voiceDuration,
                            muted = muted,
                            silenced = silenced,
                            handsFreeMode = handsFreeMode,
                            sessionUsage = sessionUsage,
                            messages = messages,
                            revealedChars = revealedChars,
                            streamingText = streamingText,
                            micAmplitude = micAmplitude,
                            ttsPlaybackFraction = ttsPlaybackFraction,
                            ttsWordTimestamps = ttsWordTimestamps,
                            onMicClick = {
                                if (state == AssistantState.IDLE) service?.startRecording()
                                else if (state == AssistantState.LISTENING) service?.stopRecording(currentPersona)
                            },
                            onStopClick = { service?.stopEverything() },
                            onTextModeToggle = { textModeOpen = !textModeOpen },
                            onMuteToggle = { service?.toggleMicMute() },
                            onSilenceToggle = { service?.toggleSilence() },
                            onHandsFreeToggle = {
                                if (service?.handsFreeMode?.value == true) service.stopVadListening()
                                else service?.startVadListening()
                            }
                        )
                    } else {
                        ControlBar(
                        textModeOpen = textModeOpen,
                        textInput = textInput,
                        onTextInputChange = { textInput = it },
                        attachedFiles = attachedFiles,
                        attachedImage = attachedImage,
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
                        onImageAttachClick = { imageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onRemoveAttachment = { attachedFiles = attachedFiles - it },
                        onRemoveImage = { attachedImage = null },
                        onSendClick = {
                            // Defensive copy: the composition's attachedFiles is a
                            // state-backed mutable list. We snapshot it BEFORE clearing the
                            // UI, so the synchronous `attachedFiles = emptyList()` below
                            // can't leave the in-flight send reading an emptied list.
                            val filesToSend = attachedFiles.toList()
                            val imgToSend = attachedImage
                            service?.sendTextMessageToServer(textInput, currentPersona, filesToSend, imgToSend)
                            textInput = ""
                            attachedFiles = emptyList()
                            attachedImage = null
                        },
                        onMicClick = {
                            if (state == AssistantState.IDLE) service?.startRecording()
                            else if (state == AssistantState.LISTENING) service?.stopRecording(currentPersona)
                        },
                        onStopClick = { service?.stopEverything() },
                        state = state,
                        personaColor = personaColor,
                        personaName = currentPersona.name,
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
                        streamingText = streamingText,
                        ttsPlaybackFraction = ttsPlaybackFraction,
                        ttsWordTimestamps = ttsWordTimestamps,
                        voiceDuration = voiceDuration,
                        miniScrollState = miniScrollState,
                        listState = listState,
                        onEditMessage = { idx, txt -> service?.updateMessage(idx, txt) },
                        onDeleteMessage = { idx -> service?.deleteMessage(idx) },
                        onReplayAudio = { msg -> service?.replayMessageAudio(msg, currentPersona) }
                        )
                    } // end celestial-if/else (HUD body vs classic ControlBar)
                }
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
                ProviderLogo(icon = currentPersona.providerIcon, isCloud = currentPersona.isCloud, imagePath = currentPersona.iconImageUri)
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
    attachedImage: Uri?,
    onAttachClick: () -> Unit,
    onImageAttachClick: () -> Unit,
    onRemoveAttachment: (Uri) -> Unit,
    onRemoveImage: () -> Unit,
    onSendClick: () -> Unit,
    onMicClick: () -> Unit,
    onStopClick: () -> Unit,
    state: AssistantState,
    personaColor: Color,
    // Persona NAME (not just the color) so the mini box can derive the same
    // "{persona}_{messageCount}_{textHash}" identity the reveal effect uses — the
    // teleprompter re-checks the anchored bubble against it before measuring it.
    personaName: String,
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
    // Stage-1 streaming overlay: non-null while a Direct-Ollama stream is in
    // flight. Rendered as a trailing in-progress bubble with a typing cursor
    // in the voice-mode mini box; text mode streams into the chat list the
    // same way. A non-blank value also suppresses the "..." dots below
    // (Fix #4) — once real text is on screen the dots must not sit under it.
    streamingText: String?,
    ttsPlaybackFraction: Float?,
    ttsWordTimestamps: String?,
    voiceDuration: Int,
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
                streamingText = streamingText,
                ttsPlaybackFraction = ttsPlaybackFraction,
                ttsWordTimestamps = ttsWordTimestamps,
                voiceDuration = voiceDuration,
                onEditMessage = onEditMessage,
                onDeleteMessage = onDeleteMessage,
                onReplayAudio = onReplayAudio
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (attachedFiles.isNotEmpty() || attachedImage != null) {
                Row(modifier = Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    attachedFiles.forEach { uri ->
                        Box(modifier = Modifier.size(50.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))) {
                            Icon(Icons.Default.AttachFile, null, modifier = Modifier.align(Alignment.Center))
                            IconButton(onClick = { onRemoveAttachment(uri) }, modifier = Modifier.size(16.dp).align(Alignment.TopEnd)) {
                                Icon(Icons.Default.Close, null, modifier = Modifier.size(10.dp))
                            }
                        }
                    }
                    attachedImage?.let { uri ->
                        Box(modifier = Modifier.size(50.dp).clip(MaterialTheme.shapes.small)) {
                            AsyncImage(
                                model = uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            IconButton(onClick = onRemoveImage, modifier = Modifier.size(16.dp).align(Alignment.TopEnd).background(Color.Black.copy(alpha = 0.5f), CircleShape)) {
                                Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(10.dp))
                            }
                        }
                    }
                }
            }
            
            TextInputRow(
                textInput = textInput,
                onTextInputChange = onTextInputChange,
                onAttachClick = onAttachClick,
                onImageAttachClick = onImageAttachClick,
                onSendClick = onSendClick,
                onMicClick = onMicClick,
                onStopClick = onStopClick,
                state = state,
                personaColor = personaColor,
                focusRequester = focusRequester,
                attachedFiles = attachedFiles,
                attachedImage = attachedImage
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

                // Stage-2 auto-teleprompter: while the chunked TTS karaoke is
                // active, the mini box tracks the spoken word instead of staying
                // pinned at the bottom (the old arrival-only scroll left the
                // highlighted word off-screen at the top until it happened to
                // reach the visible tail). The highlight lives inside the LAST
                // assistant bubble, so the target is derived from that bubble's
                // measured pixel range — NOT from the whole scrollable content,
                // which may start with older history. ~⅓ of the viewport is kept
                // above the tracked position as lead so the active line never
                // hugs the top fade. Plain scrollTo (no animation): fraction
                // emissions land every ~150 ms, each step is a few pixels, and
                // isScrollInProgress stays reserved for real user drags (which
                // briefly win over the auto-track).
                // Layout refs are a plain holder, NOT state: the position
                // callbacks fire on every scroll frame and storing them in
                // mutableStateOf would recompose the whole voice column ~60×/s.
                val layoutRefs = remember { LayoutRefHolder() }
                LaunchedEffect(ttsPlaybackFraction) {
                    val f = ttsPlaybackFraction ?: return@LaunchedEffect
                    if (state != AssistantState.SPEAKING) return@LaunchedEffect
                    if (miniScrollState.isScrollInProgress) return@LaunchedEffect
                    if (miniScrollState.maxValue <= 0) return@LaunchedEffect
                    val container = layoutRefs.container ?: return@LaunchedEffect
                    val bubble = layoutRefs.bubble ?: return@LaunchedEffect
                    // Identity gate (stale-ref hardening): the refs are captured by
                    // onGloballyPositioned and never invalidated, so after a persona
                    // switch or clear-chat they can still point at the PREVIOUS
                    // message list's nodes. Recompute the identity of the CURRENT last
                    // assistant message (reading messages/currentPersona here reads
                    // the values as of this emission, not as of effect launch) and bail
                    // out when the bubble ref no longer corresponds to it. Trusting a
                    // stale pair measures a detached node: localPositionOf then falls
                    // back to findCommonAncestor and, when the two nodes were detached
                    // in different chains, throws
                    // IllegalArgumentException("layouts are not part of the same
                    // hierarchy") — an uncaught crash inside a layout callback, not
                    // merely a wrong scroll position.
                    val lastMessage = messages.lastOrNull()
                    val expectedBubbleMessageId =
                        if (lastMessage != null && lastMessage.role == "assistant") {
                            "${personaName}_${messages.size}_${lastMessage.text.hashCode()}"
                        } else null
                    if (expectedBubbleMessageId == null ||
                        layoutRefs.bubbleMessageId != expectedBubbleMessageId
                    ) return@LaunchedEffect
                    // Belt-and-braces on top of the identity gate above (#2 of the
                    // last sweep, deliberately cheap): a ref can still go stale
                    // BETWEEN the gate's read and the measurement below — the
                    // fraction emission that launched this effect can already have
                    // been in flight when a clear-chat/persona switch published a
                    // new list, and the anchor's node is removed during the layout
                    // pass that follows. LayoutCoordinates.isAttached is the
                    // coordinates' own liveness flag (O(1), no hierarchy walk);
                    // measuring detached coordinates is exactly what
                    // localPositionOf cannot do safely (it falls back to
                    // findCommonAncestor and throws for nodes detached in
                    // different chains — an uncaught crash inside a
                    // composition-controlled coroutine). Bail out instead: the next
                    // fraction emission (~150 ms later) re-runs the full gate
                    // against whatever is attached then, so a bail costs at most
                    // one scroll step, never the highlight overall.
                    if (!container.isAttached || !bubble.isAttached) return@LaunchedEffect
                    // Bubble top in CONTENT space: both refs measure inside the
                    // scroll's coordinate space, so localPositionOf returns the
                    // position in the scrollable content — the scroll offset is
                    // NOT subtracted (adding it double-counts and overshoots
                    // maxValue, pinning the scroll at the bottom).
                    val bubbleTop = container.localPositionOf(bubble, Offset.Zero).y
                    val bubbleHeight = bubble.size.height.toFloat()
                    // Real visible viewport height: the ref BEFORE verticalScroll
                    // (a ref after it measures the unbounded content height).
                    val viewport = (layoutRefs.viewport?.size?.height
                        ?: (container.size.height - miniScrollState.maxValue)).toFloat().coerceAtLeast(1f)
                    val trackedY = bubbleTop + f * bubbleHeight
                    val target = (trackedY - viewport / 3f).roundToInt().coerceIn(0, miniScrollState.maxValue)
                    miniScrollState.scrollTo(target)
                }

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
                            .onGloballyPositioned { layoutRefs.viewport = it }
                            .verticalScroll(miniScrollState)
                            .onGloballyPositioned { layoutRefs.container = it }) {

                            messages.forEachIndexed { index, msg ->
                                val isLastAssistant = index == messages.size - 1 && msg.role == "assistant"
                                // Stage-2 karaoke: during chunked playback the text is
                                // ALWAYS fully visible — the sync engine positions the
                                // highlight on the word currently being spoken (persona
                                // color). The classic typewriter only drives non-chunked
                                // paths (eSpeak/System TTS / no player fraction).
                                val chunkedKaraoke = isLastAssistant && state == AssistantState.SPEAKING &&
                                    ttsPlaybackFraction != null && voiceDuration > 0
                                val displayText = if (isLastAssistant && !chunkedKaraoke) {
                                    msg.text.take(revealedChars)
                                } else msg.text
                                val activeFraction = ttsPlaybackFraction
                                val highlightRange = if (chunkedKaraoke) {
                                    ttsActiveWordRange(msg.text, ttsWordTimestamps, activeFraction!!, voiceDuration)
                                } else null
                                // Identity recorded alongside the teleprompter anchor below,
                                // using the same "{persona}_{messageCount}_{textHash}" pattern
                                // the reveal effect uses for the last assistant message. The
                                // teleprompter re-checks it before trusting the refs, so a
                                // ref captured for what is no longer the last assistant
                                // message (persona switch / clear-chat / list edit) can never
                                // be measured. Computed here (once per recomposition, not per
                                // placement) so the onGloballyPositioned lambda does no
                                // string work on the scroll-time layout pass.
                                val anchorMessageId = if (isLastAssistant) {
                                    "${personaName}_${messages.size}_${msg.text.hashCode()}"
                                } else null
                                
                                ChatMessageBubble(
                                    message = msg,
                                    displayText = displayText,
                                    highlightRange = highlightRange,
                                    highlightColor = personaColor,
                                    personaColor = personaColor,
                                    isCompact = true,
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = if (isLastAssistant) {
                                        // Anchor for the auto-teleprompter: the
                                        // spoken response bubble's pixel range.
                                        Modifier.fillMaxWidth()
                                            .onGloballyPositioned {
                                                layoutRefs.bubble = it
                                                layoutRefs.bubbleMessageId = anchorMessageId
                                            }
                                    } else {
                                        Modifier.fillMaxWidth()
                                    }
                                )
                            }
                            // Fix #4: the Direct-Ollama stream keeps state == THINKING
                            // for its ENTIRE duration, while the accumulated stream
                            // text is already rendered as the trailing placeholder
                            // message in `messages` (see the note below) — so the old
                            // unconditional dots sat underneath the growing response
                            // for the whole generation. Gate them on there being no
                            // visible assistant content yet: a non-blank streamingText
                            // only exists once the placeholder has been appended and
                            // populated (published in the same loop iteration, right
                            // after updatePlaceholder()), so the dots yield to it.
                            // Thinking-only chunks (persona.enableThinking) leave
                            // streamingText blank, so the dots correctly persist until
                            // real answer text begins.
                            if (state == AssistantState.THINKING && streamingText.isNullOrBlank()) {
                                Text("...", color = personaColor.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            }
                            // Stage-1 streaming: the placeholder message in
                            // messages already carries the accumulated stream
                            // content (updated per chunk by the NDJSON loop) -
                            // no separate overlay needed here (it would double-
                            // render the same text).
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

@Composable
fun TextInputRow(
    textInput: String,
    onTextInputChange: (String) -> Unit,
    onAttachClick: () -> Unit,
    onImageAttachClick: () -> Unit,
    onSendClick: () -> Unit,
    onMicClick: () -> Unit,
    onStopClick: () -> Unit,
    state: AssistantState,
    personaColor: Color,
    focusRequester: FocusRequester,
    attachedFiles: List<Uri>,
    attachedImage: Uri?
) {
    val hasContent = textInput.isNotBlank() || attachedFiles.isNotEmpty() || attachedImage != null
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
                Row {
                    IconButton(onClick = onAttachClick, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Add, 
                            null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onImageAttachClick, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.Image, 
                            null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
